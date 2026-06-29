package com.liquidmusicglass.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liquidmusicglass.engine.EqualizerController
import com.liquidmusicglass.ui.theme.LiquidColors
import com.liquidmusicglass.ui.theme.LiquidTheme
import kotlin.math.roundToInt

@Composable
fun EqualizerScreen(onBack: () -> Unit) {
    val lc = LiquidTheme.colors
    val scroll = rememberScrollState()

    val enabled by EqualizerController.enabled.collectAsState()
    val gains by EqualizerController.gains.collectAsState()
    val presetIndex by EqualizerController.presetIndex.collectAsState()

    Box(modifier = Modifier.fillMaxSize().background(lc.settingsBackground)) {
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
                CircleButton(lc) { onBack() }
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    "Equalizer",
                    color = lc.textPrimary,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = enabled,
                    onCheckedChange = { EqualizerController.setEnabled(it) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = lc.accent,
                        uncheckedThumbColor = Color.White,
                        uncheckedTrackColor = if (lc.isDark) Color(0xFF3A3A3C) else Color(0xFFD1D1D6)
                    )
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ── Preset chips ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .alpha(if (enabled) 1f else 0.4f),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                EqualizerController.PRESETS.forEachIndexed { i, preset ->
                    PresetChip(
                        label = preset.name,
                        selected = presetIndex == i,
                        lc = lc,
                        enabled = enabled
                    ) { EqualizerController.applyPreset(i) }
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            // ── 10 vertical band sliders ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .alpha(if (enabled) 1f else 0.4f),
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                for (band in 0 until EqualizerController.BAND_COUNT) {
                    val g = gains.getOrElse(band) { 0f }
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        BandSlider(
                            value = g,
                            label = EqualizerController.BAND_LABELS[band],
                            lc = lc,
                            interactive = enabled,
                            onChange = { EqualizerController.setBand(band, it) }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            // ── Reset ──
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(lc.cardSurface)
                    .clickable(enabled = enabled) { EqualizerController.reset() }
                    .padding(vertical = 14.dp)
                    .alpha(if (enabled) 1f else 0.4f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "Reset to Flat",
                    color = lc.accent,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(modifier = Modifier.height(40.dp))
        }
    }
}

@Composable
private fun PresetChip(
    label: String,
    selected: Boolean,
    lc: LiquidColors,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(if (selected) lc.accent else lc.cardSurface)
            .clickable(remember { MutableInteractionSource() }, null, enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (selected) Color.White else lc.textSecondary,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/**
 * Вертикальный слайдер полосы EQ: центр = 0 дБ, вверх = boost (+12), вниз = cut (−12).
 * Заливка идёт от центра к ползунку, поэтому сразу видно знак усиления.
 */
@Composable
private fun BandSlider(
    value: Float,
    label: String,
    lc: LiquidColors,
    interactive: Boolean,
    onChange: (Float) -> Unit
) {
    val range = EqualizerController.MAX_GAIN_DB - EqualizerController.MIN_GAIN_DB // 24
    var trackHeightPx by remember { mutableFloatStateOf(1f) }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        // dB readout
        Text(
            text = "${if (value > 0) "+" else ""}${value.roundToInt()}",
            color = if (value != 0f) lc.accent else lc.textSecondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(8.dp))

        Box(
            modifier = Modifier
                .height(180.dp)
                .width(34.dp)
                .pointerInput(interactive) {
                    if (!interactive) return@pointerInput
                    detectVerticalDragGestures { change, dragAmount ->
                        change.consume()
                        // вверх (отрицательный dy) → +dB
                        val deltaDb = -(dragAmount / trackHeightPx) * range
                        onChange((value + deltaDb).coerceIn(
                            EqualizerController.MIN_GAIN_DB, EqualizerController.MAX_GAIN_DB
                        ))
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            val trackColor = if (lc.isDark) Color(0xFF2C2C2E) else Color(0xFFE5E5EA)
            Canvas(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(6.dp)
            ) {
                trackHeightPx = size.height
                val w = size.width
                val h = size.height
                val r = w / 2f
                // фон трека
                drawRoundRect(
                    color = trackColor,
                    topLeft = Offset(0f, 0f),
                    size = Size(w, h),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r)
                )
                // позиция ползунка: value=+12 → y=0 (верх), value=-12 → y=h (низ)
                val t = (EqualizerController.MAX_GAIN_DB - value) / range // 0..1
                val thumbY = (t * h).coerceIn(0f, h)
                val centerY = h / 2f
                // заливка от центра к ползунку
                val top = minOf(centerY, thumbY)
                val bot = maxOf(centerY, thumbY)
                drawRoundRect(
                    color = lc.accent,
                    topLeft = Offset(0f, top),
                    size = Size(w, (bot - top).coerceAtLeast(1f)),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r)
                )
            }
            // ползунок (поверх трека) — позиционируем оффсетом
            ThumbDot(value = value, range = range, lc = lc)
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = label,
            color = lc.textSecondary,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun ThumbDot(value: Float, range: Float, lc: LiquidColors) {
    // Рисуем ползунок как отдельный Canvas во всю высоту, чтобы попасть в ту же
    // систему координат, что и трек (центр = 0 дБ).
    Canvas(modifier = Modifier.fillMaxHeight().width(34.dp)) {
        val h = size.height
        val cx = size.width / 2f
        val t = (EqualizerController.MAX_GAIN_DB - value) / range
        val y = (t * h).coerceIn(0f, h)
        drawCircle(color = Color.White, radius = 11.dp.toPx(), center = Offset(cx, y))
        drawCircle(color = lc.accent, radius = 7.dp.toPx(), center = Offset(cx, y))
    }
}

@Composable
private fun CircleButton(
    lc: LiquidColors,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .background(if (lc.isDark) Color(0xFF1C1C1E) else Color(0xFFF2F2F7), CircleShape)
            .clip(CircleShape)
            .clickable(remember { MutableInteractionSource() }, null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.AutoMirrored.Rounded.ArrowBack,
            null,
            tint = lc.iconDefault,
            modifier = Modifier.size(22.dp)
        )
    }
}
