# ICM Music Partner API Documentation
## https://byicloud.online/partners/api-docs

---

## База
https://byicloud.online

## Авторизация

API-ключ выдаётся при подключении, формат `pk_<id>_<random>`. Передавать в заголовке:

```
X-Partner-Key: pk_yourname_xxxxxxxxxxxx
```

Для запросов от лица конкретного юзера добавь `X-Partner-User-Id: <id>` — нужно для аналитики и per-user настроек (`hide_explicit`).

### Модели авторизации

| Сценарий | Кто стучится | Как авторизоваться | CORS? |
|----------|-------------|-------------------|-------|
| Server-to-server | Твой backend → наш API | `X-Partner-Key` | Нет |
| Client-direct | JS в браузере → наш API напрямую | `partner_session_token` | Да |
| Мобильный/нативный | Приложение → наш API | `partner_session_token` или `X-Partner-Key` | Нет |

### Session-токен

Выпуск на твоём бэкенде:
```bash
curl -X POST -H "X-Partner-Key: pk_..." -H "Content-Type: application/json" \
  -d '{"partner_user_id":"u_42","hide_explicit":false}' \
  https://byicloud.online/api/partner/session/issue
```

Ответ:
```json
{
  "partner_session_token": "eyJhbGciOiJIUzI1NiJ9...",
  "expires_in": 3600,
  "partner_user_id": "u_42",
  "scopes": ["search", "stream", "album", "artist", "meta"]
}
```

Клиент использует токен в заголовке `Authorization: Bearer <token>`.

---

## Эндпоинты

### Health
```bash
curl -H "X-Partner-Key: pk_..." https://byicloud.online/api/partner/health
```

```json
{
  "partner_id": "yourname",
  "status": "active",
  "scopes": ["search", "stream", "album", "artist", "meta"],
  "rate_limits": {
    "search": {"rpm": 600, "burst": 60},
    "stream": {"rpm": 1200, "burst": 120}
  },
  "stream": {
    "max_quality": "256K",
    "allowed_sources": ["apple", "vk"],
    "signed_url_ttl_seconds": 600
  },
  "search": {"max_results": 30, "regions_allowed": ["us", "ru"]},
  "server_time": 1747200000
}
```

### Поиск
```bash
curl -H "X-Partner-Key: pk_..." \
  "https://byicloud.online/api/partner/search?q=imagine+dragons&region=us"
```

```json
{
  "query": "imagine dragons",
  "region": "us",
  "items": [
    {
      "id": "358714030",
      "title": "Imagine Dragons",
      "artistName": "Imagine Dragons",
      "artistId": "358714030",
      "cover": "https://.../1000x1000.jpg",
      "isArtist": true,
      "isAlbum": false
    },
    {
      "id": "1440831203",
      "title": "Believer",
      "artist": "Imagine Dragons",
      "artistId": "358714030",
      "cover": "https://.../1000x1000.jpg",
      "preview": "https://...m4a",
      "collectionId": "1440831190",
      "album": "Evolve",
      "is_explicit": false,
      "region": "us",
      "isArtist": false,
      "isAlbum": false
    },
    {
      "id": "1440831190",
      "title": "Evolve",
      "artist": "Imagine Dragons",
      "artistId": "358714030",
      "cover": "https://.../1000x1000.jpg",
      "collectionId": "1440831190",
      "isAlbum": true,
      "isArtist": false
    }
  ]
}
```

Параметры: `q` (до 200 символов), `region` (us/ru/nz).

Типы сущностей:
| Тип | Условие | Действие |
|-----|---------|----------|
| Артист | `isArtist: true` | `GET /artist/{id}` |
| Альбом | `isAlbum: true` | `GET /album/{id}` |
| Трек | оба false | `POST /track` с `trackId` |

Поля результата:
- `id` — всегда (trackId / collectionId / artistId)
- `cover` — URL-шаблон Apple Music, меняй размер: `1000x1000 → 300x300`
- `preview` — 30-сек превью, можно играть напрямую
- `is_explicit` — `true` если 18+
- `collectionId` — id альбома
- `album` — название альбома
- `artistName` — только у артистов
- `isCustom` — собственные релизы

### Получить трек (stream URL)
```bash
curl -X POST -H "X-Partner-Key: pk_..." -H "Content-Type: application/json" \
  -d '{"trackId":"1440831203","region":"us","quality":"256K"}' \
  https://byicloud.online/api/partner/track
```

