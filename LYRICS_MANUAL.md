# Мануал: Интеграция лирики в LiquidMusicGlass

## Текущая реализация

### Источники лирики (приоритет)

1. **ICM API** — официальный источник текстов от партнёра
2. **Embedded lyrics** — из тегов аудиофайлов (локальные треки)

LRCLIB полностью удалён.

---

## ICM API Lyrics

### Endpoint

```
GET /api/partner/track/{id}/lyrics?region={region}
```

Зеркало:
```
GET /api/partner/lyrics?track_id={id}&region={region}
```

### Параметры

| Поле | Описание |
|------|----------|
| `track_id` | Apple ID (`123...`), `tidal_track_<n>`, или `custom_<n>` |
| `region` | Опционально. По умолчанию первый из `regions_allowed` ключа |

### Ответ

```json
{
  "track_id": "1411628233",
  "source": "apple",
  "format": "lrc",
  "lyrics": "[00:07.92]First things first\n[00:09.40]I'ma say all the words..."
}
```

### Ошибки

| Код | Когда |
|-----|-------|
| `403 scope_not_allowed` | Scope `lyrics` выключен в админке |
| `404 lyrics_not_found` | Текст не найден ни на диске, ни в API |

### Важно

- **На холодных треках первый запрос может занять до 10 секунд**
- Последующие запросы — мгновенно из кеша
- Не дёргать lyrics пачкой на 100 треков — lazy load при открытии плеера
- VK-треки: lyrics **не поддерживаются** (всегда `404`)

---

## Почему лирика может не работать

### 1. Нет текста в базе ICM

Проверить через API:
```bash
curl "https://byicloud.online/api/partner/track/1411628233/lyrics?region=us" \
  -H "X-Partner-Key: pk_..."
```

Если ответ `{"detail":{"error":"lyrics_not_found"}}` — у этого трека действительно нет текста.

**Примеры треков С лирикой:**
- `1411628233` — Imagine Dragons, Believer
- `1440831203` — пример из документации

**Примеры треков БЕЗ лирики:**
- `1469882396` — Akon, Smack That
- Большинство VK-треков

### 2. Проблема с парсингом ответа

`IcmLyricsResponse` должен быть помечен `@Serializable`:

```kotlin
@Serializable  // ← ОБЯЗАТЕЛЬНО
data class IcmLyricsResponse(
    val trackId: String,
    val lyrics: String? = null,
    val synced: Boolean = false
)
```

Без аннотации kotlinx.serialization не парсит JSON и возвращает `null`.

### 3. Таймаут

Первый запрос может занять 1–10 секунд. В `LyricsParser.fetchOnlineLyrics()` должен быть таймаут ≥ 15 секунд:

```kotlin
val response = withTimeout(15_000) {
    IcmRepository.getLyrics(trackId)
}
```

### 4. Неправильный trackId

Для онлайн-треков `track.uri` после резолва стрим-URL меняется с `https://byicloud.online/track/{id}` на `https://byicloud.online/api/partner/audio/...`. Парсинг ID из URI ломается.

**Решение:** передавать `track.id` напрямую в `LyricsSheet`, не парсить из URI.

---

## Архитектура лирики в приложении

### Поток данных

```
FullPlayer
  └── LyricsSheet(trackId = currentTrackObj?.id)
        └── resolvedTrackId = trackId ?: parseFromUri(audioFileUri)
        └── LaunchedEffect → LyricsParser.loadLyrics(trackId = resolvedTrackId)
              └── fetchOnlineLyrics(trackId)
                    └── IcmRepository.getLyrics(trackId)
                          └── IcmApi.getLyrics(trackId)
                                └── GET /track/{id}/lyrics
```

### Ключевые файлы

| Файл | Ответственность |
|------|-----------------|
| `LyricsSheet.kt` | UI экрана лирики, вызов `loadLyrics()` |
| `LyricsParser.kt` | Парсинг LRC, вызов `IcmRepository.getLyrics()` |
| `IcmRepository.kt` | Обёртка над `IcmApi.getLyrics()` |
| `IcmApi.kt` | HTTP-запрос к `/track/{id}/lyrics` |
| `IcmModels.kt` | `IcmLyricsResponse` — **должен быть `@Serializable`** |

---

## Отладка

### Логи

В `LyricsParser.fetchOnlineLyrics()` добавлено логирование:
```kotlin
android.util.Log.d("LyricsParser", "Fetching lyrics for $trackId")
android.util.Log.w("LyricsParser", "Lyrics fetch timeout for $trackId")
```

### Проверка через adb

```bash
adb logcat | grep LyricsParser
```

### Ручной тест API

```bash
# Трек с лирикой
curl -s "https://byicloud.online/api/partner/track/1411628233/lyrics?region=us" \
  -H "X-Partner-Key: pk_..." | head -c 200

# Трек без лирики
curl -s "https://byicloud.online/api/partner/track/1469882396/lyrics?region=us" \
  -H "X-Partner-Key: pk_..."
```

---

## Чек-лист "Почему не работает"

- [ ] Трек точно имеет lyrics в базе ICM? (проверить через curl)
- [ ] `IcmLyricsResponse` помечен `@Serializable`?
- [ ] `trackId` передаётся напрямую (не парсится из резолвленного URI)?
- [ ] Таймаут в `fetchOnlineLyrics()` ≥ 15 секунд?
- [ ] Scope `lyrics` включён в `/health`?
- [ ] Это не VK-трек? (VK не поддерживает lyrics)

---

## Документация API

Полная документация сохранена на сервере: `/root/icm_api_docs.html`

Ключевые разделы:
- **Тексты треков (lyrics)** — endpoint, параметры, ошибки
- **Получить трек** — `POST /track` для стрим-URL
- **Стрим аудио** — как проигрывать, кеширование
- **Async-режим** — `?async=1` для холодных треков
- **Batch-запросы** — `POST /tracks/meta`
- **Волна (Wave)** — `/library/wave/next`
- **Выбор качества** — `/me/quality`

---

## История изменений

| Дата | Изменение |
|------|-----------|
| 2026-05-18 | Удалён LRCLIB, добавлен ICM API lyrics |
| 2026-05-18 | Добавлен `@Serializable` к `IcmLyricsResponse` |
| 2026-05-18 | `LyricsSheet` принимает `trackId` напрямую |
| 2026-05-18 | Добавлен таймаут 15 секунд для lyrics fetch |
| 2026-05-18 | Передизайн `LyricsSheet` под Apple Music стиль |
