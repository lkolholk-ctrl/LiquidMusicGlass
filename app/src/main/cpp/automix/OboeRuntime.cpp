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

    // ── Haptic Music: огибающая баса + детектор ударов ──
    // Пишет ТОЛЬКО аудио-поток (сглаживание/история — обычные статики),
    // Kotlin читает атомики. Кулдаун между ударами ~120мс (в сэмплах, по 48к —
    // на 44.1к чуть длиннее, для тактильности некритично).
    static std::atomic<int>  gHapticEnvMilli { 0 };
    static std::atomic<long> gHapticBeatCount { 0 };
    static std::atomic<int>  gHapticBeatStrengthMilli { 0 };

    void noteBassLevel (float bassMeanAbs, int numSamples)
    {
        // Две огибающие с ВРЕМЕННЫМИ константами (не по блокам! на реальном
        // железе блок бывает 4мс (burst 192) — по-блочное сглаживание липнет
        // к сигналу мгновенно, и удар никогда не пробивает порог — «два стука
        // при включении и тишина», полевой баг v1):
        //   fast — атака ~4мс / спад ~60мс: контур кика;
        //   slow — ~350мс в обе стороны: средний уровень баса.
        // Удар = fast пробила slow (снятую ДО обновления) с запасом.
        static float fast = 0.0f, slow = 0.0f;
        static float aF = 0.5f, rF = 0.1f, cS = 0.02f;
        static int   coefsForSamples = -1;
        static long  samplesSinceBeat = 1 << 30;
        static long  warmupSamples = 0;
        constexpr float kSr = 48000.0f;               // точность sr некритична
        constexpr long  kBeatCooldownSamples = 7200;  // ~150мс

        if (numSamples != coefsForSamples)            // блок-размер меняется редко
        {
            coefsForSamples = numSamples;
            const float n = (float) numSamples;
            aF = 1.0f - std::exp (-n / (0.004f * kSr));
            rF = 1.0f - std::exp (-n / (0.060f * kSr));
            cS = 1.0f - std::exp (-n / (0.350f * kSr));
        }

        fast += (bassMeanAbs > fast ? aF : rF) * (bassMeanAbs - fast);
        const float slowPrev = slow;
        slow += cS * (bassMeanAbs - slow);
        gHapticEnvMilli.store ((int) (fast * 1000.0f), std::memory_order_relaxed);

        samplesSinceBeat += numSamples;
        // Прогрев ~0.5с после старта/тишины: огибающие с нуля дают ложные
        // «удары» на первых же блоках (те самые два стука при включении).
        warmupSamples += numSamples;
        if (slowPrev < 0.003f && fast < 0.003f)
            warmupSamples = 0;
        if (warmupSamples < 24000)
            return;

        // Peak-hold: пересечение только ВЗВОДИТ удар; сила меряется по пику
        // fast за следующие ~48мс (замер в блоке пересечения ловил кик в
        // случайной фазе — сила прыгала 0.1..1.0 на ровной бочке). Публикация
        // счётчика происходит после окна: тап опаздывает на ~50мс — на слух
        // незаметно, зато сила честная.
        static bool  beatPending = false;
        static float peakFast = 0.0f, slowAtCross = 0.0f;
        static long  peakSamples = 0;
        static float avgRatio = 0.0f;      // средний удар (EMA) — адаптивная норма
        constexpr long kPeakWindowSamples = 2304;   // ~48мс

        if (beatPending)
        {
            if (fast > peakFast) peakFast = fast;
            peakSamples += numSamples;
            if (peakSamples >= kPeakWindowSamples)
            {
                beatPending = false;
                const float ratio = peakFast / (slowAtCross > 0.015f ? slowAtCross : 0.015f);
                if (avgRatio <= 0.0f) avgRatio = ratio;
                // Сила ОТНОСИТЕЛЬНО среднего удара трека: ровный кач -> ~0.2,
                // вдвое жирнее среднего (дроп/акцент) -> 1.0. Самокалибровка
                // под любой жанр/громкость (EMA по последним ударам).
                float strength = 0.2f + 1.2f * (ratio / avgRatio - 1.0f);
                if (strength > 1.0f) strength = 1.0f;
                if (strength < 0.0f) strength = 0.0f;
                avgRatio += 0.2f * (ratio - avgRatio);
                gHapticBeatStrengthMilli.store ((int) (strength * 1000.0f),
                                                std::memory_order_relaxed);
                gHapticBeatCount.fetch_add (1, std::memory_order_relaxed);
            }
        }
        else if (samplesSinceBeat >= kBeatCooldownSamples
                 && fast > slowPrev * 1.5f + 0.006f)
        {
            samplesSinceBeat = 0;          // кулдаун — от пересечения
            beatPending = true;
            peakFast = fast;
            slowAtCross = slowPrev;
            peakSamples = 0;
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
