package com.liquidmusicglass.api.icm

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Оффлайн-очередь сигналов волны.
 *
 * wave/feedback и wave/playback — главное топливо персонализации; раньше они
 * были fire-and-forget: обрыв 4G терял сигнал навсегда, и волна недополучала
 * данные о вкусах. Теперь недоставленные сигналы копятся здесь (prefs, до
 * 200 шт) и досылаются при восстановлении сети / старте приложения / первой
 * успешной отправке. Постоянные 4xx (битый запрос) не копятся —
 * IcmRepository.DeliveryResult различает RETRY и REJECTED.
 */
object WaveSignalQueue {

    private const val TAG = "WaveSignalQueue"
    private const val MAX_QUEUE = 200

    @Serializable
    private data class Signal(
        val kind: String,              // "feedback" | "playback"
        val a: String,                 // feedbackType | trackId
        val b: String? = null,         // value (только feedback)
        val played: Double = 0.0,
        val total: Double? = null,
        val completed: Boolean? = null,
        val skipped: Boolean? = null
    )

    private val json = Json { ignoreUnknownKeys = true }
    private var prefs: SharedPreferences? = null
    private val lock = Any()

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext
                .getSharedPreferences("wave_signal_queue", Context.MODE_PRIVATE)
        }
    }

    private fun load(): MutableList<Signal> = synchronized(lock) {
        val raw = prefs?.getString("q", null) ?: return mutableListOf()
        runCatching {
            json.decodeFromString(ListSerializer(Signal.serializer()), raw)
        }.getOrDefault(emptyList()).toMutableList()
    }

    private fun save(list: List<Signal>) = synchronized(lock) {
        prefs?.edit()?.putString(
            "q",
            json.encodeToString(ListSerializer(Signal.serializer()), list.takeLast(MAX_QUEUE))
        )?.apply()
    }

    private fun enqueue(s: Signal) {
        val list = load()
        list.add(s)
        save(list)
        android.util.Log.d(TAG, "queued ${s.kind} (${list.size} pending)")
    }

    private suspend fun deliver(s: Signal): IcmRepository.DeliveryResult =
        runCatching {
            when (s.kind) {
                "feedback" -> IcmRepository.deliverWaveFeedback(s.a, s.b ?: "")
                else -> IcmRepository.deliverWavePlayback(
                    s.a, s.played, s.total, s.completed, s.skipped
                )
            }
        }.getOrDefault(IcmRepository.DeliveryResult.RETRY)

    /** Фидбек волны с гарантией доставки: неудача → копим и дошлём. */
    suspend fun sendFeedback(type: String, value: String) {
        send(Signal("feedback", type, value))
    }

    /** Событие прослушивания с гарантией доставки. */
    suspend fun sendPlayback(
        trackId: String,
        playedSeconds: Double,
        totalSeconds: Double?,
        completed: Boolean?,
        skipped: Boolean?
    ) {
        send(Signal("playback", trackId, null, playedSeconds, totalSeconds, completed, skipped))
    }

    private suspend fun send(s: Signal) {
        when (deliver(s)) {
            IcmRepository.DeliveryResult.DELIVERED -> drain()   // сеть жива — дошлём хвост
            IcmRepository.DeliveryResult.RETRY -> enqueue(s)
            IcmRepository.DeliveryResult.REJECTED -> { /* мусор не копим */ }
        }
    }

    /**
     * Дослать накопленное. Стоп на первом RETRY (сеть ещё лежит), REJECTED
     * выбрасывается. Зовётся при старте, восстановлении сети и после успешной
     * свежей отправки.
     */
    suspend fun drain() {
        var list = load()
        if (list.isEmpty()) return
        var sent = 0
        while (list.isNotEmpty()) {
            when (deliver(list.first())) {
                IcmRepository.DeliveryResult.RETRY -> break
                else -> {                       // DELIVERED или REJECTED — из очереди
                    list = list.drop(1).toMutableList()
                    sent++
                }
            }
        }
        if (sent > 0) {
            save(list)
            android.util.Log.d(TAG, "drained $sent signals, ${list.size} left")
        }
    }
}
