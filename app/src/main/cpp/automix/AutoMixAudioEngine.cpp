#include "AutoMixAudioEngine.h"
#include "MediaCodecDecoder.h"
#include "MediaCodecAudioSource.h"
#include "OboeRuntime.h"
#include "TimeStretch.h"

#include <cmath>
#include <memory>

namespace
{
    struct TransitionGains
    {
        float out;
        float in;
    };

    float clamp01 (float v)
    {
        return juce::jlimit (0.0f, 1.0f, v);
    }

    float smoothstep01 (float v)
    {
        const float x = clamp01 (v);
        return x * x * (3.0f - 2.0f * x);
    }

    float equalPowerOut (float p)
    {
        return (float) std::cos (clamp01 (p) * juce::MathConstants<float>::halfPi);
    }

    float equalPowerIn (float p)
    {
        return (float) std::sin (clamp01 (p) * juce::MathConstants<float>::halfPi);
    }

    int normaliseTransitionStyle (int style)
    {
        return style >= 0 && style <= 5 ? style : 0;
    }

    // ── DJ FX: параметры реального DSP переходов ────────────────────────────
    // FILTER_SWEEP: экспоненциальный съезд среза LP (муффл «уходим в подвал»).
    constexpr float kDjSweepHiHz = 16000.0f;
    constexpr float kDjSweepLoHz = 150.0f;
    // ECHO_OUT: делэй ~375мс (≈ dotted-eighth на 120 BPM — универсально
    // музыкально), умеренный feedback, хвост дозвучивает ~2.2с после перехода.
    constexpr double kDjEchoDelaySec  = 0.375;
    // Умеренные send/feedback: геометрическая сумма повторов ~0.95x пика
    // уходящей деки — не жарче обычного сложения двух дек в кроссфейде.
    constexpr float  kDjEchoSend      = 0.55f;
    constexpr float  kDjEchoFeedback  = 0.42f;
    constexpr double kDjEchoTailSec   = 2.2;
    constexpr double kDjEchoTailFade  = 0.25;  // фейд последних 0.25с хвоста

    TransitionGains transitionGainsForStyle (float progress, int style)
    {
        const float p = clamp01 (progress);

        switch (normaliseTransitionStyle (style))
        {
            case 1: // ENERGY_FADE: outgoing gets out of the way early, incoming rises after the hit.
            {
                const float outPhase = smoothstep01 (p * 1.25f);
                const float inPhase  = smoothstep01 (p * 0.90f) * 0.85f + smoothstep01 (p) * 0.15f;
                return { equalPowerOut (outPhase), equalPowerIn (inPhase) };
            }
            case 2: // BEAT_MATCH: long, smooth blend with a slight lead for the incoming deck.
            {
                const float outPhase = smoothstep01 ((p + 0.06f) / 1.06f);
                const float inPhase  = smoothstep01 (p);
                const float out = equalPowerOut (outPhase);
                return { out * (0.92f + 0.08f * out), equalPowerIn (inPhase) };
            }
            case 3: // HARD_CUT: short DJ-style swap, but still ramped to avoid clicks.
            {
                const float earlyDuck = smoothstep01 (p * 1.15f) * 0.35f;
                const float lateDrop  = smoothstep01 ((p - 0.35f) / 0.45f) * 0.65f;
                const float outPhase = clamp01 (earlyDuck + lateDrop);
                const float inPhase  = smoothstep01 (p / 0.60f);
                return { equalPowerOut (outPhase), equalPowerIn (inPhase) };
            }
            case 4: // FILTER_SWEEP: сам муффл делает реальный LP в микс-лупе.
            {
                // Громкость уходящей держится почти в полный рост до середины —
                // съезд фильтра ДОЛЖЕН быть слышен, — и гаснет к концу.
                const float outPhase = smoothstep01 ((p - 0.45f) / 0.55f);
                const float inPhase  = smoothstep01 (p);
                return { equalPowerOut (outPhase), equalPowerIn (inPhase) };
            }
            case 5: // ECHO_OUT: сухая уходящая гаснет раньше — её место занимают
                    // повторы из делэя (реальное эхо в микс-лупе), не тишина.
            {
                const float outPhase = smoothstep01 (p * 1.4f);
                const float inPhase  = smoothstep01 (p);
                return { equalPowerOut (outPhase), equalPowerIn (inPhase) };
            }
            default: // SMOOTH_FADE: equal-power crossfade.
                return { equalPowerOut (p), equalPowerIn (p) };
        }
    }

    struct TryAudioLock
    {
        explicit TryAudioLock (juce::CriticalSection& lockToUse)
            : lock (lockToUse), locked (lock.tryEnter()) {}

        ~TryAudioLock()
        {
            if (locked)
                lock.exit();
        }

        juce::CriticalSection& lock;
        bool locked;
    };

    void copyLastGoodBlockOrClear (juce::AudioBuffer<float>& dst,
                                   const juce::AudioBuffer<float>& lastGood,
                                   int channels,
                                   int samples,
                                   bool audible,
                                   bool valid,
                                   std::atomic<int>& repeatCounter)
    {
        if (! audible || ! valid || lastGood.getNumChannels() < channels || lastGood.getNumSamples() < samples)
        {
            dst.clear();
            return;
        }

        // Reusing the same block for several callbacks sounds like a buzz/whine.
        // Keep only a single short, faded hold for an isolated lock miss.
        if (repeatCounter.fetch_add (1, std::memory_order_acq_rel) > 0)
        {
            dst.clear();
            return;
        }

        for (int c = 0; c < channels; ++c)
        {
            dst.copyFrom (c, 0, lastGood, c, 0, samples);
            dst.applyGainRamp (c, 0, samples, 1.0f, 0.0f);
        }
    }

    void rememberLastGoodBlock (juce::AudioBuffer<float>& lastGood,
                                const juce::AudioBuffer<float>& src,
                                std::atomic<int>& repeatCounter,
                                int channels,
                                int samples)
    {
        if (lastGood.getNumChannels() < channels || lastGood.getNumSamples() < samples)
            return;

        // Не запоминаем блок с NaN/Inf как «последний хороший»: иначе он
        // повторялся бы при следующем промахе лока (NaN×gain остаётся NaN).
        for (int c = 0; c < channels; ++c)
        {
            const float* s = src.getReadPointer (c);
            for (int i = 0; i < samples; ++i)
                if (! std::isfinite (s[i])) return;
        }

        for (int c = 0; c < channels; ++c)
            lastGood.copyFrom (c, 0, src, c, 0, samples);
        repeatCounter.store (0, std::memory_order_release);
    }
}

AutoMixAudioEngine::AutoMixAudioEngine()
{
    // WAV + AIFF always; FLAC + Ogg Vorbis because we enabled them in CMake.
    formatManager.registerBasicFormats();
    // По одному read-ahead потоку на дек (см. заголовок: общий поток starve'ил
    // играющий дек при загрузке incoming во время свода).
    // Максимальный НЕ-realtime приоритет: при шторке/переключении приложений
    // система кратко тротлит фоновые потоки. Если поток предчтения декодирует
    // mp3 на меньшем приоритете, он отстаёт → буфер транспорта осушается →
    // микро-заикания вплоть до «циклички». highest держит декод впереди под
    // нагрузкой (но НИЖЕ realtime-потока Oboe — аудио-колбэк не голодает).
    readAheadThreadA.startThread (juce::Thread::Priority::highest);
    readAheadThreadB.startThread (juce::Thread::Priority::highest);
}

