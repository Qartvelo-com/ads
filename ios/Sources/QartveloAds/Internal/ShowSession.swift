import UIKit

/// State of one Qartvelo Ads full-screen show, rendered by `AdViewController`. Main thread only.
final class ShowSession {
    private unowned let engine: Engine
    private let controller: FullscreenController
    let ad: ServedAd
    /// Held for the duration of the show only.
    private let delegate: QartveloAdsDelegate?
    private weak var host: UIViewController?
    private let info: QartveloAdsAdInfo

    private(set) var rendered = false
    private(set) var renderedAt: Int64 = 0
    private(set) var videoCompleted = false
    private(set) var playbackFailed = false
    private(set) var closed = false
    private var clickRecorded = false
    private var rewardGranted = false

    var isRewarded: Bool { controller.format == .rewarded }

    init(engine: Engine, controller: FullscreenController, ad: ServedAd, delegate: QartveloAdsDelegate?, host: UIViewController) {
        self.engine = engine
        self.controller = controller
        self.ad = ad
        self.delegate = delegate
        self.host = host
        info = QartveloAdsAdInfo(
            placementId: controller.placementId,
            format: controller.format,
            source: .qartvelo,
            campaignId: ad.campaignId,
            creativeId: ad.creativeId
        )
    }

    /// The creative is visibly on screen: emit shown + impression once and queue the impression event.
    func onRendered() {
        guard !rendered, !closed else { return }
        rendered = true
        renderedAt = Clock.now()
        engine.events.enqueue(.impression, ad: ad)
        let info = self.info
        Listeners.emit(delegate) { $0.qartveloAdDidShow?(info) }
        Listeners.emit(delegate) { $0.qartveloAdDidRecordImpression?(info) }
    }

    /// Records the click (queued after the impression) before handing the URL to the browser.
    func onClick() {
        guard rendered, !closed, let url = ad.clickURL else { return }
        if !clickRecorded {
            clickRecorded = true
            engine.events.enqueue(.click, ad: ad)
            let info = self.info
            Listeners.emit(delegate) { $0.qartveloAdDidClick?(info) }
        }
        ClickOpener.open(url)
    }

    /// Playback reached the end. Rewarded placements grant exactly one reward here, never earlier.
    func onVideoCompleted() {
        guard !videoCompleted, rendered, !closed else { return }
        videoCompleted = true
        if isRewarded && !rewardGranted {
            rewardGranted = true
            engine.events.enqueue(.reward, ad: ad, completion: true)
            let info = self.info
            Listeners.emit(delegate) { $0.qartveloAd?(info, didEarnReward: QartveloAdsReward()) }
        }
    }

    func onPlaybackFailed() {
        playbackFailed = true
    }

    /// Creative failed before display: no events were sent, so the controller may fall back.
    /// Call after the ad view controller was dismissed.
    func onRenderFailed() {
        guard !rendered, !closed else { return }
        closed = true
        controller.onRenderFailed(host: host, delegate: delegate)
    }

    /// The user closed the ad (or it was dismissed). Idempotent.
    func close() {
        guard !closed else { return }
        closed = true
        controller.onSessionClosed()
        let info = self.info
        if !rendered {
            // Closed before anything was displayed: no impression, so report "nothing shown".
            Listeners.emit(delegate) { $0.qartveloAdNoAdAvailable?(placementId: info.placementId, format: info.format) }
            return
        }
        if isRewarded && !rewardGranted {
            // Reported as incomplete so the backend can tell skips from completions.
            engine.events.enqueue(.reward, ad: ad, completion: false)
        }
        Listeners.emit(delegate) { $0.qartveloAdDidDismiss?(info) }
    }

    /// Runs decoding or other disk work off the main thread.
    func runInBackground(_ task: @escaping () -> Void) {
        engine.io.async(execute: task)
    }
}

/// User-facing strings of the full-screen ad.
enum Strings {
    static let ad = "Ad"
    static let testAd = "Test ad"
    static let aboutAds = "Ad. About Qartvelo Ads"
    static let close = "Close ad"
    static let learnMore = "Learn more"
    static let rewardEarned = "Reward earned"
    static let skipTitle = "Close video?"
    static let skipMessage = "If you close now you will not receive the reward."
    static let skipConfirm = "Close video"
    static let skipResume = "Resume"

    static func rewardIn(_ seconds: Int) -> String { "Reward in \(seconds)s" }
    static func secondsLeft(_ seconds: Int) -> String { "\(seconds)s" }
}
