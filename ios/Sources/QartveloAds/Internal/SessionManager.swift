import Foundation

/// Owns the ephemeral session token. Exactly one `/sdk/initialize` call is in flight at a time;
/// concurrent callers join it. Tokens are kept in memory only.
///
/// - `acquire` returns a valid token, refreshing proactively in the background when it is close to
///   expiry and synchronously (bounded by the caller's deadline) when it has expired.
/// - After a failure, new attempts are throttled so offline starts fail fast instead of piling up.
final class SessionManager {
    /// A token must have at least this much validity left to be used for a request.
    static let minValidityMs: Int64 = 15_000
    /// Refresh in the background once a token is this close to expiry.
    static let proactiveRefreshMs: Int64 = 5 * 60_000
    /// After a failed initialize, skip new attempts for this long (fail fast while offline).
    static let failureBackoffMs: Int64 = 5_000

    private let api: ApiClient
    /// Dedicated queue so session calls never wait behind ad requests or downloads.
    private let queue = DispatchQueue(label: "com.qartvelo.ads.session")
    private let initBody: () -> JSON
    private let initTimeoutMs: Int64
    private let onInitialized: (InitResult) -> Void

    private let lock = NSLock()
    private var inFlight: Attempt?
    private var lastFailure: Error?
    private var lastFailureAt: Int64 = 0
    private var session: Session?

    init(api: ApiClient, initBody: @escaping () -> JSON, initTimeoutMs: Int64, onInitialized: @escaping (InitResult) -> Void) {
        self.api = api
        self.initBody = initBody
        self.initTimeoutMs = initTimeoutMs
        self.onInitialized = onInitialized
    }

    /// Token if one is currently valid, without any network activity.
    func currentToken() -> String? {
        lock.lock()
        defer { lock.unlock() }
        guard let session = session, session.expiresAt - Clock.now() > Self.minValidityMs else { return nil }
        return session.token
    }

    /// Starts (or joins) a session refresh; `callback` runs on a background thread with the outcome.
    func refreshAsync(force: Bool = false, callback: ((Error?) -> Void)? = nil) {
        let attempt = startOrJoin(force: force)
        if let callback = callback { attempt.onComplete(callback) }
    }

    /// Blocking; never call on the main thread. Throws when no token is available before `deadline`.
    func acquire(deadline: Int64) throws -> String {
        let now = Clock.now()
        lock.lock()
        if let session = session, session.expiresAt - now > Self.minValidityMs {
            let token = session.token
            let refreshSoon = session.expiresAt - now < Self.proactiveRefreshMs
            lock.unlock()
            if refreshSoon { refreshAsync() }
            return token
        }
        if inFlight == nil, let failure = lastFailure, now - lastFailureAt < Self.failureBackoffMs {
            lock.unlock()
            throw failure
        }
        lock.unlock()
        let attempt = startOrJoin(force: false)
        let remaining = deadline - Clock.now()
        if remaining <= 0 { throw TimeoutError(message: "timeout waiting for session") }
        if let error = attempt.await(timeoutMs: remaining) { throw error }
        guard let token = currentToken() else { throw NetworkError(message: "no session") }
        return token
    }

    /// Drops `token` after the API rejected it, so the next `acquire` re-initializes.
    func invalidate(_ token: String) {
        lock.lock()
        if session?.token == token { session = nil }
        lastFailure = nil
        lock.unlock()
    }

    private func startOrJoin(force: Bool) -> Attempt {
        lock.lock()
        if let current = inFlight {
            lock.unlock()
            return current
        }
        if !force, let session = session, session.expiresAt - Clock.now() > Self.proactiveRefreshMs {
            lock.unlock()
            let done = Attempt()
            done.complete(nil)
            return done
        }
        let attempt = Attempt()
        inFlight = attempt
        lock.unlock()

        queue.async { [self] in
            var failure: Error?
            do {
                let result = try api.initialize(body: initBody(), timeoutMs: initTimeoutMs)
                lock.lock()
                session = result.session
                lastFailure = nil
                lock.unlock()
                onInitialized(result)
            } catch {
                failure = error
                lock.lock()
                lastFailure = error
                lastFailureAt = Clock.now()
                lock.unlock()
                Log.e("QartveloAds session request failed: \(Self.describe(error))")
            }
            lock.lock()
            inFlight = nil
            lock.unlock()
            attempt.complete(failure)
        }
        return attempt
    }

    static func describe(_ error: Error) -> String {
        switch error {
        case let api as ApiError: return "\(api.code) (HTTP \(api.httpStatus)): \(api.message)"
        case let timeout as TimeoutError: return timeout.message
        case let network as NetworkError: return network.message
        default: return error.localizedDescription
        }
    }

    /// Minimal one-shot future.
    private final class Attempt {
        private let group = DispatchGroup()
        private let lock = NSLock()
        private var callbacks: [(Error?) -> Void] = []
        private var done = false
        private var error: Error?

        init() {
            group.enter()
        }

        func complete(_ failure: Error?) {
            lock.lock()
            guard !done else {
                lock.unlock()
                return
            }
            error = failure
            done = true
            let toRun = callbacks
            callbacks.removeAll()
            lock.unlock()
            group.leave()
            toRun.forEach { $0(failure) }
        }

        func onComplete(_ callback: @escaping (Error?) -> Void) {
            lock.lock()
            if done {
                let failure = error
                lock.unlock()
                callback(failure)
            } else {
                callbacks.append(callback)
                lock.unlock()
            }
        }

        /// Returns the failure, or nil on success. Times out as a `TimeoutError`.
        func await(timeoutMs: Int64) -> Error? {
            if group.wait(timeout: .now() + .milliseconds(Int(max(1, timeoutMs)))) == .timedOut {
                return TimeoutError(message: "timeout waiting for session")
            }
            lock.lock()
            defer { lock.unlock() }
            return error
        }
    }
}
