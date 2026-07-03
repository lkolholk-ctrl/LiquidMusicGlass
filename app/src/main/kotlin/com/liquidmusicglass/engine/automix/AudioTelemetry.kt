package com.liquidmusicglass.engine.automix

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.liquidmusicglass.debug.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Анонимная телеметрия аудио-выхода (этап 2, передающая половина).
 *
 * Отправляет РОВНО одно: «на такой-то модели работает такой-то режим» —
 * модель/Android/режим/срабатывания watchdog/версия приложения. Ни ID, ни
 * треков, ни чего-либо личного. Из этих отчётов строится карта quirks.json:
 * первые владельцы новой модели «разведывают» её watchdog'ом, остальные
 * получают готовый режим с первого запуска.
 *
 * Механика: через 90с после подъёма движка (когда watchdog уже успел бы
 * сработать и режим устоялся) шлётся один отчёт на сессию — и только если
 * связка (устройство+версия+режим+watchdog) изменилась с прошлой отправки,
 * поэтому в типичной жизни это ноль-один запрос на обновление приложения.
 * Полностью выключена при пустом [AudioFleetConfig.REPORT_URL]; уважает
 * тумблер telemetry_enabled в prefs. Ошибки сети молча игнорируются.
 */
object AudioTelemetry {

    private const val REPORT_DELAY_MS = 90_000L

    @Volatile private var appContext: Context? = null
    private val watchdogFires = AtomicInteger(0)
    @Volatile private var reportScheduled = false

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val main = Handler(Looper.getMainLooper())
    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** Движок поднялся: через 90с зафиксировать устоявшийся режим (раз в сессию). */
    fun onEngineStarted() {
        if (!enabled() || reportScheduled) return
        reportScheduled = true
        main.postDelayed({ sendIfChanged() }, REPORT_DELAY_MS)
    }

    /** Watchdog переключил режим — считаем для отчёта (и это сигнал сам по себе). */
    fun onWatchdogEscalated() {
        watchdogFires.incrementAndGet()
    }

    private fun enabled(): Boolean {
        if (AudioFleetConfig.REPORT_URL.isBlank()) return false
        val ctx = appContext ?: return false
        return ctx.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            .getBoolean("telemetry_enabled", true)
    }

    private fun sendIfChanged() {
        val ctx = appContext ?: return
        if (!enabled()) return

        val mode = com.liquidmusicglass.engine.AppSettings.audioCompatMode.value
        val fires = watchdogFires.get()
        val app = runCatching {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
        }.getOrDefault("?") ?: "?"

        val signature = "$mode|$fires|$app|${Build.MODEL}"
        val p = ctx.getSharedPreferences("audio_telemetry", Context.MODE_PRIVATE)
        if (p.getString("last_sent", null) == signature) return   // ничего нового

        val fields = AudioFleetConfig.REPORT_FIELDS
        val body = FormBody.Builder().apply {
            fields["device"]?.takeIf { it.isNotBlank() }?.let {
                add(it, "${Build.MANUFACTURER}/${Build.BRAND} ${Build.MODEL} (${Build.DEVICE})")
            }
            fields["android"]?.takeIf { it.isNotBlank() }?.let {
                add(it, "${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            }
            fields["mode"]?.takeIf { it.isNotBlank() }?.let { add(it, mode.toString()) }
            fields["watchdog"]?.takeIf { it.isNotBlank() }?.let { add(it, fires.toString()) }
            fields["app"]?.takeIf { it.isNotBlank() }?.let { add(it, app) }
        }.build()

        scope.launch {
            runCatching {
                http.newCall(
                    Request.Builder().url(AudioFleetConfig.REPORT_URL).post(body).build()
                ).execute().use { resp ->
                    // «Отправлено» помечаем ТОЛЬКО при HTTP 2xx: неопубликованная
                    // Google Form отвечает заглушкой, и раньше такой ответ навсегда
                    // гасил повторную отправку этой связки.
                    if (resp.isSuccessful) {
                        p.edit().putString("last_sent", signature).apply()
                        DebugLog.add("TELEMETRY sent: mode=$mode watchdog=$fires")
                    } else {
                        DebugLog.add("TELEMETRY rejected: HTTP ${resp.code} — повтор в следующей сессии")
                    }
                }
            }
        }
    }
}
