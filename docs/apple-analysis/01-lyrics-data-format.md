# Лирика: форматы данных, роли вокала, кэш

Разбор устройства Apple Music для Android. Аналитический отчёт: структура,
форматы данных, алгоритмы и поведение. Исходники и тексты песен не включены.

---

Analysis complete. The network sub-agent hasn't reported back, but I recovered the network layer independently.

## Что сделано

Разобрана подсистема лирики Apple Music (`SV_MediaPlayback`, сборка 834 / `SV_MediaApi-2424`). Довыгружены `classes.dex` → `out1/`, `classes4.dex` → `out4/` (нужен был enum тайминга). Ничего в репозиториях не менялось.

**Главный вывод по архитектуре:** лирика — это **TTML**, а не JSON. Весь парсинг и тайминг-движок живут в нативе (`libandroidappmusic.so`, C++, исходные пути уцелели: `SV_MediaPlayback/src/fuse/cpp/Lyrics/`), Java/Kotlin — только транспорт (JavaCPP) и рендер.

---

## (а) Карта классов

### Нативная модель (JavaCPP-биндинги, `out3/sources/com/apple/android/music/ttml/javanative/model/`)

| Класс | Назначение | Ключевые поля |
|---|---|---|
| `LyricsTiming` | Базовый тайминг-объект, от него наследуются секция/строка/слово | `getBegin()`, `getEnd()`, `getDuration()` (все `int`, мс), `getAgent()` (id вокалиста) |
| `SongInfo` | Корень документа | `getSections()`, `getAgents()`, `getAgent(id)`, `getTiming()`, `getLanguage()`, `getScript()`, `getLyricsId()`, `getAdamId()`, `getQueueId()`, `getDuration()`, `hasTranslation/hasPronunciation/hasScript(lang)`, `getTranslationLanguages()`, `getPronunciationLanguages()`, `isTranslationAutomaticallyCreated()`, `getSongwriters(lang)`, `generateLegacyLyricsLines()` |
| `LyricsSection` | Группа строк (куплет/припев), сама с begin/end | `getLines()` |
| `LyricsLine` | Строка | `getLineId()`, `getWords()`, `getBackgroundWords(bool)`, `getPronunciationWords()`, `getPronunciationBackgroundWords(bool)`, `getTranslatedBackgroundWords(bool)`, `getKeepParenthesis()`, + 6 вариантов `getHtml*LineText()` |
| `LyricsWord` | Слово/слог | `getWordId()`, `getBegin()`, `getEnd()`, `getDuration()`, `isWhitespace()`, `getLyricsLine()` (обратная ссылка) |
| `LyricsAgent` | Исполнитель партии | `getId()`, `getType()` ∈ {None, Person, Character, Group, Organization, Other}, `getName(NameType)`, NameType ∈ {None, Full, Family, Given, Alias, Other} |
| `Vi.a` (`out4`) | Enum доступного тайминга | `None(0)`, `Line(1)`, `Word(2)` |

### Движок и оркестрация

| Класс | Роль |
|---|---|
| `javanative/TTMLParser` | Единственный метод: `songInfoFromTTML(String) → SongInfoPtr` |
| `javanative/SongInfoTimeProcessorJavaCpp` | `processEvents(...)`, `suggestLineOffset(int)`, `suggestWordOffset(int)`, `applyLyricsOffset(bool)`, `getSuggestedLine/WordOffset()` |
| `ttml/SongInfoTimeProcessor` (Kotlin) | Обёртка: 5 колбэков (line / word / bgWord / prWord / prBgWord), каждый `(forPosition, vector, deadline)`; возвращает **deadline** следующего события |
| `ttml/g` (`C1740g.java`) | Kotlin `LyricsController`: загрузка (сеть/файл), парсинг, прокачка `processEvents`, владение процессором |
| `ttml/h` (`C1741h.java`) | «Visual state» + телеметрия проигрывания лирики (`PlayActivityEventsReporter`) |
| `ttml/k` (`C1746k.java`) | Плоский индекс: секции → сквозной номер строки (`a(i)`, `b()` = всего строк) |
| `ttml/j` (`C1743j.java`) | Адаптер `LyricsSongInfo` для репортинга (`isTimingLineType()`) |
| `player/viewmodel/PlayerLyricsViewModel` | `loadLyrics()`, `mLyricsResult`, `mTimeRangeToLyricIndexMap`, `notifyWordHighlight(lineId, wordId, duration, isBg)`, `getLyricLineIndex(ms)` |
| `player/fragment/PlayerLyricsViewFragment` | Планировщик тика, offsets, коннект к `MediaBrowser` |
| `player/C4398z` | Рендер строк/слов, дуэт-выравнивание, karaoke-анимации |

