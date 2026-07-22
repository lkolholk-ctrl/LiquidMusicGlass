package com.liquidmusicglass.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liquidmusicglass.api.icm.IcmAuthRepository
import com.liquidmusicglass.api.icm.icmUserMessage
import com.liquidmusicglass.ui.theme.LiquidTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val TelegramBlue = Color(0xFF0088CC)
private val AppleRed = Color(0xFFFC3C44)

/**
 * Управление паролем ICM-аккаунта (для вошедшего через email).
 * «Change» — текущий+новый (мин 8); «Reset» — временный пароль на почту
 * (кулдаун 60с у ICM держим и на клиенте).
 */
@Composable
fun IcmPasswordSheet(onClose: () -> Unit) {
    val lc = LiquidTheme.colors
    val scope = rememberCoroutineScope()

    var current by remember { mutableStateOf("") }
    var new1 by remember { mutableStateOf("") }
    var new2 by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var resetCooldown by remember { mutableStateOf(0) }

    LaunchedEffect(resetCooldown) {
        if (resetCooldown > 0) { delay(1000); resetCooldown -= 1 }
    }

    val cardBg = if (lc.isDark) Color(0xFF1C1C1E) else Color(0xFFF2F2F7)
    val fieldColors = TextFieldDefaults.colors(
        focusedContainerColor = cardBg, unfocusedContainerColor = cardBg,
        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
        focusedTextColor = lc.textPrimary, unfocusedTextColor = lc.textPrimary,
        cursorColor = TelegramBlue,
    )

    @Composable
    fun field(value: String, onChange: (String) -> Unit, hint: String) {
        TextField(
            value = value,
            onValueChange = { onChange(it); error = null },
            singleLine = true, enabled = !busy,
            placeholder = { Text(hint, color = lc.textTertiary) },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            colors = fieldColors, shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth()
        )
    }

    fun change() {
        error = null; notice = null
        if (new1.length < 8) { error = "New password must be at least 8 characters."; return }
        if (new1 != new2) { error = "Passwords don't match."; return }
        if (new1 == current) { error = "New password must be different."; return }
        busy = true
        scope.launch {
            val r = IcmAuthRepository.changeIcmPassword(current, new1)
            busy = false
            r.onSuccess {
                notice = "Password changed."; current = ""; new1 = ""; new2 = ""
                delay(1200); onClose()
            }.onFailure { error = icmUserMessage(it) }
        }
    }

    fun reset() {
        error = null; notice = null; busy = true
        scope.launch {
            val r = IcmAuthRepository.resetIcmPassword()
            busy = false
            r.onSuccess {
                notice = "A temporary password was sent to your email."
                resetCooldown = 60
            }.onFailure { error = icmUserMessage(it); resetCooldown = 60 }
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("ICM Password", color = lc.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
            "Sign-in uses an email code — this manages your ICM account password.",
            color = lc.textSecondary, fontSize = 12.sp, textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(18.dp))

        field(current, { current = it }, "Current password")
        Spacer(Modifier.height(10.dp))
        field(new1, { new1 = it }, "New password (min 8)")
        Spacer(Modifier.height(10.dp))
        field(new2, { new2 = it }, "Confirm new password")
        Spacer(Modifier.height(16.dp))

        Box(
            modifier = Modifier.fillMaxWidth().height(50.dp)
                .background(
                    if (!busy && current.isNotEmpty() && new1.isNotEmpty()) TelegramBlue
                    else TelegramBlue.copy(alpha = 0.4f),
                    RoundedCornerShape(percent = 50)
                )
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    enabled = !busy && current.isNotEmpty() && new1.isNotEmpty()
                ) { change() },
            contentAlignment = Alignment.Center
        ) {
            Text(if (busy) "Working…" else "Change password", color = Color.White,
                fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        }

        Spacer(Modifier.height(14.dp))
        Text(
            text = if (resetCooldown > 0) "Reset available in ${resetCooldown}s" else "Forgot password? Reset via email",
            color = if (resetCooldown > 0) lc.textTertiary else AppleRed, fontSize = 13.sp,
            modifier = Modifier
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    enabled = resetCooldown == 0 && !busy
                ) { reset() }
                .padding(6.dp)
        )

        notice?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = lc.textSecondary, fontSize = 13.sp, textAlign = TextAlign.Center)
        }
        error?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = AppleRed, fontSize = 13.sp, textAlign = TextAlign.Center)
        }
    }
}
