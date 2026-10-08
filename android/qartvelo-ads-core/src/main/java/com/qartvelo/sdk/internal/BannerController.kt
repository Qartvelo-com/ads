package com.qartvelo.sdk.internal

import android.content.Context
import android.content.MutableContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.qartvelo.sdk.AdFormat
import com.qartvelo.sdk.AdSource
import com.qartvelo.sdk.QartveloAdsAdInfo
import com.qartvelo.sdk.QartveloAdsBannerView
import com.qartvelo.sdk.QartveloAdsError
import com.qartvelo.sdk.QartveloAdsErrorCode
import com.qartvelo.sdk.QartveloAdsListener
import com.qartvelo.sdk.R
import com.qartvelo.sdk.fallback.FallbackBanner
import com.qartvelo.sdk.fallback.FallbackBannerCallback
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One banner per placement, shared by every [QartveloAdsBannerView] instance for that placement: a view that
 * is re-created (rotation, list recycling, React Native re-renders) re-attaches to the loaded banner
 * instead of requesting a new one. Main thread only.
 *
 * Refresh never happens faster than `banner_refresh_seconds` (measured between request starts) and only
 * while the host view is attached and visible. The host is held weakly and a fallback banner's context
 * is swapped back to the application context when detached, so no Activity is ever retained.
 */
internal class BannerController(private val engine: Engine, val placementId: String) {
    private sealed class Content {
        class QartveloAds(val ad: ServedAd, val bitmap: Bitmap) : Content() {
            var impressed = false
            var clicked = false
        }

        class Fallback(val banner: FallbackBanner, val wrapper: MutableContextWrapper) : Content()
    }

    private var host: WeakReference<QartveloAdsBannerView>? = null
    private var hostVisible = false
    private var content: Content? = null
    private var loading = false
    private var lastRequestAt = Long.MIN_VALUE / 2
    private var pendingFallback: FallbackBanner? = null
    private val refreshTask = Runnable { refreshIfDue() }

    private val hostView: QartveloAdsBannerView? get() = host?.get()

    // ---- host lifecycle -------------------------------------------------------------------------

    fun attach(view: QartveloAdsBannerView) {
        val previous = hostView
        if (previous === view) {
            if (content == null && !loading && isDue()) startLoad()
            return
        }
        previous?.let { unrender(it) }
        host = WeakReference(view)
        hostVisible = view.isVisibleForAds()
        val current = content
        if (current != null && isDisplayable(current)) {
            render(view, current)
            val info = infoFor(current)
            // Re-created view: tell its own listener the ad is ready, without a new request. Global
            // observers already saw this load, so they are not notified again.
            Listeners.emit(listOf(view.listener), "onLoaded", includeGlobal = false) { it.onLoaded(info) }
            onShownMaybe()
        } else {
            if (current != null) replaceContent(null)
            if (!loading && isDue()) startLoad()
        }
        schedule()
    }

    fun detach(view: QartveloAdsBannerView) {
        if (hostView !== view) return
        unrender(view)
        host = null
        hostVisible = false
        schedule()
    }

    fun onHostAttachedToWindow(view: QartveloAdsBannerView) {
        if (hostView !== view) return
        content?.let { if (isDisplayable(it)) render(view, it) }
        onVisibilityChanged(view, view.isVisibleForAds())
    }

    fun onHostDetachedFromWindow(view: QartveloAdsBannerView) {
        if (hostView !== view) return
        onVisibilityChanged(view, false)
        // Drop the fallback view from the detached hierarchy and point it at the app context so a
        // destroyed Activity cannot be reached through the controller.
        (content as? Content.Fallback)?.let { unrender(view) }
    }

    fun onVisibilityChanged(view: QartveloAdsBannerView, visible: Boolean) {
        if (hostView !== view || hostVisible == visible) return
        hostVisible = visible
        (content as? Content.Fallback)?.banner?.let { guard("banner pause/resume") { if (visible) it.resume() else it.pause() } }
        if (visible) onShownMaybe()
        schedule()
    }

    // ---- refresh --------------------------------------------------------------------------------