---

## (б) Формат данных

**Транспорт:** обычный MediaAPI-энтити JSON, `type: "syllable-lyrics"` (класс `SyllableLyrics extends Lyrics`) либо `"lyrics"` (статика). Внутри:
- `attributes.ttmlLocalizations` — **предпочитаемое** поле, TTML со всеми переводами/транслитерациями;
- `attributes.ttml` — fallback, базовый TTML;
- `attributes.playParams.id` → `lyricsId`.

Легаси-модель `LyricsResponse {id, lyrics}` — старый плоский текст, в новом пути не используется.

**Сам TTML.** Словарь, подтверждённый строковыми литералами в `libandroidappmusic.so` (точные совпадения): `head`, `body`, `div`, `p`, `span`, `metadata`, `agent`, `role`, `x-bg`, `begin`, `end`, `id`, `type`, `name`, `key`, `text`, `lang`, `timing`, `translation`, `transliteration`, `songwriter`, `iTunesMetadata`, `keep`, `parenthesis`.

Структура:
- `<tt itunes:timing="None|Line|Word">` — **глобальный флаг гранулярности** (→ `SongInfo.getTiming()`).
- `<head><metadata>`: `ttm:agent` (id + `type` + вложенные `name` разных `NameType`) и приватный блок `iTunesMetadata` с `translations` / `transliterations` (каждый `<text for="<key>">`, привязка по ключу строки) и `songwriters`.
- `<body>` → `<div>` = секция (begin/end) → `<p>` = строка (`begin`, `end`, `itunes:key`, `ttm:agent`) → `<span begin= end=>` = слово/слог.
- Бэк-вокал: вложенный `<span ttm:role="x-bg">`, внутри — свои `<span>`-слова. Скобки вокруг бэка — часть контента; парсер умеет их сохранять/срезать (`getKeepParenthesis()`, флаг у `getBackgroundWords(bool)`).
- Время: строгий формат `Hour:MinutesSeconds.3-digit-fraction` (парсер ругается «Out of range! Must be Hour:MinutesSeconds.3-digit-fraction»), внутри всё в целых миллисекундах.

**Про слово: есть И begin, И end, И duration** — три независимых поля. Пробелы — отдельные «слова» с `isWhitespace()==true`. Пауз как сущности **нет**: пауза = дырка между `word[i].end` и `word[i+1].begin` (и между `line.end` и `nextLine.begin`). Валидатор парсера отбраковывает слово, если `begin < line.begin`, `end > line.end` или `duration <= 0`.

**Четыре параллельных трека слов на каждую строку:** основной, бэк-вокал, произношение (романизация), произношение бэк-вокала. Плюс перевод — как готовый текст строки, не пословно.

**Remote-путь (AirPlay/handoff, `remoteclient/generated/`)** — деградированный: `LyricsItem{token, lyrics: String, userProvided}`, `LyricsEvent{token, startTime, endTime: double}`. Только построчно, пословных таймингов там нет.

---

## (в) Алгоритмы

### Тайминг-движок — событийный, без поллинга
`processEvents(songInfo, positionMs, 5 колбэков) → deadline`. Натив сам вызывает нужные колбэки и **возвращает время следующего события**. `PlayerLyricsViewFragment.m4939K2()` планирует себя заново через `Handler.sendMessageDelayed(msg, deadline - position)`. Пока никто не поёт — CPU простаивает.

### Активная строка и перекрытия
Колбэк отдаёт **вектор** строк (`LyricsLineVector`), а не одну — перекрытие (дуэт, бэк поверх основного) поддержано нативно и рендерится одновременно. Никакого «выбора победителя» нет.

Отдельно строится `mTimeRangeToLyricIndexMap` — TreeMap-интервальная карта `[line.begin, line.end] → сквозной индекс строки` для сик/диплинков. Поиск: `floorEntry(t)`, затем состояния `IN_RANGE` / `HOLE` (инструментальный проигрыш) / `OUT_OF_RANGE_MIN` / `OUT_OF_RANGE_MAX`. В `HOLE` возвращается `null` — «строки нет».

