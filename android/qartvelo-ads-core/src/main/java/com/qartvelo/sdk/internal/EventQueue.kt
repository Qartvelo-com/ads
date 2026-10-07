package com.qartvelo.sdk.internal

import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

internal enum class TrackedEventType(val path: String) {
    IMPRESSION("api/v1/events/impression"),
    CLICK("api/v1/events/click"),
    REWARD("api/v1/events/reward"),
}

/**
 * Serial background delivery of impression/click/reward events. A single worker thread guarantees an
 * impression is delivered (or definitively rejected) before a later click for the same ad.
 * Transient failures (network, 5xx, 408, 429) are retried with exponential backoff; the worker only
 * ever sleeps on its own thread, so the UI is never blocked.
 */
internal class EventQueue(
    private val api: ApiClient,
    private val retryBaseMs: Long = DEFAULT_RETRY_BASE_MS,
    private val executor: ExecutorService = Executors.newSingleThreadExecutor(daemonThreadFactory("QartveloAds-events")),
) {
    fun enqueue(type: TrackedEventType, ad: ServedAd, completion: Boolean? = null) {
        val body = JSONObject()
            .put("request_id", ad.requestId)
            .put("impression_token", ad.impressionToken)
        if (completion != null) body.put("completion", completion)
        val label = "${type.name.lowercase()} for ${ad.requestId}"
        try {
            executor.execute { deliver(type, body, label) }
        } catch (t: Throwable) {
            OurLog.e("Could not queue $label", t)
        }
    }

    /** Tests only. */
    fun shutdown() {
        executor.shutdownNow()
    }

    private fun deliver(type: TrackedEventType, body: JSONObject, label: String) {
        for (attempt in 0 until MAX_ATTEMPTS) {
            val retry = try {
                val code = api.postEvent(type.path, body, TIMEOUT_MS)
                when {
                    code in 200..299 -> {
                        OurLog.d("Event $label accepted")
                        return
                    }
                    code == 408 || code == 429 || code >= 500 -> true
                    else -> {
                        // 409 duplicate / 422 invalid or expired: retrying cannot help.
                        OurLog.i("Event $label rejected with HTTP $code")
                        return
                    }
                }
            } catch (e: IOException) {
                OurLog.i("Event $label failed: ${e.message}")
                true
            } catch (t: Throwable) {
                OurLog.e("Event $label failed unexpectedly", t)
                return
            }
            if (retry && attempt < MAX_ATTEMPTS - 1) {
                try {
                    Thread.sleep(retryBaseMs shl attempt)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                }
            }
        }
        OurLog.e("Event $label dropped after $MAX_ATTEMPTS attempts")
    }

    companion object {
        const val MAX_ATTEMPTS = 4
        const val DEFAULT_RETRY_BASE_MS = 1_000L
        const val TIMEOUT_MS = 10_000L
    }
}
