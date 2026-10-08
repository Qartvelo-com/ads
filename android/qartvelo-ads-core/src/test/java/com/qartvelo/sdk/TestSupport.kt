package com.qartvelo.sdk

import android.app.Activity
import android.content.Context
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import com.qartvelo.sdk.fallback.FallbackAdapter
import com.qartvelo.sdk.fallback.FallbackBanner
import com.qartvelo.sdk.fallback.FallbackBannerCallback
import com.qartvelo.sdk.fallback.FallbackLoadCallback
import com.qartvelo.sdk.fallback.FallbackSettings
import com.qartvelo.sdk.fallback.FallbackShowCallback
import com.qartvelo.sdk.internal.AdVideoPlayer
import com.qartvelo.sdk.internal.AspectFitLayout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.fail
import org.robolectric.Shadows.shadowOf
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream

/** Pumps the paused Robolectric main looper until [condition] holds (background threads run for real). */
fun awaitMain(timeoutMs: Long = 5_000, message: String = "condition", condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (true) {
        shadowOf(Looper.getMainLooper()).idle()
        if (condition()) return
        if (System.currentTimeMillis() > deadline) fail("Timed out waiting for $message")
        Thread.sleep(5)
    }
}

/** Lets background work settle, then drains the main looper (used to assert that nothing else happens). */
fun settle(ms: Long = 300) {
    val end = System.currentTimeMillis() + ms
    while (System.currentTimeMillis() < end) {
        shadowOf(Looper.getMainLooper()).idle()
        Thread.sleep(10)
    }
    shadowOf(Looper.getMainLooper()).idle()
}

/** Advances the fake clock (SystemClock + delayed main-thread tasks). */
fun advance(ms: Long) {
    shadowOf(Looper.getMainLooper()).idleFor(ms, TimeUnit.MILLISECONDS)
}

fun isoIn(ms: Long): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
    .apply { timeZone = TimeZone.getTimeZone("UTC") }
    .format(Date(System.currentTimeMillis() + ms))

/** Encodes a solid-colour RGB PNG (java.awt is not on the Android unit-test classpath). */
fun pngBytes(width: Int, height: Int): ByteArray {
    fun chunk(out: DataOutputStream, type: String, data: ByteArray) {
        val crc = CRC32().apply { update(type.toByteArray()); update(data) }
        out.writeInt(data.size)
        out.write(type.toByteArray())
        out.write(data)
        out.writeInt(crc.value.toInt())
    }
    val header = ByteArrayOutputStream().also { b ->
        DataOutputStream(b).apply { writeInt(width); writeInt(height); write(byteArrayOf(8, 2, 0, 0, 0)) }
    }.toByteArray()
    val raw = ByteArrayOutputStream().apply {
        repeat(height) {
            write(0)
            repeat(width) { write(byteArrayOf(0x1E, 0x3A, 0x8A.toByte())) }
        }
    }.toByteArray()
    val compressed = ByteArrayOutputStream().also { DeflaterOutputStream(it).use { d -> d.write(raw) } }.toByteArray()
    return ByteArrayOutputStream().also { b ->
        val out = DataOutputStream(b)
        out.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10))
        chunk(out, "IHDR", header)
        chunk(out, "IDAT", compressed)
        chunk(out, "IEND", ByteArray(0))
    }.toByteArray()
}

/** Minimal ISO-BMFF header; the fake player never decodes it. */
fun mp4Bytes(): ByteArray = byteArrayOf(0, 0, 0, 0x18) + "ftypmp42".toByteArray() + ByteArray(2048)

fun View.findByDescription(text: String): View? {
    if (contentDescription?.toString() == text) return this
    if (this is ViewGroup) for (i in 0 until childCount) getChildAt(i).findByDescription(text)?.let { return it }
    return null
}

fun View.findByText(text: String): View? {
    if (this is android.widget.TextView && this.text?.toString() == text) return this
    if (this is ViewGroup) for (i in 0 until childCount) getChildAt(i).findByText(text)?.let { return it }
    return null
}

