package com.qartvelo.sdk.internal

import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Lets the main-thread timeout cancel an in-flight HTTP call. */
internal class CallTracker {
    private val call = AtomicReference<Call?>()

    @Volatile
    var cancelled: Boolean = false
        private set

    fun track(c: Call) {
        call.set(c)
        if (cancelled) c.cancel()
    }

    fun cancel() {
        cancelled = true
        call.get()?.cancel()
    }
}

/**
 * Blocking HTTP client for the QartveloAds API. Every method must be called off the main thread.
 * OkHttp provides keep-alive pooling and transparent gzip; each call gets a strict call timeout.
 */
internal class ApiClient(
    baseUrl: String,
    private val userAgent: String,
    private val client: OkHttpClient = sharedClient,
) {
    private val base: HttpUrl? = baseUrl.trim().let { if (it.endsWith("/")) it else "$it/" }.toHttpUrlOrNull()

    val isConfigured: Boolean get() = base != null

    fun initialize(body: JSONObject, timeoutMs: Long, tracker: CallTracker? = null): InitResult {
        val (json, serverNow) = postJson("api/v1/sdk/initialize", body, timeoutMs, tracker)
        val token = json.optStringOrNull("session_token") ?: throw IOException("initialize: missing session_token")
        val expiresAt = parseIsoMillis(json.optString("session_expires_at")) ?: (serverNow + 3_600_000L)
        // `test_mode` is only an echo of the request; it must never outlive this process (see Engine.testMode).
        val config = (json.optJSONObject("config") ?: JSONObject()).apply { remove("test_mode") }
        val cacheable = JSONObject()
            .put("config", config)
            .put("placements", json.optJSONArray("placements") ?: org.json.JSONArray())
        return InitResult(
            session = Session(token, toElapsed(expiresAt, serverNow)),
            config = RemoteConfig.parse(cacheable),
            cacheableJson = cacheable.toString(),
        )
    }

    fun requestAd(body: JSONObject, timeoutMs: Long, tracker: CallTracker?): AdResponse {
        val (json, serverNow) = postJson("api/v1/ads/request", body, timeoutMs, tracker)
        return when (json.optString("status")) {
            "fill" -> AdResponse.Fill(parseAd(json, serverNow))
            "no_fill" -> AdResponse.NoFill(
                requestId = json.optStringOrNull("request_id"),
                fallback = json.optStringOrNull("fallback"),
                reason = json.optStringOrNull("reason"),
            )
            else -> throw IOException("ads/request: unexpected status")
        }
    }

    /** Posts an event and returns the HTTP status. Network failures throw [IOException]. */
    fun postEvent(path: String, body: JSONObject, timeoutMs: Long): Int {
        val call = newCall(path, body, timeoutMs) ?: throw IOException("invalid base url")
        call.execute().use { return it.code }
    }

    /** Streams [url] into [dest] (via a temp file), rejecting non-2xx answers and oversized bodies. */
    fun download(url: String, dest: File, maxBytes: Long, timeoutMs: Long, tracker: CallTracker? = null) {
        val httpUrl = url.toHttpUrlOrNull() ?: throw IOException("invalid creative url")
        val request = Request.Builder().url(httpUrl).header("User-Agent", userAgent).get().build()
        val call = client.newCall(request)
        call.timeout().timeout(timeoutMs, TimeUnit.MILLISECONDS)
        tracker?.track(call)
        call.execute().use { response ->
            if (!response.isSuccessful) throw IOException("creative HTTP ${response.code}")
            val body = response.body ?: throw IOException("creative without body")
            val declared = body.contentLength()
            if (declared > maxBytes) throw IOException("creative too large ($declared bytes)")
            val tmp = File(dest.parentFile, dest.name + ".part")
            var total = 0L
            try {
                body.byteStream().use { input ->
                    tmp.outputStream().use { output ->
                        val buffer = ByteArray(16 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            if (total > maxBytes) throw IOException("creative too large")
                            output.write(buffer, 0, read)
                        }
                    }
                }
                if (total == 0L) throw IOException("empty creative")
                if (!tmp.renameTo(dest)) throw IOException("cannot store creative")
            } finally {
                tmp.delete()
            }
        }
    }

    private fun newCall(path: String, body: JSONObject, timeoutMs: Long): Call? {
        val url = base?.resolve(path) ?: return null
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Accept", "application/json")
            .post(body.toString().toRequestBody(JSON))
            .build()
        return client.newCall(request).also { it.timeout().timeout(timeoutMs, TimeUnit.MILLISECONDS) }
    }

    private fun postJson(path: String, body: JSONObject, timeoutMs: Long, tracker: CallTracker?): Pair<JSONObject, Long> {
        val call = newCall(path, body, timeoutMs) ?: throw IOException("invalid base url")
        tracker?.track(call)
        val started = Clock.elapsed()
        call.execute().use { response ->
            OurLog.d("POST /$path -> ${response.code} in ${Clock.elapsed() - started} ms")
            val text = response.body?.string().orEmpty()
            val json = try {
                if (text.isEmpty()) JSONObject() else JSONObject(text)
            } catch (e: JSONException) {
                throw IOException("$path: malformed JSON (HTTP ${response.code})")
            }
            if (!response.isSuccessful) {
                val err = json.optJSONObject("error")
                throw ApiException(
                    httpStatus = response.code,
                    code = err?.optStringOrNull("code") ?: "http_${response.code}",
                    message = err?.optStringOrNull("message") ?: "HTTP ${response.code}",
                    details = err?.optJSONObject("details"),
                )
            }
            return json to serverNow(response)
        }
    }

    private fun parseAd(root: JSONObject, serverNow: Long): ServedAd {
        val ad = root.optJSONObject("ad") ?: throw IOException("fill without ad")
        val creativeUrl = ad.optStringOrNull("creative_url")?.takeIf { it.isHttpUrl() }
            ?: throw IOException("fill with invalid creative_url")
        val token = ad.optStringOrNull("impression_token") ?: throw IOException("fill without impression_token")
        val expiresAt = parseIsoMillis(ad.optString("expires_at")) ?: throw IOException("fill without expires_at")
        val type = when (ad.optString("creative_type")) {
            "image" -> CreativeType.IMAGE
            "video" -> CreativeType.VIDEO
            "html5" -> CreativeType.HTML5
            else -> throw IOException("unsupported creative_type")
        }
        val bundle = if (type == CreativeType.HTML5) parseBundle(ad, creativeUrl) else null
        return ServedAd(
            requestId = root.optStringOrNull("request_id") ?: throw IOException("fill without request_id"),
            adId = ad.optString("id"),
            campaignId = ad.optStringOrNull("campaign_id"),
            creativeId = ad.optStringOrNull("creative_id"),
            format = adFormatFromWire(ad.optString("format")) ?: throw IOException("fill with unknown format"),
            creativeType = type,
            creativeUrl = creativeUrl,
            clickUrl = ad.optStringOrNull("click_url")?.takeIf { it.isHttpUrl() },
            width = ad.optInt("width"),
            height = ad.optInt("height"),
            durationSeconds = ad.optPositiveLong("duration_seconds")?.toInt(),
            impressionToken = token,
            expiresAtElapsed = toElapsed(expiresAt, serverNow),
            test = ad.optBoolean("test", false),
            bundle = bundle,
        )
    }

    /**
     * The layout matching the fill's width and height (else the first) with its files. A fill
     * without files, or with a path that leaves the bundle, is rejected.
     */
    private fun parseBundle(ad: JSONObject, creativeUrl: String): Html5Bundle {
        val base = Html5Files.baseOf(creativeUrl) ?: throw IOException("html5 creative_url is not an index.html")
        val layouts = ad.optJSONArray("layouts") ?: throw IOException("html5 fill without layouts")
        val width = ad.optInt("width")
        val height = ad.optInt("height")
        val candidates = (0 until layouts.length()).mapNotNull { layouts.optJSONObject(it) }
        val layout = candidates.firstOrNull { it.optInt("width") == width && it.optInt("height") == height }
            ?: candidates.firstOrNull()
            ?: throw IOException("html5 fill without layouts")
        val list = layout.optJSONArray("files") ?: throw IOException("html5 layout without files")
        val files = (0 until list.length()).map { list.optString(it) }.distinct()
        if (files.isEmpty() || files.size > MAX_BUNDLE_FILES || Html5Files.INDEX !in files || !files.all { Html5Files.isSafePath(it) }) {
            throw IOException("html5 layout with invalid files")
        }
        return Html5Bundle(base, files, layout.optInt("width", width), layout.optInt("height", height))
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private const val MAX_BUNDLE_FILES = 60

        /** One pooled client per process: keep-alive connections are shared by all calls. */
        val sharedClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
        }

        private fun serverNow(response: Response): Long =
            response.headers.getDate("Date")?.time ?: System.currentTimeMillis()

        /** Maps a server wall-clock instant onto elapsedRealtime, cancelling out device clock skew. */
        private fun toElapsed(epochMillis: Long, serverNow: Long): Long =
            Clock.elapsed() + (epochMillis - serverNow)
    }
}

internal fun String.isHttpUrl(): Boolean {
    val lower = lowercase()
    return (lower.startsWith("https://") || lower.startsWith("http://")) && toHttpUrlOrNull() != null
}
