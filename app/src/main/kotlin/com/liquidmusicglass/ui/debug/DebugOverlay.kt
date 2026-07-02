package com.liquidmusicglass.ui.debug

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liquidmusicglass.debug.DebugLog
import com.liquidmusicglass.engine.automix.AutoMixNativeEngine

/**
 * ВРЕМЕННЫЙ оверлей on-screen логгера. Полупрозрачная панель сверху со скроллом
 * (новые записи внизу, авто-скролл). Пустые области НЕ перехватывают тач —
 * взаимодействие с приложением под панелью сохраняется (или просто «Hide»).
 */
@Composable
fun DebugOverlay() {
    val visible by DebugLog.visible

    Box(modifier = Modifier.fillMaxWidth()) {
        if (visible) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .heightIn(max = 260.dp)
                    .background(Color(0xE6000000))
                    .padding(horizontal = 6.dp, vertical = 4.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("JUCE DEBUG", color = Color(0xFFFFD54F), fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.width(12.dp))
                    Text("CLEAR", color = Color(0xFF4FC3F7), fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                        modifier = Modifier.clickable { DebugLog.clear() }.padding(4.dp))
                    Spacer(Modifier.width(8.dp))
                    // Дамп фактических параметров Oboe-потоков (API/sharing/perf/
                    // format/rate/burst) — чтобы с проблемного устройства сразу
                    // видеть, каким путём пошёл звук.
                    Text("OBOE", color = Color(0xFF4FC3F7), fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                        modifier = Modifier.clickable {
                            DebugLog.add("OBOE ${AutoMixNativeEngine.audioDiagnostics()}")
                        }.padding(4.dp))
                    Spacer(Modifier.width(8.dp))
                    // Перебор режимов Oboe на лету (0 normal → 1 safe → 2 exclusive →
                    // 3 normal-i16 → 4 safe-i16): A/B прямо на играющей музыке, поток
                    // переоткрывается сам. I16-режимы — для HAL с битым float-микшером.
                    Text("MODE", color = Color(0xFFFF8A65), fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                        modifier = Modifier.clickable {
                            val next = (com.liquidmusicglass.engine.AppSettings.audioCompatMode.value + 1) % 5
                            com.liquidmusicglass.engine.AppSettings.setAudioCompatMode(next)
                            DebugLog.add("OBOE mode -> ${when (next) {
                                1 -> "SAFE(shared+none)"
                                2 -> "EXCLUSIVE(stock)"
                                3 -> "NORMAL_I16(shared+lowlat+i16)"
                                4 -> "SAFE_I16(shared+none+i16)"
                                else -> "NORMAL(shared+lowlat)"
                            }}")
                        }.padding(4.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("HIDE", color = Color(0xFF4FC3F7), fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                        modifier = Modifier.clickable { DebugLog.visible.value = false }.padding(4.dp))
                }

                val scroll = rememberScrollState()
                LaunchedEffect(DebugLog.lines.size) { scroll.scrollTo(scroll.maxValue) }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(scroll)
                ) {
                    DebugLog.lines.forEach { l ->
                        Text(
                            l,
                            color = Color(0xFF9CFF9C),
                            fontSize = 9.sp,
                            lineHeight = 11.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        } else {
            // Свёрнуто — маленький чип, чтобы вернуть панель.
            Box(modifier = Modifier.statusBarsPadding().padding(6.dp), contentAlignment = Alignment.TopEnd) {
                Text(
                    "LOG",
                    color = Color(0xFFFFD54F),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .background(Color(0xCC000000))
                        .clickable { DebugLog.visible.value = true }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
    }
}