data class Placement(
    val code: String,
    val format: String,
    val qartveloEnabled: Boolean = true,
    val fallbackProvider: String = "admob",
    val admobUnit: String? = "ca-app-pub-test/${code}",
    val timeoutMs: Int = 800,
    val refreshSeconds: Int = 60,
)

/** Scriptable stand-in for the Laravel API, matching CONTRACT.md section 5. */
class FakeBackend : Dispatcher() {
    lateinit var baseUrl: String
    val requests: MutableList<Pair<String, JSONObject>> = Collections.synchronizedList(ArrayList())
    var placements = listOf(
        Placement("game_end", "interstitial"),
        Placement("reward_coins", "rewarded"),
        Placement("home_banner", "banner"),
    )
    var initStatus = 200
    var initDelayMs = 0L
    var servingEnabled = true
    var sessionTtlMs = 3_600_000L
    var adDelayMs = 0L

    /** Queue of ad responses; when empty the backend answers no_fill. */
    val adResponses: MutableList<MockResponse> = CopyOnWriteArrayList()
    val creatives = HashMap<String, MockResponse>()
    val eventStatuses = HashMap<String, MutableList<Int>>()
    private var requestCounter = 0

    fun count(path: String): Int = synchronized(requests) { requests.count { it.first == path } }
    fun bodies(path: String): List<JSONObject> = synchronized(requests) { requests.filter { it.first == path }.map { it.second } }
    fun paths(): List<String> = synchronized(requests) { requests.map { it.first } }

    /** Like the real backend, `test_mode` in the config only echoes the request. */
    fun initJson(testMode: Boolean = false): JSONObject = JSONObject()
        .put("session_token", "sess-${System.nanoTime()}")
        .put("session_expires_at", isoIn(sessionTtlMs))
        .put("config", JSONObject().put("serving_enabled", servingEnabled).put("request_timeout_ms", 800)
            .put("fallback_enabled", true).put("test_mode", testMode).put("config_ttl_seconds", 3600))
        .put("placements", JSONArray().apply {
            placements.forEach { p ->
                put(JSONObject().put("code", p.code).put("format", p.format).put("ourads_enabled", p.qartveloEnabled)
                    .put("fallback_provider", p.fallbackProvider).put("admob_ad_unit_id", p.admobUnit ?: JSONObject.NULL)
                    .put("request_timeout_ms", p.timeoutMs).put("frequency_cap_count", JSONObject.NULL)
                    .put("frequency_cap_period", JSONObject.NULL).put("banner_refresh_seconds", p.refreshSeconds))
            }
        })

    fun fill(
        format: String,
        creativeType: String = if (format == "rewarded") "video" else "image",
        creativePath: String = if (creativeType == "video") "/creatives/v.mp4" else "/creatives/i.png",
        expiresInMs: Long = 30 * 60_000L,
        width: Int = if (format == "banner") 320 else 1080,
        height: Int = if (format == "banner") 50 else 1920,
        test: Boolean = false,
    ): MockResponse {
        val id = synchronized(this) { ++requestCounter }
        val body = JSONObject().put("status", "fill").put("request_id", "req_$id").put("ad", JSONObject()
            .put("id", "ad_$id").put("campaign_id", "cmp_12").put("creative_id", "cr_34").put("format", format)
            .put("creative_type", creativeType).put("creative_url", baseUrl.trimEnd('/') + creativePath)
            .put("click_url", "https://advertiser.example/landing").put("width", width).put("height", height)
            .put("duration_seconds", if (creativeType == "video") 15 else JSONObject.NULL)
            .put("impression_token", "imp-token-$id").put("expires_at", isoIn(expiresInMs)).put("test", test))
        return json(body)
    }

    fun noFill(fallback: String = "admob"): MockResponse = json(
        JSONObject().put("status", "no_fill").put("request_id", "req_nf").put("fallback", fallback).put("reason", "no_eligible_campaign"),
    )

    fun error(status: Int, code: String): MockResponse =
        json(JSONObject().put("error", JSONObject().put("code", code).put("message", code)), status)