AutoMixAudioEngine::~AutoMixAudioEngine()
{
    release();
    {
        const juce::CriticalSection::ScopedLockType slA (deckA.mutationLock);
        const juce::CriticalSection::ScopedLockType slB (deckB.mutationLock);
        clearDeckUnlocked (deckA);
        clearDeckUnlocked (deckB);
    }
    readAheadThreadA.stopThread (2000);
    readAheadThreadB.stopThread (2000);
}

bool AutoMixAudioEngine::init()
{
    const std::lock_guard<std::mutex> deviceLock (deviceControlMutex);

    if (initialised.load())
        return true;

    const juce::String err = deviceManager.initialiseWithDefaultDevices (0, 2);
    if (err.isNotEmpty())
    {
        DBG ("AutoMixAudioEngine: initialiseWithDefaultDevices failed: " << err);
        return false;
    }

    // Буфер под текущий маршрут: встроенный/проводной → умеренный low-latency
    // буфер, Bluetooth → крупный буфер (BT высоколатентный, fast-path не
    // тянет). Детали — в applyBufferForRoute. На старте устройство уже открыто
    // initialiseWithDefaultDevices, поэтому реопен не форсируем.
    initialised.store (true);
    applyBufferForRouteUnlocked (routeIsBluetooth.load(), /*forceReopen=*/false);
    routeEverApplied.store (true);

    deviceManager.addAudioCallback (this);
    initialised.store (true);
    return true;
}

// Подобрать размер буфера под маршрут и (при необходимости) переоткрыть Oboe-поток
// на ТЕКУЩЕМ устройстве по умолчанию. Зовётся не из аудио-потока (init / смена
// маршрута с фонового потока монитора). Позиция воспроизведения сохраняется:
// транспорты не сбрасываются, releaseResources/prepareToPlay не трогают позицию.
void AutoMixAudioEngine::applyBufferForRoute (bool isBluetooth, bool forceReopen)
{
    const std::lock_guard<std::mutex> deviceLock (deviceControlMutex);
    applyBufferForRouteUnlocked (isBluetooth, forceReopen);
}

void AutoMixAudioEngine::applyBufferForRouteUnlocked (bool isBluetooth, bool forceReopen)
{
    if (! initialised.load())
        return;

    // AudioTrack-sink активен → Oboe-девайс закрыт НАМЕРЕННО. Любой реопен здесь
    // (смена BT-маршрута, эскалация буфера, power-save) воскресил бы Oboe
    // ПАРАЛЛЕЛЬНО sink'у: два потребителя одного движка = двойная скорость/каша.
    // AudioTrack сам следует за системным роутингом (как ExoPlayer) — при смене
    // маршрута делать ничего не нужно. Выход из sink-режима реопенит явно
    // (nativeSinkStop), минуя этот гвард.
    if (automix::isSinkActive())
        return;

    auto setup = deviceManager.getAudioDeviceSetup();
    int target = setup.bufferSize;

    if (auto* dev = deviceManager.getCurrentAudioDevice())
    {
        const int  burst = dev->getDefaultBufferSize();           // нативный low-latency burst
        const auto sizes = dev->getAvailableBufferSizes();
        if (isBluetooth)
        {
            // BT — высоколатентный беспроводной маршрут. Маленький буфер уводит на
            // fast-path, который BT-микшер не тянет → underrun/заикания. Берём
            // крупный буфер: Oboe уходит на обычный микшер, звук стабилен.
            target = juce::jmax (burst > 0 ? burst * 8 : 4096, 3840);
        }
        else
        {
            // Встроенный/проводной — держим low-latency путь, но с запасом.
            // 2×burst (~8-10 мс на многих телефонах) слишком хрупок для JUCE +
            // MediaCodec + FX: при шторке/переключении приложений read-ahead и mixer
            // получают короткий CPU stall, и fast-path уходит в underrun. Для музыки
            // 30-40 мс системной задержки не критичны, зато Oboe перестаёт щёлкать.
            // Множитель адаптивный: базово 6×burst, телеметрия xrun'ов может
            // поднять до 8× (escalateBufferForUnderruns); в энергосбережении —
            // сразу максимум (система тротлит CPU жёстче обычного).
            int mult = bufferBurstMultiplier.load();
            if (powerSaveBuffer.load())
                mult = juce::jmax (mult, 8);
            target = (burst > 0) ? juce::jmax (burst * mult, 1536)
                                 : (sizes.isEmpty() ? 1536 : sizes.getFirst());
        }
        if (! sizes.isEmpty())
            target = juce::jmax (target, sizes.getFirst());        // не ниже минимума устройства
    }

    // Принудительный реопен нужен, когда маршрут сменился, а размер буфера совпал
    // (например проводная гарнитура → встроенный динамик): иначе setAudioDeviceSetup
    // увидит идентичный setup и НЕ переоткроет поток на новый маршрут.
    if (forceReopen)
        deviceManager.closeAudioDevice();

    if (forceReopen || setup.bufferSize != target)
    {
        setup.bufferSize = target;
        deviceManager.setAudioDeviceSetup (setup, true); // реопен на текущий default-маршрут
    }
}

// Маршрут вывода сменился (BT подключён/отключён, гарнитура и т.п.). Зовётся из
// Kotlin-монитора AudioDeviceCallback на ФОНОВОМ потоке (не main, не audio).
void AutoMixAudioEngine::onOutputRouteChanged (bool isBluetooth)
{
    const bool prev = routeIsBluetooth.exchange (isBluetooth);
    if (! initialised.load())
        return;                                   // применится при init()
    if (routeEverApplied.load() && prev == isBluetooth)
        return;                                   // маршрут не изменился — не дёргаем поток
    routeEverApplied.store (true);
    applyBufferForRoute (isBluetooth, /*forceReopen=*/true);
}

// Переоткрыть Oboe-поток на текущем маршруте, чтобы применился новый compat-режим
// (sharing/performance mode читаются из OboeRuntime при каждом открытии потока).
// Транспорты не сбрасываются — позиция воспроизведения сохраняется.
void AutoMixAudioEngine::reopenAudioDevice()
{
    if (! initialised.load())
        return;                                   // применится при init()
    applyBufferForRoute (routeIsBluetooth.load(), /*forceReopen=*/true);
}

void AutoMixAudioEngine::release()
{
    toneOn.store (false);
    outputMuted.store (true, std::memory_order_release);
    invalidateAllHolds();
    fxResetRequested.store (true, std::memory_order_release);
    {
        const juce::CriticalSection::ScopedLockType slA (deckA.mutationLock);
        const juce::CriticalSection::ScopedLockType slB (deckB.mutationLock);
        deckA.transport.stop();
        deckB.transport.stop();
    }

    const std::lock_guard<std::mutex> deviceLock (deviceControlMutex);
    if (initialised.load())
    {
        deviceManager.removeAudioCallback (this);
        deviceManager.closeAudioDevice();
        initialised.store (false);
    }
}

void AutoMixAudioEngine::startTone() { toneOn.store (true); }
void AutoMixAudioEngine::stopTone()  { toneOn.store (false); }

