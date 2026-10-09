package com.qartvelo.sdk.internal

import com.qartvelo.sdk.AdFormat
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** Per-placement remote configuration returned by `/sdk/initialize`. */
internal data class PlacementConfig(
    val code: String,
    val format: AdFormat?,
    val qartveloEnabled: Boolean,
    val fallbackProvider: String,
    val admobAdUnitId: String?,
    val requestTimeoutMs: Long?,
    val bannerRefreshSeconds: Int,
)

internal data class RemoteConfig(
    val servingEnabled: Boolean,
    val requestTimeoutMs: Long?,
    val fallbackEnabled: Boolean,
    val configTtlSeconds: Long,
    val placements: Map<String, PlacementConfig>,
) {
    companion object {
        const val MIN_BANNER_REFRESH_SECONDS = 30
        const val DEFAULT_BANNER_REFRESH_SECONDS = 60

        /** Parses the `config` + `placements` members of an initialize response (or its cached copy). */
        fun parse(root: JSONObject): RemoteConfig {
            val cfg = root.optJSONObject("config") ?: JSONObject()
            val list = root.optJSONArray("placements")
            val placements = LinkedHashMap<String, PlacementConfig>()
            if (list != null) {
                for (i in 0 until list.length()) {
                    val p = list.optJSONObject(i) ?: continue
                    val code = p.optString("code").takeIf { it.isNotEmpty() } ?: continue
                    placements[code] = PlacementConfig(
                        code = code,
                        format = adFormatFromWire(p.optString("format")),
                        qartveloEnabled = p.optBoolean("ourads_enabled", true),
                        fallbackProvider = p.optStringOrNull("fallback_provider") ?: "admob",
                        admobAdUnitId = p.optStringOrNull("admob_ad_unit_id"),
                        requestTimeoutMs = p.optPositiveLong("request_timeout_ms"),
                        bannerRefreshSeconds = (p.optPositiveLong("banner_refresh_seconds")?.toInt()
                            ?: DEFAULT_BANNER_REFRESH_SECONDS).coerceAtLeast(MIN_BANNER_REFRESH_SECONDS),
                    )
                }
            }
            return RemoteConfig(
                servingEnabled = cfg.optBoolean("serving_enabled", true),
                requestTimeoutMs = cfg.optPositiveLong("request_timeout_ms"),
                fallbackEnabled = cfg.optBoolean("fallback_enabled", true),
                configTtlSeconds = cfg.optPositiveLong("config_ttl_seconds") ?: 3600,
                placements = placements,
            )
        }
    }
}

/** Ephemeral session issued by the backend. Lives in memory only and is never persisted or logged. */
internal class Session(val token: String, val expiresAtElapsed: Long)

internal class InitResult(val session: Session, val config: RemoteConfig, val cacheableJson: String)

internal enum class CreativeType { IMAGE, VIDEO }

/** An ad returned by `/ads/request`. Expiry is converted to the device's monotonic clock. */
internal class ServedAd(
    val requestId: String,
    val adId: String,
    val campaignId: String?,
    val creativeId: String?,
    val format: AdFormat,
    val creativeType: CreativeType,
    val creativeUrl: String,
    val clickUrl: String?,
    val width: Int,
    val height: Int,
    val durationSeconds: Int?,
    val impressionToken: String,
    val expiresAtElapsed: Long,
    val test: Boolean,
) {
    /** Local copy of the creative; set once pre-download succeeded. */
    @Volatile
    var file: File? = null

    /**
     * An ad is only showable while its signed token is still valid for the impression event,
     * with a safety margin for activity start-up and event delivery.
     */
    fun isValid(now: Long = Clock.elapsed()): Boolean =
        now < expiresAtElapsed - EXPIRY_MARGIN_MS && file?.exists() == true

    companion object {
        const val EXPIRY_MARGIN_MS = 5_000L
    }
}

internal sealed class AdResponse {
    class Fill(val ad: ServedAd) : AdResponse()
    class NoFill(val requestId: String?, val fallback: String?, val reason: String?) : AdResponse()
}

/** Non-2xx API answer with the contract's error envelope. */
internal class ApiException(
    val httpStatus: Int,
    val code: String,
    message: String,
    /** The envelope's optional `error.details` object. */
    val details: JSONObject? = null,
) : IOException("$code ($httpStatus): $message") {
    val isSessionError: Boolean get() = code == "session_expired" || code == "invalid_session"
}

internal fun JSONObject.optStringOrNull(name: String): String? =
    if (isNull(name)) null else optString(name).takeIf { it.isNotEmpty() }

internal fun JSONObject.optPositiveLong(name: String): Long? =
    if (isNull(name)) null else optLong(name, -1).takeIf { it > 0 }
