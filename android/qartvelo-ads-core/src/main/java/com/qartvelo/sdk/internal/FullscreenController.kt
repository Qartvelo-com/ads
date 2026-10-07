package com.qartvelo.sdk.internal

import android.app.Activity
import com.qartvelo.sdk.AdFormat
import com.qartvelo.sdk.AdSource
import com.qartvelo.sdk.QartveloAdsAdInfo
import com.qartvelo.sdk.QartveloAdsError
import com.qartvelo.sdk.QartveloAdsErrorCode
import com.qartvelo.sdk.QartveloAdsListener
import com.qartvelo.sdk.QartveloAdsReward
import com.qartvelo.sdk.fallback.FallbackLoadCallback
import com.qartvelo.sdk.fallback.FallbackShowCallback
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Per-placement state machine for interstitial and rewarded ads. Main thread only, except [isReady].
 *
 * Load: a valid cached QartveloAds ad completes immediately; otherwise QartveloAds is requested (bounded by the
 * effective timeout) while the fallback network preloads concurrently. Loads issued while one is in
 * flight join it. On QartveloAds failure: `onFallbackStarted` then `onLoaded(ADMOB)` or
 * `onNoAdAvailable` + `onLoadFailed`. A fill whose creative is still downloading at the decision
 * deadline (timeout + [CREATIVE_GRACE_MS]) lets a ready fallback finish the load; the download
 * continues and the QartveloAds ad is kept for the next show.
 *
 * Show: a valid QartveloAds ad, else a ready fallback ad, else `onNoAdAvailable`.
 *
 * Listeners are only held while a load is running; the last load listener (used by a show without
 * its own listener) is held weakly, so an Activity passed as a listener is never retained.
 */
