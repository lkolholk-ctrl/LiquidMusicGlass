package com.liquidmusicglass.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liquidmusicglass.R
import com.liquidmusicglass.data.playlistimport.ImportRateGate
import com.liquidmusicglass.data.playlistimport.ImportState
import com.liquidmusicglass.data.playlistimport.LoadingPhase
import com.liquidmusicglass.data.playlistimport.PlaylistImportManager
import com.liquidmusicglass.ui.glass.liquidClickable
import com.liquidmusicglass.ui.theme.LiquidMotion
import com.liquidmusicglass.ui.theme.LiquidTheme

/**
 * Redesigned import screen (#74): one card per supported source with its REAL
 * brand icon, a per-service daily-remaining counter, and in-app progress
 * (X of Y matched) shown right on the card — not only in the system shade.
 *
 * Sources (по решению юзера): Apple Music, Yandex Music, Spotify. Tidal/VK
 * убраны как неактуальные. Apple идёт через официальный ICM (без лимита);
 * Yandex/Spotify — scrape, поэтому троттлятся ([ImportRateGate]) и показывают
 * «осталось X из N».
 *
 * Физику импорта не трогаем — карточка лишь зовёт [PlaylistImportManager.importPlaylist]
 * и наблюдает его StateFlow.
 */

private data class ImportServiceUi(
    val key: String,              // "apple" | "yandex" | "spotify" (ключ троттла/атрибуции)
    val name: String,
    val iconRes: Int,
    val accent: Color,
    val throttled: Boolean,       // false = без дневного лимита (Apple)
    val placeholder: String,
    val hint: String?             // подсказка «как взять ссылку», null если не нужна
)

private val IMPORT_SERVICES = listOf(
    ImportServiceUi(
        key = "apple",
        name = "Apple Music",
        iconRes = R.drawable.ic_service_apple_music,
        accent = Color(0xFFFA233B),
        throttled = false,
        placeholder = "https://music.apple.com/…/playlist/…",
        hint = null
    ),
    ImportServiceUi(
        key = "yandex",
        name = "Yandex Music",
        iconRes = R.drawable.ic_service_yandex,
        accent = Color(0xFFFC3F1D),
        throttled = true,
        placeholder = "https://music.yandex.ru/playlists/…",
        // #73: подсказка «как получить ссылку» — только для Яндекса.
        hint = "In Yandex Music open the playlist → Share → Copy link, then paste it here. Yandex playlists are always public."
    ),
    ImportServiceUi(
        key = "spotify",
        name = "Spotify",
        iconRes = R.drawable.ic_service_spotify,
        accent = Color(0xFF1DB954),
        throttled = true,
        placeholder = "https://open.spotify.com/playlist/…",
        hint = null
    )
)

private fun urlMatchesService(url: String, key: String): Boolean {
    val u = url.lowercase().trim()
    return when (key) {
        "apple" -> u.contains("apple.com") || u.contains("music.apple")
        "yandex" -> u.contains("music.yandex")
        "spotify" -> u.contains("spotify.com") || u.startsWith("spotify:")
        else -> false
    }
}