    private fun refreshMs(): Long =
        (engine.placement(placementId)?.bannerRefreshSeconds ?: RemoteConfig.DEFAULT_BANNER_REFRESH_SECONDS)
            .coerceAtLeast(RemoteConfig.MIN_BANNER_REFRESH_SECONDS) * 1000L

    private fun isDue(): Boolean = Clock.elapsed() - lastRequestAt >= refreshMs()

    private fun schedule() {
        Main.cancel(refreshTask)
        if (!hostVisible || loading || hostView == null) return
        val delay = lastRequestAt + refreshMs() - Clock.elapsed()
        OurLog.d("Banner '$placementId' refresh in ${delay.coerceAtLeast(0) / 1000} s")
        Main.postDelayed(delay, refreshTask)
    }

    private fun refreshIfDue() {
        if (hostVisible && !loading && hostView != null && isDue()) startLoad() else schedule()
    }

    // ---- loading --------------------------------------------------------------------------------

    private fun startLoad() {
        loading = true
        lastRequestAt = Clock.elapsed()
        Main.cancel(refreshTask)
        engine.whenReady { loadNow() }
    }

    private fun loadNow() {
        val placement = engine.placement(placementId)
        if (placement?.format != null && placement.format != AdFormat.BANNER) {
            loading = false
            val error = QartveloAdsError(QartveloAdsErrorCode.INVALID_PLACEMENT, "Placement '$placementId' is not a banner placement")
            emit("onLoadFailed") { it.onLoadFailed(placementId, error) }
            return
        }
        // A stale "QartveloAds off" config is re-checked with the backend, so a kill switch turned back on
        // reaches a running app (/ads/request applies every switch itself).
        val refreshing = engine.refreshConfigIfStale(placement)
        if (!refreshing && !engine.ourAdsEnabled(placement)) {
            onQartveloAdsFailed(QartveloAdsFailure(QartveloAdsFailure.DISABLED, QartveloAdsError(QartveloAdsErrorCode.NO_FILL, "QartveloAds serving is disabled for this placement")))
            return
        }
        engine.fetchOurAd(placementId, AdFormat.BANNER, engine.effectiveTimeoutMs(placement)) { outcome ->
            when (outcome) {
                is FetchOutcome.Success -> decodeBanner(outcome.ad)
                is FetchOutcome.Failure -> onQartveloAdsFailed(outcome.failure)
            }
        }
    }

    private fun decodeBanner(ad: ServedAd) {
        val file = ad.file ?: return onQartveloAdsFailed(creativeFailure())
        engine.io.execute {
            val bitmap = try {
                BitmapFactory.decodeFile(file.absolutePath)
            } catch (t: Throwable) {
                OurLog.e("Banner decode failed", t)
                null
            }
            Main.post {
                if (bitmap == null) onQartveloAdsFailed(creativeFailure()) else onQartveloAdsReady(ad, bitmap)
            }
        }
    }

    private fun creativeFailure() = QartveloAdsFailure(
        QartveloAdsFailure.CREATIVE_FAILED,
        QartveloAdsError(QartveloAdsErrorCode.CREATIVE_FAILED, "QartveloAds banner creative could not be decoded"),
    )

    private fun onQartveloAdsReady(ad: ServedAd, bitmap: Bitmap) {
        OurLog.d("QartveloAds banner ready for '$placementId'")
        loading = false
        val next = Content.QartveloAds(ad, bitmap)
        replaceContent(next)
        hostView?.let { render(it, next) }
        val info = infoFor(next)
        emit("onLoaded") { it.onLoaded(info) }
        onShownMaybe()
        schedule()
    }

    private fun onQartveloAdsFailed(failure: QartveloAdsFailure) {
        val unit = if (failure.serverFallback == "none") null else engine.fallbackUnit(placementId, engine.placement(placementId))
        if (unit != null && content is Content.Fallback) {
            // The fallback banner is already on screen and refreshes itself; keep it.
            loading = false
            schedule()
            return
        }
        if (unit == null) {
            loading = false
            if (content == null) {
                emit("onNoAdAvailable") { it.onNoAdAvailable(placementId, AdFormat.BANNER) }
                emit("onLoadFailed") { it.onLoadFailed(placementId, failure.error) }
            }
            schedule()
            return
        }
        engine.reportFallback(placementId, failure.reason)
        emit("onFallbackStarted") { it.onFallbackStarted(placementId, AdFormat.BANNER, failure.reason) }
        loadFallback(unit, failure)
    }