```json
{
  "track_id": "1440831203",
  "file_id": "BAAdf...",
  "source": "apple",
  "quality": "256K",
  "artist_id": "358714030",
  "url": "https://byicloud.online/api/partner/audio/BAAdf...?ps=...",
  "expires_at": 1747200600
}
```

Параметры: `trackId`, `region`, `quality` (128K/256K/320K/ALAC).

### Информация об альбоме
```bash
curl -H "X-Partner-Key: pk_..." \
  "https://byicloud.online/api/partner/album/1440831190?region=us"
```

```json
{
  "album": {
    "id": "1440831190",
    "title": "Evolve",
    "artist": "Imagine Dragons",
    "artistId": "358714030",
    "cover": "https://.../1000x1000bb.jpg",
    "type": "Album",
    "year": 2017,
    "trackCount": 11
  },
  "tracks": [
    {
      "id": "1440831200",
      "title": "I Don't Know Why",
      "artist": "Imagine Dragons",
      "artistId": "358714030",
      "cover": "https://.../1000x1000bb.jpg",
      "collectionId": "1440831190",
      "duration": 199853,
      "is_explicit": false,
      "trackNumber": 1
    }
  ]
}
```

### Информация об артисте
```bash
curl -H "X-Partner-Key: pk_..." \
  "https://byicloud.online/api/partner/artist/358714030?region=us"
```

```json
{
  "id": "358714030",
  "name": "Imagine Dragons",
  "cover": "https://...",
  "topTracks": [...],
  "albums": [...],
  "similarArtists": [...]
}
```

### Метаданные трека
```bash
curl -H "X-Partner-Key: pk_..." \
  https://byicloud.online/api/partner/track/1440831203/meta
```

```json
{
  "id": "1440831203",
  "collectionId": "1440831190",
  "title": "Believer",
  "artist": "Imagine Dragons",
  "cover": "https://...",
  "duration": 204347
}
```

### Batch метаданные
```bash
curl -X POST -H "X-Partner-Key: pk_..." -H "Content-Type: application/json" \
  -d '{"track_ids":["1440831200","1440831201","1440831203"]}' \
  https://byicloud.online/api/partner/tracks/meta
```

### Подпись обложки
Если URL начинается с `/api/avatar/...` (custom), нужен signed URL:
```bash
curl -H "X-Partner-Key: pk_..." \
  "https://byicloud.online/api/partner/cover-sign?file_id=AgACAg..."
```

Apple Music обложки можно использовать напрямую.

---

## Ошибки

| Код | Значение |
|-----|----------|
| 401 missing_api_key | Нет заголовка X-Partner-Key |
| 401 invalid_session_token | Session-токен битый или истёк |
| 403 invalid_api_key | Ключ не найден |
| 403 partner_suspended | Доступ приостановлен |
| 403 scope_not_allowed | Функция отключена в конфиге |
| 403 source_not_allowed | Источник трека отключён |
| 403 invalid_or_expired_signature | Signed URL истёк |
| 404 track_not_found | Трек не найден |
| 429 rate_limited | Превышен лимит |
| 451 region_unavailable | Трек недоступен в регионе |

При 429 в заголовке `Retry-After` — секунды ожидания.

---

## Лимиты

- `search` — /search
- `stream` — /track
- `session_issue` — /session/issue
- `default` — /album, /artist, /track/{id}/meta, /cover-sign

Сам стрим (`/audio`, `/cover`) лимитом не считается.

### Кеширование

| Эндпоинт | Кеш | TTL |
|----------|-----|-----|
| /search | локальный | 60 сек |
| /album/{id}, /artist/{id}, /track/{id}/meta | локальный | 24ч+ |
| /track (file_id) | локальный | 24ч+ |
| url из /track | НЕ кешировать | 10 мин |
| /audio/{file_id} | браузер | 7 дней (Cache-Control) |

---

## CORS

Актуален только для JS в браузере (client-direct). Серверный код, мобильные приложения, curl — CORS не нужен.

Добавь origin в whitelist: `https://chat.example.com` (со схемой). Поддомены — отдельно. `http://localhost:3000` для dev тоже можно.

---

## End-to-end сценарий

1. Поиск: `GET /search?q=believer&region=us`
2. Берём первый трек (`!isArtist && !isAlbum`), его `id`
3. Получаем stream: `POST /track` с `trackId`
4. Берём `url` из ответа — отдаём в плеер
