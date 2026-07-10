package com.liquidmusicglass.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Star
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.liquidmusicglass.api.icm.IcmAuthRepository
import com.liquidmusicglass.data.local.LocalAuthManager
import com.liquidmusicglass.ui.glass.liquidClickable
import com.liquidmusicglass.ui.theme.AppFontFamily
import com.liquidmusicglass.ui.theme.LiquidTheme
import kotlinx.coroutines.launch
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

    LaunchedEffect(isLoggedIn) {
        if (isLoggedIn) IcmAuthRepository.fetchUserData()
    }

    // ── Подписки на артистов + регион (всё про подписку живёт в профиле) ──
    var followedArtists by remember {
        mutableStateOf<List<com.liquidmusicglass.api.icm.IcmLibraryArtist>>(emptyList())
    }
    var regionInfo by remember {
        mutableStateOf<com.liquidmusicglass.api.icm.IcmRegionResponse?>(null)
    }
    var regionExpanded by remember { mutableStateOf(false) }
    var regionBusy by remember { mutableStateOf(false) }
    LaunchedEffect(isLoggedIn) {
        if (isLoggedIn) {
            followedArtists = runCatching {
                com.liquidmusicglass.api.icm.IcmRepository
                    .getLibrarySubscriptions(limit = 50)?.items
            }.getOrNull() ?: emptyList()
            regionInfo = runCatching {
                com.liquidmusicglass.api.icm.IcmRepository.getUserRegion()
            }.getOrNull()
        } else {
            followedArtists = emptyList()
            regionInfo = null
        }
    }

    val displayName = when {
        !profileName.isNullOrBlank() -> profileName!!
        userEmail != null -> userEmail!!.substringBefore("@").replaceFirstChar { it.uppercase() }
        telegramId != null -> "Telegram user"
        else -> "Guest"
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
                        val rawCode = sub?.regions?.firstOrNull()?.code
                        val regionCode = if (rawCode.equals("nz", ignoreCase = true)) "US"
                            else rawCode?.uppercase() ?: "WW"
                        // NZ — аварийное зеркало US (см. регион-селектор ниже):
                        // показываем как America, не раскрывая юзеру фейловер.
                        val regionCodeRaw = sub?.regions?.firstOrNull()?.code
                        val displayRegion = when {
                            regionCodeRaw.equals("nz", ignoreCase = true) ||
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
            //  2. ARTISTS YOU FOLLOW — подписки ICM (/library/subscriptions)
            // ═══════════════════════════════════════════════════════════
            if (isLoggedIn && followedArtists.isNotEmpty()) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp)
                            .clip(RoundedCornerShape(28.dp))
                            .background(if (lc.isDark) SurfaceDark else Color(0xFFF2F2F7))
                            .padding(vertical = 16.dp)
                    ) {
                        Text(
                            text = "ARTISTS YOU FOLLOW",
                            fontFamily = AppFontFamily,
                            color = lc.textSecondary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.5.sp,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        androidx.compose.foundation.lazy.LazyRow(
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            items(followedArtists.size) { i ->
                                val artist = followedArtists[i]
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier
                                        .width(76.dp)
                                        .liquidClickable {
                                            // Тап = радио по артисту (мгновенный старт).
                                            com.liquidmusicglass.engine.PlayerController
                                                .startArtistWave(context, artist.id, artist.displayName)
                                        }
                                ) {
                                    val img = artist.image ?: artist.cover
                                    Box(
                                        modifier = Modifier
                                            .size(64.dp)
                                            .clip(androidx.compose.foundation.shape.CircleShape)
                                            .background(if (lc.isDark) SurfaceElevated else Color.White),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (!img.isNullOrBlank()) {
                                            AsyncImage(
                                                model = img,
                                                contentDescription = null,
                                                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                                modifier = Modifier.fillMaxSize()
                                            )
                                        } else {
                                            Icon(
                                                Icons.Rounded.Person, null,
                                                tint = lc.iconMuted,
                                                modifier = Modifier.size(28.dp)
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = artist.displayName,
                                        fontFamily = AppFontFamily,
                                        color = lc.textPrimary,
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
                item { Spacer(modifier = Modifier.height(16.dp)) }
            }

            // ═══════════════════════════════════════════════════════════
            //  3. REGION — текущий + выбор из доступных (/me/region)
            // ═══════════════════════════════════════════════════════════
            if (isLoggedIn && regionInfo != null) {
                item {
                    val ri = regionInfo!!
                    // NZ у ICM — аварийное зеркало US (включают при проблемах с
                    // US-аккаунтом; менеджер: «показывайте us free, разницы нет»).
                    // Юзеру не показываем кухню фейловера — рисуем как United States.
                    fun regionDisplay(code: String, name: String): String =
                        if (code.equals("nz", true)) "United States" else name
                    // Селектор — только то, что доступно партнёрскому ключу
                    // (allowed_by_partner). Пустой список = старый сервер → показываем всё.
                    val selectableRegions = if (ri.allowedByPartner.isEmpty()) ri.available
                        else ri.available.filter { it.code in ri.allowedByPartner }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp)
                            .clip(RoundedCornerShape(28.dp))
                            .background(if (lc.isDark) SurfaceDark else Color(0xFFF2F2F7))
                            .padding(vertical = 4.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                                .liquidClickable { regionExpanded = !regionExpanded }
                                .padding(horizontal = 20.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Region",
                                    fontFamily = AppFontFamily,
                                    color = lc.textPrimary,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = ri.available.firstOrNull { it.code == ri.current }
                                        ?.let { regionDisplay(it.code, it.name) }
                                        ?: regionDisplay(ri.current, ri.current.uppercase()),
                                    fontFamily = AppFontFamily,
                                    color = lc.textSecondary,
                                    fontSize = 12.sp
                                )
                            }
                            if (regionBusy) {
                                Text("…", color = lc.textSecondary, fontSize = 15.sp)
                            } else {
                                Icon(
                                    Icons.AutoMirrored.Rounded.KeyboardArrowRight, null,
                                    tint = lc.iconMuted,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        if (regionExpanded) {
                            for (r in selectableRegions) {
                                val selected = r.code == ri.current
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(44.dp)
                                        .liquidClickable(enabled = !regionBusy && !selected) {
                                            regionBusy = true
                                            scope.launch {
                                                val ok = runCatching {
                                                    com.liquidmusicglass.api.icm.IcmRepository
                                                        .updateUserRegion(r.code)
                                                }.getOrNull() != null
                                                if (ok) {
                                                    regionInfo = runCatching {
                                                        com.liquidmusicglass.api.icm.IcmRepository.getUserRegion()
                                                    }.getOrNull() ?: regionInfo
                                                    IcmAuthRepository.fetchUserData()
                                                } else {
                                                    android.widget.Toast.makeText(
                                                        context, "Couldn't switch region",
                                                        android.widget.Toast.LENGTH_SHORT
                                                    ).show()
                                                }
                                                regionBusy = false
                                                regionExpanded = false
                                            }
                                        }
                                        .padding(horizontal = 20.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // Пометка честно из ДВУХ полей сервера:
                                    // requires_subscription главнее флага free
                                    // (флаг у ICM может значить «доступен твоему
                                    // партнёрскому ключу», а не «бесплатен всем»).
                                    val needsSub = r.code in ri.requiresSubscription
                                    Text(
                                        text = regionDisplay(r.code, r.name) + when {
                                            needsSub -> " • premium"
                                            r.free -> " • free"
                                            else -> ""
                                        },
                                        fontFamily = AppFontFamily,
                                        color = if (selected) AppleRed else lc.textPrimary,
                                        fontSize = 14.sp,
                                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (selected) {
                                        Icon(
                                            Icons.Rounded.Star, null,
                                            tint = AppleRed,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                    }
                }
                item { Spacer(modifier = Modifier.height(16.dp)) }
            }

            // ═══════════════════════════════════════════════════════════
            //  4. ACCOUNT — единственная карточка-подложка (28dp, как в
            //  настройках). «Playback & Appearance» удалён (профиль и так
            //  открывается ИЗ настроек — ссылка на самого себя), «Danger
            //  Zone / Clear All Downloads» удалён как бессмысленный здесь.
            // ═══════════════════════════════════════════════════════════
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                        .clip(RoundedCornerShape(28.dp))
                        .background(if (lc.isDark) SurfaceDark else Color(0xFFF2F2F7))
                ) {
                    if (isLoggedIn) {
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
                    } else {
                        SettingRowNavigable(
                            icon = Icons.Rounded.Person,
                            label = "Sign In",
                            value = "Connect your account",
                            onClick = onOpenAuth
                        )
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(32.dp)) }

            // ── Footer: честная версия из BuildConfig (versionName у нас —
            // дата сборки CI), а не зашитое «v1.0». ──
            item {
                Text(
                    text = "Liquid Music Glass • ${com.liquidmusicglass.BuildConfig.VERSION_NAME}",
                    fontFamily = AppFontFamily,
                    color = lc.textTertiary,
                    fontSize = 10.sp,
                    textAlign = TextAlign.Center,
                    letterSpacing = 1.sp,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Диагностика: копирует последние строки ICM-лога в буфер обмена (тот же
            // IcmApiFileLogger, что и в LibraryScreen, но доступно всегда — чтобы снять
            // причину сбоя волны без ошибки импорта).
            item {
                Spacer(modifier = Modifier.height(12.dp))
                val clipboard = LocalClipboardManager.current
                Text(
                    text = "Copy ICM logs",
                    color = Color(0xFF0A84FF),
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            val logs = com.liquidmusicglass.api.icm.IcmApiFileLogger.getRecentLogs(200)
                            clipboard.setText(androidx.compose.ui.text.AnnotatedString(logs))
                            android.widget.Toast.makeText(
                                context, "ICM logs copied", android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                        .padding(vertical = 8.dp)
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
            .liquidClickable(onClick = onClick)
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
            .liquidClickable(onClick = onClick)
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
