package com.liquidmusicglass.api.icm

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.io.IOException
import kotlin.coroutines.resumeWithException

/**
 * Internal file logger for IcmApi — writes to app cache dir so logs survive
 * even when system logcat is encrypted (Honor, etc.).
 */
object IcmApiFileLogger {
    private var logFile: File? = null
    private var rotatedFile: File? = null
    // dateFormat используется ТОЛЬКО на writer-потоке (SimpleDateFormat не
    // потокобезопасен — раньше format() звался с main/IO одновременно).
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    /** Ротация по размеру: файл рос ВЕЧНО (2-4 строки на каждый запрос), и
     *  «Copy ICM logs» на старом инсталле читал десятки МБ на main → ANR. */
    private const val MAX_LOG_BYTES = 256L * 1024

    // Выделенный writer-поток (P1, аудит): log() зовётся из горячих путей —
    // в т.ч. из invokeOnCancellation при отмене запроса, а отменяет часто MAIN
    // (searchJob?.cancel() на каждый keystroke) — диск на main на каждую букву.
    // Теперь log() лишь ставит запись в очередь; пишет фоновый демон.
    private val writer = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "icm-file-log").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }

    fun init(context: Context) {
        // filesDir (внутреннее, всегда смонтировано), а НЕ getExternalFilesDir:
        // первый getExternalFilesDir делает binder-IPC в StorageManager + FUSE-
        // резолв тома, что на Honor/HyperOS холодным стартом висит на потоке. Для
        // «Copy ICM logs» внутреннего app-private хранилища достаточно.
        val dir = File(context.filesDir, "icm_logs")
        dir.mkdirs()
        logFile = File(dir, "icm_api.log")
        rotatedFile = File(dir, "icm_api.log.1")
    }

    fun log(level: String, tag: String, message: String) {
        // Echo в logcat сразу (дёшево, на потоке вызова)
        when (level) {
            "D" -> android.util.Log.d(tag, message)
            "E" -> android.util.Log.e(tag, message)
            "W" -> android.util.Log.w(tag, message)
            "I" -> android.util.Log.i(tag, message)
        }
        val ts = System.currentTimeMillis()
        try {
            writer.execute {
                try {
                    val f = logFile ?: return@execute
                    if (f.length() > MAX_LOG_BYTES) {
                        rotatedFile?.let { rot -> rot.delete(); f.renameTo(rot) }
                    }
                    f.appendText("[${dateFormat.format(Date(ts))}] $level/$tag: $message\n")
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {} // executor мог быть погашен — лог не роняет приложение
    }

    fun getLogPath(): String? = logFile?.absolutePath

    fun getRecentLogs(maxLines: Int = 200): String {
        return try {
            // Оба файла ограничены MAX_LOG_BYTES — чтение хвоста дёшево и БЕЗ
            // риска многосекундного readLines на распухшем файле.
            val lines = mutableListOf<String>()
            rotatedFile?.takeIf { it.isFile }?.let { lines += it.readLines() }
            logFile?.takeIf { it.isFile }?.let { lines += it.readLines() }
            lines.takeLast(maxLines).joinToString("\n").ifBlank { "No logs" }
        } catch (_: Exception) { "No logs" }
    }

    fun clear() {
        try {
            rotatedFile?.delete()
            logFile?.writeText("")
        } catch (_: Exception) {}
    }
}

/**
 * ICM Music client — через НАШ серверный брокер (см. [SERVER_BASE]).
 *
 * Партнёрский ключ ICM живёт ТОЛЬКО на сервере: брокер подставляет
 * X-Partner-Key сам, минтит сессии (/session/refresh) и владеет premium
 * (/me/subscription). Прямых обращений к byicloud.online из APK больше нет —
 * секрет с устройства убран, форк без нашего сервера не работает.
 *
 * Клиент шлёт только Bearer session-token и X-Partner-User-Id.
 * Дока партнёрки (пути под /icm те же): https://byicloud.online/partners/api-docs
 */
class IcmApi private constructor() {

    companion object {
        /** Наш серверный брокер (ключ ICM, сессии, premium, конфиг). */
        const val SERVER_BASE = "https://api.gsgit.org/lmg"

        // Реверс-прокси партнёрки: относительные пути (search, track, wave,
        // me) НЕ меняются — меняется только база. ВАЖНО: в KDoc здесь нельзя
        // писать глоб-пути вида «слэш-звёздочка» — Kotlin-комментарии
        // ВЛОЖЕННЫЕ, такая последовательность открывает вложенный комментарий
        // и молча съедает остаток файла (уже наступили).
        const val BASE_URL = "$SERVER_BASE/icm"

        @Volatile
        private var instance: IcmApi? = null

        fun getInstance(): IcmApi {
            return instance ?: synchronized(this) {
                instance ?: IcmApi().also { instance = it }
            }
        }

        fun resetInstance() {
            instance = null
        }
    }

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    private val client by lazy {
        // Явный Dispatcher: ограничиваем число одновременных запросов (всё идёт на
        // один хост byicloud.online). Реальный темп держит IcmRateGate, а это —
        // страховка от переполнения пула при залпах резолвов/префетча.
        // maxRequestsPerHost снижен 8→5: на холодном старте летит залп запросов к
        // byicloud, и пока ни одно HTTP/2-соединение не установлено, OkHttp открывал
        // ДО 8 параллельных TLS-handshake'ов. На медленной/«подрезанной» сети они
        // зависали пачкой → ложное «No internet connection». С 5 — гонок меньше +
        // мёртвый хост не сожрёт весь пул; остальное мультиплексируется по HTTP/2.
        val dispatcher = okhttp3.Dispatcher().apply {
            maxRequests = 12
            maxRequestsPerHost = 5
        }
        OkHttpClient.Builder()
            // Чуть щедрее к долгому хендшейку (туннели/дальние маршруты): 5с
            // connect давал ложные фейлы на ровном месте при высокой латентности.
            .connectTimeout(7, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .writeTimeout(8, TimeUnit.SECONDS)
            // callTimeout ограничивает ВЕСЬ вызов (connect+TLS+retry+ответ). 12с — чтобы
            // при мёртвой/медленной сети запрос падал БЫСТРО (юзер не думает, что
            // приложение встало колом), а зависшие коннекты не копились.
            .callTimeout(12, TimeUnit.SECONDS)
            // ВКЛючаем штатное восстановление соединения OkHttp: прозрачный повтор на
            // протухших keep-alive соединениях (сервер закрыл сокет) и перебор маршрутов
            // (IPv6→IPv4). Без этого единичный мёртвый маршрут/протухший коннект давал
            // «failed to connect» → ложное «No internet connection», хотя сеть жива.
            // Это НЕ дублирует наш интерсептор: тот ретраит 5xx/таймауты, а это —
            // выбор живого маршрута внутри одной попытки (4xx по-прежнему не ретраятся).
            .retryOnConnectionFailure(true)
            .dispatcher(dispatcher)
            .connectionPool(okhttp3.ConnectionPool(5, 30, TimeUnit.SECONDS))
            .protocols(listOf(okhttp3.Protocol.HTTP_2, okhttp3.Protocol.HTTP_1_1))
            .addInterceptor { chain ->
                val request = chain.request()
                // «Быстрый» путь (sync-резолв на потоке ExoPlayer): короткий таймаут.
                val fast = request.header("X-LMG-Fast") == "1"
                // Внутренний маркер на сервер не отправляем.
                val outRequest = if (fast) request.newBuilder().removeHeader("X-LMG-Fast").build() else request
                val activeChain = if (fast) {
                    chain.withConnectTimeout(5, TimeUnit.SECONDS)
                        .withReadTimeout(5, TimeUnit.SECONDS)
                        .withWriteTimeout(5, TimeUnit.SECONDS)
                } else chain
                // Ретрай транзиентных 5xx с бэкоффом ПЕРЕНЕСЁН в coroutine-слой
                // (executeWithRetry + delay()). Раньше здесь был Thread.sleep(backoff),
                // державший поток OkHttp Dispatcher'а во время паузы — при пачке
                // запросов это подъедало пул и тормозило вход в приложение (полевой
                // лог: поток OkHttp в intercept→Thread.sleep). Интерсептор теперь
                // делает РОВНО один proceed; connection-level ретраи покрывает
                // retryOnConnectionFailure(true) нативно, без ручного sleep.
                activeChain.proceed(outRequest)
            }
            .build()
    }

    /**
     * Сбросить сетевое состояние при СМЕНЕ сети (Wi-Fi↔моб., VPN вкл/выкл).
     * Соединения в пуле привязаны к прежнему маршруту и после переключения мертвы —
     * приложение продолжало долбиться в них и «ничего не грузило». Вызывать из
     * networkCallback при появлении/смене активной сети.
     */
    fun evictConnections() {
        try {
            client.dispatcher.cancelAll()      // отменяем зависшие вызовы на мёртвых соединениях
            client.connectionPool.evictAll()   // выкидываем протухшие соединения из пула
            IcmRateGate.reset()                // снимаем локальный circuit-breaker бан
        } catch (_: Throwable) {}
    }

    private val mediaTypeJson = "application/json; charset=utf-8".toMediaType()

    // apiKey УДАЛЁН: партнёрский ключ живёт только на сервере-брокере.

    /** Session token (JWT) — единственная клиентская авторизация */
    @Volatile
    var sessionToken: String? = null

    /** Дефолтный регион ДО опроса сервера. "tr", не "us": по разбору ICM запросы
     *  на партнёре перенаправляются, и зашитый "us" давал 404. После логина регион
     *  сверяется с сервером — /me/region.current (IcmAuthRepository.syncRegionFromServer). */
    var defaultRegion: String = "tr"

    /** Stream quality: "128K", "256K", "320K", "ALAC" or null for default */
    var streamQuality: String? = IcmStreamQuality.K256

    /** Partner user id for analytics and per-user settings (X-Partner-User-Id) */
    var partnerUserId: String? = null

    /** Callback for X-Request-Id tracing */
    var onRequestId: ((String) -> Unit)? = null

    /** Перевыпуск session-токена для авто-рефреша на 401 (ставит IcmAuthRepository).
     *  Возвращает СВЕЖИЙ токен или null, если перевыпуск невозможен. */
    var sessionRefresher: (suspend () -> String?)? = null

    private inline fun <reified T> parseResponse(body: okhttp3.ResponseBody?): T {
        val text = body?.string() ?: throw IcmApiException(0, "Empty response body")
        return json.decodeFromString(text)
    }

    private fun extractRequestId(response: okhttp3.Response): String? {
        return response.header("X-Request-Id")
    }

    /** Числовой trackId для строк ошибок /track: раньше в логе был только rid
     *  (X-Request-Id ответа), а сам айди трека жил в POST-body и в диагностику
     *  не попадал — VVS не мог сматчить битые треки по логу. */
    private fun trackIdForLog(endpoint: String, body: String?): String {
        if (!endpoint.startsWith("/track") || body == null) return ""
        val m = Regex("\"trackId\":\"([^\"]+)\"").find(body) ?: return ""
        return " trackId=${m.groupValues[1]}"
    }

    /** Links a coroutine cancellation to the underlying OkHttp call. */
    private suspend fun Call.awaitResponse(endpoint: String): Response = suspendCancellableCoroutine { continuation ->
        IcmApiFileLogger.log("D", "IcmApi", "Request started $endpoint")
        continuation.invokeOnCancellation {
            IcmApiFileLogger.log("D", "IcmApi", "Request cancelled; Call.cancel() $endpoint")
            cancel()
        }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!continuation.isCancelled) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (continuation.isCancelled) {
                    response.close()
                } else {
                    continuation.resume(response) { _, value, _ -> value.close() }
                }
            }
        })
    }

    /**
     * Выполнить запрос с ретраем ТРАНЗИЕНТНЫХ сбоев (5xx / IOException) и бэкоффом
     * через delay() — в coroutine, а НЕ через Thread.sleep в OkHttp-интерсепторе
     * (тот держал поток Dispatcher'а). 4xx (в т.ч. 429) НЕ ретраим — повтор лишь
     * усугубляет бан; их отсекает и circuit-breaker (IcmRateGate). fast-запросы
     * (X-LMG-Fast, sync-резолв на потоке ExoPlayer) — без ретрая (1 попытка).
     *
     * Отменяемость: delay() отменяем сам; CancellationException пробрасываем сразу
     * (не глотаем как IOException), чтобы отмена ICM-запроса завершалась мгновенно.
     */
    private suspend fun executeWithRetry(request: Request, endpoint: String): Response {
        val maxRetries = if (request.header("X-LMG-Fast") == "1") 1 else 2
        var attempt = 0
        while (true) {
            try {
                val response = client.newCall(request).awaitResponse(endpoint)
                // НИКОГДА не ретраим 4xx; 5xx — только пока есть попытки.
                if (response.isSuccessful || response.code < 500 || attempt >= maxRetries - 1) {
                    return response
                }
                response.close()   // не течём телом 5xx перед повтором
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e            // отмена coroutine — сразу наружу
            } catch (e: IOException) {
                if (attempt >= maxRetries - 1) throw e
            }
            attempt++
            // Экспоненциальный бэкофф + джиттер, но через ОТМЕНЯЕМЫЙ delay() —
            // поток OkHttp Dispatcher'а во время паузы НЕ держим.
            val backoff = 250L * (1L shl (attempt - 1)) + kotlin.random.Random.nextLong(0, 120)
            kotlinx.coroutines.delay(backoff)
        }
    }

    /** Относительные эндпоинты идут на прокси партнёрки ([BASE_URL]); абсолютные
     *  (серверные /lmg/session|auth|me — начинаются с http) — как есть. */
    private fun resolveUrl(endpoint: String, async: Boolean): String {
        val base = if (endpoint.startsWith("http")) endpoint else "$BASE_URL$endpoint"
        return if (async) "$base?async=1" else base
    }

    private fun buildRequest(url: String, method: String = "GET", body: String? = null, fast: Boolean = false): Request {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", "LiquidMusicGlass/1.0")
            .header("Accept", "application/json")

        // Помечаем «быстрые» вызовы (sync-резолв на потоке загрузчика ExoPlayer):
        // интерсептор даст им короткий таймаут и НИ ОДНОГО ретрая, чтобы загрузчик
        // не висел до 30с при медленном/банящем сервере.
        if (fast) {
            builder.header("X-LMG-Fast", "1")
        }

        // Auth: Bearer session-token + X-Partner-User-Id. X-Partner-Key УДАЛЁН
        // из клиента полностью — партнёрский ключ подставляет наш сервер
        // (секрет в APK не живёт). S2S-эндпоинты с устройства больше не
        // вызываются: сессии/email-линк идут на серверные /lmg/*-маршруты.
        sessionToken?.let {
            builder.header("Authorization", "Bearer $it")
        }

        partnerUserId?.let {
            builder.header("X-Partner-User-Id", it)
        }

        if (body != null) {
            val requestBody = body.toRequestBody(mediaTypeJson)
            builder.method(method, requestBody)
        } else if (method != "GET") {
            builder.method(method, "".toRequestBody(null))
        }

        return builder.build()
    }

    private suspend inline fun <reified T> execute(
        endpoint: String,
        method: String = "GET",
        body: String? = null,
        async: Boolean = false
    ): Result<T> {
        val first = executeOnce<T>(endpoint, method, body, async)
        // Session-токен живёт ~1ч, а раньше обновлялся ТОЛЬКО на старте
        // приложения: через час слушания все юзерские вызовы (волна, лайки,
        // playback-лог) падали 401 до перезапуска. Теперь: 401 при активном
        // токене → перевыпуск через sessionRefresher и ОДИН повтор запроса.
        val e = first.exceptionOrNull()
        if (e is IcmApiException && e.code == 401 && sessionToken != null) {
            val fresh = try {
                sessionRefresher?.invoke()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (!fresh.isNullOrBlank() && fresh != sessionToken) {
                sessionToken = fresh
                return executeOnce(endpoint, method, body, async)
            }
        }
        // ВНЕШНИЙ 5xx-ретрай /track УДАЛЁН (P1, аудит): транзиентные 5xx уже
        // ретраит executeWithRetry внутри executeOnce (2 HTTP-попытки с
        // бэкоффом). Два несогласованных слоя давали до 6 HTTP-запросов на
        // ОДИН логический getTrack при устойчивом 502 — каждый жёг токен
        // RateGate (5/с) и раскручивал шторм (полевой лог: retry #1/#2 +
        // серия 502/404). Устойчивый 5xx теперь быстро фейлится и копится в
        // 5xx-streak circuit-breaker'а — шторм гаснет сам.
        return first
    }

    /**
     * Raw JSON request for feature-specific API wrappers that live outside this
     * file but must still reuse the same auth, rate-gate, timeout and token
     * refresh behavior as the core ICM client.
     */
    internal suspend fun requestJson(
        endpoint: String,
        method: String = "GET",
        body: String? = null,
        async: Boolean = false
    ): Result<String> {
        val first = requestJsonOnce(endpoint, method, body, async)
        val e = first.exceptionOrNull()
        if (e is IcmApiException && e.code == 401 && sessionToken != null) {
            val fresh = try {
                sessionRefresher?.invoke()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (!fresh.isNullOrBlank() && fresh != sessionToken) {
                sessionToken = fresh
                return requestJsonOnce(endpoint, method, body, async)
            }
        }
        return first
    }

    private suspend fun requestJsonOnce(
        endpoint: String,
        method: String = "GET",
        body: String? = null,
        async: Boolean = false
    ): Result<String> {
        if (IcmRateGate.isBanned()) {
            return Result.failure(
                IcmApiException(429, "rate-limited (local gate)", "ip_temporarily_blocked", null, IcmRateGate.bannedForSeconds().coerceAtLeast(1), null)
            )
        }
        // Throttle ВНЕ withTimeoutOrNull (P1, аудит): ожидание токена засчитывалось
        // в 14с «сетевого» таймаута; при шторме очередь за токенами превышала 14с →
        // запросы «фейлились», НЕ выйдя в сеть, каждый звал recordFailure → 4 подряд
        // = 15с самобан. Локальный троттлинг — не сетевой фейл.
        IcmRateGate.throttle()
        return withTimeoutOrNull(14_000L) {
            withContext(Dispatchers.IO) {
                try {
                    val url = resolveUrl(endpoint, async)
                    val request = buildRequest(url, method, body)
                    val response = executeWithRetry(request, endpoint)
                    try {
                    // 5xx НЕ сбрасывает счётчик фейлов (аудит): «ответ получен» при
                    // устойчивом 502-шторме держал breaker закрытым — шторм ничем не
                    // гасился. 5xx копится как фейл → streak открывает breaker.
                    if (response.code < 500) IcmRateGate.recordSuccess() else IcmRateGate.recordFailure()
                    com.liquidmusicglass.engine.NetworkVitality.onRequestSuccess()

                    val requestId = extractRequestId(response)
                    requestId?.let { onRequestId?.invoke(it) }

                    return@withContext when {
                        response.isSuccessful -> {
                            val bodyText = response.body?.string() ?: ""
                            IcmApiFileLogger.log("D", "IcmApi", "Success ${response.code} on $endpoint")
                            Result.success(bodyText)
                        }
                        else -> {
                            val errorText = response.body?.string() ?: "HTTP ${response.code}"
                            IcmApiFileLogger.log("E", "IcmApi", "API error: ${response.code} on $endpoint rid=${requestId ?: "-"}${trackIdForLog(endpoint, body)}")
                            if (response.code != 429) {
                                com.liquidmusicglass.debug.DebugLog.add(
                                    "ICM ${response.code} $endpoint rid=${requestId ?: "-"}${trackIdForLog(endpoint, body)}"
                                )
                            }
                            val error = try {
                                json.decodeFromString<IcmErrorWrapper>(errorText).detail
                            } catch (_: Exception) {
                                try {
                                    json.decodeFromString<IcmError>(errorText)
                                } catch (_: Exception) {
                                    null
                                }
                            }
                            val retryAfterHeader = response.header("Retry-After")?.toIntOrNull()
                            if (response.code == 429 || error?.error == "rate_limited" || error?.error == "ip_temporarily_blocked") {
                                IcmRateGate.tripBan(retryAfterHeader ?: error?.retryAfter)
                            }
                            Result.failure(
                                IcmApiException(
                                    response.code,
                                    errorText,
                                    error?.error,
                                    error?.requiredRegion,
                                    retryAfterHeader ?: error?.retryAfter,
                                    error?.source
                                )
                            )
                        }
                    }
                    } finally {
                        response.close()
                        IcmApiFileLogger.log("D", "IcmApi", "Request finished $endpoint")
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    IcmRateGate.recordFailure()
                    com.liquidmusicglass.engine.NetworkVitality.onRequestNetworkError()
                    Result.failure(e)
                }
            }
        } ?: run {
            IcmRateGate.recordFailure()
            com.liquidmusicglass.engine.NetworkVitality.onRequestNetworkError()
            Result.failure(IcmApiException(408, "icm call coroutine timeout"))
        }
    }

    private suspend inline fun <reified T> executeOnce(
        endpoint: String,
        method: String = "GET",
        body: String? = null,
        async: Boolean = false
    ): Result<T> {
        // Обычный расширяемый Dispatchers.IO — НЕ limitedParallelism: при лежащей сети
        // узкий лимит забивался висяками и вешал весь API. Число коннектов держим
        // короткими таймаутами (callTimeout) + ограниченными ретраями, а не очередью.
        // ВНЕШНИЙ корутинный таймаут — на случай, если OkHttp callTimeout не сработает
        // (висение на socketRead0): корутина гарантированно отменяется, не вешает пул.
        // Внешний лимит > callTimeout (12с), иначе корутина отваливалась бы раньше OkHttp.
        // Circuit-breaker: пока активен бан — мгновенно фейлим, не выходя в сеть.
        if (IcmRateGate.isBanned()) {
            return Result.failure(
                IcmApiException(429, "rate-limited (local gate)", "ip_temporarily_blocked", null, IcmRateGate.bannedForSeconds().coerceAtLeast(1), null)
            )
        }
        // Throttle ВНЕ таймаута — см. requestJsonOnce (самобан от очереди за токенами).
        IcmRateGate.throttle()
        return withTimeoutOrNull(14_000L) {
        withContext(Dispatchers.IO) {
            try {
                val url = resolveUrl(endpoint, async)
                val request = buildRequest(url, method, body)
                val response = executeWithRetry(request, endpoint)
                try {
                // 5xx копится как фейл breaker'а — см. requestJsonOnce.
                if (response.code < 500) IcmRateGate.recordSuccess() else IcmRateGate.recordFailure()
                com.liquidmusicglass.engine.NetworkVitality.onRequestSuccess()

                val requestId = extractRequestId(response)
                requestId?.let { onRequestId?.invoke(it) }

                return@withContext when {
                    response.code == 202 && endpoint.contains("/track") -> {
                        // Async track pending — parse as pending response
                        val pending = parseResponse<IcmAsyncTrackPending>(response.body)
                        Result.failure(IcmAsyncPendingException(pending))
                    }
                    response.isSuccessful -> {
                        val bodyText = response.body?.string() ?: ""
                        IcmApiFileLogger.log("D", "IcmApi", "Success ${response.code} on $endpoint")
                        try {
                            Result.success(json.decodeFromString(bodyText))
                        } catch (e: Exception) {
                            IcmApiFileLogger.log("E", "IcmApi", "Parse error on $endpoint: ${e.message}")
                            Result.failure(e)
                        }
                    }
                    else -> {
                        val errorText = response.body?.string() ?: "HTTP ${response.code}"
                        // rid — X-Request-Id сервера: по нему саппорт ICM находит
                        // конкретный запрос в своих логах за секунды.
                        IcmApiFileLogger.log("E", "IcmApi", "API error: ${response.code} on $endpoint rid=${requestId ?: "-"}${trackIdForLog(endpoint, body)}")
                        if (response.code != 429) {
                            com.liquidmusicglass.debug.DebugLog.add(
                                "ICM ${response.code} $endpoint rid=${requestId ?: "-"}${trackIdForLog(endpoint, body)}"
                            )
                        }
                        val error = try {
                            json.decodeFromString<IcmErrorWrapper>(errorText).detail
                        } catch (_: Exception) {
                            try {
                                json.decodeFromString<IcmError>(errorText)
                            } catch (_: Exception) {
                                null
                            }
                        }
                        // Prefer the canonical HTTP Retry-After header, fall back to body field.
                        val retryAfterHeader = response.header("Retry-After")?.toIntOrNull()
                        // 429 / блокировка → взводим circuit-breaker, чтобы остановить долбёжку.
                        if (response.code == 429 || error?.error == "rate_limited" || error?.error == "ip_temporarily_blocked") {
                            IcmRateGate.tripBan(retryAfterHeader ?: error?.retryAfter)
                        }
                        Result.failure(IcmApiException(
                            response.code,
                            errorText,
                            error?.error,
                            error?.requiredRegion,
                            retryAfterHeader ?: error?.retryAfter,
                            error?.source
                        ))
                    }
                }
                } finally {
                    response.close()
                    IcmApiFileLogger.log("D", "IcmApi", "Request finished $endpoint")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                IcmRateGate.recordFailure()   // сетевой фейл без ответа → копим к circuit-breaker
                // Fail-streak детект: N таких подряд = маршрут протух тихо
                // (реконнект туннеля без смены Network id) → NetworkVitality
                // сам вычистит пулы, не дожидаясь системного колбэка.
                com.liquidmusicglass.engine.NetworkVitality.onRequestNetworkError()
                Result.failure(e)
            }
        }
        } ?: run {
            // Корутинный таймаут (запрос завис дольше 14с) — фиксируем фейл, не виснем.
            IcmRateGate.recordFailure()
            com.liquidmusicglass.engine.NetworkVitality.onRequestNetworkError()
            Result.failure(IcmApiException(408, "icm call coroutine timeout"))
        }
    }

    private inline fun <reified T> executeSync(
        endpoint: String,
        method: String = "GET",
        body: String? = null,
        async: Boolean = false
    ): Result<T> {
        // Circuit-breaker: пока активен бан — не выходим в сеть (важно для потока
        // загрузчика ExoPlayer: не виснем на таймауте во время бана).
        if (IcmRateGate.isBanned()) {
            return Result.failure(
                IcmApiException(429, "rate-limited (local gate)", "ip_temporarily_blocked", null, IcmRateGate.bannedForSeconds().coerceAtLeast(1), null)
            )
        }
        try {
            IcmRateGate.throttleBlocking()
            val url = resolveUrl(endpoint, async)
            val request = buildRequest(url, method, body, fast = true)
            IcmApiFileLogger.log("D", "IcmApi", "Sync request started $endpoint")
            val response = client.newCall(request).execute()
            try {
            // 5xx копится как фейл breaker'а — см. requestJsonOnce.
            if (response.code < 500) IcmRateGate.recordSuccess() else IcmRateGate.recordFailure()

            extractRequestId(response)?.let { onRequestId?.invoke(it) }

            return when {
                response.code == 202 && endpoint.contains("/track") -> {
                    val pending = parseResponse<IcmAsyncTrackPending>(response.body)
                    Result.failure(IcmAsyncPendingException(pending))
                }
                response.isSuccessful -> {
                    Result.success(parseResponse<T>(response.body))
                }
                else -> {
                    val errorText = response.body?.string() ?: "HTTP ${response.code}"
                    IcmApiFileLogger.log("E", "IcmApi", "API error (sync): ${response.code} on $endpoint${trackIdForLog(endpoint, body)}")
                    val error = try {
                        json.decodeFromString<IcmErrorWrapper>(errorText).detail
                    } catch (_: Exception) {
                        try {
                            json.decodeFromString<IcmError>(errorText)
                        } catch (_: Exception) {
                            null
                        }
                    }
                    val retryAfterHeader = response.header("Retry-After")?.toIntOrNull()
                    if (response.code == 429 || error?.error == "rate_limited" || error?.error == "ip_temporarily_blocked") {
                        IcmRateGate.tripBan(retryAfterHeader ?: error?.retryAfter)
                    }
                    Result.failure(IcmApiException(
                        response.code,
                        errorText,
                        error?.error,
                        error?.requiredRegion,
                        retryAfterHeader ?: error?.retryAfter,
                        error?.source
                    ))
                }
            }
            } finally {
                response.close()
                IcmApiFileLogger.log("D", "IcmApi", "Sync request finished $endpoint")
            }
        } catch (e: java.io.InterruptedIOException) {
            // ЛОКАЛЬНЫЙ отказ (кап ожидания токена / interrupt отменённой загрузки)
            // — НЕ сетевой фейл, breaker не кормим: иначе быстрые скипы юзера
            // сами взводили бы 15-секундный бан.
            return Result.failure(e)
        } catch (e: Exception) {
            IcmRateGate.recordFailure()
            return Result.failure(e)
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  Public API
    // ═══════════════════════════════════════════════════════════

    /**
     * Check API health and key configuration.
     */
    suspend fun health(): Result<IcmHealthResponse> = execute("/health")

    /**
     * Первичный минт session-токена — через НАШ сервер-брокер
     * (POST <SERVER>/session/issue; s2s к партнёрке с устройства больше не
     * зовётся — ключ живёт на брокере). Рефреш протухшего токена делает
     * IcmAuthRepository через <SERVER>/session/refresh.
     */
    suspend fun issueSession(
        partnerUserId: String,
        hideExplicit: Boolean = false
    ): Result<IcmSessionResponse> {
        val body = json.encodeToString(
            IcmSessionRequest(partnerUserId = partnerUserId, hideExplicit = hideExplicit)
        )
        return execute("$SERVER_BASE/session/issue", method = "POST", body = body)
    }

    /**
     * Issue a fresh session token if no live one is cached, otherwise just
     * return a stub success with the existing token information. Per docs
     * 12.1, `partner_session_token` is cached locally up to `expires_in`,
     * so we only mint a new one when we don't already have one.
     */
    suspend fun refreshSessionIfNeeded(
        partnerUserId: String,
        hideExplicit: Boolean = false
    ): Result<IcmSessionResponse> {
        // Снимаем в локальную val: sessionToken — mutable var, между проверкой и
        // использованием другой поток мог обнулить его (был бы NPE на !!).
        val existing = sessionToken
        if (existing != null) {
            return Result.success(
                IcmSessionResponse(
                    partnerSessionToken = existing,
                    expiresIn = 0,
                    partnerUserId = partnerUserId,
                    scopes = emptyList()
                )
            )
        }
        return issueSession(partnerUserId, hideExplicit).also { result ->
            result.getOrNull()?.let { sessionToken = it.partnerSessionToken }
        }
    }

/**
 * Search tracks, albums, and artists.
 * @param query Search string (up to 200 chars, min 2 alphanumeric)
 * @param region Region (us/ru/nz), null uses defaultRegion
 * @param source Music source: "apple" (default), "vk", "all" — по доке ICM.
 * @param limit Max results (clamped to partner.config.search.max_results)
 * @return Search response with mixed items (artists, albums, tracks)
 */
suspend fun search(
    query: String,
    region: String? = null,
    source: String? = null,
    limit: Int? = null
): Result<IcmSearchResponse> {
    val r = region ?: defaultRegion
    val encQuery = java.net.URLEncoder.encode(query, "UTF-8")
    val params = buildString {
        append("?q=$encQuery")
        append("&region=$r")
        // Normalize source names to ICM API values
        // Internal: PRIMARY="primary", VK="secondary", ALL="all"
        // API expects: "apple", "vk", "all"
        val normalizedSource = when (source) {
            "primary", "apple" -> "apple"
            "secondary", "vk" -> "vk"
            "all" -> "all"
            else -> null
        }
        if (normalizedSource != null) append("&source=$normalizedSource")
        if (limit != null && limit > 0) append("&limit=$limit")
    }
    return execute("/search$params")
}

    /**
     * Get signed playback URL for a track.
     * @param trackId Track ID from search/album
     * @param region Region
     * @param quality Quality: "256K", "128K" or null
     * @return IcmTrackResponse with url field for streaming
     */
    suspend fun getTrack(
        trackId: String,
        region: String? = null,
        quality: String? = streamQuality
    ): Result<IcmTrackResponse> {
        val body = json.encodeToString(
            IcmTrackRequest(
                trackId = trackId,
                region = region ?: defaultRegion,
                quality = quality
            )
        )
        return execute("/track", method = "POST", body = body)
    }

    fun getTrackSync(
        trackId: String,
        region: String? = null,
        quality: String? = streamQuality
    ): Result<IcmTrackResponse> {
        val body = json.encodeToString(
            IcmTrackRequest(
                trackId = trackId,
                region = region ?: defaultRegion,
                quality = quality
            )
        )
        return executeSync("/track", method = "POST", body = body)
    }

    /**
     * Album info + track list.
     */
    suspend fun getAlbum(
        albumId: String,
        region: String? = null
    ): Result<IcmAlbumResponse> {
        val r = region ?: defaultRegion
        return execute("/album/$albumId?region=$r")
    }

    /**
     * Artist info: top tracks, albums, similar artists.
     */
    suspend fun getArtist(
        artistId: String,
        region: String? = null
    ): Result<IcmArtistResponse> {
        val r = region ?: defaultRegion
        return execute("/artist/$artistId?region=$r")
    }

    /**
     * Track metadata (without playback URL).
     */
    suspend fun getTrackMeta(trackId: String): Result<IcmTrackMeta> =
        execute("/track/$trackId/meta")

    /**
     * Editorial Apple Music playlist (Today Hits, etc).
     * ICM API uses the same /album/{id} endpoint for playlists (id starts with pl.).
     */
    suspend fun getPlaylist(
        playlistId: String,
        region: String? = null
    ): Result<IcmAlbumResponse> {
        val r = region ?: defaultRegion
        return execute("/album/$playlistId?region=$r")
    }

    /**
     * Sign cover URL (for custom covers).
     * Apple Music covers don't need signing — use URL directly.
     */
    suspend fun signCover(fileId: String): Result<IcmCoverSignResponse> {
        val encFileId = java.net.URLEncoder.encode(fileId, "UTF-8")
        return execute("/cover-sign?file_id=$encFileId")
    }

    /**
     * Song lyrics.
     * Primary: GET /track/{id}/lyrics?region={region}
     * Mirror:  GET /lyrics?track_id={trackId}
     */
    suspend fun getLyrics(trackId: String, region: String? = null): Result<IcmLyricsResponse> {
        val r = region ?: defaultRegion
        return execute("/track/$trackId/lyrics?region=$r")
    }

    // ═══════════════════════════════════════════════════════════
    //  Batch & Async
    // ═══════════════════════════════════════════════════════════

    /**
     * Batch track metadata — up to 50 per request.
     */
    suspend fun getBatchTrackMeta(trackIds: List<String>): Result<IcmBatchTrackMetaResponse> {
        if (trackIds.isEmpty()) return Result.failure(IllegalArgumentException("trackIds must not be empty"))
        if (trackIds.size > 50) return Result.failure(IllegalArgumentException("trackIds max 50, got ${trackIds.size}"))
        val body = json.encodeToString(IcmBatchTrackMetaRequest(trackIds = trackIds))
        return execute("/tracks/meta", method = "POST", body = body)
    }

    /**
     * Get track in async mode.
     * If track is cold — returns 202 with job_id for polling.
     */
    suspend fun getTrackAsync(
        trackId: String,
        region: String? = null,
        quality: String? = streamQuality
    ): Result<IcmTrackResponse> {
        val body = json.encodeToString(
            IcmTrackRequest(
                trackId = trackId,
                region = region ?: defaultRegion,
                quality = quality
            )
        )
        return execute("/track", method = "POST", body = body, async = true)
    }

    /**
     * Check async job status.
     */
    suspend fun pollAsyncJob(jobId: String): Result<IcmAsyncTrackReady> {
        return execute("/track/job/$jobId")
    }

    // ═══════════════════════════════════════════════════════════
    //  Account Linking
    // ═══════════════════════════════════════════════════════════

    /**
     * Generate URL for linking user account to ICM via Telegram.
     * @param partnerUserId User ID in your system
     * @param redirectUri Callback URI after authorization (custom-scheme deep link,
     *   whitelisted by ICM — no intermediate redirect server needed)
     * @param state Random string for CSRF protection
     * @param appName Название приложения, которое юзер увидит на экране входа
     *   в аккаунт (параметр app_name, добавлен ICM). Пусто — дефолт сервера.
     */
    fun buildAccountLinkUrl(
        partnerId: String,
        partnerUserId: String,
        redirectUri: String,
        state: String,
        appName: String? = null
    ): String {
        val encRedirect = java.net.URLEncoder.encode(redirectUri, "UTF-8")
        val encState = java.net.URLEncoder.encode(state, "UTF-8")
        val encUserId = java.net.URLEncoder.encode(partnerUserId, "UTF-8")
        val appNameParam = appName?.takeIf { it.isNotBlank() }?.let {
            "&app_name=${java.net.URLEncoder.encode(it, "UTF-8")}"
        } ?: ""
        return "https://byicloud.online/partner/$partnerId/link?partner_user_id=$encUserId&redirect_uri=$encRedirect&state=$encState$appNameParam"
    }

    /**
     * Parse callback from ICM after linking.
     */
    fun parseAccountLinkCallback(
        state: String,
        linked: Boolean,
        icmUserId: String? = null,
        error: String? = null
    ): IcmAccountLinkCallback {
        return IcmAccountLinkCallback(
            state = state,
            linked = linked,
            icmUserId = icmUserId,
            error = error
        )
    }

    // ═══════════════════════════════════════════════════════════
    //  Email Account Linking — через наш сервер (/lmg/auth/email/*),
    //  партнёрский ключ подставляет брокер
    // ═══════════════════════════════════════════════════════════

    /**
     * Request email OTP for account linking.
     * Auto-registers new ICM account if email doesn't exist.
     * S2S only — requires X-Partner-Key.
     */
    suspend fun requestEmailLink(
        partnerUserId: String,
        email: String,
        state: String? = null
    ): Result<IcmEmailLinkResponse> {
        val body = json.encodeToString(IcmEmailLinkRequest(
            partnerUserId = partnerUserId,
            email = email,
            state = state
        ))
        return execute("$SERVER_BASE/auth/email/request", method = "POST", body = body)
    }

    /**
     * Verify email OTP and link account.
     */
    suspend fun verifyEmailLink(
        nonce: String,
        otp: String
    ): Result<IcmEmailVerifyResponse> {
        val body = json.encodeToString(IcmEmailVerifyRequest(nonce = nonce, otp = otp))
        return execute("$SERVER_BASE/auth/email/verify", method = "POST", body = body)
    }

    /**
     * Change password for linked user.
     * S2S only. User must be linked to YOUR partner_id.
     */
    suspend fun changePassword(
        partnerUserId: String,
        currentPassword: String,
        newPassword: String
    ): Result<IcmPasswordChangeResponse> {
        val body = json.encodeToString(IcmPasswordChangeRequest(
            partnerUserId = partnerUserId,
            currentPassword = currentPassword,
            newPassword = newPassword
        ))
        return execute("$SERVER_BASE/auth/email/password/change", method = "POST", body = body)
    }

    /**
     * Reset password for linked user.
     * S2S only. New password sent to user's email.
     */
    suspend fun resetPassword(
        partnerUserId: String
    ): Result<IcmPasswordResetResponse> {
        val body = json.encodeToString(IcmPasswordResetRequest(partnerUserId = partnerUserId))
        return execute("$SERVER_BASE/auth/email/password/reset", method = "POST", body = body)
    }

    // ═══════════════════════════════════════════════════════════
    //  Library (likes, subscriptions)
    //  Requires X-Partner-User-Id header and linked user
    // ═══════════════════════════════════════════════════════════

    /**
     * Get user's liked tracks.
     * Requires partnerUserId to be set.
     */
    suspend fun getLibraryLikes(
        source: String? = null,
        limit: Int? = null,
        offset: Int? = null
    ): Result<IcmLibraryLikesResponse> {
        val params = buildString {
            val query = mutableListOf<String>()
            if (!source.isNullOrBlank()) query.add("source=$source")
            if (limit != null) query.add("limit=$limit")
            if (offset != null) query.add("offset=$offset")
            if (query.isNotEmpty()) append("?${query.joinToString("&")}")
        }
        return execute("/library/likes$params")
    }

    /**
     * Toggle-лайк трека. POST /library/likes — теперь TOGGLE (changelog ICM
     * 2026-07-11): первый вызов с track_id ставит лайк, повторный снимает; в
     * ответе liked = итоговое состояние. Раньше write отдавал 405 (тогда стоял
     * предохранитель likesBlocked) — теперь эндпоинт рабочий.
     */
    suspend fun likeTrack(trackId: String): Result<IcmLikeResponse> {
        val body = json.encodeToString(IcmLikeRequest(trackIdSnake = trackId, trackIdCamel = trackId))
        return execute("/library/likes", method = "POST", body = body)
    }

    /**
     * Снять лайк — тот же POST-toggle. Changelog гарантирует только POST;
     * DELETE /library/likes/{id} может по-прежнему отдавать 405, поэтому не
     * используем его. Итоговое состояние — в ответе liked.
     */
    suspend fun unlikeTrack(trackId: String): Result<IcmLikeResponse> {
        val body = json.encodeToString(IcmLikeRequest(trackIdSnake = trackId, trackIdCamel = trackId))
        return execute("/library/likes", method = "POST", body = body)
    }

    /**
     * Get user's artist subscriptions.
     * Requires partnerUserId to be set.
     */
    suspend fun getLibrarySubscriptions(
        source: String? = null,
        limit: Int? = null,
        offset: Int? = null
    ): Result<IcmLibrarySubscriptionsResponse> {
        val params = buildString {
            val query = mutableListOf<String>()
            if (!source.isNullOrBlank()) query.add("source=$source")
            if (limit != null) query.add("limit=$limit")
            if (offset != null) query.add("offset=$offset")
            if (query.isNotEmpty()) append("?${query.joinToString("&")}")
        }
        return execute("/library/subscriptions$params")
    }

    /**
     * Get next wave track.
     *
     * @param seedTrackId Optional track ID to create a "station based on track"
     * @param exclude Comma-separated track IDs to exclude (current queue)
     * @param recentSkips Number of consecutive skipped tracks (skip-streak fallback)
     * @param region Region override
     * @param source Source override
     * @param mood Mood filter (e.g., "energetic", "chill", "focus")
     * @param genre Genre filter (e.g., "electronic", "rock", "jazz")
     */
    suspend fun getWaveNext(
        seedTrackId: String? = null,
        exclude: List<String>? = null,
        recentSkips: Int? = null,
        region: String? = null,
        source: String? = null,
        mood: String? = null,
        genre: String? = null
    ): Result<IcmWaveResponse> {
        if (partnerUserId.isNullOrBlank()) {
            return Result.failure(
                IcmApiException(401, "partner_user_id is required for /library/wave/next")
            )
        }
        val params = buildString {
            append("/library/wave/next")
            val query = mutableListOf<String>()
            if (!seedTrackId.isNullOrBlank()) {
                query.add("seed_track_id=${java.net.URLEncoder.encode(seedTrackId, "UTF-8")}")
            }
            if (!exclude.isNullOrEmpty()) {
                val joined = exclude.joinToString(",")
                query.add("exclude=${java.net.URLEncoder.encode(joined, "UTF-8")}")
            }
            if (recentSkips != null) query.add("recent_skips=$recentSkips")
            if (region != null) query.add("region=$region")
            if (source != null) query.add("source=$source")
            if (!mood.isNullOrBlank()) query.add("mood=${java.net.URLEncoder.encode(mood, "UTF-8")}")
            if (!genre.isNullOrBlank()) query.add("genre=${java.net.URLEncoder.encode(genre, "UTF-8")}")
            if (query.isNotEmpty()) append("?${query.joinToString("&")}")
        }
        return execute(params)
    }

    /**
     * Send feedback about wave track.
     * feedback_type: less_track / less_artist / less_genre / more_track / more_artist / more_genre
     * value: track ID, artist ID, or genre name
     */
    suspend fun sendWaveFeedback(
        feedbackType: String,
        value: String
    ): Result<IcmWaveFeedbackResponse> {
        val body = json.encodeToString(IcmWaveFeedbackRequest(feedbackType = feedbackType, value = value))
        return execute("/library/wave/feedback", method = "POST", body = body)
    }

    /**
     * Reset wave history, seed artists, and preferences.
     * Likes are preserved.
     */
    suspend fun resetWave(): Result<IcmWaveResetResponse> {
        return execute("/library/wave/reset", method = "POST")
    }

    /**
     * Get popular artists for wave onboarding.
     * Does NOT require partnerUserId — can be called before linking.
     * Cached 24h on server.
     */
    suspend fun getWavePopularArtists(): Result<List<IcmWaveOnboardingArtist>> {
        return execute("/library/wave/popular-artists")
    }

    /**
     * Check wave onboarding status.
     */
    suspend fun getWaveOnboarding(): Result<IcmWaveOnboardingResponse> {
        return execute("/library/wave/onboarding")
    }

    /**
     * Save user's artist selection for wave onboarding.
     * Minimum 1 artist, recommended 3-5.
     */
    suspend fun saveWaveOnboarding(
        artists: List<IcmWaveOnboardingArtistSave>
    ): Result<IcmWaveOnboardingSaveResponse> {
        val body = json.encodeToString(IcmWaveOnboardingSaveRequest(artists = artists))
        return execute("/library/wave/onboarding", method = "POST", body = body)
    }

    /**
     * Log playback event for wave ranking improvement.
     * Called when user finishes/skips/switches a wave track.
     */
    suspend fun logWavePlayback(
        trackId: String,
        playedSeconds: Double,
        totalSeconds: Double? = null,
        completed: Boolean? = null,
        skipped: Boolean? = null
    ): Result<IcmWavePlaybackResponse> {
        val body = json.encodeToString(
            IcmWavePlaybackRequest(
                trackId = trackId,
                playedSeconds = playedSeconds,
                totalSeconds = totalSeconds,
                completed = completed,
                skipped = skipped
            )
        )
        return execute("/library/wave/playback", method = "POST", body = body)
    }

    // ═══════════════════════════════════════════════════════════
    //  Personal Cabinet (/me/*) — requires linked user + subscription
    // ═══════════════════════════════════════════════════════════

    // /me/quality удалён: эндпоинта нет в доке (404) — качество задаётся
    // через GET/PUT /me/preferences (getUserPreferences/updateUserPreferences).

    /**
     * Get current user preferences (quality, region, hide_explicit, source).
     * Requires linked user with active subscription.
     */
    suspend fun getUserPreferences(): Result<IcmUserPreferences> {
        return execute("/me/preferences")
    }

    /**
     * Update user preferences. Only non-null fields in [prefs] are sent.
     */
    suspend fun updateUserPreferences(prefs: IcmUserPreferences): Result<IcmUserPreferences> {
        val body = json.encodeToString(IcmUpdatePreferencesRequest(prefs.qualityPreference))
        return execute("/me/preferences", method = "PUT", body = body)
    }

    /**
     * Get user profile (partner_user_id, name, username, avatar_url — email/phone дока не отдаёт никогда).
     */
    suspend fun getUserProfile(): Result<IcmUserProfile> {
        return execute("/me/profile")
    }

    /**
     * Get user's ICM subscription information.
     * Scope: user_subscription. Linked user required.
     */
    suspend fun getUserSubscription(): Result<IcmSubscriptionResponse> {
        return execute("/me/subscription")
    }

    /**
     * Get user's current and available regions.
     * Scope: user_region. Linked user required.
     */
    suspend fun getUserRegion(): Result<IcmRegionResponse> {
        return execute("/me/region")
    }

    /**
     * Update user's region.
     * Scope: user_region. Linked user required.
     */
    suspend fun updateUserRegion(region: String): Result<IcmUpdateRegionResponse> {
        val body = json.encodeToString(IcmUpdateRegionRequest(region = region))
        return execute("/me/region", method = "PUT", body = body)
    }

    /**
     * Start playlist import from Yandex or Apple.
     * Scope: playlists_import. Linked user required.
     */
    suspend fun importPlaylist(source: String, url: String, name: String? = null): Result<IcmPlaylistImportResponse> {
        val body = json.encodeToString(IcmPlaylistImportRequest(source = source, url = url, name = name))
        return execute("/me/playlists/import", method = "POST", body = body)
    }

    /**
     * Preview playlist without importing it.
     * Scope: playlists_import. Linked user required.
     */
    suspend fun previewPlaylist(source: String, url: String): Result<IcmPlaylistPreviewResponse> {
        val body = json.encodeToString(IcmPlaylistPreviewRequest(source = source, url = url))
        return execute("/me/playlists/preview", method = "POST", body = body)
    }

    /**
     * Get the status of an asynchronous playlist import job.
     * Scope: playlists_import. Linked user required.
     */
    suspend fun getImportJobStatus(jobId: String): Result<IcmPlaylistImportJobResponse> {
        return execute("/me/playlists/import/$jobId")
    }

    /**
     * Get a list of the user's imported playlists.
     * Scope: playlists_import. Linked user required.
     */
    suspend fun getUserPlaylists(limit: Int = 50, offset: Int = 0): Result<IcmUserPlaylistsResponse> {
        return execute("/me/playlists?limit=$limit&offset=$offset")
    }

    /**
     * Get tracks from an imported playlist.
     * Scope: playlists_import. Linked user required.
     */
    suspend fun getUserPlaylistTracks(playlistId: String, limit: Int = 200, offset: Int = 0): Result<IcmUserPlaylistTracksResponse> {
        return execute("/me/playlists/$playlistId?limit=$limit&offset=$offset")
    }

    /**
     * Delete an imported playlist.
     * Scope: playlists_import. Linked user required.
     */
    suspend fun deleteUserPlaylist(playlistId: String): Result<IcmDeletePlaylistResponse> {
        return execute("/me/playlists/$playlistId", method = "DELETE")
    }
}

/**
 * API exception with HTTP code.
 */
class IcmApiException(
    val code: Int,
    override val message: String,
    val errorCode: String? = null,
    val requiredRegion: String? = null,
    val retryAfter: Int? = null,
    val source: String? = null
) : Exception("HTTP $code: $message")

/**
 * Async pending exception — track is still being prepared.
 */
class IcmAsyncPendingException(
    val pending: IcmAsyncTrackPending
) : Exception("Track pending: job ${pending.jobId}, poll after ${pending.pollAfterSeconds}s")
