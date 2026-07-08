package com.liquidmusicglass.ui.screens

import com.liquidmusicglass.ui.icons.LiquidGlyphs
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liquidmusicglass.data.playlistimport.ImportState
import com.liquidmusicglass.data.playlistimport.LoadingPhase

/**
 * Subtle background import indicator shown at the top of the Library screen.
 * Non-blocking — user can freely navigate while import runs.
 */
@Composable
fun PlaylistImportIndicator(
    state: ImportState,
    onDismiss: () -> Unit = {},
    onPlayPlaylist: (playlistId: String) -> Unit = {}
) {
    AnimatedVisibility(
        visible = state != ImportState.Idle,
        enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(top = 8.dp)
        ) {
            when (state) {
                is ImportState.Loading -> LoadingCard(state)
                is ImportState.Success -> SuccessCard(state, onDismiss, onPlayPlaylist)
                is ImportState.Error -> ErrorCard(state, onDismiss)
                else -> {} // Idle — hidden
            }
        }
    }
}

@Composable
private fun LoadingCard(state: ImportState.Loading) {
    val phaseText = when (state.phase) {
        LoadingPhase.RESOLVING -> "Fetching playlist..."
        LoadingPhase.MATCHING -> "Matching tracks against catalog..."
        LoadingPhase.SAVING -> "Saving to your library..."
    }

    val progress = if (state.totalTracks > 0) {
        state.processedTracks.toFloat() / state.totalTracks
    } else 0f

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1C1E))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = Color(0xFFFF453A),
                    strokeWidth = 2.dp
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Importing your playlist",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = phaseText,
                        color = Color.Gray,
                        fontSize = 12.sp
                    )
                }
                Text(
                    text = "${state.processedTracks}/${state.totalTracks}",
                    color = Color.Gray,
                    fontSize = 12.sp
                )
            }

            Spacer(Modifier.height(10.dp))

            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(RoundedCornerShape(50)),
                color = Color(0xFFFF453A),
                trackColor = Color(0xFF3A3A3C)
            )
        }
    }
}

@Composable
private fun SuccessCard(
    state: ImportState.Success,
    onDismiss: () -> Unit,
    onPlayPlaylist: (String) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clickable(remember { MutableInteractionSource() }, null) {
                onPlayPlaylist(state.playlistId)
            },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1C2E1C))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                LiquidGlyphs.CheckCircle,
                contentDescription = null,
                tint = Color(0xFF30D158),
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Import completed",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "${state.insertedCount} tracks added to ${state.playlistName}",
                    color = Color(0xFF30D158),
                    fontSize = 12.sp
                )
            }
            TextButton(onClick = onDismiss) {
                Text("Dismiss", color = Color.Gray, fontSize = 12.sp)
            }
        }
    }

    // Auto-dismiss after 5 seconds
    LaunchedEffect(state) {
        kotlinx.coroutines.delay(5000)
        onDismiss()
    }
}

@Composable
private fun ErrorCard(
    state: ImportState.Error,
    onDismiss: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF2E1C1C))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                LiquidGlyphs.Error,
                contentDescription = null,
                tint = Color(0xFFFF453A),
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Import failed",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = state.message,
                    color = Color(0xFFFF453A),
                    fontSize = 12.sp,
                    maxLines = 2
                )
            }
            IconButton(onClick = onDismiss) {
                Icon(
                    LiquidGlyphs.Close,
                    contentDescription = "Dismiss",
                    tint = Color.Gray,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}
