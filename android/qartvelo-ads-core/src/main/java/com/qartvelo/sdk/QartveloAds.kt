package com.qartvelo.sdk

import android.app.Activity
import android.content.Context
import com.qartvelo.sdk.fallback.FallbackAdapter
import com.qartvelo.sdk.internal.Engine
import com.qartvelo.sdk.internal.FullscreenController
import com.qartvelo.sdk.internal.Listeners
import com.qartvelo.sdk.internal.Main
import com.qartvelo.sdk.internal.OurLog
import com.qartvelo.sdk.internal.ShowRegistry

/**
 * Entry point of the QartveloAds SDK. Every method is safe to call from any thread, never throws, and
 * delivers callbacks on the main thread. No networking or disk access happens on the caller's thread.
 */
public object QartveloAds {
    public const val SDK_VERSION: String = "0.3.1"

    private val lock = Any()

    @Volatile
    private var engine: Engine? = null

    @Volatile
    private var privacy: QartveloAdsPrivacy = QartveloAdsPrivacy()

    @Volatile
    private var registeredAdapter: FallbackAdapter? = null

    @Volatile
    private var logLevelOverride: QartveloAdsLogLevel? = null

    /**
     * Initializes the SDK once (idempotent). Only the application context is retained. Later calls are
     * ignored apart from [listener], which receives the result of the first initialization.
     */
    @JvmStatic
    @JvmOverloads
    public fun initialize(
        context: Context,
        appKey: String,
        options: QartveloAdsOptions = QartveloAdsOptions(),
        listener: QartveloAdsInitListener? = null,
    ) {
        try {
            val created: Engine
            synchronized(lock) {
                val existing = engine
                if (existing != null) {
                    if (existing.appKey != appKey || existing.options != options) {
                        OurLog.i("QartveloAds is already initialized; ignoring the new appKey/options")
                    }
                    Main.run { existing.addInitListener(listener) }
                    return
                }
                OurLog.level = logLevelOverride ?: options.logLevel
                if (appKey.isBlank()) {
                    OurLog.e("QartveloAds.initialize called with an empty appKey")
                    notifyInitFailure(listener, QartveloAdsError(QartveloAdsErrorCode.NOT_INITIALIZED, "appKey is empty"))
                    return
                }
                created = Engine(context.applicationContext ?: context, appKey.trim(), options)
                engine = created
            }
            Main.run { created.start(listener) }
        } catch (t: Throwable) {
            OurLog.e("QartveloAds.initialize failed", t)
            notifyInitFailure(listener, QartveloAdsError(QartveloAdsErrorCode.INTERNAL_ERROR, t.javaClass.simpleName))
        }
    }

    /** True once the first initialization attempt has finished (possibly in offline/fallback mode). */
    @JvmStatic
    public fun isInitialized(): Boolean = engine?.isReady == true

    @JvmStatic
    @JvmOverloads
    public fun loadInterstitial(placementId: String, listener: QartveloAdsListener? = null) {
        load(placementId, AdFormat.INTERSTITIAL, listener)
    }

    @JvmStatic
    @JvmOverloads
    public fun showInterstitial(activity: Activity, placementId: String, listener: QartveloAdsListener? = null) {
        show(activity, placementId, AdFormat.INTERSTITIAL, listener)
    }

    @JvmStatic
    public fun isInterstitialReady(placementId: String): Boolean = isReady(placementId, AdFormat.INTERSTITIAL)

    @JvmStatic
    @JvmOverloads
    public fun loadRewarded(placementId: String, listener: QartveloAdsListener? = null) {
        load(placementId, AdFormat.REWARDED, listener)
    }

    @JvmStatic
    @JvmOverloads
    public fun showRewarded(activity: Activity, placementId: String, listener: QartveloAdsListener? = null) {
        show(activity, placementId, AdFormat.REWARDED, listener)
    }

    @JvmStatic
    public fun isRewardedReady(placementId: String): Boolean = isReady(placementId, AdFormat.REWARDED)

    /** Observes events of every placement and format (in addition to per-call listeners). */
    @JvmStatic
    public fun addEventListener(listener: QartveloAdsListener) {
        Listeners.add(listener)
    }

    @JvmStatic
    public fun removeEventListener(listener: QartveloAdsListener) {
        Listeners.remove(listener)
    }

