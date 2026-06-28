package com.liquidmusicglass.engine.automix

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.provider.MediaStore
import java.util.concurrent.atomic.AtomicInteger
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
 * Тяжёлая загрузка (JUCE init + полный декод mp3/aac) выполняется на выделенном
 * фоновом потоке; главный поток только читает лёгкие atomics в getState(), так
 * что UI/нотификация не замирают. Все мутирующие @Synchronized-вызовы движка
 * тоже идут на фоновом потоке.
 */
@UnstableApi
class JuceLocalPlayer(
    private val context: Context,
    looper: Looper
) : SimpleBasePlayer(looper) {

    private val engine = AutoMixNativeEngine
    private val handler = Handler(looper)

    // Тяжёлая загрузка трека (JUCE init + полный декод mp3/aac) НИКОГДА не должна
    // идти на главном потоке — иначе UI замирает на секунды (ANR/«колом»). Грузим
    // на выделенном фоновом потоке; по готовности дёргаем invalidateState на main.
    private val loadThread = HandlerThread("juce-local-load").apply { start() }
    private val loadHandler = Handler(loadThread.looper)
    private val loadSeq = AtomicInteger(0)   // защита от гонок при быстром переключении
    @Volatile private var loading = false

    private var playlist: List<MediaItem> = emptyList()
    private var currentIndex = 0
    @Volatile private var playWhenReadyFlag = false   // пишется на main, читается фоном
    private var prepared = false
    @Volatile private var released = false

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
            val b = MediaItemData.Builder(/* uid = */ mi.mediaId.ifEmpty { "juce_$i" })
                .setMediaItem(mi)
            // ВАЖНО: длительность берём из СТАБИЛЬНЫХ метаданных трека (MediaStore),
            // а НЕ из движка. Меняющийся durationUs между вызовами getState() Media3
            // трактует как разрыв таймлайна; если длина на миг просядет ниже позиции
            // (например, read-ahead голодает при тяжёлой перерисовке UI), плеер ложно
            // «заканчивает» трек → стоп + перемотка в начало. Метаданные не плавают.
            val durMs = mi.mediaMetadata.durationMs
            if (durMs != null && durMs > 0L) b.setDurationUs(durMs * 1000L)
            b.build()
        }

        val state = when {
            !prepared || playlist.isEmpty() -> Player.STATE_IDLE
            loading -> Player.STATE_BUFFERING   // фоновый декод идёт — UI крутит спиннер
            else -> Player.STATE_READY
        }
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
        android.util.Log.d("JUCELocalDbg", "handleSetMediaItems n=${mediaItems.size} start=$startIndex pos=$startPositionMs", Throwable("caller"))
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
        android.util.Log.d("JUCELocalDbg", "handleSetPlayWhenReady=$playWhenReady", Throwable("caller"))
        playWhenReadyFlag = playWhenReady
        // Движок дёргаем на фоновом потоке (вызовы @Synchronized могут встать за
        // идущим декодом — нельзя блокировать main). Если идёт загрузка, она сама
        // стартует по флагу playWhenReadyFlag по завершении.
        loadHandler.post {
            if (released) return@post
            if (playWhenReady) engine.playCurrent() else engine.pause()
        }
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: Int
    ): ListenableFuture<*> {
        val target = if (mediaItemIndex == C.INDEX_UNSET) currentIndex else mediaItemIndex
        if (target != currentIndex && target in playlist.indices) {
            currentIndex = target
            loadCurrent(if (positionMs == C.TIME_UNSET) 0L else positionMs)
        } else {
            val to = (if (positionMs == C.TIME_UNSET) 0L else positionMs).toDouble()
            loadHandler.post { if (!released && !loading) engine.seekCurrent(to) }
        }
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        android.util.Log.d("JUCELocalDbg", "handleStop", Throwable("caller"))
        playWhenReadyFlag = false
        loadHandler.post { if (!released) runCatching { engine.stop() } }
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        released = true
        handler.removeCallbacksAndMessages(null)
        loadHandler.removeCallbacksAndMessages(null)
        // release() движка тоже @Synchronized — на фоне, чтобы не ждать монитор на
        // main. quitSafely даёт этой задаче выполниться перед остановкой потока.
        loadHandler.post { runCatching { engine.release() } }
        loadThread.quitSafely()
        return Futures.immediateVoidFuture()
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    /**
     * Загрузить текущий трек в JUCE (текущий дек = A) и при необходимости играть.
     *
     * Тяжёлая часть (JUCE init + полный декод mp3/aac) идёт на фоновом потоке —
     * главный поток НЕ блокируется. Пока грузится — состояние BUFFERING. По
     * готовности применяем play/invalidateState на main, но только если это всё
     * ещё актуальная загрузка (пользователь не переключил трек) — loadSeq guard.
     */
    private fun loadCurrent(positionMs: Long) {
        val mi = playlist.getOrNull(currentIndex) ?: return
        val uri = mi.localConfiguration?.uri ?: return

        android.util.Log.d("JUCELocalDbg", "loadCurrent idx=$currentIndex pos=$positionMs pwr=$playWhenReadyFlag")
        val seq = loadSeq.incrementAndGet()
        loading = true
        invalidateState()                      // сразу показать BUFFERING (на main)

        loadHandler.post {
            val path = resolveFilePath(uri)
            var ok = false
            if (path != null) {
                runCatching {
                    engine.init(context)
                    engine.stop()              // current deck back to A
                    engine.clearDeck(1)        // incoming empty
                    engine.setBassSwap(true)
                    ok = engine.loadTrackA(path)
                    if (ok && positionMs > 0L) engine.seekCurrent(positionMs.toDouble())
                    // Старт здесь же, на фоне — main не дёргает @Synchronized движок.
                    if (ok && playWhenReadyFlag && seq == loadSeq.get()) engine.playCurrent()
                }
            }
            // Состояние применяем на main — только если загрузка ещё актуальна.
            handler.post {
                if (released || seq != loadSeq.get()) return@post
                loading = false
                invalidateState()
            }
        }
    }

    /** Конец трека (без AutoMix) → следующий; на последнем — стоп. */
    private fun maybeAdvanceAtEnd() {
        if (loading || !prepared || !playWhenReadyFlag || playlist.isEmpty()) return
        val len = engine.lengthMsCurrent().toLong()
        if (len <= 1000L) return                 // длина ещё не известна/мусор — не дёргаем
        val pos = engine.positionMsCurrent().toLong()
        if (pos > 1000L && pos >= len - 300L) {   // требуем реально доигранный трек
            if (currentIndex < playlist.lastIndex) {
                currentIndex++
                loadCurrent(0L)
            } else {
                playWhenReadyFlag = false
                loadHandler.post { if (!released) runCatching { engine.pause() } }
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
