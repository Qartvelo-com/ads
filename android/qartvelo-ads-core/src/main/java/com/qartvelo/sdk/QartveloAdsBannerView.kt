package com.qartvelo.sdk

import android.content.Context
import android.util.AttributeSet
import android.graphics.Rect
import android.view.View
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import com.qartvelo.sdk.internal.BannerController
import com.qartvelo.sdk.internal.Listeners
import com.qartvelo.sdk.internal.Main
import com.qartvelo.sdk.internal.OurLog
import com.qartvelo.sdk.internal.guard

/**
 * Banner container. Set [placementId] (or `app:qartvelo_placementId` in XML), optionally a [listener],
 * then call [load]. The view renders an QartveloAds banner, or the publisher's AdMob banner through the
 * optional adapter when QartveloAds has no fill or fails.
 *
 * Banners are owned by a per-placement controller: re-creating this view (rotation, list recycling,
 * React Native re-renders) and calling [load] again reuses the loaded banner instead of requesting a
 * new one. Refresh pauses while the view is detached or not visible. Call [destroy] when the view is
 * permanently removed.
 */
public class QartveloAdsBannerView @JvmOverloads public constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    public var placementId: String? = null
    public var listener: QartveloAdsListener? = null

    /**
     * True (default): the view reserves Google's anchored adaptive banner slot, the full width and
     * 50 to 90 dp tall (the same height as the AdMob fallback banner), and QartveloAds creatives are
     * fitted into it. False: the view takes the creative's own size, for example for an inline
     * rectangle. Set it before [load].
     */
    public var usesAdaptiveSize: Boolean = true

    /**
     * [BannerSizing.ANCHORED] (default) or [BannerSizing.INLINE] for banners inside scrolling
     * content. Inline wins over [usesAdaptiveSize]. Set it before [load].
     */
    public var sizing: BannerSizing = BannerSizing.ANCHORED

    /** The most an inline banner may be tall, in dp (default 250, at least 32). */
    public var inlineMaxHeightDp: Int = 250

    private var controller: BannerController? = null

    /** Last window visibility reported by the framework (GONE while the Activity is stopped). */
    private var windowShown = false

    init {
        if (attrs != null) {
            val a = context.obtainStyledAttributes(attrs, R.styleable.QartveloAdsBannerView, defStyleAttr, 0)
            try {
                placementId = a.getString(R.styleable.QartveloAdsBannerView_qartvelo_placementId)
            } finally {
                a.recycle()
            }
        }
    }

    /** Idempotent: repeated calls on the same view never trigger extra requests. */
    public fun load() {
        guard("banner load") {
            val id = placementId?.trim()?.takeIf { it.isNotEmpty() }
            if (id == null) {
                fail("", QartveloAdsError(QartveloAdsErrorCode.INVALID_PLACEMENT, "placementId is not set"))
                return
            }
            val engine = QartveloAds.engine()
            if (engine == null) {
                fail(id, QartveloAdsError(QartveloAdsErrorCode.NOT_INITIALIZED, "Call QartveloAds.initialize() before loading banners"))
                return
            }
            Main.run {
                val next = engine.banner(id)
                val previous = controller
                if (previous != null && previous !== next) previous.detach(this)
                controller = next
                next.attach(this)
            }
        }
    }

    /** Releases this view from its placement controller; the loaded banner stays cached for reuse. */
    public fun destroy() {
        guard("banner destroy") {
            Main.run {
                controller?.detach(this)
                controller = null
                removeAllViews()
            }
        }
    }

    /**
     * Attached, shown and at least half on screen: inside the window and every clipping parent
     * (a list or scroll view laying the banner out below the fold does not count).
     */
    internal fun isVisibleForAds(): Boolean {
        if (!isAttachedToWindow || !windowShown || !isShown || width <= 0 || height <= 0) return false
        if (!getGlobalVisibleRect(visibleRect)) return false
        return visibleRect.width().toLong() * visibleRect.height() * 2 >= width.toLong() * height
    }

    private val visibleRect = Rect()

    // Scrolling and layout move the banner on or off screen without a visibility change.
    private val onScreenListener = ViewTreeObserver.OnScrollChangedListener { notifyVisibility() }
    private val onLayoutListener = ViewTreeObserver.OnGlobalLayoutListener { notifyVisibility() }

    override fun onAttachedToWindow() {
        windowShown = windowVisibility == View.VISIBLE
        super.onAttachedToWindow()
        viewTreeObserver.addOnScrollChangedListener(onScreenListener)
        viewTreeObserver.addOnGlobalLayoutListener(onLayoutListener)
        guard("banner attach") { controller?.onHostAttachedToWindow(this) }
    }

    override fun onDetachedFromWindow() {
        windowShown = false
        viewTreeObserver.removeOnScrollChangedListener(onScreenListener)
        viewTreeObserver.removeOnGlobalLayoutListener(onLayoutListener)
        guard("banner detach") { controller?.onHostDetachedFromWindow(this) }
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Called during layout: refit the adaptive slot on the next frame, not inside this pass.
        if (w != oldw && w > 0) post { guard("banner resize") { controller?.onHostResized(this) } }
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        windowShown = visibility == View.VISIBLE
        notifyVisibility()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        notifyVisibility()
    }

    private fun notifyVisibility() {
        // Called from the FrameLayout constructor too, before `controller` is initialised.
        @Suppress("SENSELESS_COMPARISON")
        if (controller == null) return
        guard("banner visibility") { controller?.onVisibilityChanged(this, isVisibleForAds()) }
    }

    private fun fail(placementId: String, error: QartveloAdsError) {
        OurLog.e("Banner load failed: ${error.message}")
        Listeners.emit(listener, "onLoadFailed") { it.onLoadFailed(placementId, error) }
    }
}
