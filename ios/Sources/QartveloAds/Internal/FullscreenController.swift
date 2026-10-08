import UIKit

/// Per-placement state machine for interstitial and rewarded ads. Main thread only.
///
/// Load: a valid cached Qartvelo Ads ad completes immediately; otherwise Qartvelo Ads is requested
/// (bounded by the effective timeout) while the fallback network preloads concurrently. Loads issued
/// while one is in flight join it. On failure: `qartveloAdDidStartFallback` then `qartveloAdDidLoad`
/// (source admob) or `qartveloAdNoAdAvailable` + `qartveloAdDidFailToLoad`. A fill whose creative is
/// still downloading at the decision deadline (timeout + `creativeGraceMs`) lets a ready fallback
/// finish the load; the download continues and the ad is kept for the next show.
///
/// Show: a valid Qartvelo Ads ad, else a ready fallback ad, else `qartveloAdNoAdAvailable`.
///
/// Delegates are only held while a load is running; the last load delegate (used by a show without
/// its own delegate) is held weakly, so a view controller passed as a delegate is never retained.
final class FullscreenController {
    /// Extra time after the request timeout for a creative to download before a ready fallback wins.
    static let creativeGraceMs: Int64 = 1_500

    private unowned let engine: Engine
    let placementId: String
    let format: QartveloAdFormat

    /// Loaded Qartvelo Ads ad with its creative on disk, not yet shown.
    private var loadedAd: ServedAd?
    private var op: LoadOp?
    private weak var lastLoadDelegate: QartveloAdsDelegate?
    private var fallbackLoading = false

    init(engine: Engine, placementId: String, format: QartveloAdFormat) {
        self.engine = engine
        self.placementId = placementId
        self.format = format
    }

    func isReady() -> Bool {
        loadedAd?.isValid() == true || fallbackReady()
    }

    private func fallbackReady() -> Bool {
        guard let adapter = engine.fallbackAdapter else { return false }
        return format == .rewarded ? adapter.isRewardedReady(placementId: placementId) : adapter.isInterstitialReady(placementId: placementId)
    }

    // MARK: - Load

    func load(_ delegate: QartveloAdsDelegate?) {
        if let delegate = delegate { lastLoadDelegate = delegate }
        if let op = op {
            Log.d("Joining in-flight load for '\(placementId)'")
            op.delegates.append(delegate)
            return
        }
        let placement = engine.placement(placementId)
        if let configured = placement?.format, configured != format {
            let error = QartveloAdsError(.invalidPlacement, "Placement '\(placementId)' is a \(configured.wireName) placement")
            Listeners.emit(delegate) { $0.qartveloAdDidFailToLoad?(placementId: self.placementId, error: error) }
            return
        }
        let created = LoadOp(controller: self, delegate: delegate, placement: placement)
        op = created
        created.start()
    }

    private final class LoadOp {
        unowned let controller: FullscreenController
        var delegates: [QartveloAdsDelegate?]
        private let placement: PlacementConfig?
        private let timeoutMs: Int64
        private var failure: QartveloAdsFailure?
        /// Qartvelo Ads filled but its creative missed the decision deadline; a ready fallback may finish.
        private var slow = false
        private var fallbackAttempted = false
        private var fallbackAnnounced = false
        private var done = false
        private var decisionDeadline: DispatchWorkItem?

        private var engine: Engine { controller.engine }
        private var placementId: String { controller.placementId }
        private var format: QartveloAdFormat { controller.format }

        init(controller: FullscreenController, delegate: QartveloAdsDelegate?, placement: PlacementConfig?) {
            self.controller = controller
            delegates = [delegate]
            self.placement = placement
            timeoutMs = controller.engine.effectiveTimeoutMs(placement)
        }

        /// Resolved when needed: remote config may arrive while this load is running.
        private func fallbackUnit(_ serverFallback: String? = nil) -> String? {
            engine.fallbackUnit(placementId, placement: engine.placement(placementId) ?? placement, serverFallback: serverFallback)
        }

