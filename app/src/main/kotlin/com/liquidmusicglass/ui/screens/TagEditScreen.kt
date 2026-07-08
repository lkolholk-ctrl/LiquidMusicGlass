package com.liquidmusicglass.ui.screens

import com.liquidmusicglass.ui.icons.LiquidGlyphs
import android.app.Activity
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liquidmusicglass.data.local.LocalLibraryStore
import com.liquidmusicglass.engine.TagEditor
import com.liquidmusicglass.engine.Track
import com.liquidmusicglass.ui.theme.LiquidColors
import com.liquidmusicglass.ui.theme.LiquidTheme
import kotlinx.coroutines.launch

@Composable
fun TagEditScreen(track: Track, onBack: () -> Unit) {
    val lc = LiquidTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()

    val name = remember(track.id) { TagEditor.displayName(context, track.uri) }
    val supported = remember(name) { TagEditor.isSupported(name) }

    var loading by remember { mutableStateOf(supported) }
    var title by remember { mutableStateOf(track.title) }
    var artist by remember { mutableStateOf(track.artist) }
    var album by remember { mutableStateOf(track.albumName) }
    var albumArtist by remember { mutableStateOf("") }
    var trackNo by remember { mutableStateOf("") }
    var year by remember { mutableStateOf("") }
    var genre by remember { mutableStateOf("") }

    var status by remember { mutableStateOf("") }      // сообщение под кнопкой
    var saving by remember { mutableStateOf(false) }

    fun currentTags() = TagEditor.Tags(title.trim(), artist.trim(), album.trim(),
        albumArtist.trim(), trackNo.trim(), year.trim(), genre.trim())

    // Читаем теги из файла (фон), заполняем поля.
    LaunchedEffect(track.id, supported) {
        if (!supported) { loading = false; return@LaunchedEffect }
        val t = TagEditor.read(context, track.uri, name)
        if (t != null) {
            if (t.title.isNotBlank()) title = t.title
            if (t.artist.isNotBlank()) artist = t.artist
            if (t.album.isNotBlank()) album = t.album
            albumArtist = t.albumArtist
            trackNo = t.trackNumber
            year = t.year
            genre = t.genre
        }
        loading = false
    }

    suspend fun performWrite() {
        saving = true; status = "Сохранение…"
        when (val r = TagEditor.write(context, track.uri, name, currentTags())) {
            is TagEditor.WriteResult.Ok -> {
                runCatching {
                    LocalLibraryStore.updateTags(
                        context, track.id, title.trim(), artist.trim(), album.trim(),
                        trackNo.trim().toIntOrNull() ?: 0, year.trim().toIntOrNull() ?: 0
                    )
                }
                saving = false; status = "Сохранено ✓"
            }
            is TagEditor.WriteResult.Unsupported -> { saving = false; status = "Формат не поддерживается" }
            is TagEditor.WriteResult.Error -> { saving = false; status = r.message }
            is TagEditor.WriteResult.NeedsPermission -> { /* API 29 — обработается лаунчером ниже */ }
        }
    }

    // Лаунчер запроса разрешения на запись файла (scoped storage).
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            scope.launch { performWrite() }
        } else {
            saving = false; status = "Доступ к файлу не выдан"
        }
    }

    fun onSaveClick() {
        if (saving) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val isender = TagEditor.createWriteRequest(context, listOf(track.uri))
            if (isender != null) {
                saving = true; status = "Запрос разрешения…"
                permLauncher.launch(IntentSenderRequest.Builder(isender).build())
                return
            }
        }
        // API 29: пробуем записать; если нужно разрешение — запустим intentSender из результата.
        scope.launch {
            saving = true; status = "Сохранение…"
            val r = TagEditor.write(context, track.uri, name, currentTags())
            if (r is TagEditor.WriteResult.NeedsPermission) {
                permLauncher.launch(IntentSenderRequest.Builder(r.intentSender).build())
            } else {
                // переиспользуем общую обработку результата
                when (r) {
                    is TagEditor.WriteResult.Ok -> {
                        runCatching {
                            LocalLibraryStore.updateTags(
                                context, track.id, title.trim(), artist.trim(), album.trim(),
                                trackNo.trim().toIntOrNull() ?: 0, year.trim().toIntOrNull() ?: 0
                            )
                        }
                        saving = false; status = "Сохранено ✓"
                    }
                    is TagEditor.WriteResult.Unsupported -> { saving = false; status = "Формат не поддерживается" }
                    is TagEditor.WriteResult.Error -> { saving = false; status = r.message }
                    else -> {}
                }
            }
        }
    }

    Box(Modifier.fillMaxSize().background(lc.settingsBackground)) {
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 20.dp)) {
            Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                CircleBack(lc, onBack)
                Spacer(Modifier.width(14.dp))
                Text("Теги", color = lc.textPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(16.dp))

            if (!supported) {
                Text(
                    "Формат этого файла пока не поддерживается для редактирования тегов.",
                    color = lc.textSecondary, fontSize = 14.sp
                )
            } else {
                Box(Modifier.alpha(if (loading || saving) 0.5f else 1f)) {
                    Column {
                        EditField("Название", title, lc) { title = it }
                        EditField("Артист", artist, lc) { artist = it }
                        EditField("Альбом", album, lc) { album = it }
                        EditField("Артист альбома", albumArtist, lc) { albumArtist = it }
                        EditField("Номер трека", trackNo, lc, number = true) { trackNo = it.filter { c -> c.isDigit() } }
                        EditField("Год", year, lc, number = true) { year = it.filter { c -> c.isDigit() } }
                        EditField("Жанр", genre, lc) { genre = it }
                    }
                }
                Spacer(Modifier.height(20.dp))
                Box(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                        .background(if (saving) lc.cardSurface else lc.accent)
                        .clickable(enabled = !saving && !loading) { onSaveClick() }
                        .padding(vertical = 14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(if (saving) "…" else "Сохранить",
                        color = if (saving) lc.textSecondary else Color.White,
                        fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
                if (status.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text(status, color = lc.textSecondary, fontSize = 13.sp,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
                Text(
                    "Изменения пишутся в сам файл. Медиатека и поиск обновятся сразу.",
                    color = lc.textTertiary, fontSize = 12.sp,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}

// ══════════════════════════════════════════════════════════════════════════════
//  Массовое редактирование: общие поля для нескольких выбранных треков
// ══════════════════════════════════════════════════════════════════════════════
@Composable
fun BulkTagEditScreen(tracks: List<Track>, onBack: () -> Unit) {
    val lc = LiquidTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()
    val total = tracks.size

    var artist by remember { mutableStateOf("") }
    var album by remember { mutableStateOf("") }
    var albumArtist by remember { mutableStateOf("") }
    var year by remember { mutableStateOf("") }
    var genre by remember { mutableStateOf("") }

    var saving by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var okTotal by remember { mutableStateOf(0) }
    var failTotal by remember { mutableStateOf(0) }

    // очередь дозаписи (для API 29 — по файлу за раз) и перенос разрешения через состояние
    var remaining by remember { mutableStateOf<List<TagEditor.BulkItem>>(emptyList()) }
    var pendingFields by remember { mutableStateOf<List<Pair<org.jaudiotagger.tag.FieldKey, String>>>(emptyList()) }
    var pendingPermission by remember { mutableStateOf<android.content.IntentSender?>(null) }

    fun fields(): List<Pair<org.jaudiotagger.tag.FieldKey, String>> = buildList {
        if (artist.isNotBlank()) add(org.jaudiotagger.tag.FieldKey.ARTIST to artist.trim())
        if (album.isNotBlank()) add(org.jaudiotagger.tag.FieldKey.ALBUM to album.trim())
        if (albumArtist.isNotBlank()) add(org.jaudiotagger.tag.FieldKey.ALBUM_ARTIST to albumArtist.trim())
        if (year.isNotBlank()) add(org.jaudiotagger.tag.FieldKey.YEAR to year.trim())
        if (genre.isNotBlank()) add(org.jaudiotagger.tag.FieldKey.GENRE to genre.trim())
    }

    suspend fun drain(items: List<TagEditor.BulkItem>, f: List<Pair<org.jaudiotagger.tag.FieldKey, String>>) {
        val out = TagEditor.writePartialMany(context, items, f)
        okTotal += out.okCount; failTotal += out.failCount
        out.doneTrackIds.forEach { id ->
            runCatching {
                LocalLibraryStore.updateTagsBulk(
                    context, id, artist.trim(), album.trim(), year.trim().toIntOrNull() ?: 0
                )
            }
        }
        if (out.needPermission != null) {
            remaining = out.remaining; pendingFields = f
            status = "Запрос разрешения…"
            pendingPermission = out.needPermission           // запустит лаунчер через LaunchedEffect
        } else {
            saving = false
            status = "Готово: $okTotal из $total" + if (failTotal > 0) " · пропущено $failTotal" else ""
        }
    }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        pendingPermission = null
        if (result.resultCode == Activity.RESULT_OK) {
            scope.launch { drain(remaining, pendingFields) }
        } else {
            saving = false; status = "Доступ к файлам не выдан"
        }
    }

    // Запрос разрешения вынесен в состояние, чтобы не было цикла объявлений drain↔launcher.
    LaunchedEffect(pendingPermission) {
        pendingPermission?.let { permLauncher.launch(IntentSenderRequest.Builder(it).build()) }
    }

    fun onApply() {
        if (saving) return
        val f = fields()
        if (f.isEmpty()) { status = "Заполни хотя бы одно поле"; return }
        saving = true; okTotal = 0; failTotal = 0; status = "Подготовка…"
        pendingFields = f
        scope.launch {
            val items = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                tracks.map { TagEditor.BulkItem(it.id, it.uri, TagEditor.displayName(context, it.uri)) }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                remaining = items
                val isender = TagEditor.createWriteRequest(context, items.map { it.uri })
                if (isender != null) { status = "Запрос разрешения…"; pendingPermission = isender; return@launch }
            }
            drain(items, f)   // API 29 — попросит разрешение по файлам по ходу
        }
    }

    Box(Modifier.fillMaxSize().background(lc.settingsBackground)) {
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 20.dp)) {
            Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                CircleBack(lc, onBack)
                Spacer(Modifier.width(14.dp))
                Column {
                    Text("Теги пачкой", color = lc.textPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Text("Выбрано треков: $total", color = lc.textSecondary, fontSize = 13.sp)
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Заполненные поля применятся ко ВСЕМ выбранным трекам. Пустые поля не трогаем. " +
                    "Название и номер трека здесь не меняются.",
                color = lc.textTertiary, fontSize = 12.sp, modifier = Modifier.padding(vertical = 4.dp)
            )
            Spacer(Modifier.height(8.dp))

            Box(Modifier.alpha(if (saving) 0.5f else 1f)) {
                Column {
                    EditField("Артист", artist, lc) { artist = it }
                    EditField("Альбом", album, lc) { album = it }
                    EditField("Артист альбома", albumArtist, lc) { albumArtist = it }
                    EditField("Год", year, lc, number = true) { year = it.filter { c -> c.isDigit() } }
                    EditField("Жанр", genre, lc) { genre = it }
                }
            }
            Spacer(Modifier.height(20.dp))
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                    .background(if (saving) lc.cardSurface else lc.accent)
                    .clickable(enabled = !saving) { onApply() }
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(if (saving) "…" else "Применить к $total",
                    color = if (saving) lc.textSecondary else Color.White,
                    fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
            if (status.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(status, color = lc.textSecondary, fontSize = 13.sp,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun EditField(label: String, value: String, lc: LiquidColors, number: Boolean = false, onChange: (String) -> Unit) {
    Column(Modifier.padding(vertical = 6.dp)) {
        Text(label, color = lc.textSecondary, fontSize = 12.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(lc.cardSurface)
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            BasicTextField(
                value = value, onValueChange = onChange, singleLine = true,
                textStyle = TextStyle(color = lc.textPrimary, fontSize = 16.sp),
                cursorBrush = SolidColor(lc.accent), modifier = Modifier.fillMaxWidth(),
                keyboardOptions = if (number)
                    androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
                else androidx.compose.foundation.text.KeyboardOptions.Default,
                decorationBox = { inner ->
                    if (value.isEmpty()) Text("—", color = lc.textTertiary, fontSize = 16.sp)
                    inner()
                }
            )
        }
    }
}

@Composable
private fun CircleBack(lc: LiquidColors, onClick: () -> Unit) {
    Box(
        Modifier.size(40.dp).background(if (lc.isDark) Color(0xFF1C1C1E) else Color(0xFFF2F2F7), CircleShape)
            .clip(CircleShape).clickable(remember { MutableInteractionSource() }, null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(LiquidGlyphs.Back, null, tint = lc.iconDefault, modifier = Modifier.size(22.dp))
    }
}
