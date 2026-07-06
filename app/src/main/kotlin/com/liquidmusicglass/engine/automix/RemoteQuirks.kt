package com.liquidmusicglass.engine.automix

import android.content.Context
import android.content.SharedPreferences
import com.liquidmusicglass.debug.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Удалённый слой карты причуд (этап 2 телеметрии, приёмная половина).
 *
 * Раз в [AudioFleetConfig.QUIRKS_REFRESH_MS] тихо забирает quirks.json по
 * [AudioFleetConfig.QUIRKS_URL], кэширует в prefs и держит распарсенным в
 * памяти. [AudioQuirks.defaultMode] спрашивает этот слой ПЕРВЫМ — удалённые
 * правила сильнее встроенных, поэтому нового кривого вендора можно чинить
 * правкой одного файла: все установки подхватят за сутки без релиза.
 *
 * Полностью пассивен при пустом URL и при любых сетевых ошибках: карта — это
 * ускорение первого запуска, а не критический путь (watchdog всё равно
 * подстрахует локально).
 */
object RemoteQuirks {

    private const val PREFS = "audio_quirks_remote"
    private const val KEY_JSON = "json"
    private const val KEY_FETCHED_AT = "fetched_at"

    @Volatile private var models: Map<String, Int> = emptyMap()
    @Volatile private var families: Map<String, Int> = emptyMap()
    @Volatile private var version: Int = 0

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(12, TimeUnit.SECONDS)
            .build()
    }

    /** Синхронно поднять кэш прошлого запуска (дёшево: prefs + маленький JSON).
     *  Зовётся ДО AppSettings.init, чтобы auto-режим резолвился уже с картой. */
    fun preload(context: Context) {
        if (AudioFleetConfig.QUIRKS_URL.isBlank()) return
        val p = prefs(context)
        p.getString(KEY_JSON, null)?.let { cached ->
            runCatching { apply(cached) }
        }
        // Сетевое обновление — в фоне, с троттлингом.
        val age = System.currentTimeMillis() - p.getLong(KEY_FETCHED_AT, 0L)
        if (age > AudioFleetConfig.QUIRKS_REFRESH_MS) refreshAsync(context)
    }

    private fun refreshAsync(context: Context) {
        scope.launch {
            runCatching {
                val resp = http.newCall(
                    Request.Builder().url(AudioFleetConfig.QUIRKS_URL).build()
                ).execute()
                resp.use {
                    if (!it.isSuccessful) return@launch
                    val body = it.body?.string() ?: return@launch
                    if (body.length > 64_000) return@launch   // защита от мусора
                    apply(body)                                // валидация парсом
                    prefs(context).edit()
                        .putString(KEY_JSON, body)
                        .putLong(KEY_FETCHED_AT, System.currentTimeMillis())
                        .apply()
                    DebugLog.add("QUIRKS remote v$version: ${models.size} models, ${families.size} families")
                }
            }
        }
    }

    private fun apply(json: String) {
        val root = JSONObject(json)
        version = root.optInt("version", 0)
        models = root.optJSONObject("models")?.toModeMap() ?: emptyMap()
        families = root.optJSONObject("families")?.toModeMap() ?: emptyMap()
    }

    private fun JSONObject.toModeMap(): Map<String, Int> {
        val out = LinkedHashMap<String, Int>()
        keys().forEach { k ->
            val v = optInt(k, -1)
            if (v in 0..6) out[k.lowercase()] = v
        }
        return out
    }

    /** Режим по модели (подстрока в MODEL+DEVICE, lowercase) или null. */
    fun modeForModel(modelId: String): Int? =
        models.entries.firstOrNull { (sub, _) -> sub in modelId }?.value

    /** Режим по семейству (подстрока в MANUFACTURER+BRAND, lowercase) или null. */
    fun modeForFamily(familyId: String): Int? =
        families.entries.firstOrNull { (sub, _) -> sub in familyId }?.value

    fun describe(): String =
        if (AudioFleetConfig.QUIRKS_URL.isBlank()) "remote=off"
        else "remote v$version (${models.size}m/${families.size}f)"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
