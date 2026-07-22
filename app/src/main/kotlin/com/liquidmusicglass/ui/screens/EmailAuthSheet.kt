package com.liquidmusicglass.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liquidmusicglass.api.icm.IcmAuthRepository
import com.liquidmusicglass.api.icm.icmUserMessage
import com.liquidmusicglass.ui.theme.LiquidTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val TelegramBlue = Color(0xFF0088CC)

/**
 * Email-вход по OTP (passwordless): email → «Send code» → 6-значный код.
 * Вход = регистрация; ICM сам заводит аккаунт для нового email.
 * Состояния: ввод email → ввод кода. Ошибки — человеческие (icmUserMessage).
 */
@Composable
fun EmailAuthSheet(
    onSuccess: () -> Unit,
    onClose: () -> Unit,
) {
    val lc = LiquidTheme.colors
    val scope = rememberCoroutineScope()

    var stage by remember { mutableStateOf(0) }        // 0 = email, 1 = otp
    var email by remember { mutableStateOf("") }
    var otp by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var session by remember { mutableStateOf<IcmAuthRepository.EmailOtpSession?>(null) }
    var resendIn by remember { mutableStateOf(0) }     // секунды до повторной отправки (rate limit 1/мин)

    LaunchedEffect(resendIn) {
        if (resendIn > 0) { delay(1000); resendIn -= 1 }
    }

    val cardBg = if (lc.isDark) Color(0xFF1C1C1E) else Color(0xFFF2F2F7)
    val fieldColors = TextFieldDefaults.colors(
        focusedContainerColor = cardBg,
        unfocusedContainerColor = cardBg,
        focusedIndicatorColor = Color.Transparent,
        unfocusedIndicatorColor = Color.Transparent,
        focusedTextColor = lc.textPrimary,
        unfocusedTextColor = lc.textPrimary,
        cursorColor = TelegramBlue,
    )

    fun sendCode() {
        error = null; notice = null; busy = true
        scope.launch {
            val r = IcmAuthRepository.requestEmailOtp(email)
            busy = false
            r.onSuccess {
                session = it; stage = 1; otp = ""; resendIn = 60
            }.onFailure { error = icmUserMessage(it) }
        }
    }

    fun verify(code: String) {
        val s = session ?: return
        error = null; busy = true
        scope.launch {
            val r = IcmAuthRepository.verifyEmailOtp(s, code)
            busy = false
            r.onSuccess { passwordIssued ->
                if (passwordIssued) {
                    notice = "Account created — a password was sent to your email."
                    delay(1200)
                }
                onSuccess()
            }.onFailure { otp = ""; error = icmUserMessage(it) }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = if (stage == 0) "Continue with Email" else "Enter the code",
            color = lc.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = if (stage == 0) "We'll email you a 6-digit code to sign in."
                   else "Sent to ${session?.email ?: email}",
            color = lc.textSecondary, fontSize = 13.sp, textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(18.dp))

        if (stage == 0) {
            TextField(
                value = email,
                onValueChange = { email = it.trim(); error = null },
                singleLine = true,
                placeholder = { Text("you@example.com", color = lc.textTertiary) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                colors = fieldColors,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(14.dp))
            PrimaryButton(
                label = if (busy) "Sending…" else "Send code",
                enabled = !busy && email.contains("@"),
                onClick = { sendCode() }
            )
        } else {
            TextField(
                value = otp,
                onValueChange = { v ->
                    otp = v.filter { it.isDigit() }.take(6)
                    error = null
                    if (otp.length == 6) verify(otp)      // авто-сабмит на 6-й цифре
                },
                singleLine = true,
                enabled = !busy,
                placeholder = { Text("••••••", color = lc.textTertiary) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                colors = fieldColors,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(14.dp))
            PrimaryButton(
                label = if (busy) "Checking…" else "Verify",
                enabled = !busy && otp.length == 6,
                onClick = { verify(otp) }
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = if (resendIn > 0) "Resend code in ${resendIn}s" else "Resend code",
                color = if (resendIn > 0) lc.textTertiary else TelegramBlue,
                fontSize = 13.sp,
                modifier = Modifier
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        enabled = resendIn == 0 && !busy
                    ) { sendCode() }
                    .padding(6.dp)
            )
        }

        notice?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = lc.textSecondary, fontSize = 13.sp, textAlign = TextAlign.Center)
        }
        error?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = Color(0xFFFC3C44), fontSize = 13.sp, textAlign = TextAlign.Center)
        }

        Spacer(Modifier.height(8.dp))
        Text(
            text = "Cancel",
            color = lc.textTertiary, fontSize = 13.sp,
            modifier = Modifier
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { onClose() }
                .padding(8.dp)
        )
    }
}

@Composable
private fun PrimaryButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp)
            .background(
                if (enabled) TelegramBlue else TelegramBlue.copy(alpha = 0.4f),
                RoundedCornerShape(percent = 50)
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled
            ) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
    }
}
