# ICM Music Partner API - Полная документация

## База
https://byicloud.online

## Авторизация

API-ключ выдаём при подключении, формат `pk_<id>_<random>`. Передавать в заголовке:

```
X-Partner-Key: pk_yourname_xxxxxxxxxxxx
```

Если ключ утерян — напиши, выпустим новый.

Для запросов от лица конкретного юзера добавь `X-Partner-User-Id: <id>` — нужно для аналитики и per-user настроек (hide_explicit).

### Какую модель выбрать

| Сценарий | Кто стучится к нам | Как авторизоваться | Нужен CORS? |
|----------|-------------------|-------------------|-------------|
| Server-to-server | Твой backend → наш API. Клиент ходит только к твоему backend | X-Partner-Key | Нет |
| Client-direct | JS в браузере юзера → наш API напрямую | partner_session_token (выпускает твой backend) | Да |
| Мобильный/нативный клиент | Приложение → наш API | partner_session_token или X-Partner-Key | Нет |

### Session-токен (для клиентов в браузере / мобилке)

API-ключ нельзя светить во фронте — любой пользователь увидит его в DevTools. Поэтому для прямых запросов с клиента ваш бэкенд выпускает короткоживущий токен и отдаёт его клиенту. Сам API-ключ остаётся только у вас на сервере.

**Выпуск на твоём бэкенде:**

```bash
curl -X POST -H "X-Partner-Key: pk_..." -H "Content-Type: application/json" \
  -d '{"partner_user_id":"u_42","hide_explicit":false}' \
  https://byicloud.online/api/partner/session/issue
```

**Параметры:**

| Поле | Что это |
|------|---------|
| partner_user_id | Любой стабильный id юзера в твоей системе (1–128 символов). Сюда привязывается аналитика и per-user настройки |
| hide_explicit | true если этот юзер должен видеть отфильтрованный поиск/треки. По умолчанию false |

**Ответ:**

```json
{
  "partner_session_token": "eyJhbGciOiJIUzI1NiJ9...",
  "expires_in": 3600,
  "partner_user_id": "u_42",
  "scopes": ["search", "stream", "album", "artist", "meta"]
}
```

| Поле | Описание |
|------|----------|
| partner_session_token | JWT, по умолчанию 1 час жизни. TTL настраивается админом |
| expires_in | Сколько секунд токен ещё валиден |
| scopes | Список разрешённых эндпоинтов на момент выпуска |

Клиент использует токен так же как API-ключ, только в заголовке Authorization:

```javascript
fetch("https://byicloud.online/api/partner/search?q=test", {
  headers: { "Authorization": `Bearer ${sessionToken}` }
})
```

### CORS — когда и зачем

CORS актуален только если фронт ходит к нам из браузера напрямую (схема client-direct).

- Указывай полный origin со схемой: `https://chat.example.com`
- Поддомены нужно добавлять отдельно
- `http://localhost:3000` для дев-разработки тоже можно добавить

Без CORS работает: curl, серверный код, Postman, нативные мобильные приложения.

CORS нужен только когда JS в браузере делает fetch() или WebView грузит веб-страницу.

**Когда обновлять токен:**
- Истёк (expires_in прошёл) → новый запрос за токеном из твоего бэкенда
- Сменился юзер (логин/логаут) → выпуски токены per юзер

