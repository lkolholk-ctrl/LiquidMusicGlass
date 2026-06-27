package com.liquidmusicglass.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Реактивный детектор режима энергосбережения (Battery Saver).
 *
 * В энергосбере тяжёлые эффекты УПРОЩАЮТСЯ (а не выключаются): полноэкранный
 * 6-октавный fbm-дым → лёгкий градиентный фон, AGSL-мудкарточки → статичный
 * градиент. Состояние [active] — Compose-состояние: эффекты-композаблы читают его
 * и перестраиваются мгновенно при включении/выключении энергосбера
 * (ACTION_POWER_SAVE_MODE_CHANGED).
 */
object PowerSaveMonitor {

    var active by mutableStateOf(false)
        private set

    private var registered = false
    private var powerManager: PowerManager? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            active = powerManager?.isPowerSaveMode ?: false
        }
    }

    fun init(context: Context) {
        if (registered) return
        val app = context.applicationContext
        powerManager = app.getSystemService(PowerManager::class.java)
        active = powerManager?.isPowerSaveMode ?: false
        try {
            val filter = IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                app.registerReceiver(receiver, filter)
            }
            registered = true
        } catch (_: Throwable) {
            // На отказ регистрации остаёмся в текущем (считанном) состоянии.
        }
    }
}
