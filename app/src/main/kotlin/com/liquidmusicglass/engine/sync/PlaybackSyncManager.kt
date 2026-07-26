package com.liquidmusicglass.engine.sync

import android.content.Context
import android.os.Build
import com.liquidmusicglass.api.icm.IcmRepository
import com.liquidmusicglass.api.lmg.LmgSyncApi
import com.liquidmusicglass.debug.DebugLog
import com.liquidmusicglass.engine.PlayerController
import com.liquidmusicglass.engine.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Синхронизация воспроизведения через брокер: продолжение на другом устройстве и
 * комнаты совместного прослушивания.
 *
 * Живёт рядом с плеером, а не в UI: состояние надо отправлять и когда экран
 * закрыт, а комнату — вести, пока играет музыка.
 */
object PlaybackSyncManager {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** Как часто сообщать «я здесь, играю вот это». */
    private const val STATE_PUSH_INTERVAL_MS = 20_000L

    /** Как часто хозяин комнаты публикует позицию. */
    private const val ROOM_PUBLISH_INTERVAL_MS = 4_000L

    /** Как часто участник спрашивает состояние комнаты. */
    private const val ROOM_POLL_INTERVAL_MS = 3_000L

    /**
     * Насколько участник может отстать, прежде чем его подтянут перемоткой.
     * Мельче — и перемотка дёргала бы звук на каждой сетевой задержке.
     */
    private const val ROOM_DRIFT_TOLERANCE_MS = 2_500L

    /** Предложение продолжить имеет смысл, пока запись свежая. */
    private const val CONTINUITY_MAX_AGE_MS = 24L * 60 * 60 * 1000

    private var stateJob: Job? = null
    private var roomJob: Job? = null

    private val _room = MutableStateFlow<LmgSyncApi.Room?>(null)

    /** Текущая комната или null. */
    val room: StateFlow<LmgSyncApi.Room?> = _room

    private val _continuity = MutableStateFlow<LmgSyncApi.PlaybackState?>(null)

    /** Состояние с другого устройства, которое можно продолжить. */
    val continuity: StateFlow<LmgSyncApi.PlaybackState?> = _continuity

    private val deviceName: String
        get() = listOf(Build.MANUFACTURER, Build.MODEL)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .ifBlank { "Android" }

    /** Запускает регулярную отправку состояния. Вызывать при старте сервиса. */
    fun start() {
        if (stateJob?.isActive == true) return
        stateJob = scope.launch {
            while (isActive) {
                delay(STATE_PUSH_INTERVAL_MS)
                pushStateNow()
            }
        }
    }

    /** Отправить состояние немедленно (смена трека, пауза, выход из приложения). */
    fun pushStateNow() {
        val track = PlayerController.currentTrack.value ?: return
        scope.launch {
            LmgSyncApi.saveState(
                LmgSyncApi.PlaybackState(
                    trackId = track.id,
                    positionMs = PlayerController.currentPositionMs.value,
                    durationMs = PlayerController.durationMs.value,
                    title = track.title,
                    artist = track.artist,
                    coverUrl = track.coverUrl.orEmpty(),
                    isPlaying = PlayerController.isPlaying.value,
                    deviceName = deviceName
                )
            )
        }
    }

    /**
     * Спрашивает у брокера, есть ли что продолжить с другого устройства.
     * Результат кладётся в [continuity] — UI показывает предложение.
     */
    fun refreshContinuity() {
        scope.launch {
            val (state, sameDevice) = LmgSyncApi.fetchState() ?: run {
                _continuity.value = null
                return@launch
            }
            val age = System.currentTimeMillis() - state.updatedAt
            val worthOffering = !sameDevice &&
                state.trackId.isNotBlank() &&
                age in 0..CONTINUITY_MAX_AGE_MS &&
                // Секунда-другая с начала трека — не то, что стоит «продолжать».
                state.positionMs > 15_000L
            _continuity.value = if (worthOffering) state else null
        }
    }

    /** Продолжить то, что играло на другом устройстве. */
    fun resumeContinuity(context: Context) {
        val state = _continuity.value ?: return
        _continuity.value = null
        scope.launch {
            val track = resolveTrack(state.trackId) ?: run {
                DebugLog.add("Sync.resume: трек ${state.trackId} не найден")
                return@launch
            }
            PlayerController.play(context, listOf(track), 0)
            // Плееру нужно поднять источник, прежде чем перемотка что-то значит.
            delay(1_200L)
            PlayerController.seekTo(state.positionMs)
        }
    }

