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
