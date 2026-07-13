#include "AudioFxChain.h"

#include <cmath>

namespace
{
    // Те же центры полос, что в Kotlin/UI (ISO-октавы).
    constexpr std::array<double, 10> kEqCenters {
        31.0, 62.0, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0
    };
    // Дефолтные центры параметрических полос (те же, что в Kotlin/UI).
    constexpr std::array<float, 5> kParamDefFreq { 60.0f, 250.0f, 1000.0f, 4000.0f, 12000.0f };
}

AudioFxChain::AudioFxChain()
{
    for (auto& g : eqGainsDb)
        g.store (0.0f);
    for (int b = 0; b < kParamBands; ++b)
    {
        paramFreq[(size_t) b].store   (kParamDefFreq[(size_t) b]);
        paramQ[(size_t) b].store      (1.0f);
        paramGainDb[(size_t) b].store (0.0f);
    }
}

void AudioFxChain::prepare (double sampleRate, int maxBlockSize, int numChannels)
{
    sr = sampleRate > 0.0 ? sampleRate : 44100.0;

    juce::dsp::ProcessSpec spec;
    spec.sampleRate       = sr;
    spec.maximumBlockSize = (juce::uint32) juce::jmax (1, maxBlockSize);
    spec.numChannels      = (juce::uint32) juce::jmax (1, juce::jmin (numChannels, kMaxCh));

    compressor.prepare (spec);
    limiter.prepare (spec);
    reverb.prepare (spec);

    preampGain.reset (sr, 0.02);   // 20 мс рамп — без зиппера
    preampGain.setCurrentAndTargetValue (juce::Decibels::decibelsToGain (preampGainDb.load()));

    compDirty.store (true);
    limDirty.store (true);
    dirty.store (true);
    reset();
}

void AudioFxChain::reset()
{
    for (auto& cs : eqS) for (auto& s : cs) s = {};
    for (auto& cs : paramS) for (auto& s : cs) s = {};
    for (auto& s : bassS)      s = {};
    for (auto& s : loudLowS)   s = {};
    for (auto& s : loudHighS)  s = {};
    compressor.reset();
    limiter.reset();
    reverb.reset();
}

// ── Сеттеры IIR-стадий ───────────────────────────────────────────────────────
void AudioFxChain::setEqBandGain (int band, float gainDb)
{
    if (band < 0 || band >= kEqBands) return;
    eqGainsDb[(size_t) band].store (juce::jlimit (-12.0f, 12.0f, gainDb));
    dirty.store (true);
}

void AudioFxChain::setEqBands (const float* gainsDb, int count)
{
    if (gainsDb == nullptr) return;
    const int n = juce::jmin (count, (int) kEqBands);
    for (int i = 0; i < n; ++i)
        eqGainsDb[(size_t) i].store (juce::jlimit (-12.0f, 12.0f, gainsDb[i]));
    dirty.store (true);
}

void AudioFxChain::setParamBand (int band, float freqHz, float q, float gainDb)
{
    if (band < 0 || band >= kParamBands) return;
    paramFreq[(size_t) band].store   (juce::jlimit (20.0f, 20000.0f, freqHz));
    paramQ[(size_t) band].store      (juce::jlimit (0.1f, 10.0f, q));
    paramGainDb[(size_t) band].store (juce::jlimit (-12.0f, 12.0f, gainDb));
    dirty.store (true);
}

void AudioFxChain::setBassBoost (bool on, float freqHz, float gainDb)
{
    bassEnabled.store (on);
    bassFreq.store    (juce::jlimit (40.0f, 200.0f, freqHz));
    bassGainDb.store  (juce::jlimit (0.0f, 12.0f, gainDb));
    dirty.store (true);
}

void AudioFxChain::setCompressor (bool on, float threshDb, float ratio, float attackMs, float releaseMs)
{
    compEnabled.store (on);
    compThresh.store  (juce::jlimit (-60.0f, 0.0f, threshDb));
    compRatio.store   (juce::jlimit (1.0f, 20.0f, ratio));
    compAttack.store  (juce::jlimit (1.0f, 200.0f, attackMs));
    compRelease.store (juce::jlimit (10.0f, 1000.0f, releaseMs));
    compDirty.store (true);
}