internal class FullscreenController(
    private val engine: Engine,
    val placementId: String,
    val format: AdFormat,
) {
    /** Loaded QartveloAds ad with its creative on disk, not yet shown. */
    @Volatile
    private var ourAd: ServedAd? = null
    private var op: LoadOp? = null
    private var lastLoadListener: WeakReference<QartveloAdsListener>? = null
    private var fallbackLoading = false

    /** Thread-safe readiness snapshot. */
    fun isReady(): Boolean = ourAd?.isValid() == true || fallbackReady()

    private fun fallbackReady(): Boolean {
        val adapter = engine.fallbackAdapter ?: return false
        return try {
            if (format == AdFormat.REWARDED) adapter.isRewardedReady(placementId) else adapter.isInterstitialReady(placementId)
        } catch (t: Throwable) {
            OurLog.e("Fallback adapter readiness check failed", t)
            false
        }
    }

    // ---- load -----------------------------------------------------------------------------------

    fun load(listener: QartveloAdsListener?) {
        if (listener != null) lastLoadListener = WeakReference(listener)
        op?.let {
            OurLog.d("Joining in-flight load for '$placementId'")
            it.listeners.add(listener)
            return
        }
        val placement = engine.placement(placementId)
        if (placement?.format != null && placement.format != format) {
            val error = QartveloAdsError(
                QartveloAdsErrorCode.INVALID_PLACEMENT,
                "Placement '$placementId' is a ${placement.format.wireName} placement",
            )
            Listeners.emit(listener, "onLoadFailed") { it.onLoadFailed(placementId, error) }
            return
        }
        LoadOp(listener, placement).also { op = it }.start()
    }

    private inner class LoadOp(listener: QartveloAdsListener?, private val placement: PlacementConfig?) {
        val listeners = arrayListOf(listener)
        private val timeoutMs = engine.effectiveTimeoutMs(placement)
        private var ourAdsFailure: QartveloAdsFailure? = null

        /** QartveloAds filled but its creative missed the decision deadline; a ready fallback may finish. */
        private var ourAdsSlow = false
        private var fallbackAttempted = false
        private var fallbackAnnounced = false
        private var done = false
        private val decisionDeadline = Runnable { onDecisionDeadline() }

        /** Resolved when needed: remote config may arrive while this load is running. */
        private fun fallbackUnit(serverFallback: String? = null): String? =
            engine.fallbackUnit(placementId, engine.placement(placementId) ?: placement, serverFallback)

        fun start() {
            val cached = ourAd
            if (cached != null && !cached.isValid()) discardOurAd("expired")
            val valid = ourAd
            if (valid != null) {
                preloadFallback()
                finishLoaded(AdSource.QARTVELO, valid)
                return
            }
            val refreshing = engine.refreshConfigIfStale(placement)
            if (!refreshing && !engine.ourAdsEnabled(placement)) {
                preloadFallback()
                onQartveloAdsFailed(QartveloAdsFailure(QartveloAdsFailure.DISABLED, QartveloAdsError(QartveloAdsErrorCode.NO_FILL, "QartveloAds serving is disabled for this placement")))
                return
            }
            // Dispatch the QartveloAds request first: a cold fallback SDK can keep the main thread busy
            // while it starts loading, and that must not eat into the QartveloAds time budget.
            fetch()
            preloadFallback()
            Main.postDelayed(timeoutMs + (TestHooks.creativeGraceMs ?: CREATIVE_GRACE_MS), decisionDeadline)
        }

        private fun fetch() {
            engine.fetchOurAd(placementId, format, timeoutMs) { outcome ->
                when (outcome) {
                    is FetchOutcome.Success -> {
                        if (done) {
                            // The fallback finished this load while the creative was downloading:
                            // keep the QartveloAds ad for the next show.
                            if (ourAd?.isValid() != true) ourAd = outcome.ad
                            return@fetchOurAd
                        }
                        ourAd = outcome.ad
                        finishLoaded(AdSource.QARTVELO, outcome.ad)
                    }
                    is FetchOutcome.Failure -> onQartveloAdsFailed(outcome.failure)
                }
            }
        }

        /** Preloads the fallback in parallel so it is ready the moment QartveloAds cannot serve. */
        private fun preloadFallback() {
            val unit = fallbackUnit() ?: return
            fallbackAttempted = true
            if (!fallbackReady() && !fallbackLoading) startFallbackLoad(unit)
        }

        private fun onQartveloAdsFailed(failure: QartveloAdsFailure) {
            if (done) return
            ourAdsFailure = failure
            val unit = fallbackUnit(failure.serverFallback)
            if (unit == null) {
                finishNoAd(failure)
                return
            }
            announceFallback(failure.reason)
            when {
                fallbackReady() -> finishLoaded(AdSource.ADMOB, null)
                fallbackLoading -> Unit // onFallbackResult completes the load.
                !fallbackAttempted -> {
                    fallbackAttempted = true
                    startFallbackLoad(unit)
                }
                else -> finishNoAd(failure)
            }
        }

        /**
         * QartveloAds answered in time but its creative is still downloading (a slow CDN). The host must
         * not wait for it when the fallback can serve; without a usable fallback, keep waiting.
         */
        private fun onDecisionDeadline() {
            if (done || ourAdsFailure != null) return
            val unit = fallbackUnit() ?: return
            OurLog.i("QartveloAds creative for '$placementId' is still loading; the fallback may serve this load")
            ourAdsSlow = true
            when {
                fallbackReady() -> finishWithFallback(QartveloAdsFailure.TIMEOUT)
                !fallbackLoading && !fallbackAttempted -> {
                    fallbackAttempted = true
                    startFallbackLoad(unit)
                }
            }
        }

        fun onFallbackResult(success: Boolean) {
            if (done) return
            val failure = ourAdsFailure
            when {
                failure != null -> if (success) finishLoaded(AdSource.ADMOB, null) else finishNoAd(failure)
                ourAdsSlow && success -> finishWithFallback(QartveloAdsFailure.TIMEOUT)
                // Otherwise QartveloAds is still pending and its own result completes the load.
            }
        }

        private fun finishWithFallback(reason: String) {
            announceFallback(reason)
            finishLoaded(AdSource.ADMOB, null)
        }

        private fun announceFallback(reason: String) {
            if (fallbackAnnounced) return
            fallbackAnnounced = true
            engine.reportFallback(placementId, reason)
            OurLog.i("Falling back for '$placementId' ($reason)")
            Listeners.emit(listeners, "onFallbackStarted") { it.onFallbackStarted(placementId, format, reason) }
        }

        private fun finishLoaded(source: AdSource, ad: ServedAd?) {
            finish()
            val info = QartveloAdsAdInfo(placementId, format, source, ad?.campaignId, ad?.creativeId)
            OurLog.i("Loaded ${format.wireName} '$placementId' from ${source.name}")
            Listeners.emit(listeners, "onLoaded") { it.onLoaded(info) }
            listeners.clear()
        }

        private fun finishNoAd(failure: QartveloAdsFailure) {
            finish()
            OurLog.i("No ad available for '$placementId' (${failure.reason})")
            Listeners.emit(listeners, "onNoAdAvailable") { it.onNoAdAvailable(placementId, format) }
            Listeners.emit(listeners, "onLoadFailed") { it.onLoadFailed(placementId, failure.error) }
            listeners.clear()
        }

        private fun finish() {
            done = true
            Main.cancel(decisionDeadline)
            if (op === this) op = null
        }
    }

    private fun startFallbackLoad(unit: String) {
        val adapter = engine.fallbackAdapter ?: return
        fallbackLoading = true
        val callback = object : FallbackLoadCallback {
            private val once = AtomicBoolean()
            override fun onLoaded() {
                if (once.compareAndSet(false, true)) Main.post { onFallbackLoadFinished(true, null) }
            }

            override fun onFailed(message: String) {
                if (once.compareAndSet(false, true)) Main.post { onFallbackLoadFinished(false, message) }
            }
        }
        try {
            if (format == AdFormat.REWARDED) {
                adapter.loadRewarded(engine.appContext, placementId, unit, callback)
            } else {
                adapter.loadInterstitial(engine.appContext, placementId, unit, callback)
            }
        } catch (t: Throwable) {
            OurLog.e("Fallback load threw", t)
            callback.onFailed(t.javaClass.simpleName)
        }
    }

    private fun onFallbackLoadFinished(success: Boolean, message: String?) {
        fallbackLoading = false
        if (success) {
            OurLog.d("Fallback ${format.wireName} ready for '$placementId'")
        } else {
            OurLog.i("Fallback ${format.wireName} failed for '$placementId': $message")
        }
        op?.onFallbackResult(success)
    }

    private fun discardOurAd(why: String) {
        if (ourAd != null) OurLog.i("Discarding QartveloAds ad for '$placementId' ($why)")
        ourAd = null
    }

    // ---- show -----------------------------------------------------------------------------------

    fun show(activity: Activity, listener: QartveloAdsListener?) {
        val target = listener ?: lastLoadListener?.get()
        if (engine.fullscreenShowing) {
            Listeners.emit(target, "onLoadFailed") {
                it.onLoadFailed(placementId, QartveloAdsError(QartveloAdsErrorCode.ALREADY_SHOWING, "Another full-screen ad is showing"))
            }
            return
        }
        if (activity.isFinishing) {
            Listeners.emit(target, "onLoadFailed") {
                it.onLoadFailed(placementId, QartveloAdsError(QartveloAdsErrorCode.SHOW_FAILED, "Activity is finishing"))
            }
            return
        }
        val ad = ourAd
        if (ad != null) {
            ourAd = null // One ad, one show: the signed token allows exactly one impression.
            if (ad.isValid()) {
                if (launchQartveloAds(activity, ad, target)) return
            } else {
                OurLog.i("QartveloAds ad for '$placementId' expired before show")
            }
        }
        if (fallbackReady()) {
            showFallback(activity, target)
            return
        }
        Listeners.emit(target, "onNoAdAvailable") { it.onNoAdAvailable(placementId, format) }
    }

    private fun launchQartveloAds(activity: Activity, ad: ServedAd, listener: QartveloAdsListener?): Boolean {
        val session = ShowSession(engine, this, ad, listener, activity)
        ShowRegistry.put(session)
        engine.fullscreenShowing = true
        return try {
            activity.startActivity(QartveloAdsActivity.intent(activity, session.id))
            true
        } catch (t: Throwable) {
            OurLog.e("Could not start the QartveloAds ad activity", t)
            ShowRegistry.remove(session.id)
            engine.fullscreenShowing = false
            false
        }
    }

    /** Called by [ShowSession] when the creative failed before anything was displayed. */
    fun onQartveloAdsRenderFailed(host: Activity?, listener: QartveloAdsListener?) {
        engine.fullscreenShowing = false
        if (host != null && !host.isFinishing && fallbackReady()) {
            OurLog.i("QartveloAds creative failed to render; showing fallback for '$placementId'")
            showFallback(host, listener)
        } else {
            Listeners.emit(listener, "onNoAdAvailable") { it.onNoAdAvailable(placementId, format) }
        }
    }

    fun onSessionClosed() {
        engine.fullscreenShowing = false
    }

    private fun showFallback(activity: Activity, listener: QartveloAdsListener?) {
        val adapter = engine.fallbackAdapter ?: return
        engine.fullscreenShowing = true
        val info = QartveloAdsAdInfo(placementId, format, AdSource.ADMOB)
        val callback = FallbackShowRelay(info, listener)
        try {
            if (format == AdFormat.REWARDED) {
                adapter.showRewarded(activity, placementId, callback)
            } else {
                adapter.showInterstitial(activity, placementId, callback)
            }
        } catch (t: Throwable) {
            OurLog.e("Fallback show threw", t)
            callback.onShowFailed(t.javaClass.simpleName)
        }
    }

    /**
     * Normalises fallback show callbacks: main thread, each event at most once, reward at most once
     * and only for rewarded placements, exactly one terminal event.
     */
    private inner class FallbackShowRelay(
        private val info: QartveloAdsAdInfo,
        private val listener: QartveloAdsListener?,
    ) : FallbackShowCallback {
        private val shown = AtomicBoolean()
        private val impression = AtomicBoolean()
        private val clicked = AtomicBoolean()
        private val rewarded = AtomicBoolean()
        private val finished = AtomicBoolean()

        override fun onShown() {
            if (!finished.get() && shown.compareAndSet(false, true)) Listeners.emit(listener, "onShown") { it.onShown(info) }
        }

        override fun onImpression() {
            if (!finished.get() && impression.compareAndSet(false, true)) Listeners.emit(listener, "onImpression") { it.onImpression(info) }
        }

        override fun onClicked() {
            if (!finished.get() && clicked.compareAndSet(false, true)) Listeners.emit(listener, "onClicked") { it.onClicked(info) }
        }

        override fun onReward(type: String, amount: Int) {
            if (format != AdFormat.REWARDED || finished.get()) return
            if (!rewarded.compareAndSet(false, true)) return
            val reward = QartveloAdsReward(type.ifBlank { "reward" }, amount.coerceAtLeast(1))
            Listeners.emit(listener, "onReward") { it.onReward(info, reward) }
        }

        override fun onDismissed() {
            if (!finished.compareAndSet(false, true)) return
            Main.post { engine.fullscreenShowing = false }
            Listeners.emit(listener, "onDismissed") { it.onDismissed(info) }
        }

        override fun onShowFailed(message: String) {
            if (!finished.compareAndSet(false, true)) return
            OurLog.e("Fallback ad failed to show: $message")
            Main.post { engine.fullscreenShowing = false }
            Listeners.emit(listener, "onLoadFailed") {
                it.onLoadFailed(info.placementId, QartveloAdsError(QartveloAdsErrorCode.SHOW_FAILED, "Fallback ad failed to show"))
            }
        }
    }

    companion object {
        /** Extra time after the request timeout for an QartveloAds creative to download before a ready fallback wins. */
        const val CREATIVE_GRACE_MS = 1_500L
    }
}
