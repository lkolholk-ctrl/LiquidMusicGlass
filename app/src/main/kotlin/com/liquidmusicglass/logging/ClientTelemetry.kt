package com.liquidmusicglass.logging

import android.content.Context
import com.liquidmusicglass.api.icm.IcmApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Лёгкая телеметрия для админки брокера (см. серверный /lmg/client-log):
 *
 *  - стабильный per-install device-id (X-Device-Id) + версия приложения
 *    (X-App-Version) — сервер группирует девайсы и показывает разбивку версий;
 *  - догрузка крэш-логов на старте: CrashHandler пишет их в crash_logs/, мы
 *    fire-and-forget шлём краткую выжимку на брокер (файлы НЕ трогаем — экран
 *    краша работает как раньше; повторную отправку гасит prefs-набор имён).
 *
 * Никакой аналитики поведения: только крэши и версия. Всё best-effort —
 * ни одна ошибка тут не должна влиять на приложение.
 */
object ClientTelemetry {

    private const val PREFS = "client_telemetry"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_UPLOADED = "uploaded_crashes"

    @Volatile
    var deviceId: String = ""
        private set

    @Volatile
    var appVersion: String = ""
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    fun init(context: Context) {
        val app = context.applicationContext
        appVersion = runCatching { com.liquidmusicglass.BuildConfig.VERSION_NAME }.getOrDefault("")
        val p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        deviceId = p.getString(KEY_DEVICE_ID, null) ?: java.util.UUID.randomUUID()
            .toString().replace("-", "").take(16)
            .also { p.edit().putString(KEY_DEVICE_ID, it).apply() }
    }

    /**
     * Отправить на брокер крэши, накопленные с прошлого запуска. Файлы не
     * удаляем (их показывает/чистит экран краша) — от повторной отправки
     * защищает список уже отправленных имён в prefs (кап 20).
     */
    fun uploadPendingCrashes(context: Context) {
        val app = context.applicationContext
        scope.launch {
            runCatching {
                val p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val uploaded = p.getStringSet(KEY_UPLOADED, emptySet())!!.toMutableSet()
                val dir = File(app.filesDir, "crash_logs")
                val files = dir.listFiles()
                    ?.filter { it.isFile && !it.name.startsWith("ui_freeze_") && it.name !in uploaded }
                    ?.sortedBy { it.lastModified() }
                    ?.take(5)   // за раз не больше 5 — квота брокера 10/мин/IP
                    ?: return@launch
                for (f in files) {
                    val text = runCatching { f.readText() }.getOrNull() ?: continue
                    // Краткая выжимка: тип креша из первых строк, стек — как есть (сервер режет до 4000).
                    val message = text.lineSequence()
                        .firstOrNull { it.startsWith("Exception:") || it.startsWith("signal") }
                        ?.take(300) ?: f.name
                    if (send(tag = f.name.substringBefore('_') + "_crash", message = message, stack = text)) {
                        uploaded.add(f.name)
                    }
                }
                // Кап набора: старые имена (файлы давно удалены) выкидываем.
                val capped = uploaded.toList().takeLast(20).toSet()
                p.edit().putStringSet(KEY_UPLOADED, capped).apply()
            }
        }
    }

    /** Синхронная отправка одной записи (звать с IO). true = принято сервером. */
    private fun send(tag: String, message: String, stack: String?): Boolean {
        return runCatching {
            val body = JSONObject().apply {
                put("level", "crash")
                put("tag", tag.take(64))
                put("message", message.take(500))
                if (!stack.isNullOrBlank()) put("stack", stack.take(4000))
                put("version", appVersion)
            }
            val req = Request.Builder()
                .url("${IcmApi.SERVER_BASE}/client-log")
                .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .apply {
                    if (deviceId.isNotBlank()) header("X-Device-Id", deviceId)
                    if (appVersion.isNotBlank()) header("X-App-Version", appVersion)
                    IcmApi.getInstance().partnerUserId?.let { header("X-Partner-User-Id", it) }
                }
                .build()
            http.newCall(req).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }
}
