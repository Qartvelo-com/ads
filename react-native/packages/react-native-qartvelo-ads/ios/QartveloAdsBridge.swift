import UIKit

private let initNotification = Notification.Name("QartveloAdsRNInitialized")
private var sdkStarted = false

private func adInfo(_ info: QartveloAdsAdInfo) -> [String: Any] {
    var result: [String: Any] = ["placementId": info.placementId, "format": info.format.description,
                               "source": info.source.description.lowercased()]
    result["campaignId"] = info.campaignId
    result["creativeId"] = info.creativeId
    return result
}

// Used for both the global event stream and per-call delegates. SDK callbacks are on main.
private final class AdRelay: NSObject, QartveloAdsDelegate {
    var event: (([String: Any]) -> Void)?
    var format: QartveloAdFormat?
    var reward: QartveloAdsReward?
    var source: String?
    func send(_ type: String, _ info: QartveloAdsAdInfo) {
        var data = adInfo(info); data["type"] = type
        event?(data)
    }
    func qartveloAdDidLoad(_ info: QartveloAdsAdInfo) { send("loaded", info) }
    func qartveloAdDidShow(_ info: QartveloAdsAdInfo) { source = info.source.description.lowercased(); send("shown", info) }
    func qartveloAdDidRecordImpression(_ info: QartveloAdsAdInfo) { send("impression", info) }
    func qartveloAdDidClick(_ info: QartveloAdsAdInfo) { send("clicked", info) }
    func qartveloAdDidDismiss(_ info: QartveloAdsAdInfo) { send("dismissed", info) }
    func qartveloAd(_ info: QartveloAdsAdInfo, didEarnReward reward: QartveloAdsReward) {
        if self.reward == nil { self.reward = reward }
        var data = adInfo(info); data["type"] = "rewarded"
        data["reward"] = ["type": reward.type, "amount": reward.amount]
        event?(data)
    }
    func qartveloAdDidFailToLoad(placementId: String, error: QartveloAdsError) {
        var data: [String: Any] = ["type": "loadFailed", "placementId": placementId,
                                   "error": ["code": error.code.description.lowercased(), "message": error.message]]
        data["format"] = format?.description
        event?(data)
    }
    func qartveloAdDidStartFallback(placementId: String, format: QartveloAdFormat, reason: String) {
        event?(["type": "fallbackStarted", "placementId": placementId, "format": format.description, "reason": reason])
    }
    func qartveloAdNoAdAvailable(placementId: String, format: QartveloAdFormat) {
        event?(["type": "noAdAvailable", "placementId": placementId, "format": format.description])
    }
    func qartveloAdsDidReportSetupIssue(_ issue: QartveloAdsSetupIssue) {
        event?(["type": "setupIssue", "placementId": issue.placementId ?? "",
                "error": ["code": issue.code, "message": issue.message]])
    }
}

@objc(QartveloAdsBridge)
public final class QartveloAdsBridge: NSObject {
    @objc public var onEvent: (([String: Any]) -> Void)?
    private let observer = AdRelay()
    private var calls: [UUID: AdRelay] = [:]
    private var initCalls: [UUID: ((Any?) -> Void, (String, String) -> Void)] = [:]
    private var formats: [String: QartveloAdFormat] = [:]
    private var active = true

    @objc public override init() {
        super.init()
        observer.event = { [weak self] data in
            guard let self = self, self.active else { return }
            var data = data
            if data["format"] == nil, let id = data["placementId"] as? String { data["format"] = self.formats[id]?.description }
            self.onEvent?(data)
        }
        DispatchQueue.main.async { [weak self] in
            if let self = self, self.active { QartveloAds.addObserver(self.observer) }
        }
    }

    @objc public func invalidate() {
        DispatchQueue.main.async { [self] in
            active = false
            QartveloAds.removeObserver(observer)
            observer.event = nil
            onEvent = nil
            calls.values.forEach { $0.event = nil }
            calls.removeAll()
            initCalls.removeAll()
        }
    }