//==============================================================================
void AutoMixAudioEngine::clearDeckUnlocked (Deck& deck)
{
    invalidateHoldForDeck (deck);
    deck.hasTrack.store (false);
    deck.transport.stop();
    deck.transport.setSource (nullptr);
    deck.readerSource.reset();
    deck.memorySource.reset();
    deck.mediaCodecSource.reset();
    deck.decodedBuffer.setSize (0, 0);
    deck.path = {};
    deck.sourceSampleRate = 0.0;
}

void AutoMixAudioEngine::invalidateHoldForDeck (const Deck& deck)
{
    const auto index = (size_t) deckIndex (deck);
    holdValid[index].store (false, std::memory_order_release);
    holdRepeats[index].store (0, std::memory_order_release);
}

void AutoMixAudioEngine::invalidateAllHolds()
{
    holdValid[0].store (false, std::memory_order_release);
    holdValid[1].store (false, std::memory_order_release);
    holdRepeats[0].store (0, std::memory_order_release);
    holdRepeats[1].store (0, std::memory_order_release);
}

bool AutoMixAudioEngine::loadDeck (Deck& deck, const juce::String& path)
{
    // A controlled source swap must not replay the previous audio block while the
    // deck lock is held; that sounds like a short buzz before skip/pause completes.
    invalidateHoldForDeck (deck);
    {
        const juce::CriticalSection::ScopedLockType sl (deck.mutationLock);
        clearDeckUnlocked (deck);
    }

    const juce::File file (path);
    if (! file.existsAsFile())
    {
        DBG ("AutoMixAudioEngine: file not found: " << path);
        return false;
    }

    // wav/aiff/flac/ogg via JUCE.
    if (auto* reader = formatManager.createReaderFor (file))
    {
        const double sourceRate = reader->sampleRate;
        auto readerSource = std::make_unique<juce::AudioFormatReaderSource> (reader, true);

        const juce::CriticalSection::ScopedLockType sl (deck.mutationLock);
        clearDeckUnlocked (deck);
        deck.sourceSampleRate = sourceRate;
        deck.readerSource = std::move (readerSource);
        // ~5с предчтения (переживает затяжной тротлинг фоновых потоков при
        // погашенном экране / энергосбережении).
        deck.transport.setSource (deck.readerSource.get(), (int) (sourceRate * 5.0),
                                  &threadForDeck (deck), sourceRate, 2);
        deck.transport.setPosition (0.0);
        deck.path = path;
        deck.hasTrack.store (true);
        return true;
    }

    // mp3/aac/m4a via STREAMING MediaCodec source — playback starts immediately
    // (only codec config up front), decode happens on the read-ahead thread.
    if (auto streaming = automix::MediaCodecAudioSource::create (path))
    {
        const double sourceRate = streaming->getSampleRate();

        const juce::CriticalSection::ScopedLockType sl (deck.mutationLock);
        clearDeckUnlocked (deck);
        deck.sourceSampleRate = sourceRate;
        deck.mediaCodecSource = std::move (streaming);
        // ~8с предчтения (декод mp3 тяжелее, чем чтение wav): при переключении
        // приложений и в фоне с погашенным экраном система тротлит CPU — большой
        // буфер не даёт транспорту осушиться, звук не заикается и не уходит в
        // «цикличку». ~3 МБ/дек @48k stereo float — приемлемо.
        deck.transport.setSource (deck.mediaCodecSource.get(), (int) (sourceRate * 8.0),
                                  &threadForDeck (deck), sourceRate, 2);
        deck.transport.setPosition (0.0);
        deck.path = path;
        deck.hasTrack.store (true);
        return true;
    }

    DBG ("AutoMixAudioEngine: no JUCE reader and MediaCodec open failed: " << path);
    return false;
}

// Decode an entire file to PCM (independent of the playing source), for the
// offline stretch. wav/flac via JUCE, mp3/aac via MediaCodec.
bool AutoMixAudioEngine::decodeFullPCM (const juce::String& path, juce::AudioBuffer<float>& out, double& rate)
{
    const juce::File file (path);
    if (! file.existsAsFile())
        return false;

    // Кап длины: оффлайн-стретч держит в памяти до трёх полных float-копий
    // трека (src + stretched + decodedBuffer деки). 10-мин трек ≈ 700 МБ пика
    // → на слабом устройстве OOM-kill прямо на переходе. Режем на 12 минутах
    // (по частоте деки; для стретча этого более чем достаточно).
    const auto capSamples = [] (double sr) -> juce::int64
    {
        const double effSr = (sr > 0.0) ? sr : 48000.0;
        return (juce::int64) (effSr * 12.0 * 60.0);
    };

    if (std::unique_ptr<juce::AudioFormatReader> reader { formatManager.createReaderFor (file) })
    {
        const juce::int64 cap = capSamples (reader->sampleRate);
        const juce::int64 len64 = juce::jmin ((juce::int64) reader->lengthInSamples, cap);
        if (len64 <= 0)
            return false;
        const int len = (int) len64;   // ≤ 12 мин — в int влезает с запасом
        out.setSize (2, len);
        reader->read (&out, 0, len, 0, true, true);
        rate = reader->sampleRate;
        return true;
    }

    if (! automix::decodeWithMediaCodec (path, out, rate))
        return false;
    // MediaCodec-путь декодит целиком — подрезаем постфактум.
    const juce::int64 cap = capSamples (rate);
    if ((juce::int64) out.getNumSamples() > cap)
        out.setSize (out.getNumChannels(), (int) cap, true, false, true);
    return true;
}

bool AutoMixAudioEngine::prepareStretchB (double bpmA, double bpmB)
{
    if (bpmA <= 0.0 || bpmB <= 0.0)
        return false;

    juce::String sourcePath;
    {
        const juce::CriticalSection::ScopedLockType sl (deckB.mutationLock);
        if (! deckB.hasTrack.load() || deckB.path.isEmpty())
            return false;
        sourcePath = deckB.path;
    }

    // Full source PCM of B (independent of the currently-set playing source).
    juce::AudioBuffer<float> srcB;
    double srcRate = 0.0;
    if (! decodeFullPCM (sourcePath, srcB, srcRate))
        return false;

    // Match B's tempo to A: speed factor = bpmA/bpmB, so duration ratio = bpmB/bpmA.
    const double timeRatio = bpmB / bpmA;

    juce::AudioBuffer<float> stretched;
    if (! automix::timeStretchOffline (srcB, srcRate, timeRatio, stretched))
        return false;

    // Swap deck B to play the beat-matched buffer.
    invalidateHoldForDeck (deckB);
    const juce::CriticalSection::ScopedLockType sl (deckB.mutationLock);
    if (deckB.path != sourcePath)
        return false;
    clearDeckUnlocked (deckB);
    deckB.decodedBuffer = std::move (stretched);
    deckB.memorySource = std::make_unique<juce::MemoryAudioSource> (deckB.decodedBuffer, false, false);
    deckB.transport.setSource (deckB.memorySource.get(), 0, nullptr, srcRate, 2);
    deckB.transport.setPosition (0.0);
    deckB.sourceSampleRate = srcRate;
    deckB.path = sourcePath;
    deckB.hasTrack.store (true);
    return true;
}

