#pragma once

#include <juce_audio_devices/juce_audio_devices.h>
#include <juce_audio_formats/juce_audio_formats.h>
#include <atomic>
#include <memory>

/**
 * Stage 2 of the native AutoMix engine: decode and play ONE real audio file
 * through JUCE -> Oboe.
 *
 * Owns a juce::AudioDeviceManager (drives Oboe on Android) plus a small JUCE
 * playback graph: AudioFormatManager -> AudioFormatReaderSource -> a buffered
 * AudioTransportSource. The audio callback pulls PCM from the transport, so the
 * device plays the decoded track. The Stage 1 440 Hz tone is kept as a quick
 * diagnostic when no track is loaded.
 *
 * Still single-track only: no second deck, mixing, crossfade, time-stretch or
 * model integration (those are later stages).
 */
class AutoMixAudioEngine : public juce::AudioIODeviceCallback
{
public:
    AutoMixAudioEngine();
    ~AutoMixAudioEngine() override;

    /** Open the default output device and attach our callback. Idempotent. */
    bool init();

    /** Detach the callback and close the device. Safe to call multiple times. */
    void release();

    // Stage 1 diagnostic tone (used only when no track is loaded) -------------
    void startTone();
    void stopTone();

    // Stage 2 single-track playback ------------------------------------------
    /** Decode-open a local file. Returns false if missing/unsupported format. */
    bool loadTrack(const juce::String& path);
    void play();
    void pause();
    void stop();

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
    juce::AudioFormatManager formatManager;
    juce::TimeSliceThread readAheadThread { "automix-readahead" };
    std::unique_ptr<juce::AudioFormatReaderSource> readerSource; // wav/flac/ogg via JUCE
    juce::AudioBuffer<float> decodedBuffer;                       // mp3/aac via MediaCodec
    std::unique_ptr<juce::MemoryAudioSource> memorySource;
    juce::AudioTransportSource transport;

    std::atomic<bool> initialised { false };
    std::atomic<bool> toneOn { false };
    std::atomic<bool> hasTrack { false };

    double currentSampleRate { 44100.0 };
    int    currentBlockSize  { 512 };
    double phase { 0.0 };

    static constexpr double kToneHz = 440.0;
    static constexpr float  kToneAmplitude = 0.2f; // quiet, don't deafen

    JUCE_DECLARE_NON_COPYABLE_WITH_LEAK_DETECTOR(AutoMixAudioEngine)
};
