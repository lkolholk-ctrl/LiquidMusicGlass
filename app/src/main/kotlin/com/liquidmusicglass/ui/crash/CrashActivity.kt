package com.liquidmusicglass.ui.crash

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.liquidmusicglass.logging.CrashHandler
import com.liquidmusicglass.ui.theme.LiquidMusicGlassTheme

class CrashActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val crashText = CrashHandler.readAndClearAll(this) ?: "Log not found."

        setContent {
            LiquidMusicGlassTheme {
                CrashScreen(
                    crashText = crashText,
                    onCopy = { copyToClipboard(this, crashText) },
                    onShare = { shareLog(this, crashText) }
                )
            }
        }
    }

    private fun copyToClipboard(context: Context, text: String) {
        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("crash_log", text))
            // Без явного подтверждения юзер думает, что «ничего не произошло»
            // (полевой отзыв: «Copy никуда не копируется»). Android 13+ рисует
            // системную плашку сам, но на 12 и ниже — нет, поэтому тостим всегда.
            Toast.makeText(context, "Crash log copied to clipboard", Toast.LENGTH_SHORT).show()
        } catch (t: Throwable) {
            Toast.makeText(context, "Copy failed: ${t.message}", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Отправка лога. Раньше весь текст улетал через Intent.EXTRA_TEXT — на
     * большом логе это TransactionTooLargeException (лог «никуда не
     * отправлялся»), да и мессенджеры такой объём в тексте часто режут.
     * Пишем лог во ФАЙЛ (cache/logs/, уже разрешён в file_paths.xml) и шлём как
     * вложение через FileProvider — Telegram/почта примут его целиком. Если
     * файловый путь недоступен — деградируем к EXTRA_TEXT, всё в try/catch с
     * тостом, чтобы кнопка никогда не «молчала».
     */
    private fun shareLog(context: Context, text: String) {
        val fileIntent = try {
            val dir = File(context.cacheDir, "logs").apply { mkdirs() }
            val file = File(dir, "crash_log.txt").apply { writeText(text) }
            val uri: Uri = FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", file
            )
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "LiquidMusicGlass crash log")
                putExtra(Intent.EXTRA_STREAM, uri)
                // Дублируем текстом для клиентов, читающих только EXTRA_TEXT.
                putExtra(Intent.EXTRA_TEXT, text.take(90_000))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } catch (_: Throwable) {
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "LiquidMusicGlass crash log")
                putExtra(Intent.EXTRA_TEXT, text.take(90_000))
            }
        }
        try {
            context.startActivity(Intent.createChooser(fileIntent, "Send log via..."))
        } catch (t: Throwable) {
            Toast.makeText(context, "No app to send the log: ${t.message}", Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        const val EXTRA_CRASH_TEXT = "extra_crash_text"
    }
}

@Composable
private fun CrashScreen(
    crashText: String,
    onCopy: () -> Unit,
    onShare: () -> Unit
) {
    val scrollState = rememberScrollState()
    val context = LocalContext.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF12090B),
                        Color(0xFF08090F),
                        Color(0xFF030406)
                    )
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(20.dp)
        ) {
            Text(
                text = "The app crashed",
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Copy or send the crash log.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.72f)
            )
            Spacer(modifier = Modifier.height(18.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ActionButton(
                    text = "Copy",
                    onClick = onCopy,
                    modifier = Modifier.weight(1f)
                )
                ActionButton(
                    text = "Share",
                    onClick = onShare,
                    modifier = Modifier.weight(1f)
                )
                ActionButton(
                    text = "✕",
                    onClick = { if (context is ComponentActivity) context.finish() },
                    modifier = Modifier.weight(0.5f)
                )
            }
            Spacer(modifier = Modifier.height(18.dp))
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        color = Color.White.copy(alpha = 0.06f),
                        shape = RoundedCornerShape(24.dp)
                    )
                    .padding(16.dp)
            ) {
                Text(
                    text = crashText,
                    color = Color(0xFFF3F3F3),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.verticalScroll(scrollState)
                )
            }
        }
    }
}

@Composable
private fun ActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .height(52.dp)
            .background(
                color = Color.White.copy(alpha = 0.10f),
                shape = RoundedCornerShape(18.dp)
            )
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(text = text, color = Color.White)
    }
}