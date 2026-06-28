package com.liquidmusicglass.engine.automix

import android.app.Activity
import java.lang.ref.WeakReference

/**
 * Держатель текущей Activity для JUCE-инициализации.
 *
 * JUCE на Android инициализируется через android Activity-контекст
 * (Thread::initialiseJUCE -> JuceActivityWatcher -> getAppContext делает
 * IsInstanceOf по android.app.Activity/Application). С applicationContext этот
 * путь падает с "JNI DETECTED ERROR: java_class == null in IsInstanceOf".
 *
 * UI (MainActivity) кладёт сюда ссылку, а AutoMixNativeEngine.init берёт её,
 * когда вызов идёт не из Activity (например, из AudioService/координатора).
 * WeakReference — чтобы не держать Activity и не течь.
 */
object JuceContextHolder {
    @Volatile private var ref: WeakReference<Activity>? = null

    fun set(activity: Activity) { ref = WeakReference(activity) }
    fun clear(activity: Activity) {
        if (ref?.get() === activity) ref = null
    }

    /** Живая Activity или null. */
    fun get(): Activity? = ref?.get()?.takeIf { !it.isFinishing && !it.isDestroyed }
}
