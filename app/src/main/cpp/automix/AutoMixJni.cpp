#include <jni.h>
#include <cstring>
#include <memory>
#include <mutex>
#include <vector>

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
static std::shared_ptr<AutoMixAudioEngine> gEngine;
static std::mutex gEngineMutex;

// Мьютекс держим ТОЛЬКО на копирование указателя; сам вызов движка идёт без
// него. Раньше мьютекс жил на весь вызов, и тяжёлые операции (загрузка дека,
// offline-стретч на секунды) блокировали pause/silenceOutput и геттеры позиции,
// которые Media3 дёргает с main каждые ~100мс → фризы UI и «не нажимается
// пауза» во время загрузки. Движок внутри сам потокобезопасен (atomics +
// пер-дековые локи), сериализация JNI-уровня ему не нужна; shared_ptr держит
// объект живым, даже если nativeRelease случится посреди чужого вызова.
static std::shared_ptr<AutoMixAudioEngine> engineSnapshot()
{
    std::lock_guard<std::mutex> lock (gEngineMutex);
    return gEngine;
}

template <typename Fn, typename R>
static R withEngine (R fallback, Fn&& fn)
{
    if (const auto engine = engineSnapshot())
        return fn (*engine);
    return fallback;
}

template <typename Fn>
static void withEngineVoid (Fn&& fn)
{
    if (const auto engine = engineSnapshot())
        fn (*engine);
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
    std::shared_ptr<AutoMixAudioEngine> engine;
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
            gEngine = std::make_shared<AutoMixAudioEngine>();
        engine = gEngine;
    }

    // init() открывает аудио-устройство (может занять сотни мс) — уже без
    // мьютекса, чтобы не блокировать снапшоты из других потоков.
    return engine->init() ? JNI_TRUE : JNI_FALSE;
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
Java_com_liquidmusicglass_engine_automix_AutoMixNativeEngine_nativeFxSetBalance(
        JNIEnv* /*env*/, jobject /*thiz*/, jfloat pan)
{
    withEngineVoid ([&] (AutoMixAudioEngine& engine) { engine.setBalance ((float) pan); });
}

JNIEXPORT void JNICALL
Java_com_liquidmusicglass_engine_automix_AutoMixNativeEngine_nativeFxSetMono(
        JNIEnv* /*env*/, jobject /*thiz*/, jboolean on)
{
    withEngineVoid ([&] (AutoMixAudioEngine& engine) { engine.setMono (on == JNI_TRUE); });
}

JNIEXPORT void JNICALL
Java_com_liquidmusicglass_engine_automix_AutoMixNativeEngine_nativeFxSetParamEqEnabled(
        JNIEnv* /*env*/, jobject /*thiz*/, jboolean on)
{
    withEngineVoid ([&] (AutoMixAudioEngine& engine) { engine.setParamEqEnabled (on == JNI_TRUE); });
}

JNIEXPORT void JNICALL
Java_com_liquidmusicglass_engine_automix_AutoMixNativeEngine_nativeFxSetParamBand(
        JNIEnv* /*env*/, jobject /*thiz*/, jint band, jfloat freqHz, jfloat q, jfloat gainDb)
{
    withEngineVoid ([&] (AutoMixAudioEngine& engine) {
        engine.setParamBand ((int) band, (float) freqHz, (float) q, (float) gainDb);
    });
}

