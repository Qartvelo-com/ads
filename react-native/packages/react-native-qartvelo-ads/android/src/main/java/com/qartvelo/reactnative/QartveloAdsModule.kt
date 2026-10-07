package com.qartvelo.reactnative

import android.util.Log
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.module.annotations.ReactModule
import com.qartvelo.sdk.AdFormat
import com.qartvelo.sdk.QartveloAds
import com.qartvelo.sdk.QartveloAdsErrorCode

/**
 * TurboModule bridging `@qartvelo/react-native-ads` to the QartveloAds Android SDK. It holds no ad logic:
 * every call is forwarded to [QartveloAds] with a per-call listener that settles the JS promise exactly
 * once, and the SDK's global observer is forwarded to JS as `onAdEvent`.
 */
@ReactModule(name = QartveloAdsModule.NAME)
class QartveloAdsModule(reactContext: ReactApplicationContext) : NativeQartveloAdsSpec(reactContext) {
    private val pending = PendingCalls()

    @Volatile
    private var active = true

    private val events = AdEventForwarder(PlacementFormats::get) { payload -> emitEvent(payload) }

    init {
        QartveloAds.addEventListener(events)
    }

    override fun getName(): String = NAME

    override fun invalidate() {
        active = false
        QartveloAds.removeEventListener(events)
        pending.cancelAll()
        super.invalidate()
    }

    // ---- initialization -------------------------------------------------------------------------

    override fun initializeSdk(options: ReadableMap, promise: Promise) {
        val call = newCall(promise)
        val config = try {
            Options.parseInit(options.toHashMap())
        } catch (e: IllegalArgumentException) {
            call.reject(INVALID_ARGUMENT, e.message ?: "Invalid options")
            return
        }
        QartveloAds.initialize(reactApplicationContext.applicationContext, config.appKey, config.options) { success, error ->
            if (success) {
                call.resolve(null)
            } else {
                call.reject((error?.code ?: QartveloAdsErrorCode.INTERNAL_ERROR).wire, error?.message ?: "Initialization failed")
            }
        }
        InitGate.open()
    }

    override fun isInitialized(promise: Promise) {
        promise.resolve(QartveloAds.isInitialized())
    }

    // ---- full-screen formats --------------------------------------------------------------------

    override fun loadInterstitial(placementId: String, promise: Promise) = load(placementId, AdFormat.INTERSTITIAL, promise)

    override fun showInterstitial(placementId: String, promise: Promise) = show(placementId, AdFormat.INTERSTITIAL, promise)

    override fun isInterstitialReady(placementId: String, promise: Promise) {
        promise.resolve(QartveloAds.isInterstitialReady(placementId))
    }

    override fun loadRewarded(placementId: String, promise: Promise) = load(placementId, AdFormat.REWARDED, promise)

    override fun showRewarded(placementId: String, promise: Promise) = show(placementId, AdFormat.REWARDED, promise)

    override fun isRewardedReady(placementId: String, promise: Promise) {
        promise.resolve(QartveloAds.isRewardedReady(placementId))
    }

    private fun load(placementId: String, format: AdFormat, promise: Promise) {
        PlacementFormats.record(placementId, format)
        val listener = LoadCall(newCall(promise))
        if (format == AdFormat.REWARDED) {
            QartveloAds.loadRewarded(placementId, listener)
        } else {
            QartveloAds.loadInterstitial(placementId, listener)
        }
    }

    private fun show(placementId: String, format: AdFormat, promise: Promise) {
        PlacementFormats.record(placementId, format)
        val call = newCall(promise)
        val activity = reactApplicationContext.currentActivity
        if (activity == null) {
            call.reject(QartveloAdsErrorCode.SHOW_FAILED.wire, "No foreground Activity to show the ad in")
            return
        }
        val listener = ShowCall(format, call)
        if (format == AdFormat.REWARDED) {
            QartveloAds.showRewarded(activity, placementId, listener)
        } else {
            QartveloAds.showInterstitial(activity, placementId, listener)
        }
    }

    // ---- settings -------------------------------------------------------------------------------

    override fun setLogLevel(level: String) {
        try {
            QartveloAds.setLogLevel(Options.parseLogLevel(level))
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, e.message ?: "Invalid log level")
        }
    }

    override fun setPrivacy(privacy: ReadableMap) {
        try {
            QartveloAds.setPrivacy(Options.parsePrivacy(privacy.toHashMap()))
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, e.message ?: "Invalid privacy signals")
        }
    }

    // ---- plumbing -------------------------------------------------------------------------------

    private fun newCall(promise: Promise) = OneShot(PromiseCompletion(promise), pending)

    private fun emitEvent(payload: Map<String, Any?>) {
        // The emitter callback is installed by the TurboModule runtime after construction; until then
        // no JS listener can exist, so events are dropped.
        if (!active || mEventEmitterCallback == null) return
        try {
            emitOnAdEvent(Arguments.makeNativeMap(payload))
        } catch (t: Throwable) {
            Log.w(TAG, "Could not deliver an QartveloAds event to JS", t)
        }
    }

    private class PromiseCompletion(private val promise: Promise) : Completion {
        override fun resolve(value: Any?) {
            @Suppress("UNCHECKED_CAST")
            promise.resolve(if (value is Map<*, *>) Arguments.makeNativeMap(value as Map<String, Any?>) else value)
        }

        override fun reject(code: String, message: String) {
            promise.reject(code, message)
        }
    }

    companion object {
        const val NAME = NativeQartveloAdsSpec.NAME
        private const val TAG = "QartveloAdsRN"
        private const val INVALID_ARGUMENT = "invalid_argument"
    }
}
