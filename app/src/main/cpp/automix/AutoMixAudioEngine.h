#pragma once

#include <juce_audio_devices/juce_audio_devices.h>
#include <juce_audio_formats/juce_audio_formats.h>
#include <juce_dsp/juce_dsp.h>
#include <array>
#include <atomic>
#include <memory>
#include <mutex>

#include "AudioFxChain.h"

namespace automix { class MediaCodecAudioSource; }

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

    /** Маршрут вывода сменился (BT подключён/отключён и т.п.): переоткрыть Oboe-поток
     *  на текущем устройстве с правильным буфером (BT → без fast-path; встроенный →
     *  fast-path/RT). Позиция воспроизведения сохраняется. Зовётся НЕ из аудио-потока. */
    void onOutputRouteChanged (bool isBluetooth);

    /** Сменился режим совместимости Oboe (OboeRuntime): переоткрыть поток, чтобы
     *  новый sharing/performance mode применился. Позиция сохраняется (как при
     *  смене маршрута). Зовётся НЕ из аудио-потока. */
    void reopenAudioDevice();

    // ── AudioTrack-выход (Java-sink): третий вход в аудио-систему ────────────
    // Для устройств, где кривые ОБА нативных пути (AAudio и OpenSL): Oboe-девайс
    // закрывается, движок остаётся жив, и готовые блоки тянет Java-поток
    // AudioTrackSink — тем же путём, каким играет ExoPlayer/стриминг.
    /** Закрыть Oboe-устройство и подготовить движок под ручной рендер. */
    void enterSinkMode (double sampleRate, int blockSize);
    /** Отрендерить numSamples стерео-кадров в l/r (зовёт ТОЛЬКО поток sink'а). */
    void renderSinkBlock (float* left, float* right, int numSamples);

    // Stage 1 diagnostic tone (only when neither deck has a track) -----------
    void startTone();
    void stopTone();

    // Stage 2/3 playback -----------------------------------------------------
    bool loadTrack  (const juce::String& path);   // back-compat alias -> deck A
    bool loadTrackA (const juce::String& path);
    bool loadTrackB (const juce::String& path);
    /** Load deck A from a file descriptor (content:// via openFileDescriptor) — no copy.
     *  Always decodes via MediaCodec (handles all common formats). */
    bool loadTrackAFd (int fd, long long offset, long long size);
    void play();
    void pause();
    void stop();
    /** RT-safe emergency mute. Used before async pause/skip/load reaches the engine. */
    void silenceOutput();

    /** Crossfade deck A -> deck B over durationMs. Starts both decks. */
    void startCrossfade (double durationMs, int transitionType = 0);

    // ── Stage 8: full LOCAL player (ping-pong decks) ───────────────────────
    // The engine plays a queue: one deck is "current", the other is "incoming".
    // A transition crossfades current -> incoming (either direction); on
    // completion the incoming deck becomes current. Transport ops act on the
    // current deck so Kotlin can drive a normal player (play/pause/seek/pos).

    /** Load a track into the NON-current (incoming) deck, ready for a transition. */
    bool loadIncoming (const juce::String& path);
    /** Same, but from a file descriptor (content:// без копии) — для AutoMix-свода
     *  следующего локального трека, у которого нет прямого пути. */
    bool loadIncomingFd (int fd, long long offset, long long size);
    /** Crossfade current -> incoming over durationMs; incoming starts
     *  at entryMs. On completion the incoming deck becomes current. */
    void startTransition (double durationMs, double entryMs, int transitionType = 0);
    /** Start / resume the current deck. */
    void playCurrent();
    /** Seek the current deck (ms). */
    void seekCurrent (double ms);
    /** Current deck position / length (ms). */
    double positionMsCurrent();
    double lengthMsCurrent();
    /** True while a transition (crossfade) is running. */
    bool isCrossfadeActive();
    /** Which deck is current (0 = A, 1 = B). */
    int  currentDeckIndex();
    /** Clear a deck by index (0 = A, 1 = B): free its source/track. */
    void clearDeck (int index);

    /** Stage 6: where deck B enters from (model's entryOffsetMs). Applied at the
     *  next startCrossfade. 0 (default) keeps the Stage 3-5 behaviour unchanged. */
    void setEntryOffsetB (double ms);

    /** Stage 7 hand-off: where deck A starts the blend from (the Media3 cue tail).
     *  Applied at the next startCrossfade. 0 (default) keeps Stage 3-6 behaviour
     *  unchanged (deck A blends from its current position). */
    void setEntryOffsetA (double ms);

    /** Stage 7 overlap crossfade: empty deck A so the next startCrossfade only
     *  fades deck B IN (A stays on Media3 and fades out there — true overlap). */
    void clearDeckA();

    /** Enable/disable the Stage 5 bass-swap. Off for the overlap crossfade where
     *  deck A isn't in JUCE (the swap would wrongly thin out B's low end). */
    void setBassSwap (bool enabled);

    /** Deck A transport position / length in ms — for transition timing. */
    double positionMsA();
    double lengthMsA();

    /** Счётчик underrun'ов (xrun) Oboe-потока с момента его открытия; -1 если
     *  неизвестен/устройство занято переоткрытием. Не блокирует (try_lock) и
     *  не зовётся с аудио-потока. Для телеметрии/DebugLog — тюнить буфер по
     *  реальным данным, а не вслепую. */
    int xRunCount();

    /** Underrun-адаптация: телеметрия поймала рост xrun → увеличить буфер
     *  (множитель burst 4→6→8, кап) и переоткрыть поток на текущем маршруте.
     *  Короткий разрыв звука при реопене — цена за прекращение постоянных
     *  заиканий под системной нагрузкой. НЕ с аудио- и НЕ с main-потока. */
    void escalateBufferForUnderruns();

    /** Энергосбережение (Battery Saver): система жёстче тротлит CPU — на время
     *  режима держим максимальный буфер (8×burst) для динамика/провода.
     *  Реопен потока при смене состояния. НЕ с аудио- и НЕ с main-потока. */
    void setPowerSaveMode (bool on);

    /**
     * Stage 4: pre-stretch deck B so its tempo matches deck A (bpmB -> bpmA),
     * pitch preserved. Heavy/offline — call off the audio & main threads, before
     * starting the crossfade. After this, deck B plays the beat-matched audio.
     */
    bool prepareStretchB (double bpmA, double bpmB);

    // ── Professional audio FX chain (applied to the final LOCAL mix) ─────────
    // Post-processes the mixed output, so it covers ALL local audio (single deck
    // or an AutoMix crossfade). Implemented by AudioFxChain (RT-safe juce::dsp).
    // These just forward to it; никакого влияния на AutoMix/RT/fast-path.
    static constexpr int kEqBands = 10;
    void setFxMasterEnabled (bool on);
    void setPreampGainDb    (float db);
    void setEqEnabled       (bool enabled);
    void setEqBandGain      (int band, float gainDb);
    void setEqBands         (const float* gainsDb, int count);
    void setBassBoost       (bool on, float freqHz, float gainDb);
    void setLoudnessEnabled (bool on);
    void setCurrentVolume   (float v01);
    void setStereoWidth     (float width);
    void setCompressorFx    (bool on, float threshDb, float ratio, float attackMs, float releaseMs);
    void setLimiterFx       (bool on, float threshDb, float releaseMs);

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
        std::unique_ptr<juce::AudioFormatReaderSource> readerSource;       // wav/flac/ogg
        juce::AudioBuffer<float> decodedBuffer;                            // stretched/beat-matched
        std::unique_ptr<juce::MemoryAudioSource> memorySource;            // plays decodedBuffer
        std::unique_ptr<automix::MediaCodecAudioSource> mediaCodecSource; // streaming mp3/aac (instant)
        std::atomic<bool> hasTrack { false };
        juce::String path;                                           // for re-decode/stretch
        double sourceSampleRate { 0.0 };
        // ОДИН лок на дек (не общий!). Свод грузит incoming-дек под ЕГО локом, а
        // аудио-колбэк тянет играющий дек под ДРУГИМ — играющий не блокируется
        // загрузкой соседа, поэтому музыка не замирает в начале AutoMix.
        juce::CriticalSection mutationLock;

        Deck() = default;
        JUCE_DECLARE_NON_COPYABLE (Deck)
    };

    bool loadDeck (Deck& deck, const juce::String& path);
    bool loadDeckFd (Deck& deck, int fd, long long offset, long long size);
    bool decodeFullPCM (const juce::String& path, juce::AudioBuffer<float>& out, double& rate);
    void clearDeckUnlocked (Deck& deck);
    int deckIndex (const Deck& deck) const { return &deck == &deckA ? 0 : 1; }
    void invalidateHoldForDeck (const Deck& deck);
    void invalidateAllHolds();
    void applyBufferForRoute (bool isBluetooth, bool forceReopen);          // not on audio thread
    void applyBufferForRouteUnlocked (bool isBluetooth, bool forceReopen);  // держа deviceControlMutex
    void prepareInternal (double sampleRate, int blockSize);                // общая подготовка (device/sink)
    Deck& deckRef (int index) { return index == 0 ? deckA : deckB; }

    // Управляющие операции с устройством (init / переоткрытие под маршрут /
    // закрытие в release) идут с РАЗНЫХ потоков (main, route-монитор, load) и
    // после ухода от глобального JNI-мьютекса могут перекрыться. Сериализуем их
    // здесь; аудио-коллбэк этот мьютекс НИКОГДА не берёт.
    std::mutex deviceControlMutex;

    juce::AudioDeviceManager deviceManager;
    juce::AudioFormatManager formatManager;
    // ОДИН read-ahead поток на оба дека starve'ил играющий дек: при свводе загрузка
    // incoming-дека запрашивает ~5с пред-декода, и общий поток на эту вспышку
    // переставал кормить буфер играющего дека → музыка замирала на ~секунду в
    // начале AutoMix. Отдельный поток на КАЖДЫЙ дек убирает конкуренцию: загрузка
    // одного дека не трогает предчтение другого.
    juce::TimeSliceThread readAheadThreadA { "automix-readahead-A" };
    juce::TimeSliceThread readAheadThreadB { "automix-readahead-B" };

    Deck deckA, deckB;
    // Поток предчтения соответствующего дека (для setSource буферизации).
    juce::TimeSliceThread& threadForDeck (Deck& d) { return (&d == &deckA) ? readAheadThreadA : readAheadThreadB; }
    juce::AudioBuffer<float> scratchA, scratchB; // per-block pull buffers (audio thread)
    juce::AudioBuffer<float> holdA, holdB;       // last good block: masks short deck-lock misses
    std::array<std::atomic<bool>, 2> holdValid { { {false}, {false} } };
    std::array<std::atomic<int>, 2> holdRepeats { { {0}, {0} } };

    // Lock-free transport reporting. The audio callback publishes each deck's
    // position/length into these atomics (under that deck's lock); the main-thread
    // getters read them WITHOUT taking any lock. This kills the every-100ms ticker /
    // Media3 getState() contention that otherwise made the callback's tryEnter()
    // fail and emit silence blocks → audible stutter.
    std::array<std::atomic<double>, 2> reportedPosMs { { {0.0}, {0.0} } };
    std::array<std::atomic<double>, 2> reportedLenMs { { {0.0}, {0.0} } };

    // Bass-swap: one low-pass per deck per channel extracts the low band so we
    // can scale each deck's bass with a scalar (fixed coefficients -> no clicks).
    std::array<juce::dsp::IIR::Filter<float>, 2> lowpassA, lowpassB;
    static constexpr float kBassCutoffHz = 150.0f;
    std::atomic<bool> bassSwapEnabled { true }; // off for Stage 7 overlap (A on Media3)

    // Профессиональная DSP-цепочка (Preamp→EQ→Bass→Loudness→Width→Comp→Limiter),
    // применяется к уже сведённому локальному миксу. Вся обработка и RT-безопасные
    // апдейты коэффициентов — внутри AudioFxChain.
    AudioFxChain audioFx;

    std::atomic<bool> initialised { false };
    std::atomic<bool> toneOn { false };
    std::atomic<bool> outputMuted { false };
    std::atomic<bool> fxResetRequested { false };

    // Текущий маршрут вывода (true = Bluetooth). routeEverApplied — чтобы первый
    // вызов с тем же значением всё равно применился, а последующие без изменения — нет.
    std::atomic<bool> routeIsBluetooth { false };
    std::atomic<bool> routeEverApplied { false };

    // Множитель burst-размера для встроенного/проводного маршрута. Стартует с 6
    // (запас против CPU-сторма системного UI: шторка/переключения); телеметрия
    // xrun'ов может адаптивно поднять до 8 (escalateBufferForUnderruns).
    std::atomic<int> bufferBurstMultiplier { 6 };
    // Battery Saver: на время режима буфер держится на максимуме (8×burst).
    std::atomic<bool> powerSaveBuffer { false };

    // Crossfade state. crossfadeStart is latched by the audio thread so the
    // sample position resets in sync; everything else is plain atomics.
    std::atomic<bool> crossfadeStart  { false };
    std::atomic<bool> crossfadeActive { false };
    std::atomic<int>  currentDeck     { 0 };   // 0 = A, 1 = B (Stage 8 ping-pong)
    std::atomic<int>  fadeOutDeck     { 0 };   // deck fading OUT during active transition
    std::atomic<long long> crossfadeTotal { 0 };  // total samples
    std::atomic<int> transitionStyle { 0 };        // DJEffectsEngine transitionType
    long long crossfadePos { 0 };                 // audio-thread only
    std::atomic<float> baseGainA { 1.0f };        // gains outside an active crossfade
    std::atomic<float> baseGainB { 0.0f };
    std::atomic<double> entryOffsetMsB { 0.0 };   // deck B entry point (model decides)
    std::atomic<double> entryOffsetMsA { 0.0 };   // deck A blend-start (Stage 7 hand-off)

    double currentSampleRate { 44100.0 };
    int    currentBlockSize  { 512 };
    double phase { 0.0 };

    static constexpr double kToneHz = 440.0;
    static constexpr float  kToneAmplitude = 0.2f;

    JUCE_DECLARE_NON_COPYABLE_WITH_LEAK_DETECTOR (AutoMixAudioEngine)
};