    fun defaultCreatives() {
        creatives["/creatives/i.png"] = MockResponse().setBody(Buffer().write(pngBytes(108, 192))).setHeader("Content-Type", "image/png")
        creatives["/creatives/b.png"] = MockResponse().setBody(Buffer().write(pngBytes(320, 50))).setHeader("Content-Type", "image/png")
        creatives["/creatives/v.mp4"] = MockResponse().setBody(Buffer().write(mp4Bytes())).setHeader("Content-Type", "video/mp4")
    }

    override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.path?.substringBefore('?') ?: ""
        val body = request.body.readUtf8().let { if (it.isBlank()) JSONObject() else JSONObject(it) }
        requests.add(path to body)
        return when {
            path == "/api/v1/sdk/initialize" ->
                (if (initStatus == 200) json(initJson(body.optBoolean("test_mode"))) else error(initStatus, "invalid_app_key"))
                    .also { if (initDelayMs > 0) it.setBodyDelay(initDelayMs, TimeUnit.MILLISECONDS) }
            path == "/api/v1/ads/request" -> {
                val response = synchronized(adResponses) { if (adResponses.isEmpty()) null else adResponses.removeAt(0) }
                (response ?: noFill()).also { if (adDelayMs > 0) it.setBodyDelay(adDelayMs, TimeUnit.MILLISECONDS) }
            }
            path.startsWith("/creatives/") -> creatives[path] ?: MockResponse().setResponseCode(404)
            path.startsWith("/api/v1/events/") -> {
                val queue = eventStatuses[path]
                val status = synchronized(eventStatuses) { if (queue.isNullOrEmpty()) 200 else queue.removeAt(0) }
                json(JSONObject().put("status", if (status == 200) "accepted" else "rejected"), status)
            }
            else -> MockResponse().setResponseCode(404)
        }
    }

    private fun json(body: JSONObject, status: Int = 200) = MockResponse()
        .setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body.toString())
}

/** Records every callback and whether it arrived on the main thread. */
class RecordingListener(private val name: String = "listener") : QartveloAdsListener {
    val events: MutableList<String> = CopyOnWriteArrayList()
    val infos: MutableList<QartveloAdsAdInfo> = CopyOnWriteArrayList()
    val errors: MutableList<QartveloAdsError> = CopyOnWriteArrayList()
    val rewards: MutableList<QartveloAdsReward> = CopyOnWriteArrayList()
    @Volatile var offMainThread = 0

    private fun record(event: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) offMainThread++
        events.add(event)
    }

    fun has(prefix: String) = events.any { it.startsWith(prefix) }
    fun count(prefix: String) = events.count { it.startsWith(prefix) }
    override fun toString(): String = "$name$events"

    override fun onLoaded(info: QartveloAdsAdInfo) { infos.add(info); record("loaded:${info.placementId}:${info.source}") }
    override fun onLoadFailed(placementId: String, error: QartveloAdsError) { errors.add(error); record("loadFailed:$placementId:${error.code}") }
    override fun onShown(info: QartveloAdsAdInfo) = record("shown:${info.placementId}:${info.source}")
    override fun onImpression(info: QartveloAdsAdInfo) = record("impression:${info.placementId}:${info.source}")
    override fun onClicked(info: QartveloAdsAdInfo) = record("clicked:${info.placementId}:${info.source}")
    override fun onDismissed(info: QartveloAdsAdInfo) = record("dismissed:${info.placementId}:${info.source}")
    override fun onReward(info: QartveloAdsAdInfo, reward: QartveloAdsReward) { rewards.add(reward); record("reward:${info.placementId}:${info.source}") }
    override fun onFallbackStarted(placementId: String, format: AdFormat, reason: String) = record("fallbackStarted:$placementId:$reason")
    override fun onNoAdAvailable(placementId: String, format: AdFormat) = record("noAd:$placementId")
}

