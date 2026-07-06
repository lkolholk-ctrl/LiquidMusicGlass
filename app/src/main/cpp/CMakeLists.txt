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

# ---------------------------------------------------------------------------
# lmg_resolve — нативные резолверы плейлистов (батч 16, защита #82).
# Логика скрейпа (URL/заголовки/парсинг) уходит из Kotlin в .so; секретные
# строки XOR-зашифрованы. Парсинг JSON — через nlohmann/json (корректный
# разэкран \uXXXX для кириллицы/эмодзи). Header-only, отдельный target, чтобы
# не влиять на аудио-движок.
# ---------------------------------------------------------------------------
FetchContent_Declare(
    nlohmann_json
    GIT_REPOSITORY https://github.com/nlohmann/json.git
    GIT_TAG v3.11.3
    GIT_SHALLOW TRUE
)
set(JSON_BuildTests OFF CACHE INTERNAL "")
set(JSON_Install OFF CACHE INTERNAL "")
FetchContent_MakeAvailable(nlohmann_json)

add_library(lmg_resolve SHARED resolve.cpp)
target_compile_features(lmg_resolve PRIVATE cxx_std_17)
target_compile_options(lmg_resolve PRIVATE
    -fexceptions          # nlohmann/json бросает при парсинге — ловим catch(...)
    -fvisibility=hidden
    -fomit-frame-pointer
    -fdata-sections
    -ffunction-sections
    -Oz
)
target_link_libraries(lmg_resolve android log nlohmann_json::nlohmann_json)

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

# Свежий Oboe вместо вендоренного в JUCE 7.0.12 (там 1.8.0): 1.9.3 несёт более
# полный QuirksManager с воркараундами под проблемные устройства. Безопасность
# апгрейда: JUCE 8 использует тот же (почти неизменённый) juce_Oboe_android.cpp
# с Oboe 1.9.x. Компилируем исходники Oboe так же напрямую в нашу либу.
FetchContent_Declare(
    Oboe
    GIT_REPOSITORY https://github.com/google/oboe.git
    GIT_TAG 1.9.3
    GIT_SHALLOW TRUE
)
FetchContent_GetProperties(Oboe)
if(NOT oboe_POPULATED)
    FetchContent_Populate(Oboe)
endif()

set(JUCE_MODULES_DIR ${juce_SOURCE_DIR}/modules)
set(OBOE_DIR ${oboe_SOURCE_DIR})

# Девайс-совместимость: JUCE жёстко открывает Oboe-поток Exclusive+LowLatency
# (AAudio MMAP-exclusive) — на части прошивок (Xiaomi/MIUI и др.) этот путь
# отдаёт белый шум вместо звука. Правим juce_Oboe_android.cpp в FetchContent-
# дереве: sharing/performance mode берутся из runtime-хуков (automix/OboeRuntime.cpp),
# каждое открытие потока репортится для он-девайс диагностики.
include(${CMAKE_CURRENT_SOURCE_DIR}/cmake/PatchJuceOboeCompat.cmake)
lmg_patch_juce_oboe("${JUCE_MODULES_DIR}/juce_audio_devices/native/juce_Oboe_android.cpp")

# Их предшественник — жёсткая string-replace замена Exclusive→Shared /
# LowLatency→None из ветки greeting — заменён runtime-хуками выше: дефолт тот же
# полевой проверенный Shared+None (SAFE в OboeRuntime), но режим переключается
# без пересборки, и каждое открытие потока диагностируется.
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
    automix/OboeRuntime.cpp
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
