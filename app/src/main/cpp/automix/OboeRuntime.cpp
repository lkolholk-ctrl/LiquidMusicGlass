#include "OboeRuntime.h"

#include <oboe/Oboe.h>
#include <android/log.h>

#include <atomic>
#include <cmath>
#include <mutex>
#include <cstdio>

namespace
{
    constexpr const char* kLogTag = "OboeCompat";

    // Дефолт — NORMAL (Shared+LowLatency). Полевая матрица (02.07.2026):
    //   Honor владельца:  Exclusive+LowLatency ✓, Shared+LowLatency ✓, Shared+None ✗ (тишина!)
    //   Xiaomi (битый MMAP): Exclusive+LowLatency ✗ (шум) — Shared-режимы не проверены.
    // Т.е. «самый совместимый» Shared+None сам ломает часть устройств — универсального
    // статического режима нет, дефолт = минимальное отклонение от стока (уходим только
    // от exclusive-MMAP), SAFE — тумблер в настройках для проблемных девайсов.
    std::atomic<int> gCompatMode { 0 };   // 0 NORMAL / 1 SAFE / 2 EXCLUSIVE (см. OboeRuntime.h)

    // Кольцо последних отчётов об открытых потоках. Открытие идёт с обычных
    // (не realtime) потоков, поэтому мьютекс здесь безопасен.
    struct StreamReport
    {
        int direction, openResult, usesAAudio, sharing, perf, format;
        int sampleRate, bufferSize, burst, capacity, channels;
        int mode;        // compat-режим на момент открытия
        long seq;        // порядковый номер (растёт), для «свежее/старее»
    };

    // 12: каждый реопен устройства открывает пачку потоков (пробы + сессия), при
    // 6 сессионный поток предыдущего режима выпадал из дампа.
    constexpr int kMaxReports = 12;
    std::mutex gReportsMutex;
    StreamReport gReports[kMaxReports];
    long gReportSeq = 0;

    std::mutex gCodecInfoMutex;
    std::string gLastCodecInfo;   // формат вывода MediaCodec-декодера (см. setLastCodecInfo)

    const char* modeName (int mode)
    {
        switch (mode)
        {
            case 1:  return "SAFE (shared+none)";
            case 2:  return "EXCLUSIVE (exclusive+lowlat)";
            case 3:  return "NORMAL_I16 (shared+lowlat+i16)";
            case 4:  return "SAFE_I16 (shared+none+i16)";
            case 5:  return "OPENSLES_I16 (opensl+none+i16)";
            default: return "NORMAL (shared+lowlat)";
        }
    }

    std::atomic<long> gNanScrubbed { 0 };   // вычищенные NaN/Inf на выходе колбэка
    std::atomic<bool> gSinkActive { false };// AudioTrack-sink (Java-выход) активен
    std::atomic<long> gCbCount { 0 };       // пульс аудио-колбэка
    std::atomic<int>  gCbNumSamples { 0 };  // размер последнего блока
    std::atomic<int>  gCbLevelMilli { 0 };  // средний |сэмпл| последнего блока ×1000

    void formatReport (char* buf, size_t bufSize, const StreamReport& r)
    {
        std::snprintf (buf, bufSize,
                       "#%ld %s %s api=%s share=%s perf=%s fmt=%s rate=%d ch=%d buf=%d/%d burst=%d mode=%d",
                       r.seq,
                       r.direction == (int) oboe::Direction::Output ? "out" : "in",
                       oboe::convertToText ((oboe::Result) r.openResult),
                       r.usesAAudio < 0 ? "?" : (r.usesAAudio != 0 ? "AAudio" : "OpenSLES"),
                       r.sharing < 0 ? "?" : oboe::convertToText ((oboe::SharingMode) r.sharing),
                       r.perf < 0 ? "?" : oboe::convertToText ((oboe::PerformanceMode) r.perf),
                       r.format < 0 ? "?" : oboe::convertToText ((oboe::AudioFormat) r.format),
                       r.sampleRate, r.channels, r.bufferSize, r.capacity, r.burst, r.mode);
    }
}

namespace automix
{
    bool setOboeCompatMode (int mode)
    {
        const int clamped = mode < 0 ? 0 : (mode > 5 ? 5 : mode);
        const int prev = gCompatMode.exchange (clamped);
        if (prev != clamped)
            __android_log_print (ANDROID_LOG_INFO, kLogTag, "compat mode %d -> %d (%s)",
                                 prev, clamped, modeName (clamped));
        return prev != clamped;
    }

