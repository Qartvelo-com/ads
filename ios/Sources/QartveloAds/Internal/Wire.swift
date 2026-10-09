import Foundation

typealias JSON = [String: Any]

extension Dictionary where Key == String, Value == Any {
    func string(_ key: String) -> String? {
        guard let value = self[key] as? String, !value.isEmpty else { return nil }
        return value
    }

    func bool(_ key: String, default fallback: Bool) -> Bool {
        if let value = self[key] as? Bool { return value }
        if let value = self[key] as? NSNumber { return value.boolValue }
        return fallback
    }

    func positiveInt64(_ key: String) -> Int64? {
        // JSON booleans arrive as NSNumber too; `is Bool` would also match 0 and 1, so check the CF type.
        guard let value = self[key] as? NSNumber, CFGetTypeID(value) != CFBooleanGetTypeID() else { return nil }
        let number = value.int64Value
        return number > 0 ? number : nil
    }

    func int(_ key: String) -> Int {
        (self[key] as? NSNumber)?.intValue ?? 0
    }

    func object(_ key: String) -> JSON? {
        self[key] as? JSON
    }

    func array(_ key: String) -> [Any]? {
        self[key] as? [Any]
    }
}

/// Per-placement remote configuration returned by `/sdk/initialize`.
struct PlacementConfig {
    let code: String
    let format: QartveloAdFormat?
    let qartveloEnabled: Bool
    let fallbackProvider: String
    let admobAdUnitId: String?
    let requestTimeoutMs: Int64?
    let bannerRefreshSeconds: Int
}

struct RemoteConfig {
    static let minBannerRefreshSeconds = 30
    static let defaultBannerRefreshSeconds = 60

    let servingEnabled: Bool
    let requestTimeoutMs: Int64?
    let fallbackEnabled: Bool
    let configTtlSeconds: Int64
    let placements: [String: PlacementConfig]

    /// Parses the `config` + `placements` members of an initialize response (or its cached copy).
    static func parse(_ root: JSON) -> RemoteConfig {
        let config = root.object("config") ?? [:]
        var placements: [String: PlacementConfig] = [:]
        for item in root.array("placements") ?? [] {
            guard let placement = item as? JSON, let code = placement.string("code") else { continue }
            let refresh = placement.positiveInt64("banner_refresh_seconds").map(Int.init) ?? defaultBannerRefreshSeconds
            placements[code] = PlacementConfig(
                code: code,
                format: placement.string("format").flatMap(QartveloAdFormat.init(wireName:)),
                qartveloEnabled: placement.bool("ourads_enabled", default: true),
                fallbackProvider: placement.string("fallback_provider") ?? "admob",
                admobAdUnitId: placement.string("admob_ad_unit_id"),
                requestTimeoutMs: placement.positiveInt64("request_timeout_ms"),
                bannerRefreshSeconds: max(refresh, minBannerRefreshSeconds)
            )
        }
        return RemoteConfig(
            servingEnabled: config.bool("serving_enabled", default: true),
            requestTimeoutMs: config.positiveInt64("request_timeout_ms"),
            fallbackEnabled: config.bool("fallback_enabled", default: true),
            configTtlSeconds: config.positiveInt64("config_ttl_seconds") ?? 3600,
            placements: placements
        )
    }
}

/// Ephemeral session issued by the backend. In memory only; never persisted or logged.
struct Session {
    let token: String
    let expiresAt: Int64
}

struct InitResult {
    let session: Session
    let config: RemoteConfig
    let cacheableJSON: Data
}

enum CreativeType {
    case image, video
}

/// An ad returned by `/ads/request`. Expiry is converted to the monotonic clock.
final class ServedAd {
    /// Margin for presentation and event delivery: an ad is only showable while its signed token is
    /// still comfortably valid for the impression event.
    static let expiryMarginMs: Int64 = 5_000

    let requestId: String
    let adId: String
    let campaignId: String?
    let creativeId: String?
    let format: QartveloAdFormat
    let creativeType: CreativeType
    let creativeURL: URL
    let clickURL: String?
    let width: Int
    let height: Int
    let durationSeconds: Int?
    let impressionToken: String
    let expiresAt: Int64
    let test: Bool
    let localTest: Bool

    private let lock = NSLock()
    private var storedFile: URL?

    /// Local copy of the creative, set once the download succeeded.
    var file: URL? {
        get { lock.lock(); defer { lock.unlock() }; return storedFile }
        set { lock.lock(); storedFile = newValue; lock.unlock() }
    }

    init(
        requestId: String, adId: String, campaignId: String?, creativeId: String?, format: QartveloAdFormat,
        creativeType: CreativeType, creativeURL: URL, clickURL: String?, width: Int, height: Int,
        durationSeconds: Int?, impressionToken: String, expiresAt: Int64, test: Bool, localTest: Bool = false
    ) {
        self.requestId = requestId
        self.adId = adId
        self.campaignId = campaignId
        self.creativeId = creativeId
        self.format = format
        self.creativeType = creativeType
        self.creativeURL = creativeURL
        self.clickURL = clickURL
        self.width = width
        self.height = height
        self.durationSeconds = durationSeconds
        self.impressionToken = impressionToken
        self.expiresAt = expiresAt
        self.test = test
        self.localTest = localTest
    }

    func isValid(now: Int64 = Clock.now()) -> Bool {
        guard now < expiresAt - ServedAd.expiryMarginMs, let file = file else { return false }
        return FileManager.default.fileExists(atPath: file.path)
    }
}

enum AdResponse {
    case fill(ServedAd)
    case noFill(requestId: String?, fallback: String?, reason: String?)
}

/// Non-2xx API answer with the contract's error envelope.
struct ApiError: Error {
    let httpStatus: Int
    let code: String
    let message: String
    /// The envelope's optional `error.details` object.
    var details: JSON? = nil

    var isSessionError: Bool { code == "session_expired" || code == "invalid_session" }
}

/// The request did not finish within its budget (or was cancelled by it).
struct TimeoutError: Error {
    let message: String
}

/// Network or protocol failure.
struct NetworkError: Error {
    let message: String
}

enum WireDates {
    private static let withFraction: ISO8601DateFormatter = {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return formatter
    }()

    private static let plain: ISO8601DateFormatter = {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime]
        return formatter
    }()

    private static let http: DateFormatter = {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = TimeZone(identifier: "GMT")
        formatter.dateFormat = "EEE, dd MMM yyyy HH:mm:ss zzz"
        return formatter
    }()

    private static let lock = NSLock()

    /// ISO 8601 instant in epoch milliseconds.
    static func isoMillis(_ value: String?) -> Int64? {
        guard let value = value, !value.isEmpty else { return nil }
        lock.lock()
        defer { lock.unlock() }
        guard let date = plain.date(from: value) ?? withFraction.date(from: value) else { return nil }
        return Int64(date.timeIntervalSince1970 * 1000)
    }

    /// HTTP `Date` header in epoch milliseconds.
    static func httpMillis(_ value: String?) -> Int64? {
        guard let value = value else { return nil }
        lock.lock()
        defer { lock.unlock() }
        return http.date(from: value).map { Int64($0.timeIntervalSince1970 * 1000) }
    }

    static func wallNowMillis() -> Int64 {
        Int64(Date().timeIntervalSince1970 * 1000)
    }
}

func isWebURL(_ value: String) -> Bool {
    guard let url = URL(string: value), let scheme = url.scheme?.lowercased(), url.host != nil else { return false }
    return scheme == "https" || scheme == "http"
}
