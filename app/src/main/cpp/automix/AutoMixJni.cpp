#include <jni.h>
#include <memory>

#include <juce_core/juce_core.h>

#include "AutoMixAudioEngine.h"

// Single process-wide engine instance for the Stage 1 test tone.
static std::unique_ptr<AutoMixAudioEngine> gEngine;

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_liquidmusicglass_engine_automix_AutoMixNativeEngine_nativeInit(
        JNIEnv* env, jobject /*thiz*/, jobject context)
{
    // JUCE needs the JNIEnv + Android Context before it can talk to the platform
    // (this app is not a JUCE_Application, so JUCE never sets this up itself).
    juce::Thread::initialiseJUCE(env, context);

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
