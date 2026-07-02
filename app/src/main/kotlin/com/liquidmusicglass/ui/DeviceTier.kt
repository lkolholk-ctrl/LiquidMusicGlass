package com.liquidmusicglass.ui

import android.app.ActivityManager
import android.content.Context

/**
 * Одноразовая классификация устройства для тяжёлых AGSL-эффектов.
 *
 * lite = слабое устройство (системный low-RAM флаг или < ~5.5 ГБ RAM).
 * На lite полноэкранный fbm-дым рисуется с МЕНЬШИМ числом октав (4 вместо 6)
 * и все анимационные AGSL-клоки публикуются на половинной частоте (~30 Гц):
 * для медленного дыма/переливов это визуально неотличимо, а нагрузка на
 * GPU/RenderThread падает примерно вдвое — старые устройства не душат
 * аудио-конвейер в моменты системных анимаций.
 *
 * Это СТАТИЧЕСКАЯ классификация железа (не FPS-деградация): решается один раз
 * на старте, эффекты не «мигают» между качествами.
 */
object DeviceTier {

    @Volatile
    var lite: Boolean = false
        private set

    fun init(context: Context) {
        val am = context.applicationContext
            .getSystemService(ActivityManager::class.java) ?: return
        val mi = ActivityManager.MemoryInfo()
        runCatching { am.getMemoryInfo(mi) }
        val totalGb = mi.totalMem.toDouble() / (1L shl 30)
        lite = am.isLowRamDevice || (mi.totalMem > 0 && totalGb < 5.5)
    }
}
