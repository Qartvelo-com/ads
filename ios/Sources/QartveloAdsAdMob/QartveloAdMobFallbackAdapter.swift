import GoogleMobileAds
import QartveloAds
import UIKit

/// Google Mobile Ads implementation of the Qartvelo Ads fallback seam.
///
/// - Uses the app's own AdMob app ID (`GADApplicationIdentifier` in Info.plist) and ad unit ids;
///   Qartvelo Ads never proxies revenue.
/// - Test mode swaps every unit for Google's public test units.
/// - Consent: the app's own Google Mobile Ads/UMP configuration is left untouched. Only explicit
///   Qartvelo Ads privacy signals are forwarded (child-directed and under-age-of-consent treatment,
///   and non-personalized ads when the app reports that consent was refused). Nothing here grants
///   consent.
///
/// Register it before initializing: `QartveloAds.registerFallbackAdapter(QartveloAdMobFallbackAdapter())`.
/// When it is linked with `-ObjC` (CocoaPods does this) the SDK also finds it on its own.
@objc(QartveloAdMobFallbackAdapter)
public final class QartveloAdMobFallbackAdapter: NSObject, QartveloFallbackAdapter {
    /// AdMob full-screen ads expire one hour after loading; stop offering them a little earlier.
    static let adMaxAge: TimeInterval = 55 * 60

    public let networkName = "admob"

    private var settings = QartveloFallbackSettings(testMode: false, privacy: QartveloAdsPrivacy())
    private var interstitials: [String: Loaded<InterstitialAd>] = [:]
    private var rewardeds: [String: Loaded<RewardedAd>] = [:]
    /// Keeps each show's delegate alive until the ad is dismissed.
    private var showRelays: [ObjectIdentifier: ShowRelay] = [:]
    private static var started = false

    private struct Loaded<T> {
        let ad: T
        let loadedAt = Date()

        var isFresh: Bool { Date().timeIntervalSince(loadedAt) < QartveloAdMobFallbackAdapter.adMaxAge }
    }

    @objc public override init() {
        super.init()
    }

    public func initialize(settings: QartveloFallbackSettings) {
        self.settings = settings
        applyRequestConfiguration(settings.privacy)
        guard !Self.started else { return }
        Self.started = true
        MobileAds.shared.start(completionHandler: nil)
    }

    public func updateSettings(_ settings: QartveloFallbackSettings) {
        self.settings = settings
        applyRequestConfiguration(settings.privacy)
    }

    // MARK: - Interstitial

    public func loadInterstitial(placementId: String, adUnitId: String, callback: QartveloFallbackLoadCallback) {
        guard let unit = AdMobUnits.resolve(.interstitial, configured: adUnitId, testMode: settings.testMode) else {
            callback.onFailed("no AdMob interstitial unit configured")
            return
        }
        InterstitialAd.load(with: unit, request: buildRequest()) { [weak self] ad, error in
            guard let ad = ad else {
                callback.onFailed(Self.describe(error))
                return
            }
            self?.interstitials[placementId] = Loaded(ad: ad)
            callback.onLoaded()
        }
    }

    public func isInterstitialReady(placementId: String) -> Bool {
        fresh(&interstitials, placementId) != nil
    }

    public func showInterstitial(placementId: String, from viewController: UIViewController, callback: QartveloFallbackShowCallback) {
        guard let ad = interstitials.removeValue(forKey: placementId), ad.isFresh else {
            callback.onShowFailed("no AdMob interstitial loaded")
            return
        }
        ad.ad.fullScreenContentDelegate = relay(for: ad.ad, callback)
        ad.ad.present(from: viewController)
    }

    // MARK: - Rewarded

    public func loadRewarded(placementId: String, adUnitId: String, callback: QartveloFallbackLoadCallback) {
        guard let unit = AdMobUnits.resolve(.rewarded, configured: adUnitId, testMode: settings.testMode) else {
            callback.onFailed("no AdMob rewarded unit configured")
            return
        }
        RewardedAd.load(with: unit, request: buildRequest()) { [weak self] ad, error in
            guard let ad = ad else {
                callback.onFailed(Self.describe(error))
                return
            }
            self?.rewardeds[placementId] = Loaded(ad: ad)
            callback.onLoaded()
        }
    }

