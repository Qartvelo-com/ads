package com.qartvelo.sdk.internal

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import com.qartvelo.sdk.AdFormat
import com.qartvelo.sdk.QartveloAds
import com.qartvelo.sdk.QartveloAdsError
import com.qartvelo.sdk.QartveloAdsErrorCode
import com.qartvelo.sdk.QartveloAdsInitListener
import com.qartvelo.sdk.QartveloAdsListener
import com.qartvelo.sdk.QartveloAdsOptions
import com.qartvelo.sdk.QartveloAdsSetupIssue
import com.qartvelo.sdk.fallback.FallbackAdapter
import com.qartvelo.sdk.fallback.FallbackSettings
import org.json.JSONObject
import java.io.IOException
import java.io.InterruptedIOException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Why QartveloAds could not serve a load; [reason] is the `onFallbackStarted` string. */
internal class QartveloAdsFailure(val reason: String, val error: QartveloAdsError, val serverFallback: String? = null) {
    companion object {
        const val NO_FILL = "no_fill"
        const val TIMEOUT = "timeout"
        const val ERROR = "error"
        const val CREATIVE_FAILED = "creative_failed"
        const val DISABLED = "disabled"
    }
}

internal sealed class FetchOutcome {
    class Success(val ad: ServedAd) : FetchOutcome()
    class Failure(val failure: QartveloAdsFailure) : FetchOutcome()
}

/**
 * Everything created by one `QartveloAds.initialize` call. Controllers and mutable state are confined to
 * the main thread; network and disk work runs on [io], [downloads] and the session thread.
 */
