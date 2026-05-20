package com.liquidmusicglass.ui.screens

import android.content.Context
import android.content.Intent
import android.media.audiofx.AudioEffect
import android.net.Uri
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.liquidmusicglass.engine.AppSettings
import com.liquidmusicglass.engine.PlayerController
import com.liquidmusicglass.ui.liquid.LiquidToggle
import com.liquidmusicglass.ui.theme.LiquidTheme

private val AppleRed = Color(0xFFFC3C44)

@Composable
fun SettingsScreen(
    autoMixEnabled: Boolean,
    onAutoMixChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    onOpenEqualizer: () -> Unit = {},
    backdrop: LayerBackdrop
) {
    val context = LocalContext.current
    val scroll = rememberScrollState()

    val gaplessEnabled by AppSettings.gaplessEnabled.collectAsState()
    val sleepTimerMinutes by AppSettings.sleepTimerMinutes.collectAsState()
    val sleepOptions = listOf(0, 15, 30, 45, 60, 90)

    val themeMode by PlayerController.themeMode.collectAsState()
    val themeLabels = listOf("System", "Dark", "Light")

    val lc = LiquidTheme.colors

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(horizontal = 20.dp)
        ) {
            Spacer(modifier = Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
            Spacer(modifier = Modifier.height(12.dp))

            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF1C1C1E))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { onBack() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = null,
                        tint = lc.iconDefault,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = "Settings",
                    color = lc.textPrimary,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            // PLAYBACK
            SectionLabel("PLAYBACK")

            val hideExplicit by AppSettings.hideExplicit.collectAsState()

            PlainCard {
                SettingsToggleItem(
                    title = "AutoMix",
                    subtitle = "ML-powered DJ transitions",
                    selected = autoMixEnabled,
                    onSelect = onAutoMixChange
                )
                PlainDivider()
                SettingsToggleItem(
                    title = "Gapless Playback",
                    subtitle = "No silence between tracks",
                    selected = gaplessEnabled,
                    onSelect = { AppSettings.setGapless(it) }
                )
                PlainDivider()
                SettingsToggleItem(
                    title = "Hide Explicit",
                    subtitle = "Filter explicit content from search & artist",
                    selected = hideExplicit,
                    onSelect = { AppSettings.setHideExplicit(it) }
                )
                PlainDivider()
                SettingsActionItem(
                    title = "Equalizer",
                    subtitle = "Bass Boost, Surround, Presets",
                    icon = Icons.Rounded.Equalizer,
                    onClick = onOpenEqualizer
                )
            }

            Spacer(modifier = Modifier.height(28.dp))

            // SLEEP TIMER
            SectionLabel("SLEEP TIMER")

            PlainCard {
                SleepTimerSelector(
                    options = sleepOptions,
                    selectedMinutes = sleepTimerMinutes,
                    onSelect = { AppSettings.setSleepTimer(it) }
                )
            }

            Spacer(modifier = Modifier.height(28.dp))

            // STREAM QUALITY
            SectionLabel("STREAM QUALITY")

            val qualityOptions = listOf(
                "128K" to "Compressed. Fastest load, lowest data usage.",
                "256K" to "Balanced. Standard high-quality AAC.",
                "320K" to "Premium. Near-lossless perceptual quality.",
                "ALAC" to "Lossless Apple format. Studio quality."
            )
            var selectedQuality by remember {
                mutableStateOf(
                    context.getSharedPreferences("icm", Context.MODE_PRIVATE)
                        .getString("stream_quality", "256K") ?: "256K"
                )
            }

            PlainCard {
                Column(modifier = Modifier.padding(vertical = 14.dp)) {
                    val isPremium by com.liquidmusicglass.api.icm.IcmAuthRepository.isPremium.collectAsState()
                    
                    // Auto-fallback to 256K if premium is lost but high quality was selected
                    androidx.compose.runtime.LaunchedEffect(isPremium) {
                        if (!isPremium && (selectedQuality == "320K" || selectedQuality == "ALAC")) {
                            selectedQuality = "256K"
                            context.getSharedPreferences("icm", Context.MODE_PRIVATE)
                                .edit().putString("stream_quality", "256K").apply()
                            com.liquidmusicglass.api.icm.IcmRepository.streamQuality = "256K"
                        }
                    }

                    qualityOptions.forEach { (quality, description) ->
                        val isSelected = selectedQuality == quality
                        val isAvailable = isPremium || (quality != "320K" && quality != "ALAC")
                        
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    enabled = isAvailable
                                ) {
                                    selectedQuality = quality
                                    context.getSharedPreferences("icm", Context.MODE_PRIVATE)
                                        .edit().putString("stream_quality", quality).apply()
                                    com.liquidmusicglass.api.icm.IcmRepository.streamQuality = quality
                                }
                                .padding(horizontal = 16.dp, vertical = 12.dp)
                                .graphicsLayer { alpha = if (isAvailable) 1f else 0.4f },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = quality,
                                        color = if (isSelected) AppleRed else lc.textPrimary,
                                        fontSize = 16.sp,
                                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                                    )
                                    if (!isAvailable) {
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            "Premium",
                                            color = Color(0xFF8B5CF6),
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier
                                                .background(Color(0xFF8B5CF6).copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                                                .padding(horizontal = 4.dp, vertical = 1.dp)
                                        )
                                    }
                                }
                                Text(
                                    text = description,
                                    color = lc.textSecondary,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (isSelected) {
                                Box(
                                    modifier = Modifier
                                        .size(20.dp)
                                        .clip(CircleShape)
                                        .background(AppleRed),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "✓",
                                        color = Color.White,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            // THEME
            SectionLabel("APPEARANCE")

            PlainCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    themeLabels.forEachIndexed { index, label ->
                        val isSelected = themeMode == index
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(36.dp)
                                .background(
                                    if (isSelected) AppleRed else Color(0xFF1C1C1E),
                                    RoundedCornerShape(50)
                                )
                                .clip(RoundedCornerShape(50))
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = { PlayerController.setThemeMode(index) }
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            val textColor by animateColorAsState(
                                targetValue = if (isSelected) Color.White
                                else Color.White.copy(alpha = 0.45f),
                                animationSpec = tween(200),
                                label = "themeText"
                            )
                            Text(
                                text = label,
                                color = textColor,
                                fontSize = 14.sp,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

// ── UI Components ──

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        color = LiquidTheme.colors.textSecondary,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.5.sp,
        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
    )
}

@Composable
private fun PlainCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1C1C1E), RoundedCornerShape(12.dp))
            .padding(vertical = 4.dp),
        content = content
    )
}

