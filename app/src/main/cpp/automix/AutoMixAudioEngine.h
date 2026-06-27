#pragma once

#include <juce_audio_devices/juce_audio_devices.h>
#include <atomic>

/**
 * Stage 1 of the native AutoMix engine: prove JUCE -> Oboe audio output works.
 *
 * Owns a juce::AudioDeviceManager (on Android JUCE drives Oboe under the hood)
 * and an AudioIODeviceCallback that, when the tone is enabled, fills the output
 * with a quiet 440 Hz sine. Nothing else yet: no decoding, mixing, time-stretch
 * or model integration (those are later stages).
 */
class AutoMixAudioEngine : public juce::AudioIODeviceCallback
{
public:
    AutoMixAudioEngine() = default;
    ~AutoMixAudioEngine() override;

    /** Open the default output device and attach our callback. Idempotent. */
    bool init();

    /** Enable / disable the 440 Hz test tone (audio callback keeps running). */
    void startTone();
    void stopTone();

    /** Detach the callback and close the device. Safe to call multiple times. */
    void release();

    // juce::AudioIODeviceCallback
    void audioDeviceAboutToStart(juce::AudioIODevice* device) override;
    void audioDeviceStopped() override;
    void audioDeviceIOCallbackWithContext(const float* const* inputChannelData,
                                          int numInputChannels,
                                          float* const* outputChannelData,
                                          int numOutputChannels,
                                          int numSamples,
                                          const juce::AudioIODeviceCallbackContext& context) override;

private:
    juce::AudioDeviceManager deviceManager;
    std::atomic<bool> initialised { false };
    std::atomic<bool> toneOn { false };

    double currentSampleRate { 44100.0 };
    double phase { 0.0 };

    static constexpr double kToneHz = 440.0;
    static constexpr float  kToneAmplitude = 0.2f; // quiet, don't deafen

    JUCE_DECLARE_NON_COPYABLE_WITH_LEAK_DETECTOR(AutoMixAudioEngine)
};
