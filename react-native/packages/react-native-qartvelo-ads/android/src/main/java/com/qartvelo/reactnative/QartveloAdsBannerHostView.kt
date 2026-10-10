package com.qartvelo.reactnative

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.widget.FrameLayout
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.ReactContext
import com.facebook.react.bridge.WritableMap
import com.facebook.react.uimanager.PixelUtil
import com.facebook.react.uimanager.ThemedReactContext
import com.facebook.react.uimanager.UIManagerHelper
import com.facebook.react.uimanager.events.Event
import com.qartvelo.sdk.AdFormat
import com.qartvelo.sdk.BannerSizing
import com.qartvelo.sdk.QartveloAdsBannerView

/**
 * Fabric host of one [QartveloAdsBannerView]. The SDK's per-placement banner controller owns loading,
 * refresh and de-duplication: this view only forwards `placementId` changes and lifecycle, so React
 * re-renders never issue requests and a remount re-attaches to the already loaded banner.
 *
 * The SDK adds the creative (an image, or an AdMob AdView) asynchronously, outside React's layout
 * pass. This view therefore measures and lays out its child itself and reports the natural content
 * size to JS (`onSizeChange`), which sizes the component.
 */
@SuppressLint("ViewConstructor")
internal class QartveloAdsBannerHostView(context: ThemedReactContext) : FrameLayout(context) {
    private val banner = QartveloAdsBannerView(context)

    private var placementId: String? = null
    private var released = false
    private var reportedWidth = -1f
    private var reportedHeight = -1f

    // False while FrameLayout's constructor runs (it calls requestLayout before our fields exist).
    private var constructed = false
    private var layoutScheduled = false
    private val measureAndLayout = Runnable {
        layoutScheduled = false
        measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
        layout(left, top, right, bottom)
    }

    init {
        addView(banner, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        banner.listener = AdEventForwarder({ AdFormat.BANNER }) { payload -> dispatchAdEvent(payload) }
        constructed = true
    }

    // Props arrive one setter at a time in no fixed order; they are applied together in
    // [applyProps] (after each update transaction), so a banner never loads with half its props.
    private var pendingPlacementId: String? = null
    private var pendingSizing = BannerSizing.ANCHORED
    private var pendingMaxHeightDp = 250

    fun setPlacementId(value: String?) {
        pendingPlacementId = value?.trim().orEmpty()
    }

    /** `size="inline"`: the biggest ad that fits the width and `maxHeight`. */
    fun setInline(inline: Boolean) {
        pendingSizing = if (inline) BannerSizing.INLINE else BannerSizing.ANCHORED
    }

    /** `maxHeight` in dp, for inline banners. */
    fun setInlineMaxHeight(dp: Double) {
        pendingMaxHeightDp = if (dp.isFinite()) dp.toInt() else 250
    }

    /** Called by the view manager after every prop update; a load starts only when something changed. */
    fun applyProps() {
        val id = pendingPlacementId ?: return
        if (released) return
        val sizingChanged = banner.sizing != pendingSizing ||
            (pendingSizing == BannerSizing.INLINE && banner.inlineMaxHeightDp != pendingMaxHeightDp)
        if (id == placementId && !sizingChanged) return
        if (placementId != null) banner.destroy()
        placementId = id
        banner.sizing = pendingSizing
        banner.inlineMaxHeightDp = pendingMaxHeightDp
        if (id.isEmpty()) {
            // Detach from the previous placement; load() then reports INVALID_PLACEMENT.
            banner.destroy()
        } else {
            PlacementFormats.record(id, AdFormat.BANNER)
        }
        banner.placementId = id
        requestLoad()
    }

    private fun requestLoad() {
        if (InitGate.isOpen()) {
            InitGate.cancel(this)
            banner.load()
        } else {
            InitGate.await(this)
        }
    }

    /** InitGate callback (main thread) once `initialize()` has reached the SDK. */
    fun onInitializeRequested() {
        if (!released && placementId != null) banner.load()
    }

    /** The React view was dropped. The loaded banner stays cached by the SDK for the next mount. */
    fun release() {
        if (released) return
        released = true
        InitGate.cancel(this)
        banner.listener = null
        banner.destroy()
        removeCallbacks(measureAndLayout)
        layoutScheduled = false
    }

    // ---- layout ---------------------------------------------------------------------------------

    override fun requestLayout() {
        super.requestLayout()
        if (constructed && !released && !layoutScheduled) {
            layoutScheduled = true
            post(measureAndLayout)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val childWidthSpec = if (width > 0) {
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY)
        } else {
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        }
        // The content keeps its natural height regardless of the size React currently assigns.
        banner.measure(childWidthSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        setMeasuredDimension(width, MeasureSpec.getSize(heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        banner.layout(0, 0, banner.measuredWidth, banner.measuredHeight)
        reportContentSize()
    }

    private fun reportContentSize() {
        var contentWidth = 0
        for (i in 0 until banner.childCount) {
            contentWidth = maxOf(contentWidth, banner.getChildAt(i).measuredWidth)
        }
        val contentHeight = if (banner.childCount == 0) 0 else banner.measuredHeight
        val widthDp = PixelUtil.toDIPFromPixel(contentWidth.toFloat())
        val heightDp = PixelUtil.toDIPFromPixel(contentHeight.toFloat())
        if (widthDp == reportedWidth && heightDp == reportedHeight) return
        reportedWidth = widthDp
        reportedHeight = heightDp
        dispatch(
            EVENT_SIZE_CHANGE,
            Arguments.createMap().apply {
                putDouble("width", widthDp.toDouble())
                putDouble("height", heightDp.toDouble())
            },
        )
    }

    // ---- events ---------------------------------------------------------------------------------

    private fun dispatchAdEvent(payload: Map<String, Any?>) {
        if (released) return
        dispatch(EVENT_AD, Arguments.makeNativeMap(Wire.flatten(payload)))
    }

    private fun dispatch(name: String, data: WritableMap) {
        if (id == NO_ID) return
        val reactContext = context as? ReactContext ?: return
        // getEventDispatcher(ReactContext) only exists in newer React Native; the per-tag lookup
        // works on every supported version (0.79+).
        val dispatcher = UIManagerHelper.getEventDispatcherForReactTag(reactContext, id) ?: return
        dispatcher.dispatchEvent(BannerEvent(UIManagerHelper.getSurfaceId(this), id, name, data))
    }

    private class BannerEvent(
        surfaceId: Int,
        viewTag: Int,
        private val name: String,
        private val data: WritableMap,
    ) : Event<BannerEvent>(surfaceId, viewTag) {
        override fun getEventName(): String = name

        // Every ad event matters (shown and impression can arrive in the same frame).
        override fun canCoalesce(): Boolean = false

        override fun getEventData(): WritableMap = data
    }

    companion object {
        const val EVENT_AD = "topAdEvent"
        const val EVENT_SIZE_CHANGE = "topSizeChange"
    }
}