        func start() {
            if let cached = controller.loadedAd, !cached.isValid() { controller.discardAd("expired") }
            if let valid = controller.loadedAd {
                preloadFallback()
                finishLoaded(.qartvelo, ad: valid)
                return
            }
            let refreshing = engine.refreshConfigIfStale(placement)
            if !refreshing && !engine.qartveloEnabled(placement) {
                preloadFallback()
                onFailed(QartveloAdsFailure(
                    reason: QartveloAdsFailure.disabled,
                    error: QartveloAdsError(.noFill, "Qartvelo Ads serving is disabled for this placement")
                ))
                return
            }
            // Dispatch the Qartvelo Ads request first: a cold fallback SDK can keep the main thread busy
            // while it starts loading, and that must not eat into the Qartvelo Ads time budget.
            fetch()
            preloadFallback()
            let grace = TestHooks.creativeGraceMs ?? FullscreenController.creativeGraceMs
            decisionDeadline = Main.postDelayed(timeoutMs + grace) { [weak self] in self?.onDecisionDeadline() }
        }

        private func fetch() {
            engine.fetchAd(placementId, format: format, timeoutMs: timeoutMs) { [self] outcome in
                switch outcome {
                case .success(let ad):
                    if done {
                        // The fallback finished this load while the creative was downloading:
                        // keep the ad for the next show.
                        if controller.loadedAd?.isValid() != true { controller.loadedAd = ad }
                        return
                    }
                    controller.loadedAd = ad
                    finishLoaded(.qartvelo, ad: ad)
                case .failure(let failure):
                    onFailed(failure)
                }
            }
        }

        /// Preloads the fallback in parallel so it is ready the moment Qartvelo Ads cannot serve.
        private func preloadFallback() {
            guard let unit = fallbackUnit() else { return }
            fallbackAttempted = true
            if !controller.fallbackReady() && !controller.fallbackLoading { controller.startFallbackLoad(unit) }
        }

        private func onFailed(_ failure: QartveloAdsFailure) {
            guard !done else { return }
            self.failure = failure
            guard let unit = fallbackUnit(failure.serverFallback) else {
                finishNoAd(failure)
                return
            }
            announceFallback(failure.reason)
            if controller.fallbackReady() {
                finishLoaded(.admob, ad: nil)
            } else if controller.fallbackLoading {
                // onFallbackResult completes the load.
            } else if !fallbackAttempted {
                fallbackAttempted = true
                controller.startFallbackLoad(unit)
            } else {
                finishNoAd(failure)
            }
        }

        /// Qartvelo Ads answered in time but its creative is still downloading (a slow CDN). The host
        /// must not wait for it when the fallback can serve; without a usable fallback, keep waiting.
        private func onDecisionDeadline() {
            guard !done, failure == nil, let unit = fallbackUnit() else { return }
            Log.i("Qartvelo Ads creative for '\(placementId)' is still loading; the fallback may serve this load")
            slow = true
            if controller.fallbackReady() {
                finishWithFallback(QartveloAdsFailure.timeout)
            } else if !controller.fallbackLoading && !fallbackAttempted {
                fallbackAttempted = true
                controller.startFallbackLoad(unit)
            }
        }

        func onFallbackResult(_ success: Bool) {
            guard !done else { return }
            if let failure = failure {
                if success { finishLoaded(.admob, ad: nil) } else { finishNoAd(failure) }
            } else if slow && success {
                finishWithFallback(QartveloAdsFailure.timeout)
            }
            // Otherwise Qartvelo Ads is still pending and its own result completes the load.
        }

        private func finishWithFallback(_ reason: String) {
            announceFallback(reason)
            finishLoaded(.admob, ad: nil)
        }

        private func announceFallback(_ reason: String) {
            guard !fallbackAnnounced else { return }
            fallbackAnnounced = true
            engine.reportFallback(placementId, reason: reason)
            Log.i("Falling back for '\(placementId)' (\(reason))")
            let placementId = self.placementId
            let format = self.format
            Listeners.emit(delegates) { $0.qartveloAdDidStartFallback?(placementId: placementId, format: format, reason: reason) }
        }

