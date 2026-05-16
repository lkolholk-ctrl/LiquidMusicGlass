package com.liquidmusicglass.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlaylistPlay
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule
import com.liquidmusicglass.engine.PlaylistManager
import com.liquidmusicglass.ui.theme.LiquidTheme

private val AppleRed = Color(0xFFFC3C44)

@Composable
fun PlaylistsScreen(
    onBack: () -> Unit,
    onOpenPlaylist: (String) -> Unit
) {
    val backdrop = rememberLayerBackdrop()
    val lc = LiquidTheme.colors
    val playlists by PlaylistManager.playlists.collectAsState()
    var showCreateDialog by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp)
    ) {
        // Header
        item {
            Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                GlassCircleBtn(backdrop, lc) { onBack() }
                Spacer(Modifier.width(16.dp))
                Text("Playlists", color = lc.textPrimary, fontSize = 24.sp,
                    fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                // Add button
                GlassCircleBtn(backdrop, lc, Icons.Rounded.Add, AppleRed) {
                    showCreateDialog = true
                }
            }
            Spacer(Modifier.height(24.dp))
        }

        // Create dialog
        if (showCreateDialog) {
            item {
                CreatePlaylistCard(backdrop, lc, onCreate = { name ->
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
                backdrop = backdrop, lc = lc,
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
    backdrop: LayerBackdrop,
    lc: com.liquidmusicglass.ui.theme.LiquidColors,
    onCreate: (String) -> Unit,
    onCancel: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    val shape = RoundedCornerShape(22.dp)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .drawBackdrop(
                backdrop = backdrop,
                shape = { shape },
                effects = {
                    vibrancy(); blur(4.dp.toPx())
                    lens(22.dp.toPx(), 30.dp.toPx(), chromaticAberration = true)
                },
                highlight = { Highlight.Ambient },
                shadow = { Shadow(radius = 8.dp, color = Color.Black.copy(alpha = 0.15f)) },
                innerShadow = { InnerShadow(radius = 4.dp, alpha = 0.3f) },
                onDrawSurface = {
                    drawRect(Color.White.copy(alpha = 0.03f))
                    drawRect(Color.White.copy(alpha = 0.20f), style = Stroke(width = 1.dp.toPx()))
                }
            )
            .padding(20.dp)
    ) {
        Column {
            Text("New Playlist", color = lc.textPrimary, fontSize = 18.sp,
                fontWeight = FontWeight.Bold)
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
                            .drawBackdrop(
                                backdrop = backdrop,
                                shape = { Capsule() },
                                effects = { vibrancy(); blur(4.dp.toPx()) },
                                onDrawSurface = {
                                    drawRect(Color.White.copy(alpha = 0.04f))
                                    drawRect(Color.White.copy(alpha = 0.22f), style = Stroke(1.dp.toPx()))
                                }
                            )
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
                        .drawBackdrop(
                            backdrop = backdrop,
                            shape = { Capsule() },
                            effects = { vibrancy(); blur(4.dp.toPx()) },
                            onDrawSurface = {
                                drawRect(Color.White.copy(alpha = 0.04f))
                                drawRect(Color.White.copy(alpha = 0.22f), style = Stroke(1.dp.toPx()))
                            }
                        )
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
                        .drawBackdrop(
                            backdrop = backdrop,
                            shape = { Capsule() },
                            effects = {
                                vibrancy(); blur(4.dp.toPx())
                                lens(14.dp.toPx(), 20.dp.toPx(), chromaticAberration = true)
                            },
                            highlight = { Highlight.Default.copy(alpha = 0.7f) },
                            shadow = { Shadow(radius = 6.dp, color = AppleRed.copy(alpha = 0.3f)) },
                            innerShadow = { InnerShadow(radius = 3.dp, alpha = 0.2f) },
                            onDrawSurface = { drawRect(AppleRed) }
                        )
                        .clip(RoundedCornerShape(50))
                        .clickable(remember { MutableInteractionSource() }, null) {
                            if (name.isNotBlank()) onCreate(name.trim())
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text("Create", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
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
    backdrop: LayerBackdrop,
    lc: com.liquidmusicglass.ui.theme.LiquidColors,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .drawBackdrop(
                backdrop = backdrop,
                shape = { shape },
                effects = { vibrancy(); blur(4.dp.toPx()) },
                highlight = { Highlight.Ambient.copy(alpha = 0.3f) },
                onDrawSurface = {
                    drawRect(Color.White.copy(alpha = 0.03f))
                    drawRect(Color.White.copy(alpha = 0.22f), style = Stroke(width = 1.dp.toPx()))
                }
            )
            .clip(shape)
            .clickable(remember { MutableInteractionSource() }, null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Icon
        Box(
            modifier = Modifier
                .size(44.dp)
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { Capsule() },
                    effects = { vibrancy(); blur(4.dp.toPx()) },
                    onDrawSurface = { drawRect(AppleRed.copy(alpha = 0.15f)) }
                )
                .clip(CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Rounded.PlaylistPlay, null, tint = AppleRed, modifier = Modifier.size(24.dp))
        }

        Spacer(Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(name, color = lc.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("$trackCount tracks", color = lc.textSecondary, fontSize = 13.sp)
        }

        // Delete
        Icon(
            Icons.Rounded.Delete, null,
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
private fun GlassCircleBtn(
    backdrop: LayerBackdrop,
    lc: com.liquidmusicglass.ui.theme.LiquidColors,
    icon: androidx.compose.ui.graphics.vector.ImageVector = Icons.AutoMirrored.Rounded.ArrowBack,
    tint: Color = lc.iconDefault,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .drawBackdrop(
                backdrop = backdrop,
                shape = { Capsule() },
                effects = {
                    vibrancy(); blur(4.dp.toPx())
                    lens(18.dp.toPx(), 24.dp.toPx(), chromaticAberration = true)
                },
                highlight = { Highlight.Ambient },
                shadow = { Shadow(radius = 4.dp, color = Color.Black.copy(alpha = 0.12f)) },
                innerShadow = { InnerShadow(radius = 3.dp, alpha = 0.2f) },
                onDrawSurface = {
                    drawRect(Color.White.copy(alpha = 0.03f))
                    drawRect(Color.White.copy(alpha = 0.22f), style = Stroke(width = 1.dp.toPx()))
                }
            )
            .clip(CircleShape)
            .clickable(remember { MutableInteractionSource() }, null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
    }
}
