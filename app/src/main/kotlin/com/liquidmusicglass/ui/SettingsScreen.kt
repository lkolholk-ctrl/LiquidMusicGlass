package com.liquidmusicglass.ui.screens

import android.content.Context
import android.content.Intent
import android.media.audiofx.AudioEffect
import android.net.Uri
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.Timer
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule
import com.liquidmusicglass.engine.AppSettings
import com.liquidmusicglass.engine.PlayerController
import com.liquidmusicglass.ui.liquid.LiquidToggle
import com.liquidmusicglass.ui.theme.LiquidTheme
import com.liquidmusicglass.ui.theme.DarkSettingsGradient
import com.liquidmusicglass.ui.theme.LightSettingsGradient

private val AppleRed = Color(0xFFFC3C44)
private val AppleGreen = Color(0xFF34C759)
// SubtitleColor removed — use LiquidTheme.colors.textSecondary

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
    val screenBackdrop = rememberLayerBackdrop()

    // ── Persistent state from AppSettings ──
    val gaplessEnabled by AppSettings.gaplessEnabled.collectAsState()
    val sleepTimerMinutes by AppSettings.sleepTimerMinutes.collectAsState()
    val sleepOptions = listOf(0, 15, 30, 45, 60, 90)

    val themeMode by com.liquidmusicglass.engine.PlayerController.themeMode.collectAsState()
    val themeLabels = listOf("System", "Dark", "Light")

    val lc = LiquidTheme.colors

    Box(modifier = Modifier.fillMaxSize()) {
        // ── Ambient background ──
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    if (lc.isDark) DarkSettingsGradient else LightSettingsGradient
                )
                .layerBackdrop(screenBackdrop)
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(horizontal = 20.dp)
        ) {
            Spacer(modifier = Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
            Spacer(modifier = Modifier.height(12.dp))

            // ── Header ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .drawBackdrop(
                            backdrop = screenBackdrop,
                            shape = { Capsule() },
                            effects = {
                                vibrancy()
                                blur(4.dp.toPx())
                                lens(18.dp.toPx(), 24.dp.toPx(), chromaticAberration = true)
                            },
                            highlight = {
                                Highlight.Ambient
                            },
                            shadow = {
                                Shadow(radius = 4.dp, color = Color.Black.copy(alpha = 0.12f))
                            },
                            innerShadow = {
                                InnerShadow(radius = 3.dp, alpha = 0.2f)
                            },
                            onDrawSurface = {
                                drawRect(Color.White.copy(alpha = 0.03f))
                                drawRect(
                                    color = Color.White.copy(alpha = 0.12f),
                                    style = Stroke(width = 1.dp.toPx())
                                )
                            }
                        )
                        .clip(CircleShape)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { onBack() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = null,
                        tint = LiquidTheme.colors.iconDefault,
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

            // ═══════════════════════════════════════════
            //  PLAYBACK
            // ═══════════════════════════════════════════
            SectionLabel("PLAYBACK")

            GlassCapsuleCard(backdrop = screenBackdrop) {
                SettingsToggleItem(
                    title = "AutoMix",
                    subtitle = "ML-powered DJ transitions",
                    checked = autoMixEnabled,
                    onCheckedChange = onAutoMixChange
                )
                GlassDivider()
                SettingsToggleItem(
                    title = "Gapless Playback",
                    subtitle = "No silence between tracks",
                    checked = gaplessEnabled,
                    onCheckedChange = { AppSettings.setGapless(it) }
                )
                GlassDivider()
                SettingsActionItem(
                    title = "Equalizer",
                    subtitle = "Bass Boost, Surround, Presets",
                    icon = Icons.Rounded.Equalizer,
                    onClick = onOpenEqualizer
                )
            }

            Spacer(modifier = Modifier.height(28.dp))

            // ═══════════════════════════════════════════
            //  SLEEP TIMER
            // ═══════════════════════════════════════════
            SectionLabel("SLEEP TIMER")

            GlassCapsuleCard(backdrop = screenBackdrop) {
                SleepTimerSelector(
                    options = sleepOptions,
                    selectedMinutes = sleepTimerMinutes,
                    onSelect = { AppSettings.setSleepTimer(it) },
                    backdrop = screenBackdrop
                )
            }

            Spacer(modifier = Modifier.height(28.dp))

            // ═══════════════════════════════════════════
            // ═══════════════════════════════════════════
            //  ICM MUSIC API
            // ═══════════════════════════════════════════
            SectionLabel("ICM MUSIC API")

            var apiKeyValue by remember {
                mutableStateOf(
                    context.getSharedPreferences("icm", Context.MODE_PRIVATE)
                        .getString("api_key", "") ?: ""
                )
            }
            
            // Stream quality selector
            val qualityOptions = listOf("128K", "256K", "320K", "ALAC")
            var selectedQuality by remember { 
                mutableStateOf(
                    context.getSharedPreferences("icm", Context.MODE_PRIVATE)
                        .getString("stream_quality", "256K") ?: "256K"
                )
            }
            
            // Region selector
            val regionOptions = listOf("us", "ru", "nz")
            var selectedRegion by remember {
                mutableStateOf(
                    context.getSharedPreferences("icm", Context.MODE_PRIVATE)
                        .getString("region", "us") ?: "us"
                )
            }
            
            GlassCapsuleCard(backdrop = screenBackdrop) {
                Column(modifier = Modifier.padding(vertical = 14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Partner API Key",
                            color = LiquidTheme.colors.textPrimary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium
                        )
                        val isActive = apiKeyValue.isNotBlank() && apiKeyValue.startsWith("pk_")
                        Text(
                            if (isActive) "Active" else "Not set",
                            color = if (isActive) Color(0xFF34C759) else LiquidTheme.colors.textTertiary,
                            fontSize = 13.sp
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    androidx.compose.foundation.text.BasicTextField(
                        value = apiKeyValue,
                        onValueChange = { newValue ->
                            apiKeyValue = newValue
                            context.getSharedPreferences("icm", Context.MODE_PRIVATE)
                                .edit().putString("api_key", newValue).apply()
                            if (newValue.isNotBlank() && newValue.startsWith("pk_")) {
                                com.liquidmusicglass.api.icm.IcmRepository.init(newValue)
                            }
                        },
                        textStyle = androidx.compose.ui.text.TextStyle(
                            color = LiquidTheme.colors.textPrimary,
                            fontSize = 14.sp
                        ),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        decorationBox = { innerTextField ->
                            Box {
                                if (apiKeyValue.isEmpty()) {
                                    Text(
                                        "pk_yourname_xxxxxxxxxxxx",
                                        color = LiquidTheme.colors.textTertiary,
                                        fontSize = 14.sp
                                    )
                                }
                                innerTextField()
                            }
                        }
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Get your key at byicloud.online/partners",
                        color = LiquidTheme.colors.textTertiary,
                        fontSize = 11.sp
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    GlassDivider()
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    // Stream Quality
                    Text(
                        "Stream Quality",
                        color = LiquidTheme.colors.textPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        qualityOptions.forEach { quality ->
                            GlassCapsuleChip(
                                text = quality,
                                isSelected = quality == selectedQuality,
                                backdrop = screenBackdrop,
                                onClick = {
                                    selectedQuality = quality
                                    context.getSharedPreferences("icm", Context.MODE_PRIVATE)
                                        .edit().putString("stream_quality", quality).apply()
                                    com.liquidmusicglass.api.icm.IcmRepository.streamQuality = quality
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    GlassDivider()
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    // Region
                    Text(
                        "Region",
                        color = LiquidTheme.colors.textPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        regionOptions.forEach { region ->
                            GlassCapsuleChip(
                                text = region.uppercase(),
                                isSelected = region == selectedRegion,
                                backdrop = screenBackdrop,
                                onClick = {
                                    selectedRegion = region
                                    context.getSharedPreferences("icm", Context.MODE_PRIVATE)
                                        .edit().putString("region", region).apply()
                                    com.liquidmusicglass.api.icm.IcmRepository.region = region
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            // ═══════════════════════════════════════════
            //  ABOUT
            // ═══════════════════════════════════════════
            SectionLabel("ABOUT")

            GlassCapsuleCard(backdrop = screenBackdrop) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Version", color = LiquidTheme.colors.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    Text("1.0.0", color = LiquidTheme.colors.textSecondary, fontSize = 16.sp)
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            // ═══════════════════════════════════════════
            //  DEVELOPER
            // ═══════════════════════════════════════════
            SectionLabel("DEVELOPER")

            GlassCapsuleCard(backdrop = screenBackdrop) {
                SettingsLinkItem(
                    title = "GitHub",
                    subtitle = "stanislavdev987",
                    onClick = {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/stanislavdev987"))
                        )
                    }
                )
                GlassDivider()
                SettingsLinkItem(
                    title = "Telegram",
                    subtitle = "@fengbei1998",
                    onClick = {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/fengbei1998"))
                        )
                    }
                )
            }

            Spacer(modifier = Modifier.height(200.dp))
        }
    }
}

// ═══════════════════════════════════════════════════════════
//  Section label
// ═══════════════════════════════════════════════════════════

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        color = LiquidTheme.colors.sectionLabel,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 4.dp, bottom = 10.dp)
    )
}

// ═══════════════════════════════════════════════════════════
//  Glass Capsule Card — Kyant0 liquid glass
// ═══════════════════════════════════════════════════════════

@Composable
private fun GlassCapsuleCard(
    backdrop: LayerBackdrop,
    content: @Composable () -> Unit
) {
    val shape = RoundedCornerShape(22.dp)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .drawBackdrop(
                backdrop = backdrop,
                shape = { shape },
                effects = {
                    vibrancy()
                    blur(4.dp.toPx())
                    lens(
                        refractionHeight = 18.dp.toPx(),
                        refractionAmount = 22.dp.toPx(),
                        chromaticAberration = true
                    )
                },
                highlight = {
                    Highlight.Ambient.copy(alpha = 0.6f)
                },
                shadow = {
                    Shadow(
                        radius = 8.dp,
                        color = Color.Black.copy(alpha = 0.15f)
                    )
                },
                innerShadow = {
                    InnerShadow(radius = 4.dp, alpha = 0.3f)
                },
                onDrawSurface = {
                    drawRect(Color.White.copy(alpha = 0.05f))
                    drawRect(
                        color = Color.White.copy(alpha = 0.12f),
                        style = Stroke(width = 1.dp.toPx())
                    )
                }
            )
            .padding(horizontal = 20.dp, vertical = 6.dp)
    ) {
        Column {
            content()
        }
    }
}

// ═══════════════════════════════════════════════════════════
//  Glass divider
// ═══════════════════════════════════════════════════════════

@Composable
private fun GlassDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(0.5.dp)
            .background(Color.White.copy(alpha = 0.06f))
    )
}

// ═══════════════════════════════════════════════════════════
//  Toggle item
// ═══════════════════════════════════════════════════════════

@Composable
private fun SettingsToggleItem(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    backdrop: Backdrop = rememberLayerBackdrop()
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = LiquidTheme.colors.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Spacer(modifier = Modifier.height(2.dp))
            Text(subtitle, color = LiquidTheme.colors.textSecondary, fontSize = 14.sp)
        }
        Spacer(modifier = Modifier.width(12.dp))
        LiquidToggle(
            selected = { checked },
            onSelect = onCheckedChange,
            backdrop = backdrop
        )
    }
}

// ═══════════════════════════════════════════════════════════
//  Slider item
// ═══════════════════════════════════════════════════════════

@Composable
private fun SettingsSliderItem(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    valueLabel: String,
    onValueChange: (Float) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, color = LiquidTheme.colors.textSecondary, fontSize = 14.sp)
            Text(valueLabel, color = AppleRed, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(modifier = Modifier.height(4.dp))
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = AppleRed,
                inactiveTrackColor = Color.White.copy(alpha = 0.12f),
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent
            ),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

// ═══════════════════════════════════════════════════════════
//  Action item
// ═══════════════════════════════════════════════════════════

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
                indication = null,
                onClick = onClick
            )
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = AppleRed, modifier = Modifier.size(22.dp))
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = LiquidTheme.colors.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Spacer(modifier = Modifier.height(2.dp))
            Text(subtitle, color = LiquidTheme.colors.textSecondary, fontSize = 14.sp)
        }
        Icon(
            Icons.Rounded.ChevronRight, null,
            tint = LiquidTheme.colors.textTertiary,
            modifier = Modifier.size(20.dp)
        )
    }
}

