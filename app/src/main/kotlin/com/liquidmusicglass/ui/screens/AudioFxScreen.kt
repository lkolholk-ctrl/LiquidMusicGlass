package com.liquidmusicglass.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Brush
import com.liquidmusicglass.engine.AudioFxController
import com.liquidmusicglass.ui.glass.liquidClickable
import com.liquidmusicglass.ui.theme.LiquidMotion
import com.liquidmusicglass.ui.liquid.LiquidSlider
import com.liquidmusicglass.ui.liquid.LiquidToggle
import com.liquidmusicglass.ui.theme.LiquidColors
import com.liquidmusicglass.ui.theme.LiquidTheme
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlin.math.roundToInt

/**
 * Backdrop для стеклянных ползунков. По правилу автора Kyant: стекло (drawBackdrop)
 * НЕЛЬЗЯ вкладывать внутрь layer-backdrop, который оно преломляет — иначе цикл в
 * дереве рендера → рекурсия → краш RenderThread. Поэтому захваченный фон лежит в
 * слое-СИБЛИНГЕ (рядом), а ползунки преломляют его, НЕ находясь внутри него.
 */
private val LocalFxBackdrop = compositionLocalOf<Backdrop?> { null }

@Composable
fun AudioFxScreen(onBack: () -> Unit) {
    val lc = LiquidTheme.colors
    val scroll = rememberScrollState()

    val master by AudioFxController.masterEnabled.collectAsState()
    val screenBackdrop = rememberLayerBackdrop()

    Box(modifier = Modifier.fillMaxSize().background(lc.settingsBackground)) {
        // СЛОЙ-СИБЛИНГ: захватываем фон (лёгкий градиент) в screenBackdrop. Ползунки
        // его преломляют, НО сами тут НЕ лежат (иначе backdrop вложен в себя → краш).
        Spacer(
            modifier = Modifier.fillMaxSize().layerBackdrop(screenBackdrop).background(
                Brush.verticalGradient(
                    listOf(lc.accent.copy(alpha = 0.10f), Color.Transparent, lc.accent.copy(alpha = 0.06f))
                )
            )
        )
        // ФОРЕГРАУНД-СИБЛИНГ: контент с ползунками (преломляет screenBackdrop, beside).
        CompositionLocalProvider(LocalFxBackdrop provides screenBackdrop) {
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
                    ParametricEqSection(lc, master)
                    Spacer(Modifier.height(14.dp))
                    BassSection(lc, master)
                    Spacer(Modifier.height(14.dp))
                    LoudnessSection(lc, master)
                    Spacer(Modifier.height(14.dp))
                    StereoSection(lc, master)
                    Spacer(Modifier.height(14.dp))
                    BalanceSection(lc, master)
                    Spacer(Modifier.height(14.dp))
                    MonoSection(lc, master)
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
}

// ── Sections ─────────────────────────────────────────────────────────────────

@Composable
private fun PreampSection(lc: LiquidColors, enabled: Boolean) {
    val v by AudioFxController.preamp01.collectAsState()
    val db = (AudioFxController.PREAMP_MIN_DB + (AudioFxController.PREAMP_MAX_DB - AudioFxController.PREAMP_MIN_DB) * v)
    Section("Preamp", lc, valueText = "${db.roundToInt()} dB") {
        FxSlider(value = v, range = 0f..1f, enabled = enabled,
            onChange = { AudioFxController.setPreamp01(it) }, lc = lc)
        Text("Logarithmic volume — even across the whole range.",
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
        Spacer(Modifier.height(10.dp))
        Column {
            for (band in 0 until AudioFxController.BAND_COUNT) {
                EqBandRow(band, gains.getOrElse(band) { 0f }, AudioFxController.BAND_LABELS[band], active, lc)
            }
        }
    }
}

/** Горизонтальная полоса EQ: [частота] [LiquidSlider со стеклом] [дБ]. */
@Composable
private fun EqBandRow(band: Int, gain: Float, label: String, active: Boolean, lc: LiquidColors) {
    val fallback = rememberLayerBackdrop()
    val backdrop = LocalFxBackdrop.current ?: fallback   // сиблинг-слой (beside), не вложенный
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).alpha(if (active) 1f else 0.5f),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.width(40.dp), color = lc.textSecondary,
            fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Box(modifier = Modifier.weight(1f)) {
            LiquidSlider(
                value = { gain },
                onValueChange = { if (active) AudioFxController.setEqBand(band, it) },
                valueRange = AudioFxController.EQ_MIN_DB..AudioFxController.EQ_MAX_DB,
                backdrop = backdrop,
                accentColor = lc.accent,
                trackColor = (if (lc.isDark) Color.White else Color.Black).copy(alpha = 0.18f)
            )
        }
        Text(
            text = "${if (gain > 0) "+" else ""}${gain.roundToInt()}",
            modifier = Modifier.width(34.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
            color = if (gain != 0f) lc.accent else lc.textSecondary,
            fontSize = 12.sp, fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun ParametricEqSection(lc: LiquidColors, master: Boolean) {
    val on by AudioFxController.paramEqEnabled.collectAsState()
    val bands by AudioFxController.paramBands.collectAsState()
    val active = master && on
    SectionWithToggle("Parametric EQ", lc, on, { AudioFxController.setParamEqEnabled(it) }, master) {
        Box(Modifier.alpha(if (active) 1f else 0.4f)) {
            Column {
                bands.forEachIndexed { i, b ->
                    if (i > 0) Spacer(Modifier.height(12.dp))
                    ParamBandControls(i, b, active, lc)
                }
            }
        }
    }
}

@Composable
private fun ParamBandControls(index: Int, band: AudioFxController.ParamBand, active: Boolean, lc: LiquidColors) {
    val fMin = AudioFxController.PARAM_FREQ_MIN
    val fMax = AudioFxController.PARAM_FREQ_MAX
    // Частоту крутим по логарифму (слайдер 0..1) — иначе низы «съедаются».
    val fNorm = (Math.log((band.freq / fMin).toDouble()) / Math.log((fMax / fMin).toDouble())).toFloat()
    Text("Band ${index + 1}", color = lc.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    LabelValue("Frequency", "${band.freq.roundToInt()} Hz", lc)
    FxSlider(fNorm, 0f..1f, active, { n ->
        val f = (fMin * Math.pow((fMax / fMin).toDouble(), n.toDouble())).toFloat()
        AudioFxController.setParamBand(index, f, band.q, band.gain)
    }, lc)
    LabelValue("Gain", "${if (band.gain > 0) "+" else ""}${band.gain.roundToInt()} dB", lc)
    FxSlider(band.gain, AudioFxController.PARAM_GAIN_MIN..AudioFxController.PARAM_GAIN_MAX, active,
        { AudioFxController.setParamBand(index, band.freq, band.q, it) }, lc)
    LabelValue("Q", String.format("%.2f", band.q), lc)
    FxSlider(band.q, AudioFxController.PARAM_Q_MIN..AudioFxController.PARAM_Q_MAX, active,
        { AudioFxController.setParamBand(index, band.freq, it, band.gain) }, lc)
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
                LabelValue("Frequency", "${freq.roundToInt()} Hz", lc)
                FxSlider(freq, AudioFxController.BASS_FREQ_MIN..AudioFxController.BASS_FREQ_MAX, active,
                    { AudioFxController.setBassFreq(it) }, lc)
                Spacer(Modifier.height(8.dp))
                LabelValue("Gain", "+${gain.roundToInt()} dB", lc)
                FxSlider(gain, 0f..12f, active, { AudioFxController.setBassGain(it) }, lc)
            }
        }
    }
}

@Composable
private fun LoudnessSection(lc: LiquidColors, master: Boolean) {
    val on by AudioFxController.loudnessEnabled.collectAsState()
    SectionWithToggle("Loudness Compensation", lc, on, { AudioFxController.setLoudnessEnabled(it) }, master) {
        Text("Boosts bass (and slightly the highs) at low volume — even sound at any level.",
            color = lc.textSecondary, fontSize = 12.sp)
    }
}

@Composable
private fun StereoSection(lc: LiquidColors, master: Boolean) {
    val w by AudioFxController.stereoWidth.collectAsState()
    Section("Stereo Width", lc, valueText = "${(w * 100).roundToInt()}%") {
        FxSlider(w, 0f..2f, master, { AudioFxController.setStereoWidth(it) }, lc)
        Text("0% — mono, 100% — normal, 200% — wide.", color = lc.textSecondary, fontSize = 12.sp)
    }
}

@Composable
private fun BalanceSection(lc: LiquidColors, master: Boolean) {
    val bal by AudioFxController.balance.collectAsState()
    val pct = (kotlin.math.abs(bal) * 100).roundToInt()
    val label = when {
        bal < -0.005f -> "L $pct%"
        bal > 0.005f -> "R $pct%"
        else -> "Center"
    }
    Section("Balance", lc, valueText = label) {
        FxSlider(bal, -1f..1f, master, { AudioFxController.setBalance(it) }, lc)
        Text("Shifts sound between the left and right ear.", color = lc.textSecondary, fontSize = 12.sp)
    }
}

@Composable
private fun MonoSection(lc: LiquidColors, master: Boolean) {
    val on by AudioFxController.monoEnabled.collectAsState()
    SectionWithToggle("Mono", lc, on, { AudioFxController.setMonoEnabled(it) }, master) {
        Text("Merges left and right into one channel — for mono speakers or one-ear listening.",
            color = lc.textSecondary, fontSize = 12.sp)
    }
}

@Composable
private fun CompressorSection(lc: LiquidColors, master: Boolean) {
    val on by AudioFxController.compEnabled.collectAsState()
    val preset by AudioFxController.compPreset.collectAsState()
    val threshold by AudioFxController.compThreshold.collectAsState()
    val ratio by AudioFxController.compRatio.collectAsState()
    val attack by AudioFxController.compAttack.collectAsState()
    val release by AudioFxController.compRelease.collectAsState()
    val active = master && on
    SectionWithToggle("Compressor", lc, on, { AudioFxController.setCompEnabled(it) }, master) {
        Box(Modifier.alpha(if (active) 1f else 0.4f)) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AudioFxController.COMP_PRESETS.forEachIndexed { i, p ->
                        Chip(p.name, preset == i, lc, active) { AudioFxController.setCompPreset(i) }
                    }
                }
                if (preset == AudioFxController.COMP_PRESET_CUSTOM) {
                    Spacer(Modifier.height(8.dp))
                    Text("Custom", color = lc.accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(10.dp))
                LabelValue("Threshold", "${threshold.roundToInt()} dB", lc)
                FxSlider(
                    threshold,
                    AudioFxController.COMP_THRESH_MIN_DB..AudioFxController.COMP_THRESH_MAX_DB,
                    active,
                    { AudioFxController.setCompThreshold(it) },
                    lc
                )
                LabelValue("Ratio", "${"%.1f".format(ratio)}:1", lc)
                FxSlider(
                    ratio,
                    AudioFxController.COMP_RATIO_MIN..AudioFxController.COMP_RATIO_MAX,
                    active,
                    { AudioFxController.setCompRatio(it) },
                    lc
                )
                LabelValue("Attack", "${attack.roundToInt()} ms", lc)
                FxSlider(
                    attack,
                    AudioFxController.COMP_ATTACK_MIN_MS..AudioFxController.COMP_ATTACK_MAX_MS,
                    active,
                    { AudioFxController.setCompAttack(it) },
                    lc
                )
                LabelValue("Release", "${release.roundToInt()} ms", lc)
                FxSlider(
                    release,
                    AudioFxController.COMP_RELEASE_MIN_MS..AudioFxController.COMP_RELEASE_MAX_MS,
                    active,
                    { AudioFxController.setCompRelease(it) },
                    lc
                )
            }
        }
    }
}

@Composable
private fun LimiterSection(lc: LiquidColors, master: Boolean) {
    val on by AudioFxController.limEnabled.collectAsState()
    val thr by AudioFxController.limThreshold.collectAsState()
    val release by AudioFxController.limRelease.collectAsState()
    val active = master && on
    SectionWithToggle("Limiter", lc, on, { AudioFxController.setLimEnabled(it) }, master) {
        Box(Modifier.alpha(if (active) 1f else 0.4f)) {
            Column {
                LabelValue("Threshold", "${"%.1f".format(thr)} dB", lc)
                FxSlider(
                    thr,
                    AudioFxController.LIM_THRESH_MIN_DB..AudioFxController.LIM_THRESH_MAX_DB,
                    active,
                    { AudioFxController.setLimThreshold(it) },
                    lc
                )
                Spacer(Modifier.height(8.dp))
                LabelValue("Release", "${release.roundToInt()} ms", lc)
                FxSlider(
                    release,
                    AudioFxController.LIM_RELEASE_MIN_MS..AudioFxController.LIM_RELEASE_MAX_MS,
                    active,
                    { AudioFxController.setLimRelease(it) },
                    lc
                )
                Text("Protects against clipping on loud audio. Recommended: ON.",
                    color = lc.textSecondary, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun ResetButton(lc: LiquidColors, enabled: Boolean) {
    Box(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(lc.cardSurface)
            .liquidClickable(enabled = enabled, pressedScale = LiquidMotion.PressButton) { AudioFxController.resetAll() }
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
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp))
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
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp))
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

/** Ползунок «из фулл-плеера» — LiquidSlider СО СТЕКЛОМ (преломляет фон экрана). */
@Composable
private fun FxSlider(
    value: Float, range: ClosedFloatingPointRange<Float>, enabled: Boolean,
    onChange: (Float) -> Unit, lc: LiquidColors
) {
    val fallback = rememberLayerBackdrop()
    val backdrop = LocalFxBackdrop.current ?: fallback   // сиблинг-слой (beside), не вложенный
    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).alpha(if (enabled) 1f else 0.5f)) {
        LiquidSlider(
            value = { value },
            onValueChange = { if (enabled) onChange(it) },
            valueRange = range,
            backdrop = backdrop,
            accentColor = lc.accent,
            trackColor = (if (lc.isDark) Color.White else Color.Black).copy(alpha = 0.18f)
        )
    }
}

/** Тумблер «как в настройках» — LiquidToggle БЕЗ стекла (пустой backdrop). */
@Composable
private fun FxSwitch(checked: Boolean, enabled: Boolean = true, onCheckedChange: (Boolean) -> Unit) {
    val emptyBackdrop = rememberLayerBackdrop()   // без стекла: ничего не преломляет
    LiquidToggle(
        selected = { checked },
        onSelect = { if (enabled) onCheckedChange(it) },
        backdrop = emptyBackdrop
    )
}

@Composable
private fun Chip(label: String, selected: Boolean, lc: LiquidColors, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier.clip(CircleShape).background(if (selected) lc.accent else lc.settingsBackground)
            .liquidClickable(enabled = enabled, pressedScale = LiquidMotion.PressButton, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = if (selected) Color.White else lc.textSecondary,
            fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun CircleButton(lc: LiquidColors, onClick: () -> Unit) {
    Box(
        modifier = Modifier.size(40.dp)
            .background(if (lc.isDark) Color(0xFF1C1C1E) else Color(0xFFF2F2F7), CircleShape)
            .clip(CircleShape)
            .liquidClickable(pressedScale = LiquidMotion.PressIcon, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.AutoMirrored.Rounded.ArrowBack, null, tint = lc.iconDefault, modifier = Modifier.size(22.dp))
    }
}
