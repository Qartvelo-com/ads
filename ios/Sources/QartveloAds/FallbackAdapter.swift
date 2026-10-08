import UIKit

/// Seam between `QartveloAds` and an optional fallback network (the `QartveloAdsAdMob` module).
///
/// Threading: the SDK calls every method on the main thread. Implementations may call back on any
/// thread; the SDK moves callbacks to the main thread and ignores duplicates.
///
/// Ads are keyed by Qartvelo Ads placement id, so two placements may share one network ad unit.
/// When `settings.testMode` is true the adapter must use the network's public test ad units instead
/// of the supplied ids (an empty `adUnitId` is only ever passed in test mode).
///
/// An adapter found automatically must be an `NSObject` subclass with a plain `init()`, registered
/// with the Objective-C name `QartveloAdMobFallbackAdapter`; any adapter can also be registered with
/// `QartveloAds.registerFallbackAdapter(_:)`.
public protocol QartveloFallbackAdapter: AnyObject {
    /// Short network name used in logs, for example `admob`.
    var networkName: String { get }

    /// Called once after initialization starts. Must be cheap.
    func initialize(settings: QartveloFallbackSettings)

    /// Called when test mode or privacy signals change.
    func updateSettings(_ settings: QartveloFallbackSettings)

    func loadInterstitial(placementId: String, adUnitId: String, callback: QartveloFallbackLoadCallback)
    func isInterstitialReady(placementId: String) -> Bool
    func showInterstitial(placementId: String, from viewController: UIViewController, callback: QartveloFallbackShowCallback)

    func loadRewarded(placementId: String, adUnitId: String, callback: QartveloFallbackLoadCallback)
    func isRewardedReady(placementId: String) -> Bool
    func showRewarded(placementId: String, from viewController: UIViewController, callback: QartveloFallbackShowCallback)

    /// Creates and starts loading a banner sized for `width` points.
    func createBanner(
        placementId: String,
        adUnitId: String,
        width: CGFloat,
        rootViewController: UIViewController?,
        callback: QartveloFallbackBannerCallback
    ) -> QartveloFallbackBanner
}

public struct QartveloFallbackSettings: Equatable {
    public let testMode: Bool
    public let privacy: QartveloAdsPrivacy

    public init(testMode: Bool, privacy: QartveloAdsPrivacy) {
        self.testMode = testMode
        self.privacy = privacy
    }
}

public protocol QartveloFallbackLoadCallback: AnyObject {
    func onLoaded()
    func onFailed(_ message: String)
}

public protocol QartveloFallbackShowCallback: AnyObject {
    func onShown()
    func onImpression()
    func onClicked()
    /// Only after the network confirmed the reward was earned.
    func onReward(type: String, amount: Int)
    func onDismissed()
    func onShowFailed(_ message: String)
}

public protocol QartveloFallbackBannerCallback: AnyObject {
    func onLoaded()
    func onFailed(_ message: String)
    func onImpression()
    func onClicked()
}

/// A fallback banner owned by the SDK's per-placement banner controller.
public protocol QartveloFallbackBanner: AnyObject {
    var view: UIView { get }
    /// Called when the hosting view controller changes (for click-through presentation).
    func setRootViewController(_ viewController: UIViewController?)
    func destroy()
}
