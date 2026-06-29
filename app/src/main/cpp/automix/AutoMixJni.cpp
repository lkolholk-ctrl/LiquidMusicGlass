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

JNIEXPORT jboolean JNICALL
Java_com_liquidmusicglass_engine_automix_AutoMixNativeEngine_nativeLoadTrackAFd(
        JNIEnv* /*env*/, jobject /*thiz*/, jint fd, jlong offset, jlong length)
{
    if (gEngine == nullptr || fd < 0)
        return JNI_FALSE;
    return gEngine->loadTrackAFd ((int) fd, (long long) offset, (long long) length) ? JNI_TRUE : JNI_FALSE;
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
Java_com_liquidmusicglass_engine_automix_AutoMixNativeEngine_nativeSetEntryOffsetB(
        JNIEnv* /*env*/, jobject /*thiz*/, jdouble ms)
{
    if (gEngine != nullptr)
        gEngine->setEntryOffsetB ((double) ms);
}

JNIEXPORT void JNICALL
Java_com_liquidmusicglass_engine_automix_AutoMixNativeEngine_nativeSetEntryOffsetA(
        JNIEnv* /*env*/, jobject /*thiz*/, jdouble ms)
{
    if (gEngine != nullptr)
        gEngine->setEntryOffsetA ((double) ms);
}

JNIEXPORT void JNICALL
Java_com_liquidmusicglass_engine_automix_AutoMixNativeEngine_nativeClearDeckA(
        JNIEnv* /*env*/, jobject /*thiz*/)
{
    if (gEngine != nullptr)
        gEngine->clearDeckA();
}

JNIEXPORT void JNICALL
Java_com_liquidmusicglass_engine_automix_AutoMixNativeEngine_nativeSetBassSwap(
        JNIEnv* /*env*/, jobject /*thiz*/, jboolean enabled)
{
    if (gEngine != nullptr)
        gEngine->setBassSwap (enabled == JNI_TRUE);
}

// ── Graphic EQ ──────────────────────────────────────────────────────────────

JNIEXPORT void JNICALL
Java_com_liquidmusicglass_engine_automix_AutoMixNativeEngine_nativeSetEqEnabled(
        JNIEnv* /*env*/, jobject /*thiz*/, jboolean enabled)
{
    if (gEngine != nullptr)
        gEngine->setEqEnabled (enabled == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_liquidmusicglass_engine_automix_AutoMixNativeEngine_nativeSetEqBandGain(
        JNIEnv* /*env*/, jobject /*thiz*/, jint band, jfloat gainDb)
{
    if (gEngine != nullptr)
        gEngine->setEqBandGain ((int) band, (float) gainDb);
}

JNIEXPORT void JNICALL
Java_com_liquidmusicglass_engine_automix_AutoMixNativeEngine_nativeSetEqBands(
        JNIEnv* env, jobject /*thiz*/, jfloatArray gains)
{
    if (gEngine == nullptr || gains == nullptr)
        return;
    const jsize n = env->GetArrayLength (gains);
    if (n <= 0)
        return;
    jfloat* vals = env->GetFloatArrayElements (gains, nullptr);
    if (vals == nullptr)
        return;
    gEngine->setEqBands ((const float*) vals, (int) n);
    env->ReleaseFloatArrayElements (gains, vals, JNI_ABORT); // read-only, don't copy back
}

// ── Stage 8: full LOCAL player (ping-pong decks) ───────────────────────────

JNIEXPORT jboolean JNICALL
Java_com_liquidmusicglass_engine_automix_AutoMixNativeEngine_nativeLoadIncoming(
        JNIEnv* env, jobject /*thiz*/, jstring path)
{
    return loadInto (env, path, [] (const juce::String& p) { return gEngine->loadIncoming (p); });
}

JNIEXPORT jboolean JNICALL
Java_com_liquidmusicglass_engine_automix_AutoMixNativeEngine_nativeLoadIncomingFd(
        JNIEnv* /*env*/, jobject /*thiz*/, jint fd, jlong offset, jlong length)
{
    if (gEngine == nullptr || fd < 0)
        return JNI_FALSE;
    return gEngine->loadIncomingFd ((int) fd, (long long) offset, (long long) length) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_liquidmusicglass_engine_automix_AutoMixNativeEngine_nativeStartTransition(
        JNIEnv* /*env*/, jobject /*thiz*/, jdouble durationMs, jdouble entryMs)
{
    if (gEngine != nullptr)
        gEngine->startTransition ((double) durationMs, (double) entryMs);
}

JNIEXPORT void JNICALL
Java_com_liquidmusicglass_engine_automix_AutoMixNativeEngine_nativePlayCurrent(
        JNIEnv* /*env*/, jobject /*thiz*/)
{
    if (gEngine != nullptr)
        gEngine->playCurrent();
}

JNIEXPORT void JNICALL
Java_com_liquidmusicglass_engine_automix_AutoMixNativeEngine_nativeSeekCurrent(
        JNIEnv* /*env*/, jobject /*thiz*/, jdouble ms)
{
    if (gEngine != nullptr)
        gEngine->seekCurrent ((double) ms);
}

JNIEXPORT jdouble JNICALL
Java_com_liquidmusicglass_engine_automix_AutoMixNativeEngine_nativePositionMsCurrent(
        JNIEnv* /*env*/, jobject /*thiz*/)
{
    return gEngine != nullptr ? (jdouble) gEngine->positionMsCurrent() : 0.0;
}

JNIEXPORT jdouble JNICALL
Java_com_liquidmusicglass_engine_automix_AutoMixNativeEngine_nativeLengthMsCurrent(
        JNIEnv* /*env*/, jobject /*thiz*/)
{
    return gEngine != nullptr ? (jdouble) gEngine->lengthMsCurrent() : 0.0;
}

JNIEXPORT jboolean JNICALL
Java_com_liquidmusicglass_engine_automix_AutoMixNativeEngine_nativeIsCrossfadeActive(
        JNIEnv* /*env*/, jobject /*thiz*/)
{
    return (gEngine != nullptr && gEngine->isCrossfadeActive()) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_liquidmusicglass_engine_automix_AutoMixNativeEngine_nativeCurrentDeckIndex(
        JNIEnv* /*env*/, jobject /*thiz*/)
{
    return gEngine != nullptr ? (jint) gEngine->currentDeckIndex() : 0;
}

JNIEXPORT void JNICALL
Java_com_liquidmusicglass_engine_automix_AutoMixNativeEngine_nativeClearDeck(
        JNIEnv* /*env*/, jobject /*thiz*/, jint index)
{
    if (gEngine != nullptr)
        gEngine->clearDeck ((int) index);
}

JNIEXPORT jdouble JNICALL
Java_com_liquidmusicglass_engine_automix_AutoMixNativeEngine_nativePositionMsA(
        JNIEnv* /*env*/, jobject /*thiz*/)
{
    return gEngine != nullptr ? (jdouble) gEngine->positionMsA() : 0.0;
}

JNIEXPORT jdouble JNICALL
Java_com_liquidmusicglass_engine_automix_AutoMixNativeEngine_nativeLengthMsA(
        JNIEnv* /*env*/, jobject /*thiz*/)
{
    return gEngine != nullptr ? (jdouble) gEngine->lengthMsA() : 0.0;
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
