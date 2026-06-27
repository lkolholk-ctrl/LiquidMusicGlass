#include "AutoMixAudioEngine.h"

#include <cmath>

AutoMixAudioEngine::~AutoMixAudioEngine()
{
    release();
}

bool AutoMixAudioEngine::init()
{
    if (initialised.load())
        return true;

    // 0 inputs, 2 outputs. On Android JUCE opens an Oboe stream under the hood.
    const juce::String err = deviceManager.initialiseWithDefaultDevices(0, 2);
    if (err.isNotEmpty())
    {
        DBG("AutoMixAudioEngine: initialiseWithDefaultDevices failed: " << err);
        return false;
    }

    deviceManager.addAudioCallback(this);
    initialised.store(true);
    return true;
}

void AutoMixAudioEngine::startTone()
{
    toneOn.store(true);
}

void AutoMixAudioEngine::stopTone()
{
    toneOn.store(false);
}

void AutoMixAudioEngine::release()
{
    toneOn.store(false);

    if (initialised.load())
    {
        deviceManager.removeAudioCallback(this);
        deviceManager.closeAudioDevice();
        initialised.store(false);
    }
}

void AutoMixAudioEngine::audioDeviceAboutToStart(juce::AudioIODevice* device)
{
    if (device != nullptr)
        currentSampleRate = device->getCurrentSampleRate();

    if (currentSampleRate <= 0.0)
        currentSampleRate = 44100.0;

    phase = 0.0;
}

void AutoMixAudioEngine::audioDeviceStopped()
{
    phase = 0.0;
}

void AutoMixAudioEngine::audioDeviceIOCallbackWithContext(const float* const* /*inputChannelData*/,
                                                          int /*numInputChannels*/,
                                                          float* const* outputChannelData,
                                                          int numOutputChannels,
                                                          int numSamples,
                                                          const juce::AudioIODeviceCallbackContext& /*context*/)
{
    const bool playing = toneOn.load();

    if (! playing)
    {
        // Silence: clear every output channel.
        for (int ch = 0; ch < numOutputChannels; ++ch)
            if (outputChannelData[ch] != nullptr)
                juce::FloatVectorOperations::clear(outputChannelData[ch], numSamples);
        return;
    }

    const double phaseInc = juce::MathConstants<double>::twoPi * kToneHz / currentSampleRate;

    for (int i = 0; i < numSamples; ++i)
    {
        const float sample = kToneAmplitude * (float) std::sin(phase);
        phase += phaseInc;
        if (phase >= juce::MathConstants<double>::twoPi)
            phase -= juce::MathConstants<double>::twoPi;

        for (int ch = 0; ch < numOutputChannels; ++ch)
            if (outputChannelData[ch] != nullptr)
                outputChannelData[ch][i] = sample;
    }
}