/** Scriptable fallback adapter. Callbacks can be delivered from a background thread like real SDKs. */
class FakeAdapter(
    var loadSucceeds: Boolean = true,
    var callbackFromBackground: Boolean = true,
    var rewardTimes: Int = 1,
) : FallbackAdapter {
    override val networkName = "fake"
    val loads: MutableList<Pair<String, String>> = CopyOnWriteArrayList()
    val shows: MutableList<String> = CopyOnWriteArrayList()
    val banners: MutableList<FakeBanner> = CopyOnWriteArrayList()
    var settings: FallbackSettings? = null

    /** When true, banner load results wait in [heldBannerLoads] until the test releases them. */
    var holdBanners = false
    val heldBannerLoads: MutableList<() -> Unit> = CopyOnWriteArrayList()
    private val ready = HashSet<String>()

    private fun deliver(block: () -> Unit) {
        if (callbackFromBackground) Thread(block).start() else block()
    }

    override fun initialize(context: Context, settings: FallbackSettings) { this.settings = settings }
    override fun updateSettings(settings: FallbackSettings) { this.settings = settings }

    private fun load(key: String, placementId: String, adUnitId: String, callback: FallbackLoadCallback) {
        loads.add(placementId to adUnitId)
        deliver {
            if (loadSucceeds) {
                synchronized(ready) { ready.add(key) }
                callback.onLoaded()
            } else {
                callback.onFailed("fake no fill")
            }
        }
    }

    private fun isReady(key: String) = synchronized(ready) { key in ready }

    private fun show(key: String, rewarded: Boolean, callback: FallbackShowCallback) {
        shows.add(key)
        synchronized(ready) { ready.remove(key) }
        deliver {
            callback.onShown()
            callback.onImpression()
            if (rewarded) repeat(rewardTimes) { callback.onReward("coins", 5) }
            callback.onDismissed()
            callback.onDismissed() // Duplicate terminal events must be swallowed by core.
        }
    }

    override fun loadInterstitial(context: Context, placementId: String, adUnitId: String, callback: FallbackLoadCallback) =
        load("i:$placementId", placementId, adUnitId, callback)
    override fun isInterstitialReady(placementId: String) = isReady("i:$placementId")
    override fun showInterstitial(activity: Activity, placementId: String, callback: FallbackShowCallback) =
        show("i:$placementId", false, callback)
    override fun loadRewarded(context: Context, placementId: String, adUnitId: String, callback: FallbackLoadCallback) =
        load("r:$placementId", placementId, adUnitId, callback)
    override fun isRewardedReady(placementId: String) = isReady("r:$placementId")
    override fun showRewarded(activity: Activity, placementId: String, callback: FallbackShowCallback) =
        show("r:$placementId", true, callback)

    override fun createBanner(context: Context, placementId: String, adUnitId: String, widthDp: Int, callback: FallbackBannerCallback): FallbackBanner {
        val banner = FakeBanner(context, adUnitId)
        banners.add(banner)
        loads.add(placementId to adUnitId)
        val result = { if (loadSucceeds) callback.onLoaded() else callback.onFailed("fake banner no fill") }
        if (holdBanners) heldBannerLoads.add(result) else deliver(result)
        return banner
    }

    class FakeBanner(val context: Context, val adUnitId: String) : FallbackBanner {
        override val view: View = View(context)
        var destroyed = false
        var paused = false
        override fun pause() { paused = true }
        override fun resume() { paused = false }
        override fun destroy() { destroyed = true }
    }
}

/** Video player stand-in driven by the test. */
internal class FakePlayer : AdVideoPlayer {
    override var listener: AdVideoPlayer.Listener? = null
    override var positionMs: Long = 0
    override val durationMs: Long = 15_000
    var prepareCount = 0
    var attachCount = 0
    var playing = false
    var released = false

    override fun prepare(file: File) { prepareCount++ }
    override fun attach(container: AspectFitLayout) { attachCount++ }
    override fun detach() {}
    override fun play() { playing = true }
    override fun pause() { playing = false }
    override fun release() { released = true }

    fun firstFrame() = listener?.onFirstFrame()
    fun complete() = listener?.onCompleted()
}