    public func isRewardedReady(placementId: String) -> Bool {
        fresh(&rewardeds, placementId) != nil
    }

    public func showRewarded(placementId: String, from viewController: UIViewController, callback: QartveloFallbackShowCallback) {
        guard let loaded = rewardeds.removeValue(forKey: placementId), loaded.isFresh else {
            callback.onShowFailed("no AdMob rewarded ad loaded")
            return
        }
        let ad = loaded.ad
        ad.fullScreenContentDelegate = relay(for: ad, callback)
        var rewarded = false
        // Google only calls this after the user earned the reward; the core SDK still guards "exactly once".
        ad.present(from: viewController) { [weak ad] in
            guard !rewarded else { return }
            rewarded = true
            let reward = ad?.adReward
            callback.onReward(type: reward?.type ?? "reward", amount: reward?.amount.intValue ?? 1)
        }
    }

    // MARK: - Banner

    public func adaptiveBannerSize(width: CGFloat) -> CGSize {
        currentOrientationAnchoredAdaptiveBanner(width: width).size
    }

    public func createBanner(
        placementId: String,
        adUnitId: String,
        width: CGFloat,
        rootViewController: UIViewController?,
        callback: QartveloFallbackBannerCallback
    ) -> QartveloFallbackBanner {
        makeBanner(adSize: currentOrientationAnchoredAdaptiveBanner(width: width), adUnitId: adUnitId, rootViewController: rootViewController, callback: callback)
    }

    /// Google's inline adaptive banner: `width` wide, as tall as Google decides up to `maxHeight`.
    public func createInlineBanner(
        placementId: String,
        adUnitId: String,
        width: CGFloat,
        maxHeight: CGFloat,
        rootViewController: UIViewController?,
        callback: QartveloFallbackBannerCallback
    ) -> QartveloFallbackBanner? {
        makeBanner(adSize: inlineAdaptiveBanner(width: width, maxHeight: maxHeight), adUnitId: adUnitId, rootViewController: rootViewController, callback: callback)
    }

    private func makeBanner(
        adSize: AdSize,
        adUnitId: String,
        rootViewController: UIViewController?,
        callback: QartveloFallbackBannerCallback
    ) -> QartveloFallbackBanner {
        let bannerView = BannerView(adSize: adSize)
        let banner = AdMobBanner(bannerView: bannerView, callback: callback)
        guard let unit = AdMobUnits.resolve(.banner, configured: adUnitId, testMode: settings.testMode) else {
            callback.onFailed("no AdMob banner unit configured")
            return banner
        }
        bannerView.adUnitID = unit
        bannerView.rootViewController = rootViewController
        bannerView.load(buildRequest())
        return banner
    }

    // MARK: - Helpers

    private func relay(for ad: AnyObject, _ callback: QartveloFallbackShowCallback) -> ShowRelay {
        let key = ObjectIdentifier(ad)
        let relay = ShowRelay(callback: callback) { [weak self] in self?.showRelays.removeValue(forKey: key) }
        showRelays[key] = relay
        return relay
    }

    private func fresh<T>(_ store: inout [String: Loaded<T>], _ key: String) -> Loaded<T>? {
        guard let entry = store[key] else { return nil }
        if entry.isFresh { return entry }
        store.removeValue(forKey: key)
        return nil
    }

    private func buildRequest() -> Request {
        AdMobPrivacy.buildRequest(settings.privacy)
    }

    private func applyRequestConfiguration(_ privacy: QartveloAdsPrivacy) {
        AdMobPrivacy.apply(privacy, to: MobileAds.shared.requestConfiguration)
    }

    static func describe(_ error: Error?) -> String {
        guard let error = error as NSError? else { return "AdMob: no ad" }
        return "AdMob \(error.code): \(error.localizedDescription)"
    }
}

/// Relays Google's full-screen callbacks to the Qartvelo Ads show callback.
private final class ShowRelay: NSObject, FullScreenContentDelegate {
    private let callback: QartveloFallbackShowCallback
    private let onFinished: () -> Void

