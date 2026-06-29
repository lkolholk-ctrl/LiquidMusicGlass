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
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import com.liquidmusicglass.engine.AudioFxController
import com.liquidmusicglass.ui.theme.LiquidColors
import com.liquidmusicglass.ui.theme.LiquidTheme
import kotlin.math.roundToInt

@Composable
fun AudioFxScreen(onBack: () -> Unit) {
    val lc = LiquidTheme.colors
    val scroll = rememberScrollState()

    val master by AudioFxController.masterEnabled.collectAsState()

    Box(modifier = Modifier.fillMaxSize().background(lc.settingsBackground)) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 20.dp)
        ) {
            Spacer(modifier = Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
            Spacer(modifier = Modifier.height(12.dp))

            // Header + master enable
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                CircleButton(lc) { onBack() }
                Spacer(modifier = Modifier.width(16.dp))
                Text("Audio", color = lc.textPrimary, fontSize = 24.sp,
                    fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                FxSwitch(master) { AudioFxController.setMasterEnabled(it) }
            }
            Spacer(modifier = Modifier.height(16.dp))

            Box(modifier = Modifier.alpha(if (master) 1f else 0.4f)) {
                Column {
                    PreampSection(lc, master)
                    Spacer(Modifier.height(14.dp))
                    EqSection(lc, master)
                    Spacer(Modifier.height(14.dp))
                    BassSection(lc, master)
                    Spacer(Modifier.height(14.dp))
                    LoudnessSection(lc, master)
                    Spacer(Modifier.height(14.dp))
                    StereoSection(lc, master)
                    Spacer(Modifier.height(14.dp))
                    CompressorSection(lc, master)
                    Spacer(Modifier.height(14.dp))
                    LimiterSection(lc, master)
                    Spacer(Modifier.height(20.dp))
                    ResetButton(lc, master)
                }
            }
            Spacer(modifier = Modifier.height(40.dp))
        }
    }
}

// ── Sections ─────────────────────────────────────────────────────────────────

@Composable
private fun PreampSection(lc: LiquidColors, enabled: Boolean) {
    val v by AudioFxController.preamp01.collectAsState()
    val db = (AudioFxController.PREAMP_MIN_DB + (AudioFxController.PREAMP_MAX_DB - AudioFxController.PREAMP_MIN_DB) * v)
    Section("Preamp", lc, valueText = "${db.roundToInt()} dB") {
        FxSlider(value = v, range = 0f..1f, enabled = enabled,
            onChange = { AudioFxController.setPreamp01(it) }, lc = lc)
        Text("Логарифмическая громкость — равномерно по всей шкале.",
            color = lc.textSecondary, fontSize = 12.sp)
    }
}

@Composable
private fun EqSection(lc: LiquidColors, master: Boolean) {
    val on by AudioFxController.eqEnabled.collectAsState()
    val gains by AudioFxController.eqGains.collectAsState()
    val preset by AudioFxController.eqPreset.collectAsState()
    val active = master && on
    SectionWithToggle("Equalizer", lc, on, { AudioFxController.setEqEnabled(it) }, master) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .alpha(if (active) 1f else 0.4f),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AudioFxController.EQ_PRESETS.forEachIndexed { i, p ->
                Chip(p.name, preset == i, lc, active) { AudioFxController.applyEqPreset(i) }
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(
            modifier = Modifier.fillMaxWidth().alpha(if (active) 1f else 0.4f),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            for (band in 0 until AudioFxController.BAND_COUNT) {
                val g = gains.getOrElse(band) { 0f }
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    BandSlider(g, AudioFxController.BAND_LABELS[band], lc, active) {
                        AudioFxController.setEqBand(band, it)
                    }
                }
            }
        }
    }
}

