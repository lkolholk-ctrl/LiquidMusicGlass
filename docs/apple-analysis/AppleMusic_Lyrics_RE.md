# Apple Music (Android) — как устроена «плавающая» лирика

Реверс по бинарям из APK: 25 нативных `.so` + 4 `classes*.dex`.
Инструменты: `strings` / `nm` / `readelf` (нативка) + `jadx 1.5.6` (dex).
**Кода Apple не берём — только архитектура и константы для собственной реализации.**

---

## 1. Разделение слоёв

| Что | Где | Язык |
|-----|-----|------|
| Парсинг TTML, модель, тайминг-движок | `libandroidappmusic.so` (нативный C++) | C++ |
| Связка нативки с Java | JavaCPP (`com.apple.android.music.ttml.javanative.*`) | JNI |
| UI, анимация, отрисовка | `classes2.dex` → `PlayerLyricsViewFragment` | Kotlin/Java + Android Views |

Вывод: **в нативе НЕТ анимации** — там только парсинг TTML и движок событий.
Вся визуальщина (заливка/пружина/свечение) — в Kotlin-слое на классических
`View` + `ValueAnimator`/`ObjectAnimator` (не Compose, не OpenGL — Canvas).
Шейдеров в `.so` нет (проверено `strings`).

---

## 2. Нативный кор (libandroidappmusic.so)

Исходники (пути уцелели в бинаре):
```
SV_MediaPlayback/src/fuse/cpp/Lyrics/
  TTMLParser.cpp              парсер TTML (word-synced)
  StaticLyricsParser.cpp      парсер статичной лирики
  LyricsController.cpp        оркестратор
  model/SongInfo.cpp, LyricsSection.cpp, LyricsLine.cpp
  timingengine/SongInfoTimeProcessor.cpp   движок тайминга
```

Модель данных — на КАЖДУЮ строку 4 параллельных трека слов:
- основной вокал (`getWords`)
- бэк-вокал (`getBackgroundWords`)
- произношение/романизация для CJK (`getPronunciationWords`)
- перевод (`getTranslationLineText`, `hasTranslation`, `getTranslationLanguages`,
  `isTranslationAutomaticallyCreated`)
- у каждого — свой HTML-рендер строки (`getHtmlLineText`,
  `getHtmlBackgroundVocalsLineText`, `getHtmlPronunciationLineText`, …)
- у слова: `getWordId`, `getBegin`, `getEnd`, `getDuration`, `isWhitespace`.

---

## 3. Движок тайминга — событийный, НЕ поллинг

`SongInfoTimeProcessor.processEvents(...)`:
```
long processEvents(
    SongInfoPtr songInfo,
    long songPositionInMs,
    q<Long, LyricsLineVector, Long> lineCallback,       // строка активна
    q<Long, LyricsWordVector, Long> wordCallback,       // слово активно
    q<Long, LyricsWordVector, Long> bgWordCallback,     // бэк-вокал
    q<Long, LyricsWordVector, Long> prWordCallback,     // произношение
    q<Long, LyricsWordVector, Long> prBgWordCallback    // произн. бэк-вокала
)  // → ВОЗВРАЩАЕТ deadline: время следующего события
```
Ключевая идея: не опрашивают каждый кадр, а зовут `processEvents(pos)` → он
дёргает нужные колбэки и **возвращает deadline** (когда наступит следующее
слово/строка). Планировщик ждёт до deadline и зовёт снова. Дёшево по CPU.

Колбэки приходят в `PlayerLyricsViewFragment` (внутренние классы f/g/n/p/r).

---

## 4. Анимация (PlayerLyricsViewFragment, classes2.dex) — КОНСТАНТЫ

### 4.1. Фирменная easing-кривая (используется ВЕЗДЕ)
```java
static final PathInterpolator INTERP = new PathInterpolator(0.75f, 0.0f, 0.25f, 1.0f);
// cubic-bezier(0.75, 0.0, 0.25, 1.0)
```
Резкая ease-in-out: медленный старт → быстрый разгон → мягкое торможение.
Применяется к заливке слова, переходам строки, фейдам «Sing»/vocal-attenuation.

### 4.2. Заливка слова — per-word ARGB, а не градиент-вайп
На каждое активное слово: `notifyWordHighlight(lineId, wordId, word.getDuration(), isBg)`.
Цвет слова анимируется `ArgbEvaluator.evaluate(fraction, dimColor, brightColor)`
за `word.duration`, параллельно `FloatEvaluator` для доп-параметра (glow/scale/alpha).
`ValueAnimator.ofFloat(0f, 1f)` + `setInterpolator(INTERP)`.

### 4.3. Проигрыш / waiting — точная формула
```java
float gap = clamp(nextLine.getBegin() - line.getEnd(), 200f, 750f);  // мс
int offset = round( lerp(480f, 750f, (gap - 200f) / 550f) );          // px
```
Зазор между строками зажимается в **[200, 750] мс**; из него линейно
маппится «выезд» индикатора-отсчёта в **[480, 750] px**. Короткий зазор —
скромно, длинный — заметный обратный отсчёт.

### 4.4. Свечение активной строки
```java
textView.setShadowLayer(radiusFromDimen, 0f, 0f, argb(alpha·255, 255,255,255));
```
Обычная тень-glow (белая, радиус из ресурса), не блюр-эффект.

### 4.5. Прочие тайминги (тот же INTERP)
- fade «Sing»-подписи: `ofFloat(0f, 2.5f)`, delay 1000, duration 2000.
- alpha in/out контролов: `ObjectAnimator ALPHA`, duration 850–1000, delay 1000.

---

## 5. DRM / шифрование (попутно)

- Нативные `libCoreFP` / `libCoreLSKD` / `libFPDIFor3P` / `libCoreADI` — это
  **FairPlay** (DRM полных треков). К лирике/клипам отношения не имеют.
- В либах лирики маркеров DRM/шейдеров нет.

---

## 6. Что переносим в наш Compose

У нас уже есть: пословная заливка (`LyricLineSweep`), spring-scale строки,
glow-тень, waiting-точки. Не хватало ровно апловских деталей:

1. **Easing**: `CubicBezierEasing(0.75f, 0f, 0.25f, 1f)` на прогресс заливки
   слова (вместо линейного/spring внутри слова).
2. **Per-word «расцветание»**: заливка идёт по каждому слову за его `duration`
   этой кривой (у нас уже word-level — просто прогонять прогресс через easing).
3. **Waiting**: длительность/заметность точек-отсчёта из `clamp(gap, 200, 750)`.
4. Glow-тень оставить, pull через ту же кривую.

Итог: 3 точечные правки визуала — и «ощущение» становится апловским, без
копирования их кода (техника Canvas+ValueAnimator у них → Brush+Easing у нас).
