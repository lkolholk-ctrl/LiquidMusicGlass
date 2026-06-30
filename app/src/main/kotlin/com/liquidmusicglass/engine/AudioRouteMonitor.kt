package com.liquidmusicglass.engine

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.liquidmusicglass.engine.automix.AutoMixNativeEngine

/**
 * Следит за маршрутом аудио-вывода (BT/гарнитура) через [AudioManager.AudioDeviceCallback]
 * и сообщает движку, чтобы он переоткрыл Oboe-поток с правильным буфером (BT → без
 * fast-path; встроенный → fast-path/RT). Позиция воспроизведения сохраняется.
 *
 * ВАЖНО против рывков/«циклички»:
 *  • DEBOUNCE — при смене маршрута система шлёт пачку add/removed подряд; коалесцируем
 *    их в ОДНО применение после паузы (settle), иначе шла череда close/reopen = рывки.
 *  • DEDUP — дёргаем движок ТОЛЬКО когда BT-состояние реально изменилось; иначе любые
 *    события устройств (в т.ч. наш собственный reopen, переключение из другого
 *    приложения) повторно переоткрывали поток → петля.
 *
 * Колбэк/вызовы движка — на фоновом потоке (close/reopen нельзя на audio/main).
 */
object AudioRouteMonitor {

    private const val TAG = "AudioRouteMonitor"
    private const val SETTLE_MS = 700L

    private var audioManager: AudioManager? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    // Последнее ПРИМЕНЁННОЕ BT-состояние. null = ещё не применяли (форс на первом).
    @Volatile private var lastAppliedBt: Boolean? = null

    private val applyRunnable = Runnable { applyIfChanged() }

    fun init(context: Context) {
        if (audioManager != null) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return  // AudioDeviceCallback c API 23
        val am = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        audioManager = am
        val t = HandlerThread("audio-route").apply { start() }
        thread = t
        handler = Handler(t.looper)

        runCatching {
            am.registerAudioDeviceCallback(object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = schedule()
                override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = schedule()
            }, handler)
        }.onFailure { Log.w(TAG, "registerAudioDeviceCallback failed", it) }
    }

    /** Применить текущий маршрут к движку (после ленивого подъёма движка). Форсим. */
    fun reapplyToEngine() {
        val h = handler ?: return
        h.post { lastAppliedBt = null; applyIfChanged() }
    }

    /** Коалесцируем пачку событий устройств в одно применение после паузы. */
    private fun schedule() {
        val h = handler ?: return
        h.removeCallbacks(applyRunnable)
        h.postDelayed(applyRunnable, SETTLE_MS)
    }

    private fun applyIfChanged() {
        val bt = isBluetoothActive()
        if (bt == lastAppliedBt) return        // маршрут реально не изменился → движок НЕ трогаем
        lastAppliedBt = bt
        AutoMixNativeEngine.setOutputRouteBluetooth(bt)
    }

    /** Активен ли беспроводной (BT) выход. SCO (звонки) не считаем — музыка идёт по A2DP/BLE. */
    private fun isBluetoothActive(): Boolean {
        val am = audioManager ?: return false
        return runCatching {
            val outs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            outs.any { d ->
                when (d.type) {
                    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> true
                    else -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                        (d.type == AudioDeviceInfo.TYPE_BLE_HEADSET || d.type == AudioDeviceInfo.TYPE_BLE_SPEAKER)
                }
            }
        }.getOrDefault(false)
    }
}
