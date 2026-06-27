#include "TimeStretch.h"

#include <signalsmith-stretch.h>

#include <cmath>
#include <cstring>
#include <vector>

namespace automix
{

bool timeStretchOffline (const juce::AudioBuffer<float>& in,
                         double sampleRate,
                         double timeRatio,
                         juce::AudioBuffer<float>& out)
{
    const int ch       = in.getNumChannels();
    const int inFrames = in.getNumSamples();
    if (ch <= 0 || inFrames <= 0 || sampleRate <= 0.0 || timeRatio <= 0.0)
        return false;

    // timeRatio = output_duration / input_duration (= bpmB / bpmA to match B to A).
    const int outFrames = (int) std::llround ((double) inFrames * timeRatio);
    if (outFrames <= 0)
        return false;

    // Signalsmith Stretch (MIT, header-only). Offline whole-buffer process; pitch
    // preserved (transpose factor 1.0). The stretch amount is simply the ratio of
    // requested output samples to input samples.
    signalsmith::stretch::SignalsmithStretch<float> stretch;
    stretch.presetDefault (ch, (float) sampleRate);
    stretch.setTransposeFactor (1.0f); // keep original pitch (no chipmunk)

    const int lat = stretch.outputLatency();

    // The stretcher is a streaming STFT, so output is delayed by `lat` samples.
    // We process the whole input (-> outFrames), then flush the tail (-> lat), and
    // take the latency-compensated window [lat, lat + outFrames) as the result.
    const int total = outFrames + lat;
    std::vector<std::vector<float>> work ((size_t) ch, std::vector<float> ((size_t) total, 0.0f));
    std::vector<float*> procPtrs ((size_t) ch), flushPtrs ((size_t) ch);
    for (int c = 0; c < ch; ++c)
    {
        procPtrs[(size_t) c]  = work[(size_t) c].data();
        flushPtrs[(size_t) c] = work[(size_t) c].data() + outFrames;
    }

    const float* const* inPtrs = in.getArrayOfReadPointers();
    stretch.process (inPtrs, inFrames, procPtrs.data(), outFrames);
    stretch.flush (flushPtrs.data(), lat);

    out.setSize (ch, outFrames);
    for (int c = 0; c < ch; ++c)
        std::memcpy (out.getWritePointer (c),
                     work[(size_t) c].data() + lat,
                     sizeof (float) * (size_t) outFrames);
    return true;
}

} // namespace automix