    int getOboeCompatMode()
    {
        return gCompatMode.load();
    }

    void addNanScrubbed (int count)
    {
        if (count > 0)
            gNanScrubbed.fetch_add (count, std::memory_order_relaxed);
    }

    void setSinkActive (bool active)
    {
        gSinkActive.store (active, std::memory_order_relaxed);
        __android_log_print (ANDROID_LOG_INFO, kLogTag, "AudioTrack sink %s",
                             active ? "ACTIVE" : "stopped");
    }

    bool isSinkActive()
    {
        return gSinkActive.load (std::memory_order_relaxed);
    }

    void noteAudioCallback (int numSamples)
    {
        gCbCount.fetch_add (1, std::memory_order_relaxed);
        gCbNumSamples.store (numSamples, std::memory_order_relaxed);
    }

    void noteAudioLevel (float meanAbs)
    {
        gCbLevelMilli.store ((int) (meanAbs * 1000.0f), std::memory_order_relaxed);
    }

    long getCallbackCount()
    {
        return gCbCount.load (std::memory_order_relaxed);
    }

    // ── Haptic Music: двухполосный детектор ударов (низ + середина) ──
    // Пишет ТОЛЬКО аудио-поток (состояние — обычные статики), Kotlin читает
    // атомики. Полосы независимы: бас (кик) — глубокие тапы, середина
    // (снейр/клэп) — лёгкие тики. Верха не подаются вовсе (как у Apple).
    static std::atomic<int>  gHapticEnvMilli { 0 };
    static std::atomic<long> gHapticBeatCount { 0 };
    static std::atomic<int>  gHapticBeatStrengthMilli { 0 };
    static std::atomic<long> gHapticMidBeatCount { 0 };
    static std::atomic<int>  gHapticMidBeatStrengthMilli { 0 };

    namespace
    {
        struct HapticBandDet
        {
            float fast = 0.0f, slow = 0.0f;
            bool  pending = false;
            bool  armed = true;
            float peakFast = 0.0f, slowAtCross = 0.0f;
            long  peakSamples = 0, samplesSinceBeat = 1 << 30;
            float avgRatio = 0.0f;
        };

        // Одна полоса: fast (атака 4мс/спад 60мс) против slow (~350мс), удар
        // взводится на пересечении, сила — peak-hold ~24мс, адаптивная норма
        // (EMA среднего удара). Перевзвод (armed): после удара ждём, пока fast
        // опустится к среднему — непрерывная бас-линия НЕ строчит «швейной
        // машинкой». НО не дольше ~320мс: на плотном миксе fast может вообще
        // не опускаться, и детектор молчал кусками («иногда пусто», полевой
        // фидбек) — форс-перевзвод по таймауту возвращает удары.
        void processBand (HapticBandDet& d, float x, int numSamples,
                          float aF, float rF, float cS,
                          std::atomic<long>& count, std::atomic<int>& strengthMilli)
        {
            d.fast += (x > d.fast ? aF : rF) * (x - d.fast);
            const float slowPrev = d.slow;
            d.slow += cS * (x - d.slow);
            d.samplesSinceBeat += numSamples;

            if (! d.armed
                && (d.fast < slowPrev * 1.15f + 0.004f
                    || d.samplesSinceBeat >= 15360))   // форс ~320мс
                d.armed = true;

            if (d.pending)
            {
                if (d.fast > d.peakFast) d.peakFast = d.fast;
                d.peakSamples += numSamples;
                if (d.peakSamples >= 1152)             // ~24мс: тап ближе к биту
                {
                    d.pending = false;
                    const float ratio = d.peakFast /
                        (d.slowAtCross > 0.015f ? d.slowAtCross : 0.015f);
                    if (d.avgRatio <= 0.0f) d.avgRatio = ratio;
                    float strength = 0.2f + 1.2f * (ratio / d.avgRatio - 1.0f);
                    if (strength > 1.0f) strength = 1.0f;
                    if (strength < 0.0f) strength = 0.0f;
                    d.avgRatio += 0.2f * (ratio - d.avgRatio);
                    strengthMilli.store ((int) (strength * 1000.0f),
                                         std::memory_order_relaxed);
                    count.fetch_add (1, std::memory_order_relaxed);
                }
            }
            else if (d.armed && d.samplesSinceBeat >= 7200   // кулдаун ~150мс
                     && d.fast > slowPrev * 1.5f + 0.006f)
            {
                d.samplesSinceBeat = 0;
                d.pending = true;
                d.armed = false;
                d.peakFast = d.fast;
                d.slowAtCross = slowPrev;
                d.peakSamples = 0;
            }
        }