### Прогресс внутри слова
Слово не интерполируется по позиции плеера. В момент word-события запускается `ValueAnimator.ofFloat(0,1)` длительностью ровно `word.getDuration()`, `PathInterpolator`, и он доигрывает сам. Виды слов хранятся в `ArrayMap`, **ключ — `word.getBegin()`**. Наружу это идёт как `notifyWordHighlight(lineId, wordId, wordAnimationDuration, isBackgroundWord)`.

Посимвольная «расцветка» длинных нот включается только если `duration >= 1000 мс` **и** длина слова `<= 7` символов (и язык не из CJK/особых Unicode-блоков); иначе слово красится целиком. Стагтер между символами ограничен сверху ~400 мс.

### Дуэт: выравнивание по агенту
1. Работает **только при `timing == Word`**.
2. Считаются агенты типов `Person` и `Other`; если их суммарно `<= 1` — обычная одноколоночная вёрстка.
3. Для каждой строки вычисляется код выравнивания ∈ {1 = дефолт, 5 = «своя» сторона, 6 = «противоположная»}:
   - первая строка: `Person → 5`, `Other → 6`, остальное → 1;
   - `Group` всегда → 1 (хор не сдвигают);
   - тот же агент, что у предыдущей строки → наследует её код;
   - смена агента → флип 5↔6, **с поправкой на направление письма**: если RTL-ность текущей и предыдущей строки различается, флип инвертируется (иначе RTL-строка сама уедет не туда).
4. Если ни одна строка не получила 6 — карта выравниваний выбрасывается, рисуем как обычно.
5. На вьюхе код 6 даёт `gravity = LTR ? LEFT : RIGHT`, всё остальное — зеркально.

Размер/стиль бэк-вокала — отдельные layout-биндинги (`lyrics_word_karaoke_bg`, `lyrics_bg_translation_line_karaoke`), своя строка flex-контейнера под основной; выравнивание наследуется от агента основной строки.

### Деградация
- `Word` → пословная заливка + дуэт + karaoke.
- `Line` → `isTimingLineType()`; только line-колбэки, слова не запрашиваются, дуэт-карта не строится. В телеметрии это `"LyricsTimeSync"`.
- `None` → `StaticLyricsFragment` + `SongInfo.generateLegacyLyricsLines()` (плоский `String[]`), синхронизации нет вообще. Телеметрия: `"LyricsStatic"`.

### Синхронизация и компенсация задержки — самое интересное
Позиция берётся **двумя разными путями**:
- **remote/cast** → `MediaBrowser.getCurrentPosition()`;
- **локальное воспроизведение** → нативный клок-саплаер (микросекунды / 1000), т.е. позиция **аудиорендерера**, а не MediaSession. Это и есть базовая компенсация латентности аудиотракта.

Поверх — три уровня коррекции в `SongInfoTimeProcessor`:

1. **Line lead-in (упреждение строки).** По зазору до следующей строки:
   ```
   gap    = clamp(nextLine.begin - line.end, 200, 750)   // мс
   leadIn = round(lerp(480, 750, (gap - 200) / 550))     // мс, диапазон [480,750]
   suggestLineOffset(-leadIn)
   itemAnimator.moveDuration = leadIn
   ```
   Строка подсвечивается/скроллится за `leadIn` мс до своего `begin`, а длительность анимации скролла равна `leadIn` — поэтому строка «приезжает» ровно в момент начала пения. Дефолт при смене трека: `-500` мс и `moveDuration = 750`.

   > Поправка к прежней заметке в `AppleMusic_Lyrics_RE.md`: диапазон `[480, 750]` — это **миллисекунды упреждения/длительности анимации**, а не пиксели выезда индикатора.

2. **Word lead-in:** `suggestWordOffset(-100)` когда включён karaoke-режим (`karaokeAnimationMode == 2`, либо `== 1` при активной вокальной аттенюации), иначе `0`.

3. **Atmos-компенсация:** `applyLyricsOffset(codec == EAC3_JOC)`. Т.е. при Dolby Atmos (E-AC-3 JOC) натив добавляет фиксированный сдвиг на лишнюю латентность декодера. В логе движка: `processEvents for position ... (line offset .. word offset ..) (Apply Atmos offset .. value = ..)`. Реализовано через `LyricsTimingTransform::setBeginEndOffset(int)` — сдвигаются begin/end, а не позиция.

