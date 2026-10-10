import UIKit
import QartveloAds

/// Manual sample: load each format, present full-screen ads on demand, and inspect callbacks.
final class TestAdsViewController: UIViewController, QartveloAdsDelegate {
    private let banner = QartveloAdsBannerView(placementId: AppConfig.bannerPlacement)
    /// Inline banner inside the scrolling content: the biggest ad that fits, up to 250 points tall.
    /// Its own placement: banners of one placement share one ad.
    private let inlineBanner = QartveloAdsBannerView(placementId: AppConfig.inlineBannerPlacement)
    private let bannerCard = AdControlCard(title: "Banner", symbol: "rectangle.bottomthird.inset.filled", detail: "Adaptive banner at the bottom of the screen.")
    private let interstitialCard = AdControlCard(title: "Interstitial", symbol: "rectangle.expand.vertical", detail: "Full-screen image. Close it to return here.")
    private let rewardedCard = AdControlCard(title: "Rewarded", symbol: "gift", detail: "Watch the video to earn a test reward.")
    private let sdkStatus = UILabel()
    private let rewardStatus = UILabel()
    private let eventStatus = UILabel()
    private let loadAll = UIButton(type: .system)
    private var bannerLoaded = false
    private var presentingAd = false
    private var rewardCount = 0
    private var hiddenBannerHeight: NSLayoutConstraint!

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .systemGroupedBackground
        view.tintColor = UIColor(red: 0.53, green: 0.07, blue: 0.22, alpha: 1)
        banner.delegate = self
        banner.rootViewController = self
        banner.isHidden = true
        banner.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(banner)
        hiddenBannerHeight = banner.heightAnchor.constraint(equalToConstant: 0)
        hiddenBannerHeight.isActive = true

        let scroll = UIScrollView()
        scroll.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(scroll)
        let title = label("Qartvelo Ads", style: .largeTitle)
        title.font = .preferredFont(forTextStyle: .largeTitle).withTraits(.traitBold)
        let subtitle = label("Test SDK \(QartveloAds.sdkVersion) · No billing", style: .subheadline)
        subtitle.textColor = .secondaryLabel
        sdkStatus.font = .preferredFont(forTextStyle: .footnote)
        sdkStatus.textColor = .secondaryLabel
        sdkStatus.text = "Connecting…"
        sdkStatus.numberOfLines = 0
        sdkStatus.adjustsFontForContentSizeCategory = true
        var configuration = UIButton.Configuration.filled()
        configuration.title = "Load all test ads"
        configuration.image = UIImage(systemName: "arrow.down.circle")
        configuration.imagePadding = 8
        configuration.cornerStyle = .medium
        loadAll.configuration = configuration
        loadAll.accessibilityIdentifier = "loadAllAds"
        loadAll.isEnabled = false
        loadAll.addAction(UIAction { [weak self] _ in self?.loadAllAds() }, for: .touchUpInside)
        loadAll.heightAnchor.constraint(greaterThanOrEqualToConstant: 48).isActive = true

        configureCards()
        rewardStatus.font = .preferredFont(forTextStyle: .subheadline)
        rewardStatus.adjustsFontForContentSizeCategory = true
        rewardStatus.text = "Test rewards earned: 0"
        rewardStatus.accessibilityIdentifier = "rewardCount"
        rewardStatus.numberOfLines = 0
        eventStatus.font = .preferredFont(forTextStyle: .footnote)
        eventStatus.adjustsFontForContentSizeCategory = true
        eventStatus.textColor = .secondaryLabel
        eventStatus.numberOfLines = 0
        eventStatus.text = "Tap Load, then Show. Full-screen ads open only when you choose."
        eventStatus.accessibilityIdentifier = "latestAdEvent"

