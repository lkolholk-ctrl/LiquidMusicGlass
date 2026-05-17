package com.liquidmusicglass.api.icm

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest

/**
 * ICM Auth Repository — handles user authentication, session tokens, and subscription state.
 *
 * Auth flow:
 * 1. User enters email or uses Telegram login
 * 2. We generate partner_user_id (hashed email or telegram id)
 * 3. POST /session/issue → get JWT partner_session_token
 * 4. Store token, use it for all API calls
 * 5. Subscription status is checked separately (from your backend or manual flag)
 */
object IcmAuthRepository {

    private const val PREFS_NAME = "icm_auth"
    private const val KEY_USER_ID = "partner_user_id"
    private const val KEY_TOKEN = "session_token"
    private const val KEY_TOKEN_EXPIRES = "token_expires_at"
    private const val KEY_EMAIL = "user_email"
    private const val KEY_IS_PREMIUM = "is_premium"
    private const val KEY_PREMIUM_EXPIRES = "premium_expires_at"
    private const val KEY_TELEGRAM_ID = "telegram_id"
    private const val KEY_AUTH_METHOD = "auth_method" // "email" or "telegram"

    private val _isLoggedIn = MutableStateFlow(false)
    val isLoggedIn: StateFlow<Boolean> = _isLoggedIn

    private val _isPremium = MutableStateFlow(false)
    val isPremium: StateFlow<Boolean> = _isPremium

    private val _userEmail = MutableStateFlow<String?>(null)
    val userEmail: StateFlow<String?> = _userEmail

    private val _telegramId = MutableStateFlow<String?>(null)
    val telegramId: StateFlow<String?> = _telegramId

    private val _partnerUserId = MutableStateFlow<String?>(null)
    val partnerUserId: StateFlow<String?> = _partnerUserId

    private val _premiumExpiresAt = MutableStateFlow<Long>(0)
    val premiumExpiresAt: StateFlow<Long> = _premiumExpiresAt

