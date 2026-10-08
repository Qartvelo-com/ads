import Foundation

enum TrackedEventType: String {
    case impression, click, reward

    var path: String { "api/v1/events/\(rawValue)" }
}

/// Serial background delivery of impression/click/reward events. A single serial queue guarantees an
/// impression is delivered (or definitively rejected) before a later click for the same ad.
/// Transient failures (network, 5xx, 408, 429) are retried with exponential backoff; the queue only
/// ever sleeps on its own thread, so the UI is never blocked.
final class EventQueue {
    static let maxAttempts = 4
    static let defaultRetryBaseMs: Int64 = 1_000
    static let timeoutMs: Int64 = 10_000

    private let api: ApiClient
    private let retryBaseMs: Int64
    private let queue = DispatchQueue(label: "com.qartvelo.ads.events")

    init(api: ApiClient, retryBaseMs: Int64 = TestHooks.eventRetryBaseMs ?? EventQueue.defaultRetryBaseMs) {
        self.api = api
        self.retryBaseMs = retryBaseMs
    }

    func enqueue(_ type: TrackedEventType, ad: ServedAd, completion: Bool? = nil) {
        var body: JSON = ["request_id": ad.requestId, "impression_token": ad.impressionToken]
        if let completion = completion { body["completion"] = completion }
        let label = "\(type.rawValue) for \(ad.requestId)"
        queue.async { [self] in deliver(type, body: body, label: label) }
    }

    /// Tests only: waits until every queued event was handled.
    func drain() {
        queue.sync {}
    }

    private func deliver(_ type: TrackedEventType, body: JSON, label: String) {
        for attempt in 0..<Self.maxAttempts {
            let retry: Bool
            do {
                let code = try api.postEvent(type.path, body: body, timeoutMs: Self.timeoutMs)
                if (200..<300).contains(code) {
                    Log.d("Event \(label) accepted")
                    return
                } else if code == 408 || code == 429 || code >= 500 {
                    retry = true
                } else {
                    // 409 duplicate / 422 invalid or expired: retrying cannot help.
                    Log.i("Event \(label) rejected with HTTP \(code)")
                    return
                }
            } catch {
                guard error is TimeoutError || error is NetworkError else {
                    Log.e("Event \(label) failed unexpectedly: \(error)")
                    return
                }
                Log.i("Event \(label) failed, will retry")
                retry = true
            }
            if retry && attempt < Self.maxAttempts - 1 {
                Thread.sleep(forTimeInterval: Double(retryBaseMs << Int64(attempt)) / 1000)
            }
        }
        Log.e("Event \(label) dropped after \(Self.maxAttempts) attempts")
    }
}
