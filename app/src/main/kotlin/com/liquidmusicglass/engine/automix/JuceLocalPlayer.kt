package com.liquidmusicglass.engine.automix

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.OpenableColumns
import com.liquidmusicglass.engine.PlayerController
import com.liquidmusicglass.engine.PlayerSettings
import com.liquidmusicglass.automix.AutoMixController
import com.liquidmusicglass.debug.DebugLog
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
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

    // getState() зовётся каждые ~500мс (тикер) и на каждую инвалидацию. Маппинг
    // ВСЕГО плейлиста в MediaItemData на каждый вызов — лишний CPU/мусор на main
    // при длинной очереди волны (GC-паузы косвенно бьют по аудио на слабых
    // устройствах). Кэшируем по identity списка: плейлист всегда заменяется
    // новым инстансом при мутации, поэтому сравнение по ссылке корректно.
    private var cachedItemsSource: List<MediaItem>? = null
    private var cachedItems: List<MediaItemData> = emptyList()
    @Volatile private var playWhenReadyFlag = false   // пишется на main, читается фоном
    private var prepared = false
    @Volatile private var ended = false
    @Volatile private var released = false

    // ── Stage 8c (ШАГ 1: ТОЛЬКО НАБЛЮДЕНИЕ) ──────────────────────────────────
    // Когда трек подходит к концу и включён тумблер AutoMix — в фоне анализируем
    // пару (текущий+следующий) моделью и ЛОГИРУЕМ предсказание. Звук НЕ трогаем:
    // переход трека идёт как обычно. Это безопасная проверка, что v4 даёт
    // вменяемые параметры свода, прежде чем реально исполнять кроссфейд (шаг 2).
    private val autoMixScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val autoMixController by lazy { AutoMixController(context.applicationContext) }
    @Volatile private var autoMixAnalyzedIndex = -1
    private val AUTOMIX_LEAD_MS = 40_000L   // окно до конца трека для анализа (хватает на свод ≤30с)
    private val MIN_AUTOMIX_XFADE_MS = 5_000L
    private val MAX_AUTOMIX_XFADE_MS = 30_000L
    private val TICK_MS = 100L              // точка свода должна ловиться плотнее, чем UI polling
    private val STATE_TICK_MS = 500L        // MediaSession state не надо инвалидировать 10 раз/с
    private var lastStateInvalidateMs = 0L

    // Телеметрия underrun'ов Oboe: раз в XRUN_LOG_MS сверяем счётчик xrun и
    // пишем прирост в DebugLog — тюнить размер буфера по реальным данным.
    private val XRUN_LOG_MS = 5_000L
    private var lastXRunCheckMs = 0L
    private var lastXRuns = -1

    // Адаптация буфера: если за окно проверки прилетело ≥ XRUN_ESCALATE_DELTA
    // underrun'ов — просим движок расширить буфер (6×→8×burst). Не чаще
    // MAX_BUFFER_ESCALATIONS раз за сессию плеера; сам вызов — на loadHandler
    // (внутри реопен устройства, main это делать нельзя).
    private val XRUN_ESCALATE_DELTA = 3
    private val MAX_BUFFER_ESCALATIONS = 1
    private var bufferEscalations = 0

    // Синхронизация буфера движка с Battery Saver: тикер замечает смену режима
    // и передаёт движку (реопен на loadHandler — не на main).
    private var lastPowerSave: Boolean? = null

    // ── Stage 8c (ШАГ 2: РЕАЛЬНЫЙ СВОД) ──────────────────────────────────────
    // Когда модель сказала ready и подобрала параметры — у точки свода реально
    // запускаем equal-power кроссфейд current→incoming в движке (пинг-понг деков).
    // Точку запуска якорим к КОНЦУ трека (remaining ≤ xfade): голова start у v4
    // выдаёт раннюю долю и ненадёжна, а xfade/entry/compat — осмысленные.
    // По завершении свода incoming становится current → двигаем currentIndex.
    // Всё под тумблером AutoMix; при фейле — обычное переключение (фолбэк).
    private data class AutoMixPlan(
        val fromIndex: Int,
        val xfadeMs: Long,
        val entryMs: Long,
        val type: Int,
        val nextUri: Uri
    )
    @Volatile private var autoMixPlan: AutoMixPlan? = null
    // 0 = простаиваем, 1 = грузим incoming, 2 = свод идёт
    @Volatile private var transitionState = 0
    @Volatile private var transitionFromIndex = -1

    // Периодический пинок состояния, чтобы позиция/прогресс в сессии шли «живо».
    private val ticker = object : Runnable {
        override fun run() {
            if (released) return
            maybeAnalyzeForAutoMix()       // шаг 1: анализ пары моделью + план свода
            maybeRunAutoMixTransition()    // шаг 2: реальный кроссфейд по плану модели
            maybeAdvanceAtEnd()            // обычное переключение (если свода нет)
            maybeLogXRuns()                // телеметрия underrun'ов Oboe
            maybeSyncPowerSave()           // буфер движка ↔ Battery Saver
            // Watchdog выхода: «должны играть, а прогресса нет» → авто-уход на
            // AudioTrack. Кормим только когда звук ОБЯЗАН идти прямо сейчас.
            AudioOutputWatchdog.noteTick(
                playingAudibly = prepared && !loading && playWhenReadyFlag &&
                    !ended && playlist.isNotEmpty() && transitionState != 1,
                positionMs = engine.positionMsCurrent().toLong()
            )
            invalidateStateFromTicker()
            handler.postDelayed(this, TICK_MS)
        }
    }

    init {
        handler.postDelayed(ticker, TICK_MS)
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

        val currentPlaylist = playlist
        val items = if (currentPlaylist === cachedItemsSource) cachedItems else {
            currentPlaylist.mapIndexed { i, mi ->
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
            }.also {
                cachedItemsSource = currentPlaylist
                cachedItems = it
            }
        }

        val state = when {
            ended -> Player.STATE_ENDED
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
        val incomingIds = mediaItems.map { it.mediaId }
        val currentIds = playlist.map { it.mediaId }
        val sameQueue = incomingIds.isNotEmpty() && incomingIds == currentIds
        val targetIndex = if (startIndex == C.INDEX_UNSET) currentIndex
            else startIndex.coerceIn(0, mediaItems.lastIndex.coerceAtLeast(0))
        val noStartPos = startPositionMs == C.TIME_UNSET || startPositionMs <= 0L

        DebugLog.add("JUCE.setItems n=${mediaItems.size} start=$startIndex pos=$startPositionMs same=$sameQueue tgt=$targetIndex cur=$currentIndex | ${DebugLog.caller()}")

        // ЗАЩИТА: повторная установка ТОЙ ЖЕ очереди на ТОТ ЖЕ индекс без явной
        // позиции (например, ресинк сессии при переключении вкладок) НЕ должна
        // перезагружать трек — иначе звук обрывается и ползунок прыгает в 0.
        // Просто обновляем ссылку на плейлист и выходим, не трогая движок.
        if (sameQueue && targetIndex == currentIndex && noStartPos) {
            DebugLog.add("JUCE.setItems SKIPPED (same queue+index, no reload)")
            playlist = mediaItems.toList()
            invalidateState()
            return Futures.immediateVoidFuture()
        }

        playlist = mediaItems.toList()
        currentIndex = targetIndex
        ended = false
        loadCurrent(if (startPositionMs == C.TIME_UNSET) 0L else startPositionMs)
        notifyCurrentTrackChanged()
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    // COMMAND_CHANGE_MEDIA_ITEMS объявлен в доступных командах И используется
    // нашим же кодом (clearMediaItems/addMediaItems/replaceMediaItem в
    // AudioService/PlayerController), поэтому ОБЯЗАНЫ реализовать все мутаторы
    // очереди. Иначе SimpleBasePlayer по умолчанию бросает
    // IllegalStateException «Missing implementation to handle
    // COMMAND_CHANGE_MEDIA_ITEMS» прямо на main → ANR (ловили на HONOR при
    // clearMediaItems из легаси-контроллера сессии).
    //
    // Принцип: держим playlist/currentIndex согласованными и дёргаем движок
    // ТОЛЬКО если поменялся реально играющий трек. Добавление/перемещение/
    // замена не-текущего элемента звук НЕ трогают.

    override fun handleAddMediaItems(
        index: Int,
        mediaItems: MutableList<MediaItem>
    ): ListenableFuture<*> {
        val list = playlist.toMutableList()
        val at = index.coerceIn(0, list.size)
        list.addAll(at, mediaItems)
        playlist = list
        if (at <= currentIndex) currentIndex += mediaItems.size  // текущий сдвинулся правее
        ended = false
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleRemoveMediaItems(
        fromIndex: Int,
        toIndex: Int
    ): ListenableFuture<*> {
        val list = playlist.toMutableList()
        val from = fromIndex.coerceIn(0, list.size)
        val to = toIndex.coerceIn(from, list.size)
        if (from == to) { invalidateState(); return Futures.immediateVoidFuture() }
        val removedCurrent = currentIndex in from until to
        repeat(to - from) { if (from < list.size) list.removeAt(from) }
        playlist = list

        when {
            list.isEmpty() -> {
                currentIndex = 0
                playWhenReadyFlag = false
                ended = false
                engine.silenceOutput()
                loadHandler.post { if (!released) runCatching { engine.stop() } }
            }
            removedCurrent -> {
                currentIndex = from.coerceIn(0, list.lastIndex)
                ended = false
                loadCurrent(0L)                 // в эту позицию «въехал» следующий трек
                notifyCurrentTrackChanged()
            }
            currentIndex >= to -> currentIndex -= (to - from)  // удаляли только до текущего
        }
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleMoveMediaItems(
        fromIndex: Int,
        toIndex: Int,
        newIndex: Int
    ): ListenableFuture<*> {
        val list = playlist.toMutableList()
        val from = fromIndex.coerceIn(0, list.size)
        val to = toIndex.coerceIn(from, list.size)
        if (from == to) { invalidateState(); return Futures.immediateVoidFuture() }
        val curItem = playlist.getOrNull(currentIndex)   // следим за играющим по ссылке
        val moved = ArrayList(list.subList(from, to))
        repeat(to - from) { list.removeAt(from) }
        val insertAt = newIndex.coerceIn(0, list.size)
        list.addAll(insertAt, moved)
        playlist = list
        // Перемещение не меняет играющий трек — только его индекс.
        val newCur = list.indexOfFirst { it === curItem }
        if (newCur >= 0) currentIndex = newCur
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleReplaceMediaItems(
        fromIndex: Int,
        toIndex: Int,
        mediaItems: MutableList<MediaItem>
    ): ListenableFuture<*> {
        val list = playlist.toMutableList()
        val from = fromIndex.coerceIn(0, list.size)
        val to = toIndex.coerceIn(from, list.size)
        val replacedCurrent = currentIndex in from until to
        val oldCurUri = playlist.getOrNull(currentIndex)?.localConfiguration?.uri
        repeat(to - from) { if (from < list.size) list.removeAt(from) }
        list.addAll(from, mediaItems)
        playlist = list

        if (replacedCurrent) {
            currentIndex = from.coerceIn(0, list.lastIndex.coerceAtLeast(0))
            val newCurUri = list.getOrNull(currentIndex)?.localConfiguration?.uri
            // Если играющий трек заменили на тот же URI (например, обновление
            // метаданных) — НЕ перезагружаем, чтобы не рвать звук.
            if (list.isNotEmpty() && newCurUri != oldCurUri) {
                ended = false
                loadCurrent(0L)
                notifyCurrentTrackChanged()
            }
        } else if (currentIndex >= to) {
            currentIndex += mediaItems.size - (to - from)
        }
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handlePrepare(): ListenableFuture<*> {
        prepared = true
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        DebugLog.add("JUCE.setPWR=$playWhenReady | ${DebugLog.caller()}")
        if (playWhenReady) ended = false
        playWhenReadyFlag = playWhenReady
        if (!playWhenReady) {
            transitionState = 0
            transitionFromIndex = -1
            autoMixPlan = null
            engine.silenceOutput()
        }
        // Движок дёргаем на фоновом потоке (вызовы @Synchronized могут встать за
        // идущим декодом — нельзя блокировать main). Если идёт загрузка, она сама
        // стартует по флагу playWhenReadyFlag по завершении.
        loadHandler.post {
            if (released) return@post
            if (playWhenReady) {
                if (playWhenReadyFlag) engine.playCurrent()
            } else {
                if (!playWhenReadyFlag) engine.pause()
            }
        }
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: Int
    ): ListenableFuture<*> {
        DebugLog.add("JUCE.handleSeek idx=$mediaItemIndex pos=$positionMs cur=$currentIndex cmd=$seekCommand | ${DebugLog.caller()}")
        val target = if (mediaItemIndex == C.INDEX_UNSET) currentIndex else mediaItemIndex
        if (target != currentIndex && target in playlist.indices) {
            currentIndex = target
            ended = false
            loadCurrent(if (positionMs == C.TIME_UNSET) 0L else positionMs)
            notifyCurrentTrackChanged()
        } else {
            val to = (if (positionMs == C.TIME_UNSET) 0L else positionMs).toDouble()
            // Не выбрасываем seek во время загрузки: loadHandler последователен,
            // поэтому пост встанет В ОЧЕРЕДЬ за идущей загрузкой и применится к
            // только что загруженному треку. Guard по loadSeq отменяет seek, если
            // к моменту исполнения стартовала более новая загрузка (другой трек).
            val seq = loadSeq.get()
            loadHandler.post { if (!released && seq == loadSeq.get()) engine.seekCurrent(to) }
        }
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        DebugLog.add("JUCE.handleStop | ${DebugLog.caller()}")
        playWhenReadyFlag = false
        ended = false
        transitionState = 0
        transitionFromIndex = -1
        autoMixPlan = null
        loadSeq.incrementAndGet()
        engine.silenceOutput()
        loadHandler.post { if (!released) runCatching { engine.stop() } }
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        released = true
        loadSeq.incrementAndGet()
        engine.silenceOutput()
        runCatching { autoMixScope.cancel() }
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

        DebugLog.add("JUCE.loadCurrent idx=$currentIndex pos=$positionMs pwr=$playWhenReadyFlag")
        // Прямая загрузка трека (скип/сик/новая очередь) ОТМЕНЯЕТ любой идущий
        // модельный свод: дальше всё на деке A, движок ниже делает engine.stop().
        transitionState = 0
        transitionFromIndex = -1
        autoMixPlan = null
        engine.silenceOutput()
        val seq = loadSeq.incrementAndGet()
        loading = true
        ended = false
        autoMixAnalyzedIndex = -1              // новый трек → заново разрешить AutoMix-анализ
        invalidateState()                      // сразу показать BUFFERING (на main)

        loadHandler.post {
            var ok = false
            runCatching {
                if (released || seq != loadSeq.get()) return@runCatching
                engine.init(context)
                if (released || seq != loadSeq.get()) return@runCatching
                engine.stop()              // current deck back to A
                engine.clearDeck(1)        // incoming empty
                engine.setBassSwap(true)
                ok = loadUriIntoDeckA(uri)
                if (released || seq != loadSeq.get()) return@runCatching
                if (ok && positionMs > 0L) engine.seekCurrent(positionMs.toDouble())
                // Старт здесь же, на фоне — main не дёргает @Synchronized движок.
                if (ok && !released && playWhenReadyFlag && seq == loadSeq.get()) engine.playCurrent()
            }
            // ДИАГНОСТИКА локального аудио: показываем результат загрузки и что
            // движок видит по длине/позиции (сразу видно: не загрузился трек,
            // ложная длина ~3с, или движок вообще не поднялся).
            DebugLog.add(
                "JUCE.loadDone ok=$ok loaded=${AutoMixNativeEngine.isLoaded} " +
                    "len=${engine.lengthMsCurrent().toLong()} pos=${engine.positionMsCurrent().toLong()} pwr=$playWhenReadyFlag"
            )
            // Состояние применяем на main — только если загрузка ещё актуальна.
            handler.post {
                if (released || seq != loadSeq.get()) return@post
                loading = false
                if (!ok) {
                    playWhenReadyFlag = false
                    PlayerController.onPlaybackError("JUCE_LOAD_FAILED")
                    DebugLog.add("JUCE.loadFAILED uri=$uri")
                }
                invalidateState()
            }
        }
    }

    /**
     * Загрузить URI в дек A. content:// → сначала по файловому дескриптору (БЕЗ
     * копирования в кэш), при неудаче — фолбэк на путь/копию. file:// → по пути
     * (JUCE-ридеры wav/flac лучше идут по пути). Вызывается на loadHandler (фон).
     */
    private fun loadUriIntoDeckA(uri: Uri): Boolean {
        if (uri.scheme == "file") {
            val p = uri.path ?: return false
            return engine.loadTrackA(p)
        }
        if (uri.scheme == "content") {
            // 1) ПРЯМОЙ путь из MediaStore.DATA — самый быстрый (::open файла
            //    напрямую). Чтение через дескриптор content-провайдера медленнее
            //    → декод отстаёт → цикличные заедания. Поэтому путь приоритетнее FD.
            legacyDataPath(uri)?.takeIf { File(it).exists() }?.let { p ->
                if (engine.loadTrackA(p)) return true
            }
            // 2) FD без копии — для scoped-storage файлов БЕЗ прямого пути.
            val viaFd = runCatching {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    engine.loadTrackAFd(pfd.fd, 0L, pfd.statSize)  // statSize=-1 → fstat в нативке
                } ?: false
            }.getOrDefault(false)
            if (viaFd) return true
            // 3) Последний фолбэк — копия в кэш.
            DebugLog.add("JUCE.path+fd failed → fallback copy uri=$uri")
            val path = copyContentUriToCache(uri) ?: return false
            return engine.loadTrackA(path)
        }
        // Прочие схемы — через общий резолвер.
        val path = resolveFilePath(uri) ?: return false
        return engine.loadTrackA(path)
    }

    /** Конец трека (без AutoMix) → следующий; на последнем — стоп. */
    private fun maybeAdvanceAtEnd() {
        if (transitionState != 0) return   // идёт модельный свод — он сам двигает трек
        if (loading || !prepared || !playWhenReadyFlag || playlist.isEmpty()) return
        val len = engine.lengthMsCurrent().toLong()
        if (len <= 1000L) return                 // длина ещё не известна/мусор — не дёргаем
        val pos = engine.positionMsCurrent().toLong()
        if (pos > 1000L && pos >= len - 300L) {   // требуем реально доигранный трек
            // ДИАГНОСТИКА: если это срабатывает на ~3с — значит движок отдаёт
            // ложно-короткую длину, и трек «доигрывает» и перезагружается по кругу.
            DebugLog.add("JUCE.advanceAtEnd pos=$pos len=$len idx=$currentIndex")
            if (currentIndex < playlist.lastIndex) {
                currentIndex++
                ended = false
                loadCurrent(0L)
                notifyCurrentTrackChanged()
            } else {
                playWhenReadyFlag = false
                ended = true
                engine.silenceOutput()
                loadHandler.post { if (!released) runCatching { engine.pause() } }
                PlayerController.onTrackEnded()
            }
            invalidateState()
        }
    }

    private fun notifyCurrentTrackChanged() {
        playlist.getOrNull(currentIndex)?.mediaId?.takeIf { it.isNotBlank() }?.let {
            PlayerController.onTrackChanged(it)
        }
    }

    /** Раз в XRUN_LOG_MS пишем прирост underrun'ов Oboe в DebugLog. Вызов не
     *  блокирует (try_lock в нативке). Счётчик сбрасывается при переоткрытии
     *  потока (смена маршрута) — тогда просто перезахватываем базу. */
    private fun maybeLogXRuns() {
        val now = SystemClock.uptimeMillis()
        if (now - lastXRunCheckMs < XRUN_LOG_MS) return
        lastXRunCheckMs = now
        if (!playWhenReadyFlag) return
        val x = engine.xRunCount()
        if (x >= 0) {
            val delta = if (lastXRuns in 0 until x) x - lastXRuns else 0
            if (delta > 0) DebugLog.add("JUCE.xruns +$delta (total=$x)")
            lastXRuns = x

            // Стабильные underrun'ы под системной нагрузкой → адаптивно расширяем
            // буфер. Счётчик xrun сбросится при реопене — база перезахватится.
            if (delta >= XRUN_ESCALATE_DELTA && bufferEscalations < MAX_BUFFER_ESCALATIONS) {
                bufferEscalations++
                DebugLog.add("JUCE.xruns escalate buffer (#$bufferEscalations)")
                loadHandler.post { if (!released) engine.escalateBufferForUnderruns() }
                lastXRuns = -1
            }
        }
    }

    /** Смена режима энергосбережения → движку максимальный буфер (или обратно). */
    private fun maybeSyncPowerSave() {
        val ps = com.liquidmusicglass.ui.PowerSaveMonitor.active
        if (ps == lastPowerSave) return
        lastPowerSave = ps
        loadHandler.post { if (!released) engine.setPowerSaveMode(ps) }
    }

    private fun invalidateStateFromTicker() {
        val now = SystemClock.uptimeMillis()
        if (now - lastStateInvalidateMs < STATE_TICK_MS && !loading && transitionState == 0) return
        lastStateInvalidateMs = now
        invalidateState()
    }

    /**
     * Stage 8c ШАГ 1 — ТОЛЬКО НАБЛЮДЕНИЕ. У точки свода (за AUTOMIX_LEAD_MS до
     * конца) разово анализируем пару моделью в фоне и пишем предсказание в лог.
     * Звук НЕ трогаем. Гейт — тумблер AutoMix. Никогда не роняет воспроизведение.
     */
    private fun maybeAnalyzeForAutoMix() {
        if (!PlayerSettings.autoMix.value) return
        if (loading || !prepared || !playWhenReadyFlag || playlist.isEmpty()) return
        val idx = currentIndex
        if (idx >= playlist.lastIndex) return            // нет следующего трека
        if (autoMixAnalyzedIndex == idx) return           // уже анализировали этот трек
        val len = engine.lengthMsCurrent().toLong()
        if (len <= 1000L) return                          // длина ещё не известна
        val remaining = len - engine.positionMsCurrent().toLong()
        if (remaining > AUTOMIX_LEAD_MS || remaining < 1500L) return  // ещё рано / уже поздно

        val curUri = playlist.getOrNull(idx)?.localConfiguration?.uri ?: return
        val nextUri = playlist.getOrNull(idx + 1)?.localConfiguration?.uri ?: return
        autoMixAnalyzedIndex = idx
        DebugLog.add("AutoMix.analyze START idx=$idx rem=${remaining}ms")

        autoMixScope.launch {
            runCatching {
                val f = autoMixController.analyzeTrackPair(curUri, nextUri, len)
                DebugLog.add(
                    "AutoMix.PRED ready=${f.readyForTransition} " +
                        "compat=${"%.2f".format(f.compatibility)} " +
                        "xfade=${f.crossfadeDurationMs}ms start=${f.transitionStartMs}ms " +
                        "entry=${f.entryOffsetMs}ms type=${f.transitionType} bpm=${f.bpmA}->${f.bpmB}\n" +
                        f.debugInfo
                )
                // Шаг 2: если модель готова свести — кладём план. Точку запуска
                // НЕ берём из модельного start (он ранний/ненадёжен), якорим к
                // концу трека по xfade. xfade держим в ML-окне [5с, 30с].
                if (PlayerSettings.autoMix.value && f.readyForTransition && idx == currentIndex) {
                    val xfade = f.crossfadeDurationMs.coerceIn(MIN_AUTOMIX_XFADE_MS, MAX_AUTOMIX_XFADE_MS)
                    val entry = f.entryOffsetMs.coerceIn(0L, 10_000L)
                    autoMixPlan = AutoMixPlan(idx, xfade, entry, f.transitionType, nextUri)
                    DebugLog.add("AutoMix.plan idx=$idx xfade=${xfade}ms entry=${entry}ms type=${f.transitionType}")
                }
            }.onFailure { DebugLog.add("AutoMix.analyze FAILED ${it.message}") }
        }
    }

    /**
     * Stage 8c ШАГ 2 — РЕАЛЬНЫЙ СВОД. Вызывается из тикера (каждые 500мс).
     * Если есть план модели и трек подошёл к точке свода (remaining ≤ xfade) —
     * грузим следующий трек в incoming-дек и запускаем equal-power кроссфейд в
     * движке. По завершении свода incoming становится current → двигаем индекс.
     */
    private fun maybeRunAutoMixTransition() {
        // Свод уже идёт — ждём завершения и финализируем.
        if (transitionState == 2) {
            if (!engine.isCrossfadeActive()) {
                val newIdx = (transitionFromIndex + 1).coerceIn(0, playlist.lastIndex.coerceAtLeast(0))
                currentIndex = newIdx
                transitionState = 0
                transitionFromIndex = -1
                ended = false
                // autoMixAnalyzedIndex остаётся равным предыдущему (fromIndex) —
                // НЕ блокируем анализ нового текущего трека: у его конца снова
                // сработает анализ и следующий свод (пинг-понг продолжается).
                notifyCurrentTrackChanged()
                invalidateState()
                DebugLog.add("AutoMix.xfade DONE → idx=$newIdx deck=${engine.currentDeckIndex()}")
            }
            return
        }
        if (transitionState == 1) return   // incoming грузится — ждём

        if (!PlayerSettings.autoMix.value) { autoMixPlan = null; return }
        val plan = autoMixPlan ?: return
        if (plan.fromIndex != currentIndex) { autoMixPlan = null; return }
        if (loading || !prepared || !playWhenReadyFlag) return
        val len = engine.lengthMsCurrent().toLong()
        if (len <= 1000L) return
        val remaining = len - engine.positionMsCurrent().toLong()
        if (remaining > plan.xfadeMs + 250L) return   // ещё рано (якорим к концу)
        if (remaining < 800L) { autoMixPlan = null; return }  // уже поздно — обычное переключение доведёт

        // Запускаем свод. Загрузка incoming + startTransition — на фоне (движок
        // @Synchronized нельзя на main). Состояние 1 (грузим), потом 2 (свод).
        autoMixPlan = null
        transitionState = 1
        transitionFromIndex = plan.fromIndex
        DebugLog.add("AutoMix.xfade TRIGGER idx=${plan.fromIndex} rem=${remaining}ms xfade=${plan.xfadeMs}ms")

        loadHandler.post {
            val canStart = !released &&
                playWhenReadyFlag &&
                transitionState == 1 &&
                transitionFromIndex == plan.fromIndex &&
                currentIndex == plan.fromIndex
            val ok = canStart && loadUriIntoIncoming(plan.nextUri)
            val stillCurrent = ok &&
                !released &&
                playWhenReadyFlag &&
                transitionState == 1 &&
                transitionFromIndex == plan.fromIndex &&
                currentIndex == plan.fromIndex
            if (stillCurrent) {
                // Ровная equal-power громкость: ВЫКЛючаем bass-swap, иначе в
                // середине свода вырезается низ одного дека и переход звучит
                // тоньше/тише. Без бит-мэтча подмена баса не нужна.
                engine.setBassSwap(false)
                engine.startTransition(plan.xfadeMs.toDouble(), plan.entryMs.toDouble(), plan.type)
            }
            handler.post {
                if (released) return@post
                if (stillCurrent) {
                    transitionState = 2     // свод пошёл
                } else {
                    if (transitionState == 1 && transitionFromIndex == plan.fromIndex) {
                        transitionState = 0     // фейл/отмена загрузки — фолбэк на обычное переключение
                        transitionFromIndex = -1
                    }
                    DebugLog.add("AutoMix.xfade LOAD FAILED → fallback advance")
                }
                invalidateState()
            }
        }
    }

    /** Загрузить URI в incoming-дек (следующий трек для свода). Зеркало
     *  loadUriIntoDeckA, но в НЕ-текущий дек. Вызывается на loadHandler (фон). */
    private fun loadUriIntoIncoming(uri: Uri): Boolean {
        if (uri.scheme == "file") {
            val p = uri.path ?: return false
            return engine.loadIncoming(p)
        }
        if (uri.scheme == "content") {
            legacyDataPath(uri)?.takeIf { File(it).exists() }?.let { p ->
                if (engine.loadIncoming(p)) return true
            }
            val viaFd = runCatching {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    engine.loadIncomingFd(pfd.fd, 0L, pfd.statSize)
                } ?: false
            }.getOrDefault(false)
            if (viaFd) return true
            val path = copyContentUriToCache(uri) ?: return false
            return engine.loadIncoming(path)
        }
        val path = resolveFilePath(uri) ?: return false
        return engine.loadIncoming(path)
    }

    /** content:// -> stable cache file path; file:// -> its path. */
    private fun resolveFilePath(uri: Uri): String? {
        if (uri.scheme == "file") return uri.path
        if (uri.scheme != "content") return uri.path
        legacyDataPath(uri)?.takeIf { File(it).exists() }?.let { return it }
        return copyContentUriToCache(uri)
    }

    @Suppress("DEPRECATION")
    private fun legacyDataPath(uri: Uri): String? {
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

    private fun copyContentUriToCache(uri: Uri): String? {
        return try {
            val dir = File(context.cacheDir, "juce_local_audio").apply { mkdirs() }
            val target = File(dir, cacheFileName(uri))
            if (target.exists() && target.length() > 0L) return target.absolutePath

            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: return null

            target.takeIf { it.exists() && it.length() > 0L }?.absolutePath
        } catch (t: Throwable) {
            DebugLog.add("JUCE.cacheCopyFAILED uri=$uri err=${t.message}")
            null
        }
    }

    private fun cacheFileName(uri: Uri): String {
        val rawName = queryDisplayName(uri)
            ?.replace(Regex("[^A-Za-z0-9._-]"), "_")
            ?.takeIf { it.isNotBlank() }
            ?: "audio"
        val displayName = if (rawName.contains('.')) {
            rawName
        } else {
            rawName + extensionForMime(context.contentResolver.getType(uri))
        }
        val key = Integer.toHexString(uri.toString().hashCode())
        return "${key}_$displayName"
    }

    private fun extensionForMime(mime: String?): String {
        return when (mime?.lowercase()) {
            "audio/mpeg", "audio/mp3" -> ".mp3"
            "audio/mp4", "audio/aac", "audio/x-m4a" -> ".m4a"
            "audio/flac", "audio/x-flac" -> ".flac"
            "audio/ogg", "application/ogg" -> ".ogg"
            "audio/wav", "audio/x-wav", "audio/wave" -> ".wav"
            else -> ".audio"
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c ->
                    if (c.moveToFirst()) {
                        val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) c.getString(idx) else null
                    } else {
                        null
                    }
                }
        } catch (_: Throwable) {
            null
        }
    }
}
