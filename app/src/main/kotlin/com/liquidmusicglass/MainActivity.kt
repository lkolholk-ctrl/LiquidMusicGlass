package com.liquidmusicglass

import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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

class MainActivity : ComponentActivity() {

    private val authScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var isNetworkCallbackRegistered = false

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        private var wasOffline = true

        override fun onAvailable(network: Network) {
            if (wasOffline && IcmAuthRepository.isLoggedIn.value) {
                wasOffline = false
                authScope.launch {
                    IcmAuthRepository.fetchUserData()
                }
            }
        }

        override fun onLost(network: Network) {
            wasOffline = true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (CrashHandler.hasCrashLog(this)) {
            startActivity(Intent(this, CrashActivity::class.java))
            finish()
            return
        }

        enableEdgeToEdge()

        // Register network callback to auto-refresh profile when coming back online
        val connectivityManager = getSystemService(ConnectivityManager::class.java)
        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager?.registerNetworkCallback(networkRequest, networkCallback)
        isNetworkCallbackRegistered = true

        // Initialize ICM API with native key (or BuildConfig fallback)
        val apiKey = try {
            IcmKeyProvider.getApiKey(this)
        } catch (_: Throwable) { "" }.ifBlank { BuildConfig.ICM_API_KEY }
        if (apiKey.isNotBlank()) {
            IcmRepository.init(apiKey, IcmAuthRepository.partnerUserId.value)
            // Restore session token if we have one (survives app updates)
            IcmAuthRepository.getSessionToken()?.let { token ->
                IcmRepository.setSessionToken(token)
            }
        }

    // Handle Telegram auth redirect
    handleTelegramAuth(intent)

    // Handle notification tap (open large player)
    handleNotificationTap(intent)

    // Refresh profile on app start if user is logged in
        if (IcmAuthRepository.isLoggedIn.value) {
            authScope.launch {
                val apiKey = try {
                    IcmKeyProvider.getApiKey(this@MainActivity)
                } catch (_: Throwable) { "" }.ifBlank { BuildConfig.ICM_API_KEY }
                
                if (apiKey.isNotBlank()) {
                    IcmAuthRepository.refreshTokenIfNeeded(apiKey)
                }
                // Always refresh profile data on startup
                IcmAuthRepository.fetchUserData()
            }
        }

        val isSecurityCompromised = mutableStateOf(false)
        val compromiseReason = mutableStateOf("")

        // Security checks: Root, Emulator, Frida, Debugger, Xposed environment verification
        authScope.launch {
            val isRooted = com.liquidmusicglass.engine.SecurityUtils.isDeviceRooted()
            val isEmulator = com.liquidmusicglass.engine.SecurityUtils.isEmulator()
            
            // Diagnostic native results
            val threats = try { com.liquidmusicglass.security.NativeSecurity.nativeSecurityCheck() } catch (_: Throwable) { -1 }
            val hooksSafe = try { com.liquidmusicglass.security.NativeSecurity.nativeCheckHooks() } catch (_: Throwable) { false }
            
            // Signature verification details
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
            LiquidMusicGlassTheme(themeMode = themeMode) {
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

    override fun onDestroy() {
        super.onDestroy()
        if (!isNetworkCallbackRegistered) return
        val connectivityManager = getSystemService(ConnectivityManager::class.java)
        try {
            connectivityManager?.unregisterNetworkCallback(networkCallback)
        } catch (e: IllegalArgumentException) {
            // Callback was not registered — ignore
        }
        isNetworkCallbackRegistered = false
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleTelegramAuth(intent)
        handleNotificationTap(intent)
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
        
        // Handle ICM Telegram link redirect (liquidmusicglass://oauth/icm)
        if (data.scheme == "liquidmusicglass" && data.host == "oauth" && data.path == "/icm") {
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
