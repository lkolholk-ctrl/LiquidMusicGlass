package com.liquidmusicglass.data.yandex

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Хранение OAuth-токена Яндекс.Музыки + статус «подключён».
 *
 * Токен:
 *  - лежит в [EncryptedSharedPreferences] (AES-256, ключ в Android Keystore);
 *  - **не** пишется в logcat / DebugLog / exception message;
 *  - не возвращается наружу API-слоя (только [clientOrNull] / [hasToken]).
 *
 * UI: вкладка Playlists → Yandex Music.
 * Гайд по токену: https://github.com/MarshalX/yandex-music-api/discussions/513
 */
object YandexAuthRepository {

    private const val TAG = "YandexAuth"

    /** Encrypted prefs name (values encrypted at rest). */
    private const val PREFS_SECURE = "yandex_music_auth_secure"

    /** Legacy plaintext prefs — читаем один раз для миграции, затем чистим. */
    private const val PREFS_LEGACY = "yandex_music_auth"

    private const val KEY_TOKEN = "oauth_token"
    private const val KEY_UID = "account_uid"
    private const val KEY_LOGIN = "account_login"
    private const val KEY_DISPLAY = "account_display_name"
    private const val KEY_PLUS = "account_has_plus"
    private const val KEY_QUALITY = "stream_quality"

    private var prefs: SharedPreferences? = null
    private var appContext: Context? = null

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _displayLabel = MutableStateFlow<String?>(null)
    /** Логин или displayName для подписи «Connected as …» (не токен). */
    val displayLabel: StateFlow<String?> = _displayLabel.asStateFlow()

    private val _hasPlus = MutableStateFlow(false)
    /** Активная подписка Яндекс Плюс — скачивание полных треков без неё не работает. */
    val hasPlus: StateFlow<Boolean> = _hasPlus.asStateFlow()

    private val _quality = MutableStateFlow(YandexQuality.HIGH)
    /** Выбранное качество стрима/скачивания (персистентно). */
    val quality: StateFlow<YandexQuality> = _quality.asStateFlow()

    fun setQuality(q: YandexQuality) {
        _quality.value = q
        prefs?.edit()?.putString(KEY_QUALITY, q.name)?.apply()
    }

    /** Эффективное качество: LOSSLESS доступен только с Плюсом, иначе HIGH. */
    fun effectiveQuality(): YandexQuality {
        val q = _quality.value
        return if (q == YandexQuality.LOSSLESS && !_hasPlus.value) YandexQuality.HIGH else q
    }

    fun init(context: Context) {
        appContext = context.applicationContext
        prefs = openSecurePrefs(context.applicationContext)
        if (prefs != null) {
            migrateFromLegacyPlaintext(context.applicationContext)
        } else {
            // Нечего мигрировать в secure — legacy plaintext всё равно вычищаем
            clearLegacyPlaintext(context.applicationContext)
        }
        val has = !readTokenRaw().isNullOrBlank()
        _isConnected.value = has
        _displayLabel.value = if (has) readLabel() else null
        _hasPlus.value = has && prefs?.getBoolean(KEY_PLUS, false) == true
        _quality.value = prefs?.getString(KEY_QUALITY, null)
            ?.let { runCatching { YandexQuality.valueOf(it) }.getOrNull() }
            ?: YandexQuality.HIGH
        // Только факт наличия — без значения токена
        Log.i(TAG, if (has) "session restored (encrypted)" else "no saved session")
    }

    fun hasToken(): Boolean = !readTokenRaw().isNullOrBlank()

    /** uid линкованного аккаунта (для `/users/{uid}/…` ручек), или null. */
    fun uidOrNull(): Long? = prefs?.getLong(KEY_UID, 0L)?.takeIf { it != 0L }

    /**
     * Клиент с текущим токеном или null.
     * Сам токен наружу не отдаём — только готовый [YandexMusicClient].
     */
    fun clientOrNull(): YandexMusicClient? {
        val t = readTokenRaw() ?: return null
        return YandexMusicClient(t)
    }

    /**
     * Проверить токен через API, сохранить в encrypted prefs при успехе.
     * @return label аккаунта (login / name / uid) — **не** токен
     */
    suspend fun connect(token: String): String = withContext(Dispatchers.IO) {
        val clean = token.trim()
        if (clean.isBlank()) throw YandexMusicException("Empty token")

        // Не логируем clean / token length-detailed — только исход попытки
        Log.i(TAG, "connect: validating token…")

        val client = YandexMusicClient(clean)
        val account = try {
            client.fetchAccount()
        } catch (e: YandexUnauthorizedException) {
            Log.w(TAG, "connect: rejected (unauthorized)")
            throw e
        } catch (e: Exception) {
            // message API/сети без токена
            Log.w(TAG, "connect: failed (${e.javaClass.simpleName})")
            throw e
        }

        val label = account.displayName
            ?: account.login
            ?: "uid ${account.uid}"

        val p = prefs ?: throw YandexMusicException("Auth storage not initialized")
        p.edit()
            .putString(KEY_TOKEN, clean)
            .putLong(KEY_UID, account.uid)
            .putString(KEY_LOGIN, account.login)
            .putString(KEY_DISPLAY, account.displayName)
            .putBoolean(KEY_PLUS, account.hasPlus)
            .apply()

        // На всякий: если legacy ещё жив — вычистить plaintext
        clearLegacyPlaintext(appContext)

        _isConnected.value = true
        _displayLabel.value = label
        _hasPlus.value = account.hasPlus
        Log.i(TAG, "connect: ok (uid=${account.uid}, plus=${account.hasPlus})")
        label
    }

