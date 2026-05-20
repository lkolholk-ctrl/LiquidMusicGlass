package com.liquidmusicglass.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
import com.kyant.shapes.Capsule
import com.liquidmusicglass.engine.AppUpdater
import com.liquidmusicglass.ui.theme.LiquidTheme

private val AppleRed = Color(0xFFFC3C44)

@Composable
fun UpdateDialog(backdrop: LayerBackdrop) {
    val context = LocalContext.current
    val lc = LiquidTheme.colors
    val updateAvailable by AppUpdater.updateAvailable.collectAsState()
    val version by AppUpdater.latestVersion.collectAsState()
    val changelog by AppUpdater.changelog.collectAsState()
    val progress by AppUpdater.downloadProgress.collectAsState()

    AnimatedVisibility(
        visible = updateAvailable,
        enter = fadeIn(),
        exit = fadeOut()
    ) {
        // Dimmed background
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.6f))
                .clickable(remember { MutableInteractionSource() }, null) { },
            contentAlignment = Alignment.Center
        ) {
            // Glass dialog card
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp)
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { RoundedCornerShape(28.dp) },
                        effects = {
                            vibrancy()
                            blur(4.dp.toPx())
                            lens(20.dp.toPx(), 32.dp.toPx(), chromaticAberration = true)
                        },
                        highlight = { Highlight.Ambient },
                        shadow = { Shadow(radius = 16.dp, color = Color.Black.copy(0.3f)) },
                        innerShadow = { InnerShadow(radius = 6.dp, alpha = 0.3f) },
                        onDrawSurface = {
                            drawRect(Color.White.copy(alpha = 0.05f))
                            drawRect(Color.White.copy(alpha = 0.20f), style = Stroke(1.dp.toPx()))
                        }
                    )
                    .padding(28.dp)
            ) {
                Column {
                    // Icon + title
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Rounded.SystemUpdate, null,
                            tint = AppleRed,
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                "Update Available",
                                color = lc.textPrimary,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "v$version",
                                color = AppleRed,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    // Changelog
                    if (changelog.isNotEmpty()) {
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = changelog,
                            color = lc.textSecondary,
                            fontSize = 14.sp,
                            maxLines = 6,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Spacer(Modifier.height(24.dp))

                    // Buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Later
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(46.dp)
                                .drawBackdrop(
                                    backdrop = backdrop,
                                    shape = { Capsule() },
                                    effects = { vibrancy(); blur(4.dp.toPx()) },
                                    highlight = { Highlight.Ambient },
                                    shadow = { Shadow(radius = 4.dp, color = Color.Black.copy(0.1f)) },
                                    onDrawSurface = {
                                        drawRect(Color.White.copy(0.05f))
                                        drawRect(Color.White.copy(0.15f), style = Stroke(1.dp.toPx()))
                                    }
                                )
                                .clip(RoundedCornerShape(50))
                                .clickable(remember { MutableInteractionSource() }, null) {
                                    AppUpdater.dismiss()
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text("Later", color = lc.textSecondary, fontWeight = FontWeight.SemiBold)
                        }

                        // Update
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(46.dp)
                                .drawBackdrop(
                                    backdrop = backdrop,
                                    shape = { Capsule() },
                                    effects = {
                                        vibrancy(); blur(4.dp.toPx())
                                        lens(8.dp.toPx(), 12.dp.toPx(), chromaticAberration = true)
                                    },
                                    highlight = { Highlight.Default.copy(alpha = 0.7f) },
                                    shadow = { Shadow(radius = 6.dp, color = AppleRed.copy(0.3f)) },
                                    innerShadow = { InnerShadow(radius = 3.dp, alpha = 0.2f) },
                                    onDrawSurface = { drawRect(AppleRed) }
                                )
                                .clip(RoundedCornerShape(50))
                                .clickable(remember { MutableInteractionSource() }, null) {
                                    if (progress < 0) {
                                        AppUpdater.downloadAndInstall(context)
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = when {
                                    progress in 0..99 -> "Downloading..."
                                    progress >= 100 -> "Installing..."
                                    else -> "Update"
                                },
                                color = Color.White,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }
    }
}