bool AutoMixAudioEngine::loadTrack  (const juce::String& path) { return loadDeck (deckA, path); }
bool AutoMixAudioEngine::loadTrackA (const juce::String& path) { return loadDeck (deckA, path); }
bool AutoMixAudioEngine::loadTrackB (const juce::String& path) { return loadDeck (deckB, path); }
bool AutoMixAudioEngine::loadTrackAFd (int fd, long long offset, long long size)
{
    return loadDeckFd (deckA, fd, offset, size);
}

// content:// без копии: декод по дескриптору через потоковый MediaCodec-источник
// (мгновенный старт, ничего не копируем в кэш). Всегда MediaCodec — он покрывает
// все распространённые форматы.
bool AutoMixAudioEngine::loadDeckFd (Deck& deck, int fd, long long offset, long long size)
{
    invalidateHoldForDeck (deck);
    {
        const juce::CriticalSection::ScopedLockType sl (deck.mutationLock);
        clearDeckUnlocked (deck);
    }

    auto streaming = automix::MediaCodecAudioSource::createFromFd (fd, (int64_t) offset, (int64_t) size);
    if (streaming == nullptr)
    {
        DBG ("AutoMixAudioEngine: MediaCodec FD open failed");
        return false;
    }

    const double sourceRate = streaming->getSampleRate();

    const juce::CriticalSection::ScopedLockType sl (deck.mutationLock);
    clearDeckUnlocked (deck);
    deck.sourceSampleRate = sourceRate;
    deck.mediaCodecSource = std::move (streaming);
    deck.transport.setSource (deck.mediaCodecSource.get(), (int) (sourceRate * 8.0),
                              &threadForDeck (deck), sourceRate, 2);
    deck.transport.setPosition (0.0);
    deck.path = {};
    deck.hasTrack.store (true);
    return true;
}

void AutoMixAudioEngine::play()
{
    outputMuted.store (false, std::memory_order_release);
    const juce::CriticalSection::ScopedLockType sl (deckA.mutationLock);
    if (deckA.hasTrack.load())
        deckA.transport.start();
}

void AutoMixAudioEngine::pause()
{
    outputMuted.store (true, std::memory_order_release);
    invalidateAllHolds();
    fxResetRequested.store (true, std::memory_order_release);
    crossfadeStart.store (false);
    crossfadeActive.store (false);
    const juce::CriticalSection::ScopedLockType slA (deckA.mutationLock);
    const juce::CriticalSection::ScopedLockType slB (deckB.mutationLock);
    deckA.transport.stop();
    deckB.transport.stop();
}

void AutoMixAudioEngine::stop()
{
    outputMuted.store (true, std::memory_order_release);
    invalidateAllHolds();
    fxResetRequested.store (true, std::memory_order_release);
    const juce::CriticalSection::ScopedLockType slA (deckA.mutationLock);
    const juce::CriticalSection::ScopedLockType slB (deckB.mutationLock);
    deckA.transport.stop();
    deckA.transport.setPosition (0.0);
    deckB.transport.stop();
    deckB.transport.setPosition (0.0);

    crossfadeStart.store (false);
    crossfadeActive.store (false);
    currentDeck.store (0);
    fadeOutDeck.store (0);
    baseGainA.store (1.0f);
    baseGainB.store (0.0f);
}

void AutoMixAudioEngine::silenceOutput()
{
    outputMuted.store (true, std::memory_order_release);
    invalidateAllHolds();
    fxResetRequested.store (true, std::memory_order_release);
}

void AutoMixAudioEngine::setPlaybackVolume (float v01)
{
    masterVolumeTarget.store (juce::jlimit (0.0f, 1.0f, v01), std::memory_order_relaxed);
}

void AutoMixAudioEngine::startCrossfade (double durationMs, int transitionType)
{
    outputMuted.store (false, std::memory_order_release);
    long long total = (long long) (durationMs * currentSampleRate / 1000.0);
    if (total < 1) total = 1;
    crossfadeTotal.store (total);
    transitionStyle.store (normaliseTransitionStyle (transitionType), std::memory_order_release);

    // Both decks must be running for the mix. Stage 7 hand-off: deck A can be
    // positioned at the Media3 cue (entryOffsetMsA) so the blend continues
    // seamlessly from where Media3 left off; 0 keeps Stage 3-6 behaviour.
    const juce::CriticalSection::ScopedLockType slA (deckA.mutationLock);
    const juce::CriticalSection::ScopedLockType slB (deckB.mutationLock);
    if (deckA.hasTrack.load())
    {
        const double aOff = entryOffsetMsA.load();
        if (aOff > 0.0)
            deckA.transport.setPosition (aOff / 1000.0);
        deckA.transport.start();
    }
    if (deckB.hasTrack.load())
    {
        deckB.transport.setPosition (entryOffsetMsB.load() / 1000.0); // model's entry point
        deckB.transport.start();
    }

    fadeOutDeck.store (0);        // legacy direction: A fades out, B fades in
    baseGainA.store (1.0f);
    baseGainB.store (0.0f);
    crossfadeStart.store (true); // audio thread resets position + activates
}

void AutoMixAudioEngine::setEntryOffsetB (double ms)
{
    entryOffsetMsB.store (ms < 0.0 ? 0.0 : ms);
}

void AutoMixAudioEngine::setEntryOffsetA (double ms)
{
    entryOffsetMsA.store (ms < 0.0 ? 0.0 : ms);
}

void AutoMixAudioEngine::clearDeckA()
{
    invalidateHoldForDeck (deckA);
    const juce::CriticalSection::ScopedLockType sl (deckA.mutationLock);
    clearDeckUnlocked (deckA);
}

// ── Stage 8: full LOCAL player (ping-pong decks) ───────────────────────────

bool AutoMixAudioEngine::loadIncoming (const juce::String& path)
{
    return loadDeck (deckRef (1 - currentDeck.load()), path);
}

bool AutoMixAudioEngine::loadIncomingFd (int fd, long long offset, long long size)
{
    return loadDeckFd (deckRef (1 - currentDeck.load()), fd, offset, size);
}

void AutoMixAudioEngine::startTransition (double durationMs, double entryMs, int transitionType)
{
    outputMuted.store (false, std::memory_order_release);
    long long total = (long long) (durationMs * currentSampleRate / 1000.0);
    if (total < 1) total = 1;
    crossfadeTotal.store (total);
    transitionStyle.store (normaliseTransitionStyle (transitionType), std::memory_order_release);

    const int out = currentDeck.load();
    const int in  = 1 - out;
    fadeOutDeck.store (out);

    Deck& outDeck = deckRef (out);
    Deck& inDeck  = deckRef (in);

    // Fixed A→B lock order (decks ping-pong, so in/out aren't always A/B) — avoids
    // any deadlock against other both-deck sections.
    const juce::CriticalSection::ScopedLockType slA (deckA.mutationLock);
    const juce::CriticalSection::ScopedLockType slB (deckB.mutationLock);
    if (inDeck.hasTrack.load())
    {
        inDeck.transport.setPosition ((entryMs < 0.0 ? 0.0 : entryMs) / 1000.0);
        inDeck.transport.start();
    }
    if (outDeck.hasTrack.load())
        outDeck.transport.start();

    // Steady gains reflect current=out (1) until the envelope ramps it over.
    baseGainA.store (out == 0 ? 1.0f : 0.0f);
    baseGainB.store (out == 0 ? 0.0f : 1.0f);
    crossfadeStart.store (true);
}

