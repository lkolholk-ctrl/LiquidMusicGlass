package com.liquidmusicglass.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.liquidmusicglass.R
import com.liquidmusicglass.data.local.db.FavoriteTrackDatabase
import com.liquidmusicglass.data.yandex.YandexAuthRepository
import com.liquidmusicglass.data.yandex.YandexDownloadManager
import com.liquidmusicglass.data.yandex.YandexMusicClient
import com.liquidmusicglass.data.yandex.YandexMusicException
import com.liquidmusicglass.data.yandex.YandexUnauthorizedException
import com.liquidmusicglass.ui.glass.liquidClickable
import com.liquidmusicglass.ui.theme.LiquidMotion
import com.liquidmusicglass.ui.theme.LiquidTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val YandexYellow = Color(0xFFFFCC00)

/**
 * Полноэкранный экран Яндекс.Музыки (Библиотека → Yandex Music). Обычный
 * navigation destination — открывается с тем же slide-переходом, что и
 * остальные детали, а не sheet-оверлеем.
 *
 * Не connected → встроенный вход (WebView, автоперехват токена) или ручной
 * ввод OAuth-токена. Connected → поиск + скачивание в Downloads.
 */
@Composable
fun YandexMusicScreen(onBack: () -> Unit) {
    val lc = LiquidTheme.colors
    val focusManager = LocalFocusManager.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val connected by YandexAuthRepository.isConnected.collectAsState()
    val label by YandexAuthRepository.displayLabel.collectAsState()
    val dlProgress by YandexDownloadManager.progress.collectAsState()

    val inputBg = if (lc.isDark) Color.White.copy(alpha = 0.06f) else Color.Black.copy(alpha = 0.04f)

    // search state
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }
    var results by remember { mutableStateOf<List<YandexMusicClient.Track>>(emptyList()) }
    // trackIds already in offline DB (ym_…)
    var offlineIds by remember { mutableStateOf<Set<String>>(emptySet()) }

    fun refreshOffline() {
        scope.launch(Dispatchers.IO) {
            val all = FavoriteTrackDatabase.getInstance(context).getDownloadedTracks()
            offlineIds = all.map { it.trackId }.filter { it.startsWith(YandexDownloadManager.ID_PREFIX) }.toSet()
        }
    }

    LaunchedEffect(connected) {
        if (connected) refreshOffline()
    }

    fun runSearch() {
        val q = query.trim()
        if (q.isEmpty() || searching) return
        focusManager.clearFocus()
        searching = true
        searchError = null
        scope.launch {
            try {
                val list = withContext(Dispatchers.IO) {
                    val client = YandexAuthRepository.clientOrNull()
                        ?: throw YandexMusicException("Not connected")
                    client.searchTracks(q)
                }
                results = list
                if (list.isEmpty()) searchError = "Nothing found"
            } catch (_: YandexUnauthorizedException) {
                searchError = "Token expired — reconnect"
                YandexAuthRepository.disconnect()
                results = emptyList()
            } catch (_: Exception) {
                // Без e.message: сеть/API могут отдавать шум; токен туда не должен попадать
                searchError = "Search failed"
                results = emptyList()
            } finally {
                searching = false
            }
        }
    }

    Box(Modifier.fillMaxSize().background(lc.settingsBackground)) {
        Column(Modifier.fillMaxSize().imePadding()) {
            Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
            Spacer(Modifier.height(12.dp))

            // Header: back + icon + title/status
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(inputBg)
                        .liquidClickable(pressedScale = LiquidMotion.PressIcon) {
                            focusManager.clearFocus()
                            onBack()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "Back",
                        tint = lc.textPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Icon(
                    painter = painterResource(R.drawable.ic_service_yandex),
                    contentDescription = null,
                    tint = Color.Unspecified,
                    modifier = Modifier.size(34.dp)
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Yandex Music",
                        color = lc.textPrimary,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        if (connected) "Connected · ${label ?: "account"}"
                        else "Sign in with your Yandex account",
                        color = if (connected) Color(0xFF34C759) else lc.textSecondary,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            if (!connected) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 120.dp)
                ) {
                    ConnectBody(
                        inputBg = inputBg,
                        lc = lc,
                        onConnected = { /* state via repo flow */ }
                    )
                }
            } else {
                // Search bar
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 16.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(inputBg)
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Rounded.Search,
                                null,
                                tint = lc.textTertiary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            BasicTextField(
                                value = query,
                                onValueChange = { query = it; searchError = null },
                                textStyle = TextStyle(color = lc.textPrimary, fontSize = 15.sp),
                                cursorBrush = SolidColor(YandexYellow),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                keyboardActions = KeyboardActions(onSearch = { runSearch() }),
                                modifier = Modifier.weight(1f),
                                decorationBox = { inner ->
                                    if (query.isEmpty()) {
                                        Text(
                                            "Search tracks…",
                                            color = lc.textTertiary,
                                            fontSize = 15.sp
                                        )
                                    }
                                    inner()
                                }
                            )
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .height(46.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(
                                if (query.isNotBlank() && !searching) YandexYellow
                                else YandexYellow.copy(alpha = 0.4f)
                            )
                            .liquidClickable(
                                enabled = query.isNotBlank() && !searching,
                                pressedScale = LiquidMotion.PressButton
                            ) { runSearch() }
                            .padding(horizontal = 16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (searching) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = Color.Black,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text("Search", color = Color.Black, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        }
                    }
                }

                if (searchError != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        searchError!!,
                        color = Color(0xFFFF453A),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }

                Spacer(Modifier.height(10.dp))

                // Results + Disconnect последним элементом; 120dp снизу —
                // просвет под плавающий бар (та же конвенция, что у остальных
                // экранов Библиотеки).
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 120.dp)
                ) {
                    if (results.isEmpty() && !searching && searchError == null) {
                        item {
                            Text(
                                "Type a query and tap Search.\nDownloads go to Playlists → Downloads.",
                                color = lc.textTertiary,
                                fontSize = 13.sp,
                                modifier = Modifier.padding(vertical = 24.dp)
                            )
                        }
                    }
                    items(results, key = { it.id }) { track ->
                        val sid = YandexDownloadManager.storageId(track.bareTrackId)
                        val prog = dlProgress[sid]
                        val saved = sid in offlineIds
                        YandexTrackRow(
                            track = track,
                            progress = prog,
                            isDownloaded = saved,
                            inputBg = inputBg,
                            onDownload = {
                                YandexDownloadManager.download(context, track) { outcome ->
                                    when (outcome) {
                                        YandexDownloadManager.Outcome.DONE -> refreshOffline()
                                        YandexDownloadManager.Outcome.FAILED -> scope.launch {
                                            searchError = "Download failed: ${track.title}"
                                        }
                                        YandexDownloadManager.Outcome.CANCELLED -> Unit
                                    }
                                }
                            },
                            onCancel = { YandexDownloadManager.cancel(sid) }
                        )
                    }
                    item {
                        Spacer(Modifier.height(10.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(42.dp)
                                .clip(RoundedCornerShape(21.dp))
                                .background(Color(0xFFFF453A).copy(alpha = 0.12f))
                                .liquidClickable(pressedScale = LiquidMotion.PressButton) {
                                    YandexAuthRepository.disconnect()
                                    results = emptyList()
                                    query = ""
                                    searchError = null
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text("Disconnect", color = Color(0xFFFF453A), fontWeight = FontWeight.Medium, fontSize = 14.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectBody(
    inputBg: Color,
    lc: com.liquidmusicglass.ui.theme.LiquidColors,
    onConnected: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var token by remember { mutableStateOf("") }
    var showToken by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showWebLogin by remember { mutableStateOf(false) }

    // Единая точка подключения: токен из встроенного входа и из ручного
    // поля идут через один и тот же connect() с шифрованным хранением.
    fun doConnect(raw: String) {
        if (busy || raw.isBlank()) return
        busy = true
        error = null
        scope.launch {
            try {
                YandexAuthRepository.connect(raw)
                token = "" // не держим plaintext в state после успеха
                onConnected()
            } catch (_: YandexUnauthorizedException) {
                error = "Invalid or expired token"
            } catch (_: YandexMusicException) {
                // Не пробрасываем e.message — там не должно быть токена, но UI-текст фиксированный
                error = "Connection failed"
            } catch (_: Exception) {
                error = "Connection failed"
            } finally {
                busy = false
            }
        }
    }

    Column {
        // Основной путь: встроенный вход через официальную страницу Яндекса —
        // токен перехватывается автоматически, вручную добывать не нужно.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(46.dp)
                .clip(RoundedCornerShape(23.dp))
                .background(if (!busy) YandexYellow else YandexYellow.copy(alpha = 0.4f))
                .liquidClickable(enabled = !busy, pressedScale = LiquidMotion.PressButton) {
                    error = null
                    showWebLogin = true
                },
            contentAlignment = Alignment.Center
        ) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(22.dp), color = Color.Black, strokeWidth = 2.dp)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painter = painterResource(R.drawable.ic_service_yandex),
                        contentDescription = null,
                        tint = Color.Unspecified,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Sign in with Yandex", color = Color.Black, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        Text(
            "Or paste an OAuth token manually " +
                "(guide: github.com/MarshalX/yandex-music-api/discussions/513):",
            color = lc.textTertiary,
            fontSize = 12.sp
        )
        Spacer(Modifier.height(8.dp))
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
                    value = token,
                    onValueChange = { token = it; error = null },
                    textStyle = TextStyle(color = lc.textPrimary, fontSize = 14.sp),
                    cursorBrush = SolidColor(YandexYellow),
                    singleLine = true,
                    visualTransformation = if (showToken) VisualTransformation.None
                    else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                    decorationBox = { inner ->
                        if (token.isEmpty()) {
                            Text("y0_AgA… OAuth token", color = lc.textTertiary, fontSize = 14.sp, maxLines = 1)
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
                        clipboard.getText()?.text?.let { token = it.trim(); error = null }
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.ContentPaste, "Paste", tint = lc.textSecondary, modifier = Modifier.size(20.dp))
            }
        }
        Text(
            if (showToken) "Hide token" else "Show token",
            color = lc.textTertiary,
            fontSize = 11.sp,
            modifier = Modifier
                .padding(top = 8.dp)
                .liquidClickable { showToken = !showToken }
        )
        if (error != null) {
            Spacer(Modifier.height(8.dp))
            Text(error!!, color = Color(0xFFFF453A), fontSize = 12.sp)
        }
        Spacer(Modifier.height(14.dp))
        val can = token.isNotBlank() && !busy
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(46.dp)
                .clip(RoundedCornerShape(23.dp))
                .background(if (can) inputBg else inputBg.copy(alpha = 0.5f))
                .liquidClickable(enabled = can, pressedScale = LiquidMotion.PressButton) {
                    doConnect(token)
                },
            contentAlignment = Alignment.Center
        ) {
            Text(
                "Connect with token",
                color = if (can) lc.textPrimary else lc.textTertiary,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp
            )
        }
    }

    if (showWebLogin) {
        YandexWebLoginDialog(
            onToken = { intercepted ->
                showWebLogin = false
                doConnect(intercepted)
            },
            onDismiss = { showWebLogin = false }
        )
    }
}

@Composable
private fun YandexTrackRow(
    track: YandexMusicClient.Track,
    progress: Float?,
    isDownloaded: Boolean,
    inputBg: Color,
    onDownload: () -> Unit,
    onCancel: () -> Unit = {},
) {
    val lc = LiquidTheme.colors
    val downloading = progress != null

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(inputBg)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color.Black.copy(alpha = 0.2f))
        ) {
            if (!track.coverUrl.isNullOrBlank()) {
                AsyncImage(
                    model = track.coverUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(
                    painter = painterResource(R.drawable.ic_service_yandex),
                    contentDescription = null,
                    tint = Color.Unspecified,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(24.dp)
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                track.title,
                color = lc.textPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                track.artistsLine,
                color = lc.textSecondary,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (downloading) {
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { (progress ?: 0f).coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = YandexYellow,
                    trackColor = YandexYellow.copy(alpha = 0.2f)
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(
                    when {
                        isDownloaded -> Color(0xFF34C759).copy(alpha = 0.15f)
                        downloading -> YandexYellow.copy(alpha = 0.2f)
                        else -> YandexYellow.copy(alpha = 0.18f)
                    }
                )
                .liquidClickable(
                    enabled = !isDownloaded && track.available,
                    pressedScale = LiquidMotion.PressIcon
                ) { if (downloading) onCancel() else onDownload() },
            contentAlignment = Alignment.Center
        ) {
            when {
                isDownloaded -> Icon(
                    Icons.Rounded.CheckCircle,
                    contentDescription = "Downloaded",
                    tint = Color(0xFF34C759),
                    modifier = Modifier.size(22.dp)
                )
                downloading -> {
                    // Тап по спиннеру = отмена загрузки
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = YandexYellow,
                        strokeWidth = 2.dp
                    )
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = "Cancel download",
                        tint = YandexYellow,
                        modifier = Modifier.size(12.dp)
                    )
                }
                else -> Icon(
                    Icons.Rounded.Download,
                    contentDescription = "Download",
                    tint = YandexYellow,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}
