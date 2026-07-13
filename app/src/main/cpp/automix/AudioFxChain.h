#pragma once

#include <juce_dsp/juce_dsp.h>
#include <array>
#include <atomic>

/**
 * Профессиональная DSP-цепочка поверх СВЕДЁННОГО локального аудио (JUCE-путь).
 * Порядок (важен для качества):
 *   Preamp(log) → EQ(10) → Bass Boost → Loudness comp → Stereo Width
 *                → Compressor → Limiter → выход
 *
 * RT-безопасность (НЕ выбиваем fast-path/RT):
 *   • Параметры — атомики, пишутся из UI/JNI-потока.
 *   • IIR-стадии (EQ/шелфы) — СВОИ биквады по формулам RBJ (та же математика, что
 *     juce::dsp::IIR::Coefficients::makePeakFilter/makeLowShelf/makeHighShelf), но
 *     коэффициенты пересчитываются в POD-структуры БЕЗ аллокаций — в отличие от
 *     juce::dsp::IIR::Filter, у которого присвоение coefficients алоцирует кучу в
 *     аудио-потоке. Пересчёт — только когда параметр изменился (флаг dirty).
 *   • Динамика — штатные juce::dsp::Compressor / juce::dsp::Limiter (без аллокаций
 *     в process). Gain преампа — со сглаживанием (juce::SmoothedValue), без зиппера.
 *   • Если ничего тонального не включено — per-sample цикл пропускается целиком.
 */
class AudioFxChain
{
public:
    static constexpr int kEqBands    = 10;
    static constexpr int kParamBands = 5;    // параметрический EQ (freq/Q/gain на полосу)
    static constexpr int kMaxCh      = 2;    // обрабатываем стерео (телефон)

    AudioFxChain();

    void prepare (double sampleRate, int maxBlockSize, int numChannels);
    void reset();
    /** Обработать буфер на месте (до kMaxCh каналов). Лёгкий, для аудио-потока. */
    void process (float* const* channels, int numChannels, int numSamples);

    // ── Сеттеры (любой поток; только атомики + флаги, без тяжёлой работы) ──────
    void setMasterEnabled (bool on)       { masterEnabled.store (on); }

    void setPreampGainDb  (float db)      { preampGainDb.store (juce::jlimit (-60.0f, 12.0f, db)); }

    void setEqEnabled     (bool on)       { eqEnabled.store (on); dirty.store (true); }
    void setEqBandGain    (int band, float gainDb);
    void setEqBands       (const float* gainsDb, int count);

    void setParamEqEnabled (bool on)      { paramEqEnabled.store (on); dirty.store (true); }
    void setParamBand      (int band, float freqHz, float q, float gainDb);

    void setBassBoost     (bool on, float freqHz, float gainDb);

    void setLoudnessEnabled (bool on)     { loudnessEnabled.store (on); dirty.store (true); }
    void setCurrentVolume   (float v01)
    {
        const float clamped = juce::jlimit (0.0f, 1.0f, v01);
        const float prev = currentVolume.exchange (clamped);
        if (prev != clamped && loudnessEnabled.load())
            dirty.store (true);
    }

    void setStereoWidth   (float width)   { stereoWidth.store (juce::jlimit (0.0f, 2.0f, width)); }

    void setBalance       (float pan)     { balance.store (juce::jlimit (-1.0f, 1.0f, pan)); }
    void setMono          (bool on)       { monoEnabled.store (on); }

    void setCompressor (bool on, float threshDb, float ratio, float attackMs, float releaseMs);
    void setLimiter    (bool on, float threshDb, float releaseMs);

private:
    struct Biquad      { float b0 { 1.0f }, b1 { 0.0f }, b2 { 0.0f }, a1 { 0.0f }, a2 { 0.0f }; };
    struct BiquadState { float z1 { 0.0f }, z2 { 0.0f }; };

    // RBJ cookbook (идентично формулам juce::dsp::IIR::Coefficients::make*).
    static Biquad makePeak      (double fs, double f0, double Q,     double gainDb);
    static Biquad makeLowShelf  (double fs, double f0, double gainDb);
    static Biquad makeHighShelf (double fs, double f0, double gainDb);

    static inline float processBiquad (const Biquad& c, BiquadState& s, float x) noexcept
    {
        const float y = c.b0 * x + s.z1;                 // transposed direct form II
        s.z1 = c.b1 * x - c.a1 * y + s.z2;
        s.z2 = c.b2 * x - c.a2 * y;
        return y;
    }

    void recompute();   // audio-thread: rebuild IIR coeffs from atomics (no alloc)

    double sr { 44100.0 };
    std::atomic<bool> masterEnabled { true };
    std::atomic<bool> dirty { true };

    // Preamp (логарифмический gain, сглаженный)
    std::atomic<float> preampGainDb { 0.0f };
    juce::SmoothedValue<float> preampGain;

    // EQ (10 полос)
    std::atomic<bool> eqEnabled { false };
    std::array<std::atomic<float>, (size_t) kEqBands> eqGainsDb;
    std::array<Biquad, (size_t) kEqBands> eqC {};
    std::array<std::array<BiquadState, (size_t) kEqBands>, (size_t) kMaxCh> eqS {};
    bool eqWas { false };

    // Параметрический EQ (5 полос: freq/Q/gain на каждую)
    std::atomic<bool> paramEqEnabled { false };
    std::array<std::atomic<float>, (size_t) kParamBands> paramFreq;
    std::array<std::atomic<float>, (size_t) kParamBands> paramQ;
    std::array<std::atomic<float>, (size_t) kParamBands> paramGainDb;
    std::array<Biquad, (size_t) kParamBands> paramC {};
    std::array<std::array<BiquadState, (size_t) kParamBands>, (size_t) kMaxCh> paramS {};
    bool paramWas { false };

    // Bass boost (low shelf)
    std::atomic<bool>  bassEnabled { false };
    std::atomic<float> bassFreq { 80.0f };
    std::atomic<float> bassGainDb { 0.0f };
    Biquad bassC {};
    std::array<BiquadState, (size_t) kMaxCh> bassS {};

    // Loudness compensation (low shelf + лёгкий high shelf, доля от громкости)
    std::atomic<bool>  loudnessEnabled { false };
    std::atomic<float> currentVolume { 1.0f };
    Biquad loudLowC {}, loudHighC {};
    std::array<BiquadState, (size_t) kMaxCh> loudLowS {}, loudHighS {};
    bool loudWas { false };

    // Stereo width (mid-side)
    std::atomic<float> stereoWidth { 1.0f };

    // Balance (L/R) и Mono-даунмикс
    std::atomic<float> balance { 0.0f };       // -1..+1 (0 = центр)
    std::atomic<bool>  monoEnabled { false };

    // Динамика (штатные juce::dsp)
    std::atomic<bool>  compEnabled { false };
    std::atomic<bool>  limEnabled  { true };
    juce::dsp::Compressor<float> compressor;
    juce::dsp::Limiter<float>    limiter;
    std::atomic<bool>  compDirty { true }, limDirty { true };
    std::atomic<float> compThresh { -18.0f }, compRatio { 3.0f }, compAttack { 20.0f }, compRelease { 150.0f };
    std::atomic<float> limThresh { -1.0f }, limRelease { 50.0f };

    JUCE_DECLARE_NON_COPYABLE (AudioFxChain)
};
