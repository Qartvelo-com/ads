import Foundation

/// Verbosity of the SDK's log output (subsystem `com.qartvelo.ads`). Tokens are never logged.
@objc public enum QartveloAdsLogLevel: Int {
    case none, error, info, debug
}

@objc public enum QartveloAdFormat: Int, CustomStringConvertible {
    case banner, interstitial, rewarded

    var wireName: String {
        switch self {
        case .banner: return "banner"
        case .interstitial: return "interstitial"
        case .rewarded: return "rewarded"
        }
    }

    init?(wireName: String) {
        switch wireName.lowercased() {
        case "banner": self = .banner
        case "interstitial": self = .interstitial
        case "rewarded": self = .rewarded
        default: return nil
        }
    }

    public var description: String { wireName }
}

/// Which network delivered the ad that was loaded or shown.
@objc public enum QartveloAdSource: Int, CustomStringConvertible {
    case qartvelo, admob

    public var description: String { self == .qartvelo ? "QARTVELO" : "ADMOB" }
}

@objc public final class QartveloAdsAdInfo: NSObject {
    @objc public let placementId: String
    @objc public let format: QartveloAdFormat
    @objc public let source: QartveloAdSource
    @objc public let campaignId: String?
    @objc public let creativeId: String?

    init(placementId: String, format: QartveloAdFormat, source: QartveloAdSource, campaignId: String? = nil, creativeId: String? = nil) {
        self.placementId = placementId
        self.format = format
        self.source = source
        self.campaignId = campaignId
        self.creativeId = creativeId
    }

    public override var description: String {
        "QartveloAdsAdInfo(placementId: \(placementId), format: \(format), source: \(source))"
    }
}

@objc public final class QartveloAdsReward: NSObject {
    @objc public let type: String
    @objc public let amount: Int

    @objc public init(type: String = "reward", amount: Int = 1) {
        self.type = type
        self.amount = amount
    }
}

@objc public enum QartveloAdsErrorCode: Int, CustomStringConvertible {
    case notInitialized
    case invalidPlacement
    case networkError
    case timeout
    case noFill
    case creativeFailed
    case adExpired
    case showFailed
    case alreadyShowing
    case internalError

    /// Same names as the Android SDK and the React Native plugin.
    public var description: String {
        switch self {
        case .notInitialized: return "NOT_INITIALIZED"
        case .invalidPlacement: return "INVALID_PLACEMENT"
        case .networkError: return "NETWORK_ERROR"
        case .timeout: return "TIMEOUT"
        case .noFill: return "NO_FILL"
        case .creativeFailed: return "CREATIVE_FAILED"
        case .adExpired: return "AD_EXPIRED"
        case .showFailed: return "SHOW_FAILED"
        case .alreadyShowing: return "ALREADY_SHOWING"
        case .internalError: return "INTERNAL_ERROR"
        }
    }
}

@objc public final class QartveloAdsError: NSObject, LocalizedError {
    @objc public let code: QartveloAdsErrorCode
    @objc public let message: String

    init(_ code: QartveloAdsErrorCode, _ message: String) {
        self.code = code
        self.message = message
    }

    public var errorDescription: String? { "\(code): \(message)" }
    public override var description: String { "\(code): \(message)" }
}

/// Privacy signals supplied by the host app. `nil` means unknown: the SDK never assumes consent
/// and never collects consent on the app's behalf. Signals are forwarded to the fallback adapter.
public struct QartveloAdsPrivacy: Equatable {
    public var consentGiven: Bool?
    public var childDirected: Bool?
    public var underAgeOfConsent: Bool?

    public init(consentGiven: Bool? = nil, childDirected: Bool? = nil, underAgeOfConsent: Bool? = nil) {
        self.consentGiven = consentGiven
        self.childDirected = childDirected
        self.underAgeOfConsent = underAgeOfConsent
    }
}

/// Production API endpoint.
public let qartveloAdsDefaultBaseURL = URL(string: "https://ads.qartvelo.com/")!

/// Options for `QartveloAds.initialize`. Set them before initializing; later changes are ignored.
@objc public final class QartveloAdsOptions: NSObject {
    /// Allow the optional `QartveloAdsAdMob` adapter to serve the app's own AdMob units.
    @objc public var admobFallback: Bool = true
    /// Qartvelo Ads request budget in milliseconds before falling back. A per-placement server value wins.
    @objc public var requestTimeoutMs: Int = 800
    /// Never serve billable campaigns: Qartvelo Ads uses public server test creatives if the backend
    /// cannot serve a test ad, and AdMob uses Google's test units. Always on in the Simulator and
    /// every installation outside the App Store, even when this option is false.
    @objc public var testMode: Bool = false
    /// Retained for source compatibility. All non-App Store iOS installations always use test mode.
    @available(*, deprecated, message: "Non-App Store iOS installations always use test mode; this option is ignored.")
    @objc public var testModeInDebugBuilds: Bool = true
    /// In test mode, force Qartvelo Ads to answer "no fill" so the fallback path can be exercised.
    @objc public var testForceNoFill: Bool = false
    @objc public var logLevel: QartveloAdsLogLevel = .error
    @objc public var baseURL: URL = qartveloAdsDefaultBaseURL
    /// Placement code -> the app's AdMob ad unit id. Wins over the server-side mapping.
    @objc public var admobAdUnits: [String: String] = [:]

    @objc public override init() {
        super.init()
    }
}
