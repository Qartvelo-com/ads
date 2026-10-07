package com.qartvelo.reactnative

import com.qartvelo.sdk.AdFormat
import com.qartvelo.sdk.AdSource
import com.qartvelo.sdk.QartveloAdsAdInfo
import com.qartvelo.sdk.QartveloAdsError
import com.qartvelo.sdk.QartveloAdsListener
import com.qartvelo.sdk.QartveloAdsReward
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/** Where a call's outcome goes: a JS promise in production, a recorder in tests. */
internal interface Completion {
    fun resolve(value: Any?)

    fun reject(code: String, message: String)
}

/** In-flight calls of one module instance, so a React teardown can drop them all. */
internal class PendingCalls {
    private val calls = ConcurrentHashMap.newKeySet<OneShot>()

    val size: Int get() = calls.size

    fun add(call: OneShot) {
        calls.add(call)
    }

    fun remove(call: OneShot) {
        calls.remove(call)
    }

    /** Forgets every pending promise without settling it (the JS runtime is going away). */
    fun cancelAll() {
        val snapshot = calls.toList()
        calls.clear()
        snapshot.forEach { it.cancel() }
    }
}

/**
 * Settles one call at most once. The completion is released on settle, so an SDK listener that is
 * retained after the call (for example as the placement's "last load listener") cannot keep the
 * promise or its JS callbacks alive.
 */
internal class OneShot(completion: Completion, private val pending: PendingCalls? = null) {
    private val target = AtomicReference<Completion?>(completion)

    init {
        pending?.add(this)
    }

    val isSettled: Boolean get() = target.get() == null

    fun resolve(value: Any?): Boolean {
        val completion = take() ?: return false
        completion.resolve(value)
        return true
    }

    fun reject(code: String, message: String): Boolean {
        val completion = take() ?: return false
        completion.reject(code, message)
        return true
    }

    fun cancel() {
        take()
    }

    private fun take(): Completion? = target.getAndSet(null)?.also { pending?.remove(this) }
}

/**
 * Per-call listener of a load. The SDK guarantees exactly one of `onLoaded` / `onLoadFailed` per
 * load call (joined loads included); [OneShot] makes any repeat harmless.
 */
internal class LoadCall(private val result: OneShot) : QartveloAdsListener {
    override fun onLoaded(info: QartveloAdsAdInfo) {
        result.resolve(Wire.adInfo(info))
    }

    override fun onLoadFailed(placementId: String, error: QartveloAdsError) {
        result.reject(error.code.wire, error.message)
    }
}

/**
 * Per-call listener of a show. Resolves on dismissal with the reward confirmed during this show (at
 * most one), resolves `shown = false` when nothing could be shown, and rejects on show errors such
 * as `already_showing`. Callbacks arrive on the main thread.
 */
internal class ShowCall(private val format: AdFormat, private val result: OneShot) : QartveloAdsListener {
    private var reward: QartveloAdsReward? = null
    private var source: AdSource? = null

    override fun onShown(info: QartveloAdsAdInfo) {
        if (source == null) source = info.source
    }

    override fun onReward(info: QartveloAdsAdInfo, reward: QartveloAdsReward) {
        if (format == AdFormat.REWARDED && this.reward == null && !result.isSettled) this.reward = reward
    }

    override fun onDismissed(info: QartveloAdsAdInfo) {
        result.resolve(Wire.showResult(shown = true, source = source ?: info.source, reward = reward))
    }

    override fun onNoAdAvailable(placementId: String, format: AdFormat) {
        result.resolve(Wire.showResult(shown = false, source = null, reward = null))
    }

    override fun onLoadFailed(placementId: String, error: QartveloAdsError) {
        result.reject(error.code.wire, error.message)
    }
}
