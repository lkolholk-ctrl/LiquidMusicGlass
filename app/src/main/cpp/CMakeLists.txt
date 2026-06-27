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

# Don't let JUCE build its host helper tool (juceaide) — we only link module
# interface libraries into a SHARED lib and never use BinaryData/app bundling,
# so juceaide is unnecessary and would otherwise need a host toolchain.
set(JUCE_BUILD_HELPER_TOOLS OFF CACHE BOOL "" FORCE)
set(JUCE_BUILD_EXTRAS OFF CACHE BOOL "" FORCE)
set(JUCE_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)

FetchContent_Declare(
    JUCE
    GIT_REPOSITORY https://github.com/juce-framework/JUCE.git
    GIT_TAG 7.0.12
    GIT_SHALLOW TRUE
)
FetchContent_MakeAvailable(JUCE)

add_library(
    automix_juce
    SHARED
    automix/AutoMixAudioEngine.cpp
    automix/AutoMixJni.cpp
)

target_compile_features(automix_juce PRIVATE cxx_std_17)

# Stage 1 keeps the JUCE footprint minimal: no networking, no browser, no plugins.
target_compile_definitions(automix_juce PRIVATE
    JUCE_STANDALONE_APPLICATION=1
    JUCE_USE_ANDROID_OBOE=1
    JUCE_USE_CURL=0
    JUCE_WEB_BROWSER=0
    JUCE_USE_FLAC=0
    JUCE_USE_OGGVORBIS=0
    JUCE_LOAD_CURL_SYMBOLS_LAZILY=0
)

target_link_libraries(automix_juce
    PRIVATE
    juce::juce_audio_devices
    juce::juce_audio_formats
    juce::juce_audio_basics
    juce::juce_dsp
    juce::juce_core
    PUBLIC
    juce::juce_recommended_config_flags
)
