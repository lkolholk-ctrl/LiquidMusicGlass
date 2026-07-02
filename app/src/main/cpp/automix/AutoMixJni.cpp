#include <jni.h>
#include <memory>
#include <mutex>

#include <juce_core/juce_core.h>

#include "AutoMixAudioEngine.h"
#include "OboeRuntime.h"

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
static std::mutex gEngineMutex;

template <typename Fn, typename R>
static R withEngine (R fallback, Fn&& fn)
{
    std::lock_guard<std::mutex> lock (gEngineMutex);
    if (gEngine == nullptr)
        return fallback;
    return fn (*gEngine);
}

template <typename Fn>
static void withEngineVoid (Fn&& fn)
{
    std::lock_guard<std::mutex> lock (gEngineMutex);
    if (gEngine != nullptr)
        fn (*gEngine);
}

// Shared helper: jstring -> juce::String, invoke a deck loader. Templates can't
// have C language linkage, so this must live OUTSIDE the extern "C" block below.
template <typename Fn>
static jboolean loadInto (JNIEnv* env, jstring path, Fn&& fn)
{
    if (path == nullptr)
        return JNI_FALSE;

    const char* utf = env->GetStringUTFChars (path, nullptr);
    if (utf == nullptr)
        return JNI_FALSE;

    const auto pathString = juce::String::fromUTF8 (utf);
    env->ReleaseStringUTFChars (path, utf);

    return withEngine (JNI_FALSE, [&] (AutoMixAudioEngine& engine) {
        return fn (engine, pathString) ? JNI_TRUE : JNI_FALSE;
    });
}

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_liquidmusicglass_engine_automix_AutoMixNativeEngine_nativeInit(
        JNIEnv* env, jobject /*thiz*/, jobject context)
{
    std::lock_guard<std::mutex> lock (gEngineMutex);

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
    return loadInto (env, path, [] (AutoMixAudioEngine& engine, const juce::String& p) { return engine.loadTrack (p); });
}

JNIEXPORT jboolean JNICALL
Java_com_liquidmusicglass_engine_automix_AutoMixNativeEngine_nativeLoadTrackA(
        JNIEnv* env, jobject /*thiz*/, jstring path)
{
    return loadInto (env, path, [] (AutoMixAudioEngine& engine, const juce::String& p) { return engine.loadTrackA (p); });
}

JNIEXPORT jboolean JNICALL
Java_com_liquidmusicglass_engine_automix_AutoMixNativeEngine_nativeLoadTrackB(
        JNIEnv* env, jobject /*thiz*/, jstring path)
{
    return loadInto (env, path, [] (AutoMixAudioEngine& engine, const juce::String& p) { return engine.loadTrackB (p); });
}

JNIEXPORT jboolean JNICALL
Java_com_liquidmusicglass_engine_automix_AutoMixNativeEngine_nativeLoadTrackAFd(
        JNIEnv* /*env*/, jobject /*thiz*/, jint fd, jlong offset, jlong length)
{
    if (fd < 0)
        return JNI_FALSE;
    return withEngine (JNI_FALSE, [&] (AutoMixAudioEngine& engine) {
        return engine.loadTrackAFd ((int) fd, (long long) offset, (long long) length) ? JNI_TRUE : JNI_FALSE;
    });
}

JNIEXPORT void JNICALL
Java_com_liquidmusicglass_engine_automix_AutoMixNativeEngine_nativeStartCrossfade(
        JNIEnv* /*env*/, jobject /*thiz*/, jdouble durationMs, jint transitionType)
{
    withEngineVoid ([&] (AutoMixAudioEngine& engine) {
        engine.startCrossfade ((double) durationMs, (int) transitionType);
    });
}

JNIEXPORT jboolean JNICALL
Java_com_liquidmusicglass_engine_automix_AutoMixNativeEngine_nativePrepareStretchB(
        JNIEnv* /*env*/, jobject /*thiz*/, jdouble bpmA, jdouble bpmB)
{
    return withEngine (JNI_FALSE, [&] (AutoMixAudioEngine& engine) {
        return engine.prepareStretchB ((double) bpmA, (double) bpmB) ? JNI_TRUE : JNI_FALSE;
    });
}

JNIEXPORT void JNICALL
Java_com_liquidmusicglass_engine_automix_AutoMixNativeEngine_nativeSetEntryOffsetB(
        JNIEnv* /*env*/, jobject /*thiz*/, jdouble ms)
{
    withEngineVoid ([&] (AutoMixAudioEngine& engine) { engine.setEntryOffsetB ((double) ms); });
}

JNIEXPORT void JNICALL
Java_com_liquidmusicglass_engine_automix_AutoMixNativeEngine_nativeSetEntryOffsetA(
        JNIEnv* /*env*/, jobject /*thiz*/, jdouble ms)
{
    withEngineVoid ([&] (AutoMixAudioEngine& engine) { engine.setEntryOffsetA ((double) ms); });
}