    init(callback: QartveloFallbackShowCallback, onFinished: @escaping () -> Void) {
        self.callback = callback
        self.onFinished = onFinished
    }

    func adWillPresentFullScreenContent(_ ad: FullScreenPresentingAd) {
        callback.onShown()
    }

    func adDidRecordImpression(_ ad: FullScreenPresentingAd) {
        callback.onImpression()
    }

    func adDidRecordClick(_ ad: FullScreenPresentingAd) {
        callback.onClicked()
    }

    func adDidDismissFullScreenContent(_ ad: FullScreenPresentingAd) {
        callback.onDismissed()
        onFinished()
    }

    func ad(_ ad: FullScreenPresentingAd, didFailToPresentFullScreenContentWithError error: Error) {
        callback.onShowFailed(QartveloAdMobFallbackAdapter.describe(error))
        onFinished()
    }
}

/// An AdMob banner owned by the core SDK's banner controller.
private final class AdMobBanner: NSObject, QartveloFallbackBanner, BannerViewDelegate {
    private let bannerView: BannerView
    private let callback: QartveloFallbackBannerCallback

    init(bannerView: BannerView, callback: QartveloFallbackBannerCallback) {
        self.bannerView = bannerView
        self.callback = callback
        super.init()
        bannerView.delegate = self
    }

    var view: UIView { bannerView }

    func setRootViewController(_ viewController: UIViewController?) {
        bannerView.rootViewController = viewController
    }

    func destroy() {
        bannerView.delegate = nil
        bannerView.removeFromSuperview()
    }

    func bannerViewDidReceiveAd(_ bannerView: BannerView) {
        callback.onLoaded()
        // A refreshed inline adaptive ad may be another height: let the slot re-read the size.
        bannerView.invalidateIntrinsicContentSize()
        bannerView.superview?.setNeedsLayout()
    }

    func bannerView(_ bannerView: BannerView, didFailToReceiveAdWithError error: Error) {
        callback.onFailed(QartveloAdMobFallbackAdapter.describe(error))
    }

    func bannerViewDidRecordImpression(_ bannerView: BannerView) {
        callback.onImpression()
    }

    func bannerViewDidRecordClick(_ bannerView: BannerView) {
        callback.onClicked()
    }
}

/// Google's public test ad units, substituted for every placement in test mode.
enum AdMobUnits {
    enum Format {
        case banner, interstitial, rewarded
    }

    static let testBanner = "ca-app-pub-3940256099942544/2435281174"
    static let testInterstitial = "ca-app-pub-3940256099942544/4411468910"
    static let testRewarded = "ca-app-pub-3940256099942544/1712485313"

    /// The unit to request, or nil when nothing usable is configured outside test mode.
    static func resolve(_ format: Format, configured: String, testMode: Bool) -> String? {
        if testMode {
            switch format {
            case .banner: return testBanner
            case .interstitial: return testInterstitial
            case .rewarded: return testRewarded
            }
        }
        let trimmed = configured.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }
}

/// Maps Qartvelo Ads privacy signals onto Google Mobile Ads. Signals only ever add restrictions;
/// anything left unspecified (nil or false) keeps the app's own configuration untouched.
enum AdMobPrivacy {
    static func apply(_ privacy: QartveloAdsPrivacy, to configuration: RequestConfiguration) {
        if privacy.childDirected == true {
            configuration.tagForChildDirectedTreatment = NSNumber(value: true)
        }
        if privacy.underAgeOfConsent == true {
            configuration.tagForUnderAgeOfConsent = NSNumber(value: true)
        }
    }

    /// `npa=1` requests non-personalized ads when the app reports that consent was refused.
    static func requestParameters(_ privacy: QartveloAdsPrivacy) -> [String: String]? {
        privacy.consentGiven == false ? ["npa": "1"] : nil
    }

    static func buildRequest(_ privacy: QartveloAdsPrivacy) -> Request {
        let request = Request()
        if let parameters = requestParameters(privacy) {
            let extras = Extras()
            extras.additionalParameters = parameters
            request.register(extras)
        }
        return request
    }
}
