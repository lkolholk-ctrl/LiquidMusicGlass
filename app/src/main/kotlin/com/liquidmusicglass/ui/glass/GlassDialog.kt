package com.liquidmusicglass.ui.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liquidmusicglass.ui.theme.LiquidMotion
import com.liquidmusicglass.ui.theme.LiquidTheme

/**
 * Reusable custom dialog that matches the "Premium Required" dialog design language.
 * All dialogs across the app should use this for visual consistency.
 *
 * @param visible Whether the dialog is shown
 * @param onDismiss Called when the user taps the backdrop to dismiss
 * @param icon Optional leading icon (e.g. download, delete, warning)
 * @param iconTint Color for the icon
 * @param title Dialog title
 * @param message Dialog body text
 * @param primaryButton Primary action (right side, accent color)
 * @param secondaryButton Secondary action (left side, muted)
 * @param dismissible Whether tapping the backdrop dismisses the dialog
 */
@Composable
fun GlassDialog(
    visible: Boolean,
    onDismiss: () -> Unit,
    icon: ImageVector? = null,
    iconTint: Color = Color(0xFFFC3C44),
    title: String,
    message: String,
    primaryButton: GlassDialogButton,
    secondaryButton: GlassDialogButton? = null,
    dismissible: Boolean = true
) {
    if (!visible) return

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = dismissible
            ) { onDismiss() },
        contentAlignment = Alignment.Center
    ) {
        val isDark = LiquidTheme.colors.isDark
        val dialogBg = if (isDark) Color(0xFF1C1C1E) else Color.White
        val dialogBorder = if (isDark) Color.White.copy(alpha = 0.12f) else Color.Black.copy(alpha = 0.10f)

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(dialogBg)
                .border(1.dp, dialogBorder, RoundedCornerShape(28.dp))
                .padding(28.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { }, // prevent dismiss when tapping inside
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // Icon
                if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = iconTint,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                }

                // Title
                Text(
                    text = title,
                    color = LiquidTheme.colors.textPrimary,
                    fontSize = 20.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(12.dp))

                // Message
                Text(
                    text = message,
                    color = LiquidTheme.colors.textSecondary,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    lineHeight = 20.sp
                )
                Spacer(modifier = Modifier.height(24.dp))

                // Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Secondary (left)
                    secondaryButton?.let { btn ->
                        val secondaryBtnBg = if (isDark) Color.White.copy(alpha = 0.08f) else Color(0xFFF2F2F7)
                        val secondaryBtnBorder = if (isDark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.05f)

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(46.dp)
                                .clip(RoundedCornerShape(23.dp))
                                .background(secondaryBtnBg)
                                .border(1.dp, secondaryBtnBorder, RoundedCornerShape(23.dp))
                                .liquidClickable(pressedScale = LiquidMotion.PressButton) { btn.onClick() },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = btn.text,
                                color = LiquidTheme.colors.textPrimary,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                            )
                        }
                    }

                    // Primary (right)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp)
                            .clip(RoundedCornerShape(23.dp))
                            .background(primaryButton.backgroundColor)
                            .liquidClickable(pressedScale = LiquidMotion.PressButton) { primaryButton.onClick() },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = primaryButton.text,
                            color = primaryButton.textColor,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}

data class GlassDialogButton(
    val text: String,
    val onClick: () -> Unit,
    val backgroundColor: Color = Color(0xFFFC3C44),
    val textColor: Color = Color.White
)
