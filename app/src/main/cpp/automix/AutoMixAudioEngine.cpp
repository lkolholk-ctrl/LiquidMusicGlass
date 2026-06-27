#include "AutoMixAudioEngine.h"
#include "MediaCodecDecoder.h"

#include <cmath>

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
    deckA.readerSource.reset(); deckA.memorySource.reset();
    deckB.readerSource.reset(); deckB.memorySource.reset();
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

    const juce::File file (path);
    if (! file.existsAsFile())
    {
        DBG ("AutoMixAudioEngine: file not found: " << path);
        return false;
    }

    // wav/aiff/flac/ogg via JUCE.
    if (auto* reader = formatManager.createReaderFor (file))
    {
        deck.readerSource = std::make_unique<juce::AudioFormatReaderSource> (reader, true);
        deck.transport.setSource (deck.readerSource.get(), 32768, &readAheadThread, reader->sampleRate, 2);
        deck.transport.setPosition (0.0);
        deck.hasTrack.store (true);
        return true;
    }

    // mp3/aac/m4a via MediaCodec -> in-memory float buffer.
    double decodedRate = 0.0;
    if (! automix::decodeWithMediaCodec (path, deck.decodedBuffer, decodedRate))
    {
        DBG ("AutoMixAudioEngine: no JUCE reader and MediaCodec decode failed: " << path);
        return false;
    }

    deck.memorySource = std::make_unique<juce::MemoryAudioSource> (deck.decodedBuffer, false, false);
    deck.transport.setSource (deck.memorySource.get(), 0, nullptr, decodedRate, 2);
    deck.transport.setPosition (0.0);
    deck.hasTrack.store (true);
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
    baseGainA.store (1.0f);
    baseGainB.store (0.0f);
}

void AutoMixAudioEngine::startCrossfade (double durationMs)
{
    long long total = (long long) (durationMs * currentSampleRate / 1000.0);
    if (total < 1) total = 1;
    crossfadeTotal.store (total);

    // Both decks must be running for the mix; B restarts from its beginning.
    if (deckA.hasTrack.load())
        deckA.transport.start();
    if (deckB.hasTrack.load())
    {
        deckB.transport.setPosition (0.0);
        deckB.transport.start();
    }

    baseGainA.store (1.0f);
    baseGainB.store (0.0f);
    crossfadeStart.store (true); // audio thread resets position + activates
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
        float ga, gb;
        if (active && total > 0.0)
        {
            double t = (double) (crossfadePos + i) / total;
            if (t > 1.0) t = 1.0;
            ga = (float) std::cos (t * halfPi); // equal power: ga*ga + gb*gb == 1
            gb = (float) std::sin (t * halfPi);
        }
        else
        {
            ga = bgA;
            gb = bgB;
        }

        for (int c = 0; c < ch; ++c)
            o[c][i] = a[c][i] * ga + b[c][i] * gb;
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
            baseGainA.store (0.0f); // A fully faded out
            baseGainB.store (1.0f); // B fully in
        }
    }
}