    @JvmStatic
    public fun setLogLevel(level: QartveloAdsLogLevel) {
        logLevelOverride = level
        OurLog.level = level
    }

    /** Updates privacy signals; they are forwarded to the fallback adapter. Consent is never assumed. */
    @JvmStatic
    public fun setPrivacy(privacy: QartveloAdsPrivacy) {
        this.privacy = privacy
        engine?.let { e -> Main.run { e.updateAdapterSettings() } }
    }

    /** Registers a fallback adapter explicitly; otherwise `qartvelo-ads-admob` is discovered automatically. */
    @JvmStatic
    public fun registerFallbackAdapter(adapter: FallbackAdapter) {
        registeredAdapter = adapter
        engine?.let { e -> Main.run { e.installAdapter(adapter) } }
    }

    // ---- internals ------------------------------------------------------------------------------

    internal fun engine(): Engine? = engine

    internal fun registeredAdapter(): FallbackAdapter? = registeredAdapter

    internal fun currentPrivacy(): QartveloAdsPrivacy = privacy

    private fun load(placementId: String, format: AdFormat, listener: QartveloAdsListener?) {
        try {
            val e = engine
            val id = placementId.trim()
            when {
                e == null -> fail(listener, id, QartveloAdsErrorCode.NOT_INITIALIZED, "Call QartveloAds.initialize() first")
                id.isEmpty() -> fail(listener, id, QartveloAdsErrorCode.INVALID_PLACEMENT, "placementId is empty")
                else -> e.whenReady { controller(e, id, format).load(listener) }
            }
        } catch (t: Throwable) {
            OurLog.e("QartveloAds load failed", t)
            fail(listener, placementId, QartveloAdsErrorCode.INTERNAL_ERROR, t.javaClass.simpleName)
        }
    }

    private fun show(activity: Activity, placementId: String, format: AdFormat, listener: QartveloAdsListener?) {
        try {
            val e = engine
            val id = placementId.trim()
            when {
                e == null -> fail(listener, id, QartveloAdsErrorCode.NOT_INITIALIZED, "Call QartveloAds.initialize() first")
                id.isEmpty() -> fail(listener, id, QartveloAdsErrorCode.INVALID_PLACEMENT, "placementId is empty")
                else -> Main.run {
                    if (e.loadsReady) {
                        controller(e, id, format).show(activity, listener)
                    } else {
                        // Nothing can have been loaded before start-up finishes.
                        Listeners.emit(listener, "onNoAdAvailable") { it.onNoAdAvailable(id, format) }
                    }
                }
            }
        } catch (t: Throwable) {
            OurLog.e("QartveloAds show failed", t)
            fail(listener, placementId, QartveloAdsErrorCode.INTERNAL_ERROR, t.javaClass.simpleName)
        }
    }

    private fun isReady(placementId: String, format: AdFormat): Boolean = try {
        engine?.existingFullscreen(placementId.trim(), format)?.isReady() == true
    } catch (t: Throwable) {
        OurLog.e("QartveloAds readiness check failed", t)
        false
    }

    private fun controller(e: Engine, id: String, format: AdFormat): FullscreenController =
        if (format == AdFormat.REWARDED) e.rewarded(id) else e.interstitial(id)

    private fun fail(listener: QartveloAdsListener?, placementId: String, code: QartveloAdsErrorCode, message: String) {
        OurLog.e("$code: $message")
        val error = QartveloAdsError(code, message)
        Listeners.emit(listener, "onLoadFailed") { it.onLoadFailed(placementId, error) }
    }

    private fun notifyInitFailure(listener: QartveloAdsInitListener?, error: QartveloAdsError) {
        if (listener == null) return
        Main.post {
            try {
                listener.onInitialized(false, error)
            } catch (t: Throwable) {
                OurLog.e("Init listener threw", t)
            }
        }
    }

    /** Tests only: tears down all state so each test starts from a clean process-like state. */
    internal fun resetForTests() {
        synchronized(lock) {
            engine?.shutdown()
            engine = null
            registeredAdapter = null
            privacy = QartveloAdsPrivacy()
            logLevelOverride = null
        }
        Listeners.clear()
        ShowRegistry.clear()
    }
}
