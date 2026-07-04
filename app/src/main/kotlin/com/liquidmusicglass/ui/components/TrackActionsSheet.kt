package com.liquidmusicglass.ui.components

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.liquidmusicglass.engine.PlayerController
import com.liquidmusicglass.engine.Track
import com.liquidmusicglass.ui.theme.LiquidTheme

/**
 * Контекст-меню трека (долгий тап по строке): играть следующим, в конец
 * очереди, поделиться. Стиль — карточки как в настройках (cardSurface).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackActionsSheet(
    track: Track,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val isDark = LiquidTheme.colors.isDark
    val sheetBg = if (isDark) Color(0xFF141416) else Color.White
    val rowBg = if (isDark) Color(0xFF1C1C1E) else Color(0xFFF2F2F7)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = sheetBg,
        dragHandle = null
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 18.dp)) {
            // ── Шапка: обложка + название + артист ──
            Row(verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(
                    model = track.coverUrl ?: track.albumArtUri,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(rowBg)
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = track.title,
                        color = LiquidTheme.colors.textPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = track.artist,
                        color = LiquidTheme.colors.textSecondary,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            ActionRow(rowBg, Icons.Rounded.QueueMusic, "Play next") {
                PlayerController.insertNext(track)
                onDismiss()
            }
            Spacer(Modifier.height(8.dp))
            ActionRow(rowBg, Icons.Rounded.PlaylistAdd, "Add to queue") {
                PlayerController.addToQueue(track)
                onDismiss()
            }
            Spacer(Modifier.height(8.dp))
            ActionRow(rowBg, Icons.Rounded.Share, "Share") {
                val text = "${track.title} — ${track.artist}\nhttps://byicloud.online/track/${track.id}"
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text)
                }
                context.startActivity(Intent.createChooser(send, track.title))
                onDismiss()
            }

            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun ActionRow(
    bg: Color,
    icon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(50))   // строки-пилюли, как в настройках
            .background(bg)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = LiquidTheme.colors.textPrimary,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = label,
            color = LiquidTheme.colors.textPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium
        )
    }
}
