# My Wave / YouTube Music Integration — Current Architecture

## Overview

LiquidMusicGlass supports two music sources ("camps"):
- **ICM** (Apple Music catalog via ICM API) — requires subscription for full features
- **YouTube Music** (via InnerTube API) — free, no subscription required

The camp selection is managed by `FeatureAccessManager` and persisted across app restarts.

---

## Core Components

### 1. FeatureAccessManager (`camp/FeatureAccessManager.kt`)

**Purpose**: Singleton that manages the active music camp and calculates capabilities.

**Key State**:
- `_currentCamp: StateFlow<MusicCamp>` — currently selected camp (ICM or Youtube)
- `_capabilities: StateFlow<Capabilities>` — feature flags based on camp + subscription

**Persistence**:
- Uses SharedPreferences (`liquid_camp_prefs`)
- Key: `selected_camp` — stores `"icm"` or `"youtube"`
- Loaded on init via `loadCamp()`, saved on `selectCamp()`

**Camp Selection**:
```kotlin
fun selectCamp(camp: MusicCamp) {
    _currentCamp.value = camp
    saveCamp(camp)  // → SharedPreferences
    recalc(subscription)  // → updates capabilities
}
```

**Capabilities Matrix**:
| Feature | ICM Free | ICM Premium | YouTube |
|---------|----------|-------------|---------|
| Download | ❌ | ✅ | ✅ |
| Hi-Res | ❌ | ✅ | ❌ |
| Lossless | ❌ | ✅ | ❌ |
| My Wave | ❌ | ✅ | ✅ |
| Full Catalog | ❌ | ✅ | ✅ |
| Background | ✅ | ✅ | ✅ |
| Unlimited Skips | ❌ | ✅ | ✅ |

---

### 2. MusicCamp (`camp/MusicCamp.kt`)

**Sealed class** with two implementations:
- `MusicCamp.Icm` — id="icm", displayName="Apple Music"
- `MusicCamp.Youtube` — id="youtube", displayName="YouTube Music"

---

### 3. HomeScreen (`ui/screens/HomeScreen.kt`)

**Current Behavior**:
1. Loads ICM home content via `HomeViewModel.loadHomeContent()`
2. Shows sections: Moods → Popular → Banners → New Releases → Charts → Recommendations → Recently Played → Favorites
3. **YouTube Music Banner**: When `currentCamp is MusicCamp.Youtube`, shows a banner "Open YouTube Music" that navigates to `YouTubeSearchScreen`
4. **My Wave / Mood Stations**: When user taps a mood card:
   - If camp is Youtube + mood is "my_wave": builds YT radio queue from search seed
   - Otherwise: uses ICM `getWaveNext()` or fallback search

**Key Issue**: Even when YT Music camp is selected, HomeScreen still shows ICM content. The only YT-specific UI is the banner.

---

### 4. SearchScreen (`ui/screens/SearchScreen.kt`)

**Current Behavior**:
- Always shows ICM search (Apple Music / VK / All sources)
- Source selector chips at top
- Results: Artists (horizontal) → Albums (horizontal) → Songs (list)
- History saved to `search_history` SharedPreferences

**No YouTube Music integration** — completely separate from camp selection.

---

### 5. YouTubeSearchScreen (`ui/screens/youtube/YouTubeSearchScreen.kt`)

**Current Behavior**:
- Separate full-screen overlay (not a tab)
- Opened from HomeScreen banner or other navigation
- Has its own search field, history (`yt_search_history` prefs)
- Calls `YouTubeMusicRepository.search()` with `YtSearchFilter.SONGS`
- Results show as list with thumbnail, title, artist
- Supports "Play" and "Play Radio" actions

**Navigation**: Opened as overlay via `youtubeSearchOpen` state in `AppRoot.kt`

---

### 6. YouTubeMusicRepository (`api/youtube/YouTubeMusicRepository.kt`)

**InnerTube API Client**:
- Endpoint: `https://music.youtube.com/youtubei/v1/`
- Methods: `search()`, `getAudioStream()`, `getRadioQueue()`, `getRadioContinuation()`
- Clients tried in order: ANDROID_MUSIC → IOS → TVHTML5

**Search Flow**:
```
search(query, filter) → POST /search → YtSearchResponse → parseSearchResults() → List<YtTrack>
```

