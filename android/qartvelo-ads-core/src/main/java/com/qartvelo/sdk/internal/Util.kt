package com.qartvelo.sdk.internal

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.qartvelo.sdk.AdFormat
import com.qartvelo.sdk.QartveloAdsLogLevel
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

/** Level-gated logcat output. Callers must never pass tokens, signatures or request bodies. */
internal object OurLog {
    private const val TAG = "QartveloAds"

    @Volatile
    var level: QartveloAdsLogLevel = QartveloAdsLogLevel.ERROR

    fun e(message: String, t: Throwable? = null) {
        if (level >= QartveloAdsLogLevel.ERROR) safe { Log.e(TAG, message, t) }
    }

    fun i(message: String) {
        if (level >= QartveloAdsLogLevel.INFO) safe { Log.i(TAG, message) }
    }

    fun d(message: String) {
        if (level >= QartveloAdsLogLevel.DEBUG) safe { Log.d(TAG, message) }
    }

    private inline fun safe(block: () -> Unit) {
        try {
            block()
        } catch (_: Throwable) {
            // Logging must never take the host app down.
        }
    }
}

/** Main-thread helpers. All SDK state machines live on the main thread. */
internal object Main {
    val handler: Handler by lazy { Handler(Looper.getMainLooper()) }

    fun isMainThread(): Boolean = Looper.myLooper() == Looper.getMainLooper()

    fun post(block: () -> Unit) {
        handler.post { guard("main task", block) }
    }

    fun postDelayed(delayMs: Long, block: Runnable) {
        handler.postDelayed(block, delayMs.coerceAtLeast(0))
    }

    fun cancel(block: Runnable) {
        handler.removeCallbacks(block)
    }

    /** Runs inline when already on the main thread, otherwise posts. */
    fun run(block: () -> Unit) {
        if (isMainThread()) guard("main task", block) else post(block)
    }
}

/** Swallows and logs any Throwable so SDK failures never propagate into the host app. */
internal inline fun guard(what: String, block: () -> Unit) {
    try {
        block()
    } catch (t: Throwable) {
        OurLog.e("Unexpected failure in $what", t)
    }
}

internal object Clock {
    fun elapsed(): Long = SystemClock.elapsedRealtime()
}

internal fun daemonThreadFactory(prefix: String): ThreadFactory {
    val counter = AtomicInteger()
    return ThreadFactory { r ->
        Thread(r, "$prefix-${counter.incrementAndGet()}").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY - 1
        }
    }
}

internal fun newIoExecutor() = Executors.newFixedThreadPool(4, daemonThreadFactory("QartveloAds-io"))

internal val AdFormat.wireName: String get() = name.lowercase()

internal fun adFormatFromWire(value: String?): AdFormat? = when (value) {
    "banner" -> AdFormat.BANNER
    "interstitial" -> AdFormat.INTERSTITIAL
    "rewarded" -> AdFormat.REWARDED
    else -> null
}

/**
 * Parses the API's ISO-8601 UTC timestamps (`2026-10-07T18:40:00Z`, optional fraction or offset)
 * without java.time, which is unavailable below API 26. Returns epoch millis or null.
 */
internal fun parseIsoMillis(value: String?): Long? {
    if (value.isNullOrBlank()) return null
    val m = ISO.matchEntire(value.trim()) ?: return null
    val g = m.groupValues
    return try {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.clear()
        cal.set(g[1].toInt(), g[2].toInt() - 1, g[3].toInt(), g[4].toInt(), g[5].toInt(), g[6].toInt())
        var millis = cal.timeInMillis
        if (g[7].isNotEmpty()) millis += g[7].padEnd(3, '0').take(3).toLong()
        val zone = g[8]
        if (zone.isNotEmpty() && zone != "Z" && zone != "z") {
            val sign = if (zone[0] == '-') -1 else 1
            val digits = zone.substring(1).replace(":", "")
            val offset = digits.take(2).toInt() * 3_600_000L + digits.drop(2).toInt() * 60_000L
            millis -= sign * offset
        }
        millis
    } catch (_: Throwable) {
        null
    }
}

private val ISO = Regex(
    "(\\d{4})-(\\d{2})-(\\d{2})[T ](\\d{2}):(\\d{2}):(\\d{2})(?:\\.(\\d{1,9}))?(Z|z|[+-]\\d{2}:?\\d{2})?",
)