        HapticBandDet gBassDet, gMidDet;
    }

    void noteBassLevel (float bassMeanAbs, float midMeanAbs, int numSamples)
    {
        // Коэффициенты — с ВРЕМЕННЫМИ константами из реального размера блока
        // (по-блочное сглаживание на блоках 4мс липло к сигналу — «два стука
        // и тишина», полевой баг v1).
        static float aF = 0.5f, rF = 0.1f, cS = 0.02f;
        static int   coefsForSamples = -1;
        static long  warmupSamples = 0;
        constexpr float kSr = 48000.0f;               // точность sr некритична

        if (numSamples != coefsForSamples)            // блок-размер меняется редко
        {
            coefsForSamples = numSamples;
            const float n = (float) numSamples;
            aF = 1.0f - std::exp (-n / (0.004f * kSr));
            rF = 1.0f - std::exp (-n / (0.060f * kSr));
            cS = 1.0f - std::exp (-n / (0.350f * kSr));
        }

        gHapticEnvMilli.store ((int) (gBassDet.fast * 1000.0f), std::memory_order_relaxed);

        // Прогрев ~0.5с после старта/тишины: огибающие с нуля дают ложные
        // «удары» на первых же блоках.
        warmupSamples += numSamples;
        if (gBassDet.slow < 0.003f && gBassDet.fast < 0.003f
            && bassMeanAbs < 0.003f)
            warmupSamples = 0;
        const bool warm = warmupSamples >= 24000;

        if (warm)
        {
            processBand (gBassDet, bassMeanAbs, numSamples, aF, rF, cS,
                         gHapticBeatCount, gHapticBeatStrengthMilli);
            processBand (gMidDet, midMeanAbs, numSamples, aF, rF, cS,
                         gHapticMidBeatCount, gHapticMidBeatStrengthMilli);
        }
        else
        {
            // На прогреве только ведём огибающие — ударов не публикуем.
            const auto warmupBand = [&] (HapticBandDet& d, float x)
            {
                d.fast += (x > d.fast ? aF : rF) * (x - d.fast);
                d.slow += cS * (x - d.slow);
                d.pending = false;
                d.armed = true;
                d.samplesSinceBeat = 1 << 30;
            };
            warmupBand (gBassDet, bassMeanAbs);
            warmupBand (gMidDet, midMeanAbs);
        }
    }

    float getHapticEnv()
    {
        return (float) gHapticEnvMilli.load (std::memory_order_relaxed) / 1000.0f;
    }

    long getHapticBeatCount()
    {
        return gHapticBeatCount.load (std::memory_order_relaxed);
    }

    float getHapticBeatStrength()
    {
        return (float) gHapticBeatStrengthMilli.load (std::memory_order_relaxed) / 1000.0f;
    }

    long getHapticMidBeatCount()
    {
        return gHapticMidBeatCount.load (std::memory_order_relaxed);
    }

    float getHapticMidBeatStrength()
    {
        return (float) gHapticMidBeatStrengthMilli.load (std::memory_order_relaxed) / 1000.0f;
    }

    void setLastCodecInfo (const char* info)
    {
        if (info == nullptr)
            return;
        {
            const std::lock_guard<std::mutex> lock (gCodecInfoMutex);
            gLastCodecInfo = info;
        }
        __android_log_print (ANDROID_LOG_INFO, kLogTag, "%s", info);
    }