        inlineBanner.sizing = .inline
        inlineBanner.inlineMaxHeight = 250
        inlineBanner.rootViewController = self
        let inlineTitle = label("Inline banner (max 250)", style: .headline)
        let stack = UIStackView(arrangedSubviews: [title, subtitle, sdkStatus, loadAll, bannerCard, interstitialCard, rewardedCard, rewardStatus, eventStatus, inlineTitle, inlineBanner])
        stack.axis = .vertical
        stack.spacing = 16
        stack.setCustomSpacing(4, after: title)
        stack.setCustomSpacing(8, after: subtitle)
        stack.translatesAutoresizingMaskIntoConstraints = false
        scroll.addSubview(stack)
        let contentWidth = stack.widthAnchor.constraint(equalTo: scroll.frameLayoutGuide.widthAnchor, constant: -40)
        contentWidth.priority = .defaultHigh
        NSLayoutConstraint.activate([
            banner.leadingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.leadingAnchor),
            banner.trailingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.trailingAnchor),
            banner.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor),
            scroll.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),
            scroll.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            scroll.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            scroll.bottomAnchor.constraint(equalTo: banner.topAnchor, constant: -8),
            stack.topAnchor.constraint(equalTo: scroll.contentLayoutGuide.topAnchor, constant: 20),
            stack.bottomAnchor.constraint(equalTo: scroll.contentLayoutGuide.bottomAnchor, constant: -20),
            stack.centerXAnchor.constraint(equalTo: scroll.frameLayoutGuide.centerXAnchor),
            contentWidth,
            stack.widthAnchor.constraint(lessThanOrEqualToConstant: 640),
        ])
    }

    deinit {
        banner.destroy()
        inlineBanner.destroy()
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        updateButtons()
    }

    func sdkDidInitialize(success: Bool, error: QartveloAdsError?) {
        loadViewIfNeeded()
        sdkStatus.text = success ? (AppConfig.forceNoFill ? "Ready · AdMob test fallback" : "Ready · Qartvelo test ads") : "Offline mode · \(error?.message ?? "Try loading an ad")"
        updateButtons()
    }

    private func configureCards() {
        bannerCard.loadButton.accessibilityIdentifier = "loadBanner"
        bannerCard.showButton.accessibilityIdentifier = "toggleBanner"
        interstitialCard.loadButton.accessibilityIdentifier = "loadInterstitial"
        interstitialCard.showButton.accessibilityIdentifier = "showInterstitial"
        rewardedCard.loadButton.accessibilityIdentifier = "loadRewarded"
        rewardedCard.showButton.accessibilityIdentifier = "showRewarded"
        bannerCard.onLoad = { [weak self] in self?.loadBanner() }
        bannerCard.onShow = { [weak self] in
            guard let self else { return }
            self.banner.isHidden.toggle()
            self.hiddenBannerHeight.isActive = self.banner.isHidden
            self.bannerCard.status.text = self.banner.isHidden ? "Hidden · ready to show" : "Showing adaptive banner"
            self.updateButtons()
        }
        interstitialCard.onLoad = { [weak self] in self?.loadInterstitial() }
        rewardedCard.onLoad = { [weak self] in self?.loadRewarded() }
        interstitialCard.onShow = { [weak self] in self?.showAd(.interstitial) }
        rewardedCard.onShow = { [weak self] in self?.showAd(.rewarded) }
    }

    private func loadAllAds() {
        guard !presentingAd else { return }
        loadBanner()
        loadInterstitial()
        loadRewarded()
        inlineBanner.load()
    }

    private func loadBanner() {
        guard !bannerCard.loading else { return }
        banner.isHidden = false
        hiddenBannerHeight.isActive = false
        if bannerLoaded {
            bannerCard.status.text = "Showing cached adaptive banner"
        } else {
            bannerCard.setLoading()
        }
        view.layoutIfNeeded()
        banner.load()
        updateButtons()
    }

    private func loadInterstitial() {
        guard !interstitialCard.loading else { return }
        interstitialCard.setLoading()
        QartveloAds.loadInterstitial(AppConfig.interstitialPlacement, delegate: self)
        updateButtons()
    }

    private func loadRewarded() {
        guard !rewardedCard.loading else { return }
        rewardedCard.setLoading()
        QartveloAds.loadRewarded(AppConfig.rewardedPlacement, delegate: self)
        updateButtons()
    }

    private func showAd(_ format: QartveloAdFormat) {
        guard !presentingAd, presentedViewController == nil else { return }
        let ready = format == .interstitial ? QartveloAds.isInterstitialReady(AppConfig.interstitialPlacement) : QartveloAds.isRewardedReady(AppConfig.rewardedPlacement)
        guard ready, card(for: format).ready else {
            card(for: format).status.text = "Ad expired. Tap Load to try again."
            updateButtons()
            return
        }
        presentingAd = true
        card(for: format).ready = false
        card(for: format).status.text = "Opening…"
        updateButtons()
        if format == .interstitial {
            QartveloAds.showInterstitial(AppConfig.interstitialPlacement, from: self, delegate: self)
        } else {
            QartveloAds.showRewarded(AppConfig.rewardedPlacement, from: self, delegate: self)
        }
    }

    private func updateButtons() {
        let available = QartveloAds.isInitialized && !presentingAd
        let cards = [bannerCard, interstitialCard, rewardedCard]
        loadAll.isEnabled = available && !cards.contains { $0.loading }
        for card in cards { card.loadButton.isEnabled = available && !card.loading }
        bannerCard.showButton.isEnabled = available && bannerLoaded
        bannerCard.showButton.configuration?.title = banner.isHidden ? "Show" : "Hide"
        bannerCard.showButton.accessibilityLabel = banner.isHidden ? "Show banner ad" : "Hide banner ad"
        interstitialCard.showButton.isEnabled = available && interstitialCard.ready && QartveloAds.isInterstitialReady(AppConfig.interstitialPlacement)
        rewardedCard.showButton.isEnabled = available && rewardedCard.ready && QartveloAds.isRewardedReady(AppConfig.rewardedPlacement)
    }

    private func card(for format: QartveloAdFormat) -> AdControlCard {
        switch format {
        case .banner: return bannerCard
        case .interstitial: return interstitialCard
        case .rewarded: return rewardedCard
        }
    }

    private func card(for placement: String) -> AdControlCard {
        if placement == AppConfig.interstitialPlacement { return interstitialCard }
        if placement == AppConfig.rewardedPlacement { return rewardedCard }
        return bannerCard
    }

    private func record(_ message: String) {
        qa(message)
        eventStatus.text = message
    }

    func qartveloAdDidLoad(_ info: QartveloAdsAdInfo) {
        let card = card(for: info.format)
        card.loading = false
        card.ready = true
        card.status.text = "Ready · \(info.source == .qartvelo ? "Qartvelo" : "AdMob") test ad"
        if info.format == .banner { bannerLoaded = true }
        record("Loaded \(info.format) · \(info.source)")
        updateButtons()
    }

    func qartveloAdDidFailToLoad(placementId: String, error: QartveloAdsError) {
        let card = card(for: placementId)
        card.loading = false
        card.ready = false
        card.status.text = "\(error.message) · Tap Load to retry."
        if placementId != AppConfig.bannerPlacement { presentingAd = false }
        record("Failed \(placementId): \(error.code)")
        updateButtons()
    }

    func qartveloAdDidShow(_ info: QartveloAdsAdInfo) {
        card(for: info.format).status.text = "Showing · \(info.source)"
        record("Shown \(info.format) · \(info.source)")
    }

    func qartveloAdDidRecordImpression(_ info: QartveloAdsAdInfo) {
        record("Displayed \(info.format) · test callback, no billing")
    }

    func qartveloAdDidDismiss(_ info: QartveloAdsAdInfo) {
        presentingAd = false
        card(for: info.format).status.text = "Closed · Load another test ad"
        record("Closed \(info.format)")
        updateButtons()
    }

    func qartveloAd(_ info: QartveloAdsAdInfo, didEarnReward reward: QartveloAdsReward) {
        rewardCount += reward.amount
        rewardStatus.text = "Test rewards earned: \(rewardCount) · \(reward.type)"
        record("Reward earned: \(reward.amount) \(reward.type)")
    }

    func qartveloAdDidStartFallback(placementId: String, format: QartveloAdFormat, reason: String) {
        card(for: format).status.text = "Loading AdMob test fallback…"
        record("Fallback \(format): \(reason)")
    }

    func qartveloAdNoAdAvailable(placementId: String, format: QartveloAdFormat) {
        card(for: format).status.text = "No ad available. Tap Load to try again."
        card(for: format).ready = false
        presentingAd = false
        updateButtons()
    }
}

