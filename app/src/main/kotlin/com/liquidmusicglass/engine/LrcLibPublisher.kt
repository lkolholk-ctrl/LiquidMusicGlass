package com.liquidmusicglass.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Публикация текстов в LRCLIB (вклад в открытую базу). Только для локальных треков.
 *
 * LRCLIB защищён Proof-of-Work от спама:
 *   1) POST /api/request-challenge → { prefix, target }
 *   2) найти nonce так, чтобы SHA-256("{prefix}{nonce}") ≤ target (побайтовое
 *      сравнение big-endian) — эталон в LRCGET (tranxuanthang/lrcget).
 *   3) POST /api/publish с заголовком X-Publish-Token: "{prefix}:{nonce}" (одноразовый).
 *
 * PoW считается на Dispatchers.Default, сеть — Dispatchers.IO. UI не блокируется.
 */
object LrcLibPublisher {

    private const val BASE = "https://lrclib.net/api"
    private const val USER_AGENT =
        "LiquidMusicGlass/2026.05.30 (https://github.com/lkolholk-ctrl/liquidmusicglass)"

    data class Meta(
        val trackName: String,
        val artistName: String,
        val albumName: String,
        val durationSec: Int
    )

    sealed class Result {
        object Success : Result()
        data class Error(val message: String) : Result()
    }

    private data class Challenge(val prefix: String, val target: String)

    /**
     * Полный цикл: challenge → PoW → publish. На ошибке токена перерешивает PoW (1 ретрай).
     * [onStatus] зовётся со стадиями (для спиннера). Любая ошибка → Result.Error, без краша.
     */
    suspend fun publish(
        meta: Meta,
        plainLyrics: String,
        syncedLyrics: String,
        onStatus: (String) -> Unit = {}
    ): Result {
        if (meta.durationSec <= 0) return Result.Error("Длительность трека неизвестна")
        if (meta.trackName.isBlank() || meta.artistName.isBlank())
            return Result.Error("Заполни название и артиста")

        var attempt = 0
        while (attempt < 2) {
            attempt++
            onStatus("Запрос задачи…")
            val challenge = try {
                requestChallenge()
            } catch (e: Exception) {
                return Result.Error("Нет связи с LRCLIB")
            } ?: return Result.Error("LRCLIB не выдал задачу")

            onStatus("Решение задачи публикации…")
            val token = withContext(Dispatchers.Default) { solveChallenge(challenge.prefix, challenge.target) }

            onStatus("Публикация…")
            val outcome = try {
                doPublish(token, meta, plainLyrics, syncedLyrics)
            } catch (e: Exception) {
                return Result.Error("Ошибка сети")
            }
            when (outcome) {
                Outcome.Ok -> return Result.Success
                Outcome.BadToken -> continue            // токен не принят → новый PoW
                is Outcome.Failed -> return Result.Error(outcome.message)
            }
        }
        return Result.Error("Не удалось подтвердить токен публикации")
    }

    // ── /api/request-challenge ───────────────────────────────────────────────
    private suspend fun requestChallenge(): Challenge? = withContext(Dispatchers.IO) {
        val conn = (URL("$BASE/request-challenge").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 6000
            readTimeout = 8000
            doOutput = true
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
        }
        try {
            conn.outputStream.use { /* пустое тело */ }
            if (conn.responseCode != 200) return@withContext null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val o = JSONObject(body)
            Challenge(o.getString("prefix"), o.getString("target"))
        } finally {
            try { conn.disconnect() } catch (_: Exception) {}
        }
    }

    // ── Proof-of-Work: SHA-256("{prefix}{nonce}") ≤ target ───────────────────
    private fun solveChallenge(prefix: String, targetHex: String): String {
        val target = hexToBytes(targetHex)
        val md = MessageDigest.getInstance("SHA-256")
        var nonce = 0L
        while (true) {
            md.reset()
            val hash = md.digest((prefix + nonce).toByteArray(Charsets.UTF_8))
            if (hashLeqTarget(hash, target)) return "$prefix:$nonce"
            nonce++
        }
    }

    /** Хеш ≤ target (big-endian, побайтово). */
    private fun hashLeqTarget(hash: ByteArray, target: ByteArray): Boolean {
        val n = minOf(hash.size, target.size)
        for (i in 0 until n) {
            val h = hash[i].toInt() and 0xFF
            val t = target[i].toInt() and 0xFF
            if (h > t) return false
            if (h < t) return true
        }
        return true
    }

    private fun hexToBytes(hex: String): ByteArray {
        val s = hex.trim()
        val out = ByteArray(s.length / 2)
        for (i in out.indices) {
            out[i] = ((Character.digit(s[i * 2], 16) shl 4) + Character.digit(s[i * 2 + 1], 16)).toByte()
        }
        return out
    }

    // ── /api/publish ─────────────────────────────────────────────────────────
    private sealed class Outcome {
        object Ok : Outcome()
        object BadToken : Outcome()
        data class Failed(val message: String) : Outcome()
    }

    private suspend fun doPublish(token: String, meta: Meta, plain: String, synced: String): Outcome =
        withContext(Dispatchers.IO) {
            val payload = JSONObject().apply {
                put("trackName", meta.trackName)
                put("artistName", meta.artistName)
                put("albumName", meta.albumName)
                put("duration", meta.durationSec)
                put("plainLyrics", plain)
                put("syncedLyrics", synced)
            }.toString()

            val conn = (URL("$BASE/publish").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 6000
                readTimeout = 10000
                doOutput = true
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("X-Publish-Token", token)
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
            }
            try {
                conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                if (code == 200 || code == 201) return@withContext Outcome.Ok

                val err = try {
                    (conn.errorStream ?: conn.inputStream).bufferedReader().use { it.readText() }
                } catch (_: Exception) { "" }
                val errObj = try { JSONObject(err) } catch (_: Exception) { null }
                val errCode = errObj?.optString("code", "") ?: ""

                if (errCode.equals("IncorrectPublishTokenError", true) ||
                    (code == 400 && err.contains("token", true))) {
                    return@withContext Outcome.BadToken
                }
                val msg = errObj?.optString("message", "") ?: ""
                Outcome.Failed(if (msg.isNotBlank()) msg else "Ошибка $code")
            } finally {
                try { conn.disconnect() } catch (_: Exception) {}
            }
        }
}
