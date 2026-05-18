package com.liquidmusicglass.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liquidmusicglass.engine.AudioEffectsEngine
import com.liquidmusicglass.engine.AudioService
import com.liquidmusicglass.ui.liquid.LiquidToggle
import com.liquidmusicglass.ui.theme.LiquidTheme

private val AppleRed = Color(0xFFFC3C44)

@Composable
fun EqualizerScreen(onBack: () -> Unit) {
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
                CircleButton(lc) { onBack() }
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    "Equalizer",
                    color = lc.textPrimary,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                CircleButton(lc, icon = Icons.Rounded.Refresh, tint = AppleRed) {
                    AudioEffectsEngine.reset()
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // EQ Enable
            PlainCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "Equalizer",
                        color = lc.textPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    val toggleBackdrop = com.kyant.backdrop.backdrops.rememberLayerBackdrop()
                    LiquidToggle(
                        selected = { eqEnabled },
                        onSelect = { AudioEffectsEngine.setEnabled(it) },
                        backdrop = toggleBackdrop
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Frequency Response Curve
            FrequencyResponseCurve(
                bandLevels = bandLevels,
                minLevel = AudioEffectsEngine.minLevel,
                maxLevel = AudioEffectsEngine.maxLevel,
                enabled = eqEnabled,
                modifier = Modifier.fillMaxWidth().height(120.dp)
            )

            Spacer(modifier = Modifier.height(20.dp))

            // 5 Horizontal Band Sliders
            PlainCard {
                Text(
                    "Bands",
                    color = lc.textSecondary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 8.dp, bottom = 16.dp)
                )

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

            // Presets
            Text(
                "Presets",
                color = lc.sectionLabel,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 4.dp, bottom = 10.dp)
            )

            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(horizontal = 4.dp)
            ) {
                items(AudioEffectsEngine.presets, key = { it.name }) { preset ->
                    PresetChip(
                        name = preset.name,
                        isSelected = currentPreset == preset.name,
                        lc = lc,
                        onClick = {
                            AudioEffectsEngine.applyPreset(preset)
                            if (!eqEnabled) AudioEffectsEngine.setEnabled(true)
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Bass Boost
            PlainCard {
                EffectRow("Bass Boost", bassBoost, 1000, eqEnabled) {
                    AudioEffectsEngine.setBassBoost(it)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Surround
            PlainCard {
                EffectRow("Surround", virtualizer, 1000, eqEnabled) {
                    AudioEffectsEngine.setVirtualizer(it)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Loudness
            PlainCard {
                EffectRow("Loudness", loudness, 1000, eqEnabled) {
                    AudioEffectsEngine.setLoudness(it)
                }
            }

            Spacer(modifier = Modifier.height(200.dp))
        }
    }
}

// ── UI Components ──

@Composable
private fun PlainCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1C1C1E), RoundedCornerShape(12.dp))
            .padding(horizontal = 20.dp, vertical = 6.dp),
        content = content
    )
}

@Composable
private fun CircleButton(
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

        Slider(
            value = fraction,
            onValueChange = { f ->
                val newLevel = (minLevel + f * range).toInt()
                onLevelChange(newLevel)
            },
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
                val trackFraction = sliderState.value
                Canvas(
                    modifier = Modifier.fillMaxWidth().height(6.dp)
                ) {
                    val trackHeight = size.height
                    val radius = trackHeight / 2f

                    drawRoundRect(
                        color = if (enabled) Color.White.copy(alpha = 0.12f)
                        else Color.White.copy(alpha = 0.06f),
                        cornerRadius = CornerRadius(radius),
                        size = Size(size.width, trackHeight)
                    )

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

        val gridColor = Color.White.copy(alpha = 0.05f)
        for (i in 0..4) drawLine(gridColor, Offset(0f, h * i / 4f), Offset(w, h * i / 4f), 0.5f)

        val zeroY = h * (maxLevel.toFloat() / range)
        drawLine(Color.White.copy(alpha = 0.10f), Offset(0f, zeroY), Offset(w, zeroY), 1f)

        val points = bandLevels.mapIndexed { i, level ->
            val x = w * i / (n - 1).toFloat()
            val y = (h * (maxLevel - level) / range).coerceIn(0f, h)
            Offset(x, y)
        }

        val path = Path().apply {
            moveTo(points[0].x, points[0].y)
            for (i in 1 until points.size) {
                val cpx = (points[i - 1].x + points[i].x) / 2f
                cubicTo(cpx, points[i - 1].y, cpx, points[i].y, points[i].x, points[i].y)
            }
        }

        val fillPath = Path().apply {
            addPath(path)
            lineTo(points.last().x, h)
            lineTo(points.first().x, h)
            close()
        }
        drawPath(
            fillPath,
            Brush.verticalGradient(
                listOf(AppleRed.copy(alpha = 0.25f * alpha), Color.Transparent)
            )
        )

        drawPath(
            path,
            AppleRed.copy(alpha = alpha),
            style = Stroke(2.5f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )

        points.forEach { p ->
            drawCircle(AppleRed.copy(alpha = alpha), 4f, p)
            drawCircle(Color.White.copy(alpha = alpha * 0.8f), 2f, p)
        }
    }
}

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
            Text(
                label,
                color = lc.textPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                "${(value * 100f / maxValue).toInt()}%",
                color = if (value > 0) AppleRed else lc.textTertiary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
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

@Composable
private fun PresetChip(
    name: String,
    isSelected: Boolean,
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
            .background(
                if (isSelected) AppleRed else Color(0xFF1C1C1E),
                RoundedCornerShape(50)
            )
            .clip(RoundedCornerShape(50))
            .clickable(remember { MutableInteractionSource() }, null, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            name,
            color = textColor,
            fontSize = 13.sp,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}
