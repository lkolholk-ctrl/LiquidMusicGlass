#pragma once

#include <juce_audio_basics/juce_audio_basics.h>

namespace automix
{
    /**
     * Stage 2b: decode an mp3/aac/m4a file (anything the platform MediaCodec
     * supports) fully into a stereo float buffer, using the Android NDK media
     * APIs (AMediaExtractor + AMediaCodec, system libmediandk — no extra binary).
     *
     * Used as the fallback when JUCE's own readers can't open a file. wav/flac/
     * ogg/aiff keep going through JUCE directly; only mp3/aac land here.
     *
     * @param path           absolute local file path
     * @param out            filled with 2-channel float PCM on success
     * @param outSampleRate  the decoded sample rate (Hz)
     * @returns false on any failure (open / no audio track / decode error)
     */
    bool decodeWithMediaCodec (const juce::String& path,
                               juce::AudioBuffer<float>& out,
                               double& outSampleRate);
}
