#include "MediaCodecAudioSource.h"

#include <fcntl.h>
#include <unistd.h>
#include <sys/stat.h>

#include <cstring>
#include <algorithm>

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

    int32_t sr = 0, ch = 0;
    AMediaFormat_getInt32 (format, AMEDIAFORMAT_KEY_SAMPLE_RATE, &sr);
    AMediaFormat_getInt32 (format, AMEDIAFORMAT_KEY_CHANNEL_COUNT, &ch);
    if (sr > 0) sampleRate  = (double) sr;
    outChannels = (ch > 0) ? ch : 2;

    int64_t durationUs = 0;
    if (AMediaFormat_getInt64 (format, AMEDIAFORMAT_KEY_DURATION, &durationUs) && durationUs > 0)
        totalFrames = (juce::int64) ((double) durationUs * sampleRate / 1.0e6);

    codec = AMediaCodec_createDecoderByType (mime);
    const bool ok = codec != nullptr
        && AMediaCodec_configure (codec, format, nullptr, nullptr, 0) == AMEDIA_OK
        && AMediaCodec_start (codec) == AMEDIA_OK;

    AMediaFormat_delete (format);
    return ok;
}

void MediaCodecAudioSource::push16 (const int16_t* s, int totalSamples)
{
    const int ch = outChannels > 0 ? outChannels : 2;
    const int frames = totalSamples / ch;
    for (int fr = 0; fr < frames; ++fr)
    {
        const float l = s[fr * ch + 0] / 32768.0f;
        const float r = (ch > 1) ? s[fr * ch + 1] / 32768.0f : l;
        leftBuf.push_back (l);
        rightBuf.push_back (r);
    }
}

void MediaCodecAudioSource::pushF (const float* s, int totalSamples)
{
    const int ch = outChannels > 0 ? outChannels : 2;
    const int frames = totalSamples / ch;
    for (int fr = 0; fr < frames; ++fr)
    {
        const float l = s[fr * ch + 0];
        const float r = (ch > 1) ? s[fr * ch + 1] : l;
        leftBuf.push_back (l);
        rightBuf.push_back (r);
    }
}

void MediaCodecAudioSource::fillLeftover (int framesWanted)
{
    while (! outputEOS && (int) (leftBuf.size() - leftoverStart) < framesWanted)
    {
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
            }
        }

        AMediaCodecBufferInfo info;
        const ssize_t outIdx = AMediaCodec_dequeueOutputBuffer (codec, &info, 2000);
        if (outIdx >= 0)
        {
            size_t outSize = 0;
            uint8_t* outBuf = AMediaCodec_getOutputBuffer (codec, (size_t) outIdx, &outSize);
            if (outBuf != nullptr && info.size > 0)
            {
                uint8_t* data = outBuf + info.offset;
                if (pcmEncoding == 4)
                    pushF  (reinterpret_cast<const float*>   (data), info.size / (int) sizeof (float));
                else
                    push16 (reinterpret_cast<const int16_t*> (data), info.size / (int) sizeof (int16_t));
            }

            AMediaCodec_releaseOutputBuffer (codec, (size_t) outIdx, false);
            if ((info.flags & AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM) != 0)
                outputEOS = true;
        }
        else if (outIdx == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED)
        {
            AMediaFormat* of = AMediaCodec_getOutputFormat (codec);
            if (of != nullptr)
            {
                int32_t v = 0;
                if (AMediaFormat_getInt32 (of, AMEDIAFORMAT_KEY_SAMPLE_RATE,  &v) && v > 0) sampleRate  = (double) v;
                if (AMediaFormat_getInt32 (of, AMEDIAFORMAT_KEY_CHANNEL_COUNT, &v) && v > 0) outChannels = v;
                if (AMediaFormat_getInt32 (of, AMEDIAFORMAT_KEY_PCM_ENCODING,  &v) && v > 0) pcmEncoding = v;
                AMediaFormat_delete (of);
            }
        }
        else if (outIdx == AMEDIACODEC_INFO_TRY_AGAIN_LATER)
        {
            // Nothing ready yet. If we've drained all input and the codec keeps
            // returning nothing, treat as EOS so we never spin forever.
            if (inputEOS)
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
    if (leftoverStart > (1u << 16))
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
