# My Wave — Текущая архитектура (LiquidMusicGlass)

> Документ описывает, как устроена функция «Моя волна» в проекте LiquidMusicGlass на момент 28.05.2026.
> Основан на анализе исходного кода в ветке `main`.

---

## 1. Обзор

«Моя волна» — это персональная радиостанция, которая подбирает треки на основе:
- Истории прослушиваний пользователя (local Room DB)
- Лайков и пропусков
- Seed-треков (случайный лайкнутый трек из кеша)
- Ответов сервера ICM `/library/wave/next`

Поддерживается в двух режимах:
| Режим | Источник | Требования |
|-------|----------|------------|
| ICM | Apple Music + VK (через ICM API) | partner_user_id + подписка |
| YouTube Music | InnerTube API | Бесплатно, без подписки |

---

## 2. Ключевые компоненты

### 2.1 FeatureAccessManager (`camp/FeatureAccessManager.kt`)

Синглтон, управляющий выбором «лагеря» (music camp).

```kotlin
sealed class MusicCamp {
    object Icm : MusicCamp()       // id="icm", displayName="Apple Music"
    object Youtube : MusicCamp()   // id="youtube", displayName="YouTube Music"
}
```

**StateFlow:**
- `_currentCamp: StateFlow<MusicCamp>` — текущий выбор
- `_capabilities: StateFlow<Capabilities>` — фичи на основе camp + подписки

**Persistence:** SharedPreferences (`liquid_camp_prefs`), ключ `selected_camp`.

**Матрица возможностей:**
| Фича | ICM Free | ICM Premium | YouTube |
|------|----------|-------------|---------|
| Download | ❌ | ✅ | ✅ |
| Hi-Res | ❌ | ✅ | ❌ |
| My Wave | ❌ | ✅ | ✅ |
| Full Catalog | ❌ | ✅ | ✅ |
| Unlimited Skips | ❌ | ✅ | ✅ |

---

### 2.2 WaveRepository (`data/local/WaveRepository.kt`)

**Назначение:** Локальный репозиторий для сбора аналитики и построения очереди «Моей волны».

**Константы:**
```kotlin
const val MIN_LISTEN_TIME_MS = 30_000L      // Мин. время для записи в историю
const val GENRE_ANALYSIS_DAYS = 30          // Сколько дней назад смотрим историю
const val WAVE_QUEUE_SIZE = 20              // Максимум треков в очереди
```

**Основные методы:**

#### `getTopGenres(limit: Int = 5): List<String>`
Возвращает топ-жанры пользователя за последние 30 дней из Room DB.
Если история пуста — использует onboarding-жанры или дефолтные (`Electronic`, `Electro House`, `Techno`).

#### `logListening(track, durationPlayedMs, source)`
Записывает факт прослушивания (только если `durationPlayedMs >= 30 сек`).

#### `logTrackPlayed(track)` / `logTrackSkipped(track)`
Обновляет статистику в `playbackHistoryDao`:
- `incrementPlayCount()` — трек дослушан (>85%)
- `incrementSkipCount()` — трек пропущен рано (<30%)

#### `buildWaveQueue(count: Int = 5): List<Track>`
**Главный метод построения очереди.**

Алгоритм:
1. Получает последние 50 треков из истории для `exclude`
2. В цикле (до `count * 6` попыток):
   - Берёт случайный лайкнутый трек из `dao.getRandomFavoriteTrackId()` как seed
   - Вызывает `IcmRepository.getWaveNext(seedTrackId, exclude, recentSkips=0)`
   - Применяет **HARD BAN FILTER** — блокирует CIS trash (`кишлак`, `soda luv`, `face`, `принц`, `рэп`, `rap`)
   - Применяет **SKIP RATIO FILTER** — если `skipRatio > 0.70` и `total >= 2`, трек исключается
   - Резолвит stream URL через `IcmRepository.getStreamUrl()`
   - Применяет **heuristic genre tagging**:
     - `title содержит "mix"/"remix"` → `Tech House`
     - `artist содержит "dj"` → `House`
     - иначе → `Electronic`
3. Возвращает список треков с resolved URL

---

