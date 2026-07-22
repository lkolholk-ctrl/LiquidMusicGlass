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

    /** Вычистить пул соединений (смена сети/fail-streak — см. NetworkVitality).
     *  Только evict, БЕЗ cancelAll: обрывать выпуск токена на полпути нельзя. */
    fun evictConnections() {
        try {
            httpClient.connectionPool.evictAll()
        } catch (_: Throwable) {}
    }

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            // Потолок всего вызова: 3 ретрая интерсептора с бэкоффом не должны
            // держать выпуск токена дольше 45с при мёртвой сети.
            .callTimeout(45, java.util.concurrent.TimeUnit.SECONDS)
            // Короткий keep-alive: долгоживущие сокеты тихо умирают при
            // пересоздании маршрута — не держим их дольше минуты.
            .connectionPool(okhttp3.ConnectionPool(3, 60, java.util.concurrent.TimeUnit.SECONDS))
            // Единая политика ретраев — только наш интерсептор (без двойных повторов).
            .retryOnConnectionFailure(false)
            .addInterceptor { chain ->
                // Ретраи с Thread.sleep УДАЛЕНЫ (P1, аудит): тот же паттерн, что
                // уже убран из IcmApi — sleep держал поток Dispatcher'а во время
                // паузы (до ~2.1с на вызов), а auth-вызовы блокирующие и без
                // отмены. Ровно один proceed; повтор при want — на вызывающем
                // уровне (auth-вызовы редкие: логин/перевыпуск токена).
                chain.proceed(chain.request())
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
    // Single-flight перевыпуска токена (P1, аудит): токен истекает у ВСЕХ
    // параллельных вызовов одновременно (401 на волне/лайках/playback-логе
    // разом) — каждый независимо перевыпускал сессию → залп POST /session/issue
    // (этот клиент идёт МИМО RateGate) и риск 429 по IP.
    private val reissueMutex = kotlinx.coroutines.sync.Mutex()

    suspend fun reissueSessionToken(): String? = withContext(Dispatchers.IO) {
        val before = prefs?.getString(KEY_TOKEN, null)
        reissueMutex.lock()
        try {
            // Пока ждали лок, параллельный вызов мог уже перевыпустить токен —
            // отдаём свежий без второго похода на сервер.
            val current = prefs?.getString(KEY_TOKEN, null)
            if (!current.isNullOrBlank() && current != before) return@withContext current

            val userId = _partnerUserId.value ?: return@withContext null
            val tokenData = issueSessionToken(userId).getOrNull() ?: return@withContext null
            prefs?.edit()?.apply {
                putString(KEY_TOKEN, tokenData.token)
                putLong(KEY_TOKEN_EXPIRES, tokenData.expiresAt)
                apply()
            }
            IcmRepository.setSessionToken(tokenData.token)
            tokenData.token
        } finally {
            reissueMutex.unlock()
        }
    }

    /**
     * Выпуск/рефреш session-токена — через НАШ сервер-брокер. Партнёрского
     * ключа на устройстве НЕТ: X-Partner-Key подставляет сервер.
     *
     * [initial] = true → POST <SERVER>/session/issue (первичный минт после
     * Telegram-линка, с hide_explicit); false → POST <SERVER>/session/refresh
     * (только partner_user_id). Сервер лимитит минт (20/мин issue, 30/мин
     * refresh на IP) — на 429 одна пауза по Retry-After (кап 10с) и один
     * повтор, в цикл не долбим. partner_user_id — bearer-секрет: только TLS,
     * в логи не пишем.
     *
     * Сервер владеет premium — is_premium/premium_expires_at из ответа
     * кэшируем локально.
     */
    private suspend fun issueSessionToken(
        partnerUserId: String,
        // hide_explicit — флаг СЕССИИ (per-user настройка на сервере ICM):
        // сервер фильтрует поиск/треки. Дефолт читает тумблер из настроек.
        hideExplicit: Boolean = com.liquidmusicglass.engine.AppSettings.hideExplicit.value,
        initial: Boolean = false
    ): Result<TokenData> {
        val endpoint = if (initial) "/session/issue" else "/session/refresh"
        val jsonBody = JSONObject().apply {
            put("partner_user_id", partnerUserId)
            if (initial) put("hide_explicit", hideExplicit)
        }

        var lastFailure: Exception? = null
        repeat(2) { attempt ->
            val request = Request.Builder()
                .url("${IcmApi.SERVER_BASE}$endpoint")
                .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
                .header("Content-Type", "application/json")
                .applyTelemetryHeaders()
                .build()
            try {
                httpClient.newCall(request).execute().use { response ->
                    if (response.code == 429 && attempt == 0) {
                        // Rate-limit брокера: пауза по Retry-After (кап 10с) + 1 повтор.
                        val waitSec = response.header("Retry-After")?.toLongOrNull()
                            ?.coerceIn(1L, 10L) ?: 3L
                        lastFailure = IOException("Session $endpoint rate-limited (429)")
                        response.close()
                        kotlinx.coroutines.delay(waitSec * 1000)
                        return@repeat
                    }
                    if (!response.isSuccessful) {
                        return Result.failure(IOException("Session $endpoint failed: ${response.code}"))
                    }

                    val body = response.body?.string() ?: return Result.failure(IOException("Empty response"))
                    val json = JSONObject(body)

                    val token = json.getString("partner_session_token")
                    val expiresIn = json.getInt("expires_in")
                    val expiresAt = System.currentTimeMillis() + expiresIn * 1000

                    // Premium с сервера (источник истины — брокер, локально кэш).
                    if (json.has("is_premium")) {
                        setPremium(
                            active = json.optBoolean("is_premium", false),
                            expiresAt = json.optLong("premium_expires_at", 0L)
                        )
                    }

                    return Result.success(TokenData(token, expiresAt))
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                return Result.failure(e)
            }
        }
        return Result.failure(lastFailure ?: IOException("Session $endpoint failed"))
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
     * Refresh session token if needed (через сервер-брокер, ключ не нужен).
     */
    suspend fun refreshTokenIfNeeded(): Result<String> = withContext(Dispatchers.IO) {
        val currentToken = getSessionToken()
        if (currentToken != null) {
            return@withContext Result.success(currentToken)
        }

        val userId = _partnerUserId.value ?: return@withContext Result.failure(IOException("Not logged in"))
        val tokenResult = issueSessionToken(userId)

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
     * Issue session token after Telegram auth (через сервер-брокер).
     * Must be called after setTelegramAuth.
     */
    suspend fun issueSessionAfterTelegramAuth(
        hideExplicit: Boolean = com.liquidmusicglass.engine.AppSettings.hideExplicit.value
    ): Result<String> = withContext(Dispatchers.IO) {
        val userId = _partnerUserId.value ?: return@withContext Result.failure(IOException("No partner_user_id set"))
        // Первичный минт после Telegram-линка → /session/issue (с hide_explicit).
        val tokenResult = issueSessionToken(userId, hideExplicit, initial = true)

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

    // ═══════════════════════════════════════════════════════════
    //  Email-вход (OTP, passwordless) — см. docs/email-auth-design.md
    //  Вход = регистрация: ICM сам создаёт аккаунт для нового email.
    //  Все вызовы идут через сервер-брокер (/lmg/auth/email/*).
    // ═══════════════════════════════════════════════════════════

    /** Короткоживущая OTP-сессия: держим В ПАМЯТИ (nonce — секрет, в prefs не пишем). */
    data class EmailOtpSession(
        val nonce: String,
        val email: String,
        val expiresAtMs: Long,
        val state: String
    )

    /**
     * Шаг 1: выслать 6-значный код на почту. partner_user_id — тот же
     * стабильный id, что и для Telegram-линка (инвариант setTelegramAuth).
     */
    suspend fun requestEmailOtp(email: String): Result<EmailOtpSession> = withContext(Dispatchers.IO) {
        val normalized = email.trim().lowercase()
        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(normalized).matches()) {
            return@withContext Result.failure(IOException("invalid email"))
        }
        val userId = ensurePartnerUserId()
        val state = java.util.UUID.randomUUID().toString()
        IcmApi.getInstance().requestEmailLink(userId, normalized, state).map { resp ->
            EmailOtpSession(
                nonce = resp.nonce,
                email = normalized,
                expiresAtMs = System.currentTimeMillis() + resp.expiresIn * 1000L,
                state = state
            )
        }
    }

    /**
     * Шаг 2: подтвердить код → линк готов → выпустить session-токен через
     * брокер → подтянуть профиль/подписку. Возвращает password_issued
     * (true = ICM создал НОВЫЙ аккаунт и выслал пароль на почту — показать нотис).
     */
    suspend fun verifyEmailOtp(session: EmailOtpSession, otp: String): Result<Boolean> = withContext(Dispatchers.IO) {
        val verify = IcmApi.getInstance().verifyEmailLink(session.nonce, otp)
            .getOrElse { return@withContext Result.failure(it) }
        if (!verify.linked) return@withContext Result.failure(IOException("not linked"))
        // Эхо state сверяем, если сервер его вернул (CSRF-гигиена как у Telegram).
        if (verify.state != null && verify.state != session.state) {
            return@withContext Result.failure(IOException("state mismatch"))
        }

        prefs?.edit()?.apply {
            putString(KEY_EMAIL, session.email)
            putString(KEY_AUTH_METHOD, "email")
            // KEY_USER_ID не трогаем — линк сделан на ensurePartnerUserId()
            apply()
        }
        _userEmail.value = session.email

        // Сессия через брокер (первичный минт) — как после Telegram-линка.
        val token = issueSessionToken(ensurePartnerUserId(), initial = true)
            .getOrElse { return@withContext Result.failure(it) }
        prefs?.edit()?.apply {
            putString(KEY_TOKEN, token.token)
            putLong(KEY_TOKEN_EXPIRES, token.expiresAt)
            apply()
        }
        _isLoggedIn.value = true
        syncToIcmApi()
        runCatching { fetchUserData() }
        Result.success(verify.passwordIssued)
    }

    /** Сменить пароль ICM-аккаунта (для вошедшего; текущий пароль обязателен). */
    suspend fun changeIcmPassword(current: String, new: String): Result<Unit> = withContext(Dispatchers.IO) {
        val userId = _partnerUserId.value ?: return@withContext Result.failure(IOException("Not logged in"))
        IcmApi.getInstance().changePassword(userId, current, new).map { }
    }

    /** Сбросить пароль ICM: временный пароль уходит на почту (кулдаун 60с у ICM). */
    suspend fun resetIcmPassword(): Result<Unit> = withContext(Dispatchers.IO) {
        val userId = _partnerUserId.value ?: return@withContext Result.failure(IOException("Not logged in"))
        IcmApi.getInstance().resetPassword(userId).map { }
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
     * Premium — ТОЛЬКО с нашего сервера: GET <SERVER>/me/subscription
     * (X-Partner-User-Id). Ответ { is_premium, premium_expires_at, plan }.
     * Локальные флаги — кэш; источник истины — брокер (клиентскую проверку
     * можно пропатчить в APK, серверную — нет).
     */
    suspend fun fetchSubscription(): Result<IcmSubscriptionResponse> = withContext(Dispatchers.IO) {
        val userId = _partnerUserId.value
            ?: return@withContext Result.failure(IOException("Not logged in"))
        try {
            val request = Request.Builder()
                .url("${IcmApi.SERVER_BASE}/me/subscription")
                .header("X-Partner-User-Id", userId)
                .applyTelemetryHeaders()
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(IOException("Subscription fetch failed: ${response.code}"))
                }
                val body = response.body?.string()
                    ?: return@withContext Result.failure(IOException("Empty response"))
                val json = JSONObject(body)
                val isPremium = json.optBoolean("is_premium", false)
                val expiresAt = json.optLong("premium_expires_at", 0L)
                val plan = json.optString("plan", "").takeIf { it.isNotBlank() }
                // Регионы подписки (US/NZ) — брокер пробрасывает их из ICM;
                // без них профиль показывал ложный «Global (WW)».
                val regions = buildList {
                    val arr = json.optJSONArray("regions")
                    if (arr != null) for (i in 0 until arr.length()) {
                        val r = arr.optJSONObject(i) ?: continue
                        val code = r.optString("code", "")
                        if (code.isNotBlank()) add(
                            IcmSubscriptionRegion(
                                code = code,
                                name = r.optString("name", code.uppercase()),
                                expiresAt = r.optLong("expires_at", 0L).takeIf { it > 0L }
                            )
                        )
                    }
                }

                setPremium(isPremium, expiresAt)

                // Синтезируем ответ в прежней форме (daysLeft из expires_at —
                // isActive считается так же, как гейт скачивания).
                val daysLeft = if (expiresAt > 0L) {
                    (((expiresAt - System.currentTimeMillis()) / 86_400_000L) + 1)
                        .coerceAtLeast(0L).toInt()
                } else if (isPremium) 1 else 0
                val sub = IcmSubscriptionResponse(
                    active = isPremium,
                    expiresAt = expiresAt.takeIf { it > 0L },
                    daysLeft = daysLeft,
                    planType = plan,
                    regions = regions
                )
                _subscription.value = sub
                Result.success(sub)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Регион для поиска/стрима СВЕРЯЕМ С СЕРВЕРОМ — берём /me/region.current.
     *
     * По разбору ICM: регион нельзя хардкодить и нельзя гадать по подписке — запросы
     * на партнёре перенаправляются, и любой зашитый регион (был "us") даёт 404.
     * Правильный регион знает сервер (/me/region.current) — его и ставим как есть,
     * без клиентских предпочтений. Базовый дефолт клиента (IcmApi.defaultRegion="tr")
     * работает, пока сервер ещё не опрошен (до логина / нет сети). Явный выбор в
     * Профиле приоритетнее и ставится там же локально после fetchUserData.
     */
    suspend fun syncRegionFromServer() {
        val current = runCatching { IcmRepository.getUserRegion()?.current }
            .getOrNull()?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: return
        if (!current.equals(IcmRepository.region, ignoreCase = true)) {
            IcmRepository.region = current
            android.util.Log.d("IcmAuthRepository", "Region synced from server -> $current")
        }
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

        // Регион поиска/стрима СВЕРЯЕМ С СЕРВЕРОМ (/me/region.current). Дефолт клиента
        // = "tr" (зашитый "us" давал 404 из-за редиректа запросов на партнёре).
        syncRegionFromServer()

        val profile = profileResult.getOrNull()
        val preferences = prefsResult.getOrNull()

        if (profile != null || preferences != null) {
            Result.success(profile to preferences)
        } else {
            Result.failure(IOException("Failed to fetch user data"))
        }
    }

    // getPartnerKey()/setPartnerKey() УДАЛЕНЫ: партнёрский ключ ICM больше не
    // хранится на устройстве ни в каком виде (prefs/JNI .so/BuildConfig) —
    // его подставляет наш сервер-брокер (см. IcmApi.SERVER_BASE).

    /** Версия/девайс для админки брокера (разбивка версий, учёт устройств). */
    private fun Request.Builder.applyTelemetryHeaders(): Request.Builder {
        com.liquidmusicglass.logging.ClientTelemetry.appVersion
            .takeIf { it.isNotBlank() }?.let { header("X-App-Version", it) }
        com.liquidmusicglass.logging.ClientTelemetry.deviceId
            .takeIf { it.isNotBlank() }?.let { header("X-Device-Id", it) }
        return this
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

}