JNIEXPORT void JNICALL
Java_com_liquidmusicglass_engine_automix_AutoMixNativeEngine_nativeFxSetReverb(
        JNIEnv* /*env*/, jobject /*thiz*/, jboolean on, jfloat roomSize, jfloat damping, jfloat wet)
{
    withEngineVoid ([&] (AutoMixAudioEngine& engine) {
        engine.setReverbFx (on == JNI_TRUE, (float) roomSize, (float) damping, (float) wet);
    });
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

JNIEXPORT jint JNICALL
Java_com_liquidmusicglass_engine_automix_AutoMixNativeEngine_nativeXRunCount(
        JNIEnv* /*env*/, jobject /*thiz*/)
{
    return withEngine ((jint) -1, [] (AutoMixAudioEngine& engine) { return (jint) engine.xRunCount(); });
}

JNIEXPORT void JNICALL
Java_com_liquidmusicglass_engine_automix_AutoMixNativeEngine_nativeEscalateBufferForUnderruns(
        JNIEnv* /*env*/, jobject /*thiz*/)
{
    withEngineVoid ([] (AutoMixAudioEngine& engine) { engine.escalateBufferForUnderruns(); });
}

JNIEXPORT void JNICALL
Java_com_liquidmusicglass_engine_automix_AutoMixNativeEngine_nativeSetPowerSaveMode(
        JNIEnv* /*env*/, jobject /*thiz*/, jboolean on)
{
    withEngineVoid ([&] (AutoMixAudioEngine& engine) { engine.setPowerSaveMode (on == JNI_TRUE); });
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

JNIEXPORT jlong JNICALL
Java_com_liquidmusicglass_engine_automix_AutoMixNativeEngine_nativeCallbackCount(
        JNIEnv* /*env*/, jobject /*thiz*/)
{
    return (jlong) automix::getCallbackCount();
}

// ── Haptic Music: бас-огибающая/удары для тактильного движка (опрос ~40 Гц) ──

JNIEXPORT jfloat JNICALL
Java_com_liquidmusicglass_engine_automix_AutoMixNativeEngine_nativeHapticEnv(
        JNIEnv* /*env*/, jobject /*thiz*/)
{
    return (jfloat) automix::getHapticEnv();
}

JNIEXPORT jlong JNICALL
Java_com_liquidmusicglass_engine_automix_AutoMixNativeEngine_nativeHapticBeatCount(
        JNIEnv* /*env*/, jobject /*thiz*/)
{
    return (jlong) automix::getHapticBeatCount();
}

JNIEXPORT jfloat JNICALL
Java_com_liquidmusicglass_engine_automix_AutoMixNativeEngine_nativeHapticBeatStrength(
        JNIEnv* /*env*/, jobject /*thiz*/)
{
    return (jfloat) automix::getHapticBeatStrength();
}

JNIEXPORT jlong JNICALL
Java_com_liquidmusicglass_engine_automix_AutoMixNativeEngine_nativeHapticMidBeatCount(
        JNIEnv* /*env*/, jobject /*thiz*/)
{
    return (jlong) automix::getHapticMidBeatCount();
}

JNIEXPORT jfloat JNICALL
Java_com_liquidmusicglass_engine_automix_AutoMixNativeEngine_nativeHapticMidBeatStrength(
        JNIEnv* /*env*/, jobject /*thiz*/)
{
    return (jfloat) automix::getHapticMidBeatStrength();
}

JNIEXPORT void JNICALL
Java_com_liquidmusicglass_engine_automix_AutoMixNativeEngine_nativeSetPlaybackVolume(
        JNIEnv* /*env*/, jobject /*thiz*/, jfloat v01)
{
    withEngineVoid ([&] (AutoMixAudioEngine& engine) { engine.setPlaybackVolume ((float) v01); });
}

// ── AudioTrack-выход (Java-sink, режим 6) ───────────────────────────────────
// Oboe-девайс закрывается, движок остаётся жив; блоки тянет Java-поток
// AudioTrackSink через nativeSinkRender. Путь ExoPlayer — работает на девайсах,
// где кривые оба нативных входа (vivo).

JNIEXPORT void JNICALL
Java_com_liquidmusicglass_engine_automix_AutoMixNativeEngine_nativeSinkStart(
        JNIEnv* /*env*/, jobject /*thiz*/, jint sampleRate, jint blockFrames)
{
    // Флаг ДО закрытия девайса: с этого момента гвард в applyBufferForRouteUnlocked
    // не даст ничему (BT-монитор, эскалация буфера) воскресить Oboe параллельно sink'у.
    automix::setSinkActive (true);
    withEngineVoid ([&] (AutoMixAudioEngine& engine) {
        engine.enterSinkMode ((double) sampleRate, (int) blockFrames);
    });
}

JNIEXPORT void JNICALL
Java_com_liquidmusicglass_engine_automix_AutoMixNativeEngine_nativeSinkRender(
        JNIEnv* env, jobject /*thiz*/, jshortArray out, jint frames)
{
    if (out == nullptr || frames <= 0)
        return;

    // Локальные буферы потока sink'а (единственный вызывающий) — без аллокаций
    // после первого вызова.
    static thread_local std::vector<float> lbuf, rbuf;
    if ((int) lbuf.size() < frames) { lbuf.resize ((size_t) frames); rbuf.resize ((size_t) frames); }

    bool rendered = false;
    withEngineVoid ([&] (AutoMixAudioEngine& engine) {
        engine.renderSinkBlock (lbuf.data(), rbuf.data(), (int) frames);
        rendered = true;
    });

    jshort* dst = (jshort*) env->GetPrimitiveArrayCritical (out, nullptr);
    if (dst == nullptr)
        return;
    if (rendered)
    {
        for (int i = 0; i < (int) frames; ++i)
        {
            const float l = juce::jlimit (-1.0f, 1.0f, lbuf[(size_t) i]);
            const float r = juce::jlimit (-1.0f, 1.0f, rbuf[(size_t) i]);
            dst[i * 2 + 0] = (jshort) juce::roundToInt (l * 32767.0f);
            dst[i * 2 + 1] = (jshort) juce::roundToInt (r * 32767.0f);
        }
    }
    else
    {
        std::memset (dst, 0, (size_t) frames * 2 * sizeof (jshort));
    }
    env->ReleasePrimitiveArrayCritical (out, dst, 0);
}

JNIEXPORT void JNICALL
Java_com_liquidmusicglass_engine_automix_AutoMixNativeEngine_nativeSinkStop(
        JNIEnv* /*env*/, jobject /*thiz*/)
{
    automix::setSinkActive (false);
    // Всегда возвращаем Oboe-девайс: последующий nativeSetOboeCompatMode может
    // оказаться no-op (тот же нативный режим), и без реопена здесь остались бы
    // без выхода вообще. Двойной реопен при смене режима — безвреден.
    withEngineVoid ([] (AutoMixAudioEngine& engine) { engine.reopenAudioDevice(); });
}

JNIEXPORT void JNICALL
Java_com_liquidmusicglass_engine_automix_AutoMixNativeEngine_nativeRelease(
        JNIEnv* /*env*/, jobject /*thiz*/)
{
    // Указатель забираем под коротким мьютексом, release() зовём уже без него.
    // In-flight вызовы на других потоках держат свой shared_ptr — объект умрёт,
    // когда завершится последний из них.
    std::shared_ptr<AutoMixAudioEngine> engine;
    {
        std::lock_guard<std::mutex> lock (gEngineMutex);
        engine = std::move (gEngine);
        gEngine.reset();
    }
    if (engine != nullptr)
        engine->release();
}

} // extern "C"
