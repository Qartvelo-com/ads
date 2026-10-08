import Foundation

/// Lets the main-thread timeout cancel an in-flight HTTP call.
final class CallTracker {
    private let lock = NSLock()
    private var task: URLSessionTask?
    private var isCancelled = false

    var cancelled: Bool {
        lock.lock()
        defer { lock.unlock() }
        return isCancelled
    }

    func track(_ task: URLSessionTask) {
        lock.lock()
        self.task = task
        let cancelNow = isCancelled
        lock.unlock()
        if cancelNow { task.cancel() }
    }

    func cancel() {
        lock.lock()
        isCancelled = true
        let current = task
        lock.unlock()
        current?.cancel()
    }
}

/// Blocking HTTP client for the Qartvelo Ads API. Never call it on the main thread.
final class ApiClient {
    private let base: URL?
    private let userAgent: String
    private let session: URLSession

    init(baseURL: URL, userAgent: String) {
        let text = baseURL.absoluteString
        base = URL(string: text.hasSuffix("/") ? text : text + "/").flatMap { url in
            let scheme = url.scheme?.lowercased()
            return (scheme == "https" || scheme == "http") && url.host != nil ? url : nil
        }
        self.userAgent = userAgent
        let configuration = URLSessionConfiguration.default
        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
        configuration.urlCache = nil
        configuration.timeoutIntervalForRequest = 15
        configuration.timeoutIntervalForResource = 60
        configuration.waitsForConnectivity = false
        configuration.httpAdditionalHeaders = ["User-Agent": userAgent]
        if !TestHooks.urlProtocols.isEmpty {
            configuration.protocolClasses = TestHooks.urlProtocols + (configuration.protocolClasses ?? [])
        }
        session = URLSession(configuration: configuration)
    }

    var isConfigured: Bool { base != nil }

    func initialize(body: JSON, timeoutMs: Int64, tracker: CallTracker? = nil) throws -> InitResult {
        let (json, serverNow) = try postJSON("api/v1/sdk/initialize", body: body, timeoutMs: timeoutMs, tracker: tracker)
        guard let token = json.string("session_token") else { throw NetworkError(message: "initialize: missing session_token") }
        let expiresAt = WireDates.isoMillis(json.string("session_expires_at")) ?? (serverNow + 3_600_000)
        // `test_mode` is only an echo of the request; it must never outlive this process (see Engine.testMode).
        var config = json.object("config") ?? [:]
        config.removeValue(forKey: "test_mode")
        let cacheable: JSON = ["config": config, "placements": json.array("placements") ?? []]
        let data = (try? JSONSerialization.data(withJSONObject: cacheable)) ?? Data()
        return InitResult(
            session: Session(token: token, expiresAt: Self.toMonotonic(expiresAt, serverNow: serverNow)),
            config: RemoteConfig.parse(cacheable),
            cacheableJSON: data
        )
    }

    func requestAd(body: JSON, timeoutMs: Int64, tracker: CallTracker?) throws -> AdResponse {
        let (json, serverNow) = try postJSON("api/v1/ads/request", body: body, timeoutMs: timeoutMs, tracker: tracker)
        switch json.string("status") {
        case "fill":
            return .fill(try parseAd(json, serverNow: serverNow))
        case "no_fill":
            return .noFill(requestId: json.string("request_id"), fallback: json.string("fallback"), reason: json.string("reason"))
        default:
            throw NetworkError(message: "ads/request: unexpected status")
        }
    }

    /// Posts an event and returns the HTTP status. Network failures throw.
    func postEvent(_ path: String, body: JSON, timeoutMs: Int64) throws -> Int {
        let request = try makeRequest(path, body: body, timeoutMs: timeoutMs)
        let (_, response) = try perform(request, tracker: nil)
        return response.statusCode
    }

