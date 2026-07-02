# ─────────────────────────────────────────────────────────────────────────────
# Девайс-совместимость JUCE/Oboe (см. automix/OboeRuntime.cpp).
#
# JUCE жёстко открывает Oboe-потоки в SharingMode::Exclusive +
# PerformanceMode::LowLatency. Exclusive → AAudio MMAP-exclusive путь, который
# сломан на части прошивок (Xiaomi/MIUI: open() успешен, но на выходе белый
# шум вместо звука; Samsung Exynos и др.). Настройки в JUCE не выведены, поэтому
# правим исходник в FetchContent-дереве на этапе configure:
#   • oboe::SharingMode::Exclusive      → lmg_oboeSharingMode()      (runtime)
#   • oboe::PerformanceMode::LowLatency → lmg_oboePerformanceMode()  (runtime)
#   • после открытия потока — вызов lmg_onOboeStreamOpen(...) с фактическими
#     параметрами (API/sharing/perf/format/rate/burst) для он-девайс диагностики.
#
# Патч идемпотентен (маркер lmg_oboeSharingModeInt) и валится с FATAL_ERROR,
# если якоря не найдены — например после апгрейда JUCE: тогда патч надо
# перепроверить руками, а не молча собрать непропатченный движок.
# ─────────────────────────────────────────────────────────────────────────────

