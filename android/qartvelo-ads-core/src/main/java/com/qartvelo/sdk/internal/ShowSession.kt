package com.qartvelo.sdk.internal

import android.app.Activity
import android.content.Context
import com.qartvelo.sdk.AdFormat
import com.qartvelo.sdk.AdSource
import com.qartvelo.sdk.QartveloAdsAdInfo
import com.qartvelo.sdk.QartveloAdsListener
import com.qartvelo.sdk.QartveloAdsReward
import java.lang.ref.WeakReference
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Live full-screen QartveloAds shows, looked up by [QartveloAdsActivity] through an intent extra. */
internal object ShowRegistry {
    private val sessions = ConcurrentHashMap<String, ShowSession>()

    fun put(session: ShowSession) {
        sessions[session.id] = session
    }

    fun get(id: String): ShowSession? = sessions[id]

    fun remove(id: String) {
        sessions.remove(id)
    }

    fun clear() = sessions.clear()
}

/**
 * State of one QartveloAds full-screen show. It outlives [QartveloAdsActivity] instances, so a configuration
 * change or activity re-creation never replays the video or re-emits shown/impression/reward events.
 * Main thread only (the reward guard is atomic regardless).
 */
internal class ShowSession(
    private val engine: Engine,
    private val controller: FullscreenController,
    val ad: ServedAd,
    private val listener: QartveloAdsListener?,
    hostActivity: Activity,
) {
    val id: String = UUID.randomUUID().toString()
    val format: AdFormat get() = controller.format
    val isRewarded: Boolean get() = controller.format == AdFormat.REWARDED
    private val host = WeakReference(hostActivity)
    private val info = QartveloAdsAdInfo(controller.placementId, controller.format, AdSource.QARTVELO, ad.campaignId, ad.creativeId)

    var rendered: Boolean = false
        private set
    var renderedAtElapsed: Long = 0L
        private set
    var videoCompleted: Boolean = false
        private set
    var playbackFailed: Boolean = false
        private set
    var confirmVisible: Boolean = false
    var closed: Boolean = false
        private set

    /** Video player owned by the session (created with the application context) across re-creation. */
    var player: AdVideoPlayer? = null

    private var clickRecorded = false
    private val rewardGranted = AtomicBoolean(false)

    /** The creative is visibly on screen: emit shown + impression once and queue the impression event. */
    fun onRendered() {
        if (rendered || closed) return
        rendered = true
        renderedAtElapsed = Clock.elapsed()
        engine.events.enqueue(TrackedEventType.IMPRESSION, ad)
        Listeners.emit(listener, "onShown") { it.onShown(info) }
        Listeners.emit(listener, "onImpression") { it.onImpression(info) }
    }

    /** Records the click (queued after the impression) before handing the URL to the browser. */
    fun onClick(context: Context) {
        if (!rendered || closed) return
        val url = ad.clickUrl ?: return
        if (!clickRecorded) {
            clickRecorded = true
            engine.events.enqueue(TrackedEventType.CLICK, ad)
            Listeners.emit(listener, "onClicked") { it.onClicked(info) }
        }
        ClickOpener.open(context, url)
    }

    /** Playback reached the end. Rewarded placements grant exactly one reward here, never earlier. */
    fun onVideoCompleted() {
        if (videoCompleted || !rendered || closed) return
        videoCompleted = true
        if (isRewarded && rewardGranted.compareAndSet(false, true)) {
            engine.events.enqueue(TrackedEventType.REWARD, ad, completion = true)
            Listeners.emit(listener, "onReward") { it.onReward(info, QartveloAdsReward()) }
        }
    }

    fun onPlaybackFailed() {
        playbackFailed = true
    }

    /** Creative failed before display: no events were sent, so the controller may fall back. */
    fun onRenderFailed() {
        if (rendered || closed) return
        closed = true
        release()
        controller.onQartveloAdsRenderFailed(host.get(), listener)
    }

    /** User closed the ad (or the system finished the activity). Idempotent. */
    fun close() {
        if (closed) return
        closed = true
        release()
        controller.onSessionClosed()
        if (!rendered) {
            // Closed before anything was displayed: no impression, so report "nothing shown".
            Listeners.emit(listener, "onNoAdAvailable") { it.onNoAdAvailable(info.placementId, info.format) }
            return
        }
        if (isRewarded && !rewardGranted.get()) {
            // Reported as incomplete so the backend can tell skips from completions.
            engine.events.enqueue(TrackedEventType.REWARD, ad, completion = false)
        }
        Listeners.emit(listener, "onDismissed") { it.onDismissed(info) }
    }

    /** Runs decoding or other disk work off the main thread. */
    fun runInBackground(task: () -> Unit) {
        try {
            engine.io.execute { guard("background task") { task() } }
        } catch (t: Throwable) {
            OurLog.e("Background executor unavailable", t)
        }
    }

    private fun release() {
        ShowRegistry.remove(id)
        guard("player release") { player?.release() }
        player = null
    }
}