@Composable
fun ImportServicesSheet(
    onDismiss: () -> Unit,
    onImport: (String) -> Unit
) {
    val lc = LiquidTheme.colors
    val isDark = lc.isDark
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current

    val dialogBg = if (isDark) Color(0xFF1C1C1E) else Color.White
    val dialogBorder = if (isDark) Color.White.copy(alpha = 0.12f) else Color.Black.copy(alpha = 0.10f)

    val importState by PlaylistImportManager.importState.collectAsState()

    // Какая карточка сейчас «активна» (пользователь запустил из неё импорт) —
    // на ней рисуем прогресс/итог. Локально, чтобы success не сбрасывал показ.
    var activeService by remember { mutableStateOf<String?>(null) }
    // Какая карточка развёрнута в поле ввода URL.
    var expanded by remember { mutableStateOf<String?>(null) }

    // Пока идёт импорт — держим активную карточку развёрнутой.
    val importing = importState is ImportState.Loading

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f))
            .clickable(remember { MutableInteractionSource() }, null) {
                focusManager.clearFocus()
                onDismiss()
            }
            .navigationBarsPadding()
            .imePadding(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(dialogBg)
                .border(1.dp, dialogBorder, RoundedCornerShape(28.dp))
                .padding(20.dp)
                .clickable(remember { MutableInteractionSource() }, null) { } // не закрывать по тапу внутри
                .animateContentSize()
        ) {
            // ═══ Header ═══
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Import a Playlist",
                        color = lc.textPrimary,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Pick a source to transfer from",
                        color = lc.textSecondary,
                        fontSize = 13.sp
                    )
                }
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (isDark) Color.White.copy(alpha = 0.06f) else Color.Black.copy(alpha = 0.05f))
                        .liquidClickable(pressedScale = LiquidMotion.PressIcon) {
                            focusManager.clearFocus()
                            onDismiss()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = "Close",
                        tint = lc.textSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            IMPORT_SERVICES.forEachIndexed { index, svc ->
                ImportServiceCard(
                    svc = svc,
                    isDark = isDark,
                    isExpanded = expanded == svc.key,
                    isActive = activeService == svc.key,
                    importState = importState,
                    // блокируем запуск другого импорта, пока идёт текущий
                    globallyBusy = importing,
                    remainingToday = remember(importState, svc.key) {
                        if (svc.throttled) ImportRateGate.remainingToday(context, svc.key) else -1
                    },
                    dailyCap = ImportRateGate.dailyCap(),
                    onToggle = {
                        expanded = if (expanded == svc.key) null else svc.key
                    },
                    onImport = { url ->
                        focusManager.clearFocus()
                        activeService = svc.key
                        onImport(url)
                    }
                )
                if (index != IMPORT_SERVICES.lastIndex) Spacer(Modifier.height(10.dp))
            }
        }
    }
}

