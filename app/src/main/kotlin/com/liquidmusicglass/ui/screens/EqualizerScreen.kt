package com.liquidmusicglass.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import com.liquidmusicglass.engine.AudioEffectsEngine
import com.liquidmusicglass.engine.AudioService
import com.liquidmusicglass.ui.liquid.LiquidToggle
import com.liquidmusicglass.ui.theme.LiquidTheme

private val AppleRed = Color(0xFFFC3C44)

@Composable
fun EqualizerScreen(onBack: () -> Unit) {
    val screenBackdrop = rememberLayerBackdrop()
    val lc = LiquidTheme.colors
    val scroll = rememberScrollState()

    val eqEnabled by AudioEffectsEngine.enabled.collectAsState()
    val bandLevels by AudioEffectsEngine.bandLevels.collectAsState()
    val bassBoost by AudioEffectsEngine.bassBoostStrength.collectAsState()
    val virtualizer by AudioEffectsEngine.virtualizerStrength.collectAsState()
    val loudness by AudioEffectsEngine.loudnessGain.collectAsState()
    val currentPreset by AudioEffectsEngine.currentPreset.collectAsState()

    LaunchedEffect(Unit) {
        val sessionId = AudioService.getAudioSessionId()
        if (sessionId != 0) AudioEffectsEngine.init(sessionId)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(lc.screenBackground)
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
                GlassCircleButton(screenBackdrop, lc) { onBack() }
                Spacer(modifier = Modifier.width(16.dp))
                Text("Equalizer", color = lc.textPrimary, fontSize = 24.sp,
                    fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                GlassCircleButton(screenBackdrop, lc, icon = Icons.Rounded.Refresh,
                    tint = AppleRed) { AudioEffectsEngine.reset() }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // ── EQ Enable ──
            GlassCard(screenBackdrop, lc) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Equalizer", color = lc.textPrimary, fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold)
                    LiquidToggle(
                        selected = { eqEnabled },
                        onSelect = { AudioEffectsEngine.setEnabled(it) },
                        backdrop = screenBackdrop
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ── Frequency Response Curve ──
            FrequencyResponseCurve(
                bandLevels = bandLevels,
                minLevel = AudioEffectsEngine.minLevel,
                maxLevel = AudioEffectsEngine.maxLevel,
                enabled = eqEnabled,
                modifier = Modifier.fillMaxWidth().height(120.dp)
            )

            Spacer(modifier = Modifier.height(20.dp))

            // ── 5 Horizontal Band Sliders ──
            GlassCard(screenBackdrop, lc) {
                Text("Bands", color = lc.textSecondary, fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 8.dp, bottom = 16.dp))

                for (i in 0 until AudioEffectsEngine.numBands) {
                    val level = if (i < bandLevels.size) bandLevels[i] else 0
                    val freq = AudioEffectsEngine.bandFrequencies.getOrElse(i) { 0 }
                    HorizontalBandSlider(
                        freq = freq,
                        level = level,
                        minLevel = AudioEffectsEngine.minLevel,
                        maxLevel = AudioEffectsEngine.maxLevel,
                        enabled = eqEnabled,
                        onLevelChange = { AudioEffectsEngine.setBandLevel(i, it) }
                    )
                    if (i < AudioEffectsEngine.numBands - 1) {
                        Spacer(modifier = Modifier.height(14.dp))
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ── Presets ──
            Text("Presets", color = lc.sectionLabel, fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 4.dp, bottom = 10.dp))

            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(horizontal = 4.dp)
            ) {
                items(AudioEffectsEngine.presets, key = { it.name }) { preset ->
                    PresetChip(
                        name = preset.name,
                        isSelected = currentPreset == preset.name,
                        backdrop = screenBackdrop, lc = lc,
                        onClick = {
                            AudioEffectsEngine.applyPreset(preset)
                            if (!eqEnabled) AudioEffectsEngine.setEnabled(true)
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // ── Bass Boost ──
            GlassCard(screenBackdrop, lc) {
                EffectRow("Bass Boost", bassBoost, 1000, eqEnabled) {
                    AudioEffectsEngine.setBassBoost(it)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // ── Surround ──
            GlassCard(screenBackdrop, lc) {
                EffectRow("Surround", virtualizer, 1000, eqEnabled) {
                    AudioEffectsEngine.setVirtualizer(it)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // ── Loudness ──
            GlassCard(screenBackdrop, lc) {
                EffectRow("Loudness", loudness, 1000, eqEnabled) {
                    AudioEffectsEngine.setLoudness(it)
                }
            }

            Spacer(modifier = Modifier.height(200.dp))
        }
    }
}

// ═══════════════════════════════════════════════════════════
//  Horizontal Band Slider — large oval thumb
// ═══════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HorizontalBandSlider(
    freq: Int,
    level: Int,
    minLevel: Int,
    maxLevel: Int,
    enabled: Boolean,
    onLevelChange: (Int) -> Unit
) {
    val lc = LiquidTheme.colors
    val range = (maxLevel - minLevel).coerceAtLeast(1).toFloat()
    val fraction = (level - minLevel).toFloat() / range

    Column {
        // Freq + dB labels
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = AudioEffectsEngine.formatFreq(freq),
                color = lc.textSecondary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = AudioEffectsEngine.formatLevel(level),
                color = if (level != 0) AppleRed else lc.textTertiary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Slider with large oval thumb
        Slider(
            value = fraction,
            onValueChange = { f ->
                val newLevel = (minLevel + f * range).toInt()
                onLevelChange(newLevel)
            },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().height(32.dp),
            thumb = {
                // Large oval thumb like screenshot
                Box(
                    modifier = Modifier
                        .size(width = 28.dp, height = 36.dp)
                        .background(Color.White, RoundedCornerShape(10.dp))
                )
            },
            track = { sliderState ->
                // Custom track: white active, gray inactive, rounded
                val trackFraction = sliderState.value
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                ) {
                    val trackHeight = size.height
                    val radius = trackHeight / 2f

                    // Inactive track (full width)
                    drawRoundRect(
                        color = if (enabled) Color.White.copy(alpha = 0.12f)
                        else Color.White.copy(alpha = 0.06f),
                        cornerRadius = CornerRadius(radius),
                        size = Size(size.width, trackHeight)
                    )

                    // Active track
                    val activeWidth = size.width * trackFraction
                    if (activeWidth > 0f) {
                        drawRoundRect(
                            color = if (enabled) Color.White else Color.White.copy(0.3f),
                            cornerRadius = CornerRadius(radius),
                            size = Size(activeWidth, trackHeight)
                        )
                    }
                }
            }
        )
    }
}

// ═══════════════════════════════════════════════════════════
//  Frequency Response Curve
// ═══════════════════════════════════════════════════════════

@Composable
private fun FrequencyResponseCurve(
    bandLevels: IntArray,
    minLevel: Int,
    maxLevel: Int,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    val range = (maxLevel - minLevel).coerceAtLeast(1).toFloat()
    val alpha by animateFloatAsState(
        targetValue = if (enabled) 1f else 0.3f,
        animationSpec = tween(300), label = "curveAlpha"
    )

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val n = bandLevels.size
        if (n < 2) return@Canvas

        // Grid
        val gridColor = Color.White.copy(alpha = 0.05f)
        for (i in 0..4) drawLine(gridColor, Offset(0f, h*i/4f), Offset(w, h*i/4f), 0.5f)

        // Zero line
        val zeroY = h * (maxLevel.toFloat() / range)
        drawLine(Color.White.copy(alpha = 0.10f), Offset(0f, zeroY), Offset(w, zeroY), 1f)

        // Points
        val points = bandLevels.mapIndexed { i, level ->
            val x = w * i / (n - 1).toFloat()
            val y = (h * (maxLevel - level) / range).coerceIn(0f, h)
            Offset(x, y)
        }

        // Smooth curve
        val path = Path().apply {
            moveTo(points[0].x, points[0].y)
            for (i in 1 until points.size) {
                val cpx = (points[i-1].x + points[i].x) / 2f
                cubicTo(cpx, points[i-1].y, cpx, points[i].y, points[i].x, points[i].y)
            }
        }

        // Fill
        val fillPath = Path().apply {
            addPath(path)
            lineTo(points.last().x, h)
            lineTo(points.first().x, h)
            close()
        }
        drawPath(fillPath, Brush.verticalGradient(
            listOf(AppleRed.copy(alpha = 0.25f * alpha), Color.Transparent)
        ))

        // Stroke
        drawPath(path, AppleRed.copy(alpha = alpha),
            style = Stroke(2.5f, cap = StrokeCap.Round, join = StrokeJoin.Round))

        // Dots
        points.forEach { p ->
            drawCircle(AppleRed.copy(alpha = alpha), 4f, p)
            drawCircle(Color.White.copy(alpha = alpha * 0.8f), 2f, p)
        }
    }
}

// ═══════════════════════════════════════════════════════════
//  Effect Row (Bass Boost, Surround, Loudness)
// ═══════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EffectRow(
    label: String,
    value: Int,
    maxValue: Int,
    enabled: Boolean,
    onValueChange: (Int) -> Unit
) {
    val lc = LiquidTheme.colors

    Column(modifier = Modifier.padding(vertical = 10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, color = lc.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Text("${(value * 100f / maxValue).toInt()}%",
                color = if (value > 0) AppleRed else lc.textTertiary,
                fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(modifier = Modifier.height(8.dp))
        Slider(
            value = value.toFloat() / maxValue,
            onValueChange = { onValueChange((it * maxValue).toInt()) },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().height(32.dp),
            thumb = {
                Box(
                    modifier = Modifier
                        .size(width = 28.dp, height = 36.dp)
                        .background(Color.White, RoundedCornerShape(10.dp))
                )
            },
            track = { sliderState ->
                Canvas(modifier = Modifier.fillMaxWidth().height(6.dp)) {
                    val trackHeight = size.height
                    val radius = trackHeight / 2f
                    drawRoundRect(
                        color = if (enabled) Color.White.copy(0.12f) else Color.White.copy(0.06f),
                        cornerRadius = CornerRadius(radius),
                        size = Size(size.width, trackHeight)
                    )
                    val activeW = size.width * sliderState.value
                    if (activeW > 0f) {
                        drawRoundRect(
                            color = if (enabled) Color.White else Color.White.copy(0.3f),
                            cornerRadius = CornerRadius(radius),
                            size = Size(activeW, trackHeight)
                        )
                    }
                }
            }
        )
    }
}

// ═══════════════════════════════════════════════════════════
//  Preset Chip
// ═══════════════════════════════════════════════════════════

@Composable
private fun PresetChip(
    name: String,
    isSelected: Boolean,
    backdrop: LayerBackdrop,
    lc: com.liquidmusicglass.ui.theme.LiquidColors,
    onClick: () -> Unit
) {
    val textColor by animateColorAsState(
        targetValue = if (isSelected) Color.White else lc.textSecondary,
        animationSpec = tween(200), label = "presetText"
    )

    Box(
        modifier = Modifier
            .height(34.dp)
            .drawBackdrop(
                backdrop = backdrop,
                shape = { Capsule() },
                effects = {
                    vibrancy(); blur(4.dp.toPx())
                    if (isSelected) lens(14.dp.toPx(), 20.dp.toPx(), chromaticAberration = true)
                },
                onDrawSurface = {
                    if (isSelected) drawRect(AppleRed)
                    else {
                        drawRect(lc.chipBg)
                        drawRect(lc.chipBorder, style = Stroke(width = 1.dp.toPx()))
                    }
                }
            )
            .clip(RoundedCornerShape(50))
            .clickable(remember { MutableInteractionSource() }, null, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(name, color = textColor, fontSize = 13.sp,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

// ═══════════════════════════════════════════════════════════
//  Glass helpers
// ═══════════════════════════════════════════════════════════

@Composable
private fun GlassCircleButton(
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
                onDrawSurface = {
                    drawRect(lc.glassTint)
                    drawRect(lc.glassBorder, style = Stroke(width = 1.dp.toPx()))
                }
            )
            .clip(CircleShape)
            .clickable(remember { MutableInteractionSource() }, null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun GlassCard(
    backdrop: LayerBackdrop,
    lc: com.liquidmusicglass.ui.theme.LiquidColors,
    content: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .drawBackdrop(
                backdrop = backdrop,
                shape = { RoundedCornerShape(22.dp) },
                effects = {
                    vibrancy(); blur(4.dp.toPx())
                    lens(24.dp.toPx(), 32.dp.toPx(), chromaticAberration = true)
                },
                onDrawSurface = {
                    drawRect(lc.glassTint)
                    drawRect(lc.glassBorder, style = Stroke(width = 1.dp.toPx()))
                }
            )
            .padding(horizontal = 20.dp, vertical = 6.dp)
    ) { Column { content() } }
}