@Composable
private fun PlainDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp)
            .height(1.dp)
            .background(Color.White.copy(alpha = 0.08f))
    )
}

@Composable
private fun SettingsToggleItem(
    title: String,
    subtitle: String,
    selected: Boolean,
    onSelect: (Boolean) -> Unit
) {
    val screenBackdrop = com.kyant.backdrop.backdrops.rememberLayerBackdrop()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onSelect(!selected) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = LiquidTheme.colors.textPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = subtitle,
                color = LiquidTheme.colors.textSecondary,
                fontSize = 12.sp
            )
        }
        LiquidToggle(selected = { selected }, onSelect = onSelect, backdrop = screenBackdrop)
    }
}

@Composable
private fun SettingsActionItem(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = LiquidTheme.colors.textPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = subtitle,
                color = LiquidTheme.colors.textSecondary,
                fontSize = 12.sp
            )
        }
        Icon(
            imageVector = Icons.Rounded.ChevronRight,
            contentDescription = null,
            tint = LiquidTheme.colors.iconDefault,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun SleepTimerSelector(
    options: List<Int>,
    selectedMinutes: Int,
    onSelect: (Int) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { minutes ->
            val isSelected = selectedMinutes == minutes
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(36.dp)
                    .background(
                        if (isSelected) AppleRed else Color(0xFF1C1C1E),
                        RoundedCornerShape(50)
                    )
                    .clip(RoundedCornerShape(50))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { onSelect(minutes) }
                    ),
                contentAlignment = Alignment.Center
            ) {
                val textColor by animateColorAsState(
                    targetValue = if (isSelected) Color.White
                    else Color.White.copy(alpha = 0.45f),
                    animationSpec = tween(200),
                    label = "sleepText"
                )
                Text(
                    text = if (minutes == 0) "Off" else "${minutes}m",
                    color = textColor,
                    fontSize = 13.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                )
            }
        }
    }
}