@Composable
private fun ImportServiceCard(
    svc: ImportServiceUi,
    isDark: Boolean,
    isExpanded: Boolean,
    isActive: Boolean,
    importState: ImportState,
    globallyBusy: Boolean,
    remainingToday: Int,
    dailyCap: Int,
    onToggle: () -> Unit,
    onImport: (String) -> Unit
) {
    val lc = LiquidTheme.colors
    val clipboard = LocalClipboardManager.current

    val cardBg = if (isDark) Color.White.copy(alpha = 0.05f) else Color.Black.copy(alpha = 0.035f)
    val cardBorder =
        if (isExpanded) svc.accent.copy(alpha = 0.5f)
        else if (isDark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.06f)

    var url by remember(svc.key) { mutableStateOf("") }
    var localError by remember(svc.key) { mutableStateOf<String?>(null) }

    val outOfQuota = svc.throttled && remainingToday <= 0

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(cardBg)
            .border(1.dp, cardBorder, RoundedCornerShape(20.dp))
            .animateContentSize()
    ) {
        // ── Card header row (tap to expand) ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .liquidClickable { onToggle() }
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(svc.iconRes),
                contentDescription = svc.name,
                tint = Color.Unspecified,
                modifier = Modifier.size(38.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = svc.name,
                    color = lc.textPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = when {
                        !svc.throttled -> "No daily limit"
                        outOfQuota -> "Daily limit reached · resets in 24h"
                        else -> "$remainingToday of $dailyCap imports left today"
                    },
                    color = if (outOfQuota) Color(0xFFFF9F0A) else lc.textSecondary,
                    fontSize = 12.sp
                )
            }
        }

        // ── Expanded body: URL input + import ──
        AnimatedVisibility(visible = isExpanded) {
            Column(modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp)) {
                if (svc.hint != null) {
                    Text(
                        text = svc.hint,
                        color = lc.textTertiary,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(bottom = 10.dp)
                    )
                }

                // URL field + paste
                val inputBg = if (isDark) Color.White.copy(alpha = 0.06f) else Color.Black.copy(alpha = 0.04f)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(inputBg)
                            .padding(horizontal = 14.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        BasicTextField(
                            value = url,
                            onValueChange = { url = it; localError = null },
                            textStyle = TextStyle(color = lc.textPrimary, fontSize = 14.sp),
                            cursorBrush = SolidColor(svc.accent),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            decorationBox = { inner ->
                                if (url.isEmpty()) {
                                    Text(
                                        text = svc.placeholder,
                                        color = lc.textTertiary,
                                        fontSize = 14.sp,
                                        maxLines = 1
                                    )
                                }
                                inner()
                            }
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(inputBg)
                            .liquidClickable(pressedScale = LiquidMotion.PressButton) {
                                clipboard.getText()?.text?.let { url = it.trim(); localError = null }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Rounded.ContentPaste,
                            contentDescription = "Paste",
                            tint = lc.textSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                if (localError != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(localError!!, color = Color(0xFFFF453A), fontSize = 12.sp)
                }

                Spacer(Modifier.height(12.dp))

                // ── Progress / result (attributed to the active card) ──
                if (isActive) {
                    ImportProgressBlock(state = importState, accent = svc.accent, lc = lc)
                }

                // Import button
                val canImport = url.isNotBlank() && !globallyBusy && !outOfQuota
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp)
                        .clip(RoundedCornerShape(23.dp))
                        .background(if (canImport) svc.accent else svc.accent.copy(alpha = 0.4f))
                        .liquidClickable(enabled = canImport, pressedScale = LiquidMotion.PressButton) {
                            if (!urlMatchesService(url, svc.key)) {
                                localError = "This doesn't look like a ${svc.name} link"
                                return@liquidClickable
                            }
                            onImport(url.trim())
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Rounded.Download,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (globallyBusy && isActive) "Importing…" else "Import",
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp
                        )
                    }
                }
            }
        }
    }
}

/** Живой прогресс/итог одного импорта: полоса + «X of Y matched». */
@Composable
private fun ImportProgressBlock(
    state: ImportState,
    accent: Color,
    lc: com.liquidmusicglass.ui.theme.LiquidColors
) {
    when (state) {
        is ImportState.Loading -> {
            val label = when (state.phase) {
                LoadingPhase.RESOLVING -> "Fetching playlist…"
                LoadingPhase.MATCHING ->
                    "Matched ${state.processedTracks} of ${state.totalTracks}" +
                        (if (state.totalTracks > state.processedTracks)
                            " · ${state.totalTracks - state.processedTracks} left" else "")
                LoadingPhase.SAVING -> "Saving to your library…"
            }
            Column(modifier = Modifier.padding(bottom = 12.dp)) {
                Text(label, color = lc.textSecondary, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                if (state.phase == LoadingPhase.MATCHING && state.totalTracks > 0) {
                    LinearProgressIndicator(
                        progress = { state.processedTracks.toFloat() / state.totalTracks.toFloat() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp)),
                        color = accent,
                        trackColor = accent.copy(alpha = 0.18f)
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp)),
                        color = accent,
                        trackColor = accent.copy(alpha = 0.18f)
                    )
                }
            }
        }
        is ImportState.Success -> {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 12.dp)
            ) {
                Icon(
                    Icons.Rounded.CheckCircle,
                    contentDescription = null,
                    tint = Color(0xFF34C759),
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Added ${state.insertedCount} tracks to “${state.playlistName}”",
                    color = lc.textPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
        is ImportState.Error -> {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 12.dp)
            ) {
                Icon(
                    Icons.Rounded.ErrorOutline,
                    contentDescription = null,
                    tint = Color(0xFFFF453A),
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    state.message,
                    color = Color(0xFFFF453A),
                    fontSize = 12.sp
                )
            }
        }
        ImportState.Idle -> { /* nothing */ }
    }
}