void AudioFxChain::setLimiter (bool on, float threshDb, float releaseMs)
{
    limEnabled.store (on);
    limThresh.store  (juce::jlimit (-12.0f, 0.0f, threshDb));
    limRelease.store (juce::jlimit (1.0f, 1000.0f, releaseMs));
    limDirty.store (true);
}

void AudioFxChain::setReverb (bool on, float roomSize, float damping, float wet)
{
    reverbEnabled.store (on);
    revRoom.store (juce::jlimit (0.0f, 1.0f, roomSize));
    revDamp.store (juce::jlimit (0.0f, 1.0f, damping));
    revWet.store  (juce::jlimit (0.0f, 1.0f, wet));
    revDirty.store (true);
}

// ── RBJ cookbook (1:1 с juce::dsp::IIR::Coefficients::make*) ──────────────────
AudioFxChain::Biquad AudioFxChain::makePeak (double fs, double f0, double Q, double gainDb)
{
    const double A     = std::pow (10.0, gainDb / 40.0);
    const double w0    = juce::MathConstants<double>::twoPi * juce::jmin (f0, fs * 0.45) / fs;
    const double cw    = std::cos (w0);
    const double alpha = std::sin (w0) / (2.0 * juce::jmax (0.0001, Q));

    const double b0 = 1.0 + alpha * A;
    const double b1 = -2.0 * cw;
    const double b2 = 1.0 - alpha * A;
    const double a0 = 1.0 + alpha / A;
    const double a1 = -2.0 * cw;
    const double a2 = 1.0 - alpha / A;
    return { (float) (b0 / a0), (float) (b1 / a0), (float) (b2 / a0),
             (float) (a1 / a0), (float) (a2 / a0) };
}

AudioFxChain::Biquad AudioFxChain::makeLowShelf (double fs, double f0, double gainDb)
{
    const double A   = std::pow (10.0, gainDb / 40.0);
    const double w0  = juce::MathConstants<double>::twoPi * juce::jmin (f0, fs * 0.45) / fs;
    const double cw  = std::cos (w0);
    const double sw  = std::sin (w0);
    const double alpha = sw / 2.0 * std::sqrt (2.0);     // shelf slope S = 1
    const double tsa = 2.0 * std::sqrt (A) * alpha;

    const double b0 =      A * ((A + 1.0) - (A - 1.0) * cw + tsa);
    const double b1 =  2.0 * A * ((A - 1.0) - (A + 1.0) * cw);
    const double b2 =      A * ((A + 1.0) - (A - 1.0) * cw - tsa);
    const double a0 =          (A + 1.0) + (A - 1.0) * cw + tsa;
    const double a1 = -2.0 *   ((A - 1.0) + (A + 1.0) * cw);
    const double a2 =          (A + 1.0) + (A - 1.0) * cw - tsa;
    return { (float) (b0 / a0), (float) (b1 / a0), (float) (b2 / a0),
             (float) (a1 / a0), (float) (a2 / a0) };
}

AudioFxChain::Biquad AudioFxChain::makeHighShelf (double fs, double f0, double gainDb)
{
    const double A   = std::pow (10.0, gainDb / 40.0);
    const double w0  = juce::MathConstants<double>::twoPi * juce::jmin (f0, fs * 0.45) / fs;
    const double cw  = std::cos (w0);
    const double sw  = std::sin (w0);
    const double alpha = sw / 2.0 * std::sqrt (2.0);
    const double tsa = 2.0 * std::sqrt (A) * alpha;

    const double b0 =      A * ((A + 1.0) + (A - 1.0) * cw + tsa);
    const double b1 = -2.0 * A * ((A - 1.0) + (A + 1.0) * cw);
    const double b2 =      A * ((A + 1.0) + (A - 1.0) * cw - tsa);
    const double a0 =          (A + 1.0) - (A - 1.0) * cw + tsa;
    const double a1 =  2.0 *   ((A - 1.0) - (A + 1.0) * cw);
    const double a2 =          (A + 1.0) - (A - 1.0) * cw - tsa;
    return { (float) (b0 / a0), (float) (b1 / a0), (float) (b2 / a0),
             (float) (a1 / a0), (float) (a2 / a0) };
}