@Composable
private fun BassSection(lc: LiquidColors, master: Boolean) {
    val on by AudioFxController.bassEnabled.collectAsState()
    val freq by AudioFxController.bassFreq.collectAsState()
    val gain by AudioFxController.bassGain.collectAsState()
    val active = master && on
    SectionWithToggle("Bass Boost", lc, on, { AudioFxController.setBassEnabled(it) }, master) {
        Box(Modifier.alpha(if (active) 1f else 0.4f)) {
            Column {
                LabelValue("Частота", "${freq.roundToInt()} Hz", lc)
                FxSlider(freq, AudioFxController.BASS_FREQ_MIN..AudioFxController.BASS_FREQ_MAX, active,
                    { AudioFxController.setBassFreq(it) }, lc)
                Spacer(Modifier.height(8.dp))
                LabelValue("Усиление", "+${gain.roundToInt()} dB", lc)
                FxSlider(gain, 0f..12f, active, { AudioFxController.setBassGain(it) }, lc)
            }
        }
    }
}

@Composable
private fun LoudnessSection(lc: LiquidColors, master: Boolean) {
    val on by AudioFxController.loudnessEnabled.collectAsState()
    SectionWithToggle("Loudness Compensation", lc, on, { AudioFxController.setLoudnessEnabled(it) }, master) {
        Text("Поднимает бас (и чуть верхи) на тихой громкости — ровный звук на любом уровне.",
            color = lc.textSecondary, fontSize = 12.sp)
    }
}

@Composable
private fun StereoSection(lc: LiquidColors, master: Boolean) {
    val w by AudioFxController.stereoWidth.collectAsState()
    Section("Stereo Width", lc, valueText = "${(w * 100).roundToInt()}%") {
        FxSlider(w, 0f..2f, master, { AudioFxController.setStereoWidth(it) }, lc)
        Text("0% — моно, 100% — норма, 200% — широко.", color = lc.textSecondary, fontSize = 12.sp)
    }
}

@Composable
private fun CompressorSection(lc: LiquidColors, master: Boolean) {
    val on by AudioFxController.compEnabled.collectAsState()
    val preset by AudioFxController.compPreset.collectAsState()
    val active = master && on
    SectionWithToggle("Compressor", lc, on, { AudioFxController.setCompEnabled(it) }, master) {
        Row(
            modifier = Modifier.fillMaxWidth().alpha(if (active) 1f else 0.4f),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AudioFxController.COMP_PRESETS.forEachIndexed { i, p ->
                Chip(p.name, preset == i, lc, active) { AudioFxController.setCompPreset(i) }
            }
        }
    }
}

