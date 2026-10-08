import UIKit
import QartveloAds

/// Runs banner, interstitial and rewarded one after another without any taps.
final class QAViewController: UIViewController, QartveloAdsDelegate {
    private let banner = QartveloAdsBannerView(placementId: AppConfig.bannerPlacement)
    private var started = false
    private var bannerShot = false
    private var finished = false
    private var watchdog: DispatchWorkItem?
    private var bannerWidthConstraint: NSLayoutConstraint?

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .systemBackground
        let title = UILabel()
        title.text = "Qartvelo Ads QA"
        title.font = .boldSystemFont(ofSize: 24)
        title.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(title)
        banner.delegate = self
        banner.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(banner)
        NSLayoutConstraint.activate([
            title.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            title.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 40),
            banner.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor),
        ])
        if let width = AppConfig.bannerWidth {
            let constraint = banner.widthAnchor.constraint(equalToConstant: width)
            bannerWidthConstraint = constraint
            NSLayoutConstraint.activate([banner.centerXAnchor.constraint(equalTo: view.centerXAnchor), constraint])
        } else {
            NSLayoutConstraint.activate([
                banner.leadingAnchor.constraint(equalTo: view.leadingAnchor),
                banner.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            ])
        }
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        guard !started else { return }
        started = true
        banner.load()
        if AppConfig.bannerOnly {
            expect("banner load", within: 15) { self.finish() }
        } else {
            after(3) { self.startInterstitial() }
        }
    }

    private func after(_ seconds: Double, _ block: @escaping () -> Void) {
        DispatchQueue.main.asyncAfter(deadline: .now() + seconds, execute: block)
    }

    /// Moves on if a step never reports back.
    private func expect(_ step: String, within seconds: Double, then next: @escaping () -> Void) {
        watchdog?.cancel()
        let item = DispatchWorkItem { qa("TIMEOUT \(step)"); next() }
        watchdog = item
        DispatchQueue.main.asyncAfter(deadline: .now() + seconds, execute: item)
    }

    private func startInterstitial() {
        qa("step interstitial")
        expect("interstitial load", within: 15) { self.startRewarded() }
        QartveloAds.loadInterstitial(AppConfig.interstitialPlacement, delegate: self)
    }

    private func startRewarded() {
        if presentedViewController != nil { dismiss(animated: false) }
        qa("step rewarded")
        expect("rewarded load", within: 20) { self.finish() }
        QartveloAds.loadRewarded(AppConfig.rewardedPlacement, delegate: self)
    }

    private func finish() {
        guard !finished else { return }
        finished = true
        watchdog?.cancel()
        after(2) { qa("DONE") }
    }

    private func closeAd() {
        qa("closing the full-screen ad")
        presentedViewController?.dismiss(animated: true)
    }

    func qartveloAdDidLoad(_ info: QartveloAdsAdInfo) {
        qa("loaded format=\(info.format) placement=\(info.placementId) source=\(info.source) campaign=\(info.campaignId ?? "-")")
        switch info.format {
        case .interstitial:
            expect("interstitial show", within: 15) { self.startRewarded() }
            after(1) { QartveloAds.showInterstitial(AppConfig.interstitialPlacement, from: self, delegate: self) }
        case .rewarded:
            expect("rewarded show", within: 15) { self.finish() }
            after(1) { QartveloAds.showRewarded(AppConfig.rewardedPlacement, from: self, delegate: self) }
        default:
            break
        }
    }

    func qartveloAdDidFailToLoad(placementId: String, error: QartveloAdsError) {
        qa("failed placement=\(placementId) error=\(error)")
        if placementId == AppConfig.interstitialPlacement { after(1) { self.startRewarded() } }
        if placementId == AppConfig.rewardedPlacement { finish() }
    }

    func qartveloAdNoAdAvailable(placementId: String, format: QartveloAdFormat) {
        qa("noAd placement=\(placementId) format=\(format)")
    }

    func qartveloAdDidStartFallback(placementId: String, format: QartveloAdFormat, reason: String) {
        qa("fallback placement=\(placementId) format=\(format) reason=\(reason)")
    }

    func qartveloAdDidShow(_ info: QartveloAdsAdInfo) {
        qa("shown format=\(info.format) source=\(info.source)")
        switch info.format {
        case .interstitial:
            expect("interstitial dismiss", within: 20) { self.startRewarded() }
            after(3) { qa("SCREENSHOT_NOW interstitial") }
            after(6) { self.closeAd() }
        case .rewarded:
            expect("rewarded reward", within: 60) {
                self.closeAd()
                self.after(3) { self.finish() }
            }
            after(5) { qa("SCREENSHOT_NOW rewarded") }
        default:
            break
        }
    }

    func qartveloAdDidRecordImpression(_ info: QartveloAdsAdInfo) {
        qa("impression format=\(info.format) source=\(info.source)")
        if info.format == .banner && !bannerShot {
            bannerShot = true
            after(1) { qa("SCREENSHOT_NOW banner") }
            if AppConfig.bannerOnly {
                after(3) {
                    if info.source == .qartvelo, let constraint = self.bannerWidthConstraint {
                        constraint.constant = max(50, constraint.constant / 2)
                        self.view.layoutIfNeeded()
                        qa("resized banner width=\(self.banner.bounds.width) height=\(self.banner.intrinsicContentSize.height)")
                        self.after(1) { qa("SCREENSHOT_NOW banner-resized") }
                        self.after(3) { self.finish() }
                    } else {
                        self.finish()
                    }
                }
            }
        }
    }

    func qartveloAdDidClick(_ info: QartveloAdsAdInfo) {
        qa("click format=\(info.format)")
    }

    func qartveloAd(_ info: QartveloAdsAdInfo, didEarnReward reward: QartveloAdsReward) {
        qa("reward type=\(reward.type) amount=\(reward.amount) source=\(info.source)")
        expect("rewarded dismiss", within: 15) { self.finish() }
        after(1) { qa("SCREENSHOT_NOW reward-earned") }
        after(3) { self.closeAd() }
    }

    func qartveloAdDidDismiss(_ info: QartveloAdsAdInfo) {
        qa("dismissed format=\(info.format) source=\(info.source)")
        switch info.format {
        case .interstitial: after(1) { self.startRewarded() }
        case .rewarded: finish()
        default: break
        }
    }
}
