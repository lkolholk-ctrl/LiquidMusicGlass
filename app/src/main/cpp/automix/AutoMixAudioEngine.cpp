#include "AutoMixAudioEngine.h"
#include "MediaCodecDecoder.h"
#include "MediaCodecAudioSource.h"
#include "TimeStretch.h"

#include <cmath>
#include <memory>

namespace
{
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

    // Graphic-EQ band centres (ISO octave spacing) and Q (~1 octave wide).
    constexpr std::array<float, 10> kEqCenters {
        31.0f, 62.0f, 125.0f, 250.0f, 500.0f, 1000.0f, 2000.0f, 4000.0f, 8000.0f, 16000.0f
    };
    constexpr float kEqQ = 1.41f;
}

AutoMixAudioEngine::AutoMixAudioEngine()
{
    // WAV + AIFF always; FLAC + Ogg Vorbis because we enabled them in CMake.
    formatManager.registerBasicFormats();
    for (auto& g : eqGainsDb)
        g.store (0.0f);
    // По одному read-ahead потоку на дек (см. заголовок: общий поток starve'ил
    // играющий дек при загрузке incoming во время свода).
    // Высокий приоритет: при переключении приложений система кратко тротлит
    // фоновые потоки. Если поток предчтения декодирует mp3 на обычном приоритете,
    // он отстаёт → буфер транспорта осушается → ~2с заикания. High держит декод
    // впереди под нагрузкой (но НИЖЕ realtime-потока Oboe — аудио-колбэк не голодает).
    readAheadThreadA.startThread (juce::Thread::Priority::high);
    readAheadThreadB.startThread (juce::Thread::Priority::high);
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
    if (initialised.load())
        return true;

    const juce::String err = deviceManager.initialiseWithDefaultDevices (0, 2);
    if (err.isNotEmpty())
    {
        DBG ("AutoMixAudioEngine: initialiseWithDefaultDevices failed: " << err);
        return false;
    }

    // RT-режим (low-latency). Раньше тут НАМЕРЕННО раздували буфер до 4096–8192,
    // чтобы пережить тротлинг. Побочка: большой буфер уводит Oboe/AAudio с MMAP
    // fast-path на Legacy-микшер с колбэком ОБЫЧНОГО приоритета — он и голодает,
    // когда система рисует уведомление/шторку → заикания возвращаются.
    //
    // Теперь запрашиваем НАТИВНЫЙ burst-кратный буфер: тогда Oboe держит
    // low-latency fast-path, а его аудио-колбэк планируется audioserver'ом как
    // real-time (SCHED_FIFO) и переживает скачки CPU приложения. Берём 2×burst —
    // всё ещё fast-path, но с небольшим запасом кадров против единичного пропуска.
    {
        auto setup = deviceManager.getAudioDeviceSetup();
        int rtBuffer = 0;
        if (auto* dev = deviceManager.getCurrentAudioDevice())
        {
            const int burst = dev->getDefaultBufferSize();   // нативный low-latency burst
            const auto sizes = dev->getAvailableBufferSizes();
            if (burst > 0)
                rtBuffer = burst * 2;
            else if (! sizes.isEmpty())
                rtBuffer = sizes.getFirst();                  // самый маленький поддерживаемый
            // не опускаемся ниже минимально поддерживаемого размера устройства
            if (! sizes.isEmpty())
                rtBuffer = juce::jmax (rtBuffer, sizes.getFirst());
        }
        if (rtBuffer > 0 && setup.bufferSize != rtBuffer)
        {
            setup.bufferSize = rtBuffer;
            deviceManager.setAudioDeviceSetup (setup, true);
        }
    }

    deviceManager.addAudioCallback (this);
    initialised.store (true);
    return true;
}

