# ICM Partner API — Streaming, Async, Batch, Wave, Quality

> Вырезка из официальной документации ICM Partner API (обновлено 2026-05-17).
> Убраны упоминания конкретных платформ-источников. Фокус на интеграцию стриминга.

---

## Содержание

1. [POST /track — получение стрим-URL](#post-track--получение-стрим-url)
2. [Стрим аудио](#стрим-аудио)
3. [Async-режим для /track](#async-режим-для-track)
4. [Batch-запросы](#batch-запросы)
5. [Метаданные трека](#метаданные-трека)
6. [Выбор качества стрима](#выбор-качества-стрима)
7. [Волна (Wave / Personal Radio)](#волна-wave--personal-radio)
8. [Что и как кешировать](#что-и-как-кешировать)
9. [Ошибки](#ошибки)

---

## POST /track — получение стрим-URL

Резолвит `trackId` в подписанный URL для проигрывания.

### Запрос

```bash
POST /api/partner/track
Content-Type: application/json
X-Partner-Key: pk_...

{
  "trackId": "1440831203",
  "region": "us",
  "quality": "256K"
}
```

### Параметры запроса

| Поле | Описание |
|------|----------|
| `trackId` | ID трека из `/search` или `tracks[].id` из `/album` |
| `region` | `us` / `ru` / `nz` — зависит от `regions_allowed` ключа. По умолчанию `us` |
| `quality` | `128K`, `256K`, `320K`, `ALAC`. Пожелание — режется до `stream.max_quality` из `/health` |

### Ответ

```json
{
  "track_id": "1440831203",
  "file_id": "CQACAgIAAyEGAAS...",
  "source": "apple",
  "quality": "256K",
  "artist_id": null,
  "url": "https://byicloud.online/api/partner/audio/...?ps=...",
  "expires_at": 1779091036
}
```

| Поле | Описание |
|------|----------|
| `source` | Откуда взят трек |
| `quality` | Реально отданное качество (после кэпа) |
| `url` | Абсолютная ссылка для стрима, действует до `expires_at` |
| `expires_at` | Unix timestamp когда ссылка протухнет (обычно now + 600 сек) |
| `file_id` | Внутренний ID файла. Кешируй на сутки+ по ключу `(trackId + region + quality)` |

### Отличия источников

| Поле | Платформа A | Платформа B |
|------|-------------|-------------|
| `file_id` | Telegram file id | null (не через Telegram-прокси) |
| `url` | Подписанный URL на `/api/partner/audio/{file_id}` | Прямой URL CDN |
| `quality` | `128K` / `256K` / `320K` / `ALAC` | Всегда `"MP3 320K"` (параметр `quality` игнорируется) |
| `region` | Влияет на стрим | Игнорируется |
| `expires_at` | 10 минут | ~10 минут (пока действителен URL CDN) |

---

## Стрим аудио

URL из поля `url` ответа `/track` подставляй прямо в плеер. Никаких заголовков авторизации не нужно — подпись уже в query-параметре.

### Range / seek

Эндпоинт отдаёт `Accept-Ranges: bytes`, поэтому seek работает автоматически — `<audio>` сам делает Range-запросы при перемотке.

Для своего плеера на `fetch()` можно явно:

```javascript
fetch(url, {
  headers: { 'Range': 'bytes=0-1048575' }
})
```

Вернётся `206 Partial Content` с заголовком `Content-Range`.

### Скачать целиком

```bash
curl -L -o track.m4a "https://byicloud.online/api/partner/audio/...?ps=..."
```

### Когда URL истёк (10 минут)

Если плеер ловит `403 invalid_or_expired_signature`, просто позови `/track` заново с тем же `trackId` — получишь свежий URL. У нас сработает свой кеш, ответ придёт за миллисекунды.

### Форматы

В ответе приходит `audio/mp4` или `audio/mpeg` — все современные плееры играют оба.

---

## Async-режим для /track

Если трек ещё не закеширован у нас — `/track` может ждать 1–5 секунд (а ALAC и до минуты), пока мы скачаем и подготовим файл. Чтобы не блокировать клиент — добавь `?async=1`:

```bash
POST /api/partner/track?async=1
Content-Type: application/json

{
  "trackId": "1440831203",
  "region": "us",
  "quality": "ALAC"
}
```

### Что происходит

- Если трек тёплый — приходит обычный `200` с `url`
- Если холодный — приходит `202 Accepted`:

```json
{
  "job_id": "abc123",
  "status": "pending",
  "poll_url": "/track/job/abc123"
}
```

### Поллинг

```bash
GET /api/partner/track/job/abc123
```

Опрашивай раз в 2 секунды. Пока `status: pending` — продолжай. Когда `status: ready` — внутри ответа есть `url`, `expires_at` и всё остальное как у обычного `/track`.

Job хранится 10 минут.

### Когда использовать async

| Сценарий | Рекомендация |
|----------|--------------|
| Пользователь нажал Play | Синхронный `/track` (тёплые треки отвечают мгновенно) |
| Preload следующего трека | Синхронный `/track` (кеш сработает) |
| ALAC качество | Async рекомендуется — может ждать до минуты |
| Bulk-resolve 50+ треков | Async + параллельный поллинг |

---

## Batch-запросы

### Batch метаданные — POST /tracks/meta

Если нужно загрузить метаданные нескольких треков (например, плейлиста) — не дёргай `/track/{id}/meta` в цикле, а отправь один batch:

```bash
POST /api/partner/tracks/meta
Content-Type: application/json

{
  "trackIds": ["1440831203", "1440831204", "1440831205"]
}
```

До 50 треков за запрос. Запрос съедает один meta rate-limit-hit вместо 50.

**Ограничение:** Batch метаданные для платформы B сейчас не поддерживаются — только платформа A.

---

## Метаданные трека

### GET /track/{id}/meta

Метаданные без playback URL (быстрый, не требует подготовки файла):

```bash
GET /api/partner/track/1440831203/meta
```

Ответ:
```json
{
  "track_id": "1440831203",
  "title": "Believer",
  "artist": "Imagine Dragons",
  "album": "Evolve",
  "duration_ms": 204000,
  "is_explicit": false,
  "track_number": 3
}
```

---

## Выбор качества стрима

Линкованный юзер с подпиской может выбрать качество выше лимита партнёра.

### Получить текущий выбор

```bash
GET /api/partner/me/quality
X-Partner-User-Id: user_123
```

Ответ:
```json
{
  "quality_preference": "320K",
  "max_quality": "320K"
}
```

### Сохранить выбор

```bash
POST /api/partner/me/quality
Content-Type: application/json
X-Partner-User-Id: user_123

{"quality": "320K"}
```

Передай `{"quality": null}` чтобы сбросить выбор (вернётся auto = max доступное по подписке).

### Логика применения качества

1. `quality` параметр в `POST /track` — имеет **приоритет**, override на конкретный запрос
2. `quality_preference` из `/me/quality` — применяется автоматически если у юзера активна подписка
3. `stream.max_quality` из `/health` — лимит партнёра
4. Если у юзера нет линковки/подписки/scope — preference игнорируется, работает `cap_quality` до тарифа партнёра

### Проверка в /health

Для линкованного юзера со scope появится:
```json
{
  "linked_user": {
    "user_quality_choice": true,
    "current_preference": "320K"
  }
}
```

---

## Волна (Wave / Personal Radio)

Персональное радио для линкованных юзеров. Требует `X-Partner-User-Id`.

### Следующий трек — GET /library/wave/next

```bash
GET /api/partner/library/wave/next?seed_track_id=1440831203&region=us
X-Partner-User-Id: user_123
```

Параметры:
| Поле | Описание |
|------|----------|
| `seed_track_id` | Опционально. С чего начать волну |
| `exclude` | Опционально. ID треков через запятую — исключить из выдачи |
| `recent_skips` | Опционально. ID недавно скипнутых треков |
| `region` | Регион |

Ответ:
```json
{
  "track": {
    "id": "1440831204",
    "title": "Thunder",
    "artist": "Imagine Dragons",
    "cover": "https://.../1000x1000bb.jpg",
    "duration": 204000
  },
  "status": "ok",
  "region": "us"
}
```

### Фидбек — POST /library/wave/feedback

```bash
POST /api/partner/library/wave/feedback
Content-Type: application/json
X-Partner-User-Id: user_123

{
  "track_id": "1440831204",
  "action": "less" | "more",
  "target": "track" | "artist" | "genre"
}
```

### Сброс волны — POST /library/wave/reset

```bash
POST /api/partner/library/wave/reset
X-Partner-User-Id: user_123
```

### Onboarding (первый запуск)

```bash
# Получить популярных артистов для выбора
GET /api/partner/library/wave/popular-artists?region=us

# Сохранить выбор артистов
POST /api/partner/library/wave/onboarding
Content-Type: application/json
X-Partner-User-Id: user_123

{
  "artist_ids": ["123", "456", "789"]
}
```

---

## Что и как кешировать

| Что | Как долго | Ключ |
|-----|-----------|------|
| `/search` | 60 сек | `(query + region)` |
| Метаданные треков/альбомов | Сутки+ | `trackId` / `albumId` |
| `file_id` из `/track` | Сутки+ | `(trackId + region + quality)` |
| `partner_session_token` | Пока не истёк | `expires_in` |

**НЕ кешировать:**
- Поле `url` из `/track` — короткоживущая подпись (10 минут)
- Сам аудио-файл (`/audio/{file_id}`) — браузер кеширует через `Cache-Control: public, max-age=604800` (неделя)

---

## Ошибки

### /track ошибки

| Код | Когда | Что делать |
|-----|-------|------------|
| `403 source_not_allowed` | Источник не в `stream.allowed_sources` | Попроси менеджера включить |
| `404 track_not_found` | Нет такого trackId | Проверь ID |
| `451 region_unavailable` | Трек недоступен в этом регионе | Повтори запрос с `required_region` из ответа |
| `403 subscription_upgrade` | Качество выше лимита, нужна подписка | Предложи юзеру апгрейд |

### Async ошибки

| Код | Когда |
|-----|-------|
| `404 job_not_found` | Job протух (10 минут) или неверный ID |
| `410 job_failed` | Ошибка подготовки файла |

### Wave ошибки

| Код | Когда |
|-----|-------|
| `403 user_not_linked` | Юзер не привязан через Telegram |
| `400 no_seed` | Первая волна без onboarding — нужен `seed_track_id` |

---

## End-to-end: правильный флоу воспроизведения

1. **Пользователь нажал трек** → `POST /track` (синхронно, с `quality` если выбрано)
2. **Получили `url`** → подставили в плеер, начали воспроизведение
3. **Preload следующего** → `POST /track` для next track (кеш сработает, ответ мгновенно)
4. **URL протух** (403) → `POST /track` с тем же `trackId` → свежий URL
5. **Пользователь листает волну** → `GET /library/wave/next` → `POST /track` для полученного трека

---

*Документация подготовлена из официальных ICM Partner API Docs (2026-05-17).*
