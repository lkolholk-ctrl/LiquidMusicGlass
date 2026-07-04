package com.liquidmusicglass.ui.debug

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.liquidmusicglass.debug.DebugLog
import com.liquidmusicglass.engine.AppSettings
import com.liquidmusicglass.engine.automix.AudioQuirks
import com.liquidmusicglass.engine.automix.AutoMixNativeEngine
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Полноценный logcat ВНУТРИ приложения — без рута, ПК и Wi-Fi.
 *
 * Android отдаёт процессу логи ТОЛЬКО его собственного UID, поэтому это
 * безопасно оставлять в релизе: чужие приложения/система не видны, а для
 * полевых репортов («нажми EXP и скинь файл») — незаменимо.
 *
 * Чтение — фоновый поток над `logcat -v threadtime` (живой хвост); строки
 * копятся в кольцевом буфере, UI обновляется дросселированно (4 раза/с),
 * чтобы поток логов не съедал перерисовку.
 */
object LogcatReader {

    private const val MAX_LINES = 3000
    private const val TAIL_ON_START = 1200

    private val buf = ArrayDeque<String>()
    private val lock = Any()
    @Volatile private var proc: Process? = null
    @Volatile private var bumpPending = false
    private val main = Handler(Looper.getMainLooper())

    /** Счётчик версий буфера — Compose пересобирает список по нему. */
    val version = mutableStateOf(0L)

    /** Открыт ли полноэкранный просмотрщик (управляется кнопками оверлея). */
    val viewerOpen = mutableStateOf(false)

    val running: Boolean get() = proc != null

    fun start() {
        if (proc != null) return
        synchronized(lock) { buf.clear() }
        try {
            val p = Runtime.getRuntime().exec(
                arrayOf("logcat", "-v", "threadtime", "-T", TAIL_ON_START.toString())
            )
            proc = p
            Thread({
                try {
                    p.inputStream.bufferedReader().forEachLine { line ->
                        synchronized(lock) {
                            buf.addLast(line)
                            while (buf.size > MAX_LINES) buf.removeFirst()
                        }
                        scheduleBump()
                    }
                } catch (_: Throwable) {
                    // процесс убит при stop() — штатный выход
                } finally {
                    if (proc === p) proc = null
                }
            }, "lmg-logcat").apply { isDaemon = true }.start()
        } catch (t: Throwable) {
            synchronized(lock) { buf.addLast("!! logcat недоступен: $t") }
            scheduleBump()
        }
    }

    fun stop() {
        proc?.destroy()
        proc = null
    }

    fun snapshot(): List<String> = synchronized(lock) { buf.toList() }

    private fun scheduleBump() {
        if (bumpPending) return
        bumpPending = true
        main.postDelayed({ bumpPending = false; version.value++ }, 250)
    }

    /** Разовый снимок logcat (когда живой читатель не запущен — для экспорта). */
    fun dumpOnce(): List<String> = runCatching {
        Runtime.getRuntime()
            .exec(arrayOf("logcat", "-d", "-v", "threadtime", "-t", "1500"))
            .inputStream.bufferedReader().readLines()
    }.getOrDefault(emptyList())