    /**
     * Обновить сведения об аккаунте (label, статус Яндекс Плюс) по сохранённому
     * токену. Плюс может истечь/появиться после подключения — зовём при входе на
     * экран Яндекса. Ошибка сети — не страшно, остаёмся на кеше; 401 — сессия
     * мертва, отключаемся.
     */
    suspend fun refreshAccountInfo() = withContext(Dispatchers.IO) {
        val client = clientOrNull() ?: return@withContext
        val account = try {
            client.fetchAccount()
        } catch (e: YandexUnauthorizedException) {
            Log.w(TAG, "refresh: token no longer valid")
            disconnect()
            return@withContext
        } catch (_: Exception) {
            return@withContext
        }
        prefs?.edit()
            ?.putLong(KEY_UID, account.uid)
            ?.putString(KEY_LOGIN, account.login)
            ?.putString(KEY_DISPLAY, account.displayName)
            ?.putBoolean(KEY_PLUS, account.hasPlus)
            ?.apply()
        _displayLabel.value = account.displayName
            ?: account.login
            ?: "uid ${account.uid}"
        _hasPlus.value = account.hasPlus
    }

    fun disconnect() {
        prefs?.edit()?.clear()?.apply()
        clearLegacyPlaintext(appContext)
        _isConnected.value = false
        _displayLabel.value = null
        _hasPlus.value = false
        Log.i(TAG, "disconnected")
    }

    // ── storage helpers ──────────────────────────────────────

    /**
     * Только EncryptedSharedPreferences — **без** plaintext fallback.
     * Если Keystore недоступен, prefs остаётся null: connect() упадёт с ошибкой,
     * токен на диск не пишется.
     */
    private fun openSecurePrefs(context: Context): SharedPreferences? {
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                PREFS_SECURE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (e: Exception) {
            Log.e(TAG, "EncryptedSharedPreferences unavailable (${e.javaClass.simpleName}) — token will not be stored")
            null
        }
    }

    /** Прочитать токен. Только внутри object — наружу не экспортируется. */
    private fun readTokenRaw(): String? =
        prefs?.getString(KEY_TOKEN, null)?.takeIf { it.isNotBlank() }

    private fun readLabel(): String? {
        val p = prefs ?: return null
        if (p.getString(KEY_TOKEN, null).isNullOrBlank()) return null
        return p.getString(KEY_DISPLAY, null)?.takeIf { it.isNotBlank() }
            ?: p.getString(KEY_LOGIN, null)?.takeIf { it.isNotBlank() }
            ?: p.getLong(KEY_UID, 0L).takeIf { it != 0L }?.let { "uid $it" }
    }

    /**
     * Одноразовая миграция: старый plaintext `yandex_music_auth` → encrypted.
     * После успеха legacy-файл очищается.
     */
    private fun migrateFromLegacyPlaintext(context: Context) {
        if (!readTokenRaw().isNullOrBlank()) {
            // Уже в secure — только подчистить legacy, если остался
            clearLegacyPlaintext(context)
            return
        }
        val legacy = try {
            context.getSharedPreferences(PREFS_LEGACY, Context.MODE_PRIVATE)
        } catch (_: Exception) {
            return
        }
        val oldToken = legacy.getString(KEY_TOKEN, null)?.takeIf { it.isNotBlank() } ?: run {
            clearLegacyPlaintext(context)
            return
        }
        val p = prefs ?: return
        p.edit()
            .putString(KEY_TOKEN, oldToken)
            .putLong(KEY_UID, legacy.getLong(KEY_UID, 0L))
            .putString(KEY_LOGIN, legacy.getString(KEY_LOGIN, null))
            .putString(KEY_DISPLAY, legacy.getString(KEY_DISPLAY, null))
            .apply()
        clearLegacyPlaintext(context)
        Log.i(TAG, "migrated session from legacy storage → encrypted")
    }

    private fun clearLegacyPlaintext(context: Context?) {
        if (context == null) return
        try {
            context.getSharedPreferences(PREFS_LEGACY, Context.MODE_PRIVATE)
                .edit().clear().apply()
            // deleteSharedPreferences API 24+
            context.deleteSharedPreferences(PREFS_LEGACY)
        } catch (_: Exception) {
            // ignore
        }
    }
}