private final class AdControlCard: UIView {
    let status = UILabel()
    let loadButton = UIButton(type: .system)
    let showButton = UIButton(type: .system)
    var loading = false
    var ready = false
    var onLoad: (() -> Void)?
    var onShow: (() -> Void)?

    init(title: String, symbol: String, detail: String) {
        super.init(frame: .zero)
        backgroundColor = .secondarySystemGroupedBackground
        layer.cornerRadius = 16
        let heading = label(title, style: .headline)
        let icon = UIImageView(image: UIImage(systemName: symbol))
        icon.contentMode = .scaleAspectFit
        icon.widthAnchor.constraint(equalToConstant: 22).isActive = true
        let header = UIStackView(arrangedSubviews: [icon, heading])
        header.spacing = 10
        let description = label(detail, style: .footnote)
        description.textColor = .secondaryLabel
        status.font = .preferredFont(forTextStyle: .footnote)
        status.adjustsFontForContentSizeCategory = true
        status.numberOfLines = 0
        status.text = "Not loaded"
        status.textColor = .secondaryLabel
        for (button, text) in [(loadButton, "Load"), (showButton, "Show")] {
            var config = UIButton.Configuration.tinted()
            config.title = text
            config.cornerStyle = .medium
            button.configuration = config
            button.isEnabled = false
            button.heightAnchor.constraint(greaterThanOrEqualToConstant: 44).isActive = true
            button.accessibilityLabel = "\(text) \(title.lowercased()) ad"
        }
        loadButton.addAction(UIAction { [weak self] _ in self?.onLoad?() }, for: .touchUpInside)
        showButton.addAction(UIAction { [weak self] _ in self?.onShow?() }, for: .touchUpInside)
        let buttons = UIStackView(arrangedSubviews: [loadButton, showButton])
        buttons.spacing = 12
        buttons.distribution = .fillEqually
        let stack = UIStackView(arrangedSubviews: [header, description, status, buttons])
        stack.axis = .vertical
        stack.spacing = 8
        stack.translatesAutoresizingMaskIntoConstraints = false
        addSubview(stack)
        NSLayoutConstraint.activate([
            stack.topAnchor.constraint(equalTo: topAnchor, constant: 16),
            stack.bottomAnchor.constraint(equalTo: bottomAnchor, constant: -16),
            stack.leadingAnchor.constraint(equalTo: leadingAnchor, constant: 16),
            stack.trailingAnchor.constraint(equalTo: trailingAnchor, constant: -16),
        ])
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError("init(coder:) is not supported") }

    func setLoading() {
        loading = true
        ready = false
        status.text = "Loading…"
    }
}

private func label(_ text: String, style: UIFont.TextStyle) -> UILabel {
    let label = UILabel()
    label.text = text
    label.font = .preferredFont(forTextStyle: style)
    label.adjustsFontForContentSizeCategory = true
    label.numberOfLines = 0
    return label
}

private extension UIFont {
    func withTraits(_ traits: UIFontDescriptor.SymbolicTraits) -> UIFont {
        guard let descriptor = fontDescriptor.withSymbolicTraits(traits) else { return self }
        return UIFont(descriptor: descriptor, size: 0)
    }
}
