package com.liquidmusicglass

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.liquidmusicglass.api.icm.IcmAuthRepository
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

class MainActivity : ComponentActivity() {

    private val authScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (CrashHandler.hasCrashLog(this)) {
            startActivity(Intent(this, CrashActivity::class.java))
            finish()
            return
        }

        enableEdgeToEdge()

        // Handle Telegram auth redirect
        handleTelegramAuth(intent)

        // Security checks: Root & Emulator detection
        if (com.liquidmusicglass.engine.SecurityUtils.isDeviceRooted() || com.liquidmusicglass.engine.SecurityUtils.isEmulator()) {
            android.util.Log.e("Security", "Security risk detected: Root or Emulator")
            // In production, you might want to show a warning dialog or finish the activity
        }

        setContent {
            val themeMode by PlayerController.themeMode.collectAsState()
            LiquidMusicGlassTheme(themeMode = themeMode) {
                AppRoot()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleTelegramAuth(intent)
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
                        // After token is issued, fetch profile & preferences (subscription)
                        IcmAuthRepository.fetchUserData()
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
