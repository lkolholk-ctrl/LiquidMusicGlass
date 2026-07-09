package com.liquidmusicglass.ui.screens

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.liquidmusicglass.data.yandex.YandexTokenExtractor
import com.liquidmusicglass.ui.glass.liquidClickable
import com.liquidmusicglass.ui.theme.LiquidMotion
import com.liquidmusicglass.ui.theme.LiquidTheme
import java.util.concurrent.atomic.AtomicBoolean

/**
 * client_id официального Android-приложения Яндекс.Музыки — его токены
 * принимает api.music.yandex.net (та же схема, что в гайде
 * MarshalX/yandex-music-api#513, только без ручного копирования).
 */
private const val YANDEX_MUSIC_CLIENT_ID = "23cabbbdc6cd418abb4b39c32c41195d"

private const val YANDEX_AUTH_URL =
    "https://oauth.yandex.ru/authorize?response_type=token&client_id=$YANDEX_MUSIC_CLIENT_ID"

/**
 * Встроенный вход в Яндекс: официальная страница oauth.yandex.ru в WebView.
 * После логина Яндекс редиректит на music.yandex.ru с токеном в URL-фрагменте —
 * перехватываем его и отдаём в [onToken]. Пароль вводится только на странице
 * Яндекса; приложение видит лишь итоговый OAuth-токен.
 *
 * URL с токеном никуда не логируется.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun YandexWebLoginDialog(
    onToken: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val lc = LiquidTheme.colors
    var progress by remember { mutableFloatStateOf(0f) }
    var pageLoading by remember { mutableStateOf(true) }
    // Токен отдаём ровно один раз: redirect может прилететь в несколько
    // колбэков WebViewClient подряд (onPageStarted + doUpdateVisitedHistory).
    val delivered = remember { AtomicBoolean(false) }

    fun handleUrl(url: String?): Boolean {
        if (YandexTokenExtractor.isAuthError(url)) {
            if (delivered.compareAndSet(false, true)) onDismiss()
            return true
        }
        val token = YandexTokenExtractor.accessTokenFrom(url) ?: return false
        if (delivered.compareAndSet(false, true)) onToken(token)
        return true
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(if (lc.isDark) Color(0xFF111113) else Color.White)
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Sign in to Yandex",
                        color = lc.textPrimary,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "oauth.yandex.ru",
                        color = lc.textTertiary,
                        fontSize = 11.sp
                    )
                }
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(lc.textPrimary.copy(alpha = 0.06f))
                        .liquidClickable(pressedScale = LiquidMotion.PressIcon) { onDismiss() },
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

            if (pageLoading) {
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp),
                    color = Color(0xFFFFCC00),
                    trackColor = Color(0xFFFFCC00).copy(alpha = 0.15f)
                )
            }

            AndroidView(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): Boolean {
                                val url = request?.url?.toString()
                                if (handleUrl(url)) return true
                                // Не-web схемы (интенты приложений Яндекса)
                                // внутри WebView не открыть — блокируем.
                                val scheme = request?.url?.scheme?.lowercase()
                                return scheme != "http" && scheme != "https"
                            }

                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                pageLoading = true
                                if (handleUrl(url)) view?.stopLoading()
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                pageLoading = false
                                handleUrl(url)
                            }

                            // SPA-переход music.yandex.ru/#access_token=… меняет
                            // только фрагмент — shouldOverrideUrlLoading может не
                            // сработать, ловим и здесь.
                            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                                if (handleUrl(url)) view?.stopLoading()
                            }
                        }
                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                progress = newProgress / 100f
                            }
                        }
                        loadUrl(YANDEX_AUTH_URL)
                    }
                },
                onRelease = { webView ->
                    webView.stopLoading()
                    webView.destroy()
                }
            )
        }
    }
}
