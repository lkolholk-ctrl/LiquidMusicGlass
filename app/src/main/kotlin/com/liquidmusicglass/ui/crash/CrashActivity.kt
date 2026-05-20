package com.liquidmusicglass.ui.crash

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
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

        val crashText = CrashHandler.readAndClearAll(this) ?: "Лог не найден."

        setContent {
            LiquidMusicGlassTheme {
                CrashScreen(
                    crashText = crashText,
                    onCopy = { copyToClipboard(this, crashText) },
                    onShare = { shareText(this, crashText) }
                )
            }
        }
    }

    private fun copyToClipboard(context: Context, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("crash_log", text))
    }

    private fun shareText(context: Context, text: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "LiquidMusicGlass crash log")
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(intent, "Отправить лог через…"))
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
                text = "Приложение вылетело",
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Скопируй или отправь лог через мессенджер.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.72f)
            )
            Spacer(modifier = Modifier.height(18.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ActionButton(
                    text = "📋 Скопировать",
                    onClick = onCopy,
                    modifier = Modifier.weight(1f)
                )
                ActionButton(
                    text = "📤 Поделиться",
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