function(lmg_patch_juce_oboe juceOboeFile)
    if(NOT EXISTS "${juceOboeFile}")
        message(FATAL_ERROR "PatchJuceOboeCompat: file not found: ${juceOboeFile}")
    endif()

    file(READ "${juceOboeFile}" src)

    # Уже патчен (реконфигур того же дерева) — выходим.
    string(FIND "${src}" "lmg_oboeSharingModeInt" alreadyPatched)
    if(NOT alreadyPatched EQUAL -1)
        return()
    endif()

    # 1) Объявления хуков + inline-хелперы — сразу после открытия namespace juce
    #    (oboe/Oboe.h уже включён родительским juce_audio_devices.cpp).
    set(declsAnchor "template <typename OboeDataFormat>  struct OboeAudioIODeviceBufferHelpers {};")
    set(decls [=[//==============================================================================
// LiquidMusicGlass device-compat patch (inserted by cmake/PatchJuceOboeCompat.cmake).
// Sharing/performance mode become runtime choices so devices with a broken AAudio
// exclusive-MMAP path (Xiaomi noise-instead-of-audio etc.) can use the shared /
// legacy output path without a rebuild. Implemented in automix/OboeRuntime.cpp,
// which also records every opened stream for on-device diagnostics.
extern "C" int  lmg_oboeSharingModeInt();
extern "C" int  lmg_oboePerformanceModeInt();
extern "C" int  lmg_oboeBufferCapacityMinFrames();
extern "C" int  lmg_oboeForceI16();
extern "C" int  lmg_oboeAudioApiInt();
extern "C" void lmg_onOboeStreamOpen (int direction, int openResult, int usesAAudio,
                                      int sharingMode, int performanceMode, int format,
                                      int sampleRate, int bufferSizeFrames, int framesPerBurst,
                                      int bufferCapacityFrames, int channelCount);
static inline oboe::SharingMode     lmg_oboeSharingMode()     { return static_cast<oboe::SharingMode> (lmg_oboeSharingModeInt()); }
static inline oboe::PerformanceMode lmg_oboePerformanceMode() { return static_cast<oboe::PerformanceMode> (lmg_oboePerformanceModeInt()); }

template <typename OboeDataFormat>  struct OboeAudioIODeviceBufferHelpers {};]=])
    string(REPLACE "${declsAnchor}" "${decls}" src "${src}")

    string(FIND "${src}" "lmg_oboeSharingModeInt" declsOk)
    if(declsOk EQUAL -1)
        message(FATAL_ERROR "PatchJuceOboeCompat: decls anchor not found in ${juceOboeFile} "
                            "(JUCE upgrade changed juce_Oboe_android.cpp? Re-check the patch).")
    endif()

    # 1b) Запрос МИНИМАЛЬНОЙ ёмкости буфера при открытии потока. JUCE ёмкость не
    #     задаёт («let OS choose»), а часть HAL (vivo) выдаёт capacity = 1 burst
    #     (192 кадра ≈ 4 мс) — setBufferSizeInFrames клампится в неё, буфер
    #     остаётся микроскопическим → хронические underrun'ы → «шип». Вставляем
    #     ДО глобальной замены токена LowLatency (якорь содержит оригинальный токен).
    set(perfAnchor "            builder.setPerformanceMode (oboe::PerformanceMode::LowLatency);")
    set(perfWithCapacity [=[            builder.setPerformanceMode (oboe::PerformanceMode::LowLatency);

            builder.setAudioApi (static_cast<oboe::AudioApi> (lmg_oboeAudioApiInt()));

            if (const int lmgMinCap = lmg_oboeBufferCapacityMinFrames(); lmgMinCap > 0)
                builder.setBufferCapacityInFrames (lmgMinCap);]=])
    string(REPLACE "${perfAnchor}" "${perfWithCapacity}" src "${src}")
    string(FIND "${src}" "setBufferCapacityInFrames (lmgMinCap)" capOk)
    if(capOk EQUAL -1)
        message(FATAL_ERROR "PatchJuceOboeCompat: setPerformanceMode anchor not found in ${juceOboeFile}.")
    endif()

    # 1c) Принудительный 16-бит вывод. JUCE пробует Float и падает на int16 только
    #     если open ФЕЙЛИТСЯ — а часть HAL (vivo) float-поток «успешно» открывает и
    #     портит в микшере (fast-путь → шум, deep-путь → тишина). Режимы 3/4 в
    #     OboeRuntime пропускают float-сессию → JUCE идёт по своей штатной
    #     int16-ветке ниже.
    set(floatAnchor [=[    if (sdkVersion >= 21)
    {
        session.reset (new OboeSessionImpl<float> (owner, inputDeviceId, outputDeviceId,
                                                   numInputChannels, numOutputChannels, sampleRate, bufferSize));]=])
    set(floatGated [=[    if (sdkVersion >= 21 && ! lmg_oboeForceI16())
    {
        session.reset (new OboeSessionImpl<float> (owner, inputDeviceId, outputDeviceId,
                                                   numInputChannels, numOutputChannels, sampleRate, bufferSize));]=])
    string(REPLACE "${floatAnchor}" "${floatGated}" src "${src}")
    string(FIND "${src}" "lmg_oboeForceI16()" i16Ok)
    if(i16Ok EQUAL -1)
        message(FATAL_ERROR "PatchJuceOboeCompat: float-session anchor not found in ${juceOboeFile}.")
    endif()

    # 2) Все 6 захардкоженных Exclusive (probe-поток, output/input сессии,
    #    рестарт после disconnect, realtime-thread test stream) → runtime-выбор.
    string(FIND "${src}" "oboe::SharingMode::Exclusive" sharingFound)
    if(sharingFound EQUAL -1)
        message(FATAL_ERROR "PatchJuceOboeCompat: no oboe::SharingMode::Exclusive occurrences found.")
    endif()
    string(REPLACE "oboe::SharingMode::Exclusive" "lmg_oboeSharingMode()" src "${src}")

    # 3) Захардкоженный LowLatency (setPerformanceMode + строка лога) → runtime-выбор.
    string(FIND "${src}" "oboe::PerformanceMode::LowLatency" perfFound)
    if(perfFound EQUAL -1)
        message(FATAL_ERROR "PatchJuceOboeCompat: no oboe::PerformanceMode::LowLatency occurrences found.")
    endif()
    string(REPLACE "oboe::PerformanceMode::LowLatency" "lmg_oboePerformanceMode()" src "${src}")

    # 4) Отчёт о каждом открытом потоке — после фиксапа размера буфера, чтобы
    #    размеры в отчёте были финальными.
    set(openAnchor [=[            if (stream != nullptr && newBufferSize != 0)
            {
                JUCE_OBOE_LOG ("Setting the bufferSizeInFrames to " + String (newBufferSize));
                stream->setBufferSizeInFrames (newBufferSize);
            }]=])
    set(openReport [=[            if (stream != nullptr && newBufferSize != 0)
            {
                JUCE_OBOE_LOG ("Setting the bufferSizeInFrames to " + String (newBufferSize));
                stream->setBufferSizeInFrames (newBufferSize);
            }

            lmg_onOboeStreamOpen ((int) direction,
                                  (int) openResult,
                                  stream != nullptr ? (int) stream->usesAAudio() : -1,
                                  stream != nullptr ? (int) stream->getSharingMode() : -1,
                                  stream != nullptr ? (int) stream->getPerformanceMode() : -1,
                                  stream != nullptr ? (int) stream->getFormat() : -1,
                                  stream != nullptr ? (int) stream->getSampleRate() : 0,
                                  stream != nullptr ? (int) stream->getBufferSizeInFrames() : 0,
                                  stream != nullptr ? (int) stream->getFramesPerBurst() : 0,
                                  stream != nullptr ? (int) stream->getBufferCapacityInFrames() : 0,
                                  stream != nullptr ? (int) stream->getChannelCount() : 0);]=])
    string(REPLACE "${openAnchor}" "${openReport}" src "${src}")

    string(FIND "${src}" "lmg_onOboeStreamOpen ((int) direction" hookOk)
    if(hookOk EQUAL -1)
        message(FATAL_ERROR "PatchJuceOboeCompat: stream-open anchor not found in ${juceOboeFile} "
                            "(JUCE upgrade changed OboeStream::open? Re-check the patch).")
    endif()

    file(WRITE "${juceOboeFile}" "${src}")
    message(STATUS "PatchJuceOboeCompat: patched ${juceOboeFile}")
endfunction()