    private fun loadFallback(unit: String, failure: QartveloAdsFailure) {
        val adapter = engine.fallbackAdapter ?: return onFallbackFailed(null, failure)
        val view = hostView
        val wrapper = MutableContextWrapper(view?.context ?: engine.appContext)
        val metrics = engine.appContext.resources.displayMetrics
        val widthPx = view?.width?.takeIf { it > 0 } ?: metrics.widthPixels
        val widthDp = (widthPx / metrics.density).toInt().coerceAtLeast(MIN_BANNER_WIDTH_DP)
        val info = QartveloAdsAdInfo(placementId, AdFormat.BANNER, AdSource.ADMOB)
        var created: FallbackBanner? = null
        val callback = object : FallbackBannerCallback {
            private val settled = AtomicBoolean()
            private val impressed = AtomicBoolean()
            private val clicked = AtomicBoolean()

            override fun onLoaded() {
                if (settled.compareAndSet(false, true)) Main.post { created?.let { onFallbackLoaded(it, wrapper) } }
            }

            override fun onFailed(message: String) {
                if (settled.compareAndSet(false, true)) {
                    OurLog.i("Fallback banner failed for '$placementId': $message")
                    Main.post { onFallbackFailed(created, failure) }
                }
            }

            override fun onImpression() {
                if (impressed.compareAndSet(false, true)) {
                    emit("onShown") { it.onShown(info) }
                    emit("onImpression") { it.onImpression(info) }
                }
            }

            override fun onClicked() {
                if (clicked.compareAndSet(false, true)) emit("onClicked") { it.onClicked(info) }
            }
        }
        try {
            created = adapter.createBanner(wrapper, placementId, unit, widthDp, callback)
            pendingFallback = created
        } catch (t: Throwable) {
            OurLog.e("Fallback banner creation threw", t)
            onFallbackFailed(null, failure)
        }
    }

    private fun onFallbackLoaded(banner: FallbackBanner, wrapper: MutableContextWrapper) {
        if (pendingFallback !== banner) return
        pendingFallback = null
        loading = false
        val next = Content.Fallback(banner, wrapper)
        replaceContent(next)
        // Only render into a host that is in a window. A host detached meanwhile (its Activity may be
        // gone) must not become the banner's context; onHostAttachedToWindow renders it later.
        val view = hostView
        if (view != null && view.isAttachedToWindow) render(view, next) else wrapper.baseContext = engine.appContext
        if (!hostVisible) guard("banner pause") { banner.pause() }
        val info = infoFor(next)
        emit("onLoaded") { it.onLoaded(info) }
        schedule()
    }

    private fun onFallbackFailed(banner: FallbackBanner?, failure: QartveloAdsFailure) {
        if (banner != null && pendingFallback !== banner) return
        pendingFallback = null
        loading = false
        banner?.let { guard("banner destroy") { it.destroy() } }
        if (content == null) {
            emit("onNoAdAvailable") { it.onNoAdAvailable(placementId, AdFormat.BANNER) }
            emit("onLoadFailed") { it.onLoadFailed(placementId, failure.error) }
        }
        schedule()
    }

    // ---- display --------------------------------------------------------------------------------

    /** An QartveloAds banner never shown before must still be within its token lifetime. */
    private fun isDisplayable(c: Content): Boolean = when (c) {
        is Content.QartveloAds -> c.impressed || Clock.elapsed() < c.ad.expiresAtElapsed - ServedAd.EXPIRY_MARGIN_MS
        is Content.Fallback -> true
    }

    /** Records the QartveloAds impression the first time the banner is actually visible. */
    private fun onShownMaybe() {
        val c = content as? Content.QartveloAds ?: return
        val view = hostView ?: return
        if (c.impressed || !hostVisible || view.childCount == 0) return
        if (!isDisplayable(c)) {
            OurLog.i("Banner for '$placementId' expired before it was seen; discarding")
            replaceContent(null)
            unrender(view)
            schedule()
            return
        }
        c.impressed = true
        engine.events.enqueue(TrackedEventType.IMPRESSION, c.ad)
        val info = infoFor(c)
        emit("onShown") { it.onShown(info) }
        emit("onImpression") { it.onImpression(info) }
    }

