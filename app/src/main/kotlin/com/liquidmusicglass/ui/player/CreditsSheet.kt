package com.liquidmusicglass.ui.player

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liquidmusicglass.api.lmg.LmgSyncApi
import com.liquidmusicglass.engine.Track

/**
 * Кто сделал трек: авторы, продюсеры, звукорежиссёры, лейбл и год.
 *
 * Данные считает сервер по внешней открытой базе — у каталога таких полей нет.
 * Совпадение ищется по названию, исполнителю и длительности, поэтому редкие и
 * локальные релизы часто не находятся. В этом случае честно показываем, что
 * данных нет: подставить «похожий» трек значило бы приписать музыке чужих людей.
 */
@Composable
fun CreditsContent(track: Track, durationMs: Long) {
    var credits by remember(track.id) { mutableStateOf<LmgSyncApi.TrackCredits?>(null) }
    var loading by remember(track.id) { mutableStateOf(true) }

    LaunchedEffect(track.id) {
        loading = true
        credits = LmgSyncApi.fetchCredits(
            trackId = track.id,
            title = track.title,
            artist = track.artist,
            durationMs = durationMs
        )
        loading = false
    }

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
        Text(
            text = track.title,
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = track.artist,
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 14.sp,
            modifier = Modifier.padding(top = 2.dp)
        )

        Spacer(modifier = Modifier.height(16.dp))

        val data = credits
        when {
            loading -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        color = Color.White.copy(alpha = 0.7f),
                        strokeWidth = 2.dp,
                        modifier = Modifier.width(18.dp).height(18.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "Looking up credits…",
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 14.sp
                    )
                }
            }

            data == null || !data.found -> {
                Text(
                    text = "No credits found for this recording.",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 14.sp
                )
                Text(
                    text = "Credits come from an open music database and are matched by " +
                        "title, artist and length. Rare or regional releases are often missing.",
                    color = Color.White.copy(alpha = 0.4f),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }

            else -> {
                data.people.forEach { person ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = person.name,
                            color = Color.White,
                            fontSize = 15.sp,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = person.role,
                            color = Color.White.copy(alpha = 0.5f),
                            fontSize = 13.sp
                        )
                    }
                }

                if (data.label.isNotBlank() || data.year.isNotBlank()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = listOf(data.label, data.year)
                            .filter { it.isNotBlank() }
                            .joinToString(" · "),
                        color = Color.White.copy(alpha = 0.5f),
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}
