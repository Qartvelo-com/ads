package com.qartvelo.sdk

/** Placeholder production endpoint; real deployments pass their own [QartveloAdsOptions.baseUrl]. */
public const val DEFAULT_BASE_URL: String = "https://ads.qartvelo.com/"

public data class QartveloAdsOptions @JvmOverloads constructor(
    /** Allow the optional `qartvelo-ads-admob` adapter to serve the publisher's own AdMob units. */
    val admobFallback: Boolean = true,
    /** QartveloAds request budget before falling back. A remote per-placement value takes precedence. */
    val requestTimeoutMs: Long = 800,
    /**
     * Never serve billable campaigns: ads are labelled "Test ad" and never billed; AdMob uses
     * Google's public test units. Always on in emulators, and on automatically in debug builds,
     * see [testModeInDebugBuilds].
     */
    val testMode: Boolean = false,
    /** In test mode, force QartveloAds to answer `no_fill` so the fallback path can be exercised. */
    val testForceNoFill: Boolean = false,
    val logLevel: QartveloAdsLogLevel = QartveloAdsLogLevel.ERROR,
    val baseUrl: String = DEFAULT_BASE_URL,
    /** Placement code -> the publisher's AdMob ad unit id. Wins over the server-side mapping. */
    val admobAdUnits: Map<String, String> = emptyMap(),
    /**
     * Turn test mode on automatically when the app is a debuggable (developer) build, like AdMob
     * test devices: real ads are shown but labelled "Test ad" and never billed. Release builds are
     * not debuggable, so they are unaffected. Set to false to see exactly what a release build does.
     */
    val testModeInDebugBuilds: Boolean = true,
    /**
     * Use the fallback network's public test units (Google's for AdMob) in debuggable builds, even
     * when test mode is off, so development never requests live AdMob ads. Release builds are not
     * debuggable, so they are unaffected. Set to false to request your real AdMob units from a debug
     * build.
     */
    val admobTestUnitsInDebugBuilds: Boolean = true,
)
