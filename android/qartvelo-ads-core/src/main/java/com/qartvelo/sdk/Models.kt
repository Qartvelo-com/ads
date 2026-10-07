package com.qartvelo.sdk

/** Verbosity of the SDK's logcat output (tag `QartveloAds`). Tokens and signatures are never logged. */
public enum class QartveloAdsLogLevel { NONE, ERROR, INFO, DEBUG }

public enum class AdFormat { BANNER, INTERSTITIAL, REWARDED }

/** Which network delivered the ad that was loaded or shown. */
public enum class AdSource { QARTVELO, ADMOB }

public data class QartveloAdsAdInfo(
    val placementId: String,
    val format: AdFormat,
    val source: AdSource,
    val campaignId: String? = null,
    val creativeId: String? = null,
)

public data class QartveloAdsReward(val type: String = "reward", val amount: Int = 1)

public enum class QartveloAdsErrorCode {
    NOT_INITIALIZED,
    INVALID_PLACEMENT,
    NETWORK_ERROR,
    TIMEOUT,
    NO_FILL,
    CREATIVE_FAILED,
    AD_EXPIRED,
    SHOW_FAILED,
    ALREADY_SHOWING,
    INTERNAL_ERROR,
}

public data class QartveloAdsError(val code: QartveloAdsErrorCode, val message: String)

/**
 * Privacy signals supplied by the host app. `null` means unknown: the SDK never assumes consent
 * and never collects consent on the app's behalf. Signals are forwarded to the fallback adapter.
 */
public data class QartveloAdsPrivacy(
    val consentGiven: Boolean? = null,
    val childDirected: Boolean? = null,
    val underAgeOfConsent: Boolean? = null,
)
