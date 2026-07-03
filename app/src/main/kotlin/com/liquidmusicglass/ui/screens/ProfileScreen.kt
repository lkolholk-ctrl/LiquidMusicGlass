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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ExitToApp
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.liquidmusicglass.api.icm.IcmAuthRepository
import com.liquidmusicglass.data.local.LocalAuthManager
import com.liquidmusicglass.engine.AudioDownloadManager
import com.liquidmusicglass.ui.glass.GlassDialog
import com.liquidmusicglass.ui.glass.GlassDialogButton
import com.liquidmusicglass.ui.screens.camp.CampSelectorScreen
import com.liquidmusicglass.ui.theme.AppFontFamily
import com.liquidmusicglass.ui.theme.LiquidTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val AppleRed = Color(0xFFFC3C44)
private val PremiumPurple = Color(0xFF8B5CF6)
private val SurfaceDark = Color(0xFF1C1C1E)
private val SurfaceElevated = Color(0xFF2C2C2E)

@Composable
fun ProfileScreen(
    onOpenSettings: () -> Unit = {},
    onLogout: () -> Unit = {},
    onOpenAuth: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lc = LiquidTheme.colors

    val isLoggedIn by IcmAuthRepository.isLoggedIn.collectAsState()
    val isPremium by IcmAuthRepository.isPremium.collectAsState()
    val userEmail by IcmAuthRepository.userEmail.collectAsState()
    val telegramId by IcmAuthRepository.telegramId.collectAsState()
    val premiumExpiresAt by IcmAuthRepository.premiumExpiresAt.collectAsState()
    val profileName by IcmAuthRepository.profileName.collectAsState()
    val avatarUrl by IcmAuthRepository.avatarUrl.collectAsState()
    val subscription by IcmAuthRepository.subscription.collectAsState()

    var showClearDialog by remember { mutableStateOf(false) }

    LaunchedEffect(isLoggedIn) {
        if (isLoggedIn) IcmAuthRepository.fetchUserData()
    }

    val displayName = when {
        !profileName.isNullOrBlank() -> profileName!!
        userEmail != null -> userEmail!!.substringBefore("@").replaceFirstChar { it.uppercase() }
        telegramId != null -> "Telegram user"
        else -> "Guest"
    }

    // ── Danger Zone Dialog ──
    if (showClearDialog) {
        GlassDialog(
            visible = showClearDialog,
            onDismiss = { showClearDialog = false },
            icon = Icons.Rounded.DeleteForever,
            iconTint = AppleRed,
            title = "CLEAR ALL DOWNLOADS",
            message = "This will permanently delete all downloaded tracks from your device. This action cannot be undone.",
            primaryButton = GlassDialogButton(
                text = "Clear All",
                onClick = {
                    showClearDialog = false
                    scope.launch(Dispatchers.IO) {
                        AudioDownloadManager.clearAllDownloads(context)
                    }
                },
                backgroundColor = AppleRed,
                textColor = Color.White
            ),
            secondaryButton = GlassDialogButton(
                text = "Cancel",
                onClick = { showClearDialog = false },
                backgroundColor = if (lc.isDark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.05f),
                textColor = lc.textSecondary
            )
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(LiquidTheme.colors.settingsBackground)
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ── Status bar spacing ──
            item { Spacer(modifier = Modifier.windowInsetsTopHeight(WindowInsets.statusBars)) }
            item { Spacer(modifier = Modifier.height(24.dp)) }

            // ═══════════════════════════════════════════════════════════
            //  1. PROFILE HEADER & IDENTITY BLOCK
            // ═══════════════════════════════════════════════════════════
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Avatar — БОЛЬШАЯ круглая (полевой фидбек: «вид аккаунта
                    // с большой аватаркой»).
                    Box(
                        modifier = Modifier
                            .size(132.dp)
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            .background(if (lc.isDark) SurfaceDark else Color(0xFFF2F2F7)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (!avatarUrl.isNullOrBlank()) {
                            AsyncImage(
                                model = avatarUrl,
                                contentDescription = null,
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Icon(
                                Icons.Rounded.Person,
                                null,
                                tint = lc.iconMuted,
                                modifier = Modifier.size(64.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(18.dp))

                    // Username + Premium Star inline
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = displayName,
                            fontFamily = AppFontFamily,
                            color = lc.textPrimary,
                            fontSize = 26.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = (-0.02).sp
                        )
                        if (isPremium) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Icon(
                                imageVector = Icons.Rounded.Star,
                                contentDescription = "Premium",
                                tint = AppleRed,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // Premium status text — clean, no background substrate
                    if (isPremium) {
                        val sub = subscription
                        val planLabel = when (sub?.planType) {
                            "family" -> if (sub.isFamilyOwner) "Premium (Family Owner)" else "Premium (Family Member)"
                            "personal" -> "Premium (Personal)"
                            else -> "Premium"
                        }
                        val expiryText = when {
                            !sub?.expiresAtIso.isNullOrBlank() -> {
                                val dateStr = sub!!.expiresAtIso.substringBefore("T")
                                try {
                                    val parts = dateStr.split("-")
                                    "${parts[2]}.${parts[1]}.${parts[0]}"
                                } catch (_: Exception) { dateStr }
                            }
                            premiumExpiresAt > 0 -> SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())
                                .format(Date(premiumExpiresAt))
                            else -> "Lifetime"
                        }
                        val daysLeftText = if (sub != null && sub.daysLeft > 0) " • ${sub.daysLeft} days left" else ""
                        Text(
                            text = "$planLabel • Until $expiryText$daysLeftText",
                            fontFamily = AppFontFamily,
                            color = lc.textSecondary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            letterSpacing = 0.5.sp
                        )
                        // Region line
                        val regionName = sub?.regions?.firstOrNull()?.name ?: "Global"
                        val regionCode = sub?.regions?.firstOrNull()?.code?.uppercase() ?: "WW"
                        val displayRegion = when {
                            regionName.equals("США", ignoreCase = true) ||
                            regionName.equals("US", ignoreCase = true) ||
                            regionName.equals("United States", ignoreCase = true) -> "America"
                            else -> regionName
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Region: $displayRegion ($regionCode)",
                            fontFamily = AppFontFamily,
                            color = lc.textSecondary,
                            fontSize = 12.sp,
                            letterSpacing = 0.3.sp
                        )
                    } else {
                        Text(
                            text = "Free Plan",
                            fontFamily = AppFontFamily,
                            color = lc.textSecondary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            letterSpacing = 0.5.sp
                        )
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(24.dp)) }

            // ═══════════════════════════════════════════════════════════
            //  4. SETTINGS LIST
            // ═══════════════════════════════════════════════════════════
            item {
                Text(
                    text = "SETTINGS",
                    fontFamily = AppFontFamily,
                    color = lc.textSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp)
                )
            }

            item {
                SettingRowNavigable(
                    icon = Icons.Rounded.Settings,
                    label = "Playback & Appearance",
                    value = "EQ, Theme, Quality",
                    onClick = onOpenSettings
                )
            }

            item {
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 20.dp),
                    color = lc.divider
                )
            }

            // ── Account section ──
            if (isLoggedIn) {
                item {
                    SettingRowAction(
                        icon = Icons.AutoMirrored.Rounded.ExitToApp,
                        label = "Sign Out",
                        tint = AppleRed,
                        onClick = {
                            LocalAuthManager.logout()
                            IcmAuthRepository.logout()
                            onLogout()
                        }
                    )
                }
            } else {
                item {
                    SettingRowNavigable(
                        icon = Icons.Rounded.Person,
                        label = "Sign In",
                        value = "Connect your account",
                        onClick = onOpenAuth
                    )
                }
            }

            item { Spacer(modifier = Modifier.height(24.dp)) }

            // ═══════════════════════════════════════════════════════════
            //  5. DANGER ZONE
            // ═══════════════════════════════════════════════════════════
            item {
                Text(
                    text = "DANGER ZONE",
                    fontFamily = AppFontFamily,
                    color = AppleRed,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp)
                )
            }

            item {
                SettingRowAction(
                    icon = Icons.Rounded.DeleteForever,
                    label = "Clear All Downloads",
                    subtitle = "Permanently delete offline tracks",
                    tint = AppleRed,
                    onClick = { showClearDialog = true }
                )
            }

            item { Spacer(modifier = Modifier.height(32.dp)) }

            // ── Footer ──
            item {
                Text(
                    text = "Liquid Music Glass v1.0",
                    fontFamily = AppFontFamily,
                    color = lc.textTertiary,
                    fontSize = 10.sp,
                    textAlign = TextAlign.Center,
                    letterSpacing = 1.sp,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }
}

// ═══════════════════════════════════════════════════════════
//  Data class for storage stats
// ═══════════════════════════════════════════════════════════

private data class StorageStats(
    val gbString: String,
    val syncedCount: Int,
    val totalCount: Int
) {
    companion object {
        val ZERO = StorageStats("0.00", 0, 0)
    }
}

// ═══════════════════════════════════════════════════════════
//  Setting Row — Navigable (with chevron)
// ═══════════════════════════════════════════════════════════

@Composable
private fun SettingRowNavigable(
    icon: ImageVector,
    label: String,
    value: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            null,
            tint = LiquidTheme.colors.iconDefault.copy(alpha = 0.6f),
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                fontFamily = AppFontFamily,
                color = LiquidTheme.colors.textPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = value,
                fontFamily = AppFontFamily,
                color = LiquidTheme.colors.textSecondary,
                fontSize = 12.sp
            )
        }
        Icon(
            Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            null,
            tint = LiquidTheme.colors.iconMuted,
            modifier = Modifier.size(20.dp)
        )
    }
}