internal class Engine(
    val appContext: Context,
    val appKey: String,
    val options: QartveloAdsOptions,
) {
    val io: ExecutorService = newIoExecutor()
    private val downloads: ExecutorService = Executors.newFixedThreadPool(2, daemonThreadFactory("QartveloAds-dl"))
    private val sessionExecutor: ExecutorService = Executors.newSingleThreadExecutor(daemonThreadFactory("QartveloAds-session"))

    @Volatile
    private var device: DeviceInfo = DeviceInfo.basic(appContext)

    val api = ApiClient(
        baseUrl = options.baseUrl,
        userAgent = "QartveloAds-Android/${QartveloAds.SDK_VERSION} (Android ${Build.VERSION.RELEASE}; ${appContext.packageName})",
    )
    private val configStore by lazy { ConfigStore(appContext, appKey) }
    val creatives = CreativeCache(appContext, api)
    val events = EventQueue(api, TestHooks.eventRetryBaseMs ?: EventQueue.DEFAULT_RETRY_BASE_MS)
    val sessions = SessionManager(
        api = api,
        executor = sessionExecutor,
        initBody = ::initBody,
        initTimeoutMs = maxOf(options.requestTimeoutMs, MIN_INIT_TIMEOUT_MS),
        onInitialized = ::onSessionInitialized,
        onFailed = ::onSessionFailed,
    )

    @Volatile
    var remoteConfig: RemoteConfig? = null
        private set

    @Volatile
    var fallbackAdapter: FallbackAdapter? = null
        private set

    /** When the backend last delivered config (0 = only cached or none). */
    @Volatile
    private var configFetchedAt = 0L

    /** When a config refresh was last started or delivered; throttles refreshes. */
    @Volatile
    private var configCheckedAt = 0L

    /** True once the first initialization attempt has finished (`QartveloAds.isInitialized`). */
    @Volatile
    var isReady: Boolean = false
        private set

    /**
     * True once loads may run: the cached config has been read and the fallback adapter installed,
     * and either a cached config exists, the first initialization finished, or [loadGraceMs] passed.
     * Loads never wait for a slow `/sdk/initialize`; the QartveloAds request acquires the session
     * within its own timeout, so a hanging backend falls back quickly (spec backend_unavailable).
     */
    @Volatile
    var loadsReady: Boolean = false
        private set

    // Main-thread state.
    private val pending = ArrayList<() -> Unit>()
    private val initListeners = ArrayList<QartveloAdsInitListener>()
    private var initOutcome: Pair<Boolean, QartveloAdsError?>? = null
    private val interstitials = ConcurrentHashMap<String, FullscreenController>()
    private val rewardeds = ConcurrentHashMap<String, FullscreenController>()
    private val banners = HashMap<String, BannerController>()

    /** Only one full-screen ad (QartveloAds or fallback) may be on screen at a time. */
    var fullscreenShowing: Boolean = false

    private val emulator: Boolean = Emulator.current()
    private val debuggable: Boolean = (appContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    /**
     * Test mode is purely the publisher's local option. The backend only echoes it back, so it is
     * never taken from (cached) remote config: a build that once ran in test mode must not stay in
     * test mode, unbilled and on Google test units, after the option is turned off.
     * Debuggable (developer) builds are in test mode too unless [QartveloAdsOptions.testModeInDebugBuilds]
     * is false; Play only accepts non-debuggable builds, so users never see test ads.
     * Emulators are always in test mode (the backend enforces it from `is_emulator` as well).
     */
    val testMode: Boolean = options.testMode || emulator || (options.testModeInDebugBuilds && debuggable)

    /**
     * The fallback network uses its public test units in test mode and, unless
     * [QartveloAdsOptions.admobTestUnitsInDebugBuilds] is false, in every debuggable build: a
     * developer never requests live AdMob ads, even while watching live Qartvelo Ads campaigns.
     */
    private val fallbackTestUnits: Boolean = testMode || (options.admobTestUnitsInDebugBuilds && debuggable)

    // ---- initialization -------------------------------------------------------------------------

    /** Main thread. Loads the cached config, discovers the adapter and requests a session. */
    fun start(listener: QartveloAdsInitListener?) {
        addInitListener(listener)
        if (emulator) {
            OurLog.i("Emulator: test mode is on, ads are labelled \"Test ad\" and never billed")
        } else if (testMode && !options.testMode) {
            OurLog.i("Debuggable build: test mode is on, ads are labelled \"Test ad\" and never billed (testModeInDebugBuilds)")
        }
        if (fallbackTestUnits && !testMode) {
            OurLog.i("Debuggable build: the AdMob fallback uses Google's test units (admobTestUnitsInDebugBuilds)")
        }
        io.execute {
            guard("cached config") {
                val cached = configStore.load()
                if (remoteConfig == null && cached != null) {
                    remoteConfig = cached
                    OurLog.i("Using cached remote configuration until the backend answers")
                }
            }
            guard("device info") { device = DeviceInfo.collect(appContext) }
            guard("creative purge") { creatives.purgeExpired() }
            val adapter = QartveloAds.registeredAdapter() ?: FallbackDiscovery.discover()
            val hasConfig = remoteConfig != null
            Main.post {
                installAdapter(adapter)
                if (hasConfig) openLoads() else Main.postDelayed(loadGraceMs(), Runnable { openLoads() })
            }
            if (!api.isConfigured) {
                Main.post { finishInit(QartveloAdsError(QartveloAdsErrorCode.INTERNAL_ERROR, "Invalid baseUrl")) }
                return@execute
            }
            configCheckedAt = Clock.elapsed()
            sessions.refreshAsync(force = true) { error ->
                Main.post { finishInit(error?.let { toInitError(it) }) }
            }
        }
    }

    /**
     * Without any cached config, the first loads wait this long for `/sdk/initialize` so they know
     * the server's placement settings and AdMob units; with a cached config they do not wait at all.
     */
    private fun loadGraceMs(): Long = (options.requestTimeoutMs.takeIf { it > 0 } ?: DEFAULT_TIMEOUT_MS)
        .coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)

    fun addInitListener(listener: QartveloAdsInitListener?) {
        if (listener == null) return
        val outcome = initOutcome
        if (outcome != null) {
            Main.post { notifyInit(listener, outcome) }
        } else {
            initListeners.add(listener)
        }
    }

    /** Runs [block] on the main thread once loads may run (see [loadsReady]). */
    fun whenReady(block: () -> Unit) {
        Main.run {
            if (loadsReady) block() else pending.add(block)
        }
    }

    /** Main thread. Releases loads queued during start-up. */
    private fun openLoads() {
        if (loadsReady) return
        loadsReady = true
        val queued = ArrayList(pending)
        pending.clear()
        queued.forEach { guard("queued call") { it() } }
    }

    private fun finishInit(error: QartveloAdsError?) {
        if (initOutcome != null) return
        val outcome = (error == null) to error
        initOutcome = outcome
        isReady = true
        if (error == null) OurLog.i("QartveloAds initialized") else OurLog.e("QartveloAds initialization failed: ${error.message}")
        val listeners = ArrayList(initListeners)
        initListeners.clear()
        listeners.forEach { l -> Main.post { notifyInit(l, outcome) } }
        openLoads()
    }

    private fun notifyInit(listener: QartveloAdsInitListener, outcome: Pair<Boolean, QartveloAdsError?>) {
        try {
            listener.onInitialized(outcome.first, outcome.second)
        } catch (t: Throwable) {
            OurLog.e("Init listener threw", t)
        }
    }

    private fun onSessionInitialized(result: InitResult) {
        remoteConfig = result.config
        configFetchedAt = Clock.elapsed()
        configCheckedAt = configFetchedAt
        guard("config persist") { configStore.save(result.cacheableJson) }
        Main.post {
            updateAdapterSettings()
            recheckPlacements()
        }
    }

    private val reportedSetupIssues: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Logs a setup issue once per process and tells the global listeners. Any thread. */
    fun reportSetupIssue(code: String, message: String, placementId: String? = null) {
        if (!reportedSetupIssues.add("$code|${placementId.orEmpty()}")) return
        OurLog.e("Setup issue ($code): $message")
        val issue = QartveloAdsSetupIssue(code, message, placementId)
        Listeners.emit(emptyList<QartveloAdsListener?>(), "onSetupIssue") { it.onSetupIssue(issue) }
    }

    /** Session executor. Turns rejected app keys into setup issues. */
    private fun onSessionFailed(t: Throwable) {
        val api = t as? ApiException ?: return
        val running = appContext.packageName
        when (api.code) {
            QartveloAdsSetupIssue.PACKAGE_MISMATCH -> {
                val registered = api.details?.optString("registered_package").orEmpty()
                val message = if (registered.isNotEmpty()) {
                    "This app key is registered for '$registered', but this app is '$running'. Use the key of the app registered for '$running', or correct the package name in the Qartvelo Ads dashboard."
                } else {
                    "This app key is not registered for '$running'. Use the key of the app registered for '$running', or correct the package name in the Qartvelo Ads dashboard."
                }
                reportSetupIssue(QartveloAdsSetupIssue.PACKAGE_MISMATCH, message)
            }
            QartveloAdsSetupIssue.PLATFORM_MISMATCH -> {
                val owner = if (api.details?.optString("platform") == "ios") "the iOS app" else "an app of another platform"
                reportSetupIssue(
                    QartveloAdsSetupIssue.PLATFORM_MISMATCH,
                    "This app key belongs to $owner. Register this Android app in the Qartvelo Ads dashboard and use its own key.",
                )
            }
        }
    }

    private fun toInitError(t: Throwable): QartveloAdsError = when {
        t is ApiException -> QartveloAdsError(QartveloAdsErrorCode.NOT_INITIALIZED, "${t.code}: rejected by the QartveloAds backend")
        t is InterruptedIOException -> QartveloAdsError(QartveloAdsErrorCode.TIMEOUT, "QartveloAds backend did not answer in time")
        t is IOException -> QartveloAdsError(QartveloAdsErrorCode.NETWORK_ERROR, "QartveloAds backend unreachable")
        else -> QartveloAdsError(QartveloAdsErrorCode.INTERNAL_ERROR, t.javaClass.simpleName)
    }

    // ---- fallback adapter -----------------------------------------------------------------------

    /** Main thread. */
    fun installAdapter(adapter: FallbackAdapter?) {
        if (adapter == null || adapter === fallbackAdapter) return
        try {
            adapter.initialize(appContext, fallbackSettings())
            fallbackAdapter = adapter
        } catch (t: Throwable) {
            OurLog.e("Fallback adapter failed to initialize; continuing without it", t)
        }
    }

    /** Main thread. */
    fun updateAdapterSettings() {
        val adapter = fallbackAdapter ?: return
        guard("adapter settings") { adapter.updateSettings(fallbackSettings()) }
    }

    private fun fallbackSettings() = FallbackSettings(testMode = fallbackTestUnits, privacy = QartveloAds.currentPrivacy())

    /**
     * The fallback ad unit for a placement, or null when fallback is impossible: adapter missing,
     * disabled locally or remotely, or no unit configured. In test mode the adapter substitutes
     * Google's test units, so an unmapped placement still falls back (with an empty id).
     */
    fun fallbackUnit(placementId: String, placement: PlacementConfig?, serverFallback: String? = null): String? {
        if (!options.admobFallback || fallbackAdapter == null) return null
        if (remoteConfig?.fallbackEnabled == false) return null
        if (placement?.fallbackProvider == "none" || serverFallback == "none") return null
        val unit = options.admobAdUnits[placementId]?.takeIf { it.isNotBlank() } ?: placement?.admobAdUnitId
        return unit ?: if (testMode) "" else null
    }

    /** Fire-and-forget fallback telemetry (`/events/fallback`). */
    fun reportFallback(placementId: String, reason: String) {
        val token = sessions.currentToken() ?: return
        val wireReason = when (reason) {
            QartveloAdsFailure.DISABLED -> QartveloAdsFailure.NO_FILL
            else -> reason
        }
        val body = JSONObject().put("session_token", token).put("placement", placementId).put("reason", wireReason)
        try {
            io.execute {
                try {
                    api.postEvent("api/v1/events/fallback", body, FALLBACK_TELEMETRY_TIMEOUT_MS)
                } catch (t: Throwable) {
                    OurLog.d("Fallback telemetry not delivered: ${t.message}")
                }
            }
        } catch (_: Throwable) {
            // Executor shut down; telemetry is optional.
        }
    }

    // ---- placements -----------------------------------------------------------------------------

    fun placement(placementId: String): PlacementConfig? = remoteConfig?.placements?.get(placementId)

    /** Placements used before fresh config arrived; checked again once it does. Main thread. */
    private val uncheckedPlacements = LinkedHashMap<String, AdFormat>()

    /**
     * Reports a placement code the dashboard does not have, or has with another format. Only config
     * fetched from the backend in this process is trusted: a cached copy can predate placements
     * created since. Main thread.
     */
    fun checkPlacement(placementId: String, format: AdFormat) {
        // Read the fetch marker first: the fresh config is stored before it is set.
        val fetched = configFetchedAt != 0L
        val config = remoteConfig
        if (!fetched || config == null) {
            uncheckedPlacements[placementId] = format
            return
        }
        val placement = config.placements[placementId]
        when {
            placement == null -> reportSetupIssue(
                QartveloAdsSetupIssue.UNKNOWN_PLACEMENT,
                "Placement '$placementId' does not exist for this Android app. Create it in the Qartvelo Ads dashboard as a ${format.wireName} placement.",
                placementId,
            )
            placement.format != null && placement.format != format -> reportSetupIssue(
                QartveloAdsSetupIssue.FORMAT_MISMATCH,
                "Placement '$placementId' is a ${placement.format.wireName} placement but is used as ${format.wireName}. Use a ${format.wireName} placement code.",
                placementId,
            )
        }
    }

    /** Main thread. */
    private fun recheckPlacements() {
        val pending = LinkedHashMap(uncheckedPlacements)
        uncheckedPlacements.clear()
        pending.forEach { (id, format) -> checkPlacement(id, format) }
    }

    fun ourAdsEnabled(placement: PlacementConfig?): Boolean =
        remoteConfig?.servingEnabled != false && placement?.qartveloEnabled != false

    /** Remote per-placement timeout wins, otherwise the publisher's option. */
    fun effectiveTimeoutMs(placement: PlacementConfig?): Long {
        val local = options.requestTimeoutMs.takeIf { it > 0 } ?: DEFAULT_TIMEOUT_MS
        return (placement?.requestTimeoutMs ?: local).coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)
    }

    fun interstitial(placementId: String): FullscreenController =
        interstitials.getOrPut(placementId) { FullscreenController(this, placementId, AdFormat.INTERSTITIAL) }

    fun rewarded(placementId: String): FullscreenController =
        rewardeds.getOrPut(placementId) { FullscreenController(this, placementId, AdFormat.REWARDED) }

    fun existingFullscreen(placementId: String, format: AdFormat): FullscreenController? =
        if (format == AdFormat.REWARDED) rewardeds[placementId] else interstitials[placementId]

    /** Main thread. One controller per placement survives view re-creation. */
    fun banner(placementId: String): BannerController =
        banners.getOrPut(placementId) { BannerController(this, placementId) }

    // ---- QartveloAds fetch pipeline ------------------------------------------------------------------

    /**
     * Requests an QartveloAds ad bounded by [timeoutMs] (session acquisition included), then pre-downloads
     * and validates the creative so that a creative failure becomes a fallback rather than a broken
     * show. [onResult] is called exactly once, on the main thread.
     */
    fun fetchOurAd(placementId: String, format: AdFormat, timeoutMs: Long, onResult: (FetchOutcome) -> Unit) {
        val tracker = CallTracker()
        var finished = false
        var responded = false
        fun finish(outcome: FetchOutcome) {
            if (finished) return
            finished = true
            onResult(outcome)
        }
        val timeout = Runnable {
            if (!responded && !finished) {
                tracker.cancel()
                finish(FetchOutcome.Failure(failure(QartveloAdsFailure.TIMEOUT)))
            }
        }
        Main.postDelayed(timeoutMs, timeout)
        val deadline = Clock.elapsed() + timeoutMs
        io.execute {
            val result: Any = try {
                requestWithSessionRetry(placementId, format, deadline, tracker)
            } catch (t: Throwable) {
                t
            }
            Main.post {
                if (finished) return@post
                responded = true
                Main.cancel(timeout)
                when (result) {
                    is AdResponse.Fill -> prepareCreative(result.ad, format, tracker) { finish(it) }
                    is AdResponse.NoFill -> {
                        OurLog.i("QartveloAds no fill for '$placementId' (${result.reason ?: "unknown"})")
                        finish(FetchOutcome.Failure(failure(QartveloAdsFailure.NO_FILL, serverFallback = result.fallback)))
                    }
                    is Throwable -> finish(FetchOutcome.Failure(failureFor(result)))
                    else -> finish(FetchOutcome.Failure(failure(QartveloAdsFailure.ERROR)))
                }
            }
        }
    }

    /**
     * Called before every load decision (full-screen and banner), including loads that QartveloAds
     * itself would not serve, so a kill switch that is turned back on reaches a running app.
     * Re-fetches remote config (and the session) in the background when it is stale:
     * - after `config_ttl_seconds`;
     * - sooner (at most [DISABLED_RECHECK_MS]) while QartveloAds is switched off for [placement];
     * - every [CONFIG_RETRY_MS] while only a cached config (or none) is known, e.g. after an
     *   offline start.
     * Returns true when a refresh was started; the caller then asks the backend for an ad even if the
     * stale config says QartveloAds is off, because `/ads/request` re-checks every kill switch itself.
     */
    fun refreshConfigIfStale(placement: PlacementConfig?): Boolean {
        if (!api.isConfigured) return false
        val ttlMs = (remoteConfig?.configTtlSeconds ?: DEFAULT_CONFIG_TTL_SECONDS) * 1000
        val intervalMs = when {
            configFetchedAt == 0L -> CONFIG_RETRY_MS
            !ourAdsEnabled(placement) -> minOf(ttlMs, DISABLED_RECHECK_MS)
            else -> ttlMs
        }
        val now = Clock.elapsed()
        if (now - configCheckedAt < intervalMs) return false
        configCheckedAt = now // At most one refresh per window, even if it fails.
        OurLog.d("Remote configuration is stale; refreshing")
        sessions.refreshAsync(force = true)
        return true
    }

    private fun prepareCreative(ad: ServedAd, format: AdFormat, tracker: CallTracker, done: (FetchOutcome) -> Unit) {
        if (ad.format != format || Clock.elapsed() >= ad.expiresAtElapsed - ServedAd.EXPIRY_MARGIN_MS) {
            OurLog.e("Discarding unusable QartveloAds response (format mismatch or already expired)")
            done(FetchOutcome.Failure(failure(QartveloAdsFailure.ERROR)))
            return
        }
        downloads.execute {
            val outcome = try {
                ad.file = creatives.fetch(ad, tracker)
                OurLog.d("Creative ready for ${ad.requestId} (${ad.creativeType.name.lowercase()})")
                FetchOutcome.Success(ad)
            } catch (t: Throwable) {
                OurLog.e("Creative unavailable: ${t.message}")
                FetchOutcome.Failure(failure(QartveloAdsFailure.CREATIVE_FAILED))
            }
            Main.post { done(outcome) }
        }
    }

    /** Blocking. One transparent retry when the backend reports the session expired or invalid. */
    private fun requestWithSessionRetry(placementId: String, format: AdFormat, deadline: Long, tracker: CallTracker): AdResponse {
        var token = sessions.acquire(deadline)
        return try {
            api.requestAd(adRequestBody(placementId, format, token), remaining(deadline), tracker)
        } catch (e: ApiException) {
            if (!e.isSessionError) throw e
            OurLog.i("Session rejected (${e.code}); refreshing once")
            sessions.invalidate(token)
            token = sessions.acquire(deadline)
            api.requestAd(adRequestBody(placementId, format, token), remaining(deadline), tracker)
        }
    }

    private fun remaining(deadline: Long): Long {
        val left = deadline - Clock.elapsed()
        if (left <= 0) throw InterruptedIOException("QartveloAds request budget exhausted")
        return left
    }

    private fun failureFor(t: Throwable): QartveloAdsFailure = when {
        t is InterruptedIOException -> failure(QartveloAdsFailure.TIMEOUT)
        t is ApiException && (t.code == "placement_not_found" || t.code == "format_mismatch") -> QartveloAdsFailure(
            QartveloAdsFailure.ERROR,
            QartveloAdsError(QartveloAdsErrorCode.INVALID_PLACEMENT, "${t.code}: check the placement code and format"),
        )
        t is IOException -> failure(QartveloAdsFailure.ERROR).also { OurLog.i("QartveloAds request failed: ${t.message}") }
        else -> QartveloAdsFailure(QartveloAdsFailure.ERROR, QartveloAdsError(QartveloAdsErrorCode.INTERNAL_ERROR, t.javaClass.simpleName))
            .also { OurLog.e("Unexpected QartveloAds request failure", t) }
    }

    private fun failure(reason: String, serverFallback: String? = null): QartveloAdsFailure {
        val error = when (reason) {
            QartveloAdsFailure.NO_FILL, QartveloAdsFailure.DISABLED -> QartveloAdsError(QartveloAdsErrorCode.NO_FILL, "No QartveloAds campaign available")
            QartveloAdsFailure.TIMEOUT -> QartveloAdsError(QartveloAdsErrorCode.TIMEOUT, "QartveloAds did not answer within the timeout")
            QartveloAdsFailure.CREATIVE_FAILED -> QartveloAdsError(QartveloAdsErrorCode.CREATIVE_FAILED, "QartveloAds creative could not be loaded")
            else -> QartveloAdsError(QartveloAdsErrorCode.NETWORK_ERROR, "QartveloAds request failed")
        }
        return QartveloAdsFailure(reason, error, serverFallback)
    }

    // ---- request bodies -------------------------------------------------------------------------

    private fun initBody(): JSONObject {
        val d = device
        return JSONObject()
            .put("app_key", appKey)
            .put("package_name", d.packageName)
            .put("sdk_version", QartveloAds.SDK_VERSION)
            .put("app_version", d.appVersion)
            .put("platform", "android")
            .put("os_version", d.osVersion)
            .put("test_mode", testMode)
            .put("is_emulator", emulator)
    }

    private fun adRequestBody(placementId: String, format: AdFormat, token: String): JSONObject {
        val d = device
        return JSONObject()
            .put("app_key", appKey)
            .put("placement", placementId)
            .put("format", format.wireName)
            .put("session_token", token)
            .put("language", Locale.getDefault().language.ifEmpty { "en" })
            .put("android_version", d.androidMajor)
            .put("app_version", d.appVersion)
            .put("sdk_version", QartveloAds.SDK_VERSION)
            .put("screen_width", d.screenWidth)
            .put("screen_height", d.screenHeight)
            .put("test_mode", testMode)
            .put("test_force_no_fill", options.testForceNoFill)
    }

    /** Tests only: stop background threads. */
    fun shutdown() {
        io.shutdownNow()
        downloads.shutdownNow()
        sessionExecutor.shutdownNow()
        events.shutdown()
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 800L
        const val MIN_TIMEOUT_MS = 100L
        const val MAX_TIMEOUT_MS = 10_000L
        const val MIN_INIT_TIMEOUT_MS = 3_000L
        const val FALLBACK_TELEMETRY_TIMEOUT_MS = 5_000L
        const val DEFAULT_CONFIG_TTL_SECONDS = 3_600L
        const val DISABLED_RECHECK_MS = 5 * 60_000L
        const val CONFIG_RETRY_MS = 60_000L
    }
}
