package com.liquidmusicglass.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liquidmusicglass.ui.theme.LiquidTheme

/**
 * Import Playlist dialog.
 * Mirrors the Premium Required dialog design language:
 * - Centered modal dialog (not bottom sheet)
 * - Deeply rounded corners (28.dp)
 * - Dark surface with subtle border
 * - Respects system bars and keyboard via navigationBarsPadding + imePadding
 */
@Composable
fun ImportUrlBottomSheet(
    onDismiss: () -> Unit,
    onImport: (String) -> Unit
) {
    val lc = LiquidTheme.colors
    val isDark = lc.isDark
    val dialogBg = if (isDark) Color(0xFF1C1C1E) else Color.White
    val dialogBorder = if (isDark) Color.White.copy(alpha = 0.12f) else Color.Black.copy(alpha = 0.10f)

    var url by remember { mutableStateOf("") }
    var urlError by remember { mutableStateOf<String?>(null) }
    val focusManager = LocalFocusManager.current

    fun validateUrl(input: String): Boolean {
        val lower = input.lowercase().trim()
        return when {
            lower.contains("music.yandex") -> true
            lower.contains("apple.com") || lower.contains("music.apple") -> true
            lower.contains("spotify.com") || lower.startsWith("spotify:") -> true
            input.isBlank() -> { urlError = null; false }
            else -> { urlError = "Only Yandex Music, Apple Music and Spotify URLs are supported"; false }
        }
    }

    // ── Scrim + dismiss on outside tap ──
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
        // ── Dialog container ──
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(dialogBg)
                .border(1.dp, dialogBorder, RoundedCornerShape(28.dp))
                .padding(28.dp)
                .clickable(remember { MutableInteractionSource() }, null) { } // prevent dismiss inside
        ) {
            Column {
                // ═══ Header: Title + Close ═══
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Import Playlist",
                        color = lc.textPrimary,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(
                        onClick = {
                            focusManager.clearFocus()
                            onDismiss()
                        }
                    ) {
                        Icon(
                            Icons.Rounded.Close,
                            contentDescription = "Close",
                            tint = Color.Gray,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // ═══ URL Input Field ═══
                // Custom styled text field matching the dialog aesthetic
                Column {
                    Text(
                        text = "Playlist URL",
                        color = Color.Gray,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)
                    )
                    val inputBg = if (isDark) Color.White.copy(alpha = 0.06f) else Color.Black.copy(alpha = 0.04f)
                    val inputBorder = if (isDark) {
                        if (urlError != null) Color(0xFFFF453A) else if (url.isNotEmpty()) Color.White.copy(alpha = 0.20f) else Color.White.copy(alpha = 0.10f)
                    } else {
                        if (urlError != null) Color(0xFFFF453A) else if (url.isNotEmpty()) Color.Black.copy(alpha = 0.15f) else Color.Black.copy(alpha = 0.08f)
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(inputBg)
                            .border(
                                width = 1.dp,
                                color = inputBorder,
                                shape = RoundedCornerShape(12.dp)
                            )
                            .padding(horizontal = 16.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        BasicTextField(
                            value = url,
                            onValueChange = {
                                url = it
                                urlError = null
                            },
                            textStyle = TextStyle(
                                color = lc.textPrimary,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Normal
                            ),
                            cursorBrush = SolidColor(Color(0xFFFF453A)),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            decorationBox = { innerTextField ->
                                if (url.isEmpty()) {
                                    Text(
                                        text = "https://music.yandex.ru/playlists/...",
                                        color = Color.Gray.copy(alpha = 0.6f),
                                        fontSize = 15.sp
                                    )
                                }
                                innerTextField()
                            }
                        )
                    }

                    // Error / helper text
                    if (urlError != null) {
                        Text(
                            text = urlError!!,
                            color = Color(0xFFFF453A),
                            fontSize = 12.sp,
                            modifier = Modifier.padding(start = 4.dp, top = 6.dp)
                        )
                    } else {
                        Text(
                            text = "Supports: Yandex Music and Apple Music",
                            color = Color.Gray.copy(alpha = 0.7f),
                            fontSize = 12.sp,
                            modifier = Modifier.padding(start = 4.dp, top = 6.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // ═══ Action Buttons ═══
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Cancel button
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp)
                            .clip(RoundedCornerShape(23.dp))
                            .background(if (isDark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.05f))
                            .border(
                                1.dp,
                                if (isDark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.10f),
                                RoundedCornerShape(23.dp)
                            )
                            .clickable(remember { MutableInteractionSource() }, null) {
                                focusManager.clearFocus()
                                onDismiss()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "Cancel",
                            color = lc.textPrimary,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp
                        )
                    }

                    // Import button
                    val isValid = url.isNotBlank() && urlError == null
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp)
                            .clip(RoundedCornerShape(23.dp))
                            .background(
                                if (isValid) Color(0xFFFF453A) else Color(0xFFFF453A).copy(alpha = 0.4f)
                            )
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                enabled = isValid
                            ) {
                                focusManager.clearFocus()
                                if (validateUrl(url)) {
                                    onImport(url.trim())
                                }
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
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                "Import",
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
}
