# Аудио-движок: архитектура и обработка

Разбор устройства Apple Music для Android. Аналитический отчёт: структура,
форматы данных, алгоритмы и поведение. Исходники и тексты песен не включены.

---

Все три направления разобраны, ключевые выводы я дополнительно перепроверил по первичным исходникам. Отчёт ниже.

---

# Аудио-движок Apple Music для Android — разбор архитектуры

Материал: `classes.dex`–`classes4.dex` (декомпилированы в `outcore`/`out2`/`out3`/`out4` в скретчпаде), нативные библиотеки в `.../scratchpad/am/`. Сборка `SV_MediaPlayback-834_fuseRelease`, форк ExoPlayer — база **2.11.2** (`ExoPlayerLibraryInfo.VERSION`).

Кроссфейд, как договаривались, не разбирался — только те места, где он структурно пересекается с остальным.

## 1. Карта компонентов

Гибрид: **сессионный слой — media3, движок — их форк ExoPlayer 2.11.2.**

| Слой | Что это | Где |
|---|---|---|
| Сессия/браузинг | `MediaPlaybackService extends androidx.media3.session.MediaLibraryService`. Один сервис, три intent-filter: `MediaBrowserService` (Auto), `androidx.media3.session.MediaSessionService`, `MEDIA_BUTTON` | `out2/.../music/player/MediaPlaybackService.java` |
| media3 `Player`-адаптер | Рукописная реализация `androidx.media3.common.Player` (2307 строк) поверх их контроллера, со своей `Timeline`, собранной из их очереди | `out2/.../music/player/C4120P.java` |
| Callback библиотеки | `MediaLibrarySession.Callback` — дерево браузинга | `out2/.../music/player/C4165f0.java` |
| Контроллер | Аудиофокус, becoming-noisy, wake/wifi-lock, свой `HandlerThread` | `out2/.../playback/controller/LocalMediaPlayerController.java` |
| Движок | Обёртка над форком ExoPlayer: собирает рендереры, `LoadControl`, `TrackSelector` | `out2/.../playback/player/ExoMediaPlayer.java` |
| Очередь | `PlaybackQueueManager` + провайдеры (библиотека/стор/радио/автоплей), персист в SQLite | `out2/.../playback/queue/` |
| Источник | `PlaybackQueueMediaSource` — динамический источник поверх всей очереди, отдаёт `PlaybackQueueTimeline` | `out2/.../playback/player/mediasource/` |
| Рендер | 2×`SVAudioRendererV2` (нативный декод) + 2×`SVMediaCodecAudioRenderer` | `out2/.../playback/renderer/` |
| Нативный кодек | `SVAudioDecoderJNI` (JavaCPP) → AAC/SBR/PS + ALAC + FairPlay внутри C++ | `libandroidappmusic.so` |

**Массив рендереров ровно 8** (`ExoMediaPlayer.createRenderers`): `[0],[1]` — `SVAudioRendererV2`; `[2],[3]` — `SVMediaCodecAudioRenderer`; `[4]` — видео; `[5],[6]` — метаданные; `[7]` — текст. То есть **две параллельные аудио-цепочки, каждая продублирована** — пара нужна для сведения/gapless, вторая пара для платформенного декода.

Слоты аудиорендереров живут в синглтон-реестре `n9.c` (`out3/sources/p099n9/C2725c.java`) — ровно 2 штуки, каждый со своим `audioSessionId`. Это важно: любой эффект, привязанный к session id, нужно ставить на **оба**.

## 2. Схема потока данных

```
PlaybackQueueManager (очередь)
   → PlaybackQueueMediaSource → PlaybackAssetMediaSource/MediaPeriod
        → PlaybackAssetRequestManager: lease + подписанный URL + SINF + FairPlay-ключ
   → PlayerProgressiveDownloadDataSource (прогрессив) | PlayerHls*DataSource (HLS)
        → MediaAssetCache: playback_assets/<storeId>_<protType>_<flavor>  [ШИФРОТЕКСТ]
   → Mp4Extractor (форк: + ludt/tlou/alou, bitDepth, iTunes-scheme)
        → Format {encoderDelay, encoderPadding, loudness, bitDepth}
   → ProtectedSampleStream: вешает AppCryptoInfo на каждый сэмпл
   → SVAudioRendererV2 (он же MediaClock)
        → SVAudioDecoderJNI: расшифровка + декод в C++
        → пул из 8 direct ByteBuffer (zero-copy, буферы аллоцирует Java)
   → DefaultAudioSink: [Vocals/Resample — выключены] → Trimming → ChannelMapping → Sonic
        → setVolume(volume × soundCheckGain)
   → AudioTrack
```