Инструментальный проигрыш: три ImageView-точки с «дыханием» scale 1.0→1.2→1.0, базовый цикл 4000 мс, `repeatCount` подбирается так, чтобы уложиться в длину паузы.

---

## Сеть и кэш (кратко — детали ниже)

**Сеть.** `/v1/catalog/{storefront}/songs/{adamId}/syllable-lyrics` (статика — `.../lyrics`); базовые URL приходят из storefront-бага: `musicSubscription/timeSyncedLyrics` и `musicSubscription/lyrics`. Запрос строится в `PlayerLyricsViewModel.loadLyrics()` и передаёт два массива: языки (`[currentSystemLyricsLanguage]`) и скрипты (`"<lang>-<script>"`, из `LocaleUtil.getSystemLyricsScripts()`); имена query-параметров в дексе — `extend`, `include`, `with`, `language`, `platform`, значение `ttmlLocalizations`.

**Гейтинг по подписке — серверный, а не клиентский.** Все URL лежат в секции `musicSubscription/*` бага: нет подписки → в баге нет эндпоинта → запрашивать нечего. Клиент дополнительно смотрит каталожные флаги `attributes.hasLyrics` / `hasTimeSyncedLyrics`.

**Нет лирики:** `loadLyrics()` при `!hasLyrics() && !hasCustomLyrics()` сразу публикует `Pair(null, null)` и чистит интервальную карту — это и есть состояние «нет лирики» (в отличие от `Pair(songInfo, null)` = ок и `Pair(null, exception)` = ошибка). Если TTML отсутствует в ответе — `RuntimeException("TTML Not found!")`.

**Кэш** (по результатам параллельного разбора):
- В памяти — **одна** ячейка `mLyricsResult` на текущий трек (не LRU, не map). Валидность проверяется сравнением `playbackItem.getId()` с `SongInfo.getAdamId()`.
- OkHttp-кэш для лирики **отключён** (клиент создаётся без `Cache`), в Room-кэш `MediaApiCache` лирика не попадает.
- На диске — только для **скачанных** треков: `<id>_lyrics.txt` в папке ассетов, AES-CBC, ключ = SHA-256 от DSID аккаунта. Хранится не TTML, а весь JSON-энтити.
- **TTL = 30 суток** по умолчанию, переопределяется багом `musicSubscription/lyricsFeatureDefaults/offlineTTL` (ISO-8601). Пишется как абсолютный дедлайн в нативную медиатеку (`DownloadedAssetInfo.expirationDate`, `assetInfoType = LYRICS`).
- Ключ диска — store id трека. Язык/сторфронт в ключ **не входят**; при смене языка лирики делается принудительный рефетч.
- **Предзагрузки лирики для следующего трека НЕТ.** Оффлайн-копия пишется лениво: только если пользователь сам открыл лирику у уже скачанного трека. `OfflineLyricsBackFillWorker` — заглушка: `doWork()` сразу возвращает `Success`, и он даже не ставится в очередь WorkManager (в отличие от соседнего `ArtworkRepairWorker`, 24 ч / UNMETERED).
- TTML парсится один раз, кэшируется распарсенный нативный `SongInfo` (`shared_ptr`), сырая строка не удерживается.

---

## (г) Что мы сможем повторить с пословными таймингами от ICM, и чего не хватит

**Повторяется 1:1, если ICM отдаёт `begin`/`end` на слово:**
- Пословная заливка с корректным прогрессом — нужен именно `end`, а не только `begin`: длительность анимации = `duration`, а «дырки» между `word.end` и `next.begin` дают естественные микропаузы внутри строки. Если ICM отдаёт только `begin`, придётся выводить `end = next.begin`, и слово будет тянуться до следующего — визуально заметно хуже на распевах.
- Событийный планировщик с deadline вместо тика каждый кадр — чистая реализация на нашей стороне, данных не требует.
- Line lead-in по формуле `clamp(gap,200,750) → lerp(480,750)` — считается из наших же `line.end`/`next.begin`.
- Word lead-in −100 мс в karaoke-режиме.
- Интервальная карта `[begin,end] → индекс строки` с состояниями IN_RANGE/HOLE для тапа «играть с этой строки» и для индикатора проигрыша.
- Посимвольная расцветка длинных нот по правилу `duration >= 1000 && len <= 7`.
- Индикатор проигрыша: 3 точки, цикл 4000 мс, repeatCount под длину паузы.

