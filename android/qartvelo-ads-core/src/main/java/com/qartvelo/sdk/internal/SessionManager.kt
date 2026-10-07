package com.qartvelo.sdk.internal

import org.json.JSONObject
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * Owns the ephemeral session token. Exactly one `/sdk/initialize` call is in flight at a time;
 * concurrent callers join it. Tokens are kept in memory only.
 *
 * - [acquire] returns a valid token, refreshing proactively in the background when it is close to
 *   expiry and synchronously (bounded by the caller's deadline) when it has expired.
 * - After a failure, new attempts are throttled so offline starts fail fast instead of piling up.
 */
internal class SessionManager(
    private val api: ApiClient,
    /** Dedicated executor so session calls never queue behind ad requests or downloads. */
    private val executor: Executor,
    private val initBody: () -> JSONObject,
    private val initTimeoutMs: Long,
    private val onInitialized: (InitResult) -> Unit,
) {
    private val lock = Any()
    private var inFlight: Attempt? = null
    private var lastFailure: Throwable? = null
    private var lastFailureAt = 0L

    @Volatile
    private var session: Session? = null

    /** Token if one is currently valid, without any network activity. */
    fun currentToken(): String? = session?.takeIf { it.expiresAtElapsed - Clock.elapsed() > MIN_VALIDITY_MS }?.token

    /** Starts (or joins) a session refresh; [callback] runs on a background thread with the outcome. */
    fun refreshAsync(force: Boolean = false, callback: ((Throwable?) -> Unit)? = null) {
        val attempt = startOrJoin(force)
        if (callback != null) attempt.onComplete(callback)
    }

    /** Blocking; never call on the main thread. Throws [IOException] when no token is available in time. */
    fun acquire(deadlineElapsed: Long): String {
        val s = session
        val now = Clock.elapsed()
        if (s != null && s.expiresAtElapsed - now > MIN_VALIDITY_MS) {
            if (s.expiresAtElapsed - now < PROACTIVE_REFRESH_MS) refreshAsync()
            return s.token
        }
        val attempt = synchronized(lock) {
            val failure = lastFailure
            if (inFlight == null && failure != null && now - lastFailureAt < FAILURE_BACKOFF_MS) {
                throw failure as? IOException ?: IOException(failure.message)
            }
            startOrJoin(force = false)
        }
        val remaining = deadlineElapsed - Clock.elapsed()
        if (remaining <= 0) throw InterruptedIOException("timeout waiting for session")
        val error = attempt.await(remaining)
        if (error != null) throw error as? IOException ?: IOException(error.message, error)
        return session?.token ?: throw IOException("no session")
    }

    /** Drops [token] after the API rejected it, so the next [acquire] re-initializes. */
    fun invalidate(token: String) {
        synchronized(lock) {
            if (session?.token == token) session = null
            lastFailure = null
        }
    }

    private fun startOrJoin(force: Boolean): Attempt = synchronized(lock) {
        inFlight?.let { return it }
        if (!force && session?.let { it.expiresAtElapsed - Clock.elapsed() > PROACTIVE_REFRESH_MS } == true) {
            return Attempt().also { it.complete(null) }
        }
        val attempt = Attempt()
        inFlight = attempt
        executor.execute {
            var error: Throwable? = null
            try {
                val result = api.initialize(initBody(), initTimeoutMs)
                synchronized(lock) {
                    session = result.session
                    lastFailure = null
                }
                onInitialized(result)
            } catch (t: Throwable) {
                error = t
                synchronized(lock) {
                    lastFailure = t
                    lastFailureAt = Clock.elapsed()
                }
                OurLog.e("QartveloAds session request failed: ${t.message}")
            } finally {
                synchronized(lock) { inFlight = null }
                attempt.complete(error)
            }
        }
        attempt
    }

    /** Minimal one-shot future (CompletableFuture needs API 24). */
    private class Attempt {
        private val latch = CountDownLatch(1)
        private val callbacks = ArrayList<(Throwable?) -> Unit>()
        private var done = false

        @Volatile
        private var error: Throwable? = null

        fun complete(e: Throwable?) {
            val toRun = synchronized(callbacks) {
                error = e
                done = true
                latch.countDown()
                ArrayList(callbacks).also { callbacks.clear() }
            }
            toRun.forEach { cb -> guard("session callback") { cb(e) } }
        }

        fun onComplete(cb: (Throwable?) -> Unit) {
            val runNow = synchronized(callbacks) {
                if (!done) callbacks.add(cb)
                done
            }
            if (runNow) guard("session callback") { cb(error) }
        }

        /** Returns the failure, or null on success. Times out as an [InterruptedIOException]. */
        fun await(timeoutMs: Long): Throwable? {
            if (!latch.await(timeoutMs.coerceAtLeast(1), TimeUnit.MILLISECONDS)) {
                return InterruptedIOException("timeout waiting for session")
            }
            return error
        }
    }

    companion object {
        /** A token must have at least this much validity left to be used for a request. */
        const val MIN_VALIDITY_MS = 15_000L

        /** Refresh in the background once a token is this close to expiry. */
        const val PROACTIVE_REFRESH_MS = 5 * 60_000L

        /** After a failed initialize, skip new attempts for this long (fail fast while offline). */
        const val FAILURE_BACKOFF_MS = 5_000L
    }
}
