package com.liquidmusicglass.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.liquidmusicglass.api.icm.IcmRepository
import com.liquidmusicglass.api.icm.IcmWaveOnboardingArtist
import com.liquidmusicglass.api.icm.IcmWaveOnboardingArtistSave
import com.liquidmusicglass.engine.AppSettings
import com.liquidmusicglass.ui.theme.LiquidTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

private val AppleRed = Color(0xFFFC3C44)
private const val MinSelection = 3

private val GenresList = listOf(
    "House",
    "Electronic House",
    "Electro House",
    "Deep House",
    "Techno",
    "Trance",
    "Drum & Bass",
    "Dubstep",
    "Ambient",
    "Indie",
    "Rock",
    "Pop",
    "Hip-Hop",
    "Jazz",
    "Classical",
    "Metal"
)

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class OnboardingViewModel : ViewModel() {
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    private val _popularArtists = MutableStateFlow<List<IcmWaveOnboardingArtist>>(emptyList())
    val popularArtists: StateFlow<List<IcmWaveOnboardingArtist>> = _popularArtists

    private val _searchResults = MutableStateFlow<List<IcmWaveOnboardingArtist>>(emptyList())
    val searchResults: StateFlow<List<IcmWaveOnboardingArtist>> = _searchResults

    private val _selectedArtists = MutableStateFlow<Map<String, IcmWaveOnboardingArtist>>(emptyMap())
    val selectedArtists: StateFlow<Map<String, IcmWaveOnboardingArtist>> = _selectedArtists

    private val _isLoadingArtists = MutableStateFlow(true)
    val isLoadingArtists: StateFlow<Boolean> = _isLoadingArtists

    private val _apiError = MutableStateFlow<String?>(null)
    val apiError: StateFlow<String?> = _apiError

    init {
        loadPopularArtists()

        // Debounce search query changes
        viewModelScope.launch {
            _searchQuery
                .debounce(300)
                .distinctUntilChanged()
                .flatMapLatest { query ->
                    if (query.trim().isEmpty()) {
                        flowOf(emptyList())
                    } else {
                        flow {
                            _isLoadingArtists.value = true
                            try {
                                val searchResponse = IcmRepository.searchAll(query, limit = 30)
                                val artistsList = mutableListOf<IcmWaveOnboardingArtist>()
                                val seenIds = mutableSetOf<String>()

                                searchResponse?.items?.forEach { item ->
                                    if (item.isArtist) {
                                        if (seenIds.add(item.id)) {
                                            artistsList.add(
                                                IcmWaveOnboardingArtist(
                                                    id = item.id,
                                                    name = item.title,
                                                    image = item.cover
                                                )
                                            )
                                        }
                                    }
                                    // Extract from item.artists
                                    item.artists.forEach { mini ->
                                        val miniId = mini.id
                                        val miniName = mini.name
                                        if (!miniId.isNullOrBlank() && !miniName.isNullOrBlank()) {
                                            if (seenIds.add(miniId)) {
                                                artistsList.add(
                                                    IcmWaveOnboardingArtist(
                                                        id = miniId,
                                                        name = miniName,
                                                        image = null
                                                    )
                                                )
                                            }
                                        }
                                    }
                                    // Extract from item.artist / item.artistId
                                    val artId = item.artistId
                                    val artName = item.artist ?: item.artistName
                                    if (!artId.isNullOrBlank() && !artName.isNullOrBlank() && artName != "Исполнитель") {
                                        if (seenIds.add(artId)) {
                                            artistsList.add(
                                                IcmWaveOnboardingArtist(
                                                    id = artId,
                                                    name = artName,
                                                    image = item.cover
                                                )
                                            )
                                        }
                                    }
                                }
                                emit(artistsList)
                            } catch (e: Exception) {
                                android.util.Log.e("OnboardingViewModel", "Search error", e)
                                emit(emptyList())
                            } finally {
                                _isLoadingArtists.value = false
                            }
                        }
                    }
                }
                .collect { results ->
                    _searchResults.value = results
                }
        }
    }

    fun setQuery(query: String) {
        _searchQuery.value = query
    }

    fun toggleArtist(artist: IcmWaveOnboardingArtist) {
        val current = _selectedArtists.value
        if (artist.id in current) {
            _selectedArtists.value = current - artist.id
        } else {
            _selectedArtists.value = current + (artist.id to artist)
        }
    }

    fun loadPopularArtists() {
        _isLoadingArtists.value = true
        _apiError.value = null
        viewModelScope.launch {
            try {
                val artists = IcmRepository.getWavePopularArtists()
                _popularArtists.value = artists
            } catch (e: Exception) {
                _apiError.value = e.message
            } finally {
                _isLoadingArtists.value = false
            }
        }
    }
}

