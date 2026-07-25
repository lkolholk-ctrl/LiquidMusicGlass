package com.liquidmusicglass.engine

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import com.liquidmusicglass.data.wave.WaveMode
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Ядро бесконечного стриминга "Моей Волны".
 *
 * Отвечает за:
 * - Управление контекстом фоновой дозагрузки.
 * - Фоновый мониторинг очереди воспроизведения.
 * - Throttling запросов к API и предотвращение параллельных вызовов (isRefilling).
 */
@OptIn(UnstableApi::class)
class EndlessPlaybackEngine(
    private val scope: CoroutineScope,
    private val getController: () -> androidx.media3.session.MediaController?,
    private val getCompanionPlayer: () -> androidx.media3.exoplayer.ExoPlayer?
) {

    companion object {
        // Держим большой буфер заранее. Пользователь не должен доедать очередь
        // до 8-10 треков и ждать сеть: initial batch 30, затем добиваем до ~60.
        const val REFILL_THRESHOLD = 40
        const val REFILL_BATCH_SIZE = 30
        // Станция по треку — единственный режим без batch-эндпоинта: каждый
        // трек = отдельный GET /library/wave/next. Пачка 30 давала залп из
        // 30+ последовательных запросов (риск 429); добираем меньшими дозами.
        const val STATION_REFILL_BATCH_SIZE = 12
        const val MIN_REFILL_INTERVAL_MS = 2000L
    }

    private val _refillContext = MutableStateFlow<RefillContext?>(null)
    val refillContext: StateFlow<RefillContext?> = _refillContext

    // Потокобезопасный set (аудит): registerTracks зовётся и с main
    // (addTracksToQueue), и с IO (playFromList), а рефилл на IO итерирует
    // playedIds — обычный LinkedHashSet давал ConcurrentModificationException
    // внутри try рефилла → рефилл молча фейлился, волна вставала.
    private val playedIds: MutableSet<String> =
        java.util.concurrent.ConcurrentHashMap.newKeySet()
    private val isRefilling = AtomicBoolean(false)
    private val lastRefillTime = AtomicLong(0L)
    private var refillCounter = 0

    data class RefillContext(
        val type: Type,
        val id: String? = null,
        val name: String? = null,
        val seedTrackId: String? = null,
        val genre: String? = null,
        val mood: String? = null,
        // Пул якорных сидов (артист-волна: топ-треки артиста). Ротация seed
        // идёт по нему, а не по хвосту очереди — станция «притягивается»
        // обратно к артисту вместо транзитивного дрейфа в соседей-соседей.
        val seedPool: List<String> = emptyList()
    ) {
        enum class Type { WAVE, ARTIST, ALBUM, SEARCH, GENRE, MOOD, PLAYLIST, LIBRARY, YWAVE }
    }

    fun setRefillContext(context: RefillContext?) {
        _refillContext.value = context
        android.util.Log.d("EndlessEngine", "Context changed to: ${context?.type?.name ?: "null"}")
    }

    fun reset() {
        playedIds.clear()
        isRefilling.set(false)
        lastRefillTime.set(0L)
        refillCounter = 0
        _refillContext.value = null
        android.util.Log.d("EndlessEngine", "Reset complete")
    }

    fun registerTracks(trackIds: List<String>) {
        playedIds.addAll(trackIds)
        if (playedIds.size > 500) {
            // Кап размера. У concurrent-сета нет порядка вставки, поэтому срез
            // приблизительный — это осознанно: прежний LinkedHashSet.take(400)
            // и так резал НЕ то (оставлял старейшие, выбрасывая свежесыгранное),
            // а главное — не переживал конкурентную итерацию рефилла (CME).
            // Свежие id тут же регистрируются заново из очереди — анти-повтор
            // для недавних треков сохраняется.
            val excess = playedIds.size - 400
            val it = playedIds.iterator()
            var removed = 0
            while (removed < excess && it.hasNext()) { it.next(); it.remove(); removed++ }
        }
    }

    fun registerTrack(trackId: String) {
        playedIds.add(trackId)
    }

    /**
     * Проверяет заполненность очереди и дозагружает треки "Моей волны" при необходимости.
     *
     * @param remainingCount Количество треков, оставшихся в очереди перед активным треком.
     * @return true, если дозагрузка была успешно выполнена.
     */
    suspend fun checkAndRefillIfNeeded(remainingCount: Int = -1, force: Boolean = false): Boolean {
        // Дозаправка волной осмысленна только для стриминга: в локальную очередь
        // она подмешивала онлайн-треки, а JUCE такой трек не откроет.
        if (com.liquidmusicglass.engine.PlayerController.isLocalJucePlaybackActive) {
            return false
        }
        // Дозаправка работает ВЕЗДЕ (полевой фидбек: «одно и то же по кругу —
        // не по кайфу»):
        //  - волна (Global) — продолжаем волну: личную или станцию по seed;
        //  - альбом/плейлист/поиск/артист — когда свой материал кончается,
        //    бесшовно переходим в станцию по хвосту очереди (как у Яндекса).
        // Прежние гейты (AutoMix-тумблер и boundary-lock на Global) убраны:
        // тумблер AutoMix управляет кроссфейдами, а не бесконечностью, и
        // именно эта связка оставляла очередь «ходить по кругу».

        if (isRefilling.get()) {
            android.util.Log.d("EndlessEngine", "Already refilling in background, skip")
            return false
        }

        // Calculate remaining items if not passed explicitly
        val remaining = if (remainingCount == -1) getRemainingTracks() else remainingCount
        android.util.Log.d("EndlessEngine", "Checking refill: remaining=$remaining, threshold=$REFILL_THRESHOLD")

        if (!force && remaining >= REFILL_THRESHOLD) {
            android.util.Log.d("EndlessEngine", "Queue has sufficient tracks ($remaining >= $REFILL_THRESHOLD), skipping")
            return false
        }

        val now = System.currentTimeMillis()
        val last = lastRefillTime.get()
        if (!force && now - last < MIN_REFILL_INTERVAL_MS) {
            android.util.Log.d("EndlessEngine", "Throttled: only ${now - last}ms elapsed since last refill (min interval=$MIN_REFILL_INTERVAL_MS)")
            return false
        }

        if (!isRefilling.compareAndSet(false, true)) {
            return false
        }

        return try {
            lastRefillTime.set(now)
            android.util.Log.d("EndlessEngine", "Starting background queue refill...")

            val newTracks = withContext(Dispatchers.IO) {
                val ctx = PlayerController.context
                if (ctx != null) {
                    val refillCtx = _refillContext.value
                    val isGlobal = PlayerController.playbackContext is PlaybackContext.Global
                    val currentQueue = PlayerController.getCurrentQueue()
                    val queueIds = currentQueue.map { it.id }
                    val currentIndex = PlayerController.getCurrentIndex()
                    val activeQueueIds = currentQueue
                        .drop(currentIndex.coerceIn(0, currentQueue.size))
                        .map { it.id }
                    val seedPool = refillCtx?.seedPool.orEmpty()
                    val isStrictTrackStation = isGlobal &&
                        refillCtx != null &&
                        refillCtx.type == RefillContext.Type.WAVE &&
                        !refillCtx.seedTrackId.isNullOrBlank() &&
                        refillCtx.id.isNullOrBlank() &&
                        refillCtx.name.isNullOrBlank() &&
                        seedPool.isEmpty()
                    val waveRepo = com.liquidmusicglass.data.local.WaveRepository.getInstance(ctx)

                    // Волна ЯМ: дозаправка пачками ротора, минуя ICM-логику ниже.
                    if (isGlobal && refillCtx?.type == RefillContext.Type.YWAVE) {
                        return@withContext com.liquidmusicglass.data.yandex.YandexWaveEngine
                            .nextBatch(excludeIds = (playedIds + queueIds).toSet())
                    }

                    if (isGlobal && refillCtx?.type == RefillContext.Type.MOOD) {
                        val mood = refillCtx.id ?: refillCtx.name
                        if (!mood.isNullOrBlank()) {
                            val moodTracks = waveRepo.buildWaveModeQueue(
                                mode = WaveMode.Mood(
                                    mood = mood,
                                    displayName = refillCtx.name,
                                    source = "apple",
                                    diversity = 0.5
                                ),
                                count = REFILL_BATCH_SIZE,
                                exclude = queueIds
                            )
                            // Пул mood кончился → личной волной; а она у Apple-only
                            // аккаунта ПУСТА, поэтому терминально — жанровый поиск
                            // (рабочий источник, когда personal пуст). Иначе муд-волна встаёт.
                            if (moodTracks.isNotEmpty()) return@withContext moodTracks
                            val personal = waveRepo.buildWaveModeQueue(WaveMode.Personal(), REFILL_BATCH_SIZE, queueIds)
                            if (personal.isNotEmpty()) return@withContext personal
                            return@withContext waveRepo.buildGenreSearchQueue(
                                genres = refillCtx.seedPool.ifEmpty { listOf(mood) },
                                count = REFILL_BATCH_SIZE,
                                exclude = (playedIds + queueIds).toList()
                            )
                        }
                    }

                    if (isGlobal && refillCtx?.type == RefillContext.Type.GENRE) {
                        val genre = refillCtx.genre ?: refillCtx.id ?: refillCtx.name
                        if (!genre.isNullOrBlank()) {
                            val genreTracks = waveRepo.buildWaveModeQueue(
                                mode = WaveMode.Genre(
                                    genre = genre,
                                    displayName = refillCtx.name,
                                    source = "apple",
                                    diversity = 0.5
                                ),
                                count = REFILL_BATCH_SIZE,
                                exclude = queueIds
                            )
                            // Пул genre кончился → личная волна пуста (Apple-only) →
                            // терминально жанровый поиск, иначе жанр-волна встаёт.
                            if (genreTracks.isNotEmpty()) return@withContext genreTracks
                            val personal = waveRepo.buildWaveModeQueue(WaveMode.Personal(), REFILL_BATCH_SIZE, queueIds)
                            if (personal.isNotEmpty()) return@withContext personal
                            return@withContext waveRepo.buildGenreSearchQueue(
                                genres = refillCtx.seedPool.ifEmpty { listOf(genre) },
                                count = REFILL_BATCH_SIZE,
                                exclude = (playedIds + queueIds).toList()
                            )
                        }
                    }

                    // SEARCH-волна: fallback пустой personal-волны. Дозаправка идёт
                    // ПОИСКОМ по топ-жанрам (seedPool) — персональная волна у Apple-
                    // only аккаунта пуста. Ротация жанров + вычитание сыгранного
                    // держат её бесконечной.
                    if (isGlobal && refillCtx?.type == RefillContext.Type.SEARCH) {
                        val genres = refillCtx.seedPool.ifEmpty {
                            listOfNotNull(refillCtx.id ?: refillCtx.name)
                        }
                        if (genres.isNotEmpty()) {
                            // Дозаправка тоже через /wave/genre (round-robin), а не сырой
                            // текст-поиск — иначе жанровость терялась после первой пачки.
                            return@withContext waveRepo.buildMultiGenreWaveQueue(
                                genres = genres,
                                count = REFILL_BATCH_SIZE,
                                exclude = (playedIds + queueIds).toList()
                            )
                        }
                    }

                    // Волна: seed из контекста (мудовая/трековая станция) или null
                    // (личная волна). Не-волна: станция по ПОСЛЕДНЕМУ треку
                    // очереди — альбом кончился, продолжаем «по мотивам».
                    val baseSeed = if (isGlobal) refillCtx?.seedTrackId else queueIds.lastOrNull()
                    // Обычная "волна по треку" должна всегда оставаться anchored к
                    // исходному seed_track_id. Ротация и дрейф ниже нужны для артист-
                    // волны/жанровых сценариев, но для track station они превращают
                    // станцию в личную волну или соседей-соседей.
                    val seed = if (isStrictTrackStation) {
                        baseSeed
                    } else if (isGlobal && baseSeed != null) {
                        refillCounter++
                        when {
                            refillCounter % 2 == 1 -> baseSeed
                            seedPool.isNotEmpty() ->
                                seedPool.randomOrNull() ?: baseSeed
                            else -> queueIds.takeLast(5).randomOrNull() ?: baseSeed
                        }
                    } else baseSeed

                    // Станция по треку: по доке сервер НЕ подмешивает свою
                    // 4-часовую сессию в этом режиме — анти-повтор целиком наш.
                    // Шлём текущую+будущую очередь И хвост уже сыгранного (кап 40),
                    // иначе станция ходит по кругу. Полную историю не шлём — от
                    // неё узкая станция высыхает (проверено полевым фидбеком).
                    val exclude = if (isStrictTrackStation) {
                        val playedPrefix = queueIds
                            .take(currentIndex.coerceIn(0, queueIds.size))
                            .takeLast(40)
                        (playedPrefix + activeQueueIds).distinct()
                    } else {
                        (playedIds + queueIds).toList()
                    }
                    val batchSize = if (isStrictTrackStation) {
                        STATION_REFILL_BATCH_SIZE
                    } else {
                        REFILL_BATCH_SIZE
                    }
                    var tracks = waveRepo.buildWaveQueue(
                        count = batchSize,
                        seedTrackId = seed,
                        exclude = exclude
                    )
                    // Если Apple station вернула только треки из текущего хвоста, не
                    // меняем seed и не уходим в персональную волну. Ослабляем exclude
                    // до текущего + пары следующих, чтобы станция могла быть бесконечной
                    // даже при небольшом пуле похожих треков.
                    if (tracks.isEmpty() && isStrictTrackStation && seed != null) {
                        val relaxedExclude = activeQueueIds.take(3).distinct()
                        if (relaxedExclude.size < exclude.size) {
                            android.util.Log.w(
                                "EndlessEngine",
                                "Track station returned empty (seed=$seed), retrying with relaxed exclude=${relaxedExclude.size}"
                            )
                            tracks = waveRepo.buildWaveQueue(
                                count = batchSize,
                                seedTrackId = seed,
                                exclude = relaxedExclude
                            )
                        }
                    }
                    // Станция реально пересохла (пусто ДАЖЕ после relaxed-retry) →
                    // ДРЕЙФ: станция вокруг последнего трека очереди (похожие-на-
                    // похожие, пул расширяется транзитивно). Работает и для строгой
                    // станции по треку — она обязана быть бесконечной: во время
                    // нормальной работы сюда не заходим (tracks непусты, seed держится),
                    // а на сухом пуле дрейфуем на соседний трек и ре-якоримся на нём.
                    if (tracks.isEmpty() && seed != null) {
                        val driftSeed = queueIds.lastOrNull()?.takeIf { it != seed }
                        if (driftSeed != null) {
                            android.util.Log.w("EndlessEngine", "Station dried up (seed=$seed) — drifting to seed=$driftSeed")
                            tracks = waveRepo.buildWaveQueue(
                                count = REFILL_BATCH_SIZE,
                                seedTrackId = driftSeed,
                                exclude = exclude
                            )
                            if (tracks.isNotEmpty() && isGlobal && refillCtx != null) {
                                _refillContext.value = refillCtx.copy(seedTrackId = driftSeed)
                                com.liquidmusicglass.debug.DebugLog.add("WAVE drift seed -> $driftSeed")
                            }
                        }
                    }
                    // Дрейфовать некуда/нечем → личная волна: музыка не должна
                    // останавливаться. Терминальный рубеж для ЛЮБОЙ станции.
                    if (tracks.isEmpty() && seed != null) {
                        android.util.Log.w("EndlessEngine", "Drift dried up too — falling back to personal wave")
                        tracks = waveRepo.buildWaveQueue(
                            count = REFILL_BATCH_SIZE,
                            seedTrackId = null,
                            exclude = exclude
                        )
                        if (tracks.isNotEmpty() && isGlobal && refillCtx?.seedTrackId != null) {
                            _refillContext.value = refillCtx.copy(seedTrackId = null)
                            com.liquidmusicglass.debug.DebugLog.add("WAVE station -> personal (dried up)")
                        }
                    }
                    // Терминальный рубеж ЛЮБОЙ WAVE-дозаправки: если после всех
                    // попыток пусто (station/drift/personal — а personal у Apple-only
                    // аккаунта пуст; либо seed вообще не-apple → сразу null) → жанровый
                    // поиск по топ-жанрам, как у «Моей волны». Без него WAVE-ветка не
                    // имеет fall-through в buildGenreSearchQueue (та достижима только
                    // из SEARCH) и станция молча встаёт. Гард — просто tracks пуст:
                    // покрывает и «станция пересохла» (seed!=null), и не-apple seed
                    // (seed==null, радио по Y/локальному треку — иначе 1 трек и стоп).
                    if (tracks.isEmpty()) {
                        val genres = waveRepo.getTopGenres(limit = 5)
                        if (genres.isNotEmpty()) {
                            tracks = waveRepo.buildMultiGenreWaveQueue(
                                genres = genres,
                                count = REFILL_BATCH_SIZE,
                                exclude = (playedIds + queueIds).toList()
                            )
                            if (tracks.isNotEmpty() && isGlobal && refillCtx != null) {
                                // Переводим станцию в SEARCH: следующие рефиллы идут
                                // сразу в рабочую ветку, а не упираются снова в пустое.
                                _refillContext.value = refillCtx.copy(
                                    type = RefillContext.Type.SEARCH,
                                    seedTrackId = null,
                                    seedPool = genres
                                )
                                com.liquidmusicglass.debug.DebugLog.add("WAVE station -> genre search (dried up)")
                            }
                        }
                    }
                    tracks
                } else {
                    android.util.Log.e("EndlessEngine", "Context is null, unable to fetch wave repository")
                    emptyList()
                }
            }

            if (newTracks.isNotEmpty()) {
                withContext(Dispatchers.Main) {
                    PlayerController.addTracksToQueue(newTracks)
                }
                android.util.Log.d("EndlessEngine", "Refill completed successfully: added ${newTracks.size} tracks")
                newTracks.forEach { playedIds.add(it.id) }
                true
            } else {
                android.util.Log.w("EndlessEngine", "WaveRepository returned an empty queue, refill failed")
                false
            }
        } catch (e: Exception) {
            android.util.Log.e("EndlessEngine", "Error refilling wave tracks: ${e.message}", e)
            false
        } finally {
            isRefilling.set(false)
        }
    }

    private suspend fun getRemainingTracks(): Int {
        return withContext(Dispatchers.Main) {
            val player = getController() ?: getCompanionPlayer() ?: return@withContext 0
            val total = player.mediaItemCount
            val current = player.currentMediaItemIndex
            if (total > 0 && current >= 0) (total - current) else 0
        }
    }
}
