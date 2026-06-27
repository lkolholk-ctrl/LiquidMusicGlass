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

set(JUCE_MODULES_DIR ${juce_SOURCE_DIR}/modules)
set(OBOE_DIR ${JUCE_MODULES_DIR}/juce_audio_devices/native/oboe)

# JUCE vendors a full copy of Oboe (not a submodule), so the shallow clone has
# it. Compile it straight into our lib; JUCE's juce_android_Oboe.cpp uses it.
file(GLOB_RECURSE OBOE_SOURCES CONFIGURE_DEPENDS ${OBOE_DIR}/src/*.cpp)

add_library(
    automix_juce
    SHARED
    automix/AutoMixAudioEngine.cpp
    automix/AutoMixJni.cpp
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
)

target_compile_features(automix_juce PRIVATE cxx_std_17)

target_include_directories(automix_juce PRIVATE
    ${JUCE_MODULES_DIR}
    ${OBOE_DIR}/include
    ${OBOE_DIR}/src
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
    JUCE_USE_FLAC=0
    JUCE_USE_OGGVORBIS=0
    JUCE_USE_MP3AUDIOFORMAT=0
)

find_library(android-lib android)
find_library(juce-log-lib log)
find_library(opensl-lib OpenSLES)
find_library(aaudio-lib aaudio)

target_link_libraries(automix_juce
    ${android-lib}
    ${juce-log-lib}
    ${opensl-lib}
    ${aaudio-lib}
)
