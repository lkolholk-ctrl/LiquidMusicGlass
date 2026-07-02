#include "OboeRuntime.h"

#include <oboe/Oboe.h>
#include <android/log.h>

#include <atomic>
#include <mutex>
#include <cstdio>

namespace
{
    constexpr const char* kLogTag = "OboeCompat";

    std::atomic<int> gCompatMode { 0 };   // 0 NORMAL / 1 SAFE / 2 EXCLUSIVE (см. OboeRuntime.h)

    // Кольцо последних отчётов об открытых потоках. Открытие идёт с обычных
    // (не realtime) потоков, поэтому мьютекс здесь безопасен.
    struct StreamReport
    {
        int direction, openResult, usesAAudio, sharing, perf, format;
        int sampleRate, bufferSize, burst;
        int mode;        // compat-режим на момент открытия
        long seq;        // порядковый номер (растёт), для «свежее/старее»
    };

    constexpr int kMaxReports = 6;
    std::mutex gReportsMutex;
    StreamReport gReports[kMaxReports];
    long gReportSeq = 0;

    const char* modeName (int mode)
    {
        switch (mode)
        {
            case 1:  return "SAFE (shared+none)";
            case 2:  return "EXCLUSIVE (exclusive+lowlat)";
            default: return "NORMAL (shared+lowlat)";
        }
    }

    void formatReport (char* buf, size_t bufSize, const StreamReport& r)
    {
        std::snprintf (buf, bufSize,
                       "#%ld %s %s api=%s share=%s perf=%s fmt=%s rate=%d buf=%d burst=%d mode=%d",
                       r.seq,
                       r.direction == (int) oboe::Direction::Output ? "out" : "in",
                       oboe::convertToText ((oboe::Result) r.openResult),
                       r.usesAAudio < 0 ? "?" : (r.usesAAudio != 0 ? "AAudio" : "OpenSLES"),
                       r.sharing < 0 ? "?" : oboe::convertToText ((oboe::SharingMode) r.sharing),
                       r.perf < 0 ? "?" : oboe::convertToText ((oboe::PerformanceMode) r.perf),
                       r.format < 0 ? "?" : oboe::convertToText ((oboe::AudioFormat) r.format),
                       r.sampleRate, r.bufferSize, r.burst, r.mode);
    }
}

namespace automix
{
    bool setOboeCompatMode (int mode)
    {
        const int clamped = mode < 0 ? 0 : (mode > 2 ? 2 : mode);
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

    std::string getAudioDiagnostics()
    {
        std::string out = "mode=";
        out += modeName (gCompatMode.load());

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
    return (int) (automix::getOboeCompatMode() == 1 ? oboe::PerformanceMode::None
                                                    : oboe::PerformanceMode::LowLatency);
}

extern "C" void lmg_onOboeStreamOpen (int direction, int openResult, int usesAAudio,
                                      int sharingMode, int performanceMode, int format,
                                      int sampleRate, int bufferSizeFrames, int framesPerBurst)
{
    StreamReport r { direction, openResult, usesAAudio, sharingMode, performanceMode,
                     format, sampleRate, bufferSizeFrames, framesPerBurst,
                     automix::getOboeCompatMode(), 0 };
    {
        const std::lock_guard<std::mutex> lock (gReportsMutex);
        r.seq = ++gReportSeq;
        gReports[(gReportSeq - 1) % kMaxReports] = r;
    }

    char line[256];
    formatReport (line, sizeof (line), r);
    __android_log_print (ANDROID_LOG_INFO, kLogTag, "stream open: %s", line);
}