Ветка Atmos идёт мимо нативного декодера: `ec-3` → `SVMediaCodecAudioRenderer` → платформенный Dolby-декодер.

## 3. Обработка звука

### Эквалайзер — своего нет

Единственное живое использование `android.media.audiofx` во всём APK — в `out2/.../common/activity/BaseActivity.java`. Схема:

1. Настройки запускают системную панель эффектов через `Intent("android.media.action.DISPLAY_AUDIO_EFFECT_CONTROL_PANEL")`; если она не резолвится — пункт EQ просто отключается (и это логируется как non-fatal с `Build.MANUFACTURER`/`MODEL`).
2. Панель правит эффект только **одной** аудио-сессии. Поэтому в `onActivityResult` (requestCode 112) они читают `Equalizer.Settings` у слота 0 и **копируют их в session id второго слота**. Всё в try/catch — на OEM без эффекта просто ничего не происходит.

Пакеты `playback/renderer/equalizer/` (`SVEqualizer`, `SVEqualizerProxy`, `FrequencyBand`, пресеты) и UI `music/equalizer/` (`EQActivity`, `EQView`, `BandLevelBar`) — **мёртвый каркас**: интерфейс `SVEqualizerProxy` не имеет ни одной реализации, `SVEqualizer` нигде не конструируется, `DefaultEqualizerConfig` только сериализует пресеты в файл `eq_config`. Полос/частот в APK нет вообще — их предполагалось перечислять рантаймом у несуществующего прокси.

В `libandroidappmusic.so` EQ-символов нет (там только кодековая математика: MDCT/QMF-фильтрбанки AAC, SBR, ALAC).

### Sound Check (нормализация громкости) — есть, целиком в Java

Источник числа — **внутри ассета**, не в JSON плеера. Два пути, оба в форке экстрактора:

- **Бокс `ludt`** MP4 (`AtomParsers.parseLudt`): `tlou` (трек) и `alou` (альбом), декод `fixedPointFromBits(v,8,2) − 57.75` для methodDefinition 1–5. Берётся только `methodDefinition==1 && measurementSystem==3` (BS.1770 program loudness). Альбомная громкость парсится, но не используется.
- **Фоллбэк `iTunNORM`** (`LoudnessInfoHolder` — класс Apple, не сток): 10 hex-слов, пересчёт replay-gain-подобных величин в LUFS с защитой от клиппинга (`gain·peak < 29200`).

Значение едет в добавленном поле `Format.loudness` (`UNSET = NaN`). Применение — `SVAudioRendererV2`:

```
gain = 10^((−16 − loudness) / 20),  клампится в [0, 1]
audioSink.setVolume(pendingVolume × gain)
```

Целевая громкость **−16 LUFS**, и гейн **только ослабляет** — тихий трек никогда не поднимается. Пересчитывается на каждом треке и при переключении тумблера (кастомное `PlayerMessage` 10001). Настройка `key_volume_normalization_enabled`, **по умолчанию включена**.

Две существенные детали:
- Sound Check есть **только на нативном рендерере**. На `SVMediaCodecAudioRenderer` (то есть на Atmos) нормализации нет вообще.
- В форке `MediaCodecAudioRenderer` добавлен флаг `shouldSuppressDrc`, который выставляет `aac-target-ref-level = −1` и `aac-drc-effect-type = −1`, чтобы встроенный DRC AAC-декодера не конфликтовал с их нормализацией.

**`FeatureParams.loudnessCurve` к нормализации отношения не имеет.** Это JavaCPP-биндинг к отдельной либе `seamless-composer`, кривая приходит из Media API запросом `extend=loudnessCurve,fades` и используется только сведением. Полностью разные механизмы с разными источниками данных.

### Вокальное ослабление (Sing) — выключено

`VocalsAudioProcessor` + `PremResampleAudioProcessor` — пара `AudioProcessor` в цепочке `DefaultAudioSink` (то есть после декода, до `AudioTrack`, в Java). Ресемпл в 44100, выход в `ENCODING_PCM_FLOAT`, деинтерливинг стерео в два планарных float-буфера — и на этом всё: вызова инференса нет, JNI нет, TFLite нет. Загрузчик модели из `assets/` есть, потребителя нет.