### 2.3 IcmRepository (`api/icm/IcmRepository.kt`)

**Назначение:** Репозиторий-прослойка между UI и `IcmApi`. Кэширует, обрабатывает ошибки, конвертирует модели.

**Wave-методы:**

#### `getWaveNext(...): IcmWaveResponse?`
Вызывает `api.getWaveNext()` с merged exclude list:
- `exclude` от вызывающего + последние 200 треков из Room `playbackHistoryDao`

Параметры:
```kotlin
suspend fun getWaveNext(
    seedTrackId: String? = null,
    exclude: List<String>? = null,
    recentSkips: Int? = null,
    region: String? = null,
    source: String? = null,
    mood: String? = null,
    genre: String? = null,
    historyLimit: Int = 200
): IcmWaveResponse?
```

#### `buildWaveQueue(count: Int = 5, seedTrackId: String? = null): List<Track>`
Простая обёртка: вызывает `getWaveNext()` `count` раз, собирает треки.

#### `sendWaveFeedback(feedbackType, value): Boolean`
Отправляет фидбек на сервер:
- `feedbackType`: `less_track`, `less_artist`, `less_genre`, `more_track`, `more_artist`, `more_genre`
- `value`: track ID, artist ID, или genre name

#### `resetWave(): Boolean`
Сбрасывает историю волны, seed-артистов и предпочтения. Лайки сохраняются.

#### `getWavePopularArtists(): List<IcmWaveOnboardingArtist>`
Возвращает популярных артистов для onboarding. **Не требует** `partnerUserId`.

#### `getWaveOnboarding(): IcmWaveOnboardingResponse?`
Проверяет статус onboarding (выбраны ли артисты).

#### `saveWaveOnboarding(artists): Boolean`
Сохраняет выбор артистов (минимум 1, рекомендуется 3-5).

#### `logWavePlayback(trackId, playedSeconds, totalSeconds, completed, skipped): Boolean`
Логирует событие воспроизведения для улучшения ранжирования волны.

---

### 2.4 IcmApi (`api/icm/IcmApi.kt`)

**Назначение:** Низкоуровневый HTTP-клиент для ICM Partner API.

**Base URL:** `https://byicloud.online/api/partner`