// ═══════════════════════════════════════════════════════════
//  Setting Row — Action (no chevron, optional subtitle)
// ═══════════════════════════════════════════════════════════

@Composable
private fun SettingRowAction(
    icon: ImageVector,
    label: String,
    subtitle: String? = null,
    tint: Color = LiquidTheme.colors.iconDefault,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (subtitle != null) 64.dp else 56.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            null,
            tint = tint.copy(alpha = 0.8f),
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                fontFamily = AppFontFamily,
                color = tint,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium
            )
            if (subtitle != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    fontFamily = AppFontFamily,
                    color = LiquidTheme.colors.textSecondary,
                    fontSize = 12.sp
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════
//  Subscription Cards
// ═══════════════════════════════════════════════════════════

@Composable
private fun SimpleSubscriptionCard(
    name: String,
    validUntil: String
) {
    val isDark = LiquidTheme.colors.isDark
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .height(64.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (isDark) Color(0xFF10141D) else Color(0xFFF2F2F7))
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(PremiumPurple.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Rounded.Star,
                    null,
                    tint = PremiumPurple,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column {
                Text(
                    text = name,
                    fontFamily = AppFontFamily,
                    color = LiquidTheme.colors.textPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "Valid until $validUntil",
                    fontFamily = AppFontFamily,
                    color = LiquidTheme.colors.textSecondary,
                    fontSize = 11.sp
                )
            }
        }
    }
}

