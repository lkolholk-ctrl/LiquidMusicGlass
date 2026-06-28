package com.liquidmusicglass.engine.automix

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.SimpleBasePlayer
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * Stage 8b — Media3 Player, играющий ЛОКАЛЬНЫЕ треки через JUCE-движок.
 *
 * Оборачивает [AutoMixNativeEngine] в androidx.media3 SimpleBasePlayer, поэтому
 * существующая MediaSession (нотификация / экран блокировки / Bluetooth /
 * Android Auto) работает как с обычным плеером — но звук даёт JUCE, а не
 * ExoPlayer. Это основа полного перехода локального аудио на JUCE: AutoMix
 * (Стадия 8c) затем делает кроссфейд ВНУТРИ движка, без швов.
 *
 * Только локальные источники: JUCE читает файлы с диска, поэтому content://
 * (MediaStore) резолвится в путь файла. Стриминг остаётся на ExoPlayer.
 *
 * ВНИМАНИЕ: этот класс пока НЕ подключён в AudioService — это компайл-этап.
 * Подключение (session.setPlayer при локальной очереди) — следующий шаг 8b.
 */
@UnstableApi
class JuceLocalPlayer(
    private val context: Context,
    looper: Looper
) : SimpleBasePlayer(looper) {

    private val engine = AutoMixNativeEngine
    private val handler = Handler(looper)

    private var playlist: List<MediaItem> = emptyList()
    private var currentIndex = 0
    private var playWhenReadyFlag = false
    private var prepared = false
    private var released = false

    // Периодический пинок состояния, чтобы позиция/прогресс в сессии шли «живо».
    private val ticker = object : Runnable {
        override fun run() {
            if (released) return
            maybeAdvanceAtEnd()
            invalidateState()
            handler.postDelayed(this, 500L)
        }
    }

    init {
        handler.postDelayed(ticker, 500L)
    }

    // ── State ───────────────────────────────────────────────────────────────

    override fun getState(): State {
        val commands = Player.Commands.Builder()
            .add(Player.COMMAND_PLAY_PAUSE)
            .add(Player.COMMAND_PREPARE)
            .add(Player.COMMAND_STOP)
            .add(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
            .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
            .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
            .add(Player.COMMAND_SEEK_TO_NEXT)
            .add(Player.COMMAND_SEEK_TO_PREVIOUS)
            .add(Player.COMMAND_SEEK_TO_MEDIA_ITEM)
            .add(Player.COMMAND_GET_CURRENT_MEDIA_ITEM)
            .add(Player.COMMAND_GET_TIMELINE)
            .add(Player.COMMAND_GET_METADATA)
            .add(Player.COMMAND_SET_MEDIA_ITEM)
            .add(Player.COMMAND_CHANGE_MEDIA_ITEMS)
            .add(Player.COMMAND_RELEASE)
            .build()

        val items = playlist.mapIndexed { i, mi ->
            MediaItemData.Builder(/* uid = */ mi.mediaId.ifEmpty { "juce_$i" })
                .setMediaItem(mi)
                .build()
        }

        val state = if (!prepared || playlist.isEmpty()) Player.STATE_IDLE else Player.STATE_READY
        val posMs = engine.positionMsCurrent().toLong().coerceAtLeast(0L)

        val builder = State.Builder()
            .setAvailableCommands(commands)
            .setPlaybackState(state)
            .setPlayWhenReady(playWhenReadyFlag, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        if (items.isNotEmpty()) {
            builder.setPlaylist(items)
                .setCurrentMediaItemIndex(currentIndex.coerceIn(0, items.size - 1))
                .setContentPositionMs(posMs)
        }
        return builder.build()
    }

    // ── Handlers ──────────────────────────────────────────────────────────────

    override fun handleSetMediaItems(
        mediaItems: MutableList<MediaItem>,
        startIndex: Int,
        startPositionMs: Long
    ): ListenableFuture<*> {
        playlist = mediaItems.toList()
        currentIndex = if (startIndex == C.INDEX_UNSET) 0 else startIndex.coerceIn(0, playlist.lastIndex.coerceAtLeast(0))
        loadCurrent(if (startPositionMs == C.TIME_UNSET) 0L else startPositionMs)
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handlePrepare(): ListenableFuture<*> {
        prepared = true
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        playWhenReadyFlag = playWhenReady
        if (playWhenReady) engine.playCurrent() else engine.pause()
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long
    ): ListenableFuture<*> {
        val target = if (mediaItemIndex == C.INDEX_UNSET) currentIndex else mediaItemIndex
        if (target != currentIndex && target in playlist.indices) {
            currentIndex = target
            loadCurrent(if (positionMs == C.TIME_UNSET) 0L else positionMs)
        } else {
            engine.seekCurrent((if (positionMs == C.TIME_UNSET) 0L else positionMs).toDouble())
        }
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        playWhenReadyFlag = false
        runCatching { engine.stop() }
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        released = true
        handler.removeCallbacksAndMessages(null)
        runCatching { engine.release() }
        return Futures.immediateVoidFuture()
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    /** Загрузить текущий трек в JUCE (текущий дек = A) и при необходимости играть. */
    private fun loadCurrent(positionMs: Long) {
        val mi = playlist.getOrNull(currentIndex) ?: return
        val path = resolveFilePath(mi.localConfiguration?.uri ?: return) ?: return
        engine.init(context)
        runCatching { engine.stop() }          // current deck back to A
        engine.clearDeck(1)                    // incoming empty
        engine.setBassSwap(true)
        engine.loadTrackA(path)
        if (positionMs > 0L) engine.seekCurrent(positionMs.toDouble())
        if (playWhenReadyFlag) engine.playCurrent()
    }

    /** Конец трека (без AutoMix) → следующий; на последнем — стоп. */
    private fun maybeAdvanceAtEnd() {
        if (!prepared || !playWhenReadyFlag || playlist.isEmpty()) return
        val len = engine.lengthMsCurrent().toLong()
        if (len <= 0L) return
        val pos = engine.positionMsCurrent().toLong()
        if (pos >= len - 300L) {
            if (currentIndex < playlist.lastIndex) {
                currentIndex++
                loadCurrent(0L)
            } else {
                playWhenReadyFlag = false
                runCatching { engine.pause() }
            }
            invalidateState()
        }
    }

    /** content:// (MediaStore) → путь файла; file:// → его путь. */
    private fun resolveFilePath(uri: Uri): String? {
        if (uri.scheme == "file") return uri.path
        return try {
            context.contentResolver.query(
                uri, arrayOf(MediaStore.Audio.Media.DATA), null, null, null
            )?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(MediaStore.Audio.Media.DATA)
                    if (idx >= 0) c.getString(idx) else null
                } else null
            }
        } catch (_: Throwable) {
            null
        }
    }
}