void AutoMixAudioEngine::playCurrent()
{
    outputMuted.store (false, std::memory_order_release);
    Deck& d = deckRef (currentDeck.load());
    const juce::CriticalSection::ScopedLockType sl (d.mutationLock);
    if (d.hasTrack.load())
        d.transport.start();
}

void AutoMixAudioEngine::seekCurrent (double ms)
{
    const double clamped = (ms < 0.0 ? 0.0 : ms);
    const int cur = currentDeck.load();
    Deck& deck = deckRef (cur);
    invalidateHoldForDeck (deck);
    const juce::CriticalSection::ScopedLockType sl (deck.mutationLock);
    deck.transport.setPosition (clamped / 1000.0);
    // Reflect the seek immediately for the lock-free getter (next callback will
    // refresh it anyway, but this avoids a one-tick lag in the UI position).
    reportedPosMs[(size_t) cur].store (clamped, std::memory_order_relaxed);
}

double AutoMixAudioEngine::positionMsCurrent()
{
    // Lock-free: read the value the audio callback last published. No contention
    // with the realtime callback (no tryEnter() failures → no silence blocks).
    return reportedPosMs[(size_t) currentDeck.load()].load (std::memory_order_relaxed);
}

double AutoMixAudioEngine::lengthMsCurrent()
{
    return reportedLenMs[(size_t) currentDeck.load()].load (std::memory_order_relaxed);
}

bool AutoMixAudioEngine::isCrossfadeActive()
{
    return crossfadeActive.load() || crossfadeStart.load();
}

int AutoMixAudioEngine::currentDeckIndex()
{
    return currentDeck.load();
}

void AutoMixAudioEngine::clearDeck (int index)
{
    Deck& d = deckRef (index);
    invalidateHoldForDeck (d);
    const juce::CriticalSection::ScopedLockType sl (d.mutationLock);
    clearDeckUnlocked (d);
}

void AutoMixAudioEngine::setBassSwap (bool enabled)
{
    bassSwapEnabled.store (enabled);
}

// ── Professional FX chain (delegates to AudioFxChain) ────────────────────────
void AutoMixAudioEngine::setFxMasterEnabled (bool on)              { audioFx.setMasterEnabled (on); }
void AutoMixAudioEngine::setPreampGainDb (float db)               { audioFx.setPreampGainDb (db); }
void AutoMixAudioEngine::setEqEnabled (bool enabled)              { audioFx.setEqEnabled (enabled); }
void AutoMixAudioEngine::setEqBandGain (int band, float gainDb)   { audioFx.setEqBandGain (band, gainDb); }
void AutoMixAudioEngine::setEqBands (const float* gainsDb, int n) { audioFx.setEqBands (gainsDb, n); }
void AutoMixAudioEngine::setBassBoost (bool on, float f, float g) { audioFx.setBassBoost (on, f, g); }
void AutoMixAudioEngine::setLoudnessEnabled (bool on)            { audioFx.setLoudnessEnabled (on); }
void AutoMixAudioEngine::setCurrentVolume (float v01)            { audioFx.setCurrentVolume (v01); }
void AutoMixAudioEngine::setStereoWidth (float w)               { audioFx.setStereoWidth (w); }
void AutoMixAudioEngine::setBalance (float p)                   { audioFx.setBalance (p); }
void AutoMixAudioEngine::setMono (bool on)                      { audioFx.setMono (on); }
void AutoMixAudioEngine::setParamEqEnabled (bool on)           { audioFx.setParamEqEnabled (on); }
void AutoMixAudioEngine::setParamBand (int b, float f, float q, float g) { audioFx.setParamBand (b, f, q, g); }
void AutoMixAudioEngine::setCompressorFx (bool on, float t, float r, float a, float rel)
                                                                 { audioFx.setCompressor (on, t, r, a, rel); }
void AutoMixAudioEngine::setLimiterFx (bool on, float t, float rel) { audioFx.setLimiter (on, t, rel); }
void AutoMixAudioEngine::setReverbFx (bool on, float rs, float d, float w) { audioFx.setReverb (on, rs, d, w); }
void AutoMixAudioEngine::setSaturationFx (bool on, float d) { audioFx.setSaturation (on, d); }
void  AutoMixAudioEngine::setMeterEnabled (bool on) { audioFx.setMeterEnabled (on); }
float AutoMixAudioEngine::meterPeakL() { return audioFx.meterPeakL(); }
float AutoMixAudioEngine::meterPeakR() { return audioFx.meterPeakR(); }

double AutoMixAudioEngine::positionMsA()
{
    return reportedPosMs[0].load (std::memory_order_relaxed);
}

double AutoMixAudioEngine::lengthMsA()
{
    return reportedLenMs[0].load (std::memory_order_relaxed);
}

int AutoMixAudioEngine::xRunCount()
{
    // try_lock: во время переоткрытия устройства (init / смена маршрута) не
    // блокируем вызывающего (тикер на main) — просто вернём «неизвестно».
    std::unique_lock<std::mutex> lock (deviceControlMutex, std::try_to_lock);
    if (! lock.owns_lock() || ! initialised.load())
        return -1;
    if (auto* dev = deviceManager.getCurrentAudioDevice())
        return dev->getXRunCount();
    return -1;
}

void AutoMixAudioEngine::setPowerSaveMode (bool on)
{
    if (powerSaveBuffer.exchange (on) == on)
        return;                                    // состояние не изменилось
    if (! initialised.load())
        return;                                    // применится при init()
    if (! routeIsBluetooth.load())
        applyBufferForRoute (false, /*forceReopen=*/true);
}

void AutoMixAudioEngine::escalateBufferForUnderruns()
{
    // Кап 8×burst: дальше расти бессмысленно — латентность заметна, а причина
    // уже точно не в размере буфера.
    int cur = bufferBurstMultiplier.load();
    if (cur >= 8)
        return;
    bufferBurstMultiplier.store (cur + 2);
    // BT-маршрут и так на крупном буфере — эскалация касается динамика/провода.
    if (! routeIsBluetooth.load())
        applyBufferForRoute (false, /*forceReopen=*/true);
}

//==============================================================================
void AutoMixAudioEngine::audioDeviceAboutToStart (juce::AudioIODevice* device)
{
    prepareInternal (device != nullptr ? device->getCurrentSampleRate() : 0.0,
                     device != nullptr ? device->getCurrentBufferSizeSamples() : 0);
}

