package com.liquidmusicglass.engine.vad

/**
 * Состояние «есть вокал / нет вокала» из VAD-модели лирики.
 * Пишется фоновым воркером [VadLyricsEngine], читается UI-слоем лирики
 * ([com.liquidmusicglass.ui.lyrics.LyricsTimeProcessor]) — по образцу
 * [com.liquidmusicglass.engine.AudioReactor].
 */
object VocalState {

    /** Включён ли VAD сейчас (экран лирики открыт + у трека есть синхро-лирика). */
    @Volatile
    var enabled: Boolean = false

    /**
     * Выдаёт ли модель реальные вердикты прямо сейчас (накопилось окно и идёт
     * инференс). Пока false — UI не доверяет VAD и падает на LRC-эвристику зазоров.
     */
    @Volatile
    var producing: Boolean = false

    /** Сглаженная вероятность вокала 0..1 (скользящее среднее по SMOOTH_FRAMES). */
    @Volatile
    var pVocal: Float = 1f

    /**
     * Идёт ли сейчас вокал по сглаженному порогу.
     * Пока VAD не включён или окно ещё не накопилось — считаем, что вокал есть
     * (не ломаем обычную лирику в отсутствие сигнала модели).
     */
    @Volatile
    var isVocal: Boolean = true

    fun reset() {
        pVocal = 1f
        isVocal = true
        producing = false
    }
}