@Composable
private fun LimiterSection(lc: LiquidColors, master: Boolean) {
    val on by AudioFxController.limEnabled.collectAsState()
    val thr by AudioFxController.limThreshold.collectAsState()
    val active = master && on
    SectionWithToggle("Limiter", lc, on, { AudioFxController.setLimEnabled(it) }, master) {
        Box(Modifier.alpha(if (active) 1f else 0.4f)) {
            Column {
                LabelValue("Порог", "${"%.1f".format(thr)} dB", lc)
                FxSlider(thr, -6f..0f, active, { AudioFxController.setLimThreshold(it) }, lc)
                Text("Защита от клиппинга на громком звуке. Рекомендуется ВКЛ.",
                    color = lc.textSecondary, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun ResetButton(lc: LiquidColors, enabled: Boolean) {
    Box(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(lc.cardSurface)
            .clickable(enabled = enabled) { AudioFxController.resetAll() }
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text("Reset to default", color = lc.accent, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

// ── Reusable building blocks ─────────────────────────────────────────────────

@Composable
private fun Section(title: String, lc: LiquidColors, valueText: String? = null, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(lc.cardSurface).padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = lc.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f))
            if (valueText != null) Text(valueText, color = lc.accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(10.dp))
        content()
    }
}

@Composable
private fun SectionWithToggle(
    title: String, lc: LiquidColors, checked: Boolean,
    onCheckedChange: (Boolean) -> Unit, master: Boolean, content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(lc.cardSurface).padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = lc.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f))
            FxSwitch(checked && master, enabled = master) { onCheckedChange(it) }
        }
        Spacer(Modifier.height(10.dp))
        content()
    }
}

@Composable
private fun LabelValue(label: String, value: String, lc: LiquidColors) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(label, color = lc.textSecondary, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(value, color = lc.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun FxSlider(
    value: Float, range: ClosedFloatingPointRange<Float>, enabled: Boolean,
    onChange: (Float) -> Unit, lc: LiquidColors
) {
    Slider(
        value = value, onValueChange = onChange, valueRange = range, enabled = enabled,
        colors = SliderDefaults.colors(
            thumbColor = lc.accent, activeTrackColor = lc.accent,
            inactiveTrackColor = if (lc.isDark) Color(0xFF2C2C2E) else Color(0xFFE5E5EA)
        )
    )
}

@Composable
private fun FxSwitch(checked: Boolean, enabled: Boolean = true, onCheckedChange: (Boolean) -> Unit) {
    val lc = LiquidTheme.colors
    Switch(
        checked = checked, onCheckedChange = onCheckedChange, enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedThumbColor = Color.White, checkedTrackColor = lc.accent,
            uncheckedThumbColor = Color.White,
            uncheckedTrackColor = if (lc.isDark) Color(0xFF3A3A3C) else Color(0xFFD1D1D6)
        )
    )
}

@Composable
private fun Chip(label: String, selected: Boolean, lc: LiquidColors, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier.clip(CircleShape).background(if (selected) lc.accent else lc.settingsBackground)
            .clickable(remember { MutableInteractionSource() }, null, enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = if (selected) Color.White else lc.textSecondary,
            fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Вертикальный слайдер полосы EQ: центр = 0 дБ, вверх boost, вниз cut. */
@Composable
private fun BandSlider(value: Float, label: String, lc: LiquidColors, interactive: Boolean, onChange: (Float) -> Unit) {
    val range = AudioFxController.EQ_MAX_DB - AudioFxController.EQ_MIN_DB // 24
    var trackHeightPx by remember { mutableFloatStateOf(1f) }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("${if (value > 0) "+" else ""}${value.roundToInt()}",
            color = if (value != 0f) lc.accent else lc.textSecondary,
            fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        Spacer(modifier = Modifier.height(8.dp))
        Box(
            modifier = Modifier.height(160.dp).width(34.dp).pointerInput(interactive) {
                if (!interactive) return@pointerInput
                detectVerticalDragGestures { change, dragAmount ->
                    change.consume()
                    val deltaDb = -(dragAmount / trackHeightPx) * range
                    onChange((value + deltaDb).coerceIn(AudioFxController.EQ_MIN_DB, AudioFxController.EQ_MAX_DB))
                }
            },
            contentAlignment = Alignment.Center
        ) {
            val trackColor = if (lc.isDark) Color(0xFF2C2C2E) else Color(0xFFE5E5EA)
            Canvas(modifier = Modifier.fillMaxHeight().width(6.dp)) {
                trackHeightPx = size.height
                val w = size.width; val h = size.height; val r = w / 2f
                drawRoundRect(color = trackColor, topLeft = Offset(0f, 0f), size = Size(w, h),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r))
                val t = (AudioFxController.EQ_MAX_DB - value) / range
                val thumbY = (t * h).coerceIn(0f, h)
                val centerY = h / 2f
                val top = minOf(centerY, thumbY); val bot = maxOf(centerY, thumbY)
                drawRoundRect(color = lc.accent, topLeft = Offset(0f, top),
                    size = Size(w, (bot - top).coerceAtLeast(1f)),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r))
            }
            Canvas(modifier = Modifier.fillMaxHeight().width(34.dp)) {
                val h = size.height; val cx = size.width / 2f
                val t = (AudioFxController.EQ_MAX_DB - value) / range
                val y = (t * h).coerceIn(0f, h)
                drawCircle(color = Color.White, radius = 11.dp.toPx(), center = Offset(cx, y))
                drawCircle(color = lc.accent, radius = 7.dp.toPx(), center = Offset(cx, y))
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(label, color = lc.textSecondary, fontSize = 10.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun CircleButton(lc: LiquidColors, onClick: () -> Unit) {
    Box(
        modifier = Modifier.size(40.dp)
            .background(if (lc.isDark) Color(0xFF1C1C1E) else Color(0xFFF2F2F7), CircleShape)
            .clip(CircleShape)
            .clickable(remember { MutableInteractionSource() }, null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.AutoMirrored.Rounded.ArrowBack, null, tint = lc.iconDefault, modifier = Modifier.size(22.dp))
    }
}