        private func finishLoaded(_ source: QartveloAdSource, ad: ServedAd?) {
            finish()
            let info = QartveloAdsAdInfo(placementId: placementId, format: format, source: source, campaignId: ad?.campaignId, creativeId: ad?.creativeId)
            Log.i("Loaded \(format) '\(placementId)' from \(source)")
            Listeners.emit(delegates) { $0.qartveloAdDidLoad?(info) }
            delegates.removeAll()
        }

        private func finishNoAd(_ failure: QartveloAdsFailure) {
            finish()
            Log.i("No ad available for '\(placementId)' (\(failure.reason))")
            let placementId = self.placementId
            let format = self.format
            Listeners.emit(delegates) { $0.qartveloAdNoAdAvailable?(placementId: placementId, format: format) }
            Listeners.emit(delegates) { $0.qartveloAdDidFailToLoad?(placementId: placementId, error: failure.error) }
            delegates.removeAll()
        }

        private func finish() {
            done = true
            decisionDeadline?.cancel()
            decisionDeadline = nil
            if controller.op === self { controller.op = nil }
        }
    }

    private func startFallbackLoad(_ unit: String) {
        guard let adapter = engine.fallbackAdapter else { return }
        fallbackLoading = true
        let callback = LoadRelay { [weak self] success, message in self?.onFallbackLoadFinished(success, message) }
        if format == .rewarded {
            adapter.loadRewarded(placementId: placementId, adUnitId: unit, callback: callback)
        } else {
            adapter.loadInterstitial(placementId: placementId, adUnitId: unit, callback: callback)
        }
    }

    private func onFallbackLoadFinished(_ success: Bool, _ message: String?) {
        fallbackLoading = false
        if success {
            Log.d("Fallback \(format) ready for '\(placementId)'")
        } else {
            Log.i("Fallback \(format) failed for '\(placementId)': \(message ?? "")")
        }
        op?.onFallbackResult(success)
    }

    private func discardAd(_ why: String) {
        if loadedAd != nil { Log.i("Discarding Qartvelo Ads ad for '\(placementId)' (\(why))") }
        loadedAd = nil
    }

    // MARK: - Show

    func show(from viewController: UIViewController, delegate: QartveloAdsDelegate?) {
        let target = delegate ?? lastLoadDelegate
        let placementId = self.placementId
        if engine.fullscreenShowing {
            let error = QartveloAdsError(.alreadyShowing, "Another full-screen ad is showing")
            Listeners.emit(target) { $0.qartveloAdDidFailToLoad?(placementId: placementId, error: error) }
            return
        }
        guard let presenter = Presenter.topmost(from: viewController) else {
            let error = QartveloAdsError(.showFailed, "The view controller is not in a window")
            Listeners.emit(target) { $0.qartveloAdDidFailToLoad?(placementId: placementId, error: error) }
            return
        }
        if let ad = loadedAd {
            loadedAd = nil // One ad, one show: the signed token allows exactly one impression.
            if ad.isValid() {
                present(ad, from: presenter, delegate: target)
                return
            }
            Log.i("Qartvelo Ads ad for '\(placementId)' expired before show")
        }
        if fallbackReady() {
            showFallback(from: presenter, delegate: target)
            return
        }
        let format = self.format
        Listeners.emit(target) { $0.qartveloAdNoAdAvailable?(placementId: placementId, format: format) }
    }

    private func present(_ ad: ServedAd, from presenter: UIViewController, delegate: QartveloAdsDelegate?) {
        let session = ShowSession(engine: engine, controller: self, ad: ad, delegate: delegate, host: presenter)
        engine.fullscreenShowing = true
        let adViewController = AdViewController(session: session)
        presenter.present(adViewController, animated: true)
    }

    /// Called by `ShowSession` when the creative failed before anything was displayed.
    func onRenderFailed(host: UIViewController?, delegate: QartveloAdsDelegate?) {
        engine.fullscreenShowing = false
        if let host = host, host.view.window != nil, fallbackReady() {
            Log.i("Qartvelo Ads creative failed to render; showing fallback for '\(placementId)'")
            showFallback(from: host, delegate: delegate)
        } else {
            let placementId = self.placementId
            let format = self.format
            Listeners.emit(delegate) { $0.qartveloAdNoAdAvailable?(placementId: placementId, format: format) }
        }
    }

    func onSessionClosed() {
        engine.fullscreenShowing = false
    }

