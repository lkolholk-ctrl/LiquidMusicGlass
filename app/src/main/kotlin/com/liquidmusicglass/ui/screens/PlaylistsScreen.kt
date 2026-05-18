package com.liquidmusicglass.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liquidmusicglass.engine.PlaylistManager
import com.liquidmusicglass.ui.theme.LiquidTheme

private val AppleRed = Color(0xFFFC3C44)

@Composable
fun PlaylistsScreen(
    onBack: () -> Unit,
    onOpenPlaylist: (String) -> Unit
) {
    val lc = LiquidTheme.colors
    val playlists by PlaylistManager.playlists.collectAsState()
    var showCreateDialog by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(horizontal = 20.dp)
    ) {
        // Header
        item {
            Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircleBtn(lc, Icons.AutoMirrored.Rounded.ArrowBack) { onBack() }
                Spacer(Modifier.width(16.dp))
                Text(
                    "Playlists",
                    color = lc.textPrimary,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                CircleBtn(lc, Icons.Rounded.Add, AppleRed) {
                    showCreateDialog = true
                }
            }
            Spacer(Modifier.height(24.dp))
        }

        // Create dialog
        if (showCreateDialog) {
            item {
                CreatePlaylistCard(lc, onCreate = { name ->
                    PlaylistManager.create(name)
                    showCreateDialog = false
                }, onCancel = { showCreateDialog = false })
                Spacer(Modifier.height(16.dp))
            }
        }

        // Playlist items
        if (playlists.isEmpty() && !showCreateDialog) {
            item {
                Spacer(Modifier.height(80.dp))
                Text(
                    "No playlists yet.\nTap + to create one!",
                    color = lc.textTertiary,
                    fontSize = 16.sp,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }

        items(playlists, key = { it.id }) { playlist ->
            PlaylistRow(
                name = playlist.name,
                trackCount = playlist.trackIds.size,
                lc = lc,
                onClick = { onOpenPlaylist(playlist.id) },
                onDelete = { PlaylistManager.delete(playlist.id) }
            )
            Spacer(Modifier.height(8.dp))
        }

        item { Spacer(Modifier.height(200.dp)) }
    }
}

// ═══════════════════════════════════════════════════════════
//  Create Playlist Card
// ═══════════════════════════════════════════════════════════

@Composable
private fun CreatePlaylistCard(
    lc: com.liquidmusicglass.ui.theme.LiquidColors,
    onCreate: (String) -> Unit,
    onCancel: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    val shape = RoundedCornerShape(12.dp)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1C1C1E), shape)
            .padding(20.dp)
    ) {
        Column {
            Text(
                "New Playlist",
                color = lc.textPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(12.dp))

            // Name field
            BasicTextField(
                value = name,
                onValueChange = { name = it },
                textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
                cursorBrush = SolidColor(AppleRed),
                singleLine = true,
                decorationBox = { inner ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF2C2C2E), RoundedCornerShape(50))
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        if (name.isEmpty()) {
                            Text("Playlist name...", color = lc.textTertiary, fontSize = 16.sp)
                        }
                        inner()
                    }
                }
            )

            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                // Cancel
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(40.dp)
                        .background(Color(0xFF2C2C2E), RoundedCornerShape(50))
                        .clip(RoundedCornerShape(50))
                        .clickable(remember { MutableInteractionSource() }, null) { onCancel() },
                    contentAlignment = Alignment.Center
                ) {
                    Text("Cancel", color = lc.textSecondary, fontSize = 14.sp)
                }

                // Create
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(40.dp)
                        .background(AppleRed, RoundedCornerShape(50))
                        .clip(RoundedCornerShape(50))
                        .clickable(remember { MutableInteractionSource() }, null) {
                            if (name.isNotBlank()) onCreate(name.trim())
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "Create",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════
//  Playlist Row
// ═══════════════════════════════════════════════════════════

@Composable
private fun PlaylistRow(
    name: String,
    trackCount: Int,
    lc: com.liquidmusicglass.ui.theme.LiquidColors,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1C1C1E), shape)
            .clip(shape)
            .clickable(remember { MutableInteractionSource() }, null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Icon
        Box(
            modifier = Modifier
                .size(44.dp)
                .background(Color(0xFF2C2C2E), CircleShape)
                .clip(CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.AutoMirrored.Rounded.PlaylistPlay,
                null,
                tint = AppleRed,
                modifier = Modifier.size(24.dp)
            )
        }

        Spacer(Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                name,
                color = lc.textPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "$trackCount tracks",
                color = lc.textSecondary,
                fontSize = 13.sp
            )
        }

        // Delete
        Icon(
            Icons.Rounded.Delete,
            null,
            tint = lc.iconMuted,
            modifier = Modifier
                .size(20.dp)
                .clickable(remember { MutableInteractionSource() }, null) { onDelete() }
        )
    }
}

// ═══════════════════════════════════════════════════════════
//  Helpers
// ═══════════════════════════════════════════════════════════

@Composable
private fun CircleBtn(
    lc: com.liquidmusicglass.ui.theme.LiquidColors,
    icon: androidx.compose.ui.graphics.vector.ImageVector = Icons.AutoMirrored.Rounded.ArrowBack,
    tint: Color = lc.iconDefault,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .background(Color(0xFF1C1C1E), CircleShape)
            .clip(CircleShape)
            .clickable(remember { MutableInteractionSource() }, null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
    }
}