    /**
     * Экспорт одним файлом: устройство/версия/QUIRKS/режим + OBOE-дамп +
     * DebugLog + logcat → cache/logs/… → системная шторка «Поделиться».
     * Файл пишется в фоне; шторка открывается с main.
     */
    fun exportLogs(context: Context) {
        val appCtx = context.applicationContext
        val debugLines = DebugLog.snapshotHistory()   // snapshot читается на main
        Thread({
            runCatching {
                val dir = File(appCtx.cacheDir, "logs").apply { mkdirs() }
                val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val file = File(dir, "lmg_log_$stamp.txt")
                file.bufferedWriter().use { w ->
                    val ver = runCatching {
                        appCtx.packageManager.getPackageInfo(appCtx.packageName, 0).versionName
                    }.getOrNull()
                    w.appendLine("== LiquidMusicGlass log export ==")
                    w.appendLine(
                        "device: ${Build.MANUFACTURER}/${Build.BRAND} ${Build.MODEL} " +
                            "(${Build.DEVICE}) Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})"
                    )
                    w.appendLine("app: $ver")
                    w.appendLine("quirks: ${runCatching { AudioQuirks.describe() }.getOrDefault("?")}")
                    w.appendLine(
                        "audio mode: ${AppSettings.audioCompatMode.value} " +
                            "(auto=${AppSettings.audioCompatAuto.value})"
                    )
                    w.appendLine()
                    w.appendLine("== OBOE ==")
                    w.appendLine(runCatching { AutoMixNativeEngine.audioDiagnostics() }.getOrDefault("?"))
                    w.appendLine()
                    w.appendLine("== DebugLog ==")
                    debugLines.forEach { w.appendLine(it) }
                    w.appendLine()
                    w.appendLine("== logcat (собственный процесс) ==")
                    (if (running) snapshot() else dumpOnce()).forEach { w.appendLine(it) }
                }
                val uri = FileProvider.getUriForFile(
                    appCtx, appCtx.packageName + ".fileprovider", file
                )
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                main.post {
                    runCatching {
                        appCtx.startActivity(
                            Intent.createChooser(send, "Экспорт логов")
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                        DebugLog.add("LOG export: ${file.name} (${file.length() / 1024} КБ)")
                    }
                }
            }.onFailure { DebugLog.add("LOG export FAILED: $it") }
        }, "lmg-log-export").start()
    }
}

// Пороги фильтра по уровню: индекс в списке → минимальный приоритет.
private val LEVEL_FILTERS = listOf("ALL", "I+", "W+", "E")
private val LEVEL_ORDER = "VDIWEF"

private fun levelOf(line: String): Char {
    // threadtime: "MM-DD HH:MM:SS.mmm  PID  TID LEVEL TAG: msg"
    var field = 0
    var i = 0
    while (i < line.length) {
        if (line[i] == ' ') {
            i++
            continue
        }
        val start = i
        while (i < line.length && line[i] != ' ') i++
        if (field == 4) return line[start]
        field++
    }
    return 'V'
}

private fun passesLevel(line: String, filterIdx: Int): Boolean {
    if (filterIdx == 0) return true
    val min = when (filterIdx) {
        1 -> 'I'; 2 -> 'W'; else -> 'E'
    }
    return LEVEL_ORDER.indexOf(levelOf(line)) >= LEVEL_ORDER.indexOf(min)
}

private fun colorFor(line: String): Color = when (levelOf(line)) {
    'E', 'F' -> Color(0xFFFF6E6E)
    'W' -> Color(0xFFFFC94D)
    'I' -> Color(0xFFD8FFD8)
    'D' -> Color(0xFF9ECFFF)
    else -> Color(0xFF9E9E9E)
}

/** Полноэкранный просмотрщик: живой хвост, фильтр уровня, поиск, экспорт. */
@Composable
fun LogcatScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        LogcatReader.start()
        onDispose { LogcatReader.stop() }
    }

    var follow by remember { mutableStateOf(true) }
    var levelIdx by remember { mutableStateOf(0) }
    var query by remember { mutableStateOf("") }
    // Источник: SYS — системный logcat процесса, DBG — внутренний журнал
    // (DebugLog, 2000 строк). Honor/Huawei режут logcat на уровне системы —
    // если за 1.5с не пришло ни строки, сами переключаемся на DBG.
    var showDebug by remember { mutableStateOf(false) }
    var autoSwitched by remember { mutableStateOf(false) }
    val version by LogcatReader.version
    val dbgVersion by DebugLog.version
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(1500)
        if (LogcatReader.snapshot().isEmpty()) {
            showDebug = true
            autoSwitched = true
        }
    }

    val lines = remember(version, dbgVersion, levelIdx, query, showDebug) {
        if (showDebug) {
            DebugLog.snapshotHistory().filter { l ->
                query.isBlank() || l.contains(query, ignoreCase = true)
            }
        } else LogcatReader.snapshot().filter { l ->
            passesLevel(l, levelIdx) && (query.isBlank() || l.contains(query, ignoreCase = true))
        }
    }

    val listState = rememberLazyListState()
    LaunchedEffect(lines.size, follow) {
        if (follow && lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xF5000000))
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "LOGCAT", color = Color(0xFFFFD54F), fontSize = 12.sp,
                fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace
            )
            Spacer(Modifier.width(14.dp))
            OverlayButton(if (showDebug) "DBG" else "SYS", Color(0xFFCE93D8)) {
                showDebug = !showDebug
            }
            Spacer(Modifier.width(10.dp))
            OverlayButton(LEVEL_FILTERS[levelIdx], Color(0xFF4FC3F7)) {
                levelIdx = (levelIdx + 1) % LEVEL_FILTERS.size
            }
            Spacer(Modifier.width(10.dp))
            OverlayButton(if (follow) "FOLLOW" else "PAUSED", if (follow) Color(0xFF9CFF9C) else Color(0xFFFF8A65)) {
                follow = !follow
            }
            Spacer(Modifier.width(10.dp))
            OverlayButton("EXPORT", Color(0xFF4FC3F7)) { LogcatReader.exportLogs(context) }
            Spacer(Modifier.width(10.dp))
            OverlayButton("CLOSE", Color(0xFFFF8A65)) { onClose() }
        }

        // Поиск по подстроке (регистронезависимый).
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .background(Color(0x22FFFFFF))
                .padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            if (query.isEmpty()) {
                Text(
                    "фильтр (текст/тег)…", color = Color(0x66FFFFFF),
                    fontSize = 11.sp, fontFamily = FontFamily.Monospace
                )
            }
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                textStyle = TextStyle(
                    color = Color.White, fontSize = 11.sp, fontFamily = FontFamily.Monospace
                ),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(Color.White),
                modifier = Modifier.fillMaxWidth()
            )
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 6.dp)
        ) {
            items(lines) { l ->
                Text(
                    l,
                    color = if (showDebug) Color(0xFF9CFF9C) else colorFor(l),
                    fontSize = 9.sp,
                    lineHeight = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        Text(
            "${lines.size} строк${if (query.isNotBlank() || (!showDebug && levelIdx != 0)) " (фильтр)" else ""} · " +
                when {
                    showDebug && autoSwitched ->
                        "внутренний журнал (вендор порезал logcat — Honor/Huawei)"
                    showDebug -> "внутренний журнал (DBG)"
                    else -> "только логи этого приложения"
                },
            color = Color(0x88FFFFFF), fontSize = 9.sp, fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun OverlayButton(label: String, tint: Color, onClick: () -> Unit) {
    Text(
        label, color = tint, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
        modifier = Modifier.clickable { onClick() }.padding(4.dp)
    )
}