**Parsing Logic** (`parseSearchResults`):
```kotlin
val shelf = response.contents?.tabbedSearchResultsRenderer?.tabs?.firstOrNull()
    ?.tabRenderer?.content?.sectionListRenderer?.contents?.lastOrNull()
    ?.musicShelfRenderer

return shelf?.contents?.mapNotNull { item ->
    item.musicResponsiveListItemRenderer?.let { parseMusicResponsiveListItem(it) }
} ?: emptyList()
```

**Known Issue**: The parser uses `.lastOrNull()` to find the music shelf, but the correct shelf might not be the last one. Also `videoId` extraction may fail if the track uses a different endpoint structure.

---

### 7. AppRoot (`ui/AppRoot.kt`)

**Navigation Structure**:
- Bottom bar: Home (0) | Search (1) | Playlists (2) | Profile (3)
- Overlay screens: AlbumDetail, ArtistDetail, Equalizer, PlaylistDetail, Settings, Auth, Profile, **YouTubeSearch**

**State Management**:
- `selectedIndex: Int` — current bottom tab
- `youtubeSearchOpen: Boolean` — controls YouTubeSearchScreen overlay
- `AppSettings.lastScreenIndex` — persists last selected tab

---

## Data Flow

### Camp Selection Flow
```
User taps camp in Settings/Profile
  → CampSelectorScreen.onCampChanged()
  → FeatureAccessManager.selectCamp()
  → Save to SharedPreferences
  → Recalculate capabilities
  → Emit new _currentCamp value
  → HomeScreen recomposes (reads currentCamp)
  → Shows/hides YouTubeMusicBanner
```

### Home Content Flow (ICM)
```
HomeScreen LaunchedEffect(isLoggedIn)
  → HomeViewModel.loadHomeContent()
  → Load from cache (immediate)
  → Fetch from ICM API (background)
  → Save to cache
  → Emit _homeContent
  → HomeScreen displays blocks
```

### My Wave Flow (ICM)
```
User taps "My Wave" mood
  → playMoodStation("my_wave")
  → WaveRepository.buildWaveQueue()
  → PlayerController.playFromList()
  → Auto-refill via getWaveNext()
```

### My Wave Flow (YouTube)
```
User taps "My Wave" mood (YT camp)
  → playMoodStation("my_wave")
  → YouTubeMusicRepository.search(seedQuery)
  → YouTubeMusicRepository.getRadioQueue(seedTrack.videoId)
  → Resolve audio streams
  → PlayerController.playFromList(autoRefillType="YOUTUBE_RADIO")
```

---

## Files Involved

| File | Purpose |
|------|---------|
| `camp/FeatureAccessManager.kt` | Camp management, persistence, capabilities |
| `camp/MusicCamp.kt` | Camp data classes |
| `ui/screens/HomeScreen.kt` | Main screen with ICM content + moods |
| `ui/viewmodel/HomeViewModel.kt` | Home content loading, wave queue building |
| `ui/screens/SearchScreen.kt` | ICM search UI |
| `ui/viewmodel/SearchViewModel.kt` | ICM search logic |
| `ui/screens/youtube/YouTubeSearchScreen.kt` | YT Music search overlay |
| `api/youtube/YouTubeMusicRepository.kt` | YT Music API client |
| `api/youtube/models/response/*.kt` | YT API response models |
| `api/youtube/models/body/*.kt` | YT API request bodies |
| `ui/AppRoot.kt` | Main navigation, overlay management |
| `ui/navigation/BottomBar.kt` | Bottom navigation bar |

---

## Identified Issues

1. **HomeScreen doesn't adapt to camp**: Always shows ICM content regardless of selected camp
2. **YouTubeMusicBanner is a workaround**: Instead of integrating YT into Home, it just opens a separate search screen
3. **Search doesn't respect camp**: SearchScreen always searches ICM, even when YT camp is active
4. **YT Search parsing may be broken**: `.lastOrNull()` approach for finding search results shelf is fragile
5. **No YT home recommendations**: No "Quick Picks", "Trending", or personalized YT home content

---

## Required Changes (User Request)

1. **Remove YouTubeMusicBanner** from HomeScreen
2. **YT Music Home**: When YT camp is selected, HomeScreen shows YT Music recommendations (quick picks, trending, moods)
3. **Unified Search**: SearchScreen should search YT Music when YT camp is active, ICM when ICM camp is active
4. **Persistence**: Camp selection already persists — verify it works correctly
5. **Fix YT Search**: Ensure `YouTubeMusicRepository.search()` returns results correctly
