cmake_minimum_required(VERSION 3.22.1)
project("icmkey")

add_library(
    icmkey
    SHARED
    icmkey.c
)

# Hiding all symbols except JNI_OnLoad and optimizing for security
target_compile_options(icmkey PRIVATE 
    -fvisibility=hidden
    -fomit-frame-pointer
    -fdata-sections
    -ffunction-sections
    -Oz # Optimize for size and obfuscation
)

add_library(lcm_native SHARED lcm_urls.c)
target_link_libraries(lcm_native android log)

# Native Security Library
add_library(lmg_security SHARED security.c)
target_compile_options(lmg_security PRIVATE 
    -fvisibility=hidden
    -fomit-frame-pointer
    -fdata-sections
    -ffunction-sections
    -Oz # Optimize for size and security
)
target_link_libraries(lmg_security android log)

find_library(log-lib log)

target_link_libraries(icmkey ${log-lib})

# ---------------------------------------------------------------------------
# AutoMix native audio engine (JUCE -> Oboe). Stage 1: 440 Hz test tone only.
#
# Kept fully separate from the existing icmkey / lcm_native / lmg_security
# targets above so JUCE cannot affect their obfuscation/flags. JUCE is pulled
# via FetchContent (the CI checkout uses no submodules), pinned to a stable tag.
# ---------------------------------------------------------------------------
include(FetchContent)

# Fetch the JUCE SOURCES only — deliberately NOT FetchContent_MakeAvailable /
# add_subdirectory(JUCE). JUCE's own CMake builds a host helper tool "juceaide"
# that compiles juce_gui_basics for the host and needs X11/freetype dev headers
# the CI runner doesn't have (fatal error: X11/Xlib.h). We don't need juceaide
# (no BinaryData / app bundling), so we compile just the audio modules we need
# directly into our target. This is the standard bare-CMake Android JUCE path
# and keeps the whole GUI/X11/juceaide toolchain out of the build.
FetchContent_Declare(
    JUCE
    GIT_REPOSITORY https://github.com/juce-framework/JUCE.git
    GIT_TAG 7.0.12
    GIT_SHALLOW TRUE
)
FetchContent_GetProperties(JUCE)
if(NOT juce_POPULATED)
    FetchContent_Populate(JUCE)
endif()

# Signalsmith Stretch (time-stretch for beat-matching). Header-only, MIT —
# safe for a closed-source app, no separate .so, no GPL linking concerns.
# Self-contained at this tag (root header + vendored dsp/, no submodules).
FetchContent_Declare(
    SignalsmithStretch
    GIT_REPOSITORY https://github.com/signalsmith-audio/signalsmith-stretch.git
    GIT_TAG 1.1.0
    GIT_SHALLOW TRUE
)
FetchContent_GetProperties(SignalsmithStretch)
if(NOT signalsmithstretch_POPULATED)
    FetchContent_Populate(SignalsmithStretch)
endif()

set(JUCE_MODULES_DIR ${juce_SOURCE_DIR}/modules)
set(OBOE_DIR ${JUCE_MODULES_DIR}/juce_audio_devices/native/oboe)

# ── ОТКАТ патча Oboe: возвращаем ДЕФОЛТНЫЙ Oboe-бэкенд JUCE (Exclusive +
# LowLatency). Прежний патч (Shared + PerformanceMode::None ради совместимости
# с записью экрана) сломал ЛОКАЛЬНОЕ воспроизведение (тишина + петля). Аудио
# важнее записи экрана — возвращаем заведомо рабочую конфигурацию.
# Реверс идемпотентен и устойчив к кэшу FetchContent: оригинал JUCE вообще не
# использует Shared/None (0 вхождений), поэтому эти замены трогают ТОЛЬКО ранее
# пропатченные значения; на свежескачанном исходнике — no-op.
set(JUCE_OBOE_CPP ${JUCE_MODULES_DIR}/juce_audio_devices/native/juce_Oboe_android.cpp)
if(NOT EXISTS ${JUCE_OBOE_CPP})
    set(JUCE_OBOE_CPP ${JUCE_MODULES_DIR}/juce_audio_devices/native/juce_android_Oboe.cpp)
endif()
if(EXISTS ${JUCE_OBOE_CPP})
    file(READ ${JUCE_OBOE_CPP} _oboe_src_orig)
    set(_oboe_src "${_oboe_src_orig}")
    string(REPLACE "SharingMode::Shared" "SharingMode::Exclusive" _oboe_src "${_oboe_src}")
    string(REPLACE "PerformanceMode::None" "PerformanceMode::LowLatency" _oboe_src "${_oboe_src}")
    if(NOT _oboe_src STREQUAL _oboe_src_orig)
        file(WRITE ${JUCE_OBOE_CPP} "${_oboe_src}")
        message(STATUS "automix: reverted JUCE Oboe backend to default Exclusive/LowLatency")
    endif()
endif()

