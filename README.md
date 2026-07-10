# 🎵 LiquidMusicGlass — `11.07 stable`

An Apple‑Music‑style player built around an **infinite personalized Wave**, a full **Yandex Music** section, native local playback, and an on‑device **ML AutoMix** DJ engine — wrapped in an Apple *Liquid Glass* interface.

> This release adds a lot and reworks a lot. The Wave was rebuilt for a fast start and truly endless playback, and Yandex Music grew from a token box into a complete in‑app music section.

---

## ✨ What's new in this release

- 🌊 **Wave, rebuilt** — near‑instant start and **guaranteed infinite** playback (personal wave *and* radio‑by‑track), with much tighter personalization.
- 🟡 **Yandex Music — a full section**, not just an import: its own bottom bar, sign‑in, search, liked, playlists, artist/album pages, stations, FLAC and lyrics.
- 🎛️ **Player polish** — brighter album‑art background, morphing play/pause, springy bottom‑bar and thumbs‑up/down animations.

---

## 🌊 Wave — endless personal radio

- **Fast start.** Music begins from a small one‑shot batch while the full session warms up in the background — no more long wait before the first track.
- **Never runs dry.** Both the personal Wave and a track‑seeded station keep refilling forever; the queue is topped up before it empties.
- **Learns fast.** 👍/👎 on a track or artist is mirrored into the Wave immediately — a disliked track/artist drops out of the *next* batch, not several tracks later.
- **Fewer irrelevant tracks.** Diversity tuning caps repeated artists per batch so the stream stays fresh and on‑taste.
- **Moods & genres.** Start the Wave from a mood or genre tile.

## 🟡 Yandex Music — built in

A fully self‑contained section with its own yellow bottom bar (Wave · Search · Liked · Playlists · Account):

- **Sign in** inside the app; the token is stored encrypted on‑device.
- **My Wave** — personal rotor station that learns, plus **radio by any track** and **genre / mood stations**.
- **Liked tracks**, **your playlists** (browse, stream, or download a whole playlist).
- **Artist & album pages**, and **multi‑type search** across tracks, artists, albums and playlists.
- **Discovery** — charts and new releases.
- **Two‑way likes** — the heart writes back to your Yandex account.
- **Stream without downloading**, or download; **FLAC lossless** with a stream/download **quality selector**.
- **Synced lyrics** via LRCLIB.

## 🎧 Local library & native playback

- Browse **On this device** by artists, albums and tracks with fast search.
- Native **JUCE** audio engine for local files — clean, gapless‑feeling handoff.
- **Tag editor** for fixing titles, artists and artwork.

## 🤖 AutoMix — on‑device ML DJ

- Beat‑matched, DJ‑style transitions between tracks using an **on‑device TFLite model** (siamese CNN) plus classic analysis — **BPM**, **musical key**, and **energy** detection.
- Crossfade, entry‑offset and transition‑type are predicted per pair for smooth, club‑like mixing.

## 🎛️ Audio FX & Equalizer

- Multiband **equalizer** with bass and DJ‑style effects.
- Real‑time processing on top of the playback engine.

## 📝 Playlists & import

- Create and manage playlists.
- **Import** playlists from **Yandex Music** and **Apple Music** by link, with progress tracking.

## 🎤 Lyrics

- Time‑synced lyrics that scroll with the track.
- Publish/correct lyrics (LRC) from inside the app.

## 📊 History

- Listening history so you can jump back to what you played.

## 🪟 Design — Apple *Liquid Glass*

- Translucent, blurred glass surfaces, springy motion, and album‑art‑driven gradients.
- Dynamic color: the player background is extracted from the current cover.

## ⚙️ Settings & updates

- Playback, audio and interface settings in one place.
- Built‑in update check.

---

## 📱 Requirements

- **Android 10 (API 29) or newer.**
- **arm64‑v8a** device (64‑bit ARM). The native engines are built for arm64 only — 32‑bit and x86 emulators are not supported.
- Some sources (Yandex Music) require signing in with your own account.

## 🛠️ Built with

- **Kotlin** + **Jetpack Compose** (Material 3, custom *Liquid Glass* backdrop)
- **Media3 / ExoPlayer** + a custom streaming data source, foreground playback service and media session
- **JUCE** (native C++) for local playback; **TensorFlow Lite** for AutoMix
- **OkHttp / Retrofit / Ktor** + Kotlinx Serialization
- **EncryptedSharedPreferences** for on‑device credential storage

---

*LiquidMusicGlass — `versionName 11.07 stable` · `versionCode 20260711`.*
