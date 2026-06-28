#include "AutoMixAudioEngine.h"
#include "MediaCodecDecoder.h"
#include "MediaCodecAudioSource.h"
#include "TimeStretch.h"

#include <cmath>
#include <memory>

AutoMixAudioEngine::AutoMixAudioEngine()
{
    // WAV + AIFF always; FLAC + Ogg Vorbis because we enabled them in CMake.
    formatManager.registerBasicFormats();
    // One read-ahead thread serves both decks' BufferingAudioSources.
    readAheadThread.startThread();
}

AutoMixAudioEngine::~AutoMixAudioEngine()
{
    release();
    deckA.transport.setSource (nullptr);
    deckB.transport.setSource (nullptr);
    deckA.readerSource.reset(); deckA.memorySource.reset(); deckA.mediaCodecSource.reset();
    deckB.readerSource.reset(); deckB.memorySource.reset(); deckB.mediaCodecSource.reset();
    readAheadThread.stopThread (2000);
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

    deviceManager.addAudioCallback (this);
    initialised.store (true);
    return true;
}

void AutoMixAudioEngine::release()
{
    toneOn.store (false);
    deckA.transport.stop();
    deckB.transport.stop();

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
bool AutoMixAudioEngine::loadDeck (Deck& deck, const juce::String& path)
{
    deck.transport.stop();
    deck.hasTrack.store (false);
    deck.transport.setSource (nullptr);
    deck.readerSource.reset();
    deck.memorySource.reset();
    deck.mediaCodecSource.reset();

    const juce::File file (path);
    if (! file.existsAsFile())
    {
        DBG ("AutoMixAudioEngine: file not found: " << path);
        return false;
    }

    // wav/aiff/flac/ogg via JUCE.
    if (auto* reader = formatManager.createReaderFor (file))
    {
        deck.sourceSampleRate = reader->sampleRate;
        deck.readerSource = std::make_unique<juce::AudioFormatReaderSource> (reader, true);
        deck.transport.setSource (deck.readerSource.get(), 32768, &readAheadThread, reader->sampleRate, 2);
        deck.transport.setPosition (0.0);
        deck.path = path;
        deck.hasTrack.store (true);
        return true;
    }

    // mp3/aac/m4a via STREAMING MediaCodec source — playback starts immediately
    // (only codec config up front), decode happens on the read-ahead thread.
    if (auto streaming = automix::MediaCodecAudioSource::create (path))
    {
        deck.sourceSampleRate = streaming->getSampleRate();
        deck.mediaCodecSource = std::move (streaming);
        // 64k-frame look-ahead (~1.3 s @ 48k) on the shared read-ahead thread.
        deck.transport.setSource (deck.mediaCodecSource.get(), 1 << 16, &readAheadThread,
                                  deck.sourceSampleRate, 2);
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
    if (! deckB.hasTrack.load() || deckB.path.isEmpty() || bpmA <= 0.0 || bpmB <= 0.0)
        return false;

    // Full source PCM of B (independent of the currently-set playing source).
    juce::AudioBuffer<float> srcB;
    double srcRate = 0.0;
    if (! decodeFullPCM (deckB.path, srcB, srcRate))
        return false;

    // Match B's tempo to A: speed factor = bpmA/bpmB, so duration ratio = bpmB/bpmA.
    const double timeRatio = bpmB / bpmA;

    juce::AudioBuffer<float> stretched;
    if (! automix::timeStretchOffline (srcB, srcRate, timeRatio, stretched))
        return false;

    // Swap deck B to play the beat-matched buffer.
    deckB.transport.stop();
    deckB.transport.setSource (nullptr);
    deckB.readerSource.reset();
    deckB.memorySource.reset();                 // release ref to old decodedBuffer
    deckB.decodedBuffer = std::move (stretched);
    deckB.memorySource = std::make_unique<juce::MemoryAudioSource> (deckB.decodedBuffer, false, false);
    deckB.transport.setSource (deckB.memorySource.get(), 0, nullptr, srcRate, 2);
    deckB.transport.setPosition (0.0);
    deckB.sourceSampleRate = srcRate;
    deckB.hasTrack.store (true);
    return true;
}

bool AutoMixAudioEngine::loadTrack  (const juce::String& path) { return loadDeck (deckA, path); }
bool AutoMixAudioEngine::loadTrackA (const juce::String& path) { return loadDeck (deckA, path); }
bool AutoMixAudioEngine::loadTrackB (const juce::String& path) { return loadDeck (deckB, path); }

void AutoMixAudioEngine::play()
{
    if (deckA.hasTrack.load())
        deckA.transport.start();
}

void AutoMixAudioEngine::pause()
{
    deckA.transport.stop();
    deckB.transport.stop();
}

void AutoMixAudioEngine::stop()
{
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
    deckA.hasTrack.store (false); // audio thread stops pulling deck A first
    deckA.transport.stop();
    deckA.transport.setSource (nullptr);
    deckA.readerSource.reset();
    deckA.memorySource.reset();
    deckA.mediaCodecSource.reset();
    deckA.decodedBuffer.setSize (0, 0);
}

// ── Stage 8: full LOCAL player (ping-pong decks) ───────────────────────────

bool AutoMixAudioEngine::loadIncoming (const juce::String& path)
{
    return loadDeck (deckRef (1 - currentDeck.load()), path);
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
    if (d.hasTrack.load())
        d.transport.start();
}

void AutoMixAudioEngine::seekCurrent (double ms)
{
    deckRef (currentDeck.load()).transport.setPosition ((ms < 0.0 ? 0.0 : ms) / 1000.0);
}

double AutoMixAudioEngine::positionMsCurrent()
{
    return deckRef (currentDeck.load()).transport.getCurrentPosition() * 1000.0;
}

double AutoMixAudioEngine::lengthMsCurrent()
{
    return deckRef (currentDeck.load()).transport.getLengthInSeconds() * 1000.0;
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
    d.hasTrack.store (false);
    d.transport.stop();
    d.transport.setSource (nullptr);
    d.readerSource.reset();
    d.memorySource.reset();
    d.mediaCodecSource.reset();
    d.decodedBuffer.setSize (0, 0);
}

void AutoMixAudioEngine::setBassSwap (bool enabled)
{
    bassSwapEnabled.store (enabled);
}

double AutoMixAudioEngine::positionMsA()
{
    return deckA.transport.getCurrentPosition() * 1000.0;
}

double AutoMixAudioEngine::lengthMsA()
{
    return deckA.transport.getLengthInSeconds() * 1000.0;
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
    deckA.transport.prepareToPlay (currentBlockSize, currentSampleRate);
    deckB.transport.prepareToPlay (currentBlockSize, currentSampleRate);
    scratchA.setSize (2, currentBlockSize);
    scratchB.setSize (2, currentBlockSize);

    // Bass-swap low-pass: same fixed coefficients for every deck/channel; only a
    // scalar changes at runtime, so there are no coefficient-swap clicks.
    auto bassCoeffs = juce::dsp::IIR::Coefficients<float>::makeLowPass (currentSampleRate, kBassCutoffHz);
    for (auto& f : lowpassA) { f.coefficients = bassCoeffs; f.reset(); }
    for (auto& f : lowpassB) { f.coefficients = bassCoeffs; f.reset(); }
}

void AutoMixAudioEngine::audioDeviceStopped()
{
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

    juce::AudioBuffer<float> output (outputChannelData, numOutputChannels, numSamples);

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

    // Pull each deck into its scratch buffer (silence when a deck isn't playing).
    scratchA.setSize (numOutputChannels, numSamples, false, false, true);
    scratchB.setSize (numOutputChannels, numSamples, false, false, true);

    if (aHas) { const juce::AudioSourceChannelInfo ia (&scratchA, 0, numSamples); deckA.transport.getNextAudioBlock (ia); }
    else        scratchA.clear();
    if (bHas) { const juce::AudioSourceChannelInfo ib (&scratchB, 0, numSamples); deckB.transport.getNextAudioBlock (ib); }
    else        scratchB.clear();

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
