package com.qartvelo.reactnative

import com.qartvelo.sdk.DEFAULT_BASE_URL
import com.qartvelo.sdk.QartveloAdsLogLevel
import com.qartvelo.sdk.QartveloAdsOptions
import com.qartvelo.sdk.QartveloAdsPrivacy
import java.util.Locale

internal data class InitConfig(val appKey: String, val options: QartveloAdsOptions)

/**
 * Parses the JS option maps (already validated by the TypeScript layer, re-checked here because the
 * native module is reachable directly). Throws [IllegalArgumentException] with a readable message.
 */
internal object Options {
    private val defaults = QartveloAdsOptions()

    fun parseInit(map: Map<String, Any?>): InitConfig {
        val appKey = (map["appKey"] as? String)?.trim().orEmpty()
        require(appKey.isNotEmpty()) { "appKey must be a non-empty string" }

        val timeout = number(map, "requestTimeoutMs")?.let {
            require(it.isFinite() && it > 0) { "requestTimeoutMs must be positive" }
            Math.round(it)
        }
        val baseUrl = (map["baseUrl"] as? String)?.trim()?.takeIf { it.isNotEmpty() }?.also {
            require(it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true)) {
                "baseUrl must be an http(s) URL"
            }
        }
        val units = (map["admobAdUnits"] as? Map<*, *>)?.entries?.mapNotNull { (key, value) ->
            val code = (key as? String)?.trim().orEmpty()
            val unit = (value as? String)?.trim().orEmpty()
            if (code.isEmpty() || unit.isEmpty()) null else code to unit
        }?.toMap()

        return InitConfig(
            appKey,
            QartveloAdsOptions(
                admobFallback = bool(map, "admobFallback") ?: defaults.admobFallback,
                requestTimeoutMs = timeout ?: defaults.requestTimeoutMs,
                testMode = bool(map, "testMode") ?: defaults.testMode,
                testForceNoFill = bool(map, "testForceNoFill") ?: defaults.testForceNoFill,
                logLevel = (map["logLevel"] as? String)?.let(::parseLogLevel) ?: defaults.logLevel,
                baseUrl = baseUrl ?: DEFAULT_BASE_URL,
                admobAdUnits = units ?: emptyMap(),
            ),
        )
    }

    fun parseLogLevel(value: String): QartveloAdsLogLevel {
        val level = QartveloAdsLogLevel.entries.firstOrNull { it.name == value.trim().uppercase(Locale.ROOT) }
        return requireNotNull(level) { "Unknown logLevel '$value'" }
    }

    /** Missing keys stay null ("unknown"): consent is never assumed. */
    fun parsePrivacy(map: Map<String, Any?>): QartveloAdsPrivacy = QartveloAdsPrivacy(
        consentGiven = bool(map, "consentGiven"),
        childDirected = bool(map, "childDirected"),
        underAgeOfConsent = bool(map, "underAgeOfConsent"),
    )

    private fun bool(map: Map<String, Any?>, key: String): Boolean? = when (val value = map[key]) {
        null -> null
        is Boolean -> value
        else -> throw IllegalArgumentException("$key must be a boolean")
    }

    private fun number(map: Map<String, Any?>, key: String): Double? = when (val value = map[key]) {
        null -> null
        is Number -> value.toDouble()
        else -> throw IllegalArgumentException("$key must be a number")
    }
}
