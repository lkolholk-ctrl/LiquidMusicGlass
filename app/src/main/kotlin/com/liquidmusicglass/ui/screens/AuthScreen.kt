package com.liquidmusicglass.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liquidmusicglass.api.icm.IcmAuthRepository
import com.liquidmusicglass.data.local.LocalAuthManager
import com.liquidmusicglass.engine.IcmKeyProvider
import kotlinx.coroutines.launch

private val AppleRed = Color(0xFFFC3C44)
private val TelegramBlue = Color(0xFF0088CC)

enum class AuthMode {
    WELCOME, LOGIN, REGISTER, FORGOT_PASSWORD
}

@Composable
fun AuthScreen(
    onAuthSuccess: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keyboardController = LocalSoftwareKeyboardController.current

    var mode by remember { mutableStateOf(AuthMode.WELCOME) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var successMessage by remember { mutableStateOf<String?>(null) }

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
            Spacer(modifier = Modifier.height(40.dp))

            // Back button if not welcome
            if (mode != AuthMode.WELCOME) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF1A1A1A))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { mode = AuthMode.WELCOME },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            null,
                            tint = Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }

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

            AnimatedContent(
                targetState = mode,
                transitionSpec = {
                    fadeIn(tween(200)) togetherWith fadeOut(tween(200))
                },
                label = "authMode"
            ) { currentMode ->
                when (currentMode) {
                    AuthMode.WELCOME -> WelcomeContent(
                        onLogin = { mode = AuthMode.LOGIN },
                        onRegister = { mode = AuthMode.REGISTER },
                        context = context
                    )
                    AuthMode.LOGIN -> LoginContent(
                        email = email,
                        onEmailChange = { email = it; errorMessage = null },
                        password = password,
                        onPasswordChange = { password = it; errorMessage = null },
                        isLoading = isLoading,
                        errorMessage = errorMessage,
                        onLogin = {
                            keyboardController?.hide()
                            scope.launch {
                                isLoading = true
                                errorMessage = null

                                if (email.isBlank() || password.isBlank()) {
                                    errorMessage = "Please fill in all fields"
                                    isLoading = false
                                    return@launch
                                }

                                val success = LocalAuthManager.login(email, password)
                                if (success) {
                                    // Issue ICM session token
                                    val apiKey = try {
                                        IcmKeyProvider.getApiKey()
                                    } catch (_: Throwable) { "" }.ifBlank {
                                        com.liquidmusicglass.BuildConfig.ICM_API_KEY
                                    }
                                    if (apiKey.isNotBlank()) {
                                        IcmAuthRepository.loginWithEmail(email, apiKey)
                                    }
                                    onAuthSuccess()
                                } else {
                                    errorMessage = "Invalid email or password"
                                }
                                isLoading = false
                            }
                        },
                        onForgotPassword = { mode = AuthMode.FORGOT_PASSWORD }
                    )
                    AuthMode.REGISTER -> RegisterContent(
                        email = email,
                        onEmailChange = { email = it; errorMessage = null },
                        password = password,
                        onPasswordChange = { password = it; errorMessage = null },
                        confirmPassword = confirmPassword,
                        onConfirmChange = { confirmPassword = it; errorMessage = null },
                        isLoading = isLoading,
                        errorMessage = errorMessage,
                        successMessage = successMessage,
                        onRegister = {
                            keyboardController?.hide()
                            scope.launch {
                                isLoading = true
                                errorMessage = null
                                successMessage = null

                                when {
                                    email.isBlank() || password.isBlank() -> {
                                        errorMessage = "Please fill in all fields"
                                    }
                                    password.length < 6 -> {
                                        errorMessage = "Password must be at least 6 characters"
                                    }
                                    password != confirmPassword -> {
                                        errorMessage = "Passwords do not match"
                                    }
                                    else -> {
                                        val success = LocalAuthManager.register(email, password)
                                        if (success) {
                                            successMessage = "Account created! Please verify your email. Check your inbox for a confirmation message."
                                            // Issue ICM session token
                                            val apiKey = try {
                                                IcmKeyProvider.getApiKey()
                                            } catch (_: Throwable) { "" }.ifBlank {
                                                com.liquidmusicglass.BuildConfig.ICM_API_KEY
                                            }
                                            if (apiKey.isNotBlank()) {
                                                IcmAuthRepository.loginWithEmail(email, apiKey)
                                            }
                                        } else {
                                            errorMessage = "An account with this email already exists"
                                        }
                                    }
                                }
                                isLoading = false
                            }
                        }
                    )
                    AuthMode.FORGOT_PASSWORD -> ForgotPasswordContent(
                        email = email,
                        onEmailChange = { email = it; errorMessage = null },
                        isLoading = isLoading,
                        errorMessage = errorMessage,
                        successMessage = successMessage,
                        onReset = {
                            keyboardController?.hide()
                            scope.launch {
                                isLoading = true
                                errorMessage = null
                                successMessage = null

                                if (email.isBlank()) {
                                    errorMessage = "Please enter your email"
                                    isLoading = false
                                    return@launch
                                }

                                // In a real app, this would send a reset email
                                // For now, show a message
                                successMessage = "If an account exists with this email, you will receive password reset instructions."
                                isLoading = false
                            }
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            Text(
                text = "By continuing, you agree to our Terms of Service",
                color = Color.White.copy(alpha = 0.3f),
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun WelcomeContent(
    onLogin: () -> Unit,
    onRegister: () -> Unit,
    context: android.content.Context
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(modifier = Modifier.height(40.dp))

        Text(
            text = "Welcome",
            color = Color.White.copy(alpha = 0.5f),
            fontSize = 15.sp,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(40.dp))

        // Login button
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(AppleRed)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { onLogin() },
            contentAlignment = Alignment.Center
        ) {
            Text(
                "Sign In",
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Register button
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF1A1A1A))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { onRegister() },
            contentAlignment = Alignment.Center
        ) {
            Text(
                "Create Account",
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Divider
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(1.dp)
                    .background(Color.White.copy(alpha = 0.1f))
            )
            Text(
                "  or  ",
                color = Color.White.copy(alpha = 0.3f),
                fontSize = 14.sp
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(1.dp)
                    .background(Color.White.copy(alpha = 0.1f))
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Telegram button
        val telegramAuthUrl = "https://liquid.glassfiles.ru/auth.html"
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(TelegramBlue)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    // Open Telegram Login Widget page in browser/Custom Tabs
                    val intent = android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse(telegramAuthUrl)
                    )
                    context.startActivity(intent)
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
    }
}

@Composable
private fun LoginContent(
    email: String,
    onEmailChange: (String) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
    isLoading: Boolean,
    errorMessage: String?,
    onLogin: () -> Unit,
    onForgotPassword: () -> Unit
) {
    val keyboardController = LocalSoftwareKeyboardController.current

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Sign In",
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold
        )

        Spacer(modifier = Modifier.height(24.dp))

        AuthTextField(
            value = email,
            onValueChange = onEmailChange,
            placeholder = "Email",
            leadingIcon = Icons.Rounded.Email,
            keyboardType = KeyboardType.Email
        )

        Spacer(modifier = Modifier.height(12.dp))

        AuthTextField(
            value = password,
            onValueChange = onPasswordChange,
            placeholder = "Password",
            leadingIcon = Icons.Rounded.Lock,
            keyboardType = KeyboardType.Password,
            isPassword = true,
            imeAction = ImeAction.Done,
            onImeAction = { keyboardController?.hide(); onLogin() }
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Forgot password
        Text(
            text = "Forgot password?",
            color = AppleRed,
            fontSize = 13.sp,
            modifier = Modifier
                .align(Alignment.End)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { onForgotPassword() }
        )

        Spacer(modifier = Modifier.height(24.dp))

        AuthButton(
            text = "Sign In",
            isLoading = isLoading,
            onClick = onLogin
        )

        if (errorMessage != null) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = errorMessage,
                color = AppleRed,
                fontSize = 14.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun RegisterContent(
    email: String,
    onEmailChange: (String) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
    confirmPassword: String,
    onConfirmChange: (String) -> Unit,
    isLoading: Boolean,
    errorMessage: String?,
    successMessage: String?,
    onRegister: () -> Unit
) {
    val keyboardController = LocalSoftwareKeyboardController.current

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Create Account",
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold
        )

        Spacer(modifier = Modifier.height(24.dp))

        AuthTextField(
            value = email,
            onValueChange = onEmailChange,
            placeholder = "Email",
            leadingIcon = Icons.Rounded.Email,
            keyboardType = KeyboardType.Email
        )

        Spacer(modifier = Modifier.height(12.dp))

        AuthTextField(
            value = password,
            onValueChange = onPasswordChange,
            placeholder = "Password",
            leadingIcon = Icons.Rounded.Lock,
            keyboardType = KeyboardType.Password,
            isPassword = true
        )

        Spacer(modifier = Modifier.height(12.dp))

        AuthTextField(
            value = confirmPassword,
            onValueChange = onConfirmChange,
            placeholder = "Confirm Password",
            leadingIcon = Icons.Rounded.Lock,
            keyboardType = KeyboardType.Password,
            isPassword = true,
            imeAction = ImeAction.Done,
            onImeAction = { keyboardController?.hide(); onRegister() }
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Verification notice
        Text(
            text = "You will need to verify your email. A confirmation message will be sent from our service.",
            color = Color.White.copy(alpha = 0.5f),
            fontSize = 12.sp,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(24.dp))

        AuthButton(
            text = "Create Account",
            isLoading = isLoading,
            onClick = onRegister
        )

        if (errorMessage != null) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = errorMessage,
                color = AppleRed,
                fontSize = 14.sp,
                textAlign = TextAlign.Center
            )
        }

        if (successMessage != null) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = successMessage,
                color = Color(0xFF34C759),
                fontSize = 14.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun ForgotPasswordContent(
    email: String,
    onEmailChange: (String) -> Unit,
    isLoading: Boolean,
    errorMessage: String?,
    successMessage: String?,
    onReset: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Reset Password",
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Enter your email and we'll send you reset instructions",
            color = Color.White.copy(alpha = 0.5f),
            fontSize = 14.sp,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(24.dp))

        AuthTextField(
            value = email,
            onValueChange = onEmailChange,
            placeholder = "Email",
            leadingIcon = Icons.Rounded.Email,
            keyboardType = KeyboardType.Email,
            imeAction = ImeAction.Done,
            onImeAction = { onReset() }
        )

        Spacer(modifier = Modifier.height(24.dp))

        AuthButton(
            text = "Send Reset Link",
            isLoading = isLoading,
            onClick = onReset
        )

        if (errorMessage != null) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = errorMessage,
                color = AppleRed,
                fontSize = 14.sp,
                textAlign = TextAlign.Center
            )
        }

        if (successMessage != null) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = successMessage,
                color = Color(0xFF34C759),
                fontSize = 14.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun AuthTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    leadingIcon: androidx.compose.ui.graphics.vector.ImageVector,
    keyboardType: KeyboardType,
    isPassword: Boolean = false,
    imeAction: ImeAction = ImeAction.Next,
    onImeAction: () -> Unit = {}
) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text(placeholder, color = Color.White.copy(alpha = 0.3f)) },
        leadingIcon = {
            Icon(
                leadingIcon,
                null,
                tint = Color.White.copy(alpha = 0.5f)
            )
        },
        visualTransformation = if (isPassword) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = keyboardType,
            imeAction = imeAction
        ),
        keyboardActions = KeyboardActions(
            onDone = { onImeAction() },
            onNext = { onImeAction() }
        ),
        singleLine = true,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color(0xFF1A1A1A),
            unfocusedContainerColor = Color(0xFF1A1A1A),
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            cursorColor = AppleRed
        ),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun AuthButton(
    text: String,
    isLoading: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(AppleRed)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = !isLoading
            ) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                color = Color.White,
                modifier = Modifier.size(24.dp),
                strokeWidth = 2.dp
            )
        } else {
            Text(
                text = text,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp
            )
        }
    }
}
