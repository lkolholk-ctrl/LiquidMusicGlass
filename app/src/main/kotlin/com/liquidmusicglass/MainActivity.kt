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
        if (data.host == "liquid.glassfiles.ru" && data.path?.startsWith("/auth/telegram") == true) {
            // Extract token from query parameters
            val token = data.getQueryParameter("token")
            val expiresIn = data.getQueryParameter("expires_in")?.toIntOrNull() ?: 3600
            val userId = data.getQueryParameter("user_id")
            val username = data.getQueryParameter("username")

            if (token != null && userId != null) {
                // Store auth data
                com.liquidmusicglass.api.icm.IcmAuthRepository.setTelegramAuth(
                    userId = userId,
                    username = username,
                    token = token,
                    expiresIn = expiresIn
                )
                // Refresh UI or notify success
                android.widget.Toast.makeText(
                    this,
                    "Telegram auth successful",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
 }