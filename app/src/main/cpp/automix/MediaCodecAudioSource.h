#pragma once

#include <juce_audio_basics/juce_audio_basics.h>

#include <media/NdkMediaExtractor.h>
#include <media/NdkMediaCodec.h>
#include <media/NdkMediaFormat.h>

#include <vector>
#include <memory>
#include <cstdint>

namespace automix
{
    /**
     * Stage 8 — STREAMING mp3/aac/m4a source for instant playback.
     *
     * Unlike decodeWithMediaCodec (which decodes the WHOLE file into RAM before a
     * single sample plays — seconds of latency + tens of MB), this is a JUCE
     * PositionableAudioSource that decodes on demand. Wrapped by AudioTransportSource
     * with a read-ahead thread, playback starts in milliseconds (only codec config
     * happens up front) and memory stays bounded to the look-ahead window.
     *
     * getNextAudioBlock runs on the transport's read-ahead (background) thread, never
     * the audio thread, so the blocking NDK dequeue calls here cause no glitches.
     */
    class MediaCodecAudioSource : public juce::PositionableAudioSource
    {
    public:
        /** Open a local file by path and configure the decoder. Returns nullptr on failure. */
        static std::unique_ptr<MediaCodecAudioSource> create (const juce::String& path);

        /** Open from a file descriptor (e.g. content:// via openFileDescriptor) — no copy.
         *  The fd is dup()'d internally, so the caller may close its own fd right after.
         *  size <= 0 → derived via fstat. Returns nullptr on failure. */
        static std::unique_ptr<MediaCodecAudioSource> createFromFd (int fd, int64_t offset, int64_t size);

        ~MediaCodecAudioSource() override;

        double getSampleRate() const { return sampleRate; }

        // juce::AudioSource
        void prepareToPlay (int, double) override {}
        void releaseResources() override {}
        void getNextAudioBlock (const juce::AudioSourceChannelInfo& info) override;

        // juce::PositionableAudioSource
        void setNextReadPosition (juce::int64 newPosition) override;
        juce::int64 getNextReadPosition() const override { return readPos; }
        juce::int64 getTotalLength() const override { return totalFrames; }
        bool isLooping() const override { return false; }

    private:
        MediaCodecAudioSource() = default;
        bool openFile (const juce::String& path);
        bool openFd (int fd, int64_t offset, int64_t size);
        bool configureFromExtractor();   // shared: pick audio track + start codec
        void closeCodec();
        /** Decode until at least framesWanted frames are buffered, or EOS. */
        void fillLeftover (int framesWanted);
        void push16 (const int16_t* s, int totalSamples);
        void pushF  (const float*  s, int totalSamples);

        int fd = -1;
        AMediaExtractor* extractor = nullptr;
        AMediaCodec*     codec     = nullptr;

        double  sampleRate  = 44100.0;
        int     outChannels = 2;
        int32_t pcmEncoding = 2;            // 2 = 16-bit, 4 = float
        juce::int64 totalFrames = 0;

        juce::int64 readPos = 0;            // next frame index to output
        bool inputEOS = false, outputEOS = false;

        // Decoded-but-not-yet-consumed frames (deinterleaved); consumed prefix tracked
        // by leftoverStart, compacted periodically to keep memory bounded.
        std::vector<float> leftBuf, rightBuf;
        size_t leftoverStart = 0;

        juce::CriticalSection lock;         // read vs seek (both on the read-ahead thread)

        JUCE_DECLARE_NON_COPYABLE_WITH_LEAK_DETECTOR (MediaCodecAudioSource)
    };
}
