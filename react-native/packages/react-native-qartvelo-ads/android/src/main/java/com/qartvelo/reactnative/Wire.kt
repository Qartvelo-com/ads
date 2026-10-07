package com.qartvelo.reactnative

import com.qartvelo.sdk.AdFormat
import com.qartvelo.sdk.AdSource
import com.qartvelo.sdk.QartveloAdsAdInfo
import com.qartvelo.sdk.QartveloAdsError
import com.qartvelo.sdk.QartveloAdsErrorCode
import com.qartvelo.sdk.QartveloAdsListener
import com.qartvelo.sdk.QartveloAdsReward
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/*
 * Conversions from SDK types to the JavaScript wire format (plain maps; converted to WritableMap at the
 * React Native boundary). Enum values are lower-case, matching the TypeScript types.
 */

internal val AdFormat.wire: String get() = name.lowercase(Locale.ROOT)
internal val AdSource.wire: String get() = name.lowercase(Locale.ROOT)
internal val QartveloAdsErrorCode.wire: String get() = name.lowercase(Locale.ROOT)

internal object Wire {
    fun adInfo(info: QartveloAdsAdInfo): Map<String, Any?> = LinkedHashMap<String, Any?>().apply {
        put("placementId", info.placementId)
        put("format", info.format.wire)
        put("source", info.source.wire)
        info.campaignId?.let { put("campaignId", it) }
        info.creativeId?.let { put("creativeId", it) }
    }

    fun reward(reward: QartveloAdsReward): Map<String, Any?> =
        mapOf("type" to reward.type, "amount" to reward.amount.toDouble())

    fun error(error: QartveloAdsError): Map<String, Any?> =
        mapOf("code" to error.code.wire, "message" to error.message)

    /** Result of a show call. `rewarded` is true only when a reward was confirmed for this show. */
    fun showResult(shown: Boolean, source: AdSource?, reward: QartveloAdsReward?): Map<String, Any?> =
        LinkedHashMap<String, Any?>().apply {
            put("shown", shown)
            put("rewarded", shown && reward != null)
            if (shown && source != null) put("source", source.wire)
            if (shown && reward != null) put("reward", reward(reward))
        }

    fun event(type: String, info: QartveloAdsAdInfo): Map<String, Any?> =
        LinkedHashMap<String, Any?>().apply {
            put("type", type)
            putAll(adInfo(info))
        }

    fun event(type: String, placementId: String, format: AdFormat?): MutableMap<String, Any?> =
        LinkedHashMap<String, Any?>().apply {
            put("type", type)
            put("placementId", placementId)
            format?.let { put("format", it.wire) }
        }

    /**
     * Banner component events are flat (Fabric event payloads): `error` becomes `errorCode` and
     * `errorMessage`. Rewards never occur on banners.
     */
    fun flatten(event: Map<String, Any?>): Map<String, Any?> = LinkedHashMap<String, Any?>().apply {
        for ((key, value) in event) {
            when (key) {
                "error" -> (value as? Map<*, *>)?.let {
                    put("errorCode", it["code"])
                    put("errorMessage", it["message"])
                }
                "reward" -> Unit
                else -> put(key, value)
            }
        }
    }
}

/**
 * Format last used for each placement code by this app. SDK load failures carry no format, so the
 * event stream fills it in from here. Thread-safe.
 */
internal object PlacementFormats {
    private val formats = ConcurrentHashMap<String, AdFormat>()

    fun record(placementId: String, format: AdFormat) {
        val id = placementId.trim()
        if (id.isNotEmpty()) formats[id] = format
    }

    operator fun get(placementId: String): AdFormat? = formats[placementId.trim()]

    fun clear() = formats.clear()
}

/**
 * Turns SDK listener callbacks into wire events (one map per callback) for [sink]. Used as the SDK's
 * global observer by the module and as the per-view listener of each banner.
 */
internal class AdEventForwarder(
    private val formatOf: (String) -> AdFormat?,
    private val sink: (Map<String, Any?>) -> Unit,
) : QartveloAdsListener {
    override fun onLoaded(info: QartveloAdsAdInfo) = emit(info.placementId, info.format, Wire.event("loaded", info))

    override fun onLoadFailed(placementId: String, error: QartveloAdsError) {
        val event = Wire.event("loadFailed", placementId, formatOf(placementId))
        event["error"] = Wire.error(error)
        sink(event)
    }

    override fun onShown(info: QartveloAdsAdInfo) = emit(info.placementId, info.format, Wire.event("shown", info))

    override fun onImpression(info: QartveloAdsAdInfo) = emit(info.placementId, info.format, Wire.event("impression", info))

    override fun onClicked(info: QartveloAdsAdInfo) = emit(info.placementId, info.format, Wire.event("clicked", info))

    override fun onDismissed(info: QartveloAdsAdInfo) = emit(info.placementId, info.format, Wire.event("dismissed", info))

    override fun onReward(info: QartveloAdsAdInfo, reward: QartveloAdsReward) {
        val event = LinkedHashMap(Wire.event("rewarded", info))
        event["reward"] = Wire.reward(reward)
        emit(info.placementId, info.format, event)
    }

    override fun onFallbackStarted(placementId: String, format: AdFormat, reason: String) {
        val event = Wire.event("fallbackStarted", placementId, format)
        event["reason"] = reason
        emit(placementId, format, event)
    }

    override fun onNoAdAvailable(placementId: String, format: AdFormat) =
        emit(placementId, format, Wire.event("noAdAvailable", placementId, format))

    private fun emit(placementId: String, format: AdFormat, event: Map<String, Any?>) {
        PlacementFormats.record(placementId, format)
        sink(event)
    }
}