**Чего не хватит без расширения формата ICM:**
- **Бэк-вокал** — нужен отдельный, помеченный трек слов на строку (аналог `ttm:role="x-bg"`) со своими begin/end. Без этой пометки бэк либо пропадёт, либо смешается с основным вокалом.
- **Дуэт/выравнивание по сторонам** — нужен `agentId` на строке + справочник агентов с типом (`Person` / `Group` / `Other`). Без типа нельзя отличить «хор — не сдвигаем» от «второй голос — сдвигаем», а без стабильного id не работает флип 5↔6.
- **Гранулярность документа** (`None`/`Line`/`Word`) — нужен явный флаг, иначе не решить, включать ли дуэт и karaoke: у Apple дуэт-раскладка жёстко завязана на `Word`.
- **Переводы/транслитерация** — у Apple это три дополнительных параллельных трека слов + `for="<key>"`-привязка к строке. Если ICM отдаёт перевод одной строкой, доступен только режим «перевод под строкой», без пословной подсветки перевода.
- **Секции** (`div` с begin/end) — Apple использует их как контейнер; без них теряется группировка куплет/припев (на текущий рендер, впрочем, не критично).
- **Компенсация Atmos/латентности** — не про данные: нужен доступ к позиции аудиорендерера, а не к `player.currentPosition`. Это ровно то, что даёт форк media3 — там можно взять позицию из `AudioTrack.getTimestamp()`/`MediaClock` вместо позиции ExoPlayer, плюс добавить фиксированный сдвиг для кодеков с большой латентностью декодирования.
- **Стабильные `lineId`/`wordId`** — Apple ими индексирует вьюхи и события подсветки; если ICM не даёт id, придётся генерировать свои и следить, чтобы они не «поехали» при смене языка/перевода.

Файлы, с которых стоит начинать при перепроверке: `/tmp/claude-0/-root/72359245-dc64-4107-b259-b5b723be01ef/scratchpad/out3/sources/com/apple/android/music/ttml/` (модель + движок), `/tmp/claude-0/-root/72359245-dc64-4107-b259-b5b723be01ef/scratchpad/out2/sources/com/apple/android/music/player/C4398z.java` (рендер, дуэт, karaoke), `/tmp/claude-0/-root/72359245-dc64-4107-b259-b5b723be01ef/scratchpad/out2/sources/com/apple/android/music/player/fragment/PlayerLyricsViewFragment.java` (планировщик и offsets), `/tmp/claude-0/-root/72359245-dc64-4107-b259-b5b723be01ef/scratchpad/am/libandroidappmusic.so` (строковый словарь TTML).

---

Пришёл отчёт по сетевому слою — он **уточняет и частично исправляет** то, что я написал по пункту 5. Дополнение:

## Сетевой слой (уточнённо)

### Эндпоинт
Живой эндпоинт ровно один:

```
GET https://amp-api.music.apple.com/v1/catalog/{storefront}/songs/{adamId}/syllable-lyrics
```

Query-параметры (в этом порядке):

| параметр | значение |
|---|---|
| `l[lyrics]` | языки через запятую — из `LocaleUtil.getSystemLyricsLanguage()`, вид `en-US` / `ja-Jpan` |
| `extend` | литерал `ttmlLocalizations` |
| `l[script]` | скрипты через запятую — `<lang>-<ScriptShortName>`, напр. `ja-Latn` |

Заголовки: `X-Dsid`, `User-Agent` (`Music/<ver> Android/<rel> model/<model> build/<n>`), `Authorization: Bearer <token>`. Плюс два интерцептора: подпись действий (`X-Apple-ActionSignature` + `X-Request-TimeStamp`, только для POST/PUT/DELETE/PATCH) и Anisette-ретрай по `X-Apple-AMD-Action`/`X-Apple-AMD-Data`.

**Storefront** резолвится так: кэш → бэг-ключ `currentStorefrontCountryCodeISO2A` (lowercase) → `GET /v1/me/storefront`, берётся `data[0].id`. Язык и скрипты — **чисто из локали устройства**, к сторфронту не привязаны.