    private func showFallback(from viewController: UIViewController, delegate: QartveloAdsDelegate?) {
        guard let adapter = engine.fallbackAdapter else { return }
        engine.fullscreenShowing = true
        let info = QartveloAdsAdInfo(placementId: placementId, format: format, source: .admob)
        let callback = FallbackShowRelay(engine: engine, info: info, delegate: delegate)
        if format == .rewarded {
            adapter.showRewarded(placementId: placementId, from: viewController, callback: callback)
        } else {
            adapter.showInterstitial(placementId: placementId, from: viewController, callback: callback)
        }
    }
}

/// Moves fallback load callbacks to the main thread, once.
final class LoadRelay: QartveloFallbackLoadCallback {
    private let lock = NSLock()
    private var fired = false
    private let handler: (Bool, String?) -> Void

    init(_ handler: @escaping (Bool, String?) -> Void) {
        self.handler = handler
    }

    func onLoaded() {
        guard claim() else { return }
        Main.post { self.handler(true, nil) }
    }

    func onFailed(_ message: String) {
        guard claim() else { return }
        Main.post { self.handler(false, message) }
    }

    private func claim() -> Bool {
        lock.lock()
        defer { lock.unlock() }
        if fired { return false }
        fired = true
        return true
    }
}

/// Normalises fallback show callbacks: main thread, each event at most once, reward at most once and
/// only for rewarded placements, exactly one terminal event.
final class FallbackShowRelay: QartveloFallbackShowCallback {
    private unowned let engine: Engine
    private let info: QartveloAdsAdInfo
    /// Held for the duration of the show only, like the Android listener.
    private let delegate: QartveloAdsDelegate?
    private var shown = false
    private var impression = false
    private var clicked = false
    private var rewarded = false
    private var finished = false

    init(engine: Engine, info: QartveloAdsAdInfo, delegate: QartveloAdsDelegate?) {
        self.engine = engine
        self.info = info
        self.delegate = delegate
    }

    func onShown() {
        Main.run { [self] in
            guard !finished, !shown else { return }
            shown = true
            let info = self.info
            Listeners.emit(delegate) { $0.qartveloAdDidShow?(info) }
        }
    }

    func onImpression() {
        Main.run { [self] in
            guard !finished, !impression else { return }
            impression = true
            let info = self.info
            Listeners.emit(delegate) { $0.qartveloAdDidRecordImpression?(info) }
        }
    }

    func onClicked() {
        Main.run { [self] in
            guard !finished, !clicked else { return }
            clicked = true
            let info = self.info
            Listeners.emit(delegate) { $0.qartveloAdDidClick?(info) }
        }
    }

    func onReward(type: String, amount: Int) {
        Main.run { [self] in
            guard info.format == .rewarded, !finished, !rewarded else { return }
            rewarded = true
            let info = self.info
            let reward = QartveloAdsReward(type: type.isEmpty ? "reward" : type, amount: max(1, amount))
            Listeners.emit(delegate) { $0.qartveloAd?(info, didEarnReward: reward) }
        }
    }

    func onDismissed() {
        Main.run { [self] in
            guard !finished else { return }
            finished = true
            engine.fullscreenShowing = false
            let info = self.info
            Listeners.emit(delegate) { $0.qartveloAdDidDismiss?(info) }
        }
    }

    func onShowFailed(_ message: String) {
        Main.run { [self] in
            guard !finished else { return }
            finished = true
            Log.e("Fallback ad failed to show: \(message)")
            engine.fullscreenShowing = false
            let placementId = info.placementId
            let error = QartveloAdsError(.showFailed, "Fallback ad failed to show")
            Listeners.emit(delegate) { $0.qartveloAdDidFailToLoad?(placementId: placementId, error: error) }
        }
    }
}

enum Presenter {
    /// The view controller to present from: `viewController`'s topmost presented descendant.
    static func topmost(from viewController: UIViewController) -> UIViewController? {
        guard viewController.viewIfLoaded?.window != nil || viewController.presentedViewController != nil else { return nil }
        var top = viewController
        while let presented = top.presentedViewController, !presented.isBeingDismissed {
            top = presented
        }
        return top
    }
}
