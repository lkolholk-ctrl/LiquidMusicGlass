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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ExitToApp
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.liquidmusicglass.api.icm.IcmAuthRepository
import com.liquidmusicglass.data.local.LocalAuthManager
import com.liquidmusicglass.ui.theme.LiquidTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val AppleRed = Color(0xFFFC3C44)
private val PremiumPurple = Color(0xFF8B5CF6)

@Composable
fun ProfileScreen(
    onOpenSettings: () -> Unit = {},
    onLogout: () -> Unit = {},
    onOpenAuth: () -> Unit = {}
) {
    val scroll = rememberScrollState()
    val context = LocalContext.current
    val isLoggedIn by IcmAuthRepository.isLoggedIn.collectAsState()
    val isPremium by IcmAuthRepository.isPremium.collectAsState()
    val userEmail by IcmAuthRepository.userEmail.collectAsState()
    val telegramId by IcmAuthRepository.telegramId.collectAsState()
    val premiumExpiresAt by IcmAuthRepository.premiumExpiresAt.collectAsState()
    val profileName by IcmAuthRepository.profileName.collectAsState()
    val avatarUrl by IcmAuthRepository.avatarUrl.collectAsState()

    // Fetch profile/preferences when screen opens and user is logged in
    LaunchedEffect(isLoggedIn) {
        if (isLoggedIn) {
            IcmAuthRepository.fetchUserData()
        }
    }

    val displayName = profileName?.takeIf { it.isNotBlank() } ?: when {
        userEmail != null -> userEmail!!.substringBefore("@").replaceFirstChar { it.uppercase() }
        telegramId != null -> "Telegram user"
        else -> "Guest"
    }

    val emailDisplay = when {
        userEmail != null -> userEmail!!
        isLoggedIn -> "Signed in via Telegram"
        else -> "Not signed in"
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(horizontal = 20.dp)
        ) {
            Spacer(modifier = Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
            Spacer(modifier = Modifier.height(20.dp))

            // Header
            Text(
                text = "Profile",
                color = LiquidTheme.colors.textPrimary,
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Avatar + Name
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (avatarUrl != null) {
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(avatarUrl)
                            .crossfade(true)
                            .build(),
                        contentDescription = "Avatar",
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape),
                        placeholder = null,
                        error = null
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF2A2A2A)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Rounded.Person,
                            null,
                            tint = Color.White.copy(alpha = 0.5f),
                            modifier = Modifier.size(36.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(16.dp))

                Column {
                    Text(
                        text = displayName,
                        color = Color.White,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = emailDisplay,
                        color = Color.White.copy(alpha = 0.5f),
                        fontSize = 14.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Premium Badge
            if (isPremium) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(PremiumPurple.copy(alpha = 0.15f))
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Rounded.Star,
                            null,
                            tint = PremiumPurple,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "PREMIUM",
                                color = PremiumPurple,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                            if (premiumExpiresAt > 0) {
                                val date = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())
                                    .format(Date(premiumExpiresAt))
                                Text(
                                    text = "Valid until $date",
                                    color = PremiumPurple.copy(alpha = 0.7f),
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            } else {
                // Upgrade to Premium banner
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(80.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFF1A1A1A))
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Upgrade to Premium",
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Download tracks, high quality audio",
                                color = Color.White.copy(alpha = 0.5f),
                                fontSize = 13.sp
                            )
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .background(AppleRed)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) {
                                    // Open the ICM premium bot in Telegram
                                    try {
                                        val builder = androidx.browser.customtabs.CustomTabsIntent.Builder()
                                        builder.setShowTitle(true)
                                        builder.setToolbarColor(android.graphics.Color.parseColor("#0088CC"))
                                        builder.build().launchUrl(
                                            context,
                                            android.net.Uri.parse("https://t.me/byicmbot")
                                        )
                                    } catch (e: Exception) {
                                        context.startActivity(
                                            android.content.Intent(
                                                android.content.Intent.ACTION_VIEW,
                                                android.net.Uri.parse("https://t.me/byicmbot")
                                            )
                                        )
                                    }
                                }
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = "Upgrade",
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Menu Items
            ProfileMenuItem(
                icon = Icons.Rounded.Settings,
                title = "Settings",
                subtitle = "Playback, EQ, Appearance",
                onClick = onOpenSettings
            )

            Spacer(modifier = Modifier.height(8.dp))

            if (isLoggedIn) {
                ProfileMenuItem(
                    icon = Icons.AutoMirrored.Rounded.ExitToApp,
                    title = "Sign Out",
                    subtitle = "Log out of your account",
                    onClick = {
                        LocalAuthManager.logout()
                        IcmAuthRepository.logout()
                        onLogout()
                    },
                    tint = AppleRed
                )
            } else {
                // Sign In button
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .clip(RoundedCornerShape(percent = 50))
                        .background(AppleRed)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { onOpenAuth() },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Sign In",
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Subscription info
            if (isPremium) {
                Text(
                    text = "Your Subscription",
                    color = LiquidTheme.colors.textPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(12.dp))

                SubscriptionCard(
                    name = "Premium",
                    validUntil = if (premiumExpiresAt > 0) {
                        SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())
                            .format(Date(premiumExpiresAt))
                    } else "Lifetime"
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            // Footer
            Text(
                text = "Liquid Music Glass v1.0",
                color = Color.White.copy(alpha = 0.2f),
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ProfileMenuItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    tint: Color = Color.White
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF1A1A1A))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            null,
            tint = tint.copy(alpha = 0.7f),
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = tint,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = subtitle,
                color = Color.White.copy(alpha = 0.4f),
                fontSize = 12.sp
            )
        }
        Icon(
            Icons.Rounded.ChevronRight,
            null,
            tint = Color.White.copy(alpha = 0.3f),
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun SubscriptionCard(
    name: String,
    validUntil: String
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF1A1A1A))
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(PremiumPurple.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Rounded.Star,
                    null,
                    tint = PremiumPurple,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column {
                Text(
                    text = name,
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "Valid until $validUntil",
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 13.sp
                )
            }
        }
    }
}
