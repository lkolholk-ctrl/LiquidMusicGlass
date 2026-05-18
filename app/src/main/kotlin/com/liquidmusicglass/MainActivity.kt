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
            com.liquidmusicglass.api.icm.IcmAuthRepository.setTelegramAuth(
                icmUserId = icmUserId,
                state = state
            )
            android.widget.Toast.makeText(
                this,
                "Telegram auth successful",
                android.widget.Toast.LENGTH_SHORT
            ).show()
            return
        }
    }
 }