### Исправление: гейтинг по подписке — клиентский, не серверный
Я ошибся, сказав, что URL лирики приходит из бэга `musicSubscription/*` и потому гейт серверный. На самом деле:
- `BagConfig.timeSyncedLyricsUrl` / `staticLyricsUrl` **разбираются, но нигде не используются** — ни одной ссылки во всех четырёх дексах. Это рудимент, как и Gson-модель `LyricsResponse{id, lyrics}`. Путь к amp-api собирается конкатенацией литералов в коде.
- Реальный гейт — предикат `C4154b1.m4819i(item)` = `SubscriptionCoordinator.h() && (item.hasLyrics() || item.hasCustomLyrics())`, где `h()` = `subscriptionStatus == ENABLED`. Не прошёл — кнопка/вкладка лирики просто не показывается, `loadLyrics()` не вызывается вообще. Отдельного upsell-экрана и специфичной для лирики обработки 401/403 нет.
- Дополнительно `StaticLyricsFragment` уходит в `ERROR` с диалогом, если трек explicit, а explicit-контент запрещён.

Для нас это скорее плюс: сервер лирику никак не защищает — вся защита в клиенте, а `hasLyrics`/`hasTimeSyncedLyrics` приходят как обычные флаги каталожного энтити.

### Состояния (уточнение к «нет лирики»)
`PlayerLyricsViewModel` кодирует всё одной `LiveData<Pair<SongInfoPtr, Exception>>`:
- `(null, null)` — лирики нет; `(songInfo, null)` — успех; `(null, exception)` — ошибка; «загрузка» = ещё не было эмита.
- `PlayerLyricsViewFragment` читает только `pair.first`, поэтому «нет лирики» и «ошибка» схлопываются в один UI-бранч (layout `lyrics_error`).
- А вот `StaticLyricsFragment` различает: у него есть явный enum `com.apple.android.music.lyrics.EnumC3777b { LOADING, ERROR, SUCCESS }`.
- HTTP-ошибки конвертируются в `MediaApiResponse` с пустым `data[]` и массивом `Error{id,status,code,title,detail,meta}`, что дальше упирается в тот же `RuntimeException("TTML Not found!")`.

### Оффлайн-пайплайн (детальнее, чем я дал)
Ключ — SHA-256 от DSID (hex); шифрование AES/CBC/PKCS5, ключ = первые 16 байт этой hex-строки, IV в начале, Base64. Файл `<name>_lyrics.txt` в корне загрузок (SD-путь из prefs, иначе `getNoBackupFilesDir()/track-assets`). TTL — бэг `musicSubscription/lyricsFeatureDefaults/offlineTTL` (ISO-8601), дефолт 30 суток, пишется абсолютным дедлайном в медиатеку. Читается только при отсутствии сети. `OfflineLyricsBackFillWorker` в этой сборке — заглушка (`doWork()` сразу `success`).

### Remote-протокол (точные номера полей)
`LyricsToken{identifier=1:string, userData=2:bytes}`, `LyricsItem{lyrics=1:string, userProvided=2:bool, token=3}`, `LyricsEvent{startTime=1:double, endTime=2:double, token=3}`, `SendLyricsEventMessage{event=1}`. Плюс `ContentItemMetadata.lyricsAvailable=24`, `.lyricsURL=71`, `.lyricsAdamID=104`. Chromecast — отдельный JSON-канал с `lyricsLang` (захардкожен `"default"`), `lyricsPosition`, `lyricsDisplayed`.

### Оговорка по достоверности
Четыре ключевых метода (`MediaApiImpl.getSyllableLyrics`, `MediaApiDefaultImpl.z`, лямбда параметров, `LyricsDownloader.downloadLyrics`) jadx помечает как «Method not decompiled» — шаблон URL, имена query-параметров и последовательность шифрования/записи восстановлены декодированием DEX-байткода (потоки строковых констант и method-ref), а не из `.java`. Роли аргументов достоверны, точный позиционный порядок в 6-арговом вызове транспорта — нет.

Выводы части (г) это не меняет: пословные тайминги от ICM закрывают заливку и тайминг-движок, а не хватать будет ровно того же — разметки бэк-вокала, `agentId` + типа агента для дуэта, флага гранулярности и позиции аудиорендерера (последнее решается форком media3, а не форматом данных).
