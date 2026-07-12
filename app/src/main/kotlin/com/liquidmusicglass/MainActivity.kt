package com.liquidmusicglass

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.border
import com.liquidmusicglass.api.icm.IcmAuthRepository
import com.liquidmusicglass.api.icm.IcmRepository
import com.liquidmusicglass.engine.IcmKeyProvider
import com.liquidmusicglass.engine.PlayerController
import com.liquidmusicglass.logging.CrashHandler
import com.liquidmusicglass.ui.AppRoot
import com.liquidmusicglass.ui.crash.CrashActivity
import com.liquidmusicglass.ui.theme.LiquidMusicGlassTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// TODO: ВРЕМЕННО для отладки бага «лирика не ползёт с первого тыка». Вернуть true
// и убрать lyrdbg-логи в LyricsScreen после диагностики.
private const val PROTECTION_ENABLED = false

class MainActivity : ComponentActivity() {

    private val authScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // POST_NOTIFICATIONS (Android 13+) — рантайм-разрешение. Регистрируем лаунчер
    // на этапе конструирования Activity (иначе registerForActivityResult падает).
    // Результат нам не важен (медиа-уведомление появится при воспроизведении).
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // JUCE инициализируется через Activity-контекст (см. JuceContextHolder).
        com.liquidmusicglass.engine.automix.JuceContextHolder.set(this)

        if (CrashHandler.hasCrashLog(this)) {
            startActivity(Intent(this, CrashActivity::class.java))
            finish()
            return
        }

        // Разрешение на уведомления запрашиваем САМИ на первом запуске — чтобы юзер
        // не выдавал его вручную через настройки (полевой фидбек: «разрешения не
        // запрашивает»). Без POST_NOTIFICATIONS медиа-уведомление плеера не видно.
        maybeRequestNotificationPermission()

        enableEdgeToEdge()

        // Детектор просадки FPS → деградация тяжёлых эффектов (аура/лирика/мудкарточки)
        // на слабом GPU, чтобы RenderThread успевал и не было ANR-плашки.
        com.liquidmusicglass.ui.PerfMonitor.start()

        // Сетевой колбэк переехал на уровень App (NetworkVitality): живёт весь
        // срок процесса, а не только пока открыта Activity.

        // Инициализация ICM API — в ФОНЕ (главная причина стартового ANR была тут).
        // IcmKeyProvider.getApiKey() грузит нативную .so (libicmkey) и гоняет
        // анти-тампер проверку APK — это дорого и раньше висело на главном потоке
        // ДО setContent. Ключ нужен только сетевому слою, не первому кадру, поэтому
        // уносим весь блок в IO. Домашний экран грузит данные с ретраями, короткое
        // окно «репозиторий ещё не готов» переживается.
        authScope.launch {
            val apiKey = try {
                IcmKeyProvider.getApiKey(this@MainActivity)
            } catch (_: Throwable) { "" }.ifBlank { BuildConfig.ICM_API_KEY }
            if (apiKey.isNotBlank()) {
                IcmRepository.init(apiKey, IcmAuthRepository.partnerUserId.value)
                // Restore session token if we have one (survives app updates)
                IcmAuthRepository.getSessionToken()?.let { token ->
                    IcmRepository.setSessionToken(token)
                }
                // Стартовые сетевые задачи — опциональны и ограничены по времени:
                // приложение показывается и работает без них, не вися на сети.
                if (IcmAuthRepository.isLoggedIn.value) {
                    kotlinx.coroutines.withTimeoutOrNull(5_000) {
                        IcmAuthRepository.refreshTokenIfNeeded(apiKey)
                        IcmAuthRepository.fetchUserData()
                    }
                }
            }
        }

    // Handle Telegram auth redirect
    handleTelegramAuth(intent)

    // Handle notification tap (open large player)
    handleNotificationTap(intent)

        val isSecurityCompromised = mutableStateOf(false)
        val compromiseReason = mutableStateOf("")