void AudioFxChain::recompute()
{
    if (eqEnabled.load())
        for (int b = 0; b < kEqBands; ++b)
            eqC[(size_t) b] = makePeak (sr, kEqCenters[(size_t) b], 1.0, eqGainsDb[(size_t) b].load());

    paramAnyActive = false;
    if (paramEqEnabled.load())
    {
        for (int b = 0; b < kParamBands; ++b)
        {
            const float g   = paramGainDb[(size_t) b].load();
            const bool  act = (g != 0.0f);              // плоская полоса (0 дБ) = проход → не считаем
            if (act && ! paramBandActive[(size_t) b])   // полоса ожила → чистим состояние (без щелчка)
                for (auto& ch : paramS) ch[(size_t) b] = {};
            paramBandActive[(size_t) b] = act;
            if (act)
            {
                paramC[(size_t) b] = makePeak (sr, paramFreq[(size_t) b].load(), paramQ[(size_t) b].load(), g);
                paramAnyActive = true;
            }
        }
    }
    else
    {
        for (auto& a : paramBandActive) a = false;
    }

    if (bassEnabled.load())
        bassC = makeLowShelf (sr, bassFreq.load(), bassGainDb.load());

    if (loudnessEnabled.load())
    {
        // Чем тише система — тем больше буст низов (психоакустика Флетчера–Мансона)
        // и чуть верхов. На полной громкости компенсация = 0 (ровно).
        const float amt = 1.0f - currentVolume.load();        // 0..1
        loudLowC  = makeLowShelf  (sr, 120.0,   amt * 10.0);  // до +10 дБ низ
        loudHighC = makeHighShelf (sr, 10000.0, amt * 4.0);   // до +4 дБ верх
    }
}

