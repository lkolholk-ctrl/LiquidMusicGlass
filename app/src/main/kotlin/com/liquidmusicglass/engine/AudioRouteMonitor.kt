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
 * Следит за маршрутом аудио-вывода (подключение/отключение Bluetooth, гарнитуры)
 * через [AudioManager.AudioDeviceCallback] — это тот же триггер, что Oboe-событие
 * DISCONNECTED, но доступный нам без патча JUCE. При смене маршрута сообщает
 * движку, чтобы он переоткрыл Oboe-поток на текущем устройстве с правильным
 * буфером (BT → без fast-path; встроенный/проводной → fast-path/RT). Позиция
 * воспроизведения при этом сохраняется (движок не сбрасывает транспорты).
 *
 * Колбэк и вызовы движка идут на ФОНОВОМ потоке: переоткрытие устройства внутри
 * JUCE (close/reopen) нельзя делать ни на audio-, ни на main-потоке.
 */
object AudioRouteMonitor {

    private const val TAG = "AudioRouteMonitor"

    private var audioManager: AudioManager? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    @Volatile private var registered = false

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
                override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = pushRoute()
                override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = pushRoute()
            }, handler)
            registered = true
        }.onFailure { Log.w(TAG, "registerAudioDeviceCallback failed", it) }
    }

    /** Переотправить текущий маршрут в движок (после ленивого подъёма движка). */
    fun reapplyToEngine() {
        val h = handler
        if (h != null) h.post { pushRoute() } else pushRoute()
    }

    private fun pushRoute() {
        AutoMixNativeEngine.setOutputRouteBluetooth(isBluetoothActive())
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
