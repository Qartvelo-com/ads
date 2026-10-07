package com.qartvelo.reactnative

import android.os.Handler
import android.os.Looper
import com.qartvelo.sdk.QartveloAds
import java.util.Collections
import java.util.WeakHashMap

/**
 * Holds banner loads until `initialize()` has reached the SDK. React can mount a banner before the
 * asynchronous initialize call runs natively, and the SDK fails banner loads issued before
 * `QartveloAds.initialize()` with NOT_INITIALIZED. Once the SDK object exists, loads issued while it is
 * still starting are queued by the SDK itself.
 */
internal object InitGate {
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var opened = false

    /** Main thread only. Weak, so a view React dropped without notice is not retained. */
    private val waiting: MutableSet<QartveloAdsBannerHostView> = Collections.newSetFromMap(WeakHashMap())

    fun isOpen(): Boolean = opened || QartveloAds.isInitialized()

    /** Any thread; call right after `QartveloAds.initialize()` returned. */
    fun open() {
        if (opened) return
        opened = true
        main.post {
            val views = waiting.toList()
            waiting.clear()
            views.forEach { it.onInitializeRequested() }
        }
    }

    /** Main thread. */
    fun await(view: QartveloAdsBannerHostView) {
        waiting.add(view)
    }

    /** Main thread. */
    fun cancel(view: QartveloAdsBannerHostView) {
        waiting.remove(view)
    }
}