JNIEXPORT void JNICALL
Java_com_liquidmusicglass_engine_automix_AutoMixNativeEngine_nativeClearDeckA(
        JNIEnv* /*env*/, jobject /*thiz*/)
{
    withEngineVoid ([] (AutoMixAudioEngine& engine) { engine.clearDeckA(); });
}

JNIEXPORT void JNICALL
Java_com_liquidmusicglass_engine_automix_AutoMixNativeEngine_nativeSetBassSwap(
        JNIEnv* /*env*/, jobject /*thiz*/, jboolean enabled)
{
    withEngineVoid ([&] (AutoMixAudioEngine& engine) { engine.setBassSwap (enabled == JNI_TRUE); });
}

// ── Graphic EQ ──────────────────────────────────────────────────────────────

JNIEXPORT void JNICALL
Java_com_liquidmusicglass_engine_automix_AutoMixNativeEngine_nativeSetEqEnabled(
        JNIEnv* /*env*/, jobject /*thiz*/, jboolean enabled)
{
    withEngineVoid ([&] (AutoMixAudioEngine& engine) { engine.setEqEnabled (enabled == JNI_TRUE); });
}

JNIEXPORT void JNICALL
Java_com_liquidmusicglass_engine_automix_AutoMixNativeEngine_nativeSetEqBandGain(
        JNIEnv* /*env*/, jobject /*thiz*/, jint band, jfloat gainDb)
{
    withEngineVoid ([&] (AutoMixAudioEngine& engine) { engine.setEqBandGain ((int) band, (float) gainDb); });
}

JNIEXPORT void JNICALL
Java_com_liquidmusicglass_engine_automix_AutoMixNativeEngine_nativeSetEqBands(
        JNIEnv* env, jobject /*thiz*/, jfloatArray gains)
{
    if (gains == nullptr)
        return;
    const jsize n = env->GetArrayLength (gains);
    if (n <= 0)
        return;
    jfloat* vals = env->GetFloatArrayElements (gains, nullptr);
    if (vals == nullptr)
        return;
    withEngineVoid ([&] (AutoMixAudioEngine& engine) { engine.setEqBands ((const float*) vals, (int) n); });
    env->ReleaseFloatArrayElements (gains, vals, JNI_ABORT); // read-only, don't copy back
}

// ── Профессиональная FX-цепочка ──────────────────────────────────────────────

JNIEXPORT void JNICALL
Java_com_liquidmusicglass_engine_automix_AutoMixNativeEngine_nativeFxSetMasterEnabled(
        JNIEnv* /*env*/, jobject /*thiz*/, jboolean on)
{
    withEngineVoid ([&] (AutoMixAudioEngine& engine) { engine.setFxMasterEnabled (on == JNI_TRUE); });
}

JNIEXPORT void JNICALL
Java_com_liquidmusicglass_engine_automix_AutoMixNativeEngine_nativeFxSetPreampGainDb(
        JNIEnv* /*env*/, jobject /*thiz*/, jfloat db)
{
    withEngineVoid ([&] (AutoMixAudioEngine& engine) { engine.setPreampGainDb ((float) db); });
}

JNIEXPORT void JNICALL
Java_com_liquidmusicglass_engine_automix_AutoMixNativeEngine_nativeFxSetBassBoost(
        JNIEnv* /*env*/, jobject /*thiz*/, jboolean on, jfloat freqHz, jfloat gainDb)
{
    withEngineVoid ([&] (AutoMixAudioEngine& engine) { engine.setBassBoost (on == JNI_TRUE, (float) freqHz, (float) gainDb); });
}

JNIEXPORT void JNICALL
Java_com_liquidmusicglass_engine_automix_AutoMixNativeEngine_nativeFxSetLoudnessEnabled(
        JNIEnv* /*env*/, jobject /*thiz*/, jboolean on)
{
    withEngineVoid ([&] (AutoMixAudioEngine& engine) { engine.setLoudnessEnabled (on == JNI_TRUE); });
}

JNIEXPORT void JNICALL
Java_com_liquidmusicglass_engine_automix_AutoMixNativeEngine_nativeFxSetCurrentVolume(
        JNIEnv* /*env*/, jobject /*thiz*/, jfloat v01)
{
    withEngineVoid ([&] (AutoMixAudioEngine& engine) { engine.setCurrentVolume ((float) v01); });
}

JNIEXPORT void JNICALL
Java_com_liquidmusicglass_engine_automix_AutoMixNativeEngine_nativeFxSetStereoWidth(
        JNIEnv* /*env*/, jobject /*thiz*/, jfloat width)
{
    withEngineVoid ([&] (AutoMixAudioEngine& engine) { engine.setStereoWidth ((float) width); });
}

