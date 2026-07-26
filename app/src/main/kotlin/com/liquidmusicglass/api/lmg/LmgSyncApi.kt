package com.liquidmusicglass.api.lmg

import com.liquidmusicglass.api.icm.IcmApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Клиент синхронизации через наш брокер: продолжение воспроизведения на другом
 * устройстве и комнаты совместного прослушивания.
 *
 * Отдельно от [IcmApi] намеренно: тот проксирует партнёрский каталог, а эти
 * эндпоинты — наши собственные, со своей моделью данных и своими отказами.
 * HTTP-клиент переиспользуем из [IcmApi]: отдельный означал бы второй пул
 * соединений к тому же хосту.
 *
 * Пользователь опознаётся теми же заголовками, что и в остальном приложении:
 * идентификатор партнёрского пользователя и идентификатор устройства.
 */
object LmgSyncApi {

    private val JSON = "application/json; charset=utf-8".toMediaType()

    /** Состояние воспроизведения, которым обмениваются устройства. */
    data class PlaybackState(
        val trackId: String,
        val positionMs: Long,
        val durationMs: Long,
        val title: String,
        val artist: String,
        val coverUrl: String,
        val isPlaying: Boolean,
        val deviceName: String = "",
        /** Момент по часам СЕРВЕРА, а не устройства. */
        val updatedAt: Long = 0L
    )

    /** Ответ комнаты: состояние плюс опора времени для подстройки позиции. */
    data class Room(
        val code: String,
        val hostPid: String,
        val state: PlaybackState?,
        val memberNames: List<String>,
        val serverTimeMs: Long
    )

    /** true, если этот пользователь ведёт комнату. */
    fun isHost(room: Room): Boolean = room.hostPid == IcmApi.partnerUserId

    // ── Continuity ───────────────────────────────────────────────────────────

    suspend fun saveState(state: PlaybackState): Boolean = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("trackId", state.trackId)
            put("positionMs", state.positionMs)
            put("durationMs", state.durationMs)
            put("title", state.title)
            put("artist", state.artist)
            put("coverUrl", state.coverUrl)
            put("isPlaying", state.isPlaying)
            put("deviceName", state.deviceName)
        }
        runCatching { post("/state", body) != null }.getOrDefault(false)
    }

    /**
     * Последнее состояние пользователя.
     *
     * @return состояние и признак «это же устройство» — на своём устройстве
     *   предлагать «продолжить» бессмысленно, там всё и так открыто.
     */
    suspend fun fetchState(): Pair<PlaybackState, Boolean>? = withContext(Dispatchers.IO) {
        val json = runCatching { get("/state") }.getOrNull() ?: return@withContext null
        val stateJson = json.optJSONObject("state") ?: return@withContext null
        parseState(stateJson) to json.optBoolean("sameDevice", false)
    }

    // ── Совместное прослушивание ─────────────────────────────────────────────

    suspend fun createRoom(name: String): Room? = withContext(Dispatchers.IO) {
        val body = JSONObject().put("name", name)
        runCatching { post("/rooms", body)?.let(::parseRoom) }.getOrNull()
    }

    suspend fun joinRoom(code: String, name: String): Room? = withContext(Dispatchers.IO) {
        val body = JSONObject().put("name", name)
        runCatching { post("/rooms/${code.uppercase()}/join", body)?.let(::parseRoom) }.getOrNull()
    }

    suspend fun fetchRoom(code: String): Room? = withContext(Dispatchers.IO) {
        runCatching { get("/rooms/${code.uppercase()}")?.let(::parseRoom) }.getOrNull()
    }

    /** Публикация состояния — принимается только от хозяина комнаты. */
    suspend fun publishRoomState(code: String, state: PlaybackState): Room? =
        withContext(Dispatchers.IO) {
            val body = JSONObject().apply {
                put("trackId", state.trackId)
                put("positionMs", state.positionMs)
                put("durationMs", state.durationMs)
                put("title", state.title)
                put("artist", state.artist)
                put("coverUrl", state.coverUrl)
                put("isPlaying", state.isPlaying)
            }
            runCatching { post("/rooms/${code.uppercase()}/state", body)?.let(::parseRoom) }
                .getOrNull()
        }

    suspend fun leaveRoom(code: String): Boolean = withContext(Dispatchers.IO) {
        runCatching { post("/rooms/${code.uppercase()}/leave", JSONObject()) != null }
            .getOrDefault(false)
    }

    // ── HTTP ─────────────────────────────────────────────────────────────────

    private fun get(path: String): JSONObject? {
        val request = Request.Builder()
            .url(IcmApi.SERVER_BASE + path)
            .get()
            .applyIdentity()
            .build()
        return execute(request)
    }

    private fun post(path: String, body: JSONObject): JSONObject? {
        val request = Request.Builder()
            .url(IcmApi.SERVER_BASE + path)
            .post(body.toString().toRequestBody(JSON))
            .applyIdentity()
            .build()
        return execute(request)
    }

    private fun Request.Builder.applyIdentity(): Request.Builder {
        IcmApi.partnerUserId?.takeIf { it.isNotBlank() }?.let { header("X-Partner-User-Id", it) }
        com.liquidmusicglass.logging.ClientTelemetry.deviceId
            .takeIf { it.isNotBlank() }?.let { header("X-Device-Id", it) }
        return this
    }

    private fun execute(request: Request): JSONObject? {
        IcmApi.sharedClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val text = response.body?.string().orEmpty()
            if (text.isBlank()) return null
            return JSONObject(text)
        }
    }

    private fun parseState(json: JSONObject) = PlaybackState(
        trackId = json.optString("trackId"),
        positionMs = json.optLong("positionMs"),
        durationMs = json.optLong("durationMs"),
        title = json.optString("title"),
        artist = json.optString("artist"),
        coverUrl = json.optString("coverUrl"),
        isPlaying = json.optBoolean("isPlaying"),
        deviceName = json.optString("deviceName"),
        updatedAt = json.optLong("updatedAt")
    )

    private fun parseRoom(json: JSONObject): Room {
        val members = mutableListOf<String>()
        json.optJSONArray("members")?.let { array ->
            for (i in 0 until array.length()) {
                array.optJSONObject(i)?.optString("name")?.takeIf { it.isNotBlank() }
                    ?.let(members::add)
            }
        }
        return Room(
            code = json.optString("code"),
            hostPid = json.optString("hostPid"),
            state = json.optJSONObject("state")?.let(::parseState),
            memberNames = members,
            serverTimeMs = json.optLong("serverTimeMs")
        )
    }
}
