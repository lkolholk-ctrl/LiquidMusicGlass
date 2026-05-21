package com.liquidmusicglass.ui.screens

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
import com.liquidmusicglass.api.icm.IcmRepository
import com.liquidmusicglass.api.icm.IcmWaveOnboardingArtist
import com.liquidmusicglass.api.icm.IcmWaveOnboardingArtistSave
import com.liquidmusicglass.ui.theme.LiquidTheme
import kotlinx.coroutines.launch

private val AppleRed = Color(0xFFFC3C44)
private val MinSelection = 3

@Composable
fun WaveOnboardingScreen(
    onComplete: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var artists by remember { mutableStateOf<List<IcmWaveOnboardingArtist>>(emptyList()) }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var isLoading by remember { mutableStateOf(true) }
    var isSaving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // Load popular artists on first composition
    LaunchedEffect(Unit) {
        isLoading = true
        try {
            artists = IcmRepository.getWavePopularArtists()
        } catch (e: Exception) {
            error = e.message
        } finally {
            isLoading = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(horizontal = 20.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(48.dp))

            Text(
                text = "Настройте свою волну",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = LiquidTheme.colors.textPrimary,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Выберите $MinSelection или более артистов, чтобы мы подобрали музыку по вкусу",
                fontSize = 15.sp,
                color = LiquidTheme.colors.textSecondary,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(24.dp))

            if (isLoading) {
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = AppleRed)
                }
            } else if (error != null) {
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Ошибка загрузки: $error",
                        color = Color(0xFFFF453A),
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(artists, key = { it.id }) { artist ->
                        val isSelected = artist.id in selectedIds
                        ArtistGridItem(
                            artist = artist,
                            isSelected = isSelected,
                            onToggle = {
                                selectedIds = if (isSelected) {
                                    selectedIds - artist.id
                                } else {
                                    selectedIds + artist.id
                                }
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Selection counter
            val selectedCount = selectedIds.size
            val canSave = selectedCount >= MinSelection

            Text(
                text = "Выбрано: $selectedCount / $MinSelection",
                fontSize = 14.sp,
                color = if (canSave) AppleRed else LiquidTheme.colors.textSecondary,
                fontWeight = if (canSave) FontWeight.SemiBold else FontWeight.Normal
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF1C1C1E),
                        contentColor = LiquidTheme.colors.textPrimary
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Пропустить", fontSize = 15.sp)
                }

                Button(
                    onClick = {
                        if (!canSave || isSaving) return@Button
                        scope.launch {
                            isSaving = true
                            val selected = artists
                                .filter { it.id in selectedIds }
                                .map { IcmWaveOnboardingArtistSave(id = it.id, name = it.name) }
                            val success = IcmRepository.saveWaveOnboarding(selected)
                            isSaving = false
                            if (success) {
                                onComplete()
                            } else {
                                error = "Не удалось сохранить выбор"
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = canSave && !isSaving,
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
                        Text("Готово", fontSize = 15.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
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
        modifier = Modifier.clickable(onClick = onToggle)
    ) {
        Box(
            modifier = Modifier
                .size(90.dp)
                .clip(RoundedCornerShape(50))
                .background(Color(0xFF1C1C1E)),
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
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = LiquidTheme.colors.textSecondary
                )
            }

            // Selection overlay
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
                        modifier = Modifier.size(32.dp)
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