    private fun onQartveloAdsClick(context: Context) {
        val c = content as? Content.QartveloAds ?: return
        val url = c.ad.clickUrl ?: return
        if (!c.impressed) return
        if (!c.clicked) {
            c.clicked = true
            engine.events.enqueue(TrackedEventType.CLICK, c.ad)
            val info = infoFor(c)
            emit("onClicked") { it.onClicked(info) }
        }
        ClickOpener.open(context, url)
    }

    private fun render(view: QartveloAdsBannerView, c: Content) {
        when (c) {
            is Content.QartveloAds -> {
                if (view.getTag(R.id.qartvelo_banner_content) === c) return
                view.removeAllViews()
                val metrics = view.resources.displayMetrics
                val wantW = (c.ad.width.coerceAtLeast(1) * metrics.density).toInt()
                val available = view.width.takeIf { it > 0 } ?: metrics.widthPixels
                val w = minOf(wantW, available)
                val h = (w.toLong() * c.ad.height.coerceAtLeast(1) / c.ad.width.coerceAtLeast(1)).toInt()
                val image = ImageView(view.context).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setImageBitmap(c.bitmap)
                    contentDescription = view.context.getString(R.string.qartvelo_ad_label)
                    setOnClickListener { v -> onQartveloAdsClick(v.context) }
                }
                // The creative and its "Ad" badge share a frame sized to the creative.
                val frame = FrameLayout(view.context)
                val match = ViewGroup.LayoutParams.MATCH_PARENT
                frame.addView(image, FrameLayout.LayoutParams(match, match))
                frame.addView(adBadge(view.context, c.ad.test), FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START,
                ))
                view.addView(frame, FrameLayout.LayoutParams(w, h, Gravity.CENTER))
                view.setTag(R.id.qartvelo_banner_content, c)
            }
            is Content.Fallback -> {
                val adView = c.banner.view
                if (adView.parent === view) return
                (adView.parent as? ViewGroup)?.removeView(adView)
                view.removeAllViews()
                c.wrapper.baseContext = view.context
                view.addView(adView, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER,
                ))
                view.setTag(R.id.qartvelo_banner_content, c)
            }
        }
    }

    private fun unrender(view: QartveloAdsBannerView) {
        (content as? Content.Fallback)?.let { it.wrapper.baseContext = engine.appContext }
        view.removeAllViews()
        view.setTag(R.id.qartvelo_banner_content, null)
    }

    private fun replaceContent(next: Content?) {
        val old = content
        content = next
        if (old is Content.Fallback && old !== next) {
            (old.banner.view.parent as? ViewGroup)?.removeView(old.banner.view)
            old.wrapper.baseContext = engine.appContext
            guard("banner destroy") { old.banner.destroy() }
        }
    }

    private fun adBadge(context: Context, test: Boolean): View = TextView(context).apply {
        text = context.getString(if (test) R.string.qartvelo_test_ad_label else R.string.qartvelo_ad_label)
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
        val d = context.resources.displayMetrics.density
        setPadding((4 * d).toInt(), 0, (4 * d).toInt(), 0)
        background = GradientDrawable().apply {
            cornerRadius = 3 * d
            setColor(0x99000000.toInt())
        }
        // Opens the Qartvelo Ads website (not the advertiser) and consumes the tap.
        contentDescription = context.getString(R.string.qartvelo_about_ads)
        setOnClickListener { v -> AboutLink.open(v.context) }
    }

    private fun infoFor(c: Content): QartveloAdsAdInfo = when (c) {
        is Content.QartveloAds -> QartveloAdsAdInfo(placementId, AdFormat.BANNER, AdSource.QARTVELO, c.ad.campaignId, c.ad.creativeId)
        is Content.Fallback -> QartveloAdsAdInfo(placementId, AdFormat.BANNER, AdSource.ADMOB)
    }

    private fun emit(event: String, block: (QartveloAdsListener) -> Unit) {
        Listeners.emit(listOf(hostView?.listener), event, block = block)
    }

    companion object {
        private const val MIN_BANNER_WIDTH_DP = 32
    }
}
