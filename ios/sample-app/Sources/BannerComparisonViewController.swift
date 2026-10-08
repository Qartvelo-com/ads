import UIKit
import QartveloAds
import QartveloAdsAdMob

/// Measures the SDK's Qartvelo image banner and its actual AdMob adaptive adapter at the same width.
final class BannerComparisonViewController: UIViewController, QartveloAdsDelegate {
    private let qartvelo = QartveloAdsBannerView(placementId: AppConfig.bannerPlacement)
    private let googleContainer = UIView()
    private let adapter = QartveloAdMobFallbackAdapter()
    private let callback = ComparisonAdMobCallback()
    private var google: QartveloFallbackBanner?
    private var googleHeight: NSLayoutConstraint!
    private var qartveloReady = false
    private var googleReady = false
    private var started = false
    private var reported = false
    private let context = UILabel()
    private let qartveloLabel = UILabel()
    private let googleLabel = UILabel()
    private let summary = UILabel()

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .systemBackground
        let title = UILabel()
        title.text = "Banner height comparison"
        title.font = .boldSystemFont(ofSize: 25)
        context.font = .systemFont(ofSize: 14)
        context.textColor = .secondaryLabel
        qartveloLabel.text = "Qartvelo · loading…"
        googleLabel.text = "AdMob adaptive · loading…"
        for label in [qartveloLabel, googleLabel] {
            label.font = .monospacedDigitSystemFont(ofSize: 18, weight: .semibold)
        }
        summary.font = .monospacedDigitSystemFont(ofSize: 18, weight: .medium)
        summary.text = "Loading both test ads…"
        let caption = UILabel()
        caption.text = "Actual rendered sizes in points\nCurrent iOS adapter: standard anchored adaptive"
        caption.font = .systemFont(ofSize: 13)
        caption.textColor = .secondaryLabel
        let stack = UIStackView(arrangedSubviews: [title, context, qartveloLabel, qartvelo, googleLabel, googleContainer, summary, caption])
        stack.axis = .vertical
        stack.spacing = 16
        stack.setCustomSpacing(36, after: context)
        stack.setCustomSpacing(32, after: qartvelo)
        stack.setCustomSpacing(32, after: googleContainer)
        for label in [title, context, qartveloLabel, googleLabel, summary, caption] {
            label.textAlignment = .center
            label.numberOfLines = 0
        }
        stack.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(stack)
        googleHeight = googleContainer.heightAnchor.constraint(equalToConstant: 0)
        let requestedWidth = AppConfig.bannerWidth
        var horizontal = [stack.centerXAnchor.constraint(equalTo: view.safeAreaLayoutGuide.centerXAnchor)]
        if let width = requestedWidth {
            horizontal.append(stack.widthAnchor.constraint(equalToConstant: width))
        } else {
            horizontal.append(stack.widthAnchor.constraint(equalTo: view.safeAreaLayoutGuide.widthAnchor))
        }
        NSLayoutConstraint.activate(horizontal + [
            stack.centerYAnchor.constraint(equalTo: view.safeAreaLayoutGuide.centerYAnchor),
            googleHeight,
        ])
        qartvelo.delegate = self
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        guard !started else { return }
        started = true
        view.layoutIfNeeded()
        let width = googleContainer.bounds.width
        let scale = view.window?.screen.scale ?? UIScreen.main.scale
        context.text = String(format: "Same width: %.0f pt · @%.0fx · test ads", width, scale)
        callback.loaded = { [weak self] in
            guard let self else { return }
            self.googleReady = true
            self.measureWhenReady()
        }
        callback.failed = { [weak self] message in
            self?.googleLabel.text = "AdMob failed: \(message)"
            qa("comparison FAILED AdMob: \(message)")
        }
        for width in [320.0, 375, 390, 393, 402, 414, 430, 440, 728] {
            let size = adapter.adaptiveBannerSize(width: width)
            qa("adaptive size width=\(width) height=\(size.height)")
        }
        adapter.initialize(settings: QartveloFallbackSettings(testMode: true, privacy: QartveloAdsPrivacy()))
        let google = adapter.createBanner(placementId: "comparison_admob", adUnitId: "", width: width, rootViewController: self, callback: callback)
        self.google = google
        let adView = google.view
        let height = adView.intrinsicContentSize.height > 0 ? adView.intrinsicContentSize.height : adView.bounds.height
        googleHeight.constant = height
        adView.translatesAutoresizingMaskIntoConstraints = false
        googleContainer.addSubview(adView)
        NSLayoutConstraint.activate([
            adView.leadingAnchor.constraint(equalTo: googleContainer.leadingAnchor),
            adView.trailingAnchor.constraint(equalTo: googleContainer.trailingAnchor),
            adView.topAnchor.constraint(equalTo: googleContainer.topAnchor),
            adView.bottomAnchor.constraint(equalTo: googleContainer.bottomAnchor),
        ])
        qartvelo.load()
    }

    func qartveloAdDidLoad(_ info: QartveloAdsAdInfo) {
        guard info.source == .qartvelo else {
            summary.text = "Qartvelo fell back; no Qartvelo creative to compare."
            return
        }
        qartveloReady = true
        measureWhenReady()
    }

    func qartveloAdDidFailToLoad(placementId: String, error: QartveloAdsError) {
        qartveloLabel.text = "Qartvelo failed: \(error)"
        qa("comparison FAILED Qartvelo: \(error)")
    }

    private func measureWhenReady() {
        guard qartveloReady, googleReady, !reported else { return }
        reported = true
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { [weak self] in
            guard let self else { return }
            self.view.layoutIfNeeded()
            let q = self.qartvelo.bounds.size
            let g = self.googleContainer.bounds.size
            self.qartveloLabel.text = String(format: "Qartvelo · %.0f × %.2f pt", q.width, q.height)
            self.googleLabel.text = String(format: "AdMob adaptive · %.0f × %.2f pt", g.width, g.height)
            let difference = g.height - q.height
            self.summary.text = abs(difference) < 0.01 ? "Matching adaptive heights" : String(format: "AdMob is %.2f pt %@\n%.1f%% %@", abs(difference), difference >= 0 ? "taller" : "shorter", abs(difference) / max(q.height, 1) * 100, difference >= 0 ? "more height" : "less height")
            qa(String(format: "comparison QARTVELO width=%.2f height=%.4f ADMOB width=%.2f height=%.4f delta=%.4f", q.width, q.height, g.width, g.height, difference))
            DispatchQueue.main.asyncAfter(deadline: .now() + 1) { qa("SCREENSHOT_NOW comparison"); qa("DONE") }
        }
    }
}

private final class ComparisonAdMobCallback: QartveloFallbackBannerCallback {
    var loaded: (() -> Void)?
    var failed: ((String) -> Void)?
    func onLoaded() { loaded?() }
    func onFailed(_ message: String) { failed?(message) }
    func onImpression() {}
    func onClicked() {}
}