    private var prefs: SharedPreferences? = null

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        loadState()
    }

    private fun loadState() {
        val p = prefs ?: return
        _userEmail.value = p.getString(KEY_EMAIL, null)
        _telegramId.value = p.getString(KEY_TELEGRAM_ID, null)
        _partnerUserId.value = p.getString(KEY_USER_ID, null)
        _isPremium.value = p.getBoolean(KEY_IS_PREMIUM, false)
        _premiumExpiresAt.value = p.getLong(KEY_PREMIUM_EXPIRES, 0)
        _isLoggedIn.value = _partnerUserId.value != null
    }

    /**
     * Generate partner_user_id from email using SHA-256 hash.
     * This creates a stable, anonymous identifier.
     */
    fun generateUserIdFromEmail(email: String): String {
        val normalized = email.trim().lowercase()
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(normalized.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return "email_${hash.take(32)}"
    }

    /**
     * Generate partner_user_id from Telegram ID.
     */
    fun generateUserIdFromTelegram(telegramId: Long): String {
        return "tg_${telegramId}"
    }

    /**
     * Login with email. Issues session token via ICM API.
     */
    suspend fun loginWithEmail(email: String, apiKey: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val userId = generateUserIdFromEmail(email)
            val result = issueSessionToken(userId, apiKey)

            result.onSuccess { tokenData ->
                prefs?.edit()?.apply {
                    putString(KEY_EMAIL, email)
                    putString(KEY_USER_ID, userId)
                    putString(KEY_TOKEN, tokenData.token)
                    putLong(KEY_TOKEN_EXPIRES, tokenData.expiresAt)
                    putString(KEY_AUTH_METHOD, "email")
                    apply()
                }
                _userEmail.value = email
                _partnerUserId.value = userId
                _isLoggedIn.value = true
            }

            result.map { it.token }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Login with Telegram ID. Issues session token via ICM API.
     */
    suspend fun loginWithTelegram(telegramId: Long, apiKey: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val userId = generateUserIdFromTelegram(telegramId)
            val result = issueSessionToken(userId, apiKey)

            result.onSuccess { tokenData ->
                prefs?.edit()?.apply {
                    putString(KEY_TELEGRAM_ID, telegramId.toString())
                    putString(KEY_USER_ID, userId)
                    putString(KEY_TOKEN, tokenData.token)
                    putLong(KEY_TOKEN_EXPIRES, tokenData.expiresAt)
                    putString(KEY_AUTH_METHOD, "telegram")
                    apply()
                }
                _telegramId.value = telegramId.toString()
                _partnerUserId.value = userId
                _isLoggedIn.value = true
            }

            result.map { it.token }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Issue session token from ICM API.
     */
    private fun issueSessionToken(
        partnerUserId: String,
        apiKey: String,
        hideExplicit: Boolean = false
    ): Result<TokenData> {
        val jsonBody = JSONObject().apply {
            put("partner_user_id", partnerUserId)
            put("hide_explicit", hideExplicit)
        }

        val request = Request.Builder()
            .url("https://byicloud.online/api/partner/session/issue")
            .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
            .header("X-Partner-Key", apiKey)
            .header("Content-Type", "application/json")
            .build()

        return try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return Result.failure(IOException("Session issue failed: ${response.code}"))
                }

                val body = response.body?.string() ?: return Result.failure(IOException("Empty response"))
                val json = JSONObject(body)

                val token = json.getString("partner_session_token")
                val expiresIn = json.getInt("expires_in")
                val expiresAt = System.currentTimeMillis() + expiresIn * 1000

                Result.success(TokenData(token, expiresAt))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Set premium status. In production this should come from your backend.
     * For now, manual activation or via Telegram bot command.
     */
    fun setPremium(active: Boolean, expiresAt: Long = 0) {
        prefs?.edit()?.apply {
            putBoolean(KEY_IS_PREMIUM, active)
            putLong(KEY_PREMIUM_EXPIRES, expiresAt)
            apply()
        }
        _isPremium.value = active
        _premiumExpiresAt.value = expiresAt
    }

    /**
     * Check if premium subscription is still valid.
     */
    fun isPremiumValid(): Boolean {
        if (!_isPremium.value) return false
        if (_premiumExpiresAt.value == 0L) return true // No expiry set
        return System.currentTimeMillis() < _premiumExpiresAt.value
    }

    /**
     * Get current session token if valid.
     */
    fun getSessionToken(): String? {
        val p = prefs ?: return null
        val token = p.getString(KEY_TOKEN, null) ?: return null
        val expiresAt = p.getLong(KEY_TOKEN_EXPIRES, 0)
        if (System.currentTimeMillis() >= expiresAt - 60_000) {
            // Token expired or about to expire
            return null
        }
        return token
    }

    /**
     * Refresh session token if needed.
     */
    suspend fun refreshTokenIfNeeded(apiKey: String): Result<String> = withContext(Dispatchers.IO) {
        val currentToken = getSessionToken()
        if (currentToken != null) {
            return@withContext Result.success(currentToken)
        }

        val userId = _partnerUserId.value ?: return@withContext Result.failure(IOException("Not logged in"))
        val result = issueSessionToken(userId, apiKey)

        result.onSuccess { tokenData ->
            prefs?.edit()?.apply {
                putString(KEY_TOKEN, tokenData.token)
                putLong(KEY_TOKEN_EXPIRES, tokenData.expiresAt)
                apply()
            }
        }

        result.map { it.token }
    }

    /**
     * Set Telegram auth data from ICM redirect (no token yet).
     * After successful Telegram link, we get icm_user_id and need to issue session token.
     */
    fun setTelegramAuth(
        icmUserId: String,
        state: String?
    ) {
        prefs?.edit()?.apply {
            putString(KEY_TELEGRAM_ID, icmUserId)
            putString(KEY_USER_ID, "tg_${icmUserId}")
            putString(KEY_AUTH_METHOD, "telegram")
            apply()
        }
        _telegramId.value = icmUserId
        _partnerUserId.value = "tg_${icmUserId}"
        _isLoggedIn.value = true
    }

    /**
     * Set Telegram auth with session token from server redirect.
     * Server issues token and redirects to app with token in URL.
     */
    fun setTelegramAuthWithToken(
        icmUserId: String,
        token: String,
        expiresIn: Int
    ) {
        val expiresAt = System.currentTimeMillis() + expiresIn * 1000
        prefs?.edit()?.apply {
            putString(KEY_TELEGRAM_ID, icmUserId)
            putString(KEY_USER_ID, "tg_${icmUserId}")
            putString(KEY_TOKEN, token)
            putLong(KEY_TOKEN_EXPIRES, expiresAt)
            putString(KEY_AUTH_METHOD, "telegram")
            apply()
        }
        _telegramId.value = icmUserId
        _partnerUserId.value = "tg_${icmUserId}"
        _isLoggedIn.value = true
    }

    /**
     * Issue session token after Telegram auth.
     * Must be called after setTelegramAuth with valid API key.
     */
    suspend fun issueSessionAfterTelegramAuth(apiKey: String, hideExplicit: Boolean = false): Result<String> = withContext(Dispatchers.IO) {
        val userId = _partnerUserId.value ?: return@withContext Result.failure(IOException("No partner_user_id set"))
        val result = issueSessionToken(userId, apiKey, hideExplicit)

        result.onSuccess { tokenData ->
            prefs?.edit()?.apply {
                putString(KEY_TOKEN, tokenData.token)
                putLong(KEY_TOKEN_EXPIRES, tokenData.expiresAt)
                apply()
            }
        }

        result.map { it.token }
    }

    /**
     * Logout — clear all auth data.
     */
    fun logout() {
        prefs?.edit()?.clear()?.apply()
        _isLoggedIn.value = false
        _isPremium.value = false
        _userEmail.value = null
        _telegramId.value = null
        _partnerUserId.value = null
        _premiumExpiresAt.value = 0
    }

    data class TokenData(
        val token: String,
        val expiresAt: Long
    )
}
