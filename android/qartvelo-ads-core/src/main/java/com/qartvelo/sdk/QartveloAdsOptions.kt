package com.qartvelo.sdk

/** Placeholder production endpoint; real deployments pass their own [QartveloAdsOptions.baseUrl]. */
public const val DEFAULT_BASE_URL: String = "https://ads.qartvelo.com/"

public data class QartveloAdsOptions @JvmOverloads constructor(
    /** Allow the optional `qartvelo-ads-admob` adapter to serve the publisher's own AdMob units. */
    val admobFallback: Boolean = true,
    /** QartveloAds request budget before falling back. A remote per-placement value takes precedence. */
    val requestTimeoutMs: Long = 800,
    /** Never serve billable campaigns; AdMob uses Google's public test units. */
    val testMode: Boolean = false,
    /** In test mode, force QartveloAds to answer `no_fill` so the fallback path can be exercised. */
    val testForceNoFill: Boolean = false,
    val logLevel: QartveloAdsLogLevel = QartveloAdsLogLevel.ERROR,
    val baseUrl: String = DEFAULT_BASE_URL,
    /** Placement code -> the publisher's AdMob ad unit id. Wins over the server-side mapping. */
    val admobAdUnits: Map<String, String> = emptyMap(),
)
