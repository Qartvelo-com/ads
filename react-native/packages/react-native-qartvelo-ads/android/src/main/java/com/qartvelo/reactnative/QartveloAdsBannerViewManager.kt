package com.qartvelo.reactnative

import com.facebook.react.module.annotations.ReactModule
import com.facebook.react.uimanager.SimpleViewManager
import com.facebook.react.uimanager.ThemedReactContext
import com.facebook.react.uimanager.ViewManagerDelegate
import com.facebook.react.uimanager.annotations.ReactProp
import com.facebook.react.viewmanagers.QartveloAdsBannerViewManagerDelegate
import com.facebook.react.viewmanagers.QartveloAdsBannerViewManagerInterface

/** Fabric view manager of `<QartveloAdsBanner />` (codegen component `QartveloAdsBannerView`). */
@ReactModule(name = QartveloAdsBannerViewManager.NAME)
internal class QartveloAdsBannerViewManager :
    SimpleViewManager<QartveloAdsBannerHostView>(),
    QartveloAdsBannerViewManagerInterface<QartveloAdsBannerHostView> {
    private val delegate = QartveloAdsBannerViewManagerDelegate(this)

    override fun getDelegate(): ViewManagerDelegate<QartveloAdsBannerHostView> = delegate

    override fun getName(): String = NAME

    override fun createViewInstance(context: ThemedReactContext): QartveloAdsBannerHostView = QartveloAdsBannerHostView(context)

    @ReactProp(name = "placementId")
    override fun setPlacementId(view: QartveloAdsBannerHostView, value: String?) {
        view.setPlacementId(value)
    }

    override fun onDropViewInstance(view: QartveloAdsBannerHostView) {
        view.release()
        super.onDropViewInstance(view)
    }

    // A host view is bound to SDK state for its whole life; never hand it to another React view.
    override fun prepareToRecycleView(reactContext: ThemedReactContext, view: QartveloAdsBannerHostView): QartveloAdsBannerHostView? = null

    override fun getExportedCustomDirectEventTypeConstants(): Map<String, Any> = mapOf(
        QartveloAdsBannerHostView.EVENT_AD to mapOf("registrationName" to "onAdEvent"),
        QartveloAdsBannerHostView.EVENT_SIZE_CHANGE to mapOf("registrationName" to "onSizeChange"),
    )

    companion object {
        const val NAME = "QartveloAdsBannerView"
    }
}