    @objc public func initialize(_ values: [String: Any], resolve: @escaping (Any?) -> Void, reject: @escaping (String, String) -> Void) {
        DispatchQueue.main.async { [self] in
            guard active else { return }
            let options = QartveloAdsOptions()
            options.requestTimeoutMs = (values["requestTimeoutMs"] as? NSNumber)?.intValue ?? 800
            options.testMode = values["testMode"] as? Bool ?? false
            options.testForceNoFill = values["testForceNoFill"] as? Bool ?? false
            options.admobFallback = values["admobFallback"] as? Bool ?? true
            options.logLevel = Self.logLevel(values["logLevel"] as? String ?? "error")
            options.admobAdUnits = values["admobAdUnits"] as? [String: String] ?? [:]
            if let url = values["baseUrl"] as? String, let parsed = URL(string: url) { options.baseURL = parsed }
            // testModeInDebugBuilds is intentionally ignored by the native iOS SDK.
            let token = UUID()
            initCalls[token] = (resolve, reject)
            QartveloAds.initialize(appKey: values["appKey"] as? String ?? "", options: options) { [weak self] success, error in
                guard let target = self?.initCalls.removeValue(forKey: token) else { return }
                if success { target.0(nil) }
                else { target.1(error?.code.description.lowercased() ?? "internal_error", error?.message ?? "Initialization failed") }
            }
            sdkStarted = true
            NotificationCenter.default.post(name: initNotification, object: nil)
        }
    }

    @objc public func initialized(_ resolve: @escaping (Any?) -> Void) {
        DispatchQueue.main.async { [weak self] in
            guard self?.active == true else { return }
            resolve(QartveloAds.isInitialized)
        }
    }
    @objc public func ready(_ placement: String, rewarded: Bool, resolve: @escaping (Any?) -> Void) {
        DispatchQueue.main.async { [weak self] in
            guard self?.active == true else { return }
            resolve(rewarded ? QartveloAds.isRewardedReady(placement) : QartveloAds.isInterstitialReady(placement))
        }
    }
    @objc public func load(_ placement: String, rewarded: Bool, resolve: @escaping (Any?) -> Void, reject: @escaping (String, String) -> Void) {
        perform(placement, rewarded: rewarded, showing: false, resolve: resolve, reject: reject)
    }
    @objc public func show(_ placement: String, rewarded: Bool, resolve: @escaping (Any?) -> Void, reject: @escaping (String, String) -> Void) {
        perform(placement, rewarded: rewarded, showing: true, resolve: resolve, reject: reject)
    }
    private func perform(_ placement: String, rewarded: Bool, showing: Bool, resolve: @escaping (Any?) -> Void, reject: @escaping (String, String) -> Void) {
        DispatchQueue.main.async { [self] in
            guard active else { return }
            let format: QartveloAdFormat = rewarded ? .rewarded : .interstitial
            formats[placement] = format
            let token = UUID(), relay = AdRelay()
            relay.format = format
            calls[token] = relay // Native delegates are weak; retain until this promise settles.
            relay.event = { [weak self, weak relay] data in
                guard let self = self, self.active, let relay = relay else { return }
                let type = data["type"] as? String
                var outcome: Any?
                if type == "loadFailed", let error = data["error"] as? [String: String] {
                    relay.event = nil; self.calls.removeValue(forKey: token)
                    reject(error["code"] ?? "internal_error", error["message"] ?? "Ad failed")
                    return
                }
                if !showing && type == "loaded" { var info = data; info.removeValue(forKey: "type"); outcome = info }
                if showing && (type == "dismissed" || type == "noAdAvailable") {
                    var result: [String: Any] = ["shown": type == "dismissed", "rewarded": relay.reward != nil]
                    result["source"] = relay.source ?? data["source"]
                    if let reward = relay.reward { result["reward"] = ["type": reward.type, "amount": reward.amount] }
                    outcome = result
                }
                if let outcome = outcome {
                    relay.event = nil; self.calls.removeValue(forKey: token); resolve(outcome)
                }
            }
            if showing {
                guard let controller = Self.presenter() else {
                    relay.event = nil; calls.removeValue(forKey: token)
                    reject("show_failed", "No foreground view controller to show the ad in"); return
                }
                if rewarded { QartveloAds.showRewarded(placement, from: controller, delegate: relay) }
                else { QartveloAds.showInterstitial(placement, from: controller, delegate: relay) }
            } else if rewarded { QartveloAds.loadRewarded(placement, delegate: relay) }
            else { QartveloAds.loadInterstitial(placement, delegate: relay) }
        }
    }
    private static func presenter() -> UIViewController? {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.filter { $0.activationState == .foregroundActive }
        guard UIApplication.shared.applicationState == .active else { return nil }
        var controller = scenes.flatMap { $0.windows }.first { $0.isKeyWindow }?.rootViewController
        if controller == nil, let legacyWindow = UIApplication.shared.delegate?.window ?? nil {
            controller = legacyWindow.rootViewController
        }
        while let presented = controller?.presentedViewController { controller = presented }
        return controller
    }
    private static func logLevel(_ level: String) -> QartveloAdsLogLevel {
        switch level { case "none": return .none; case "info": return .info; case "debug": return .debug; default: return .error }
    }
    @objc public func setLogLevel(_ level: String) { QartveloAds.setLogLevel(Self.logLevel(level)) }
    @objc public func setPrivacy(_ values: [String: Any]) {
        QartveloAds.setPrivacy(QartveloAdsPrivacy(consentGiven: values["consentGiven"] as? Bool,
            childDirected: values["childDirected"] as? Bool, underAgeOfConsent: values["underAgeOfConsent"] as? Bool))
    }
}