    fun dismissContinuity() {
        _continuity.value = null
    }

    // ── Комнаты ──────────────────────────────────────────────────────────────

    fun createRoom(onResult: (LmgSyncApi.Room?) -> Unit = {}) {
        scope.launch {
            val created = LmgSyncApi.createRoom(deviceName)
            _room.value = created
            if (created != null) startRoomLoop(created.code, isHost = true, context = null)
            onResult(created)
        }
    }

    fun joinRoom(context: Context, code: String, onResult: (LmgSyncApi.Room?) -> Unit = {}) {
        scope.launch {
            val joined = LmgSyncApi.joinRoom(code, deviceName)
            _room.value = joined
            if (joined != null) {
                startRoomLoop(joined.code, isHost = LmgSyncApi.isHost(joined), context = context)
            }
            onResult(joined)
        }
    }

    fun leaveRoom() {
        val code = _room.value?.code
        roomJob?.cancel()
        roomJob = null
        _room.value = null
        if (code != null) scope.launch { LmgSyncApi.leaveRoom(code) }
    }

    private fun startRoomLoop(code: String, isHost: Boolean, context: Context?) {
        roomJob?.cancel()
        roomJob = scope.launch {
            while (isActive) {
                if (isHost) {
                    publishRoomState(code)
                    delay(ROOM_PUBLISH_INTERVAL_MS)
                } else {
                    followRoom(code, context)
                    delay(ROOM_POLL_INTERVAL_MS)
                }
            }
        }
    }

    private suspend fun publishRoomState(code: String) {
        val track = PlayerController.currentTrack.value ?: return
        val updated = LmgSyncApi.publishRoomState(
            code,
            LmgSyncApi.PlaybackState(
                trackId = track.id,
                positionMs = PlayerController.currentPositionMs.value,
                durationMs = PlayerController.durationMs.value,
                title = track.title,
                artist = track.artist,
                coverUrl = track.coverUrl.orEmpty(),
                isPlaying = PlayerController.isPlaying.value
            )
        )
        if (updated != null) _room.value = updated
        else {
            // Комнату закрыли или сервер недоступен — не ведём её вслепую.
            DebugLog.add("Sync.room: публикация не прошла, выходим")
            leaveRoom()
        }
    }

    private suspend fun followRoom(code: String, context: Context?) {
        val fresh = LmgSyncApi.fetchRoom(code) ?: run {
            DebugLog.add("Sync.room: комната $code закрыта")
            leaveRoom()
            return
        }
        _room.value = fresh
        val state = fresh.state ?: return
        val ctx = context ?: return

        // Позиция хозяина на момент ответа плюс то, что он проиграл, пока ответ
        // шёл к нам: без этой поправки участник систематически отстаёт.
        val elapsedSincePublish = (fresh.serverTimeMs - state.updatedAt).coerceAtLeast(0L)
        val targetPositionMs =
            if (state.isPlaying) state.positionMs + elapsedSincePublish else state.positionMs

        val current = PlayerController.currentTrack.value
        if (current?.id != state.trackId) {
            val track = resolveTrack(state.trackId) ?: return
            PlayerController.play(ctx, listOf(track), 0)
            delay(1_200L)
            PlayerController.seekTo(targetPositionMs)
            return
        }

        val drift = kotlin.math.abs(PlayerController.currentPositionMs.value - targetPositionMs)
        if (drift > ROOM_DRIFT_TOLERANCE_MS) {
            DebugLog.add("Sync.room: подтягиваем на ${drift}мс")
            PlayerController.seekTo(targetPositionMs)
        }
    }

    private suspend fun resolveTrack(trackId: String): Track? {
        PlayerController.getCurrentQueue().firstOrNull { it.id == trackId }?.let { return it }
        val meta = runCatching { IcmRepository.getTrackMeta(trackId) }.getOrNull() ?: return null
        if (meta.id.isBlank()) return null
        return Track(
            id = meta.id,
            title = meta.title,
            artist = meta.artist.ifBlank { "Unknown Artist" },
            albumName = "",
            uri = android.net.Uri.parse("https://byicloud.online/track/${meta.id}"),
            durationMs = meta.durationMs,
            albumId = meta.collectionId?.hashCode()?.toLong() ?: meta.id.hashCode().toLong(),
            coverUrl = meta.cover.takeIf { it.isNotBlank() }
        )
    }
}