    /// Downloads `url` into `destination` (via a temporary file), rejecting non-2xx answers and
    /// files larger than `maxBytes`.
    func download(_ url: URL, to destination: URL, maxBytes: Int64, timeoutMs: Int64, tracker: CallTracker?) throws {
        var request = URLRequest(url: url)
        request.timeoutInterval = Double(timeoutMs) / 1000
        let semaphore = DispatchSemaphore(value: 0)
        var failure: Error?
        let partial = destination.appendingPathExtension("part")
        let task = session.downloadTask(with: request) { location, response, error in
            defer { semaphore.signal() }
            if let error = error {
                failure = Self.map(error)
                return
            }
            guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode), let location = location else {
                failure = NetworkError(message: "creative HTTP \((response as? HTTPURLResponse)?.statusCode ?? 0)")
                return
            }
            do {
                let size = (try FileManager.default.attributesOfItem(atPath: location.path)[.size] as? NSNumber)?.int64Value ?? 0
                if size == 0 { throw NetworkError(message: "empty creative") }
                if size > maxBytes { throw NetworkError(message: "creative too large (\(size) bytes)") }
                try? FileManager.default.removeItem(at: partial)
                try FileManager.default.moveItem(at: location, to: partial)
                try? FileManager.default.removeItem(at: destination)
                try FileManager.default.moveItem(at: partial, to: destination)
            } catch {
                try? FileManager.default.removeItem(at: partial)
                failure = error
            }
        }
        tracker?.track(task)
        task.resume()
        if semaphore.wait(timeout: .now() + .milliseconds(Int(timeoutMs) + 1_000)) == .timedOut {
            task.cancel()
            throw TimeoutError(message: "creative download timed out")
        }
        if let failure = failure { throw failure }
    }

    // MARK: - Internals

    private func makeRequest(_ path: String, body: JSON, timeoutMs: Int64) throws -> URLRequest {
        guard let base = base, let url = URL(string: path, relativeTo: base) else {
            throw NetworkError(message: "invalid base url")
        }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.timeoutInterval = Double(timeoutMs) / 1000
        request.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.httpBody = try JSONSerialization.data(withJSONObject: body)
        return request
    }

    /// Runs `request` and waits for it, bounded by its own timeout (plus a small margin).
    private func perform(_ request: URLRequest, tracker: CallTracker?) throws -> (Data, HTTPURLResponse) {
        let semaphore = DispatchSemaphore(value: 0)
        var result: (Data?, URLResponse?, Error?) = (nil, nil, nil)
        let task = session.dataTask(with: request) { data, response, error in
            result = (data, response, error)
            semaphore.signal()
        }
        tracker?.track(task)
        task.resume()
        let budget = Int(request.timeoutInterval * 1000) + 500
        if semaphore.wait(timeout: .now() + .milliseconds(budget)) == .timedOut {
            task.cancel()
            throw TimeoutError(message: "request timed out")
        }
        if let error = result.2 { throw Self.map(error) }
        guard let http = result.1 as? HTTPURLResponse else { throw NetworkError(message: "no HTTP response") }
        return (result.0 ?? Data(), http)
    }

    private func postJSON(_ path: String, body: JSON, timeoutMs: Int64, tracker: CallTracker?) throws -> (JSON, Int64) {
        let request = try makeRequest(path, body: body, timeoutMs: timeoutMs)
        let started = Clock.now()
        let (data, response) = try perform(request, tracker: tracker)
        Log.d("POST /\(path) -> \(response.statusCode) in \(Clock.now() - started) ms")
        let json: JSON
        if data.isEmpty {
            json = [:]
        } else if let parsed = (try? JSONSerialization.jsonObject(with: data)) as? JSON {
            json = parsed
        } else {
            throw NetworkError(message: "\(path): malformed JSON (HTTP \(response.statusCode))")
        }
        guard (200..<300).contains(response.statusCode) else {
            let error = json.object("error")
            throw ApiError(
                httpStatus: response.statusCode,
                code: error?.string("code") ?? "http_\(response.statusCode)",
                message: error?.string("message") ?? "HTTP \(response.statusCode)"
            )
        }
        let serverNow = WireDates.httpMillis(response.value(forHTTPHeaderField: "Date")) ?? WireDates.wallNowMillis()
        return (json, serverNow)
    }

    private func parseAd(_ root: JSON, serverNow: Int64) throws -> ServedAd {
        guard let ad = root.object("ad") else { throw NetworkError(message: "fill without ad") }
        guard let creative = ad.string("creative_url"), isWebURL(creative), let creativeURL = URL(string: creative) else {
            throw NetworkError(message: "fill with invalid creative_url")
        }
        guard let token = ad.string("impression_token") else { throw NetworkError(message: "fill without impression_token") }
        guard let expiresAt = WireDates.isoMillis(ad.string("expires_at")) else { throw NetworkError(message: "fill without expires_at") }
        guard let requestId = root.string("request_id") else { throw NetworkError(message: "fill without request_id") }
        guard let format = ad.string("format").flatMap(QartveloAdFormat.init(wireName:)) else {
            throw NetworkError(message: "fill with unknown format")
        }
        let type: CreativeType
        switch ad.string("creative_type") {
        case "image": type = .image
        case "video": type = .video
        default: throw NetworkError(message: "unsupported creative_type")
        }
        return ServedAd(
            requestId: requestId,
            adId: ad.string("id") ?? "",
            campaignId: ad.string("campaign_id"),
            creativeId: ad.string("creative_id"),
            format: format,
            creativeType: type,
            creativeURL: creativeURL,
            clickURL: ad.string("click_url").flatMap { isWebURL($0) ? $0 : nil },
            width: ad.int("width"),
            height: ad.int("height"),
            durationSeconds: ad.positiveInt64("duration_seconds").map(Int.init),
            impressionToken: token,
            expiresAt: Self.toMonotonic(expiresAt, serverNow: serverNow),
            test: ad.bool("test", default: false)
        )
    }

    private static func map(_ error: Error) -> Error {
        let code = (error as NSError).code
        if code == NSURLErrorTimedOut || code == NSURLErrorCancelled {
            return TimeoutError(message: error.localizedDescription)
        }
        return NetworkError(message: error.localizedDescription)
    }

    /// Maps a server wall-clock instant onto the monotonic clock, cancelling out device clock skew.
    static func toMonotonic(_ epochMillis: Int64, serverNow: Int64) -> Int64 {
        Clock.now() + (epochMillis - serverNow)
    }
}