// Общая подготовка рендера: зовётся и Oboe-девайсом (audioDeviceAboutToStart),
// и AudioTrack-sink'ом (enterSinkMode) — один и тот же код, разные источники тика.
void AutoMixAudioEngine::prepareInternal (double sampleRate, int blockSize)
{
    if (sampleRate > 0.0) currentSampleRate = sampleRate;
    if (blockSize  > 0)   currentBlockSize  = blockSize;

    if (currentSampleRate <= 0.0) currentSampleRate = 44100.0;
    if (currentBlockSize  <= 0)   currentBlockSize  = 512;

    phase = 0.0;
    {
        const juce::CriticalSection::ScopedLockType slA (deckA.mutationLock);
        const juce::CriticalSection::ScopedLockType slB (deckB.mutationLock);
        deckA.transport.prepareToPlay (currentBlockSize, currentSampleRate);
        deckB.transport.prepareToPlay (currentBlockSize, currentSampleRate);
    }

    const int scratchChannels = 8;
    const int scratchSamples = juce::jmax (currentBlockSize * 4, 4096);
    scratchA.setSize (scratchChannels, scratchSamples, false, true, false);
    scratchB.setSize (scratchChannels, scratchSamples, false, true, false);
    holdA.setSize (scratchChannels, scratchSamples, false, true, false);
    holdB.setSize (scratchChannels, scratchSamples, false, true, false);
    holdA.clear();
    holdB.clear();
    holdValid[0].store (false, std::memory_order_release);
    holdValid[1].store (false, std::memory_order_release);
    holdRepeats[0].store (0, std::memory_order_release);
    holdRepeats[1].store (0, std::memory_order_release);

    // Bass-swap low-pass: same fixed coefficients for every deck/channel; only a
    // scalar changes at runtime, so there are no coefficient-swap clicks.
    auto bassCoeffs = juce::dsp::IIR::Coefficients<float>::makeLowPass (currentSampleRate, kBassCutoffHz);
    for (auto& f : lowpassA) { f.coefficients = bassCoeffs; f.reset(); }
    for (auto& f : lowpassB) { f.coefficients = bassCoeffs; f.reset(); }

    // DJ FX: кольцевой буфер ECHO_OUT — аллокация здесь, НЕ на аудио-потоке.
    djEchoLen = juce::jmax (1, (int) (currentSampleRate * kDjEchoDelaySec));
    djEchoBuf.setSize (2, djEchoLen, false, true, false);
    djEchoBuf.clear();
    djEchoPos = 0;
    djEchoTail = 0;
    djSweepLp[0] = djSweepLp[1] = 0.0f;

    // Профессиональная FX-цепочка готовится на стерео-выход.
    audioFx.prepare (currentSampleRate, currentBlockSize, 2);
}

// ── AudioTrack-выход (Java-sink) ─────────────────────────────────────────────
// Третий вход в аудио-систему для устройств, где кривые оба нативных пути
// (vivo Y35: AAudio Float шипит, deep-путь молчит). Oboe-девайс закрывается,
// движок жив, блоки тянет Java-поток AudioTrackSink — путь ExoPlayer/стриминга,
// заведомо рабочий на этих устройствах.
void AutoMixAudioEngine::enterSinkMode (double sampleRate, int blockSize)
{
    if (initialised.load())
    {
        std::lock_guard<std::mutex> lock (deviceControlMutex);
        deviceManager.closeAudioDevice();   // Oboe-колбэки останавливаются, движок жив
    }
    prepareInternal (sampleRate, blockSize);
}

void AutoMixAudioEngine::renderSinkBlock (float* left, float* right, int numSamples)
{
    float* outs[2] = { left, right };
    audioDeviceIOCallbackWithContext (nullptr, 0, outs, 2, numSamples, {});
}

void AutoMixAudioEngine::audioDeviceStopped()
{
    const juce::CriticalSection::ScopedLockType slA (deckA.mutationLock);
    const juce::CriticalSection::ScopedLockType slB (deckB.mutationLock);
    deckA.transport.releaseResources();
    deckB.transport.releaseResources();
    audioFx.reset();
    phase = 0.0;
}