Выключено двумя независимыми предохранителями: `FORCE_DISABLE = true` в процессоре и `VocalAttenuationStateProvider.isDeviceSupported()`, возвращающий литеральный `false`. Из-за второго `createAudioProcessors()` возвращает **пустой массив** — то есть в проде в `DefaultAudioSink` нет вообще ни одного кастомного процессора.

Разрешение на трек при этом серверное: `PlayerMediaItem.isVocalAttenuationAllowed()` (в нативке зеркалится как `SVItem::isVocalAttenuationAllowed`).

## 4. Пространственное аудио

**Собственного бинаурального рендера нет.** «Spatial Audio» на Android = выбрать в HLS-мастере вариант E-AC-3 JOC и отдать его платформенному Dolby-декодеру.

- **Определение поддержки устройства** — ровно одна проверка: перебрать `new MediaCodecList(0).getCodecInfos()` и найти декодер с MIME `audio/eac3-joc` (`out3/.../music/util/AudioDeviceCapabilities.java`). Ни `android.media.Spatializer`, ни системных фич, ни AC-4, ни head-tracking во всём приложении нет.
- **Наушники не проверяются вообще.** Ни `AudioDeviceCallback`, ни `AudioDeviceInfo`, ни `ACTION_HEADSET_PLUG` в модуле воспроизведения нет. Тип вывода на выбор Atmos не влияет.
- **Настройка** `key_dolby_atmos_state`: `AUTOMATIC / ALWAYS_ON / OFF`, по умолчанию `OFF`.
- **Серверные гейты:** содержимое помечается битовой маской `audioTraits` (`atmos=1, surround=2, lossless=4, lossy-stereo=8, hi-res-lossless=16, spatial=32` — заметьте, `spatial` и `atmos` разные биты). Глобальный кил-свитч — `BagConfig.isEnhancedAudioEnabled()`, который вычисляется как «непустой `hlsServerUrl` или `hlsPrefetchKeyUrl`»: обнулив эти URL в bag, Apple выключает лосслесс и Atmos разом.
- **Отдельный URL не запрашивается.** Один HLS-мастер, вариант выбирается клиентом: `getDolbyAtmosVariantIndex()` берёт максимальный битрейт среди вариантов с `codecs == "ec-3"`, иначе падает на лестницу ALAC/AAC в том же плейлисте.

Показательная деталь: их собственный парсер мастер-плейлиста (`ExoPlayerHlsParser`) **явно выбрасывает** варианты, у которых в `audioGroupId` есть подстрока `binaural` или `downmix`. То есть сервер такие рендиции публикует, а Android-клиент их сознательно игнорирует — это прямое подтверждение, что бинаурального пути на клиенте нет.

Нестыковка, которую стоит иметь в виду: `PlayerTrackSelector.isDolbyAtmosEnabled()` принимает и `ALWAYS_ON`, и `AUTOMATIC`, но вызывающий его `isAtmosEnabled()` и `PlayerHlsDataSourceFactory` жёстко требуют `ALWAYS_ON`. Фактически `AUTOMATIC` влияет на загрузки и на подсказку варианта, но не на живой выбор трека.

## 5. Кэш и загрузка

**Основной кэш — свой, не `SimpleCache`.** `SimpleCache` (100 МБ LRU) используется только вспомогательным `ExoSimplePlayer` (превью/видео), к музыке отношения не имеет.

