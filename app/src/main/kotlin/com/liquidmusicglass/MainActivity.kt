package com.liquidmusicglass

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.liquidmusicglass.engine.PlayerController
import com.liquidmusicglass.logging.CrashHandler
import com.liquidmusicglass.ui.AppRoot
import com.liquidmusicglass.ui.crash.CrashActivity
import com.liquidmusicglass.ui.theme.LiquidMusicGlassTheme

class MainActivity : ComponentActivity() {

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
        
        // Handle server redirect with session token (liquidmusicglass://auth/telegram)
        if (data.scheme == "liquidmusicglass" && data.host == "auth" && data.path == "/telegram") {
            val success = data.getQueryParameter("success")?.toBoolean() ?: false
            val token = data.getQueryParameter("token")
            val expiresIn = data.getQueryParameter("expires_in")?.toIntOrNull() ?: 3600
            val icmUserId = data.getQueryParameter("icm_user_id")
            val error = data.getQueryParameter("error")

            if (!success || error != null) {
                android.widget.Toast.makeText(
                    this,
                    "Auth error: ${error ?: "Unknown error"}",
                    android.widget.Toast.LENGTH_LONG
                ).show()
                return
            }

            if (token != null && icmUserId != null) {
                // Store Telegram auth data with session token from server
                com.liquidmusicglass.api.icm.IcmAuthRepository.setTelegramAuthWithToken(
                    icmUserId = icmUserId,
                    token = token,
                    expiresIn = expiresIn
                )
                android.widget.Toast.makeText(
                    this,
                    "Telegram auth successful",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
            return
        }
        
        // Fallback: handle direct ICM callback (if no server redirect)
        if (data.host == "liquid.glassfiles.ru" && data.path?.startsWith("/auth/telegram") == true) {
            val linked = data.getQueryParameter("linked")?.toBoolean() ?: false
            val icmUserId = data.getQueryParameter("icm_user_id")
            val state = data.getQueryParameter("state")
            val error = data.getQueryParameter("error")

            if (error != null) {
                android.widget.Toast.makeText(
                    this,
                    "Auth error: $error",
                    android.widget.Toast.LENGTH_LONG
                ).show()
                return
            }

            if (linked && icmUserId != null) {
                com.liquidmusicglass.api.icm.IcmAuthRepository.setTelegramAuth(
                    icmUserId = icmUserId,
                    state = state
                )
                android.widget.Toast.makeText(
                    this,
                    "Telegram auth successful",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
 }