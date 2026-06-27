#include <jni.h>
#include <memory>

#include <juce_core/juce_core.h>

#include "AutoMixAudioEngine.h"

// JUCE's intended Android init is the Java method com.rmsl.juce.Java.initialiseJUCE,
// whose registered native does TWO things in order:
//     JNIClassBase::initialiseAllClasses (env, context);  // cache all jclass refs
//     Thread::initialiseJUCE (env, context);              // store context, VM, watcher
// Our app has no com.rmsl.juce.Java class, so we must do both manually. Calling only
// Thread::initialiseJUCE leaves JUCE's cached jclass refs (AndroidApplication, ...)
// null, and getAppContext() then does IsInstanceOf(context, nullptr) -> JNI abort.
// JNIClassBase lives in an internal native header that isn't on our include path, so
// forward-declare just the one static we need; the symbol resolves from juce_core.cpp.
namespace juce
{
    class JNIClassBase
    {
    public:
        static void initialiseAllClasses (JNIEnv*, jobject context);
    };
}

// Single process-wide engine instance for the Stage 1 test tone.
static std::unique_ptr<AutoMixAudioEngine> gEngine;

// Shared helper: jstring -> juce::String, invoke a deck loader. Templates can't
// have C language linkage, so this must live OUTSIDE the extern "C" block below.
template <typename Fn>
static jboolean loadInto (JNIEnv* env, jstring path, Fn&& fn)
{
    if (gEngine == nullptr || path == nullptr)
        return JNI_FALSE;

    const char* utf = env->GetStringUTFChars (path, nullptr);
    if (utf == nullptr)
        return JNI_FALSE;

    const bool ok = fn (juce::String::fromUTF8 (utf));
    env->ReleaseStringUTFChars (path, utf);
    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_liquidmusicglass_engine_automix_AutoMixNativeEngine_nativeInit(
        JNIEnv* env, jobject /*thiz*/, jobject context)
{
    // Bring JUCE up exactly like its own Java entry point: cache JNI class refs
    // first, then hand JUCE the env + Context. Process-global, so do it once.
    static bool juceInitialised = false;
    if (! juceInitialised)
    {
        juce::JNIClassBase::initialiseAllClasses (env, context);
        juce::Thread::initialiseJUCE (env, context);
        juceInitialised = true;
    }

    if (gEngine == nullptr)
        gEngine = std::make_unique<AutoMixAudioEngine>();

    return gEngine->init() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_liquidmusicglass_engine_automix_AutoMixNativeEngine_nativeLoadTrack(
        JNIEnv* env, jobject /*thiz*/, jstring path)
{
    return loadInto (env, path, [] (const juce::String& p) { return gEngine->loadTrack (p); });
}

JNIEXPORT jboolean JNICALL
Java_com_liquidmusicglass_engine_automix_AutoMixNativeEngine_nativeLoadTrackA(
        JNIEnv* env, jobject /*thiz*/, jstring path)
{
    return loadInto (env, path, [] (const juce::String& p) { return gEngine->loadTrackA (p); });
}

JNIEXPORT jboolean JNICALL
Java_com_liquidmusicglass_engine_automix_AutoMixNativeEngine_nativeLoadTrackB(
        JNIEnv* env, jobject /*thiz*/, jstring path)
{
    return loadInto (env, path, [] (const juce::String& p) { return gEngine->loadTrackB (p); });
}

JNIEXPORT void JNICALL
Java_com_liquidmusicglass_engine_automix_AutoMixNativeEngine_nativeStartCrossfade(
        JNIEnv* /*env*/, jobject /*thiz*/, jdouble durationMs)
{
    if (gEngine != nullptr)
        gEngine->startCrossfade ((double) durationMs);
}

JNIEXPORT jboolean JNICALL
Java_com_liquidmusicglass_engine_automix_AutoMixNativeEngine_nativePrepareStretchB(
        JNIEnv* /*env*/, jobject /*thiz*/, jdouble bpmA, jdouble bpmB)
{
    if (gEngine == nullptr)
        return JNI_FALSE;
    return gEngine->prepareStretchB ((double) bpmA, (double) bpmB) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_liquidmusicglass_engine_automix_AutoMixNativeEngine_nativePlay(
        JNIEnv* /*env*/, jobject /*thiz*/)
{
    if (gEngine != nullptr)
        gEngine->play();
}

JNIEXPORT void JNICALL
Java_com_liquidmusicglass_engine_automix_AutoMixNativeEngine_nativePause(
        JNIEnv* /*env*/, jobject /*thiz*/)
{
    if (gEngine != nullptr)
        gEngine->pause();
}

JNIEXPORT void JNICALL
Java_com_liquidmusicglass_engine_automix_AutoMixNativeEngine_nativeStop(
        JNIEnv* /*env*/, jobject /*thiz*/)
{
    if (gEngine != nullptr)
        gEngine->stop();
}

JNIEXPORT void JNICALL
Java_com_liquidmusicglass_engine_automix_AutoMixNativeEngine_nativeStartTone(
        JNIEnv* /*env*/, jobject /*thiz*/)
{
    if (gEngine != nullptr)
        gEngine->startTone();
}

JNIEXPORT void JNICALL
Java_com_liquidmusicglass_engine_automix_AutoMixNativeEngine_nativeStopTone(
        JNIEnv* /*env*/, jobject /*thiz*/)
{
    if (gEngine != nullptr)
        gEngine->stopTone();
}

JNIEXPORT void JNICALL
Java_com_liquidmusicglass_engine_automix_AutoMixNativeEngine_nativeRelease(
        JNIEnv* /*env*/, jobject /*thiz*/)
{
    if (gEngine != nullptr)
    {
        gEngine->release();
        gEngine.reset();
    }
}

} // extern "C"
