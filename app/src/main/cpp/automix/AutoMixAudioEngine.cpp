#include "AutoMixAudioEngine.h"
#include "MediaCodecDecoder.h"

#include <cmath>

AutoMixAudioEngine::AutoMixAudioEngine()
{
    // WAV + AIFF always; FLAC + Ogg Vorbis because we enabled them in CMake.
    formatManager.registerBasicFormats();
    // Background thread that pre-buffers decoded audio so the realtime callback
    // never touches the file / decoder directly.
    readAheadThread.startThread();
}

AutoMixAudioEngine::~AutoMixAudioEngine()
{
    release();
    transport.setSource (nullptr);
    readerSource.reset();
    memorySource.reset();
    readAheadThread.stopThread (2000);
}

bool AutoMixAudioEngine::init()
{
    if (initialised.load())
        return true;

    // 0 inputs, 2 outputs. On Android JUCE opens an Oboe stream under the hood.
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
    transport.stop();

    if (initialised.load())
    {
        deviceManager.removeAudioCallback (this);
        deviceManager.closeAudioDevice();
        initialised.store (false);
    }
}

void AutoMixAudioEngine::startTone() { toneOn.store (true); }
void AutoMixAudioEngine::stopTone()  { toneOn.store (false); }

bool AutoMixAudioEngine::loadTrack (const juce::String& path)
{
    // Detach any current track first (setSource locks against the audio thread).
    transport.stop();
    hasTrack.store (false);
    transport.setSource (nullptr);
    readerSource.reset();
    memorySource.reset();

    const juce::File file (path);
    if (! file.existsAsFile())
    {
        DBG ("AutoMixAudioEngine: file not found: " << path);
        return false;
    }

    // First try JUCE's own readers (wav/aiff/flac/ogg). createReaderFor picks a
    // format by extension and returns nullptr for types JUCE can't decode.
    if (auto* reader = formatManager.createReaderFor (file))
    {
        readerSource = std::make_unique<juce::AudioFormatReaderSource> (reader, true);

        // readAhead buffer + thread keep file reads off the realtime thread;
        // sourceSampleRateToCorrectFor makes the transport resample to the device.
        transport.setSource (readerSource.get(),
                             32768,
                             &readAheadThread,
                             reader->sampleRate,
                             2);
        transport.setPosition (0.0);
        hasTrack.store (true);
        return true;
    }

    // Fallback for mp3/aac/m4a: decode with the platform MediaCodec into memory,
    // then play that buffer through the same transport (resampled to the device).
    double decodedRate = 0.0;
    if (! automix::decodeWithMediaCodec (path, decodedBuffer, decodedRate))
    {
        DBG ("AutoMixAudioEngine: no JUCE reader and MediaCodec decode failed: " << path);
        return false;
    }

    // MemoryAudioSource references decodedBuffer (kept alive as a member), so the
    // realtime callback only copies already-decoded PCM — no codec work there.
    memorySource = std::make_unique<juce::MemoryAudioSource> (decodedBuffer, false, false);
    transport.setSource (memorySource.get(),
                         0,
                         nullptr,
                         decodedRate,
                         2);
    transport.setPosition (0.0);
    hasTrack.store (true);
    return true;
}

void AutoMixAudioEngine::play()
{
    if (hasTrack.load())
        transport.start();
}

void AutoMixAudioEngine::pause()
{
    transport.stop(); // keeps position
}

void AutoMixAudioEngine::stop()
{
    transport.stop();
    transport.setPosition (0.0);
}

void AutoMixAudioEngine::audioDeviceAboutToStart (juce::AudioIODevice* device)
{
    if (device != nullptr)
    {
        currentSampleRate = device->getCurrentSampleRate();
        currentBlockSize  = device->getCurrentBufferSizeSamples();
    }

    if (currentSampleRate <= 0.0)  currentSampleRate = 44100.0;
    if (currentBlockSize  <= 0)    currentBlockSize  = 512;

    phase = 0.0;
    transport.prepareToPlay (currentBlockSize, currentSampleRate);
}

void AutoMixAudioEngine::audioDeviceStopped()
{
    transport.releaseResources();
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

    juce::AudioBuffer<float> buffer (outputChannelData, numOutputChannels, numSamples);

    // Track playback wins; transport outputs silence when stopped/paused.
    if (hasTrack.load())
    {
        const juce::AudioSourceChannelInfo info (&buffer, 0, numSamples);
        transport.getNextAudioBlock (info);
        return;
    }

    // Stage 1 diagnostic tone when no track is loaded.
    if (toneOn.load())
    {
        const double phaseInc = juce::MathConstants<double>::twoPi * kToneHz / currentSampleRate;

        for (int i = 0; i < numSamples; ++i)
        {
            const float sample = kToneAmplitude * (float) std::sin (phase);
            phase += phaseInc;
            if (phase >= juce::MathConstants<double>::twoPi)
                phase -= juce::MathConstants<double>::twoPi;

            for (int ch = 0; ch < numOutputChannels; ++ch)
                if (outputChannelData[ch] != nullptr)
                    outputChannelData[ch][i] = sample;
        }
        return;
    }

    buffer.clear();
}