- **`MediaAssetCache`**: каталог `playback_assets/`, ключ **`<storeId>_<protectionType>_<flavor>`** — идентичность ассета, а не URL, поэтому переподписанный CDN-URL всё равно попадает в кэш. Два файла на запись: `.nfo` (сериализованный `MediaAssetInfo` с `fileSize`) и сам медиафайл. «Полностью закэширован» = длина файла равна `fileSize` из `.nfo`.
- **Вытеснение**: `android.util.LruCache` с размером в килобайтах, по умолчанию **200 МБ** (`asset_cache_size`; в UI задаётся в МиБ). Порядок LRU переживает перезапуск процесса — при каждом обращении обновляется `lastModified`, а при старте `scanAssets()` перезаполняет LruCache по возрастанию mtime.
- **На диске лежит шифротекст.** Расшифровка — посэмпльная, непосредственно перед кодеком (`ProtectedSampleStream` вешает `AppCryptoInfo`, дальше либо нативный `SVDecryptor`, либо `DecoderInputBufferProcessor` для пути MediaCodec). Отдельного шифрования кэша нет и не нужно.
- **Скачанные треки в этот кэш не попадают вообще** — это `file://`-ассеты, читаются напрямую.
- **Механика записи нестандартная**: не read-through `CacheDataSource`, а «писатель впереди читателя». Один поток загрузки (`ThreadPoolExecutor(0,1,90s)`, приоритет 9) льёт файл чанками по 128 КБ, а плеер читает **тот же файл** и блокируется на `ConditionVariable`, пока записанных байт не станет больше позиции чтения. При отмене писателя читатель прозрачно переоткрывает HTTP с текущего смещения.
- **`FastPlaybackCache`** (`playback_fast/`): запоминает per-(id, flavor) URL ассета, его размер и mini-SINF, чтобы стартовать не дожидаясь round-trip за ассетом.

### Предзагрузка следующего трека

Apple добавила в **интерфейсы** ExoPlayer третий слот `MediaPeriod` — «caching», рядом с playing/loading/reading (`MediaPeriodQueue.caching`, `LoadControl.shouldPrepareNextPeriodForCaching`, `MediaPeriod.setCachePeriod`, `DataSource.isReadingFromNetwork`). Глубина ровно 1 трек, сбрасывается на любом seek и при реальном продвижении очереди.

Ключевое правило (`PlayerLoadControl`): предзагрузка стартует **только когда текущий трек полностью лежит на диске** — то есть `!mediaPeriod.isReadingFromNetwork()`. Это их замена приоритизации: `PriorityTaskManager`/`PriorityDataSource` в форке есть, но **не используются ни одним классом Apple**. Вместо приоритетов — последовательность плюс пониженный приоритет потока загрузчика.

Прогревается HTTP/дисковый кэш, экстрактор, track groups и DRM-ключ. Декодер не аллоцируется. Вся телеметрия на кэширующем периоде подавлена.

Буферы (`PlayerLoadControl`, не дефолты `DefaultLoadControl`): **min 50000 / max 50000 / старт 5000 / после ребуфера 10000 мс** — оба порога старта вдвое больше стоковых (2500/5000). Для HLS дополнительно разрешено грузить до 300 секунд вперёд через до 2 лишних периодов.

### Поведение при обрыве сети

- HTTP-таймауты агрессивнее стока: **connect 4000 мс, read 2000 мс** (сток 8000/8000). Только для Shoutcast read поднят до 10000.
- Ретраи стоковые: 3 попытки, линейный бэкофф `min((n−1)·1000, 5000)`, блэклист 60 с. Добавлен блэклист на **HTTP 416** (в стоке только 404/410).
- Нерепрайабельные сразу: `CorruptedFileException`, `PersistentKeyLoaderException`, а на прогрессивном пути `416`.
- Основная логика восстановления — в `ExoMediaPlayer.onPlayerError`: `BehindLiveWindow` → тихое полное `preparePlayer()`; `CorruptedFileException` и `416` → **выкинуть отравленную запись кэша**; `PersistentKeyLoaderException` → форсировать новый lease. По умолчанию сетевая ошибка приводит к **скипу трека**, с исключениями (не скипать при отказе в сети; не скипать в Automotive; не скипать неподписанным).
- **Адаптивного понижения битрейта внутри трека нет** — флейвор зафиксирован на период.
- **Playback lease** — серверная аренда сессии (`BEGIN`/`DOWNLOAD`/`RENEW`), автообновление **за 90 с** до истечения, отдельный пул потоков, чтобы обновление аренды не встало в очередь за загрузкой ассета.

### Выбор качества

Решается дважды. Сервер отдаёт URL под запрошенный «флейвор» (`LWHQ`/`HQ`/`LW`/`SLW` — 320/320/128/64 кбит, списки предпочтений разные для Wi-Fi / сотовой / эконома), а внутри HLS-мастера клиент сам выбирает вариант по кодеку и порогам (`>256 кбит` = high quality AAC, `44100..48000` = lossless, `>48000` = hi-res). На сотовой качество жёстко клампится максимум до `HIGH_QUALITY`.

## 6. Gapless

Механика **стоковая ExoPlayer**, но **точка переключения — своя**.