@objc(QartveloAdsBannerHost)
public final class QartveloAdsBannerHost: UIView {
    @objc public var onEvent: (([String: Any]) -> Void)?
    @objc public var onSize: ((Double, Double) -> Void)?
    private(set) var placement: String = ""
    private var inlineSizing = false
    private var inlineMaxHeight: Double = 250
    /// All banner props at once (`placementId`, `size="inline"`, `maxHeight` in points), so a
    /// change of several never loads with half of them. Unchanged values do nothing.
    @objc public func apply(placement next: String, inline: Bool, maxHeight: Double) {
        let sizingChanged = inline != inlineSizing || (inline && maxHeight != inlineMaxHeight)
        guard next != placement || sizingChanged else { return }
        placement = next
        inlineSizing = inline
        inlineMaxHeight = maxHeight
        banner.sizing = inline ? .inline : .anchored
        banner.inlineMaxHeight = CGFloat(maxHeight)
        banner.destroy(); banner.placementId = placement
        loadIfPossible()
    }
    private let banner = QartveloAdsBannerView(frame: .zero)
    private let relay = AdRelay()
    private var notification: NSObjectProtocol?
    private var lastSize = CGSize(width: -1, height: -1)
    @objc public override init(frame: CGRect) {
        super.init(frame: frame)
        clipsToBounds = true
        addSubview(banner); banner.delegate = relay; relay.format = .banner
        relay.event = { [weak self] data in
            self?.measure(); self?.onEvent?(data)
        }
        notification = NotificationCenter.default.addObserver(forName: initNotification, object: nil, queue: .main) { [weak self] _ in self?.loadIfPossible() }
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) is unavailable") }
    deinit { if let notification = notification { NotificationCenter.default.removeObserver(notification) } }
    public override func didMoveToWindow() {
        super.didMoveToWindow()
        if window == nil { banner.destroy(); measure() } else { loadIfPossible() }
    }
    public override func layoutSubviews() {
        super.layoutSubviews()
        banner.frame = bounds; banner.layoutIfNeeded(); measure()
        loadIfPossible()
    }
    private func loadIfPossible() {
        guard sdkStarted, window != nil, bounds.width > 0, !placement.isEmpty else { return }
        banner.load()
    }
    private func measure() {
        let intrinsic = banner.intrinsicContentSize
        let size = CGSize(width: max(0, intrinsic.width), height: max(0, intrinsic.height))
        guard size != lastSize else { return }; lastSize = size
        onSize?(Double(size.width), Double(size.height))
    }
    @objc public func recycle() {
        banner.destroy(); placement = ""; inlineSizing = false; inlineMaxHeight = 250; lastSize = CGSize(width: -1, height: -1)
    }
}