**Полный флоу:**
1. Юзер логинится у тебя
2. Клиент: GET /your-backend/icm
3. Твой бэкенд: POST /session/issue с X-Partner-Key + partner_user_id
4. Возвращает клиенту partner_session_token
5. Клиент ходит к /api/partner/* с Authorization: Bearer <token>
6. Через час — повторяет с шага 2

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

**Параметры запроса:**

| Поле | Что это |
|------|---------|
| q | Строка поиска. До 200 символов |
| region | us / ru / nz. Должен быть из regions_allowed |

**Типы сущностей:**

| Тип | Условие | Что делать |
|-----|---------|-----------|
| Артист | isArtist: true | GET /artist/{id} |
| Альбом | isAlbum: true | GET /album/{id} |
| Трек | оба false | POST /track с trackId |

### Получить трек

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

| Поле | Описание |
|------|----------|
| source | apple / vk / custom / special |
| quality | Реально отданное качество |
| url | Абсолютная ссылка для стрима |
| expires_at | Unix timestamp когда ссылка протухнет |
| file_id | Внутренний id файла |

**Возможные ошибки:**
```json
{ "error": "region_unavailable", "required_region": "ru" } // 451
```

### Альбом

```bash
curl -H "X-Partner-Key: pk_..." \
  https://byicloud.online/api/partner/album/1440831190
```

```json
{
  "id": "1440831190",
  "title": "Evolve",
  "artist": "Imagine Dragons",
  "artistId": "358714030",
  "cover": "https://.../1000x1000.jpg",
  "year": "2017",
  "tracks": [
    {
      "id": "1440831203",
      "title": "Believer",
      "artist": "Imagine Dragons",
      "artistId": "358714030",
      "cover": "https://.../1000x1000.jpg",
      "duration": 204347
    }
  ]
}
```

### Артист

```bash
curl -H "X-Partner-Key: pk_..." \
  https://byicloud.online/api/partner/artist/358714030
```

```json
{
  "id": "358714030",
  "name": "Imagine Dragons",
  "genre": "Alternative Rock",
  "url": "https://music.apple.com/...",
  "image": "https://.../1000x1000.jpg",
  "topSongs": [...],
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
  "title": "Believer",
  "artist": "Imagine Dragons",
  "album": "Evolve",
  "cover": "https://...300x300...",
  "duration": 204347
}
```

### Batch-запросы

```bash
curl -X POST -H "X-Partner-Key: pk_..." -H "Content-Type: application/json" \
  -d '{"track_ids":["1440831200","1440831201","1440831203"]}' \
  https://byicloud.online/api/partner/tracks/meta
```

```json
{
  "count": 3,
  "items": [
    { "id": "1440831200", "title": "..." }
  ]
}
```

### Обложки

Если пришёл /api/avatar/... (custom-обложки), нужен signed URL:

```bash
curl -H "X-Partner-Key: pk_..." \
  "https://byicloud.online/api/partner/cover-sign?file_id=AgACAg..."
```

```json
{
  "url": "https://byicloud.online/api/partner/cover/AgACAg...?ps=...",
  "expires_at": 1747200600
}
```

## Ошибки

| Код | Значение |
|-----|----------|
| 401 missing_api_key | Нет заголовка X-Partner-Key |
| 401 invalid_session_token | Session-токен битый или истёк |
| 403 invalid_api_key | Ключ не найден или формат битый |
| 403 partner_suspended | Доступ приостановлен |
| 403 scope_not_allowed | Эта функция отключена в твоём конфиге |
| 403 source_not_allowed | Источник трека отключён |
| 403 invalid_or_expired_signature | Signed URL истёк |
| 404 track_not_found | Трек не найден |
| 429 rate_limited | Превышен лимит запросов |
| 451 region_unavailable | Трек недоступен в этом регионе |

## Лимиты

- search — /search
- stream — /track
- session_issue — /session/issue
- default — /album, /artist, /track/{id}/meta, /cover-sign, /lyrics

Сам стрим (/audio, /cover) лимитом не считается.

## Кеширование

**Кешировать:**
- /search — 60 секунд по ключу (query + region)
- Метаданные треков/альбомов — на сутки
- file_id — на сутки
- partner_session_token — пока не истёк

**НЕ кешировать:**
- Поле url из /track — короткоживущая подпись (10 минут)
- Сам аудио-файл

## Account Linking (Telegram Auth)

### Метод 1: Через Telegram (HTML-редирект)

**URL для редиректа пользователя:**
```
https://byicloud.online/partner/<твой_partner_id>
?partner_user_id=u_42
&redirect_uri=https://chat.example.com/
&state=случайная_строка
```

**Требования:**
- redirect_uri должен совпадать с одним из твоих CORS origins (разрешаются по запросу)
- state — случайная строка для защиты от CSRF, нужно проверять при возврате

**После успешной авторизации:**
```
redirect_uri?state=...&linked=1&icm_user_id=1406840236
```

**При ошибке:**
```
redirect_uri?state=...&linked=0&error=telegram_auth_failed
```

**Что сохранять:**
- icm_user_id — внутренний ID юзера в системе byicloud, можно сохранить для связи

### Метод 2: Через email

(Документация не описывает — нужно делать свою систему)