void AutoMixAudioEngine::release()
{
    toneOn.store (false);
    {
        const juce::CriticalSection::ScopedLockType slA (deckA.mutationLock);
        const juce::CriticalSection::ScopedLockType slB (deckB.mutationLock);
        deckA.transport.stop();
        deckB.transport.stop();
    }

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

bool AutoMixAudioEngine::loadDeck (Deck& deck, const juce::String& path)
{
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
        // ~3с предчтения (переживает кратковременный тротлинг при уходе в фон).
        deck.transport.setSource (deck.readerSource.get(), (int) (sourceRate * 3.0),
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
        // ~5с предчтения (декод mp3 тяжелее, чем чтение wav): при переключении
        // приложений система тротлит CPU на ~2с — большой буфер не даёт транспорту
        // осушиться, поэтому звук не заикается. ~1.9 МБ/дек @48k stereo float.
        deck.transport.setSource (deck.mediaCodecSource.get(), (int) (sourceRate * 5.0),
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

    if (std::unique_ptr<juce::AudioFormatReader> reader { formatManager.createReaderFor (file) })
    {
        const int len = (int) reader->lengthInSamples;
        if (len <= 0)
            return false;
        out.setSize (2, len);
        reader->read (&out, 0, len, 0, true, true);
        rate = reader->sampleRate;
        return true;
    }

    return automix::decodeWithMediaCodec (path, out, rate);
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
    deck.transport.setSource (deck.mediaCodecSource.get(), (int) (sourceRate * 5.0),
                              &threadForDeck (deck), sourceRate, 2);
    deck.transport.setPosition (0.0);
    deck.path = {};
    deck.hasTrack.store (true);
    return true;
}

void AutoMixAudioEngine::play()
{
    const juce::CriticalSection::ScopedLockType sl (deckA.mutationLock);
    if (deckA.hasTrack.load())
        deckA.transport.start();
}

void AutoMixAudioEngine::pause()
{
    const juce::CriticalSection::ScopedLockType slA (deckA.mutationLock);
    const juce::CriticalSection::ScopedLockType slB (deckB.mutationLock);
    deckA.transport.stop();
    deckB.transport.stop();
}

void AutoMixAudioEngine::stop()
{
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

void AutoMixAudioEngine::startCrossfade (double durationMs)
{
    long long total = (long long) (durationMs * currentSampleRate / 1000.0);
    if (total < 1) total = 1;
    crossfadeTotal.store (total);

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

void AutoMixAudioEngine::startTransition (double durationMs, double entryMs)
{
    long long total = (long long) (durationMs * currentSampleRate / 1000.0);
    if (total < 1) total = 1;
    crossfadeTotal.store (total);

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
    Deck& d = deckRef (currentDeck.load());
    const juce::CriticalSection::ScopedLockType sl (d.mutationLock);
    if (d.hasTrack.load())
        d.transport.start();
}

void AutoMixAudioEngine::seekCurrent (double ms)
{
    const double clamped = (ms < 0.0 ? 0.0 : ms);
    const int cur = currentDeck.load();
    const juce::CriticalSection::ScopedLockType sl (deckRef (cur).mutationLock);
    deckRef (cur).transport.setPosition (clamped / 1000.0);
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
    const juce::CriticalSection::ScopedLockType sl (d.mutationLock);
    clearDeckUnlocked (d);
}

void AutoMixAudioEngine::setBassSwap (bool enabled)
{
    bassSwapEnabled.store (enabled);
}

// ── Graphic EQ ──────────────────────────────────────────────────────────────
void AutoMixAudioEngine::setEqEnabled (bool enabled)
{
    eqEnabled.store (enabled);
    eqDirty.store (true);
}

void AutoMixAudioEngine::setEqBandGain (int band, float gainDb)
{
    if (band < 0 || band >= kEqBands)
        return;
    eqGainsDb[(size_t) band].store (juce::jlimit (-12.0f, 12.0f, gainDb));
    eqDirty.store (true);
}

void AutoMixAudioEngine::setEqBands (const float* gainsDb, int count)
{
    if (gainsDb == nullptr)
        return;
    const int n = juce::jmin (count, (int) kEqBands);
    for (int i = 0; i < n; ++i)
        eqGainsDb[(size_t) i].store (juce::jlimit (-12.0f, 12.0f, gainsDb[i]));
    eqDirty.store (true);
}

// Audio-thread: rebuild the 10 peaking biquads (RBJ cookbook) from the current
// gains. Called only when eqDirty / the sample rate changed, never per sample.
void AutoMixAudioEngine::recomputeEqCoeffs()
{
    const double fs = currentSampleRate > 0.0 ? currentSampleRate : 44100.0;
    eqDesignedRate = fs;
    for (int band = 0; band < kEqBands; ++band)
    {
        const double f0    = juce::jmin ((double) kEqCenters[(size_t) band], fs * 0.45);
        const double A     = std::pow (10.0, (double) eqGainsDb[(size_t) band].load() / 40.0);
        const double w0    = juce::MathConstants<double>::twoPi * f0 / fs;
        const double cw    = std::cos (w0);
        const double alpha = std::sin (w0) / (2.0 * (double) kEqQ);

        const double b0 = 1.0 + alpha * A;
        const double b1 = -2.0 * cw;
        const double b2 = 1.0 - alpha * A;
        const double a0 = 1.0 + alpha / A;
        const double a1 = -2.0 * cw;
        const double a2 = 1.0 - alpha / A;

        auto& c = eqCoeffs[(size_t) band];
        c.b0 = (float) (b0 / a0);
        c.b1 = (float) (b1 / a0);
        c.b2 = (float) (b2 / a0);
        c.a1 = (float) (a1 / a0);
        c.a2 = (float) (a2 / a0);
    }
}

double AutoMixAudioEngine::positionMsA()
{
    return reportedPosMs[0].load (std::memory_order_relaxed);
}

double AutoMixAudioEngine::lengthMsA()
{
    return reportedLenMs[0].load (std::memory_order_relaxed);
}

//==============================================================================
void AutoMixAudioEngine::audioDeviceAboutToStart (juce::AudioIODevice* device)
{
    if (device != nullptr)
    {
        currentSampleRate = device->getCurrentSampleRate();
        currentBlockSize  = device->getCurrentBufferSizeSamples();
    }

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

    // Bass-swap low-pass: same fixed coefficients for every deck/channel; only a
    // scalar changes at runtime, so there are no coefficient-swap clicks.
    auto bassCoeffs = juce::dsp::IIR::Coefficients<float>::makeLowPass (currentSampleRate, kBassCutoffHz);
    for (auto& f : lowpassA) { f.coefficients = bassCoeffs; f.reset(); }
    for (auto& f : lowpassB) { f.coefficients = bassCoeffs; f.reset(); }
}

void AutoMixAudioEngine::audioDeviceStopped()
{
    const juce::CriticalSection::ScopedLockType slA (deckA.mutationLock);
    const juce::CriticalSection::ScopedLockType slB (deckB.mutationLock);
    deckA.transport.releaseResources();
    deckB.transport.releaseResources();
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

    juce::ScopedNoDenormals noDenormals; // flush IIR (bass-swap + EQ) denormals

    juce::AudioBuffer<float> output (outputChannelData, numOutputChannels, numSamples);

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
        crossfadeActive.store (true);
    }

    const bool   active  = crossfadeActive.load();
    const double total   = (double) crossfadeTotal.load();
    const float  bgA     = baseGainA.load();
    const float  bgB     = baseGainB.load();
    const int    fOut    = fadeOutDeck.load(); // which physical deck fades OUT
    const double halfPi  = juce::MathConstants<double>::halfPi;

    const int ch = juce::jmin (numOutputChannels, 8);
    if (numSamples > scratchA.getNumSamples() || numSamples > scratchB.getNumSamples()
        || ch > scratchA.getNumChannels() || ch > scratchB.getNumChannels())
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

    // Each deck under its OWN lock. tryEnter never blocks the realtime thread; if a
    // deck is mid-swap (loading), we just silence THAT deck's scratch for this block
    // (inaudible — an incoming deck enters at gain≈0). Position/length are published
    // here too, so the lock-free getters stay current without ever contending.
    {
        TryAudioLock la (deckA.mutationLock);
        if (la.locked)
        {
            reportedPosMs[0].store (deckA.transport.getCurrentPosition() * 1000.0, std::memory_order_relaxed);
            reportedLenMs[0].store (deckA.transport.getLengthInSeconds() * 1000.0, std::memory_order_relaxed);
            if (pullA) { const juce::AudioSourceChannelInfo ia (&scratchA, 0, numSamples); deckA.transport.getNextAudioBlock (ia); }
            else       scratchA.clear();
        }
        else scratchA.clear();
    }
    {
        TryAudioLock lb (deckB.mutationLock);
        if (lb.locked)
        {
            reportedPosMs[1].store (deckB.transport.getCurrentPosition() * 1000.0, std::memory_order_relaxed);
            reportedLenMs[1].store (deckB.transport.getLengthInSeconds() * 1000.0, std::memory_order_relaxed);
            if (pullB) { const juce::AudioSourceChannelInfo ib (&scratchB, 0, numSamples); deckB.transport.getNextAudioBlock (ib); }
            else       scratchB.clear();
        }
        else scratchB.clear();
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

    for (int i = 0; i < numSamples; ++i)
    {
        float ga, gb;            // volume envelope (equal power)
        float bassA = 1.0f;      // per-deck bass amount (1 = full low end, 0 = cut)
        float bassB = 1.0f;

        if (active && total > 0.0)
        {
            double t = (double) (crossfadePos + i) / total;
            if (t > 1.0) t = 1.0;
            const float gOut = (float) std::cos (t * halfPi); // outgoing (equal power)
            const float gIn  = (float) std::sin (t * halfPi); // incoming

            // Bass swap (Stage 5), only when enabled. Complementary: incoming
            // gains its low end while the outgoing deck gives up its own.
            float bassOut = 1.0f, bassIn = 1.0f;
            if (bassSwapEnabled.load())
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

        for (int c = 0; c < ch; ++c)
        {
            float av = a[c][i];
            float bv = b[c][i];

            // Keep the low-pass state warm every sample (c < 2 = L/R have filters);
            // subtract the scaled low band to attenuate that deck's bass.
            if (c < 2)
            {
                const float la = lowpassA[(size_t) c].processSample (av);
                const float lb = lowpassB[(size_t) c].processSample (bv);
                av -= cutA * la;
                bv -= cutB * lb;
            }

            o[c][i] = av * ga + bv * gb;
        }
    }

    // ── Graphic EQ on the final LOCAL mix (stereo) ──────────────────────────
    // Runs after the deck mix, so it covers ALL local audio: a single deck or an
    // AutoMix crossfade. Skipped entirely when disabled (no CPU cost when off).
    {
        const bool eqOn = eqEnabled.load();
        if (eqOn)
        {
            if (! eqWasEnabled)               // enable edge: clear stale state, force recompute
            {
                for (auto& z : eqZ1) z.fill (0.0f);
                for (auto& z : eqZ2) z.fill (0.0f);
                eqDirty.store (true);
            }
            if (eqDirty.exchange (false) || eqDesignedRate != currentSampleRate)
                recomputeEqCoeffs();

            const int eqCh = juce::jmin (ch, 2);
            for (int c = 0; c < eqCh; ++c)
            {
                float* d = o[c];
                for (int i = 0; i < numSamples; ++i)
                {
                    float x = d[i];
                    for (int band = 0; band < kEqBands; ++band) // 10-band cascade
                    {
                        const auto& bc = eqCoeffs[(size_t) band];
                        float& z1 = eqZ1[(size_t) band][(size_t) c];
                        float& z2 = eqZ2[(size_t) band][(size_t) c];
                        const float y = bc.b0 * x + z1;          // transposed direct form II
                        z1 = bc.b1 * x - bc.a1 * y + z2;
                        z2 = bc.b2 * x - bc.a2 * y;
                        x = y;
                    }
                    d[i] = x;
                }
            }
        }
        eqWasEnabled = eqOn;
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
            const int out = fOut;                      // deck that faded out
            currentDeck.store (1 - out);               // incoming is now current
            baseGainA.store (out == 0 ? 0.0f : 1.0f);  // outgoing -> 0, incoming -> 1
            baseGainB.store (out == 0 ? 1.0f : 0.0f);
        }
    }
}
