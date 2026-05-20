package com.liquidmusicglass.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val AppleRed = Color(0xFFFC3C44)
private val TelegramBlue = Color(0xFF0088CC)

@Composable
fun AuthScreen(
    onAuthSuccess: () -> Unit = {},
    onBack: () -> Unit = {}
) {
    val context = LocalContext.current

    // ICM auth completes in MainActivity (deep link). When the login state
    // flips to true, leave the auth screen.
    val isLoggedIn by com.liquidmusicglass.api.icm.IcmAuthRepository.isLoggedIn.collectAsState()
    LaunchedEffect(isLoggedIn) {
        if (isLoggedIn) onAuthSuccess()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
            Spacer(modifier = Modifier.height(12.dp))

            // Back button
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF1C1C1E))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { onBack() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Logo
            Text(
                text = "Liquid Music",
                color = Color.White,
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Text(
                text = "Glass",
                color = AppleRed,
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Welcome",
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 15.sp,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "powered by ICM Music",
                color = Color.White.copy(alpha = 0.3f),
                fontSize = 12.sp,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(40.dp))

            // Telegram auth via ICM API
            // Generate stable partner_user_id per device. Stored exclusively
            // via IcmAuthRepository so the same value is used when ICM redirects
            // back into the app — backend matches /link by partner_user_id and
            // changing it post-link is what breaks the wave radio.
            val prefs = context.getSharedPreferences("icm_auth", android.content.Context.MODE_PRIVATE)
            val partnerUserId = com.liquidmusicglass.api.icm.IcmAuthRepository
                .ensurePartnerUserId()
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(TelegramBlue)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        // Fresh random state per attempt — for CSRF protection.
                        // Saved so MainActivity can verify it on callback.
                        val state = java.util.UUID.randomUUID().toString()
                        prefs.edit().putString("oauth_state", state).apply()

                        // Build link URL via IcmApi (handles URL-encoding)
                        // Docs: /partner/<partner_id>/link?partner_user_id=...&redirect_uri=...&state=...
                        // Server (liquid.glassfiles.ru) is whitelisted and will redirect to app
                        val telegramAuthUrl = com.liquidmusicglass.api.icm.IcmApi.getInstance()
                            .buildAccountLinkUrl(
                                partnerId = "msng",
                                partnerUserId = partnerUserId,
                                redirectUri = "https://liquid.glassfiles.ru/auth/telegram",
                                state = state
                            )

                        // Use Chrome Custom Tabs for proper Telegram widget support
                        try {
                            val builder = androidx.browser.customtabs.CustomTabsIntent.Builder()
                            builder.setShowTitle(true)
                            builder.setToolbarColor(android.graphics.Color.parseColor("#0088CC"))
                            val customTabsIntent = builder.build()
                            customTabsIntent.launchUrl(context, android.net.Uri.parse(telegramAuthUrl))
                        } catch (e: Exception) {
                            // Fallback to regular browser if Custom Tabs unavailable
                            val intent = android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse(telegramAuthUrl)
                            )
                            context.startActivity(intent)
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Rounded.Send,
                        null,
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "Continue with Telegram",
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "By continuing, you agree to our Terms of Service",
                color = Color.White.copy(alpha = 0.3f),
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.weight(1f))
        }
    }
}