void AutoMixAudioEngine::audioDeviceIOCallbackWithContext (const float* const* /*inputChannelData*/,
                                                           int /*numInputChannels*/,
                                                           float* const* outputChannelData,
                                                           int numOutputChannels,
                                                           int numSamples,
                                                           const juce::AudioIODeviceCallbackContext& /*context*/)
{
    if (numOutputChannels <= 0)
        return;

    // Пульс для диагностики: «крутится ли колбэк вообще» видно из OBOE-дампа.
    automix::noteAudioCallback (numSamples);

    juce::ScopedNoDenormals noDenormals; // flush IIR (bass-swap + EQ) denormals

    juce::AudioBuffer<float> output (outputChannelData, numOutputChannels, numSamples);

    if (fxResetRequested.exchange (false, std::memory_order_acq_rel))
    {
        audioFx.reset();
        // Пауза/стоп/скип: гасим и хвост DJ-эха, иначе после возобновления
        // из буфера вылезут повторы прошлого трека.
        djEchoTail = 0;
        if (djEchoLen > 0) djEchoBuf.clear();
        djSweepLp[0] = djSweepLp[1] = 0.0f;
        // ЛЕЧЕНИЕ NaN: bass-swap IIR (lowpassA/B) сбрасывались только на старте
        // перехода — а один NaN из деки отравлял их состояние, и `av -= cutA*la`
        // выдавал NaN каждый блок (0*NaN=NaN) до конца перехода → тишина после
        // скраба. Плюс инвалидируем hold-буферы: rememberLastGoodBlock мог
        // захватить NaN-блок и повторять его. Теперь fxReset чинит ВСЁ.
        for (auto& f : lowpassA) f.reset();
        for (auto& f : lowpassB) f.reset();
        holdValid[0].store (false, std::memory_order_release);
        holdValid[1].store (false, std::memory_order_release);
    }

    if (outputMuted.load (std::memory_order_acquire))
    {
        output.clear();
        return;
    }

    // NO global lock here. Each deck is guarded by its OWN lock during its pull
    // (below), so loading the incoming deck — which holds only that deck's lock
    // across the ~5s buffer swap — never blocks mixing the PLAYING deck. That was
    // the rt-fix regression: one global lock meant an incoming load silenced the
    // whole output for the swap window → ~1s freeze at the start of AutoMix.

    const bool aHas = deckA.hasTrack.load();
    const bool bHas = deckB.hasTrack.load();

    // No track: Stage 1 diagnostic tone, else silence.
    if (! aHas && ! bHas)
    {
        if (toneOn.load())
        {
            const double phaseInc = juce::MathConstants<double>::twoPi * kToneHz / currentSampleRate;
            for (int i = 0; i < numSamples; ++i)
            {
                const float s = kToneAmplitude * (float) std::sin (phase);
                phase += phaseInc;
                if (phase >= juce::MathConstants<double>::twoPi)
                    phase -= juce::MathConstants<double>::twoPi;
                for (int ch = 0; ch < numOutputChannels; ++ch)
                    if (outputChannelData[ch] != nullptr)
                        outputChannelData[ch][i] = s;
            }
            return;
        }
        output.clear();
        return;
    }

    // Latch a crossfade start so its sample position begins exactly here.
    if (crossfadeStart.exchange (false))
    {
        crossfadePos = 0;
        // Bass-swap фильтры считаются ТОЛЬКО во время перехода (вне его их вклад
        // всё равно нулевой — cut=0). Сбрасываем состояние на старте: вес свопа
        // поднимается от нуля лишь к середине перехода, к этому моменту фильтры
        // давно прогреты, щелчков нет.
        for (auto& f : lowpassA) f.reset();
        for (auto& f : lowpassB) f.reset();
        // DJ FX: чистый старт — свип с нуля, делэй без остатков прошлого эха.
        djSweepLp[0] = djSweepLp[1] = 0.0f;
        djEchoBuf.clear();
        djEchoPos = 0;
        djEchoTail = 0;
        crossfadeActive.store (true);
    }

    const bool   active  = crossfadeActive.load();
    const double total   = (double) crossfadeTotal.load();
    const float  bgA     = baseGainA.load();
    const float  bgB     = baseGainB.load();
    const int    fOut    = fadeOutDeck.load(); // which physical deck fades OUT
    const int    style   = transitionStyle.load (std::memory_order_acquire);
    const bool   bassOn  = bassSwapEnabled.load();
    // Гоняем bass-swap биквады только когда они реально влияют на звук: в
    // стационарном режиме (без перехода) это экономит 4 IIR на каждый сэмпл.
    const bool   bassSwapActive = active && bassOn && total > 0.0;

    const int ch = juce::jmin (numOutputChannels, 8);
    if (numSamples > scratchA.getNumSamples() || numSamples > scratchB.getNumSamples()
        || numSamples > holdA.getNumSamples() || numSamples > holdB.getNumSamples()
        || ch > scratchA.getNumChannels() || ch > scratchB.getNumChannels()
        || ch > holdA.getNumChannels() || ch > holdB.getNumChannels())
    {
        output.clear();
        return;
    }

    // Pull only audible decks. After a crossfade, the faded-out deck remains loaded
    // for reuse, but gain=0 means we must not keep decoding it every callback.
    scratchA.setSize (ch, numSamples, false, false, true);
    scratchB.setSize (ch, numSamples, false, false, true);

    const bool pullA = aHas && (active || bgA > 0.0001f);
    const bool pullB = bHas && (active || bgB > 0.0001f);

    // Each deck under its OWN lock. tryEnter never blocks the realtime thread. If an
    // audible deck is briefly mid-swap/seek, reuse its previous good block instead of
    // injecting hard zeroes; that turns a one-buffer contention spike into a short hold
    // rather than a click/dropout. Incoming muted decks still clear to silence.
    {
        TryAudioLock la (deckA.mutationLock);
        if (la.locked)
        {
            reportedPosMs[0].store (deckA.transport.getCurrentPosition() * 1000.0, std::memory_order_relaxed);
            reportedLenMs[0].store (deckA.transport.getLengthInSeconds() * 1000.0, std::memory_order_relaxed);
            if (pullA)
            {
                const juce::AudioSourceChannelInfo ia (&scratchA, 0, numSamples);
                deckA.transport.getNextAudioBlock (ia);
                rememberLastGoodBlock (holdA, scratchA, holdRepeats[0], ch, numSamples);
                holdValid[0].store (true, std::memory_order_release);
            }
            else
            {
                scratchA.clear();
            }
        }
        else copyLastGoodBlockOrClear (scratchA, holdA, ch, numSamples, pullA,
                                       holdValid[0].load (std::memory_order_acquire), holdRepeats[0]);
    }
    {
        TryAudioLock lb (deckB.mutationLock);
        if (lb.locked)
        {
            reportedPosMs[1].store (deckB.transport.getCurrentPosition() * 1000.0, std::memory_order_relaxed);
            reportedLenMs[1].store (deckB.transport.getLengthInSeconds() * 1000.0, std::memory_order_relaxed);
            if (pullB)
            {
                const juce::AudioSourceChannelInfo ib (&scratchB, 0, numSamples);
                deckB.transport.getNextAudioBlock (ib);
                rememberLastGoodBlock (holdB, scratchB, holdRepeats[1], ch, numSamples);
                holdValid[1].store (true, std::memory_order_release);
            }
            else
            {
                scratchB.clear();
            }
        }
        else copyLastGoodBlockOrClear (scratchB, holdB, ch, numSamples, pullB,
                                       holdValid[1].load (std::memory_order_acquire), holdRepeats[1]);
    }

    float*       o[8];
    const float* a[8];
    const float* b[8];
    for (int c = 0; c < ch; ++c)
    {
        o[c] = output.getWritePointer (c);
        a[c] = scratchA.getReadPointer (c);
        b[c] = scratchB.getReadPointer (c);
    }

    // ── DJ FX: пред-расчёт на блок ───────────────────────────────────────────
    // FILTER_SWEEP: срез LP уезжает экспоненциально по прогрессу перехода;
    // коэффициент обновляем раз на блок (5-20мс) — для one-pole этого более чем
    // достаточно, ступеней не слышно.
    const bool sweepOn = active && total > 0.0 && style == 4;
    float sweepAlpha = 0.0f;
    if (sweepOn)
    {
        double t0 = (double) crossfadePos / total;
        if (t0 > 1.0) t0 = 1.0;
        const float cutoff = kDjSweepHiHz * std::pow (kDjSweepLoHz / kDjSweepHiHz, (float) t0);
        sweepAlpha = 1.0f - std::exp (-juce::MathConstants<float>::twoPi * cutoff
                                      / (float) currentSampleRate);
    }
    // ECHO_OUT: активная фаза (переход идёт) или дозвучка хвоста после него.
    const bool  echoActive = active && total > 0.0 && style == 5 && djEchoLen > 0;
    const float tailFadeSamples = juce::jmax (1.0f, (float) (kDjEchoTailFade * currentSampleRate));
    float* echoPtr[2] = { nullptr, nullptr };
    if (djEchoLen > 0)
    {
        echoPtr[0] = djEchoBuf.getWritePointer (0);
        echoPtr[1] = djEchoBuf.getWritePointer (1);
    }

    for (int i = 0; i < numSamples; ++i)
    {
        float ga, gb;            // volume envelope (equal power)
        float bassA = 1.0f;      // per-deck bass amount (1 = full low end, 0 = cut)
        float bassB = 1.0f;
        float echoSend = 0.0f;   // уровень посыла уходящей деки в делэй

        if (active && total > 0.0)
        {
            double t = (double) (crossfadePos + i) / total;
            if (t > 1.0) t = 1.0;
            const auto gains = transitionGainsForStyle ((float) t, style);
            const float gOut = gains.out; // outgoing always fades down
            const float gIn  = gains.in;  // incoming always fades up

            // ECHO_OUT: посыл нарастает по ходу перехода — сначала эха почти
            // нет, к концу сухой звук ушёл фейдером, остаются повторы.
            if (echoActive)
                echoSend = smoothstep01 ((float) t) * kDjEchoSend;

            // Bass swap (Stage 5), only when enabled. Complementary: incoming
            // gains its low end while the outgoing deck gives up its own.
            float bassOut = 1.0f, bassIn = 1.0f;
            if (bassOn)
            {
                const float w = 0.5f; // swap over the middle 50% of the transition
                float u = ((float) t - (0.5f - w * 0.5f)) / w;
                u = juce::jlimit (0.0f, 1.0f, u);
                const float s = u * u * (3.0f - 2.0f * u); // smoothstep 0->1
                bassIn  = s;
                bassOut = 1.0f - s;
            }

            // Map outgoing/incoming onto physical decks A/B by direction.
            if (fOut == 0) { ga = gOut; gb = gIn;  bassA = bassOut; bassB = bassIn; }
            else           { ga = gIn;  gb = gOut; bassA = bassIn;  bassB = bassOut; }
        }
        else
        {
            ga = bgA;
            gb = bgB;
        }

        const float cutA = 1.0f - bassA; // how much low band to remove from A
        const float cutB = 1.0f - bassB;

        // Эхо живёт и ПОСЛЕ конца перехода (djEchoTail > 0): повторы
        // дозвучивают поверх нового трека и плавно гаснут к концу хвоста.
        // Wet дакается ростом громкости входящего трека — эхо уступает место
        // (и не суммируется с ним в клиппинг на жирном материале).
        const bool  echoNow = djEchoLen > 0 && (echoActive || djEchoTail > 0);
        const float gInNow  = (fOut == 0) ? gb : ga;   // громкость входящей деки
        const float echoWet = ! echoNow ? 0.0f
            : (echoActive ? 1.0f - 0.45f * gInNow
                          : 0.55f * juce::jmin (1.0f, (float) djEchoTail / tailFadeSamples));

        for (int c = 0; c < ch; ++c)
        {
            float av = a[c][i];
            float bv = b[c][i];

            // Низкочастотные фильтры работают только пока идёт переход с
            // bass-swap (состояние сброшено на его старте); вне перехода
            // cutA/cutB = 0 и вычитать нечего — фильтры пропускаем.
            if (c < 2 && bassSwapActive)
            {
                // Санитизация входа фильтра: один NaN из деки иначе навсегда
                // отравляет состояние IIR (и весь остаток перехода = NaN).
                if (! std::isfinite (av)) av = 0.0f;
                if (! std::isfinite (bv)) bv = 0.0f;
                const float la = lowpassA[(size_t) c].processSample (av);
                const float lb = lowpassB[(size_t) c].processSample (bv);
                av -= cutA * la;
                bv -= cutB * lb;
            }

            // FILTER_SWEEP: реальный муффл уходящей деки (LP, а не громкость).
            if (c < 2 && sweepOn)
            {
                float& x = (fOut == 0) ? av : bv;
                djSweepLp[c] += sweepAlpha * (x - djSweepLp[c]);
                x = djSweepLp[c];
            }

            float mixed = av * ga + bv * gb;

            // ECHO_OUT: pre-fader send уходящей деки → делэй; возврат — в микс.
            if (c < 2 && echoNow)
            {
                float* eb = echoPtr[c];
                const float y = eb[djEchoPos];
                const float send = echoActive ? ((fOut == 0 ? av : bv) * echoSend) : 0.0f;
                eb[djEchoPos] = send + y * kDjEchoFeedback;
                mixed += y * echoWet;
            }

            o[c][i] = mixed;
        }

        if (echoNow)
        {
            if (++djEchoPos >= djEchoLen) djEchoPos = 0;
            if (! echoActive && djEchoTail > 0) --djEchoTail;
        }
    }

    // ── Профессиональная FX-цепочка на финальном ЛОКАЛЬНОМ миксе ────────────
    // После сведения деков → покрывает всё локальное аудио (одиночный дек или
    // AutoMix-кроссфейд). Сама пропускает выключенные стадии (лёгкий путь).
    // o[0..ch) — указатели выхода; обрабатываем до 2 каналов (стерео).
    audioFx.process (o, ch, numSamples);

    // Мастер-громкость (дак при уведомлениях/навигаторе, фейды сервиса). Линейный
    // рамп ~20мс на полный ход — смена громкости без щелчков. Оба на 1.0 → пропуск.
    {
        const float target = masterVolumeTarget.load (std::memory_order_relaxed);
        if (target != 1.0f || masterVolumeSmoothed != 1.0f)
        {
            const float step = 1.0f / (0.02f * (float) currentSampleRate);
            for (int i = 0; i < numSamples; ++i)
            {
                if (masterVolumeSmoothed < target)
                    masterVolumeSmoothed = juce::jmin (target, masterVolumeSmoothed + step);
                else if (masterVolumeSmoothed > target)
                    masterVolumeSmoothed = juce::jmax (target, masterVolumeSmoothed - step);
                for (int c = 0; c < ch && c < 2; ++c)
                    o[c][i] *= masterVolumeSmoothed;
            }
        }
    }

    // NaN-ловушка: не-конечный сэмпл (NaN/Inf) отравляет рекурсивные IIR/лимитер
    // навсегда и на выходе слышен как постоянный шум. Вычищаем и СЧИТАЕМ
    // (nanScrubbed в OBOE-дампе): ненулевой счётчик = цепочка отравляется,
    // это отдельный баг с точным индикатором. Заодно (бесплатно, тот же проход)
    // меряем уровень выхода: level>0 в дампе при тишине из динамика = звук
    // чистый на выходе движка, его портит система.
    {
        int scrubbed = 0;
        float sumAbs = 0.0f;
        // Haptic Music: тот же проход по ch0 — LP ~110 Гц (бас) + полоса
        // ~200..1800 Гц (LP1800 - LP200, середина: снейр/клэп). Alpha под
        // 44.1-48к; точность срезов для тактильности некритична.
        float hapticLp = hapticLpState;
        float midLo = hapticLpMidLo;
        float midHi = hapticLpMidHi;
        float bassSum = 0.0f;
        float midSum = 0.0f;
        for (int c = 0; c < ch && c < 2; ++c)
        {
            float* p = o[c];
            for (int i = 0; i < numSamples; ++i)
            {
                if (! std::isfinite (p[i]))
                {
                    p[i] = 0.0f;
                    ++scrubbed;
                }
                else if (c == 0)
                {
                    sumAbs += std::abs (p[i]);
                    hapticLp += 0.014f * (p[i] - hapticLp);
                    bassSum += std::abs (hapticLp);
                    midLo += 0.026f * (p[i] - midLo);   // LP ~200 Гц
                    midHi += 0.21f  * (p[i] - midHi);   // LP ~1800 Гц
                    midSum += std::abs (midHi - midLo);
                }
            }
        }
        hapticLpState = hapticLp;
        hapticLpMidLo = midLo;
        hapticLpMidHi = midHi;
        if (numSamples > 0)
        {
            automix::noteAudioLevel (sumAbs / (float) numSamples);
            automix::noteBassLevel (bassSum / (float) numSamples,
                                    midSum / (float) numSamples, numSamples);
        }
        if (scrubbed > 0)
        {
            automix::addNanScrubbed (scrubbed);
            fxResetRequested.store (true, std::memory_order_release); // вылечить цепочку
        }
    }

    // Clear any channels beyond the 8 we mixed (phones are stereo; just in case).
    for (int c = ch; c < numOutputChannels; ++c)
        if (outputChannelData[c] != nullptr)
            juce::FloatVectorOperations::clear (outputChannelData[c], numSamples);

    if (active && total > 0.0)
    {
        crossfadePos += numSamples;
        if ((double) crossfadePos >= total)
        {
            crossfadeActive.store (false);
            // ECHO_OUT: переход закончился, но повторы дозвучивают поверх
            // нового трека и плавно гаснут (классический DJ echo-out).
            if (style == 5 && djEchoLen > 0)
                djEchoTail = (long long) (kDjEchoTailSec * currentSampleRate);
            const int out = fOut;                      // deck that faded out
            currentDeck.store (1 - out);               // incoming is now current
            baseGainA.store (out == 0 ? 0.0f : 1.0f);  // outgoing -> 0, incoming -> 1
            baseGainB.store (out == 0 ? 1.0f : 0.0f);
        }
    }
}