JNIEXPORT void JNICALL
Java_com_liquidmusicglass_engine_automix_AutoMixNativeEngine_nativeFxSetCompressor(
        JNIEnv* /*env*/, jobject /*thiz*/, jboolean on,
        jfloat threshDb, jfloat ratio, jfloat attackMs, jfloat releaseMs)
{
    withEngineVoid ([&] (AutoMixAudioEngine& engine) {
        engine.setCompressorFx (on == JNI_TRUE, (float) threshDb, (float) ratio,
                                (float) attackMs, (float) releaseMs);
    });
}

JNIEXPORT void JNICALL
Java_com_liquidmusicglass_engine_automix_AutoMixNativeEngine_nativeFxSetLimiter(
        JNIEnv* /*env*/, jobject /*thiz*/, jboolean on, jfloat threshDb, jfloat releaseMs)
{
    withEngineVoid ([&] (AutoMixAudioEngine& engine) { engine.setLimiterFx (on == JNI_TRUE, (float) threshDb, (float) releaseMs); });
}

// ── Stage 8: full LOCAL player (ping-pong decks) ───────────────────────────

JNIEXPORT jboolean JNICALL
Java_com_liquidmusicglass_engine_automix_AutoMixNativeEngine_nativeLoadIncoming(
        JNIEnv* env, jobject /*thiz*/, jstring path)
{
    return loadInto (env, path, [] (AutoMixAudioEngine& engine, const juce::String& p) { return engine.loadIncoming (p); });
}

JNIEXPORT jboolean JNICALL
Java_com_liquidmusicglass_engine_automix_AutoMixNativeEngine_nativeLoadIncomingFd(
        JNIEnv* /*env*/, jobject /*thiz*/, jint fd, jlong offset, jlong length)
{
    if (fd < 0)
        return JNI_FALSE;
    return withEngine (JNI_FALSE, [&] (AutoMixAudioEngine& engine) {
        return engine.loadIncomingFd ((int) fd, (long long) offset, (long long) length) ? JNI_TRUE : JNI_FALSE;
    });
}

JNIEXPORT void JNICALL
Java_com_liquidmusicglass_engine_automix_AutoMixNativeEngine_nativeStartTransition(
        JNIEnv* /*env*/, jobject /*thiz*/, jdouble durationMs, jdouble entryMs, jint transitionType)
{
    withEngineVoid ([&] (AutoMixAudioEngine& engine) {
        engine.startTransition ((double) durationMs, (double) entryMs, (int) transitionType);
    });
}

JNIEXPORT void JNICALL
Java_com_liquidmusicglass_engine_automix_AutoMixNativeEngine_nativePlayCurrent(
        JNIEnv* /*env*/, jobject /*thiz*/)
{
    withEngineVoid ([] (AutoMixAudioEngine& engine) { engine.playCurrent(); });
}

JNIEXPORT void JNICALL
Java_com_liquidmusicglass_engine_automix_AutoMixNativeEngine_nativeSeekCurrent(
        JNIEnv* /*env*/, jobject /*thiz*/, jdouble ms)
{
    withEngineVoid ([&] (AutoMixAudioEngine& engine) { engine.seekCurrent ((double) ms); });
}

JNIEXPORT jdouble JNICALL
Java_com_liquidmusicglass_engine_automix_AutoMixNativeEngine_nativePositionMsCurrent(
        JNIEnv* /*env*/, jobject /*thiz*/)
{
    return withEngine ((jdouble) 0.0, [] (AutoMixAudioEngine& engine) { return (jdouble) engine.positionMsCurrent(); });
}

JNIEXPORT jdouble JNICALL
Java_com_liquidmusicglass_engine_automix_AutoMixNativeEngine_nativeLengthMsCurrent(
        JNIEnv* /*env*/, jobject /*thiz*/)
{
    return withEngine ((jdouble) 0.0, [] (AutoMixAudioEngine& engine) { return (jdouble) engine.lengthMsCurrent(); });
}

JNIEXPORT jboolean JNICALL
Java_com_liquidmusicglass_engine_automix_AutoMixNativeEngine_nativeIsCrossfadeActive(
        JNIEnv* /*env*/, jobject /*thiz*/)
{
    return withEngine (JNI_FALSE, [] (AutoMixAudioEngine& engine) {
        return engine.isCrossfadeActive() ? JNI_TRUE : JNI_FALSE;
    });
}

JNIEXPORT jint JNICALL
Java_com_liquidmusicglass_engine_automix_AutoMixNativeEngine_nativeCurrentDeckIndex(
        JNIEnv* /*env*/, jobject /*thiz*/)
{
    return withEngine ((jint) 0, [] (AutoMixAudioEngine& engine) { return (jint) engine.currentDeckIndex(); });
}

