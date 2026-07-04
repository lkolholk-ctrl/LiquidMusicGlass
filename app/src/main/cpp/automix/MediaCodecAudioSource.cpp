#include "MediaCodecAudioSource.h"
#include "OboeRuntime.h"

#include <fcntl.h>
#include <unistd.h>
#include <sys/stat.h>

#include <cstdio>
#include <cstring>
#include <algorithm>

namespace
{
    const char* pcmEncodingName (int32_t enc)
    {
        switch (enc)
        {
            case 2:  return "i16";
            case 3:  return "i8";
            case 4:  return "float";
            case 21: return "i24";
            case 22: return "i32";
            default: return "?";
        }
    }
}

namespace automix
{

std::unique_ptr<MediaCodecAudioSource> MediaCodecAudioSource::create (const juce::String& path)
{
    std::unique_ptr<MediaCodecAudioSource> s (new MediaCodecAudioSource());
    if (! s->openFile (path))
        return nullptr;
    return s;
}

std::unique_ptr<MediaCodecAudioSource> MediaCodecAudioSource::createFromFd (int srcFd, int64_t offset, int64_t size)
{
    std::unique_ptr<MediaCodecAudioSource> s (new MediaCodecAudioSource());
    if (! s->openFd (srcFd, offset, size))
        return nullptr;
    return s;
}

MediaCodecAudioSource::~MediaCodecAudioSource()
{
    closeCodec();
    if (extractor != nullptr) { AMediaExtractor_delete (extractor); extractor = nullptr; }
    if (fd >= 0)              { ::close (fd); fd = -1; }
}

void MediaCodecAudioSource::closeCodec()
{
    if (codec != nullptr)
    {
        AMediaCodec_stop (codec);
        AMediaCodec_delete (codec);
        codec = nullptr;
    }
}

bool MediaCodecAudioSource::openFile (const juce::String& path)
{
    fd = ::open (path.toRawUTF8(), O_RDONLY);
    if (fd < 0)
        return false;

    struct stat st {};
    if (::fstat (fd, &st) != 0 || st.st_size <= 0)
        return false;

    extractor = AMediaExtractor_new();
    if (AMediaExtractor_setDataSourceFd (extractor, fd, 0, (off64_t) st.st_size) != AMEDIA_OK)
        return false;

    return configureFromExtractor();
}

bool MediaCodecAudioSource::openFd (int srcFd, int64_t offset, int64_t size)
{
    // dup so OUR lifetime is independent of the caller's ParcelFileDescriptor.
    fd = ::dup (srcFd);
    if (fd < 0)
        return false;

    if (size <= 0)
    {
        struct stat st {};
        if (::fstat (fd, &st) == 0 && st.st_size > 0)
            size = (int64_t) st.st_size;
    }
    if (size <= 0)
        return false;

    extractor = AMediaExtractor_new();
    if (AMediaExtractor_setDataSourceFd (extractor, fd, (off64_t) offset, (off64_t) size) != AMEDIA_OK)
        return false;

    return configureFromExtractor();
}

bool MediaCodecAudioSource::configureFromExtractor()
{
    const int trackCount = (int) AMediaExtractor_getTrackCount (extractor);
    int audioTrack = -1;
    AMediaFormat* format = nullptr;
    const char* mime = nullptr;

    for (int i = 0; i < trackCount; ++i)
    {
        AMediaFormat* f = AMediaExtractor_getTrackFormat (extractor, i);
        const char* m = nullptr;
        if (AMediaFormat_getString (f, AMEDIAFORMAT_KEY_MIME, &m) && m != nullptr
            && std::strncmp (m, "audio/", 6) == 0)
        {
            audioTrack = i;
            format = f;
            mime = m;
            break;
        }
        AMediaFormat_delete (f);
    }

    if (audioTrack < 0 || format == nullptr)
        return false;

    AMediaExtractor_selectTrack (extractor, audioTrack);
    audioTrackIndex = audioTrack;

    int32_t sr = 0, ch = 0;
    AMediaFormat_getInt32 (format, AMEDIAFORMAT_KEY_SAMPLE_RATE, &sr);
    AMediaFormat_getInt32 (format, AMEDIAFORMAT_KEY_CHANNEL_COUNT, &ch);
    if (sr > 0) sampleRate  = (double) sr;
    outChannels = (ch > 0) ? ch : 2;
    compactThresholdFrames = (size_t) juce::jmax (65536, (int) (sampleRate * 5.0));
    const size_t reserveFrames = (size_t) juce::jmax ((int) compactThresholdFrames,
                                                      (int) (sampleRate * 8.0));
    leftBuf.reserve (reserveFrames);
    rightBuf.reserve (reserveFrames);

    int64_t durationUs = 0;
    if (AMediaFormat_getInt64 (format, AMEDIAFORMAT_KEY_DURATION, &durationUs) && durationUs > 0)
        totalFrames = (juce::int64) ((double) durationUs * sampleRate / 1.0e6);

    codec = AMediaCodec_createDecoderByType (mime);
    const bool ok = codec != nullptr
        && AMediaCodec_configure (codec, format, nullptr, nullptr, 0) == AMEDIA_OK
        && AMediaCodec_start (codec) == AMEDIA_OK;

    AMediaFormat_delete (format);

    // Фактический ВЫХОДНОЙ формат кодека сразу после старта: часть OEM-кодеков
    // (vivo) отдаёт 24/32-бит вместо 16, и rate/каналы могут отличаться от
    // контейнерных. Читаем ДО того, как транспорт возьмёт getSampleRate().
    if (ok)
        refreshOutputFormat();
    return ok;
}

// Пересоздать декодер после фатальной ошибки (codec died / reclaimed системой)
// и продолжить с первого ещё не отданного кадра. Возвращает false, если
// восстановиться нельзя — тогда источник помечается EOS.
bool MediaCodecAudioSource::recreateCodec()
{
    closeCodec();
    if (extractor == nullptr || audioTrackIndex < 0)
        return false;

    AMediaFormat* format = AMediaExtractor_getTrackFormat (extractor, (size_t) audioTrackIndex);
    if (format == nullptr)
        return false;

    const char* mime = nullptr;
    if (! AMediaFormat_getString (format, AMEDIAFORMAT_KEY_MIME, &mime) || mime == nullptr)
    {
        AMediaFormat_delete (format);
        return false;
    }

    codec = AMediaCodec_createDecoderByType (mime);
    const bool ok = codec != nullptr
        && AMediaCodec_configure (codec, format, nullptr, nullptr, 0) == AMEDIA_OK
        && AMediaCodec_start (codec) == AMEDIA_OK;
    AMediaFormat_delete (format);

    if (! ok)
    {
        closeCodec();
        return false;
    }

    outputFormatKnown = false;   // новый кодек может отдать другой формат
    refreshOutputFormat();

    // Экстрактор мог уйти вперёд кадрами, которые погибший кодек так и не отдал —
    // вернём его к первому НЕ выданному кадру (readPos + буферизованный остаток).
    const juce::int64 resumeFrame = readPos + (juce::int64) (leftBuf.size() - leftoverStart);
    const int64_t resumeUs = (int64_t) ((double) resumeFrame / sampleRate * 1.0e6);
    AMediaExtractor_seekTo (extractor, resumeUs, AMEDIAEXTRACTOR_SEEK_CLOSEST_SYNC);
    inputEOS = false;
    return true;
}

// Bulk-append: один resize на выходной буфер кодека и запись по указателям.
// push_back на каждый сэмпл (проверка capacity + вызов на фрейм) заметно грел
// декод-поток на длинных предчтениях; здесь это плоский проход по памяти.
void MediaCodecAudioSource::push8 (const uint8_t* s, int totalSamples)
{
    // Android ENCODING_PCM_8BIT — БЕЗзнаковый, центр 128. Раньше кейса не было
    // и 8-бит поток шёл в push16 → «песок» (читались int16 из 8-бит байтов).
    const int ch = outChannels > 0 ? outChannels : 2;
    const int frames = totalSamples / ch;
    if (frames <= 0)
        return;

    constexpr float kScale = 1.0f / 128.0f;
    const size_t base = leftBuf.size();
    leftBuf.resize  (base + (size_t) frames);
    rightBuf.resize (base + (size_t) frames);
    float* l = leftBuf.data()  + base;
    float* r = rightBuf.data() + base;

    if (ch == 1)
        for (int fr = 0; fr < frames; ++fr)
        {
            const float v = ((float) s[fr] - 128.0f) * kScale;
            l[fr] = v; r[fr] = v;
        }
    else
        for (int fr = 0; fr < frames; ++fr)
        {
            l[fr] = ((float) s[fr * ch + 0] - 128.0f) * kScale;
            r[fr] = ((float) s[fr * ch + 1] - 128.0f) * kScale;
        }
}

void MediaCodecAudioSource::push16 (const int16_t* s, int totalSamples)
{
    const int ch = outChannels > 0 ? outChannels : 2;
    const int frames = totalSamples / ch;
    if (frames <= 0)
        return;

    constexpr float kScale = 1.0f / 32768.0f;
    const size_t base = leftBuf.size();
    leftBuf.resize  (base + (size_t) frames);
    rightBuf.resize (base + (size_t) frames);
    float* l = leftBuf.data()  + base;
    float* r = rightBuf.data() + base;

    if (ch == 1)
        for (int fr = 0; fr < frames; ++fr)
        {
            const float v = (float) s[fr] * kScale;
            l[fr] = v;
            r[fr] = v;
        }
    else
        for (int fr = 0; fr < frames; ++fr)
        {
            l[fr] = (float) s[fr * ch + 0] * kScale;
            r[fr] = (float) s[fr * ch + 1] * kScale;
        }
}

// 24-бит packed (3 байта/сэмпл, little-endian, знаковый) — так отдают звук
// аппаратные декодеры части вендоров (vivo и др.). Чтение как int16 даёт
// «шипение с прорывающейся музыкой» — байты уходят со сдвигом.
void MediaCodecAudioSource::push24 (const uint8_t* s, int totalSamples)
{
    const int ch = outChannels > 0 ? outChannels : 2;
    const int frames = totalSamples / ch;
    if (frames <= 0)
        return;

    constexpr float kScale = 1.0f / 8388608.0f;   // 2^23
    const size_t base = leftBuf.size();
    leftBuf.resize  (base + (size_t) frames);
    rightBuf.resize (base + (size_t) frames);
    float* l = leftBuf.data()  + base;
    float* r = rightBuf.data() + base;

    auto sampleAt = [s] (int index) noexcept -> float
    {
        const uint8_t* p = s + index * 3;
        int32_t v = (int32_t) p[0] | ((int32_t) p[1] << 8) | ((int32_t) p[2] << 16);
        if (v & 0x800000)
            v -= 0x1000000;                        // sign-extend 24 -> 32
        return (float) v;
    };

    if (ch == 1)
        for (int fr = 0; fr < frames; ++fr)
        {
            const float v = sampleAt (fr) * kScale;
            l[fr] = v;
            r[fr] = v;
        }
    else
        for (int fr = 0; fr < frames; ++fr)
        {
            l[fr] = sampleAt (fr * ch + 0) * kScale;
            r[fr] = sampleAt (fr * ch + 1) * kScale;
        }
}

void MediaCodecAudioSource::push32 (const int32_t* s, int totalSamples)
{
    const int ch = outChannels > 0 ? outChannels : 2;
    const int frames = totalSamples / ch;
    if (frames <= 0)
        return;

    constexpr float kScale = 1.0f / 2147483648.0f; // 2^31
    const size_t base = leftBuf.size();
    leftBuf.resize  (base + (size_t) frames);
    rightBuf.resize (base + (size_t) frames);
    float* l = leftBuf.data()  + base;
    float* r = rightBuf.data() + base;

    if (ch == 1)
        for (int fr = 0; fr < frames; ++fr)
        {
            const float v = (float) s[fr] * kScale;
            l[fr] = v;
            r[fr] = v;
        }
    else
        for (int fr = 0; fr < frames; ++fr)
        {
            l[fr] = (float) s[fr * ch + 0] * kScale;
            r[fr] = (float) s[fr * ch + 1] * kScale;
        }
}

void MediaCodecAudioSource::pushF (const float* s, int totalSamples)
{
    const int ch = outChannels > 0 ? outChannels : 2;
    const int frames = totalSamples / ch;
    if (frames <= 0)
        return;

    const size_t base = leftBuf.size();
    leftBuf.resize  (base + (size_t) frames);
    rightBuf.resize (base + (size_t) frames);
    float* l = leftBuf.data()  + base;
    float* r = rightBuf.data() + base;

    if (ch == 1)
        for (int fr = 0; fr < frames; ++fr)
        {
            const float v = s[fr];
            l[fr] = v;
            r[fr] = v;
        }
    else
        for (int fr = 0; fr < frames; ++fr)
        {
            l[fr] = s[fr * ch + 0];
            r[fr] = s[fr * ch + 1];
        }
}

// Диспетчер по фактическому pcmEncoding кодека. По умолчанию (2/неизвестно) — 16-бит.
void MediaCodecAudioSource::pushDecoded (const uint8_t* data, int sizeBytes)
{
    switch (pcmEncoding)
    {
        case 3:  push8  (data,                                    sizeBytes);                          break;
        case 4:  pushF  (reinterpret_cast<const float*>   (data), sizeBytes / (int) sizeof (float));   break;
        case 21: push24 (data,                                    sizeBytes / 3);                      break;
        case 22: push32 (reinterpret_cast<const int32_t*> (data), sizeBytes / (int) sizeof (int32_t)); break;
        default: push16 (reinterpret_cast<const int16_t*> (data), sizeBytes / (int) sizeof (int16_t)); break;
    }
}

void MediaCodecAudioSource::refreshOutputFormat()
{
    if (codec == nullptr)
        return;
    AMediaFormat* of = AMediaCodec_getOutputFormat (codec);
    if (of == nullptr)
        return;

    int32_t v = 0;
    if (AMediaFormat_getInt32 (of, AMEDIAFORMAT_KEY_SAMPLE_RATE,   &v) && v > 0) sampleRate  = (double) v;
    if (AMediaFormat_getInt32 (of, AMEDIAFORMAT_KEY_CHANNEL_COUNT, &v) && v > 0) outChannels = v;
    if (AMediaFormat_getInt32 (of, AMEDIAFORMAT_KEY_PCM_ENCODING,  &v) && v > 0) pcmEncoding = v;
    AMediaFormat_delete (of);
    outputFormatKnown = true;

    char info[96];
    std::snprintf (info, sizeof (info), "codec out: enc=%d(%s) rate=%d ch=%d",
                   (int) pcmEncoding, pcmEncodingName (pcmEncoding), (int) sampleRate, outChannels);
    automix::setLastCodecInfo (info);
}

void MediaCodecAudioSource::fillLeftover (int framesWanted)
{
    // Защита ТОЛЬКО от вечного цикла под lock'ом (dead codec → dequeue* вечно
    // возвращает коды, не покрытые ветками ниже). БЕЗОПАСНАЯ версия: при любом
    // аномальном статусе НЕ помечаем EOS и НЕ пересоздаём/сеемся — просто не
    // считаем это прогрессом, и по счётчику noProgressIters выходим из цикла,
    // отдав что успели (транспорт дольёт тишину, следующий pull повторит).
    // Прежняя агрессивная версия (recreateCodec + ложный EOS) давала seek и
    // ложный «конец трека» на ~3с → тишина и петля на локальном воспроизведении.
    int noProgressIters = 0;
    constexpr int kMaxNoProgressIters = 500;   // ~2с; выход БЕЗ EOS — безвредно
    // Кодек мог быть отобран системой за часы фоновой игры (mediaserver reclaim).
    // Тогда dequeue вечно молчит и дека немеет НАВСЕГДА. Одна попытка пересоздать
    // декодер на эпизод залипания (бюджет сбрасывается при реальном прогрессе) —
    // возвращает звук, не реанимируя прежнюю петлю с ложным EOS.
    int recreateBudget = 1;

    while (! outputEOS && (int) (leftBuf.size() - leftoverStart) < framesWanted)
    {
        bool progressed = false;

        if (! inputEOS)
        {
            const ssize_t inIdx = AMediaCodec_dequeueInputBuffer (codec, 2000);
            if (inIdx >= 0)
            {
                size_t bufSize = 0;
                uint8_t* inBuf = AMediaCodec_getInputBuffer (codec, (size_t) inIdx, &bufSize);
                const ssize_t sampleSize = (inBuf != nullptr)
                    ? AMediaExtractor_readSampleData (extractor, inBuf, bufSize) : -1;

                if (sampleSize < 0)
                {
                    AMediaCodec_queueInputBuffer (codec, (size_t) inIdx, 0, 0, 0,
                                                  AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM);
                    inputEOS = true;
                }
                else
                {
                    const int64_t pts = AMediaExtractor_getSampleTime (extractor);
                    AMediaCodec_queueInputBuffer (codec, (size_t) inIdx, 0, (size_t) sampleSize, pts, 0);
                    AMediaExtractor_advance (extractor);
                }
                progressed = true;
            }
            // Прочие статусы input (в т.ч. аномальный отрицательный) — НЕ фатально:
            // не сеемся, не EOS. Отсутствие прогресса поймает счётчик ниже.
        }

        AMediaCodecBufferInfo info;
        const ssize_t outIdx = AMediaCodec_dequeueOutputBuffer (codec, &info, 2000);
        if (outIdx >= 0)
        {
            size_t outSize = 0;
            uint8_t* outBuf = AMediaCodec_getOutputBuffer (codec, (size_t) outIdx, &outSize);
            if (outBuf != nullptr && info.size > 0)
            {
                // Страховка: если FORMAT_CHANGED так и не пришёл до первого буфера
                // (бывает на OEM-кодеках) — читаем фактический формат прямо здесь,
                // до интерпретации байтов.
                if (! outputFormatKnown)
                    refreshOutputFormat();
                pushDecoded (outBuf + info.offset, (int) info.size);
            }

            AMediaCodec_releaseOutputBuffer (codec, (size_t) outIdx, false);
            if ((info.flags & AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM) != 0)
                outputEOS = true;
            progressed = true;
        }
        else if (outIdx == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED)
        {
            refreshOutputFormat();
            progressed = true;
        }
        else if (outIdx == AMEDIACODEC_INFO_OUTPUT_BUFFERS_CHANGED)
        {
            progressed = true;   // легитимное событие, не ошибка
        }
        else if (outIdx == AMEDIACODEC_INFO_TRY_AGAIN_LATER)
        {
            // Пока нечего отдавать. Если вход исчерпан, а кодек молчит — выходим
            // (это законный конец потока).
            if (inputEOS)
                break;
        }
        // Любой другой (аномальный отрицательный) статус — НЕ фатально: не EOS,
        // не пересоздаём. noProgressIters ниже выведет из цикла.

        if (progressed)
        {
            noProgressIters = 0;
            recreateBudget = 1;   // живой кодек — бюджет восстановления обновляем
        }
        else if (++noProgressIters >= kMaxNoProgressIters)
        {
            // Кодек заглох (вход не исчерпан, а выхода нет ~2с) — вероятно
            // reclaim системой. Одна попытка пересоздать и продолжить с того
            // же кадра; если не вышло — выходим БЕЗ EOS (транспорт дольёт
            // тишину, следующий pull повторит), как раньше.
            if (recreateBudget > 0 && ! inputEOS && recreateCodec())
            {
                --recreateBudget;
                noProgressIters = 0;
                continue;
            }
            break;
        }
    }
}

void MediaCodecAudioSource::getNextAudioBlock (const juce::AudioSourceChannelInfo& info)
{
    float* outL = info.buffer->getWritePointer (0, info.startSample);
    float* outR = (info.buffer->getNumChannels() > 1)
                    ? info.buffer->getWritePointer (1, info.startSample) : nullptr;

    const int need = info.numSamples;
    int produced = 0;

    const juce::ScopedLock sl (lock);

    while (produced < need)
    {
        int avail = (int) (leftBuf.size() - leftoverStart);
        if (avail <= 0)
        {
            if (outputEOS) break;
            fillLeftover (need - produced);
            avail = (int) (leftBuf.size() - leftoverStart);
            if (avail <= 0) break;   // EOS or codec stall — stop, silence-fill below
        }

        const int take = std::min (avail, need - produced);
        const float* srcL = leftBuf.data()  + leftoverStart;
        const float* srcR = rightBuf.data() + leftoverStart;
        for (int i = 0; i < take; ++i)
        {
            outL[produced + i] = srcL[i];
            if (outR != nullptr) outR[produced + i] = srcR[i];
        }
        leftoverStart += (size_t) take;
        produced      += take;
        readPos       += take;
    }

    // Silence-fill any remainder past end-of-stream.
    for (int i = produced; i < need; ++i)
    {
        outL[i] = 0.0f;
        if (outR != nullptr) outR[i] = 0.0f;
    }

    // Compact consumed prefix so the buffers don't grow without bound.
    if (leftoverStart > compactThresholdFrames)
    {
        leftBuf.erase  (leftBuf.begin(),  leftBuf.begin()  + (long) leftoverStart);
        rightBuf.erase (rightBuf.begin(), rightBuf.begin() + (long) leftoverStart);
        leftoverStart = 0;
    }
}

void MediaCodecAudioSource::setNextReadPosition (juce::int64 newPosition)
{
    const juce::ScopedLock sl (lock);

    juce::int64 target = newPosition < 0 ? 0 : newPosition;
    if (totalFrames > 0 && target > totalFrames)
        target = totalFrames;

    const int64_t targetUs = (int64_t) ((double) target / sampleRate * 1.0e6);
    AMediaExtractor_seekTo (extractor, targetUs, AMEDIAEXTRACTOR_SEEK_CLOSEST_SYNC);
    if (codec != nullptr)
        AMediaCodec_flush (codec);

    leftBuf.clear();
    rightBuf.clear();
    leftoverStart = 0;
    inputEOS = false;
    outputEOS = false;
    readPos = target;
}

} // namespace automix