- Данные о задержках кодека берутся **только из контейнера**, серверных полей нет: атом `iTunSMPB` (`GaplessInfoHolder`) либо edit list `elst` (конвертируется в delay/padding, если правка укладывается в первые/последние 4 сэмпла). Едут как `Format.encoderDelay` / `encoderPadding`.
- Добавлен фоллбэк: если для `audio/mp4a-latm` задержка вышла нулевой, подставляется **2112** — каноническая величина прайминга AAC-LC.
- Стоковая часть: `TrimmingAudioProcessor` срезает голову и придерживает хвост; `DefaultAudioSink.canReuseAudioTrack()` сравнивает только encoding/sampleRate/channelConfig и при совпадении **подменяет конфигурацию без `flush()`**, то есть `AudioTrack` не пересоздаётся.
- Своё: сток реконфигурирует синк на `onInputFormatChanged`. Здесь же на границе потока в декодер лишь ставится **внутриполосная команда** `enqueueAudioConfigChange`, которая встаёт в очередь **за всеми ещё не декодированными сэмплами текущего трека**. Реальной границей служат флаги `outputStreamChanged` / `isEOS` **на выходном буфере нативного декодера**: по ним вызывается `playToEndOfStream()` (сбрасывает придержанный хвост-padding ровно в стыке) и конфигурируется синк под следующий трек. При совпадении формата — переключение без разрыва; при смене частоты или числа каналов происходит `flush()` и слышимый разрыв.
- Функция сравнения форматов для «мягкой» реконфигурации намеренно **игнорирует** `encoderDelay`/`encoderPadding`/битрейт/loudness — иначе каждый трек уходил бы на полный тяжёлый путь.
- **Gapless или кроссфейд решается по метаданным альбома**, а не по контейнеру: тот же `albumSubscriptionStoreId`, тот же номер диска и `trackNumber + 1` → это соседние треки альбома, кроссфейд для них запрещается и переход идёт по gapless-пути на одном рендерере.

## 7. Список приёмов с пометками

### Повторимо на media3 1.5.1

| Приём | Комментарий |
|---|---|
| **Sound Check целиком** | Самое ценное и самое дешёвое. media3 **не парсит `ludt`** и в `Format` нет поля loudness — нужно добавить разбор `tlou`/`alou` в `AtomParsers` + поле в `Format` (~100 строк), фоллбэк на `iTunNORM`, и `gain = 10^((−16−L)/20)` с клампом в `[0,1]` на `AudioSink.setVolume`. Никакой нативки. |
| **Подавление DRC AAC-декодера** | `aac-target-ref-level = −1`, `aac-drc-effect-type = −1` в `MediaFormat`. Пара строк, но без этого встроенный DRC конфликтует с нормализацией. |
| **EQ через системную панель + копирование на второй session id** | Прямо наш случай: у нас тоже два аудио-рендерера из-за кроссфейда, значит две сессии. Приём «прочитать `Equalizer.Settings` у первой, записать во вторую» переносится один в один. |
| **Выбор Atmos-варианта** | Проба `MediaCodecList` на `audio/eac3-joc` + выбор варианта с `codecs=="ec-3"` по максимальному битрейту. Тривиально. |
| **Правило gapless-vs-crossfade по номеру трека альбома** | Чистая логика поверх метаданных, ложится на уже сделанный кроссфейд. |
| **Фоллбэк 2112 для AAC без gapless-метаданных** | Одна константа, заметно улучшает стыки на плохо размеченных ассетах. |
| **Предзагрузка следующего трека** | Их «caching period» — самодельный аналог того, что в media3 1.5.1 **уже есть штатно**: `DefaultPreloadManager` + `PreloadMediaSource` со стадиями `STAGE_SOURCE_PREPARED / TRACKS_SELECTED / LOADED_FOR_DURATION_MS`. Форк править не нужно. |
| **Правило «не грузить следующий, пока текущий не докачан»** | Их главная защита от голодания. Ложится на `TargetPreloadStatusControl`. |
| **Кэш по идентичности ассета, а не по URL** | `CacheDataSource.setCacheKeyFactory` / `setCustomCacheKey` — штатно и проще их «писателя впереди читателя». Их схему копировать не надо, она хуже. |
| **Числовые настройки** | Буферы 50000/50000/5000/10000, HTTP connect 4000 / read 2000, блэклист на 416, окно оценки полосы 5000 вместо 2000. |
| **Кэш URL+размера ассета для мгновенного старта** | Аналог `FastPlaybackCache` без DRM-части. |
| **Восстановление после ошибок** | Удалять запись кэша при `416`/битом файле, скип трека по сетевой ошибке с исключениями для Automotive. |

