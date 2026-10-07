package com.qartvelo.sdk

import android.content.Context
import android.util.AttributeSet
import android.view.View
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

    internal fun isVisibleForAds(): Boolean = isAttachedToWindow && windowShown && isShown

    override fun onAttachedToWindow() {
        windowShown = windowVisibility == View.VISIBLE
        super.onAttachedToWindow()
        guard("banner attach") { controller?.onHostAttachedToWindow(this) }
    }

    override fun onDetachedFromWindow() {
        windowShown = false
        guard("banner detach") { controller?.onHostDetachedFromWindow(this) }
        super.onDetachedFromWindow()
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
