#include "MediaCodecDecoder.h"

#include <media/NdkMediaExtractor.h>
#include <media/NdkMediaCodec.h>
#include <media/NdkMediaFormat.h>

#include <fcntl.h>
#include <unistd.h>
#include <sys/stat.h>
#include <sys/types.h>

#include <cstdint>
#include <cstring>
#include <vector>

namespace automix
{

bool decodeWithMediaCodec (const juce::String& path,
                           juce::AudioBuffer<float>& out,
                           double& outSampleRate)
{
    out.setSize (0, 0);
    outSampleRate = 0.0;

    const int fd = ::open (path.toRawUTF8(), O_RDONLY);
    if (fd < 0)
        return false;

    struct stat st {};
    if (::fstat (fd, &st) != 0 || st.st_size <= 0)
    {
        ::close (fd);
        return false;
    }

    AMediaExtractor* extractor = AMediaExtractor_new();
    if (AMediaExtractor_setDataSourceFd (extractor, fd, 0, (off64_t) st.st_size) != AMEDIA_OK)
    {
        AMediaExtractor_delete (extractor);
        ::close (fd);
        return false;
    }

    // Pick the first audio track.
    const int trackCount = (int) AMediaExtractor_getTrackCount (extractor);
    int audioTrack = -1;
    AMediaFormat* format = nullptr;
    const char* mime = nullptr;

    for (int i = 0; i < trackCount; ++i)
    {
        AMediaFormat* f = AMediaExtractor_getTrackFormat (extractor, i);
        const char* m = nullptr;
        if (AMediaFormat_getString (f, AMEDIAFORMAT_KEY_MIME, &m) && m != nullptr
            && std::strncmp (m, "audio/", 6) == 0)
        {
            audioTrack = i;
            format = f;
            mime = m;
            break;
        }
        AMediaFormat_delete (f);
    }

    if (audioTrack < 0 || format == nullptr)
    {
        AMediaExtractor_delete (extractor);
        ::close (fd);
        return false;
    }

    AMediaExtractor_selectTrack (extractor, audioTrack);

    int32_t sampleRate = 0, channelCount = 0;
    AMediaFormat_getInt32 (format, AMEDIAFORMAT_KEY_SAMPLE_RATE, &sampleRate);
    AMediaFormat_getInt32 (format, AMEDIAFORMAT_KEY_CHANNEL_COUNT, &channelCount);
    if (channelCount <= 0) channelCount = 2;

    AMediaCodec* codec = AMediaCodec_createDecoderByType (mime);
    if (codec == nullptr
        || AMediaCodec_configure (codec, format, nullptr, nullptr, 0) != AMEDIA_OK
        || AMediaCodec_start (codec) != AMEDIA_OK)
    {
        if (codec != nullptr) AMediaCodec_delete (codec);
        AMediaFormat_delete (format);
        AMediaExtractor_delete (extractor);
        ::close (fd);
        return false;
    }

    // Always emit stereo: mono -> duplicated, >2ch -> first two channels.
    std::vector<float> left, right;
    int32_t pcmEncoding = 2;          // 2 = 16-bit (default), 4 = float, 3 = 8-bit
    int32_t outChannels = channelCount;
    bool inputEOS = false, outputEOS = false;

    const auto pushFrames16 = [&] (const int16_t* s, int totalSamples, int ch)
    {
        if (ch <= 0) return;
        const int frames = totalSamples / ch;
        for (int fr = 0; fr < frames; ++fr)
        {
            const float l = s[fr * ch + 0] / 32768.0f;
            const float r = (ch > 1) ? s[fr * ch + 1] / 32768.0f : l;
            left.push_back (l);
            right.push_back (r);
        }
    };

    const auto pushFramesF = [&] (const float* s, int totalSamples, int ch)
    {
        if (ch <= 0) return;
        const int frames = totalSamples / ch;
        for (int fr = 0; fr < frames; ++fr)
        {
            const float l = s[fr * ch + 0];
            const float r = (ch > 1) ? s[fr * ch + 1] : l;
            left.push_back (l);
            right.push_back (r);
        }
    };

    while (! outputEOS)
    {
        if (! inputEOS)
        {
            const ssize_t inIdx = AMediaCodec_dequeueInputBuffer (codec, 5000);
            if (inIdx >= 0)
            {
                size_t bufSize = 0;
                uint8_t* inBuf = AMediaCodec_getInputBuffer (codec, (size_t) inIdx, &bufSize);
                const ssize_t sampleSize = (inBuf != nullptr)
                    ? AMediaExtractor_readSampleData (extractor, inBuf, bufSize) : -1;

                if (sampleSize < 0)
                {
                    AMediaCodec_queueInputBuffer (codec, (size_t) inIdx, 0, 0, 0,
                                                  AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM);
                    inputEOS = true;
                }
                else
                {
                    const int64_t pts = AMediaExtractor_getSampleTime (extractor);
                    AMediaCodec_queueInputBuffer (codec, (size_t) inIdx, 0, (size_t) sampleSize, pts, 0);
                    AMediaExtractor_advance (extractor);
                }
            }
        }

        AMediaCodecBufferInfo info;
        const ssize_t outIdx = AMediaCodec_dequeueOutputBuffer (codec, &info, 5000);
        if (outIdx >= 0)
        {
            size_t outSize = 0;
            uint8_t* outBuf = AMediaCodec_getOutputBuffer (codec, (size_t) outIdx, &outSize);
            if (outBuf != nullptr && info.size > 0)
            {
                uint8_t* data = outBuf + info.offset;
                if (pcmEncoding == 4)
                    pushFramesF (reinterpret_cast<const float*> (data),
                                 info.size / (int) sizeof (float), outChannels);
                else
                    pushFrames16 (reinterpret_cast<const int16_t*> (data),
                                  info.size / (int) sizeof (int16_t), outChannels);
            }

            AMediaCodec_releaseOutputBuffer (codec, (size_t) outIdx, false);
            if ((info.flags & AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM) != 0)
                outputEOS = true;
        }
        else if (outIdx == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED)
        {
            AMediaFormat* of = AMediaCodec_getOutputFormat (codec);
            if (of != nullptr)
            {
                int32_t v = 0;
                if (AMediaFormat_getInt32 (of, AMEDIAFORMAT_KEY_SAMPLE_RATE, &v) && v > 0)   sampleRate  = v;
                if (AMediaFormat_getInt32 (of, AMEDIAFORMAT_KEY_CHANNEL_COUNT, &v) && v > 0)  outChannels = v;
                if (AMediaFormat_getInt32 (of, AMEDIAFORMAT_KEY_PCM_ENCODING, &v) && v > 0)   pcmEncoding = v;
                AMediaFormat_delete (of);
            }
        }
        // INFO_TRY_AGAIN_LATER / INFO_OUTPUT_BUFFERS_CHANGED: just keep looping.
    }

    AMediaCodec_stop (codec);
    AMediaCodec_delete (codec);
    AMediaFormat_delete (format);
    AMediaExtractor_delete (extractor);
    ::close (fd);

    const int frames = (int) left.size();
    if (frames <= 0 || sampleRate <= 0)
        return false;

    out.setSize (2, frames);
    std::memcpy (out.getWritePointer (0), left.data(),  sizeof (float) * (size_t) frames);
    std::memcpy (out.getWritePointer (1), right.data(), sizeof (float) * (size_t) frames);
    outSampleRate = (double) sampleRate;
    return true;
}

} // namespace automix