# JUCE vendors a full copy of Oboe (not a submodule), so the shallow clone has
# it. Compile it straight into our lib; JUCE's juce_android_Oboe.cpp uses it.
file(GLOB_RECURSE OBOE_SOURCES CONFIGURE_DEPENDS ${OBOE_DIR}/src/*.cpp)

# JUCE's Android path includes <cpu-features.h> (android_getCpuFamily etc.). That
# header + impl ship with the NDK under sources/android/cpufeatures but aren't on
# the default include path, so add the dir and compile its source into our lib.
if(NOT DEFINED ANDROID_NDK)
    set(ANDROID_NDK ${CMAKE_ANDROID_NDK})
endif()
set(CPUFEATURES_DIR ${ANDROID_NDK}/sources/android/cpufeatures)

add_library(
    automix_juce
    SHARED
    automix/AutoMixAudioEngine.cpp
    automix/AudioFxChain.cpp
    automix/AutoMixJni.cpp
    automix/MediaCodecDecoder.cpp
    automix/MediaCodecAudioSource.cpp
    automix/TimeStretch.cpp
    # JUCE module unity builds, compiled directly (no juceaide). Order doesn't
    # matter for a single static-link target; deps resolved via the
    # JUCE_MODULE_AVAILABLE_* defines below.
    ${JUCE_MODULES_DIR}/juce_core/juce_core.cpp
    ${JUCE_MODULES_DIR}/juce_events/juce_events.cpp
    ${JUCE_MODULES_DIR}/juce_audio_basics/juce_audio_basics.cpp
    ${JUCE_MODULES_DIR}/juce_audio_formats/juce_audio_formats.cpp
    ${JUCE_MODULES_DIR}/juce_audio_devices/juce_audio_devices.cpp
    ${JUCE_MODULES_DIR}/juce_dsp/juce_dsp.cpp
    ${OBOE_SOURCES}
    ${CPUFEATURES_DIR}/cpu-features.c
)

target_compile_features(automix_juce PRIVATE cxx_std_17)

# Аудио-движок ВСЕГДА с оптимизацией: debug-вариант AGP собирает нативку с -O0,
# и JUCE DSP/ресемплер/FLAC-декод становятся в 5-10 раз медленнее — underrun'ы
# и заикания ровно в тех сборках, на которых идёт отладка. -O2 не мешает
# отладке Kotlin-части. -fno-math-errno разрешает векторизацию sin/cos/sqrt.
target_compile_options(automix_juce PRIVATE -O2 -fno-math-errno)

target_include_directories(automix_juce PRIVATE
    ${JUCE_MODULES_DIR}
    ${OBOE_DIR}/include
    ${OBOE_DIR}/src
    ${CPUFEATURES_DIR}
    ${signalsmithstretch_SOURCE_DIR}   # <signalsmith-stretch.h> (+ vendored dsp/)
)

# Stand in for the header juce_add_module would generate: tell each module which
# sibling modules exist, and keep the footprint minimal (no net/browser/codecs).
target_compile_definitions(automix_juce PRIVATE
    # JUCE detects Android from a pre-defined JUCE_ANDROID (NOT __ANDROID__); the
    # NDK also defines __linux__, so without this JUCE picks the Linux path and
    # pulls <sys/dir.h> (gone on Android) and hides the JUCE_ANDROID-only API.
    JUCE_ANDROID=1
    # We provide module settings via -D below instead of a generated AppConfig,
    # so silence JUCE's "No global header file was included!" guard.
    JUCE_GLOBAL_MODULE_SETTINGS_INCLUDED=1
    JUCE_STANDALONE_APPLICATION=1
    JUCE_MODULE_AVAILABLE_juce_core=1
    JUCE_MODULE_AVAILABLE_juce_events=1
    JUCE_MODULE_AVAILABLE_juce_audio_basics=1
    JUCE_MODULE_AVAILABLE_juce_audio_formats=1
    JUCE_MODULE_AVAILABLE_juce_audio_devices=1
    JUCE_MODULE_AVAILABLE_juce_dsp=1
    JUCE_USE_ANDROID_OBOE=1
    JUCE_USE_CURL=0
    JUCE_WEB_BROWSER=0
    # Stage 2: decode real files. FLAC + Ogg Vorbis are bundled in JUCE (their
    # C sources are #included by juce_audio_formats.cpp, which we already build —
    # no extra CMake sources). WAV/AIFF are always on. MP3 stays off (JUCE's MP3
    # decoder is evaluation-only/licensing-encumbered); mp3/aac come later via
    # Android MediaCodec / FFmpeg.
    JUCE_USE_FLAC=1
    JUCE_USE_OGGVORBIS=1
    JUCE_USE_MP3AUDIOFORMAT=0
)

find_library(android-lib android)
find_library(juce-log-lib log)
find_library(opensl-lib OpenSLES)
find_library(aaudio-lib aaudio)
# System media NDK (AMediaExtractor/AMediaCodec) for mp3/aac decode — no APK size.
find_library(mediandk-lib mediandk)

target_link_libraries(automix_juce
    ${android-lib}
    ${juce-log-lib}
    ${opensl-lib}
    ${aaudio-lib}
    ${mediandk-lib}
)