    std::string getAudioDiagnostics()
    {
        // При активном sink'е нативный атомик хранит последний Oboe-режим (≤5) —
        // фактический выход показываем честно.
        std::string out = "mode=";
        if (gSinkActive.load (std::memory_order_relaxed))
            out += "AUDIOTRACK (java sink)\nsink=AudioTrack(java) ACTIVE — Oboe отключён";
        else
            out += modeName (gCompatMode.load());
        {
            // Пульс + уровень: cb n растёт → колбэк живёт; level>0 при тишине из
            // динамика → звук чистый на выходе движка, портит система.
            char cbLine[80];
            std::snprintf (cbLine, sizeof (cbLine), "\ncb n=%ld ns=%d level=%d",
                           gCbCount.load (std::memory_order_relaxed),
                           gCbNumSamples.load (std::memory_order_relaxed),
                           gCbLevelMilli.load (std::memory_order_relaxed));
            out += cbLine;
        }
        if (const long nan = gNanScrubbed.load (std::memory_order_relaxed); nan > 0)
        {
            char nanLine[48];
            std::snprintf (nanLine, sizeof (nanLine), "\nnanScrubbed=%ld (!)", nan);
            out += nanLine;
        }
        {
            const std::lock_guard<std::mutex> lock (gCodecInfoMutex);
            if (! gLastCodecInfo.empty())
            {
                out += '\n';
                out += gLastCodecInfo;
            }
        }

        const std::lock_guard<std::mutex> lock (gReportsMutex);
        // Свежие сверху: от последнего seq назад.
        for (long s = gReportSeq; s > gReportSeq - kMaxReports && s > 0; --s)
        {
            const StreamReport& r = gReports[(s - 1) % kMaxReports];
            char line[256];
            formatReport (line, sizeof (line), r);
            out += '\n';
            out += line;
        }
        if (gReportSeq == 0)
            out += "\n(no streams opened yet)";
        return out;
    }
}

// ── Хуки, которые зовёт патченный juce_Oboe_android.cpp ──────────────────────
// (cmake/PatchJuceOboeCompat.cmake; объявления вставляются в начало файла).

extern "C" int lmg_oboeSharingModeInt()
{
    return (int) (automix::getOboeCompatMode() == 2 ? oboe::SharingMode::Exclusive
                                                    : oboe::SharingMode::Shared);
}

extern "C" int lmg_oboePerformanceModeInt()
{
    const int mode = automix::getOboeCompatMode();
    return (int) (mode == 1 || mode == 4 || mode == 5 ? oboe::PerformanceMode::None
                                                      : oboe::PerformanceMode::LowLatency);
}

extern "C" int lmg_oboeForceI16()
{
    // Режимы 3/4/5: пропустить float-сессию JUCE → штатная int16-ветка. Для HAL,
    // которые float «успешно» открывают, но портят в микшере (vivo Y35).
    return automix::getOboeCompatMode() >= 3 ? 1 : 0;
}

extern "C" int lmg_oboeAudioApiInt()
{
    // 0 = Unspecified (AAudio, при недоступности OpenSLES), 1 = принудительный
    // OpenSL ES — запасной бэкенд для устройств, где кривой весь AAudio.
    return automix::getOboeCompatMode() == 5 ? (int) oboe::AudioApi::OpenSLES
                                             : (int) oboe::AudioApi::Unspecified;
}

extern "C" int lmg_oboeBufferCapacityMinFrames()
{
    // Минимальная ёмкость буфера потока. JUCE её не задаёт («let OS choose»), а
    // часть HAL (vivo) выдаёт capacity = 1 burst (~4 мс) — setBufferSizeInFrames
    // клампится в неё, и буфер остаётся микроскопическим → хронические underrun'ы
    // (слышно как «шип»). 4096 кадров (~85 мс @48k) — это ЁМКОСТЬ (потолок), а не
    // задержка: реальная задержка задаётся bufferSize, который движок ставит сам.
    return 4096;
}

extern "C" void lmg_onOboeStreamOpen (int direction, int openResult, int usesAAudio,
                                      int sharingMode, int performanceMode, int format,
                                      int sampleRate, int bufferSizeFrames, int framesPerBurst,
                                      int bufferCapacityFrames, int channelCount)
{
    StreamReport r { direction, openResult, usesAAudio, sharingMode, performanceMode,
                     format, sampleRate, bufferSizeFrames, framesPerBurst, bufferCapacityFrames,
                     channelCount, automix::getOboeCompatMode(), 0 };
    {
        const std::lock_guard<std::mutex> lock (gReportsMutex);
        r.seq = ++gReportSeq;
        gReports[(gReportSeq - 1) % kMaxReports] = r;
    }

    char line[256];
    formatReport (line, sizeof (line), r);
    __android_log_print (ANDROID_LOG_INFO, kLogTag, "stream open: %s", line);
}
