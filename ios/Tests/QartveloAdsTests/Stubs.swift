@testable import QartveloAds
import UIKit

/// Answers SDK requests from a per-path table.
final class StubURLProtocol: URLProtocol {
    struct Response {
        let status: Int
        let body: Data
        let contentType: String
    }

    private static let lock = NSLock()
    private static var routes: [String: Response] = [:]
    private static var recorded: [(path: String, body: JSON)] = []
    private static var gates: [String: DispatchSemaphore] = [:]

    static func reset() {
        lock.lock()
        routes = [:]
        recorded = []
        let held = gates.values
        gates = [:]
        lock.unlock()
        held.forEach { $0.signal() }
    }

    /// Holds the answer to every request for `path` until `release(_:)`, so a test can act while the
    /// request is in flight.
    static func hold(_ path: String) {
        lock.lock()
        gates[path] = DispatchSemaphore(value: 0)
        lock.unlock()
    }

    static func release(_ path: String) {
        lock.lock()
        let gate = gates.removeValue(forKey: path)
        lock.unlock()
        gate?.signal()
    }

    static func route(_ path: String, status: Int = 200, json: JSON) {
        route(path, status: status, body: try! JSONSerialization.data(withJSONObject: json), contentType: "application/json")
    }

    static func route(_ path: String, status: Int = 200, body: Data, contentType: String) {
        lock.lock()
        routes[path] = Response(status: status, body: body, contentType: contentType)
        lock.unlock()
    }

    static func requests(_ path: String) -> [JSON] {
        lock.lock()
        defer { lock.unlock() }
        return recorded.filter { $0.path == path }.map { $0.body }
    }

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        let path = request.url?.path ?? ""
        let body = Self.bodyData(of: request).flatMap { (try? JSONSerialization.jsonObject(with: $0)) as? JSON } ?? [:]
        Self.lock.lock()
        Self.recorded.append((path, body))
        let response = Self.routes[path] ?? Response(status: 404, body: Data("{}".utf8), contentType: "application/json")
        let gate = Self.gates[path]
        Self.lock.unlock()
        if let gate = gate {
            // Never hang a test process on a gate nobody released.
            _ = gate.wait(timeout: .now() + 10)
            gate.signal()
        }
        let http = HTTPURLResponse(
            url: request.url!,
            statusCode: response.status,
            httpVersion: "HTTP/1.1",
            headerFields: ["Content-Type": response.contentType]
        )!
        client?.urlProtocol(self, didReceive: http, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: response.body)
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}

    /// URLSession moves bodies into a stream before protocols see them.
    private static func bodyData(of request: URLRequest) -> Data? {
        if let body = request.httpBody { return body }
        guard let stream = request.httpBodyStream else { return nil }
        stream.open()
        defer { stream.close() }
        var data = Data()
        var buffer = [UInt8](repeating: 0, count: 4096)
        while stream.hasBytesAvailable {
            let read = stream.read(&buffer, maxLength: buffer.count)
            if read <= 0 { break }
            data.append(buffer, count: read)
        }
        return data
    }
}

/// Records every delegate callback in order.
final class RecordingDelegate: NSObject, QartveloAdsDelegate {
    var events: [String] = []
    var loaded: [QartveloAdsAdInfo] = []
    var errors: [QartveloAdsError] = []
    var onEvent: ((String) -> Void)?

    private func record(_ event: String) {
        events.append(event)
        onEvent?(event)
    }

    func qartveloAdDidLoad(_ info: QartveloAdsAdInfo) {
        loaded.append(info)
        record("loaded:\(info.source)")
    }

    func qartveloAdDidFailToLoad(placementId: String, error: QartveloAdsError) {
        errors.append(error)
        record("failed:\(error.code)")
    }

    func qartveloAdDidStartFallback(placementId: String, format: QartveloAdFormat, reason: String) {
        record("fallback:\(reason)")
    }

    func qartveloAdNoAdAvailable(placementId: String, format: QartveloAdFormat) {
        record("noAd")
    }

    var setupIssues: [QartveloAdsSetupIssue] = []

    func qartveloAdsDidReportSetupIssue(_ issue: QartveloAdsSetupIssue) {
        setupIssues.append(issue)
        record("setupIssue:\(issue.code):\(issue.placementId ?? "")")
    }
}

/// A fallback network that loads instantly.
final class StubAdapter: QartveloFallbackAdapter {
    let networkName = "stub"
    var loadedInterstitials: Set<String> = []
    var preferredBannerHeight: CGFloat = 0
    func adaptiveBannerSize(width: CGFloat) -> CGSize { CGSize(width: width, height: preferredBannerHeight) }

    func initialize(settings: QartveloFallbackSettings) {}
    func updateSettings(_ settings: QartveloFallbackSettings) {}

    func loadInterstitial(placementId: String, adUnitId: String, callback: QartveloFallbackLoadCallback) {
        loadedInterstitials.insert(placementId)
        callback.onLoaded()
    }

    func isInterstitialReady(placementId: String) -> Bool { loadedInterstitials.contains(placementId) }

    func showInterstitial(placementId: String, from viewController: UIViewController, callback: QartveloFallbackShowCallback) {
        callback.onShowFailed("not implemented")
    }

    func loadRewarded(placementId: String, adUnitId: String, callback: QartveloFallbackLoadCallback) {
        callback.onFailed("not implemented")
    }

    func isRewardedReady(placementId: String) -> Bool { false }

    func showRewarded(placementId: String, from viewController: UIViewController, callback: QartveloFallbackShowCallback) {
        callback.onShowFailed("not implemented")
    }

    func createBanner(
        placementId: String, adUnitId: String, width: CGFloat, rootViewController: UIViewController?,
        callback: QartveloFallbackBannerCallback
    ) -> QartveloFallbackBanner {
        callback.onFailed("not implemented")
        return StubBanner()
    }
}

final class StubBanner: QartveloFallbackBanner {
    let view = UIView()
    func setRootViewController(_ viewController: UIViewController?) {}
    func destroy() {}
}
