#pragma once

#include <juce_audio_devices/juce_audio_devices.h>
#include <juce_audio_formats/juce_audio_formats.h>
#include <juce_dsp/juce_dsp.h>
#include <array>
#include <atomic>
#include <memory>

/**
 * Stage 3 of the native AutoMix engine: TWO decks + equal-power crossfade.
 *
 * Each deck (A and B) decodes its own file — JUCE readers for wav/aiff/flac/ogg,
 * or the platform MediaCodec for mp3/aac — into an AudioTransportSource. The
 * realtime callback pulls both decks and sums them with a per-sample equal-power
 * envelope (cos/sin), so a crossfade has no clicks and no mid-point level dip.
 *
 * Still only volume crossfade: no time-stretch, beat-match, EQ or model
 * integration (those are later stages).
 */
class AutoMixAudioEngine : public juce::AudioIODeviceCallback
{
public:
    AutoMixAudioEngine();
    ~AutoMixAudioEngine() override;

    bool init();
    void release();

    // Stage 1 diagnostic tone (only when neither deck has a track) -----------
    void startTone();
    void stopTone();

    // Stage 2/3 playback -----------------------------------------------------
    bool loadTrack  (const juce::String& path);   // back-compat alias -> deck A
    bool loadTrackA (const juce::String& path);
    bool loadTrackB (const juce::String& path);
    void play();
    void pause();
    void stop();

    /** Equal-power crossfade deck A -> deck B over durationMs. Starts both decks. */
    void startCrossfade (double durationMs);

    /**
     * Stage 4: pre-stretch deck B so its tempo matches deck A (bpmB -> bpmA),
     * pitch preserved. Heavy/offline — call off the audio & main threads, before
     * starting the crossfade. After this, deck B plays the beat-matched audio.
     */
    bool prepareStretchB (double bpmA, double bpmB);

    // juce::AudioIODeviceCallback
    void audioDeviceAboutToStart (juce::AudioIODevice* device) override;
    void audioDeviceStopped() override;
    void audioDeviceIOCallbackWithContext (const float* const* inputChannelData,
                                           int numInputChannels,
                                           float* const* outputChannelData,
                                           int numOutputChannels,
                                           int numSamples,
                                           const juce::AudioIODeviceCallbackContext& context) override;

private:
    struct Deck
    {
        juce::AudioTransportSource transport;
        std::unique_ptr<juce::AudioFormatReaderSource> readerSource; // wav/flac/ogg
        juce::AudioBuffer<float> decodedBuffer;                      // mp3/aac or stretched
        std::unique_ptr<juce::MemoryAudioSource> memorySource;
        std::atomic<bool> hasTrack { false };
        juce::String path;                                           // for re-decode/stretch
        double sourceSampleRate { 0.0 };

        Deck() = default;
        JUCE_DECLARE_NON_COPYABLE (Deck)
    };

    bool loadDeck (Deck& deck, const juce::String& path);
    bool decodeFullPCM (const juce::String& path, juce::AudioBuffer<float>& out, double& rate);

    juce::AudioDeviceManager deviceManager;
    juce::AudioFormatManager formatManager;
    juce::TimeSliceThread readAheadThread { "automix-readahead" };

    Deck deckA, deckB;
    juce::AudioBuffer<float> scratchA, scratchB; // per-block pull buffers (audio thread)

    // Bass-swap: one low-pass per deck per channel extracts the low band so we
    // can scale each deck's bass with a scalar (fixed coefficients -> no clicks).
    std::array<juce::dsp::IIR::Filter<float>, 2> lowpassA, lowpassB;
    static constexpr float kBassCutoffHz = 150.0f;

    std::atomic<bool> initialised { false };
    std::atomic<bool> toneOn { false };

    // Crossfade state. crossfadeStart is latched by the audio thread so the
    // sample position resets in sync; everything else is plain atomics.
    std::atomic<bool> crossfadeStart  { false };
    std::atomic<bool> crossfadeActive { false };
    std::atomic<long long> crossfadeTotal { 0 };  // total samples
    long long crossfadePos { 0 };                 // audio-thread only
    std::atomic<float> baseGainA { 1.0f };        // gains outside an active crossfade
    std::atomic<float> baseGainB { 0.0f };

    double currentSampleRate { 44100.0 };
    int    currentBlockSize  { 512 };
    double phase { 0.0 };

    static constexpr double kToneHz = 440.0;
    static constexpr float  kToneAmplitude = 0.2f;

    JUCE_DECLARE_NON_COPYABLE_WITH_LEAK_DETECTOR (AutoMixAudioEngine)
};