### Требует нативного кода

| Приём | Почему |
|---|---|
| Свой декодер AAC/SBR/PS + ALAC | Полноценные кодеки в C++. **Нам не нужно** — им это понадобилось только чтобы расшифровывать FairPlay посэмпльно внутри цикла декодирования. |
| Zero-copy пул из 8 direct ByteBuffer, регистрируемых в декодере | Нативка пишет PCM прямо в память, аллоцированную Java. Требует своего декодера. |
| Привязка переключения потока к флагу `outputStreamChanged` на выходном буфере | Самая суть их gapless-точности. Без своего декодера этого флага взять неоткуда — но media3 и так делает gapless корректно на `onInputFormatChanged`, так что это не потеря. |
| Посэмпльная расшифровка перед кодеком | FairPlay/FootHill. |
| Вокальная сепарация | Модели нет вообще ни в одной из библиотек. |

### Зависит от их сервера

- `audioTraits` в Media API (`atmos`/`spatial`/`lossless`/`hi-res-lossless`/`lossy-stereo`/`surround`) — что вообще доступно для трека.
- `BagConfig` с `hlsServerUrl`/`hlsPrefetchKeyUrl` — глобальный кил-свитч всего «enhanced audio».
- HLS-мастер с вариантами `ec-3`/`alac`/`aac` (плюс binaural/downmix, которые клиент выкидывает).
- `loudnessCurve` и `fades` через `extend=loudnessCurve,fades` — для сведения, не для нормализации.
- Playback lease (`BEGIN`/`DOWNLOAD`/`RENEW`) и FairPlay-ключи.
- `ludt`/`iTunNORM` — приходят **внутри самого ассета**, то есть зависят от их энкодинг-пайплайна.
- Разрешение вокального ослабления на трек.

## 8. Найденные баги и нестыковки у них

Полезно знать, чтобы не копировать вслепую:

- **`DownloadTimeoutException` никто не бросает.** Класс есть, ветка в `Loader`, превращающая таймаут в тихий cancel вместо ошибки, есть — а источника исключения нет ни в одном dex. Похоже, мёртвый рудимент.
- **Фильтр флейворов по полосе не работает**: таблица битрейтов в кбит/с, а `getBitrateEstimate()` возвращает бит/с — на любом соединении быстрее ~5 кбит ничего не отфильтровывается.
- **`setCachePeriod(boolean)` игнорирует аргумент** и всегда пишет `true`. Пока безвредно, но это заложенная мина.
- **Sound Check не работает на пути MediaCodec**, то есть при Atmos нормализации громкости нет.
- **`AUTOMATIC` для Atmos непоследователен** — три места гейтят его по-разному.
- **Возможное двойное срезание прайминга**: нативный декодер логирует свои `encoderDelayInBytes`/`encoderPaddingInBytes` и умеет выводить прайминг из `esds`, при этом Java эти числа в него не передаёт, а на gapless-ветке `flush()` не вызывается — значит `TrimmingAudioProcessor` не перевзводится под следующий трек. Похоже, реальное срезание делает нативка, а Java-слой второй, более грубый. Точно подтвердить можно только дизассемблированием.

## 9. Главный вывод по применимости

Ничего из по-настоящему ценного в их аудио-обработке не требует нативного кода. Их нативная библиотека нужна исключительно ради FairPlay — кодеки там появились как побочный эффект того, что расшифровка обязана происходить внутри цикла декодирования. Собственного DSP у них нет вообще: эквалайзер отдан ОС, пространственное аудио — платформенному Dolby-декодеру, вокальное ослабление выключено, а единственная реальная обработка сигнала — это один скалярный гейн на `setVolume`.

Практически для нашего форка media3 1.5.1 самое окупаемое — это **Sound Check** (парсинг `ludt` + гейн, плюс подавление DRC), **копирование настроек системного EQ на оба session id** (у нас как раз два рендерера) и **правило предзагрузки «следующий трек только после того, как текущий скачан целиком»** поверх штатного `DefaultPreloadManager`. Gapless в media3 уже сделан лучше, чем в их форке ExoPlayer 2.11.2 — оттуда стоит взять только константу 2112 и правило выбора между gapless и кроссфейдом по номеру трека альбома.
