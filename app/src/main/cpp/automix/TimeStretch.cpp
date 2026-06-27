#include "TimeStretch.h"

#include <rubberband/RubberBandStretcher.h>

#include <algorithm>
#include <cstring>
#include <vector>

namespace automix
{

bool timeStretchOffline (const juce::AudioBuffer<float>& in,
                         double sampleRate,
                         double timeRatio,
                         juce::AudioBuffer<float>& out)
{
    using RubberBand::RubberBandStretcher;

    const int ch       = in.getNumChannels();
    const int inFrames = in.getNumSamples();
    if (ch <= 0 || inFrames <= 0 || sampleRate <= 0.0 || timeRatio <= 0.0)
        return false;

    // Offline mode: study the whole input, then process it, then drain. The R3
    // ("finer") engine gives the best transient/quality, fine for a prefetch.
    RubberBandStretcher rb ((size_t) sampleRate,
                            (size_t) ch,
                            RubberBandStretcher::OptionProcessOffline
                              | RubberBandStretcher::OptionEngineFiner,
                            timeRatio,
                            1.0 /* pitch scale: keep original pitch */);
    rb.setExpectedInputDuration ((size_t) inFrames);

    const float* const* inPtrs = in.getArrayOfReadPointers();
    rb.study   (inPtrs, (size_t) inFrames, true);
    rb.process (inPtrs, (size_t) inFrames, true);

    std::vector<std::vector<float>> outCh ((size_t) ch);

    const int block = 8192;
    std::vector<std::vector<float>> tmp ((size_t) ch, std::vector<float> ((size_t) block));
    std::vector<float*> outPtrs ((size_t) ch);
    for (int c = 0; c < ch; ++c)
        outPtrs[(size_t) c] = tmp[(size_t) c].data();

    // available() returns frames ready, or -1 when fully drained. In offline
    // mode all output is ready after process(final), so <= 0 means done.
    while (true)
    {
        const int avail = rb.available();
        if (avail <= 0)
            break;

        const int toGet = std::min (avail, block);
        const size_t got = rb.retrieve (outPtrs.data(), (size_t) toGet);
        if (got == 0)
            break;

        for (int c = 0; c < ch; ++c)
            outCh[(size_t) c].insert (outCh[(size_t) c].end(),
                                      tmp[(size_t) c].begin(),
                                      tmp[(size_t) c].begin() + (long) got);
    }

    const int outFrames = (int) outCh[0].size();
    if (outFrames <= 0)
        return false;

    out.setSize (ch, outFrames);
    for (int c = 0; c < ch; ++c)
        std::memcpy (out.getWritePointer (c),
                     outCh[(size_t) c].data(),
                     sizeof (float) * (size_t) outFrames);
    return true;
}

} // namespace automix