// ── Обработка ────────────────────────────────────────────────────────────────
void AudioFxChain::process (float* const* channels, int numChannels, int numSamples)
{
    if (! masterEnabled.load())
        return;

    const int n = juce::jmin (numChannels, kMaxCh);
    if (n <= 0 || numSamples <= 0)
        return;

    if (dirty.exchange (false))
        recompute();

    const bool  eqOn    = eqEnabled.load();
    const bool  paramOn = paramEqEnabled.load() && paramAnyActive;   // только если есть активные полосы
    const bool  bassOn  = bassEnabled.load();
    const bool  loudOn  = loudnessEnabled.load();
    const float width   = stereoWidth.load();
    const float preDb   = preampGainDb.load();
    const float bal     = balance.load();
    const bool  monoOn  = monoEnabled.load();
    const bool  satOn   = satEnabled.load();
    const float satDrv  = satDrive.load();

    // Сброс состояния фильтров на фронте включения (без щелчка).
    if (eqOn && ! eqWas) for (auto& cs : eqS) for (auto& s : cs) s = {};
    eqWas = eqOn;
    if (paramOn && ! paramWas) for (auto& cs : paramS) for (auto& s : cs) s = {};
    paramWas = paramOn;
    if (loudOn && ! loudWas) { for (auto& s : loudLowS) s = {}; for (auto& s : loudHighS) s = {}; }
    loudWas = loudOn;

    preampGain.setTargetValue (juce::Decibels::decibelsToGain (preDb));

    const bool tonal = eqOn || paramOn || bassOn || loudOn || satOn || (n > 1 && width != 1.0f) || preDb != 0.0f
                       || (n > 1 && (monoOn || bal != 0.0f));

    // Лёгкий быстрый путь: тональных стадий нет → per-sample цикл пропускаем.
    if (tonal)
    {
        for (int i = 0; i < numSamples; ++i)
        {
            const float g = preampGain.getNextValue();      // сглаженный преамп
            float l = channels[0][i] * g;
            float r = (n > 1) ? channels[1][i] * g : l;

            if (eqOn)
                for (int b = 0; b < kEqBands; ++b)
                {
                    l = processBiquad (eqC[(size_t) b], eqS[0][(size_t) b], l);
                    if (n > 1) r = processBiquad (eqC[(size_t) b], eqS[1][(size_t) b], r);
                }

            if (paramOn)
                for (int b = 0; b < kParamBands; ++b)
                    if (paramBandActive[(size_t) b])         // плоские полосы пропускаем
                    {
                        l = processBiquad (paramC[(size_t) b], paramS[0][(size_t) b], l);
                        if (n > 1) r = processBiquad (paramC[(size_t) b], paramS[1][(size_t) b], r);
                    }

            if (bassOn)
            {
                l = processBiquad (bassC, bassS[0], l);
                if (n > 1) r = processBiquad (bassC, bassS[1], r);
            }

            if (loudOn)
            {
                l = processBiquad (loudLowC,  loudLowS[0],  l);
                l = processBiquad (loudHighC, loudHighS[0], l);
                if (n > 1)
                {
                    r = processBiquad (loudLowC,  loudLowS[1],  r);
                    r = processBiquad (loudHighC, loudHighS[1], r);
                }
            }

            if (satOn)                                       // сатурация («ламповое тепло»)
            {
                const float g = 1.0f + satDrv * 4.0f;
                l = saturate (l, g, satDrv);
                if (n > 1) r = saturate (r, g, satDrv);
            }

            if (n > 1 && width != 1.0f)                      // stereo width (mid-side)
            {
                const float mid  = 0.5f * (l + r);
                const float side = 0.5f * (l - r) * width;
                l = mid + side;
                r = mid - side;
            }

            if (n > 1 && monoOn)                             // моно-даунмикс
            {
                const float m = 0.5f * (l + r);
                l = m; r = m;
            }

            if (n > 1 && bal != 0.0f)                        // баланс L/R (-1..+1)
            {
                l *= (bal > 0.0f) ? (1.0f - bal) : 1.0f;     // правее → тише левый
                r *= (bal < 0.0f) ? (1.0f + bal) : 1.0f;     // левее  → тише правый
            }

            channels[0][i] = l;
            if (n > 1) channels[1][i] = r;
        }
    }

    // Reverb + динамика — штатные juce::dsp на блоке.
    const bool revOn  = reverbEnabled.load();
    const bool compOn = compEnabled.load();
    const bool limOn  = limEnabled.load();
    if (revOn && ! revWas) reverb.reset();   // фронт включения — без старого хвоста
    revWas = revOn;
    if (revOn || compOn || limOn)
    {
        juce::dsp::AudioBlock<float> block (channels, (size_t) n, (size_t) numSamples);
        juce::dsp::ProcessContextReplacing<float> ctx (block);

        if (revOn)                            // reverb ДО динамики (хвост потом лимитируется)
        {
            if (revDirty.exchange (false))
            {
                juce::Reverb::Parameters p;
                p.roomSize   = revRoom.load();
                p.damping    = revDamp.load();
                p.wetLevel   = revWet.load();
                p.dryLevel   = 1.0f - revWet.load();
                p.width      = 1.0f;
                p.freezeMode = 0.0f;
                reverb.setParameters (p);
            }
            reverb.process (ctx);
        }

        if (compOn)
        {
            if (compDirty.exchange (false))
            {
                compressor.setThreshold (compThresh.load());
                compressor.setRatio     (compRatio.load());
                compressor.setAttack    (compAttack.load());
                compressor.setRelease   (compRelease.load());
            }
            compressor.process (ctx);
        }

        if (limOn)
        {
            if (limDirty.exchange (false))
            {
                limiter.setThreshold (limThresh.load());
                limiter.setRelease   (limRelease.load());
            }
            limiter.process (ctx);
        }
    }
}
