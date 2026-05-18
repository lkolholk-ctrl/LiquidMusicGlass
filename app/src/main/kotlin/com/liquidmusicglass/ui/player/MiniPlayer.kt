package com.liquidmusicglass.ui.player

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.liquidmusicglass.ui.glass.AlbumArtImage
import com.liquidmusicglass.ui.glass.pressScale
import com.liquidmusicglass.ui.theme.LiquidTheme

@Composable
fun MiniPlayer(
    trackTitle: String,
    artistName: String,
    isPlaying: Boolean,
    albumArtUri: Uri?,
    coverUrl: String? = null,
    backdrop: LayerBackdrop,
    onExpand: () -> Unit,
    onPlayPause: () -> Unit,
    onSkipNext: () -> Unit = {}
) {
    val pillShape = RoundedCornerShape(100.dp)
    val artShape = RoundedCornerShape(10.dp)

    val lc = LiquidTheme.colors

    Row(
        modifier = Modifier
            .fillMaxWidth(0.94f)
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .height(52.dp)
            .drawBackdrop(
                backdrop = backdrop,
                shape = { pillShape },
                effects = {
                    vibrancy()
                    blur(4.dp.toPx())
                    lens(
                        refractionHeight = 24.dp.toPx(),
                        refractionAmount = 40.dp.toPx(),
                        chromaticAberration = true
                    )
                },
                highlight = {
                    Highlight.Ambient
                },
                shadow = {
                    Shadow(
                        radius = 10.dp,
                        color = Color.Black.copy(alpha = 0.25f)
                    )
                },
                innerShadow = {
                    InnerShadow(
                        radius = 6.dp,
                        alpha = 0.35f
                    )
                },
                onDrawSurface = {
                    drawRect(lc.miniPlayerTint)
                    drawRect(
                        color = lc.miniPlayerBorder,
                        style = Stroke(width = 0.8.dp.toPx())
                    )
                }
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onExpand() }
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(artShape)
        ) {
            AlbumArtImage(
                uri = albumArtUri,
                coverUrl = coverUrl,
                modifier = Modifier.fillMaxWidth().fillMaxHeight(),
                contentScale = ContentScale.Crop
            )
        }

        Spacer(modifier = Modifier.width(10.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = trackTitle,
                color = LiquidTheme.colors.textPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                maxLines = 1
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = artistName,
                color = LiquidTheme.colors.textSecondary,
                fontSize = 11.sp,
                maxLines = 1
            )
        }

        Box(
            modifier = Modifier
                .size(36.dp)
                .pressScale { onPlayPause() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = LiquidTheme.colors.iconDefault
            )
        }

        Box(
            modifier = Modifier
                .size(36.dp)
                .pressScale { onSkipNext() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.SkipNext,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = LiquidTheme.colors.iconDefault
            )
        }
    }
}