// ═══════════════════════════════════════════════════════════
//  Link item
// ═══════════════════════════════════════════════════════════

@Composable
private fun SettingsLinkItem(
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = LiquidTheme.colors.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Spacer(modifier = Modifier.height(2.dp))
            Text(subtitle, color = AppleRed, fontSize = 14.sp)
        }
        Icon(
            Icons.Rounded.ChevronRight, null,
            tint = LiquidTheme.colors.textTertiary,
            modifier = Modifier.size(20.dp)
        )
    }
}

// ═══════════════════════════════════════════════════════════
//  Sleep Timer — glass capsule chips
// ═══════════════════════════════════════════════════════════

@Composable
private fun SleepTimerSelector(
    options: List<Int>,
    selectedMinutes: Int,
    onSelect: (Int) -> Unit,
    backdrop: LayerBackdrop
) {
    Column(modifier = Modifier.padding(vertical = 12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Rounded.Timer, null, tint = AppleRed, modifier = Modifier.size(22.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = if (selectedMinutes == 0) "Off" else "Sleep in $selectedMinutes min",
                color = LiquidTheme.colors.textPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium
            )
        }
        Spacer(modifier = Modifier.height(14.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            options.forEach { minutes ->
                val isSelected = minutes == selectedMinutes
                GlassCapsuleChip(
                    text = if (minutes == 0) "Off" else "${minutes}m",
                    isSelected = isSelected,
                    backdrop = backdrop,
                    onClick = { onSelect(minutes) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════
//  Theme — glass capsule selector
// ═══════════════════════════════════════════════════════════

@Composable
private fun ThemeCapsuleSelector(
    labels: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    backdrop: LayerBackdrop
) {
    Column(modifier = Modifier.padding(vertical = 12.dp)) {
        Text("Theme", color = LiquidTheme.colors.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        Spacer(modifier = Modifier.height(12.dp))

        // Outer glass capsule container
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(46.dp)
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { Capsule() },
                    effects = {
                        vibrancy()
                        blur(4.dp.toPx())
                    },
                    onDrawSurface = {
                        drawRect(Color.White.copy(alpha = 0.03f))
                        drawRect(
                            color = Color.White.copy(alpha = 0.06f),
                            style = Stroke(width = 1.dp.toPx())
                        )
                    }
                )
                .padding(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                labels.forEachIndexed { index, label ->
                    val isSelected = index == selectedIndex

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(38.dp)
                            .then(
                                if (isSelected) {
                                    Modifier.drawBackdrop(
                                        backdrop = backdrop,
                                        shape = { Capsule() },
                                        effects = {
                                            vibrancy()
                                            blur(4.dp.toPx())
                                            lens(16.dp.toPx(), 24.dp.toPx(), chromaticAberration = true)
                                        },
                                        onDrawSurface = {
                                            drawRect(AppleRed)
                                        }
                                    )
                                } else Modifier
                            )
                            .clip(RoundedCornerShape(50))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = { onSelect(index) }
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
    }
}

// ═══════════════════════════════════════════════════════════
//  Glass Capsule Chip (reusable)
// ═══════════════════════════════════════════════════════════

@Composable
private fun GlassCapsuleChip(
    text: String,
    isSelected: Boolean,
    backdrop: LayerBackdrop,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .height(36.dp)
            .drawBackdrop(
                backdrop = backdrop,
                shape = { Capsule() },
                effects = {
                    vibrancy()
                    blur(4.dp.toPx())
                    if (isSelected) {
                        lens(14.dp.toPx(), 20.dp.toPx(), chromaticAberration = true)
                    }
                },
                onDrawSurface = {
                    if (isSelected) {
                        drawRect(AppleRed)
                    } else {
                        drawRect(Color.White.copy(alpha = 0.04f))
                        drawRect(
                            color = Color.White.copy(alpha = 0.08f),
                            style = Stroke(width = 1.dp.toPx())
                        )
                    }
                }
            )
            .clip(RoundedCornerShape(50))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        val textColor by animateColorAsState(
            targetValue = if (isSelected) Color.White else LiquidTheme.colors.textSecondary,
            animationSpec = tween(200),
            label = "chipText"
        )
        Text(
            text = text,
            color = textColor,
            fontSize = 13.sp,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

// ═══════════════════════════════════════════════════════════
//  Apple-style toggle
// ═══════════════════════════════════════════════════════════

// ═══════════════════════════════════════════════════════════
//  System equalizer
// ═══════════════════════════════════════════════════════════

private fun openSystemEqualizer(context: Context) {
    try {
        val intent = Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL).apply {
            putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
            putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
        }
        context.startActivity(intent)
    } catch (_: Exception) {}
}
