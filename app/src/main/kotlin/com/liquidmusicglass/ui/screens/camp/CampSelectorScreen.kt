package com.liquidmusicglass.ui.screens.camp

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.HighQuality
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Radio
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.Waves
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liquidmusicglass.camp.AudioFormat
import com.liquidmusicglass.camp.CampCapabilities
import com.liquidmusicglass.camp.Capabilities
import com.liquidmusicglass.camp.Feature
import com.liquidmusicglass.camp.FeatureAccessManager
import com.liquidmusicglass.camp.MusicCamp
import com.liquidmusicglass.ui.theme.LiquidTheme

private val AppleRed = Color(0xFFFC3C44)
private val AppleRedLight = Color(0xFFFF6B6B)
private val YouTubeRed = Color(0xFFFF0000)
private val GlassSurface = Color(0xFF1A1A1A)
private val GlassBorder = Color(0x33FFFFFF)
private val NeonGreen = Color(0xFF39FF14)
private val NeonGreenDim = Color(0x6639FF14)

/**
 * Glassmorphism camp selector card with Apple Red accents.
 * Displays both camps as selectable cards with live capability preview.
 */
@Composable
fun CampSelectorScreen(
    onCampChanged: ((MusicCamp) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val manager = remember { FeatureAccessManager.getInstance(context) }
    val currentCamp by manager.currentCamp.collectAsState()
    val capabilities by manager.capabilities.collectAsState()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header
        Text(
            text = "Music Source",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Bold
        )

        Text(
            text = "Choose your music provider. YouTube Music is free with no subscription required.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = 20.sp
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Camp cards
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            CampCard(
                camp = MusicCamp.Icm,
                isSelected = currentCamp is MusicCamp.Icm,
                capabilities = capabilities,
                onClick = {
                    if (currentCamp !is MusicCamp.Icm) {
                        manager.selectCamp(MusicCamp.Icm)
                        onCampChanged?.invoke(MusicCamp.Icm)
                    }
                },
                modifier = Modifier.weight(1f)
            )

            CampCard(
                camp = MusicCamp.Youtube,
                isSelected = currentCamp is MusicCamp.Youtube,
                capabilities = capabilities,
                onClick = {
                    if (currentCamp !is MusicCamp.Youtube) {
                        manager.selectCamp(MusicCamp.Youtube)
                        onCampChanged?.invoke(MusicCamp.Youtube)
                    }
                },
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Active capabilities preview
        CapabilitiesPreview(
            capabilities = capabilities,
            camp = currentCamp
        )
    }
}

@Composable
private fun CampCard(
    camp: MusicCamp,
    isSelected: Boolean,
    capabilities: Capabilities,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scale by animateFloatAsState(
        targetValue = if (isSelected) 1.02f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "card_scale"
    )

    val borderColor by animateColorAsState(
        targetValue = when {
            isSelected && camp is MusicCamp.Youtube -> YouTubeRed
            isSelected -> AppleRed
            else -> GlassBorder
        },
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "border_color"
    )

    val bgAlpha = if (isSelected) 0.15f else 0.08f
    val accentColor = if (camp is MusicCamp.Youtube) YouTubeRed else AppleRed

    Box(
        modifier = modifier
            .scale(scale)
            .graphicsLayer {
                this.scaleX = scale
                this.scaleY = scale
            }
            .clip(RoundedCornerShape(20.dp))
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        GlassSurface.copy(alpha = bgAlpha + 0.05f),
                        GlassSurface.copy(alpha = bgAlpha)
                    )
                )
            )
            .border(
                width = if (isSelected) 2.dp else 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(20.dp)
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(16.dp)
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Camp icon
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(accentColor.copy(alpha = 0.12f))
                    .border(1.dp, accentColor.copy(alpha = 0.3f), RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (camp is MusicCamp.Youtube)
                        Icons.Outlined.MusicNote else Icons.Outlined.Waves,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(24.dp)
                )
            }

            // Camp name
            Text(
                text = camp.displayName,
                style = MaterialTheme.typography.titleMedium,
                color = if (isSelected) accentColor else MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            )

            // Description
            Text(
                text = camp.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                lineHeight = 16.sp
            )

            // Selection indicator
            AnimatedContent(
                targetState = isSelected,
                transitionSpec = {
                    scaleIn(animationSpec = spring(stiffness = Spring.StiffnessHigh)) +
                    fadeIn() togetherWith
                    scaleOut(animationSpec = spring(stiffness = Spring.StiffnessHigh)) +
                    fadeOut()
                },
                label = "selection_indicator"
            ) { selected ->
                if (selected) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.CheckCircle,
                            contentDescription = null,
                            tint = NeonGreen,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "ACTIVE",
                            style = MaterialTheme.typography.labelSmall,
                            color = NeonGreen,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                    }
                } else {
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }
    }
}

