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

    private const val KEY_PROFILE_NAME = "profile_name"
    private const val KEY_AVATAR_URL = "avatar_url"
    private const val KEY_MAX_QUALITY = "max_quality"
    private const val KEY_ALLOWED_QUALITIES = "allowed_qualities"

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

    // ─── Profile data from /me/profile ───
    private val _profileName = MutableStateFlow<String?>(null)
    val profileName: StateFlow<String?> = _profileName

    private val _avatarUrl = MutableStateFlow<String?>(null)
    val avatarUrl: StateFlow<String?> = _avatarUrl

    // ─── Subscription data from /me/subscription ───
    private val _subscription = MutableStateFlow<IcmSubscriptionResponse?>(null)
    val subscription: StateFlow<IcmSubscriptionResponse?> = _subscription

    // ─── Preferences from /me/preferences ───
    private val _maxQuality = MutableStateFlow<String?>(null)
    val maxQuality: StateFlow<String?> = _maxQuality

    private val _allowedQualities = MutableStateFlow<List<String>>(emptyList())
    val allowedQualities: StateFlow<List<String>> = _allowedQualities

    private var prefs: SharedPreferences? = null
    private var appContext: Context? = null

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            // Единая политика ретраев — только наш интерсептор (без двойных повторов).
            .retryOnConnectionFailure(false)
            .addInterceptor { chain ->
                val request = chain.request()
                var response: okhttp3.Response? = null
                var exception: java.io.IOException? = null
                var tryCount = 0
                val maxRetries = 3
                while (tryCount < maxRetries) {
                    try {
                        response = chain.proceed(request)
                        // Никогда не ретраим 4xx (в т.ч. 429).
                        if (response.isSuccessful || response.code < 500) {
                            return@addInterceptor response
                        }
                        // Server error (5xx) — retry с бэкоффом + джиттером.
                        tryCount++
                        if (tryCount >= maxRetries) {
                            return@addInterceptor response
                        }
                        response.close()
                        val backoff = 500L * (1L shl (tryCount - 1)) + kotlin.random.Random.nextLong(0, 150)
                        try { Thread.sleep(backoff) } catch (_: Exception) {}
                    } catch (e: java.io.IOException) {
                        exception = e
                        tryCount++
                        if (tryCount >= maxRetries) throw e
                        val backoff = 500L * (1L shl (tryCount - 1)) + kotlin.random.Random.nextLong(0, 150)
                        try { Thread.sleep(backoff) } catch (_: Exception) {}
                    }
                }
                response ?: throw exception ?: java.io.IOException("Network error")
            }
            .build()
    }

    /**
     * Data class for token + expiry. Declared early for use in method signatures.
     */
    data class TokenData(
        val token: String,
        val expiresAt: Long
    )

    fun init(context: Context) {
        appContext = context.applicationContext
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        loadState()
        syncToIcmApi()
    }

    private fun loadState() {
        val p = prefs ?: return
        _userEmail.value = p.getString(KEY_EMAIL, null)
        _telegramId.value = p.getString(KEY_TELEGRAM_ID, null)
        _partnerUserId.value = p.getString(KEY_USER_ID, null)
        _isPremium.value = p.getBoolean(KEY_IS_PREMIUM, false)
        _premiumExpiresAt.value = p.getLong(KEY_PREMIUM_EXPIRES, 0)
        _profileName.value = p.getString(KEY_PROFILE_NAME, null)
        _avatarUrl.value = p.getString(KEY_AVATAR_URL, null)
        _maxQuality.value = p.getString(KEY_MAX_QUALITY, null)
        _allowedQualities.value = p.getString(KEY_ALLOWED_QUALITIES, null)
            ?.split(",")
            ?.filter { it.isNotBlank() }
            ?: emptyList()
        val hasUser = _partnerUserId.value != null
        val isTelegram = p.getString(KEY_AUTH_METHOD, null) == "telegram"
        // Telegram link does not always issue a partner_session_token; presence
        // of a partner_user_id is enough to consider the user logged in.
        _isLoggedIn.value = hasUser && (isTelegram || p.getString(KEY_TOKEN, null) != null)
    }

    /**
     * Push the current partner_user_id and session token into the shared
     * [IcmApi] instance so subsequent API calls are authenticated correctly.
     */
    private fun syncToIcmApi() {
        val api = IcmApi.getInstance()
        api.partnerUserId = _partnerUserId.value
        api.sessionToken = getSessionToken()
        // Авто-рефреш на 401: токен живёт ~1ч, без этого через час слушания
        // юзерские вызовы (волна/лайки) молча дохли до перезапуска приложения.
        api.sessionRefresher = { reissueSessionToken() }
        IcmRepository.setPartnerUserId(_partnerUserId.value)
        IcmRepository.setSessionToken(getSessionToken())
    }

    /**
     * ФОРСИРОВАННЫЙ перевыпуск session-токена (для авто-рефреша на 401):
     * локальная валидность не важна — сервер токен уже отверг. Ключ и юзер
     * берутся из собственного состояния. null = перевыпуск невозможен
     * (нет ключа/юзера или сеть легла).
     */
    suspend fun reissueSessionToken(): String? = withContext(Dispatchers.IO) {
        val key = getPartnerKey().takeIf { it.isNotBlank() } ?: return@withContext null
        val userId = _partnerUserId.value ?: return@withContext null
        val tokenData = issueSessionToken(userId, key).getOrNull() ?: return@withContext null
        prefs?.edit()?.apply {
            putString(KEY_TOKEN, tokenData.token)
            putLong(KEY_TOKEN_EXPIRES, tokenData.expiresAt)
            apply()
        }
        IcmRepository.setSessionToken(tokenData.token)
        tokenData.token
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
            val tokenResult = issueSessionToken(userId, apiKey)

            if (tokenResult.isSuccess) {
                val tokenData = tokenResult.getOrThrow()
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
                syncToIcmApi()
                Result.success(tokenData.token)
            } else {
                Result.failure(tokenResult.exceptionOrNull() ?: IOException("Unknown error"))
            }
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
            val tokenResult = issueSessionToken(userId, apiKey)

            if (tokenResult.isSuccess) {
                val tokenData = tokenResult.getOrThrow()
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
                syncToIcmApi()
                Result.success(tokenData.token)
            } else {
                Result.failure(tokenResult.exceptionOrNull() ?: IOException("Unknown error"))
            }
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
        // hide_explicit — флаг СЕССИИ (per-user настройка на сервере ICM):
        // сервер фильтрует поиск/треки. Дефолт читает тумблер из настроек —
        // так флаг уходит при ЛЮБОМ выпуске токена (логин, рестарт, 401-рефреш).
        hideExplicit: Boolean = com.liquidmusicglass.engine.AppSettings.hideExplicit.value
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
        val tokenResult = issueSessionToken(userId, apiKey)

        if (tokenResult.isSuccess) {
            val tokenData = tokenResult.getOrThrow()
            prefs?.edit()?.apply {
                putString(KEY_TOKEN, tokenData.token)
                putLong(KEY_TOKEN_EXPIRES, tokenData.expiresAt)
                apply()
            }
            Result.success(tokenData.token)
        } else {
            Result.failure(tokenResult.exceptionOrNull() ?: IOException("Unknown error"))
        }
    }

    /**
     * Set Telegram auth data from ICM redirect (no token yet).
     * After successful Telegram link, we get icm_user_id and need to issue session token.
     *
     * IMPORTANT: this must NOT overwrite `partner_user_id` — the value that was
     * sent to ICM during the /partner/<id>/link request is the one ICM associates
     * with the linked account, and changing it here would break every subsequent
     * call (wave, library, /me/ *) because backend would return user_not_linked.
     */
    fun setTelegramAuth(
        icmUserId: String,
        state: String?
    ) {
        val p = prefs
        // Use ensurePartnerUserId() to get the SAME ID that was sent to /link.
        // After logout prefs are cleared, so this generates a new one — but that's
        // fine because /link was called with ensurePartnerUserId() too (at click time).
        val partnerUserId = ensurePartnerUserId()
        p?.edit()?.apply {
            putString(KEY_TELEGRAM_ID, icmUserId)
            putString(KEY_AUTH_METHOD, "telegram")
            putString(KEY_USER_ID, partnerUserId)
            apply()
        }
        _telegramId.value = icmUserId
        _partnerUserId.value = partnerUserId
        _isLoggedIn.value = true
        syncToIcmApi()
    }

    /**
     * Set Telegram auth with session token from server redirect.
     * Server issues token and redirects to app with token in URL.
     * Preserves the partner_user_id that was used during /link.
     */
    fun setTelegramAuthWithToken(
        icmUserId: String,
        token: String,
        expiresIn: Int
    ) {
        val expiresAt = System.currentTimeMillis() + expiresIn * 1000
        val p = prefs
        val existingUserId = p?.getString(KEY_USER_ID, null)
        p?.edit()?.apply {
            putString(KEY_TELEGRAM_ID, icmUserId)
            if (existingUserId.isNullOrBlank()) {
                putString(KEY_USER_ID, "tg_${icmUserId}")
            }
            putString(KEY_TOKEN, token)
            putLong(KEY_TOKEN_EXPIRES, expiresAt)
            putString(KEY_AUTH_METHOD, "telegram")
            apply()
        }
        _telegramId.value = icmUserId
        _partnerUserId.value = existingUserId?.takeIf { it.isNotBlank() } ?: "tg_${icmUserId}"
        _isLoggedIn.value = true
        syncToIcmApi()
    }

    /**
     * Issue session token after Telegram auth.
     * Must be called after setTelegramAuth with valid API key.
     */
    suspend fun issueSessionAfterTelegramAuth(
        apiKey: String,
        hideExplicit: Boolean = com.liquidmusicglass.engine.AppSettings.hideExplicit.value
    ): Result<String> = withContext(Dispatchers.IO) {
        val userId = _partnerUserId.value ?: return@withContext Result.failure(IOException("No partner_user_id set"))
        val tokenResult = issueSessionToken(userId, apiKey, hideExplicit)

        if (tokenResult.isSuccess) {
            val tokenData = tokenResult.getOrThrow()
            prefs?.edit()?.apply {
                putString(KEY_TOKEN, tokenData.token)
                putLong(KEY_TOKEN_EXPIRES, tokenData.expiresAt)
                apply()
            }
            syncToIcmApi()
            Result.success(tokenData.token)
        } else {
            Result.failure(tokenResult.exceptionOrNull() ?: IOException("Unknown error"))
        }
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
        _profileName.value = null
        _avatarUrl.value = null
        _maxQuality.value = null
        _allowedQualities.value = emptyList()
        _subscription.value = null
        syncToIcmApi()
    }

    /**
     * Read the partner_user_id that AuthScreen pre-allocated for the /link
     * request (or anything previously stored). Creates one on first call so the
     * value is stable across the lifetime of the install.
     */
    fun ensurePartnerUserId(): String {
        val p = prefs ?: return "lg_${java.util.UUID.randomUUID().toString().replace("-", "").take(16)}"
        p.getString(KEY_USER_ID, null)?.takeIf { it.isNotBlank() }?.let { return it }
        val generated = "lg_${java.util.UUID.randomUUID().toString().replace("-", "").take(16)}"
        p.edit().putString(KEY_USER_ID, generated).apply()
        _partnerUserId.value = generated
        syncToIcmApi()
        return generated
    }

    /**
     * Fetch user profile from /me/profile.
     * Requires linked user. Updates profileName and avatarUrl StateFlows.
     * Returns Result with IcmUserProfile or failure (401/403 if not linked).
     */
    suspend fun fetchProfile(): Result<IcmUserProfile> = withContext(Dispatchers.IO) {
        val result = IcmRepository.getUserProfile()
        result?.let { profile ->
            _profileName.value = profile.name
            _avatarUrl.value = profile.avatarUrl
            // Persist to SharedPreferences
            prefs?.edit()?.apply {
                putString(KEY_PROFILE_NAME, profile.name)
                putString(KEY_AVATAR_URL, profile.avatarUrl)
                apply()
            }
        }
        result?.let { Result.success(it) }
            ?: Result.failure(IOException("Failed to fetch profile"))
    }

    /**
     * Fetch user preferences from /me/preferences.
     * Requires linked user with active subscription.
     * Updates maxQuality and allowedQualities StateFlows.
     * Returns Result with IcmUserPreferences or failure (403 subscription_required).
     */
    suspend fun fetchPreferences(): Result<IcmUserPreferences> = withContext(Dispatchers.IO) {
        val result = IcmRepository.getUserPreferences()
        result?.let { prefsData ->
            _maxQuality.value = prefsData.maxQuality
            _allowedQualities.value = prefsData.allowedQualities
            // Infer premium status from allowed qualities
            val hasPremium = prefsData.allowedQualities.contains("ALAC") ||
                    prefsData.allowedQualities.contains("320K")
            if (hasPremium != _isPremium.value) {
                _isPremium.value = hasPremium
                prefs?.edit()?.apply {
                    putBoolean(KEY_IS_PREMIUM, hasPremium)
                    apply()
                }
            }
            // Persist preferences
            prefs?.edit()?.apply {
                putString(KEY_MAX_QUALITY, prefsData.maxQuality)
                putString(KEY_ALLOWED_QUALITIES, prefsData.allowedQualities.joinToString(","))
                apply()
            }
        }
        result?.let { Result.success(it) }
            ?: Result.failure(IOException("Failed to fetch preferences"))
    }

    /**
     * Fetch user's ICM subscription information from /me/subscription.
     * Updates isPremium, premiumExpiresAt, and subscription StateFlows.
     */
    suspend fun fetchSubscription(): Result<IcmSubscriptionResponse> = withContext(Dispatchers.IO) {
        val result = IcmRepository.getUserSubscription()
        result?.let { sub ->
            _subscription.value = sub
            // isActive (active И не истёкшая), а не сырой sub.active — чтобы качество
            // гейтилось ровно так же, как скачивание в FeatureAccessManager. Иначе
            // подписка с active=true, но daysLeft=0 давала премиум-качество, при этом
            // скачивание уже было отключено — рассинхрон с подпиской.
            _isPremium.value = sub.isActive
            _premiumExpiresAt.value = sub.expiresAt ?: 0L
            // Persist to SharedPreferences
            prefs?.edit()?.apply {
                putBoolean(KEY_IS_PREMIUM, sub.isActive)
                putLong(KEY_PREMIUM_EXPIRES, sub.expiresAt ?: 0L)
                apply()
            }
        }
        result?.let { Result.success(it) }
            ?: Result.failure(IOException("Failed to fetch subscription"))
    }

    /**
     * Fetch profile, preferences, and subscription after successful auth.
     * Call this after Telegram redirect or email login completes.
     */
    suspend fun fetchUserData(): Result<Pair<IcmUserProfile?, IcmUserPreferences?>> = withContext(Dispatchers.IO) {
        val profileResult = fetchProfile()
        val prefsResult = fetchPreferences()
        
        // Fetch subscription in parallel/sequence
        fetchSubscription()

        val profile = profileResult.getOrNull()
        val preferences = prefsResult.getOrNull()

        if (profile != null || preferences != null) {
            Result.success(profile to preferences)
        } else {
            Result.failure(IOException("Failed to fetch user data"))
        }
    }

    /**
     * Get partner API key from secure storage.
     * First tries SharedPreferences (set during setup), then falls back to native .so.
     * Returns empty string if not configured — caller must handle.
     */
    fun getPartnerKey(): String {
        // 1. Try SharedPreferences (set during app setup / onboarding)
        val prefsKey = prefs?.getString("partner_api_key", null)
        if (!prefsKey.isNullOrBlank() && prefsKey.startsWith("pk_")) {
            return prefsKey
        }

        // 2. Fallback: native .so module (JNI) — production path
        val ctx = appContext ?: com.liquidmusicglass.engine.PlayerController.context
        if (ctx != null) {
            try {
                val nativeKey = com.liquidmusicglass.engine.IcmKeyProvider.getApiKey(ctx)
                if (!nativeKey.isNullOrBlank() && nativeKey.startsWith("pk_")) {
                    return nativeKey
                }
            } catch (e: Exception) {
                android.util.Log.e("IcmAuthRepository", "Failed to load partner key from JNI IcmKeyProvider", e)
            }
        }

        // 3. Development fallback — fallback to BuildConfig.ICM_API_KEY if available
        try {
            val configKey = com.liquidmusicglass.BuildConfig.ICM_API_KEY
            if (!configKey.isNullOrBlank() && configKey.startsWith("pk_")) {
                return configKey
            }
        } catch (_: Throwable) {}

        return ""
    }

    /**
     * Get effective quality for a track based on its catalog type (Apple vs VK)
     * and the user's subscription / premium status.
     * VK tracks: always null (API rejects explicit quality for VK source).
     * Apple tracks: cap based on subscription level.
     */
    fun getEffectiveQuality(trackId: String, source: String? = null): String? {
        // VK tracks: API rejects explicit quality — must be null
        val isVk = source == "vk" || trackId.startsWith("secondary_") || trackId.startsWith("vk_")
        if (isVk) return null

        val allowed = _allowedQualities.value
        val desired = IcmApi.getInstance().streamQuality ?: "256K"
        if (allowed.isNotEmpty() && !allowed.contains(desired)) {
            return if (allowed.contains("256K")) "256K" else allowed.firstOrNull() ?: "128K"
        }
        val hasPremium = _isPremium.value
        if (!hasPremium) {
            if (desired == "ALAC" || desired == "320K") return "256K"
        } else {
            if (desired == "ALAC") return "320K"
        }
        return desired
    }

    /**
     * Save partner API key to secure storage.
     * Call this after user enters key in setup flow.
     */
    fun setPartnerKey(key: String) {
        prefs?.edit()?.apply {
            putString("partner_api_key", key)
            apply()
        }
    }
}