        // Security checks: Root, Emulator, Frida, Debugger, Xposed environment verification
        // ВРЕМЕННО отключено флагом PROTECTION_ENABLED (см. TODO у объявления флага).
        if (PROTECTION_ENABLED) authScope.launch {
            val isRooted = com.liquidmusicglass.engine.SecurityUtils.isDeviceRooted()
            val isEmulator = com.liquidmusicglass.engine.SecurityUtils.isEmulator()

            val threats = try { com.liquidmusicglass.security.NativeSecurity.nativeSecurityCheck() } catch (_: Throwable) {
                -1
            }

            val hooksSafe = try { com.liquidmusicglass.security.NativeSecurity.nativeCheckHooks() } catch (_: Throwable) {
                false
            }

            var signatureValid = true
            var sigHashGot = 0u
            try {
                val pm = packageManager
                val info = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    pm.getPackageInfo(packageName, android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES)
                } else {
                    @Suppress("DEPRECATION")
                    pm.getPackageInfo(packageName, android.content.pm.PackageManager.GET_SIGNATURES)
                }
                val signatures = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    info.signingInfo?.apkContentsSigners
                } else {
                    @Suppress("DEPRECATION")
                    info.signatures
                }
                if (signatures != null && signatures.isNotEmpty()) {
                    val sigBytes = signatures[0].toByteArray()
                    var hash = 0
                    for (b in sigBytes) {
                        hash = hash * 31 + (b.toInt() and 0xFF)
                    }
                    sigHashGot = hash.toUInt()
                    signatureValid = com.liquidmusicglass.security.NativeSecurity.nativeVerifySignature(sigBytes)
                }
            } catch (_: Throwable) {
                signatureValid = false
            }

            val apkIntegrity = try {
                com.liquidmusicglass.security.NativeSecurity.nativeCheckIntegrity(packageCodePath ?: "")
            } catch (_: Throwable) {
                false
            }

            val isSafe = hooksSafe && (threats == 0) && signatureValid && apkIntegrity

            if (isRooted || isEmulator || !isSafe) {
                val reasons = mutableListOf<String>()
                if (isRooted) reasons.add("Root Check Triggered")
                if (isEmulator) reasons.add("Emulator Check Triggered")
                if (!hooksSafe) reasons.add("Hooks Verification Failed")
                if (threats != 0) {
                    val list = mutableListOf<String>()
                    if ((threats and 0x01) != 0) list.add("Debugger/Ptrace")
                    if ((threats and 0x02) != 0) list.add("Frida")
                    if ((threats and 0x04) != 0) list.add("Xposed")
                    if ((threats and 0x08) != 0) list.add("Emulator")
                    if ((threats and 0x10) != 0) list.add("SU Binary")
                    if ((threats and 0x20) != 0) list.add("Magisk/KSU")
                    reasons.add("Threats (Mask=${threats}): ${list.joinToString(", ")}")
                }
                if (!signatureValid) reasons.add("Signature Failed (Hash=${sigHashGot})")
                if (!apkIntegrity) reasons.add("APK Integrity Verification Failed")
                
                withContext(Dispatchers.Main) {
                    compromiseReason.value = reasons.joinToString("\n")
                    isSecurityCompromised.value = true
                }
                android.util.Log.e("Security", "Security violation: Root=$isRooted, Emulator=$isEmulator, Safe=$isSafe")
            }
        }

        setContent {
            val themeMode by PlayerController.themeMode.collectAsState()
            val highContrast by com.liquidmusicglass.engine.PlayerSettings.increaseContrast.collectAsState()
            LiquidMusicGlassTheme(themeMode = themeMode, highContrast = highContrast) {
                val compromised by remember { isSecurityCompromised }
                val reasons by remember { compromiseReason }
                if (compromised) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(
                                        Color(0xFF0D0B0F),
                                        Color(0xFF15121B),
                                        Color(0xFF09070A)
                                    )
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth(0.9f)
                                .clip(RoundedCornerShape(24.dp))
                                .background(Color(0x1F2C243B))
                                .border(1.dp, Brush.verticalGradient(
                                    colors = listOf(
                                        Color(0xFFFF3B30),
                                        Color(0x33FF9500)
                                    )
                                ), RoundedCornerShape(24.dp))
                                .padding(28.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            // Neon Warning Canvas Shield
                            Box(
                                modifier = Modifier
                                    .size(76.dp)
                                    .clip(androidx.compose.foundation.shape.CircleShape)
                                    .background(Color(0x2BFF3B30)),
                                contentAlignment = Alignment.Center
                            ) {
                                androidx.compose.foundation.Canvas(modifier = Modifier.size(38.dp)) {
                                    val path = androidx.compose.ui.graphics.Path().apply {
                                        moveTo(size.width / 2f, 2f)
                                        lineTo(size.width - 2f, size.height - 2f)
                                        lineTo(2f, size.height - 2f)
                                        close()
                                    }
                                    drawPath(
                                        path = path,
                                        color = Color(0xFFFF453A),
                                        style = androidx.compose.ui.graphics.drawscope.Stroke(
                                            width = 3.dp.toPx(),
                                            join = androidx.compose.ui.graphics.StrokeJoin.Round
                                        )
                                    )
                                    drawRect(
                                        color = Color(0xFFFF453A),
                                        topLeft = androidx.compose.ui.geometry.Offset(size.width / 2f - 2.dp.toPx(), size.height * 0.35f),
                                        size = androidx.compose.ui.geometry.Size(4.dp.toPx(), size.height * 0.3f)
                                    )
                                    drawCircle(
                                        color = Color(0xFFFF453A),
                                        radius = 2.5.dp.toPx(),
                                        center = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height * 0.78f)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(20.dp))

                            Text(
                                text = "SECURITY INTEGRITY BLOCK",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFFF453A),
                                letterSpacing = 1.5.sp,
                                textAlign = TextAlign.Center
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            Text(
                                text = "To protect platform resources, API keys, user accounts, and encrypted music streaming endpoints, this application is restricted from running in debugged, rooted, or unsafe environments.",
                                fontSize = 13.sp,
                                color = Color(0xFFD1D1D6),
                                textAlign = TextAlign.Center,
                                lineHeight = 18.sp
                            )

                            Spacer(modifier = Modifier.height(20.dp))

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color(0x2B000000))
                                    .border(0.5.dp, Color(0x22FFFFFF), RoundedCornerShape(12.dp))
                                    .padding(16.dp)
                            ) {
                                Column {
                                    Text(
                                        text = "Integrity violations detected:",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color(0xFFFF9500),
                                        modifier = Modifier.padding(bottom = 6.dp)
                                    )
                                    reasons.split("\n").forEach { reason ->
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.padding(vertical = 3.dp)
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(6.dp)
                                                    .clip(androidx.compose.foundation.shape.CircleShape)
                                                    .background(Color(0xFFFF453A))
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = reason,
                                                fontSize = 12.sp,
                                                color = Color(0xFFE5E5EA),
                                                fontWeight = FontWeight.Normal
                                            )
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(24.dp))

                            Button(
                                onClick = { this@MainActivity.finishAffinity() },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFFFF3B30),
                                    contentColor = Color.White
                                ),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(46.dp)
                            ) {
                                Text(
                                    text = "CLOSE APPLICATION",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    letterSpacing = 0.5.sp
                                )
                            }
                        }
                    }
                } else {
                    AppRoot()
                }
            }
        }
    }

    // Возврат из фона при живом процессе (музыка в foreground): onStart после
    // onStop вызывается БЕЗ onCreate. GPU-контекст AGSL-шейдеров потерян — даём
    // эффектам сигнал пере-прогрева (пересоздать дым/AGSL) и перезапускаем
    // прогрев стекла. На самый первый onStart (после onCreate) НЕ реагируем.
    private var wasStopped = false

    override fun onStart() {
        super.onStart()
        if (wasStopped) {
            wasStopped = false
            com.liquidmusicglass.ui.EffectsLifecycle.onReturnedToForeground()
            com.liquidmusicglass.ui.PerfMonitor.restart()
        }
    }

    override fun onStop() {
        super.onStop()
        wasStopped = true
    }

    // Системные оверлеи/переходы (пикер файла/фото, экран «о приложении», шторка,
    // сворачивание) забирают фокус ДО onStop. Замораживаем тяжёлый AGSL-дым на это
    // время, чтобы наш рендер не конкурировал с аудио-колбэком JUCE за GPU/CPU и
    // не давал цикличных заеданий звука в момент перехода.
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        com.liquidmusicglass.ui.EffectsLifecycle.hasWindowFocus = hasFocus
    }

    override fun onDestroy() {
        super.onDestroy()
        com.liquidmusicglass.engine.automix.JuceContextHolder.clear(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleTelegramAuth(intent)
        handleNotificationTap(intent)
    }

    /**
     * Запрашивает POST_NOTIFICATIONS ОДИН раз на первом запуске (Android 13+).
     * До 13 разрешение не рантайм — ничего не делаем. Если уже выдано — не дёргаем.
     * Флаг в prefs гарантирует ровно один показ диалога: без него холодный старт
     * либо спамил бы диалогом, либо впустую дёргал систему на «навсегда отклонено».
     */
    private fun maybeRequestNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
            == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return
        val prefs = getSharedPreferences("permissions", android.content.Context.MODE_PRIVATE)
        if (prefs.getBoolean("post_notif_requested", false)) return
        prefs.edit().putBoolean("post_notif_requested", true).apply()
        try {
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        } catch (_: Throwable) {
            // Отдельные прошивки могут кинуть на launch — не роняем старт.
        }
    }

    private fun handleNotificationTap(intent: Intent?) {
        if (intent?.getStringExtra("NAVIGATE_TO") == "LARGE_PLAYER") {
            com.liquidmusicglass.engine.PlayerController.audioServiceRef?.let {
                // Trigger player expansion via shared state
                com.liquidmusicglass.engine.NotificationRouter.emitOpenLargePlayer()
            }
        }
    }

    private fun handleTelegramAuth(intent: Intent?) {
        val data = intent?.data ?: return
        
        // Handle ICM Telegram link redirect. Принимаем ЛЮБОЙ путь под host=oauth
        // (…/icm и голый liquidmusicglass://oauth): ICM whitelist сверяет
        // redirect_uri по scheme://netloc, поэтому итоговый путь может отличаться.
        if (data.scheme == "liquidmusicglass" && data.host == "oauth") {
            val linkedParam = data.getQueryParameter("linked")
            val linked = linkedParam == "1" || linkedParam.equals("true", ignoreCase = true)
            val icmUserId = data.getQueryParameter("icm_user_id")
            val state = data.getQueryParameter("state")
            val error = data.getQueryParameter("error")

            // CSRF check: returned state must match the one we sent
            val prefs = getSharedPreferences("icm_auth", android.content.Context.MODE_PRIVATE)
            val expectedState = prefs.getString("oauth_state", null)
            if (expectedState == null || state != expectedState) {
                android.widget.Toast.makeText(
                    this,
                    "Auth failed: invalid state",
                    android.widget.Toast.LENGTH_LONG
                ).show()
                return
            }
            // State is single-use — clear it to prevent replay
            prefs.edit().remove("oauth_state").apply()

            if (error != null) {
                android.widget.Toast.makeText(
                    this,
                    "Auth error: $error",
                    android.widget.Toast.LENGTH_LONG
                ).show()
                return
            }

            if (!linked || icmUserId == null) {
                android.widget.Toast.makeText(
                    this,
                    "Auth failed: not linked",
                    android.widget.Toast.LENGTH_LONG
                ).show()
                return
            }

            // Store Telegram auth data — need to issue session token separately
            IcmAuthRepository.setTelegramAuth(
                icmUserId = icmUserId,
                state = state
            )

            // Try to issue a Bearer session token so /me/* endpoints work
            // without S2S API key. Best-effort — wave already works via
            // X-Partner-Key + X-Partner-User-Id even if this fails.
            val apiKey = try {
                IcmKeyProvider.getApiKey(this)
            } catch (_: Throwable) { "" }
                .ifBlank { BuildConfig.ICM_API_KEY }
            if (apiKey.isNotBlank() && apiKey.startsWith("pk_")) {
                authScope.launch {
                    val loginResult = runCatching {
                        IcmAuthRepository.issueSessionAfterTelegramAuth(apiKey)
                    }
                    if (loginResult.isSuccess) {
                        // Fetch profile & preferences, then refresh UI on main thread
                        val dataResult = IcmAuthRepository.fetchUserData()
                        if (dataResult.isSuccess) {
                            withContext(Dispatchers.Main) {
                                // Trigger UI refresh — StateFlows will update automatically
                                // but if profile screen is already open, force recreate
                                recreate()
                            }
                        }
                    }
                }
            }

            android.widget.Toast.makeText(
                this,
                "Telegram auth successful",
                android.widget.Toast.LENGTH_SHORT
            ).show()
            return
        }
    }
}