@Composable
private fun CapabilitiesPreview(
    capabilities: Capabilities,
    camp: MusicCamp
) {
    val isYoutube = camp is MusicCamp.Youtube
    val accentColor = if (isYoutube) YouTubeRed else AppleRed

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(GlassSurface.copy(alpha = 0.1f))
            .border(1.dp, GlassBorder, RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Available Features",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold
        )

        // Quality badge
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(accentColor.copy(alpha = 0.15f))
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(
                    text = capabilities.qualityLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = accentColor,
                    fontWeight = FontWeight.SemiBold
                )
            }

            if (isYoutube) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(NeonGreen.copy(alpha = 0.15f))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "FREE",
                        style = MaterialTheme.typography.labelMedium,
                        color = NeonGreen,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Feature grid
        val features = listOf(
            FeatureRowData(
                icon = Icons.Outlined.CloudDownload,
                label = "Downloads",
                enabled = capabilities[Feature.DOWNLOAD] == true
            ),
            FeatureRowData(
                icon = Icons.Outlined.HighQuality,
                label = "Hi-Res Audio",
                enabled = capabilities[Feature.HIGH_QUALITY] == true
            ),
            FeatureRowData(
                icon = Icons.Outlined.Radio,
                label = "Radio / My Wave",
                enabled = capabilities[Feature.PERSONAL_WAVE] == true
            ),
            FeatureRowData(
                icon = Icons.Outlined.Translate,
                label = "Lyrics",
                enabled = capabilities[Feature.LOSSLESS] == true || isYoutube
            )
        )

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            features.forEach { feature ->
                FeatureRow(
                    data = feature,
                    accentColor = accentColor
                )
            }
        }
    }
}

private data class FeatureRowData(
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val label: String,
    val enabled: Boolean
)

@Composable
private fun FeatureRow(
    data: FeatureRowData,
    accentColor: Color
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            imageVector = data.icon,
            contentDescription = null,
            tint = if (data.enabled) accentColor else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            modifier = Modifier.size(18.dp)
        )

        Text(
            text = data.label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (data.enabled)
                MaterialTheme.colorScheme.onSurface
            else
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            modifier = Modifier.weight(1f)
        )

        // Status dot
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(
                    if (data.enabled) NeonGreen else Color(0xFF555555)
                )
        )
    }
}

/**
 * Compact camp switcher for embedding in other screens (e.g. player, settings).
 */
@Composable
fun CampSwitcherChip(
    onCampChanged: ((MusicCamp) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val manager = remember { FeatureAccessManager.getInstance(context) }
    val currentCamp by manager.currentCamp.collectAsState()

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(24.dp))
            .background(GlassSurface.copy(alpha = 0.2f))
            .border(1.dp, GlassBorder, RoundedCornerShape(24.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        MusicCamp.ALL.forEach { camp ->
            val isSelected = currentCamp == camp
            val accentColor = if (camp is MusicCamp.Youtube) YouTubeRed else AppleRed

            val bgColor by animateColorAsState(
                targetValue = if (isSelected) accentColor.copy(alpha = 0.2f) else Color.Transparent,
                label = "chip_bg"
            )

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(bgColor)
                    .clickable {
                        if (!isSelected) {
                            manager.selectCamp(camp)
                            onCampChanged?.invoke(camp)
                        }
                    }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = camp.displayName,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isSelected) accentColor else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                )
            }
        }
    }
}

/**
 * Inline camp indicator for player / mini-player.
 */
@Composable
fun CampIndicator(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val manager = remember { FeatureAccessManager.getInstance(context) }
    val currentCamp by manager.currentCamp.collectAsState()
    val capabilities by manager.capabilities.collectAsState()

    val accentColor = if (currentCamp is MusicCamp.Youtube) YouTubeRed else AppleRed
    val campLabel = if (currentCamp is MusicCamp.Youtube) "YT" else "ICM"

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(accentColor.copy(alpha = 0.15f))
                .padding(horizontal = 6.dp, vertical = 2.dp)
        ) {
            Text(
                text = campLabel,
                style = MaterialTheme.typography.labelSmall,
                color = accentColor,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp
            )
        }

        Text(
            text = capabilities.qualityLabel,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
    }
}
