package com.liquidmusicglass.camp

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.liquidmusicglass.api.icm.IcmAuthRepository
import com.liquidmusicglass.api.icm.IcmSubscriptionResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * FeatureAccessManager — Capability Matrix лагеря ICM.
 * Возможности зависят от статуса подписки ICM (free / premium).
 */
class FeatureAccessManager private constructor(context: Context) {

    companion object {
        private const val TAG = "FeatureAccessManager"
        private const val PREFS_NAME = "liquid_camp_prefs"
        private const val KEY_SELECTED_CAMP = "selected_camp"

        @Volatile
        private var instance: FeatureAccessManager? = null

        fun getInstance(context: Context): FeatureAccessManager {
            return instance ?: synchronized(this) {
                instance ?: FeatureAccessManager(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val appContext: Context = context.applicationContext

    private val _currentCamp = MutableStateFlow<MusicCamp>(loadCamp())
    val currentCamp: StateFlow<MusicCamp> = _currentCamp.asStateFlow()

    /** Текущие capabilities — реактивно обновляются при смене лагеря или подписки. */
    private val _capabilities = MutableStateFlow(Capabilities.default())
    val capabilities: StateFlow<Capabilities> = _capabilities.asStateFlow()

    init {
        // Subscribe to ICM subscription changes to recalc capabilities
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
            IcmAuthRepository.subscription.collect { sub ->
                recalc(sub)
            }
        }
        recalc(IcmAuthRepository.subscription.value)
    }

    /** Переключить активный лагерь. */
    fun selectCamp(camp: MusicCamp) {
        Log.i(TAG, "Switching camp: ${_currentCamp.value.id} → ${camp.id}")
        _currentCamp.value = camp
        saveCamp(camp)
        recalc(IcmAuthRepository.subscription.value)
    }

    /** Проверить, доступна ли фича в текущем контексте. */
    fun can(feature: Feature): Boolean {
        return _capabilities.value[feature] == true
    }

    /** Получить максимальный доступный битрейт для текущего лагеря. */
    fun maxBitrate(): Int {
        return _capabilities.value.maxBitrateKbps
    }

    /** Получить рекомендуемый формат для текущего лагеря. */
    fun preferredFormat(): AudioFormat {
        return _capabilities.value.preferredFormat
    }

    /** Получить лейбл для UI (например, "Free · 128K Opus"). */
    fun qualityLabel(): String {
        return _capabilities.value.qualityLabel
    }

    // ─── Internal ────────────────────────────────────────────────────────

    private fun recalc(subscription: IcmSubscriptionResponse?) {
        val camp = _currentCamp.value
        val caps = when (camp) {
            is MusicCamp.Icm -> {
                val hasSub = subscription?.isActive == true
                if (hasSub) Capabilities.icmPremium(256)
                else Capabilities.icmFree()
            }
        }
        _capabilities.value = caps
        Log.d(TAG, "Capabilities recalculated for ${camp.id}: $caps")
    }

    private fun loadCamp(): MusicCamp {
        return MusicCamp.Icm
    }

    private fun saveCamp(camp: MusicCamp) {
        prefs.edit().putString(KEY_SELECTED_CAMP, camp.id).apply()
    }
}

/**
 * Capability-флаги для UI и плеера.
 */
enum class Feature {
    /** Скачивание треков для офлайн-прослушивания. */
    DOWNLOAD,
    /** Прослушивание в высоком качестве (>128K). */
    HIGH_QUALITY,
    /** Прослушивание в Lossless качестве. */
    LOSSLESS,
    /** Доступ к персонализированным плейлистам (Моя Волна). */
    PERSONAL_WAVE,
    /** Доступ к полному каталогу ICM (включая secondary). */
    FULL_CATALOG,
    /** Фоновое воспроизведение без ограничений. */
    BACKGROUND_PLAYBACK,
    /** Пропуск треков без ограничений. */
    UNLIMITED_SKIPS
}

enum class AudioFormat {
    AAC,   // m4a — ICM default
    FLAC,  // lossless — ICM premium only
    MP3    // fallback
}

/**
 * Immutable snapshot capabilities.
 */
data class Capabilities(
    val flags: Map<Feature, Boolean>,
    val maxBitrateKbps: Int,
    val preferredFormat: AudioFormat,
    val qualityLabel: String
) {
    operator fun get(feature: Feature): Boolean? = flags[feature]

    companion object {
        fun default() = icmFree()

        /** ICM без подписки — ограниченный функционал. */
        fun icmFree() = Capabilities(
            flags = mapOf(
                Feature.DOWNLOAD to false,
                Feature.HIGH_QUALITY to false,
                Feature.LOSSLESS to false,
                Feature.PERSONAL_WAVE to false,
                Feature.FULL_CATALOG to false,
                Feature.BACKGROUND_PLAYBACK to true,
                Feature.UNLIMITED_SKIPS to false
            ),
            maxBitrateKbps = 128,
            preferredFormat = AudioFormat.AAC,
            qualityLabel = "Free · 128K AAC"
        )

        /** ICM с активной подпиской — полный функционал. */
        fun icmPremium(maxBitrate: Int) = Capabilities(
            flags = mapOf(
                Feature.DOWNLOAD to true,
                Feature.HIGH_QUALITY to (maxBitrate >= 320),
                Feature.LOSSLESS to (maxBitrate >= 900),
                Feature.PERSONAL_WAVE to true,
                Feature.FULL_CATALOG to true,
                Feature.BACKGROUND_PLAYBACK to true,
                Feature.UNLIMITED_SKIPS to true
            ),
            maxBitrateKbps = maxBitrate,
            preferredFormat = if (maxBitrate >= 900) AudioFormat.FLAC else AudioFormat.AAC,
            qualityLabel = if (maxBitrate >= 900) "Premium · Lossless" else "Premium · ${maxBitrate}K AAC"
        )
    }
}
