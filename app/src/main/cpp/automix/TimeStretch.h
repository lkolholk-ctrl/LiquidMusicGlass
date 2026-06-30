#pragma once

#include <juce_audio_basics/juce_audio_basics.h>

namespace automix
{
    /**
     * Stage 4: offline (prefetch) time-stretch using Signalsmith Stretch
     * (MIT, header-only). Pitch is preserved (no chipmunk effect); tempo changes.
     *
     * @param in          source PCM (any channel count, interleaved-by-channel)
     * @param sampleRate  source sample rate (Hz)
     * @param timeRatio   output_duration / input_duration. To match deck B's
     *                    tempo to deck A use bpmB / bpmA (B faster than A -> <1).
     * @param out         filled with the stretched PCM on success
     * @returns false on bad args or empty output
     */
    bool timeStretchOffline (const juce::AudioBuffer<float>& in,
                             double sampleRate,
                             double timeRatio,
                             juce::AudioBuffer<float>& out);
}