JNIEXPORT void JNICALL
Java_com_liquidmusicglass_engine_automix_AutoMixNativeEngine_nativeClearDeck(
        JNIEnv* /*env*/, jobject /*thiz*/, jint index)
{
    withEngineVoid ([&] (AutoMixAudioEngine& engine) { engine.clearDeck ((int) index); });
}

JNIEXPORT jdouble JNICALL
Java_com_liquidmusicglass_engine_automix_AutoMixNativeEngine_nativePositionMsA(
        JNIEnv* /*env*/, jobject /*thiz*/)
{
    return withEngine ((jdouble) 0.0, [] (AutoMixAudioEngine& engine) { return (jdouble) engine.positionMsA(); });
}

JNIEXPORT jdouble JNICALL
Java_com_liquidmusicglass_engine_automix_AutoMixNativeEngine_nativeLengthMsA(
        JNIEnv* /*env*/, jobject /*thiz*/)
{
    return withEngine ((jdouble) 0.0, [] (AutoMixAudioEngine& engine) { return (jdouble) engine.lengthMsA(); });
}

JNIEXPORT void JNICALL
Java_com_liquidmusicglass_engine_automix_AutoMixNativeEngine_nativePlay(
        JNIEnv* /*env*/, jobject /*thiz*/)
{
    withEngineVoid ([] (AutoMixAudioEngine& engine) { engine.play(); });
}

JNIEXPORT void JNICALL
Java_com_liquidmusicglass_engine_automix_AutoMixNativeEngine_nativePause(
        JNIEnv* /*env*/, jobject /*thiz*/)
{
    withEngineVoid ([] (AutoMixAudioEngine& engine) { engine.pause(); });
}

JNIEXPORT void JNICALL
Java_com_liquidmusicglass_engine_automix_AutoMixNativeEngine_nativeSilenceOutput(
        JNIEnv* /*env*/, jobject /*thiz*/)
{
    withEngineVoid ([] (AutoMixAudioEngine& engine) { engine.silenceOutput(); });
}

JNIEXPORT void JNICALL
Java_com_liquidmusicglass_engine_automix_AutoMixNativeEngine_nativeStop(
        JNIEnv* /*env*/, jobject /*thiz*/)
{
    withEngineVoid ([] (AutoMixAudioEngine& engine) { engine.stop(); });
}

JNIEXPORT void JNICALL
Java_com_liquidmusicglass_engine_automix_AutoMixNativeEngine_nativeStartTone(
        JNIEnv* /*env*/, jobject /*thiz*/)
{
    withEngineVoid ([] (AutoMixAudioEngine& engine) { engine.startTone(); });
}

JNIEXPORT void JNICALL
Java_com_liquidmusicglass_engine_automix_AutoMixNativeEngine_nativeStopTone(
        JNIEnv* /*env*/, jobject /*thiz*/)
{
    withEngineVoid ([] (AutoMixAudioEngine& engine) { engine.stopTone(); });
}

JNIEXPORT void JNICALL
Java_com_liquidmusicglass_engine_automix_AutoMixNativeEngine_nativeSetOutputRouteBluetooth(
        JNIEnv* /*env*/, jobject /*thiz*/, jboolean isBluetooth)
{
    withEngineVoid ([&] (AutoMixAudioEngine& engine) { engine.onOutputRouteChanged (isBluetooth == JNI_TRUE); });
}

// ── Девайс-совместимость Oboe (см. OboeRuntime.h) ───────────────────────────

// Не требует живого движка: до init() просто запоминает режим (первое открытие
// потока его подхватит); на живом движке — переоткрывает поток с новым режимом.
JNIEXPORT void JNICALL
Java_com_liquidmusicglass_engine_automix_AutoMixNativeEngine_nativeSetOboeCompatMode(
        JNIEnv* /*env*/, jobject /*thiz*/, jint mode)
{
    if (! automix::setOboeCompatMode ((int) mode))
        return;                                   // не изменился — устройство не трогаем
    withEngineVoid ([] (AutoMixAudioEngine& engine) { engine.reopenAudioDevice(); });
}

JNIEXPORT jstring JNICALL
Java_com_liquidmusicglass_engine_automix_AutoMixNativeEngine_nativeGetAudioDiagnostics(
        JNIEnv* env, jobject /*thiz*/)
{
    return env->NewStringUTF (automix::getAudioDiagnostics().c_str());
}

JNIEXPORT void JNICALL
Java_com_liquidmusicglass_engine_automix_AutoMixNativeEngine_nativeRelease(
        JNIEnv* /*env*/, jobject /*thiz*/)
{
    std::lock_guard<std::mutex> lock (gEngineMutex);
    if (gEngine != nullptr)
    {
        gEngine->release();
        gEngine.reset();
    }
}

} // extern "C"