**Авторизация:**
- `Authorization: Bearer <sessionToken>` — для user-scoped эндпоинтов
- `X-Partner-Key: <apiKey>` — всегда требуется для source validation
- `X-Partner-User-Id: <partnerUserId>` — для wave, library, /me/*

**Wave эндпоинты:**

```kotlin
// Получить следующий трек волны
GET /library/wave/next?seed_track_id=...&exclude=...&recent_skips=...&region=...&source=...&mood=...&genre=...

// Фидбек
POST /library/wave/feedback
Body: { "feedback_type": "less_track", "value": "track_id" }

// Сброс волны
POST /library/wave/reset

// Популярные артисты (onboarding)
GET /library/wave/popular-artists

// Статус onboarding
GET /library/wave/onboarding

// Сохранить onboarding
POST /library/wave/onboarding
Body: { "artists": [{"id": "...", "name": "..."}] }

// Лог playback
POST /library/wave/playback
Body: { "track_id": "...", "played_seconds": 120.5, "completed": true }
```

---

### 2.5 HomeScreen (`ui/screens/HomeScreen.kt`)

**Назначение:** Главный экран с контентом и mood-станциями.

**Mood Categories (горизонтальный список):**
```kotlin
val moodCategories = listOf(
    MoodCategory("my_wave", "My Wave", listOf(Color(0xFFFC3C44), Color(0xFFFF6B6B)), seedQueries = listOf("")),
    MoodCategory("melancholy", "Melancholy", listOf(Color(0xFF1E3A5F), Color(0xFF2D5A87)), seedQueries = listOf("melancholy", "sad indie", "lo-fi sad")),
    MoodCategory("good_mood", "Good Mood", listOf(Color(0xFFD4730E), Color(0xFFF5A623)), seedQueries = listOf("happy pop hits", "feel good", "summer hits")),
    MoodCategory("broken_heart", "Broken Heart", listOf(Color(0xFF8B1538), Color(0xFFC41E3A)), seedQueries = listOf("breakup songs", "heartbreak", "sad love songs")),
    MoodCategory("focus", "Focus", listOf(Color(0xFF2D5016), Color(0xFF4A7C23)), seedQueries = listOf("focus instrumental", "deep focus", "study beats")),
    MoodCategory("energy", "Energy", listOf(Color(0xFF8B4513), Color(0xFFD2691E)), seedQueries = listOf("high energy", "power hits", "edm energy")),
    MoodCategory("night", "Night Wave", listOf(Color(0xFF1A1A2E), Color(0xFF16213E)), seedQueries = listOf("late night", "night drive", "synthwave night")),
    MoodCategory("workout", "Workout", listOf(Color(0xFF4A0000), Color(0xFF8B0000)), seedQueries = listOf("workout", "gym motivation", "running mix")),
    MoodCategory("chill", "Chill", listOf(Color(0xFF483D8B), Color(0xFF6A5ACD)), seedQueries = listOf("chillhop", "chill lofi", "ambient chill"))
)
```

**Логика `playMoodStation(moodId)`:**

```
Если moodId == "my_wave":
    Если currentCamp == Youtube:
        → YouTubeMusicRepository.search(seedQuery) 
        → YouTubeMusicRepository.getRadioQueue(seedTrack.videoId)
        → PlayerController.playFromList(autoRefillType="YOUTUBE_RADIO")
    Иначе:
        → WaveRepository.buildWaveQueue()
        → PlayerController.setQueue() + playTrack(0)
Иначе (другие mood):
    → resolveMoodSeedTrackId(mood) — ищет seed через IcmRepository.searchTracks()
    → IcmRepository.getWaveNext(seedTrackId=seed, exclude, recentSkips=0) × 5 раз
    → PlayerController.setQueue() + playTrack(0)
    → loadMoreMoodTracks() — подгружает ещё 5 треков в фоне
```

**Auto-refill:**
- `PlayerController.setAutoRefillContext(type="wave", id=moodId, name=mood.title, seedTrackId=seed)`
- `EndlessPlaybackEngine` вызывает `getWaveNext()` когда очередь заканчивается

---

### 2.6 EndlessPlaybackEngine (`engine/EndlessPlaybackEngine.kt`)

**Назначение:** Автодозаполнение очереди при приближении к концу.

**Логика refill:**
```kotlin
when (autoRefillType) {
    "wave" -> {
        val response = IcmRepository.getWaveNext(
            seedTrackId = autoRefillSeed,
            exclude = currentQueueIds,
            recentSkips = recentSkipCount
        )
        if (response?.track != null) addToQueue(response.track.toTrack())
    }
    "YOUTUBE_RADIO" -> {
        val result = YouTubeMusicRepository.getRadioQueue(seedVideoId)
        // ... добавляет треки в очередь
    }
}
```

---

### 2.7 AudioService (`engine/AudioService.kt`)

**Wave playback logging:**
- При завершении трека: `IcmRepository.logWavePlayback(trackId, playedSeconds, completed=true)`
- При пропуске: `IcmRepository.logWavePlayback(trackId, playedSeconds, skipped=true)`

---

## 3. Модели данных

### 3.1 IcmWaveResponse
```kotlin
@Serializable
data class IcmWaveResponse(
    val track: IcmWaveTrack? = null,
    val status: String,           // "ok", "empty", "error"
    val region: String? = null
)
```

### 3.2 IcmWaveTrack
```kotlin
@Serializable
data class IcmWaveTrack(
    val id: String,
    val title: String,
    val artist: String? = null,
    @SerialName("artistId") val artistId: String? = null,
    val cover: String? = null,
    val duration: Long? = null,   // VK: seconds, Apple: ms
    @SerialName("collectionId") val collectionId: String? = null,
    @SerialName("is_explicit") val isExplicit: Boolean = false,
    @SerialName("isCustom") val isCustom: Boolean = false,
    val source: String? = null
) {
    val durationMs: Long
        get() = if ((duration ?: 0) < 1000L) (duration ?: 0) * 1000L else duration ?: 0L
    
    fun toTrack(): Track { ... }
}
```

### 3.3 IcmWaveOnboardingArtist
```kotlin
@Serializable
data class IcmWaveOnboardingArtist(
    @SerialName("id") val id: String,
    @SerialName("name") val name: String,
    @SerialName("image") val image: String? = null
)
```

---

## 4. Потоки данных

### 4.1 My Wave (ICM) — полный цикл

```
Пользователь тапает "My Wave"
  ↓
HomeScreen.playMoodStation("my_wave")
  ↓
Проверяет currentCamp:
  ├─ Youtube → YouTube-ветка (см. 4.2)
  └─ Icm → WaveRepository.buildWaveQueue()
            ↓
            Получает recent track IDs (50 шт) из Room
            ↓
            Цикл (до 30 попыток):
              ├─ seedTrackId = dao.getRandomFavoriteTrackId()
              ├─ IcmRepository.getWaveNext(seedTrackId, exclude, recentSkips=0)
              ├─ Ban filter (кишлак, soda luv, face...)
              ├─ Skip ratio filter (>70% skips → exclude)
              ├─ IcmRepository.getStreamUrl(track.id) → resolved URL
              └─ Heuristic genre tagging
            ↓
            Возвращает List<Track> (до 5 треков)
  ↓
PlayerController.setQueue(tracks)
PlayerController.playTrack(context, 0)
PlayerController.setAutoRefillContext("wave", "my_wave", "My Wave", seedTrackId)
  ↓
EndlessPlaybackEngine (когда очередь < 3 треков)
  ↓
IcmRepository.getWaveNext(seedTrackId, exclude=currentQueue, recentSkips)
  ↓
Добавляет новый трек в очередь
```

### 4.2 My Wave (YouTube) — полный цикл

```
Пользователь тапает "My Wave" (YT camp)
  ↓
HomeScreen.playMoodStation("my_wave")
  ↓
currentCamp == Youtube
  ↓
YouTubeMusicRepository.search("popular music", filter=SONGS)
  ↓
Берёт первый результат как seedTrack
  ↓
YouTubeMusicRepository.getRadioQueue(seedTrack.videoId)
  ↓
Для каждого трека в радио:
  YouTubeMusicRepository.getAudioStream(videoId)
  → YtAudioStream с прямым URL
  ↓
PlayerController.playFromList(
    tracks = resolvedTracks,
    autoRefillType = "YOUTUBE_RADIO",
    autoRefillId = seedTrack.videoId
)
  ↓
EndlessPlaybackEngine (YT radio continuation)
```

### 4.3 Mood Station (не-my_wave)

```
Пользователь тапает "Melancholy" / "Energy" / etc.
  ↓
resolveMoodSeedTrackId(mood)
  ↓
IcmRepository.searchTracks(mood.seedQueries.first(), limit=5)
  ↓
Берёт ID первого результата как seed
  ↓
IcmRepository.getWaveNext(seedTrackId=seed, exclude, recentSkips=0) × 5 раз
  ↓
PlayerController.setQueue() + playTrack(0)
  ↓
loadMoreMoodTracks() — подгружает ещё 5 треков в фоне
```

---

## 5. База данных (Room)

### 5.1 Таблицы

**playback_history** (via `playbackHistoryDao`):
- `trackId: String` — ID трека
- `title: String`
- `artistId: String`
- `timestamp: Long`
- `playCount: Int`
- `skippedCount: Int`

**listening_history** (via `waveDao`):
- `trackId: String`
- `title: String`
- `artist: String`
- `genre: String?`
- `source: String`
- `durationPlayedMs: Long`
- `timestamp: Long`

**cached_tracks** (via `waveDao`):
- `id: String`
- `title: String`
- `artist: String`
- `genre: String?`
- `streamUrl: String?`
- `coverUrl: String?`
- `durationMs: Long`
- `isFavorite: Boolean`
- `isDownloaded: Boolean`
- `source: String`

### 5.2 DAO-запросы для Wave

```kotlin
@Query("SELECT trackId FROM playback_history ORDER BY timestamp DESC LIMIT :limit")
suspend fun getRecentTrackIds(limit: Int): List<String>

@Query("SELECT genre, COUNT(*) as count FROM listening_history WHERE timestamp > :sinceMs GROUP BY genre ORDER BY count DESC LIMIT :limit")
suspend fun getTopGenres(sinceMs: Long, limit: Int): List<GenreCount>

@Query("SELECT id FROM cached_tracks WHERE isFavorite = 1 ORDER BY RANDOM() LIMIT 1")
suspend fun getRandomFavoriteTrackId(): String?

@Query("SELECT * FROM track_stats WHERE trackId = :trackId")
suspend fun getTrackStat(trackId: String): TrackStat?
```

---

## 6. Известные проблемы

| # | Проблема | Файл | Описание |
|---|----------|------|----------|
| 1 | HomeScreen не адаптируется к camp | `HomeScreen.kt` | Всегда показывает ICM контент, даже при выборе YT |
| 2 | YT Search parsing | `YouTubeMusicRepository.kt` | `.lastOrNull()` для поиска shelf — хрупко |
| 3 | YouTubeMusicBanner — костыль | `HomeScreen.kt` | Вместо интеграции YT в Home — отдельный баннер |
| 4 | Search не учитывает camp | `SearchScreen.kt` | Всегда ищет в ICM, игнорируя выбор YT |
| 5 | Нет YT home recommendations | — | Нет Quick Picks, Trending для YT camp |
| 6 | Hardcoded ban words | `WaveRepository.kt` | Список запрещённых слов захардкожен |
| 7 | Heuristic genre tagging | `WaveRepository.kt` | Упрощённая эвристика для жанров |

---

## 7. Файлы, задействованные в My Wave

| Файл | Назначение |
|------|------------|
| `data/local/WaveRepository.kt` | Локальная аналитика, построение очереди, фильтры |
| `api/icm/IcmRepository.kt` | Прослойка над API, кэширование, обработка ошибок |
| `api/icm/IcmApi.kt` | HTTP-клиент ICM Partner API |
| `api/icm/IcmModels.kt` | DTO: IcmWaveResponse, IcmWaveTrack, IcmWaveOnboardingArtist и др. |
| `api/icm/IcmAuthRepository.kt` | Аутентификация, session tokens, partner_user_id |
| `ui/screens/HomeScreen.kt` | UI mood-карточек, playMoodStation() |
| `ui/viewmodel/HomeViewModel.kt` | Загрузка home content, buildWaveQueue() |
| `engine/EndlessPlaybackEngine.kt` | Автодозаполнение очереди |
| `engine/AudioService.kt` | Логирование playback событий |
| `engine/PlayerController.kt` | Управление очередью, auto-refill context |
| `camp/FeatureAccessManager.kt` | Управление music camp |
| `camp/MusicCamp.kt` | Типы camp (Icm / Youtube) |
| `api/youtube/YouTubeMusicRepository.kt` | YT Music API (InnerTube) |
| `data/local/db/AppDatabase.kt` | Room DB: playback_history, listening_history, cached_tracks |
| `data/local/db/WaveDao.kt` | DAO для wave-таблиц |
| `data/local/db/PlaybackHistoryDao.kt` | DAO для playback stats |

---

## 8. API Endpoints (ICM Wave)

| Method | Endpoint | Auth | Описание |
|--------|----------|------|----------|
| GET | `/library/wave/next` | Bearer + X-Partner-Key + X-Partner-User-Id | Следующий трек волны |
| POST | `/library/wave/feedback` | Bearer + X-Partner-Key | Фидбек (less/more track/artist/genre) |
| POST | `/library/wave/reset` | Bearer + X-Partner-Key | Сброс волны |
| GET | `/library/wave/popular-artists` | X-Partner-Key | Популярные артисты (onboarding) |
| GET | `/library/wave/onboarding` | Bearer + X-Partner-Key | Статус onboarding |
| POST | `/library/wave/onboarding` | Bearer + X-Partner-Key | Сохранить выбор артистов |
| POST | `/library/wave/playback` | Bearer + X-Partner-Key | Лог playback события |

---

*Документ сгенерирован автоматически на основе анализа исходного кода.*
