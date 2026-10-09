import Foundation

/// A setup problem the app developer has to fix, such as an app key registered for another bundle ID
/// or a placement code missing from the dashboard. Reported once per process through
/// `QartveloAdsDelegate.qartveloAdsDidReportSetupIssue(_:)` (global observers) and logged as an error.
@objc public final class QartveloAdsSetupIssue: NSObject {
    @objc public static let packageMismatch = "package_mismatch"
    @objc public static let platformMismatch = "platform_mismatch"
    @objc public static let unknownPlacement = "unknown_placement"
    @objc public static let formatMismatch = "format_mismatch"

    /// One of the constants above.
    @objc public let code: String
    /// What is wrong and how to fix it, in English.
    @objc public let message: String
    /// The placement concerned, or nil for app-level issues.
    @objc public let placementId: String?

    init(code: String, message: String, placementId: String?) {
        self.code = code
        self.message = message
        self.placementId = placementId
    }
}