@Composable
fun WaveOnboardingScreen(
    onComplete: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val onboardingViewModel = remember { OnboardingViewModel() }

    var currentStep by remember { mutableIntStateOf(1) } // Step 1: Genres, Step 2: Artists

    // Selections
    var selectedGenres by remember { mutableStateOf(emptySet<String>()) }

    // Observe state from ViewModel
    val popularArtists by onboardingViewModel.popularArtists.collectAsState()
    val searchResults by onboardingViewModel.searchResults.collectAsState()
    val searchQuery by onboardingViewModel.searchQuery.collectAsState()
    val selectedArtists by onboardingViewModel.selectedArtists.collectAsState()
    val isLoadingArtists by onboardingViewModel.isLoadingArtists.collectAsState()
    val apiError by onboardingViewModel.apiError.collectAsState()

    var isSaving by remember { mutableStateOf(false) }

    val visibleArtists = if (searchQuery.trim().isEmpty()) popularArtists else searchResults
    val hasIcmKey = remember { com.liquidmusicglass.api.icm.IcmAuthRepository.getPartnerKey().isNotBlank() }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(LiquidTheme.colors.settingsBackground)
                .padding(horizontal = 20.dp)
        ) {
            if (!hasIcmKey) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(20.dp))
                            .background(if (LiquidTheme.colors.isDark) Color(0xFF1C1C1E) else Color(0xFFF2F2F7))
                            .padding(24.dp)
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "Personal radio unavailable",
                                color = LiquidTheme.colors.textPrimary,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "Apple Music / ICM Music requires a partner API key. Please set it up in the app's profile settings.",
                                color = LiquidTheme.colors.textSecondary,
                                fontSize = 14.sp,
                                textAlign = TextAlign.Center,
                                lineHeight = 20.sp
                            )
                            Spacer(modifier = Modifier.height(24.dp))
                            Button(
                                onClick = {
                                    onDismiss()
                                    android.widget.Toast.makeText(
                                        context,
                                        "You can set up the API key in your user profile",
                                        android.widget.Toast.LENGTH_LONG
                                    ).show()
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = AppleRed),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("Set up API key", color = Color.White, fontSize = 15.sp)
                            }
                            Spacer(modifier = Modifier.height(10.dp))
                            Button(
                                onClick = {
                                    onDismiss()
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (LiquidTheme.colors.isDark) Color(0xFF2C2C2E) else Color(0xFFE5E5EA),
                                    contentColor = LiquidTheme.colors.textPrimary
                                ),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("Close", fontSize = 15.sp)
                            }
                        }
                    }
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(modifier = Modifier.height(48.dp))

                    // Step Indicator Header
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        StepIndicator(step = 1, currentStep = currentStep)
                        Spacer(modifier = Modifier.width(4.dp))
                        StepIndicator(step = 2, currentStep = currentStep)
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    AnimatedContent(
                        targetState = currentStep,
                        transitionSpec = {
                            fadeIn().togetherWith(fadeOut())
                        },
                        modifier = Modifier.weight(1f),
                        label = "StepTransition"
                    ) { step ->
                        if (step == 1) {
                            GenreSelectionView(
                                selectedGenres = selectedGenres,
                                onToggleGenre = { genre ->
                                    selectedGenres = if (genre in selectedGenres) {
                                        selectedGenres - genre
                                    } else {
                                        selectedGenres + genre
                                    }
                                }
                            )
                        } else {
                            ArtistSelectionView(
                                artists = visibleArtists,
                                selectedIds = selectedArtists.keys,
                                searchQuery = searchQuery,
                                onQueryChange = { onboardingViewModel.setQuery(it) },
                                isLoading = isLoadingArtists,
                                error = apiError,
                                onToggleArtist = { artist -> onboardingViewModel.toggleArtist(artist) }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Selection Counters and Navigation Controls
                    val selectedGenresCount = selectedGenres.size
                    val selectedArtistsCount = selectedArtists.size

                    if (currentStep == 1) {
                        Text(
                            text = "Genres selected: $selectedGenresCount / $MinSelection",
                            fontSize = 14.sp,
                            color = if (selectedGenresCount >= MinSelection) AppleRed else LiquidTheme.colors.textSecondary,
                            fontWeight = if (selectedGenresCount >= MinSelection) FontWeight.SemiBold else FontWeight.Normal
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Button(
                                onClick = onDismiss,
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (LiquidTheme.colors.isDark) Color(0xFF1C1C1E) else Color(0xFFF2F2F7),
                                    contentColor = LiquidTheme.colors.textPrimary
                                ),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("Skip", fontSize = 15.sp)
                            }

                            Button(
                                onClick = { if (selectedGenresCount >= MinSelection) currentStep = 2 },
                                modifier = Modifier.weight(1f),
                                enabled = selectedGenresCount >= MinSelection,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = AppleRed,
                                    contentColor = Color.White,
                                    disabledContainerColor = AppleRed.copy(alpha = 0.4f),
                                    disabledContentColor = Color.White.copy(alpha = 0.6f)
                                ),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("Next", fontSize = 15.sp)
                            }
                        }
                    } else {
                        Text(
                            text = "Artists selected: $selectedArtistsCount / $MinSelection",
                            fontSize = 14.sp,
                            color = if (selectedArtistsCount >= MinSelection) AppleRed else LiquidTheme.colors.textSecondary,
                            fontWeight = if (selectedArtistsCount >= MinSelection) FontWeight.SemiBold else FontWeight.Normal
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Button(
                                onClick = { currentStep = 1 },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (LiquidTheme.colors.isDark) Color(0xFF1C1C1E) else Color(0xFFF2F2F7),
                                    contentColor = LiquidTheme.colors.textPrimary
                                ),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("Back", fontSize = 15.sp)
                            }

                            Button(
                                onClick = {
                                    if (selectedArtistsCount < MinSelection || isSaving) return@Button
                                    scope.launch {
                                        isSaving = true
                                        val selectedArtistsList = selectedArtists.values.toList()
                                        
                                        val savePayload = selectedArtistsList.map { 
                                            IcmWaveOnboardingArtistSave(id = it.id, name = it.name) 
                                        }

                                        // 1. Save onboarding preferences remotely to API
                                        val success = IcmRepository.saveWaveOnboarding(savePayload)
                                        
                                        // 2. Save selected onboarding settings locally as preference seeder
                                        val artistNames = selectedArtistsList.map { it.name }
                                        val genreNames = selectedGenres.toList()
                                        AppSettings.setOnboardingData(genreNames, artistNames)

                                        // 3. Seeding the Local Room Database for cold start analytics queries
                                        scope.launch(Dispatchers.IO) {
                                            try {
                                                val db = com.liquidmusicglass.data.local.db.AppDatabase.getInstance(context)
                                                val playbackDao = db.playbackHistoryDao()
                                                val waveDao = db.waveDao()

                                                // Seed selected artists in track_stats
                                                artistNames.forEach { artistName ->
                                                    playbackDao.insertTrackStat(
                                                        com.liquidmusicglass.data.local.db.TrackStatsEntity(
                                                            trackId = "seed_art_${artistName.hashCode()}_${System.currentTimeMillis()}",
                                                            title = "Seed Artist Track",
                                                            artistId = artistName,
                                                            playCount = 5, // make it highly played so user top queries hit it
                                                            skippedCount = 0,
                                                            lastPlayedTimestamp = System.currentTimeMillis()
                                                        )
                                                    )
                                                }

                                                // Seed selected genres in listening_history
                                                genreNames.forEach { genreName ->
                                                    waveDao.insertListeningRecord(
                                                        com.liquidmusicglass.data.local.db.ListeningHistory(
                                                            trackId = "seed_genre_${genreName.hashCode()}_${System.currentTimeMillis()}",
                                                            title = "Seed Genre Track",
                                                            artist = "Onboarding Seeder",
                                                            genre = genreName,
                                                            source = "ONBOARDING",
                                                            durationPlayedMs = 60_000L,
                                                            timestamp = System.currentTimeMillis()
                                                        )
                                                    )
                                                }
                                                android.util.Log.d("WaveOnboarding", "Room Database seeded successfully on cold start")
                                            } catch (e: Exception) {
                                                android.util.Log.e("WaveOnboarding", "Failed to seed Room Database", e)
                                            }
                                        }

                                        isSaving = false
                                        if (success) {
                                            onComplete()
                                        } else {
                                            // Save fallback complete even if API saves fails (network offline-first fallback)
                                            onComplete()
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                enabled = selectedArtistsCount >= MinSelection && !isSaving,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = AppleRed,
                                    contentColor = Color.White,
                                    disabledContainerColor = AppleRed.copy(alpha = 0.4f),
                                    disabledContentColor = Color.White.copy(alpha = 0.6f)
                                ),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                if (isSaving) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        color = Color.White,
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Text("Finish", fontSize = 15.sp)
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
    }
}

@Composable
private fun StepIndicator(step: Int, currentStep: Int) {
    val isActive = currentStep >= step
    Box(
        modifier = Modifier
            .size(width = 36.dp, height = 4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(if (isActive) AppleRed else if (LiquidTheme.colors.isDark) Color(0xFF1C1C1E) else Color(0xFFE5E5EA))
    )
}

@Composable
private fun GenreSelectionView(
    selectedGenres: Set<String>,
    onToggleGenre: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Select Genres",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = LiquidTheme.colors.textPrimary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Select $MinSelection or more genres you like to customize your recommendations.",
            fontSize = 15.sp,
            color = LiquidTheme.colors.textSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(24.dp))

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 12.dp)
        ) {
            items(GenresList) { genre ->
                val isSelected = genre in selectedGenres
                val bgBg = if (isSelected) AppleRed else if (LiquidTheme.colors.isDark) Color(0xFF1C1C1E) else Color(0xFFF2F2F7)
                val textCol = if (isSelected) Color.White else LiquidTheme.colors.textPrimary

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(bgBg)
                        .clickable { onToggleGenre(genre) }
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = genre,
                            fontSize = 14.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            color = textCol,
                            textAlign = TextAlign.Center,
                            maxLines = 1
                        )
                        if (isSelected) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Icon(
                                imageVector = Icons.Rounded.Check,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ArtistSelectionView(
    artists: List<IcmWaveOnboardingArtist>,
    selectedIds: Set<String>,
    searchQuery: String,
    onQueryChange: (String) -> Unit,
    isLoading: Boolean,
    error: String?,
    onToggleArtist: (IcmWaveOnboardingArtist) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Select Artists",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = LiquidTheme.colors.textPrimary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Select $MinSelection or more artists to accurately calibrate your initial music recommendations.",
            fontSize = 15.sp,
            color = LiquidTheme.colors.textSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Search Bar Component
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onQueryChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            placeholder = {
                Text(
                    text = "Search artists...",
                    color = LiquidTheme.colors.textSecondary,
                    fontSize = 15.sp
                )
            },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = LiquidTheme.colors.textPrimary,
                unfocusedTextColor = LiquidTheme.colors.textPrimary,
                focusedContainerColor = if (LiquidTheme.colors.isDark) Color(0xFF1C1C1E) else Color(0xFFF2F2F7),
                unfocusedContainerColor = if (LiquidTheme.colors.isDark) Color(0xFF1C1C1E) else Color(0xFFF2F2F7),
                focusedBorderColor = AppleRed,
                unfocusedBorderColor = Color.Transparent,
                cursorColor = AppleRed
            )
        )

        Spacer(modifier = Modifier.height(16.dp))

        if (isLoading) {
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = AppleRed)
            }
        } else if (error != null && artists.isEmpty()) {
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Failed to load artists. You may skip this step.",
                    color = Color(0xFFFF453A),
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }
        } else if (artists.isEmpty() && searchQuery.isNotEmpty()) {
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No artists found for \"$searchQuery\"",
                    color = LiquidTheme.colors.textSecondary,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(bottom = 12.dp)
            ) {
                items(artists, key = { it.id }) { artist ->
                    val isSelected = artist.id in selectedIds
                    ArtistGridItem(
                        artist = artist,
                        isSelected = isSelected,
                        onToggle = { onToggleArtist(artist) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ArtistGridItem(
    artist: IcmWaveOnboardingArtist,
    isSelected: Boolean,
    onToggle: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable(onClick = onToggle)
            .padding(4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(86.dp)
                .clip(RoundedCornerShape(50))
                .background(if (LiquidTheme.colors.isDark) Color(0xFF1C1C1E) else Color(0xFFF2F2F7)),
            contentAlignment = Alignment.Center
        ) {
            if (artist.image != null) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(artist.image)
                        .crossfade(true)
                        .build(),
                    contentDescription = artist.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Text(
                    text = artist.name.take(2).uppercase(),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = LiquidTheme.colors.textSecondary
                )
            }

            if (isSelected) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(AppleRed.copy(alpha = 0.6f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = artist.name,
            fontSize = 12.sp,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (isSelected) AppleRed else LiquidTheme.colors.textPrimary,
            maxLines = 1,
            textAlign = TextAlign.Center
        )
    }
}