@Composable
private fun SubscriptionDetailCard(
    sub: com.liquidmusicglass.api.icm.IcmSubscriptionResponse,
    premiumExpiresAt: Long
) {
    val planLabel = when (sub.planType) {
        "family" -> if (sub.isFamilyOwner) "Premium (Family Owner)" else "Premium (Family Member)"
        "personal" -> "Premium (Personal)"
        else -> "Premium"
    }

    val expiryText = when {
        !sub.expiresAtIso.isNullOrBlank() -> {
            val dateStr = sub.expiresAtIso.substringBefore("T")
            try {
                val parts = dateStr.split("-")
                "${parts[2]}.${parts[1]}.${parts[0]}"
            } catch (_: Exception) {
                dateStr
            }
        }
        premiumExpiresAt > 0 -> {
            SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(Date(premiumExpiresAt))
        }
        else -> "Lifetime"
    }

    val daysLeftText = if (sub.daysLeft > 0) " (${sub.daysLeft} days left)" else ""

    val isDark = LiquidTheme.colors.isDark
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (isDark) Color(0xFF10141D) else Color(0xFFF2F2F7))
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(PremiumPurple.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Rounded.Star,
                    null,
                    tint = PremiumPurple,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column {
                Text(
                    text = planLabel,
                    fontFamily = AppFontFamily,
                    color = LiquidTheme.colors.textPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "Valid until $expiryText$daysLeftText",
                    fontFamily = AppFontFamily,
                    color = LiquidTheme.colors.textSecondary,
                    fontSize = 11.sp
                )
            }
        }

        if (sub.regions.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Active Storefronts:",
                fontFamily = AppFontFamily,
                color = LiquidTheme.colors.textSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.5.sp
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                sub.regions.forEach { region ->
                    Box(
                         modifier = Modifier
                             .clip(RoundedCornerShape(10.dp))
                             .background(if (isDark) SurfaceElevated else Color(0xFFF2F2F7))
                             .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(5.dp)
                                    .clip(RoundedCornerShape(50))
                                    .background(Color(0xFF34C759))
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                             Text(
                                 text = "${region.name} (${region.code.uppercase()})",
                                 fontFamily = AppFontFamily,
                                 color = LiquidTheme.colors.textPrimary.copy(alpha = 0.75f),
                                 fontSize = 10.sp,
                                 fontWeight = FontWeight.Medium
                             )
                        }
                    }
                }
            }
        }
    }
}
