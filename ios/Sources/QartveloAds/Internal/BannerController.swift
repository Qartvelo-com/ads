import UIKit

/// One banner per placement, shared by every `QartveloAdsBannerView` for that placement: a view that is
/// re-created (navigation, table cell reuse, React Native re-renders) re-attaches to the loaded banner
/// instead of requesting a new one. Main thread only.
///
/// Refresh never happens faster than `banner_refresh_seconds` (measured between request starts) and
/// only while the host view is in a window and visible. The host is held weakly.
final class BannerController {
    private static let minBannerWidth: CGFloat = 32

    private enum Content {
        case qartvelo(QartveloContent)
        case fallback(QartveloFallbackBanner)
    }

    /// An image (`image`) or HTML5 (`html5`) Qartvelo Ads banner.
    private final class QartveloContent {
        let ad: ServedAd
        let image: UIImage?
        let html5: Html5Surface?
        var impressed = false
        var clicked = false

        init(ad: ServedAd, image: UIImage?, html5: Html5Surface? = nil) {
            self.ad = ad
            self.image = image
            self.html5 = html5
        }
    }

    /// An HTML5 banner loading hidden in the slot (it needs a real size to become ready); swapped
    /// in when ready, dropped (and the fallback used) when it fails or times out.
    private final class Staged {
        let ad: ServedAd
        let surface: Html5Surface
        let container = UIView()

        init(ad: ServedAd, surface: Html5Surface) {
            self.ad = ad
            self.surface = surface
            container.alpha = 0
            container.isUserInteractionEnabled = false
            surface.view.frame = container.bounds
            surface.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
            container.addSubview(surface.view)
        }
    }

    private unowned let engine: Engine
    let placementId: String
    private weak var hostView: QartveloAdsBannerView?
    private var hostVisible = false
    private var content: Content?
    private var loading = false
    private var lastRequestAt = Int64.min / 2
    private var pendingFallback: QartveloFallbackBanner?
    private var fallbackWidth: CGFloat = 0
    private var refreshTask: DispatchWorkItem?
    private var staged: Staged?
    /// Sizing of the request in flight, and of the content it produced (an anchored ad never fills an inline slot).
    private var requestInline = false
    private var contentInline = false

    init(engine: Engine, placementId: String) {
        self.engine = engine
        self.placementId = placementId
    }

    // MARK: - Host lifecycle

    func attach(_ view: QartveloAdsBannerView) {
        if content != nil, contentInline != (view.sizing == .inline) {
            // Loaded for the other sizing (anchored or inline): drop it and ask again now.
            if let host = hostView { unrender(host) }
            replaceContent(nil)
            lastRequestAt = Int64.min / 2
        }
        if let previous = hostView {
            if previous === view {
                if content == nil && !loading && isDue() { startLoad() }
                return
            }
            unrender(previous)
        }
        hostView = view
        hostVisible = view.isVisibleForAds
        if let current = content, isDisplayable(current) {
            render(view, current)
            let info = infoFor(current)
            // Re-created view: tell its own delegate the ad is ready, without a new request. Global
            // observers already saw this load, so they are not notified again.
            Listeners.emit([view.delegate], includeGlobal: false) { $0.qartveloAdDidLoad?(info) }
            onShownMaybe()
        } else {
            if content != nil { replaceContent(nil) }
            if !loading && isDue() { startLoad() }
        }
        restage()
        schedule()
    }

    func detach(_ view: QartveloAdsBannerView) {
        guard hostView === view else { return }
        unrender(view)
        hostView = nil
        hostVisible = false
        schedule()
    }

    func onVisibilityChanged(_ view: QartveloAdsBannerView, visible: Bool) {
        guard hostView === view else { return }
        if visible, let current = content, isDisplayable(current) { render(view, current) }
        guard hostVisible != visible else { return }
        hostVisible = visible
        if case .qartvelo(let item)? = content, let html5 = item.html5 { visible ? html5.resume() : html5.pause() }
        if visible { onShownMaybe() }
        schedule()
    }

    func onLayoutChanged(_ view: QartveloAdsBannerView) {
        guard hostView === view, let current = content else { return }
        render(view, current)
    }

    // MARK: - Refresh

    private func refreshMs() -> Int64 {
        let seconds = engine.placement(placementId)?.bannerRefreshSeconds ?? RemoteConfig.defaultBannerRefreshSeconds
        return Int64(max(seconds, RemoteConfig.minBannerRefreshSeconds)) * 1000
    }

    private func isDue() -> Bool {
        Clock.now() - lastRequestAt >= refreshMs()
    }

    private func schedule() {
        refreshTask?.cancel()
        refreshTask = nil
        guard hostVisible, !loading, hostView != nil else { return }
        let delay = lastRequestAt + refreshMs() - Clock.now()
        Log.d("Banner '\(placementId)' refresh in \(max(0, delay) / 1000) s")
        refreshTask = Main.postDelayed(delay) { [weak self] in self?.refreshIfDue() }
    }

    private func refreshIfDue() {
        if hostVisible && !loading && hostView != nil && isDue() { startLoad() } else { schedule() }
    }

    // MARK: - Loading

    private func startLoad() {
        loading = true
        lastRequestAt = Clock.now()
        refreshTask?.cancel()
        engine.whenReady { [weak self] in self?.loadNow() }
    }

    private func loadNow() {
        requestInline = hostView?.sizing == .inline
        let placement = engine.placement(placementId)
        engine.checkPlacement(placementId, .banner)
        if let format = placement?.format, format != .banner {
            loading = false
            let placementId = self.placementId
            let error = QartveloAdsError(.invalidPlacement, "Placement '\(placementId)' is not a banner placement")
            emit { $0.qartveloAdDidFailToLoad?(placementId: placementId, error: error) }
            return
        }
        // A stale "Qartvelo Ads off" config is re-checked with the backend, so a kill switch turned back
        // on reaches a running app (/ads/request applies every switch itself).
        let refreshing = engine.refreshConfigIfStale(placement)
        if !refreshing && !engine.qartveloEnabled(placement) {
            onFailed(QartveloAdsFailure(reason: QartveloAdsFailure.disabled, error: QartveloAdsError(.noFill, "Qartvelo Ads serving is disabled for this placement")))
            return
        }
        engine.fetchAd(placementId, format: .banner, timeoutMs: engine.effectiveTimeoutMs(placement), bannerWidth: hostView?.bannerRequestWidth, bannerHeight: hostView?.bannerRequestHeight, bannerMaxHeight: hostView?.bannerRequestMaxHeight) { [weak self] outcome in
            guard let self = self else { return }
            switch outcome {
            case .success(let ad): ad.creativeType == .html5 ? self.stageHtml5(ad) : self.decode(ad)
            case .failure(let failure): self.onFailed(failure)
            }
        }
    }

    private func decode(_ ad: ServedAd) {
        guard let file = ad.file else {
            onFailed(creativeFailure())
            return
        }
        let screen = UIScreen.main
        let maxPixels = max(screen.bounds.width, screen.bounds.height) * screen.scale
        engine.io.async { [weak self] in
            let image = AdViewController.decode(file, maxPixels: maxPixels)
            Main.post {
                guard let self = self else { return }
                if let image = image { self.onReady(ad, image: image) } else { self.onFailed(self.creativeFailure()) }
            }
        }
    }

    private func stageHtml5(_ ad: ServedAd) {
        dropStaged()
        guard let surface = Html5AdView.create(ad) else {
            onFailed(creativeFailure())
            return
        }
        let stage = Staged(ad: ad, surface: surface)
        staged = stage
        restage()
        surface.load(
            onReady: { [weak self, weak stage] in
                guard let self = self, let stage = stage, self.staged === stage else { return }
                self.onHtml5Ready(stage)
            },
            onFailed: { [weak self, weak stage] _ in
                guard let self = self, let stage = stage, self.staged === stage else { return }
                self.dropStaged()
                self.onFailed(self.creativeFailure())
            },
            onClick: { [weak self, weak surface] in
                guard let self = self, case .qartvelo(let item)? = self.content, let surface = surface, item.html5 === surface else { return }
                self.onClick(item)
            }
        )
    }

    private func onHtml5Ready(_ stage: Staged) {
        staged = nil
        stage.container.removeFromSuperview()
        stage.surface.view.removeFromSuperview()
        Log.d("Qartvelo Ads HTML5 banner ready for '\(placementId)'")
        loading = false
        let next = Content.qartvelo(QartveloContent(ad: stage.ad, image: nil, html5: stage.surface))
        replaceContent(next)
        if let view = hostView { render(view, next) }
        if !hostVisible { stage.surface.pause() }
        let info = infoFor(next)
        emit { $0.qartveloAdDidLoad?(info) }
        onShownMaybe()
        schedule()
    }

    /// Puts a loading HTML5 banner (back) into the host, sized like the ad will show.
    private func restage() {
        guard let stage = staged, let view = hostView else { return }
        if stage.container.superview !== view { view.addSubview(stage.container) }
        stage.container.frame = CGRect(origin: .zero, size: slotSize(view, ad: stage.ad))
    }

    private func dropStaged() {
        guard let stage = staged else { return }
        staged = nil
        stage.container.removeFromSuperview()
        stage.surface.destroy()
    }

    private func creativeFailure() -> QartveloAdsFailure {
        QartveloAdsFailure(reason: QartveloAdsFailure.creativeFailed, error: QartveloAdsError(.creativeFailed, "Qartvelo Ads banner creative could not be decoded"))
    }

    private func onReady(_ ad: ServedAd, image: UIImage) {
        Log.d("Qartvelo Ads banner ready for '\(placementId)'")
        loading = false
        let next = Content.qartvelo(QartveloContent(ad: ad, image: image))
        replaceContent(next)
        if let view = hostView { render(view, next) }
        let info = infoFor(next)
        emit { $0.qartveloAdDidLoad?(info) }
        onShownMaybe()
        schedule()
    }

    private func onFailed(_ failure: QartveloAdsFailure) {
        let unit = failure.serverFallback == "none" ? nil : engine.fallbackUnit(placementId, placement: engine.placement(placementId))
        if unit != nil, case .fallback? = content, abs(fallbackWidth - (hostView?.availableBannerWidth ?? UIScreen.main.bounds.width)) < 0.5 {
            // The fallback banner is already on screen and refreshes itself; keep it.
            loading = false
            schedule()
            return
        }
        guard let fallbackUnit = unit else {
            loading = false
            if content == nil { emitNoAd(failure) }
            schedule()
            return
        }
        engine.reportFallback(placementId, reason: failure.reason)
        let placementId = self.placementId
        emit { $0.qartveloAdDidStartFallback?(placementId: placementId, format: .banner, reason: failure.reason) }
        loadFallback(fallbackUnit, failure: failure)
    }

    private func loadFallback(_ unit: String, failure: QartveloAdsFailure) {
        guard let adapter = engine.fallbackAdapter else {
            onFallbackFailed(nil, failure: failure)
            return
        }
        let view = hostView
        let width = view?.availableBannerWidth ?? UIScreen.main.bounds.width
        let info = QartveloAdsAdInfo(placementId: placementId, format: .banner, source: .admob)
        let callback = BannerRelay()
        let slotWidth = max(width, Self.minBannerWidth)
        let inlineBanner = view?.sizing == .inline
            ? adapter.createInlineBanner(
                placementId: placementId,
                adUnitId: unit,
                width: slotWidth,
                maxHeight: view?.clampedInlineMaxHeight ?? 250,
                rootViewController: view?.hostViewController,
                callback: callback
            )
            : nil
        let banner = inlineBanner ?? adapter.createBanner(
            placementId: placementId,
            adUnitId: unit,
            width: slotWidth,
            rootViewController: view?.hostViewController,
            callback: callback
        )
        pendingFallback = banner
        callback.onSettled = { [weak self, weak banner] success, message in
            guard let self = self, let banner = banner else { return }
            if success {
                self.fallbackWidth = width
                self.onFallbackLoaded(banner)
            } else {
                Log.i("Fallback banner failed for '\(self.placementId)': \(message ?? "")")
                self.onFallbackFailed(banner, failure: failure)
            }
        }
        callback.onImpressionOnce = { [weak self] in
            self?.emit { $0.qartveloAdDidShow?(info) }
            self?.emit { $0.qartveloAdDidRecordImpression?(info) }
        }
        callback.onClickOnce = { [weak self] in self?.emit { $0.qartveloAdDidClick?(info) } }
        callback.release()
    }

    private func onFallbackLoaded(_ banner: QartveloFallbackBanner) {
        guard pendingFallback === banner else { return }
        pendingFallback = nil
        loading = false
        let next = Content.fallback(banner)
        replaceContent(next)
        if let view = hostView, view.window != nil { render(view, next) }
        let info = infoFor(next)
        emit { $0.qartveloAdDidLoad?(info) }
        schedule()
    }

    private func onFallbackFailed(_ banner: QartveloFallbackBanner?, failure: QartveloAdsFailure) {
        if let banner = banner, pendingFallback !== banner { return }
        pendingFallback = nil
        loading = false
        banner?.destroy()
        if content == nil { emitNoAd(failure) }
        schedule()
    }

    private func emitNoAd(_ failure: QartveloAdsFailure) {
        let placementId = self.placementId
        emit { $0.qartveloAdNoAdAvailable?(placementId: placementId, format: .banner) }
        emit { $0.qartveloAdDidFailToLoad?(placementId: placementId, error: failure.error) }
    }

    // MARK: - Display

    /// A Qartvelo Ads banner never shown before must still be within its token lifetime.
    private func isDisplayable(_ content: Content) -> Bool {
        switch content {
        case .qartvelo(let item): return item.impressed || Clock.now() < item.ad.expiresAt - ServedAd.expiryMarginMs
        case .fallback: return true
        }
    }

    /// Records the Qartvelo Ads impression the first time the banner is actually visible.
    private func onShownMaybe() {
        guard case .qartvelo(let item)? = content, let view = hostView else { return }
        guard !item.impressed, hostVisible, view.renderedContent != nil else { return }
        if !isDisplayable(.qartvelo(item)) {
            Log.i("Banner for '\(placementId)' expired before it was seen; discarding")
            replaceContent(nil)
            unrender(view)
            schedule()
            return
        }
        item.impressed = true
        if !item.ad.localTest { engine.events.enqueue(.impression, ad: item.ad) }
        let info = infoFor(.qartvelo(item))
        emit { $0.qartveloAdDidShow?(info) }
        emit { $0.qartveloAdDidRecordImpression?(info) }
    }

    private func onClick(_ item: QartveloContent) {
        guard item.impressed, let url = item.ad.clickURL else { return }
        if !item.clicked {
            item.clicked = true
            engine.events.enqueue(.click, ad: item.ad)
            let info = infoFor(.qartvelo(item))
            emit { $0.qartveloAdDidClick?(info) }
        }
        ClickOpener.open(url)
    }

    private func render(_ view: QartveloAdsBannerView, _ content: Content) {
        switch content {
        case .qartvelo(let item):
            let size = slotSize(view, ad: item.ad)
            if view.renderedContent === item {
                view.updateContentSize(size)
                return
            }
            let creative: BannerCreativeView
            if let html5 = item.html5 {
                // Taps reach the ad through the web view; it reports clicks itself.
                html5.view.removeFromSuperview()
                creative = BannerCreativeView(content: html5.view, test: item.ad.test, onTap: nil)
            } else {
                let image = UIImageView(image: item.image)
                image.contentMode = .scaleAspectFit
                image.accessibilityLabel = Strings.ad
                creative = BannerCreativeView(content: image, test: item.ad.test) { [weak self, weak item] in
                    guard let self = self, let item = item else { return }
                    self.onClick(item)
                }
            }
            view.show(creative, size: size, content: item)
            restage()
        case .fallback(let banner):
            if view.renderedContent === banner { return }
            banner.setRootViewController(view.hostViewController)
            let adView = banner.view
            adView.removeFromSuperview()
            let size = adView.intrinsicContentSize.width > 0 ? adView.intrinsicContentSize : adView.frame.size
            view.show(adView, size: size, content: banner)
        }
    }

    /// Where a Qartvelo Ads creative shows. Inline: the ad's fitted size, centered, no space reserved
    /// beyond it. Anchored: the adaptive slot (an image is fitted into it; HTML5 lays itself out in
    /// it). Legacy (`usesAdaptiveSize = false`): the creative's own size.
    private func slotSize(_ view: QartveloAdsBannerView, ad: ServedAd) -> CGSize {
        let available = view.availableBannerWidth
        if view.sizing == .inline {
            return InlineBannerFit.size(width: CGFloat(max(ad.width, 1)), height: CGFloat(max(ad.height, 1)),
                                        slotWidth: available, maxHeight: view.clampedInlineMaxHeight)
        }
        if view.usesAdaptiveSize { return view.adaptiveBannerSize }
        let width = min(CGFloat(max(ad.width, 1)), available)
        return CGSize(width: width, height: width * CGFloat(max(ad.height, 1)) / CGFloat(max(ad.width, 1)))
    }

    private func unrender(_ view: QartveloAdsBannerView) {
        view.clear()
    }

    private func replaceContent(_ next: Content?) {
        let old = content
        content = next
        if next != nil { contentInline = requestInline }
        if case .qartvelo(let oldItem)? = old, let html5 = oldItem.html5 {
            if case .qartvelo(let newItem)? = next, newItem === oldItem { return }
            html5.destroy()
        }
        if case .fallback(let oldBanner)? = old {
            if case .fallback(let newBanner)? = next, newBanner === oldBanner { return }
            oldBanner.view.removeFromSuperview()
            oldBanner.destroy()
        }
    }

    private func infoFor(_ content: Content) -> QartveloAdsAdInfo {
        switch content {
        case .qartvelo(let item):
            return QartveloAdsAdInfo(placementId: placementId, format: .banner, source: .qartvelo, campaignId: item.ad.campaignId, creativeId: item.ad.creativeId)
        case .fallback:
            return QartveloAdsAdInfo(placementId: placementId, format: .banner, source: .admob)
        }
    }

    private func emit(_ event: @escaping (QartveloAdsDelegate) -> Void) {
        Listeners.emit([hostView?.delegate], event)
    }
}

/// Moves fallback banner callbacks to the main thread: one settle (loaded or failed), one impression
/// and one click. Callbacks that arrive before the handlers are set are replayed once they are.
final class BannerRelay: QartveloFallbackBannerCallback {
    var onSettled: ((Bool, String?) -> Void)?
    var onImpressionOnce: (() -> Void)?
    var onClickOnce: (() -> Void)?

    private let lock = NSLock()
    private var held = true
    private var settled: (Bool, String?)?
    private var settledDelivered = false
    private var impression = false
    private var impressionDelivered = false
    private var clicked = false
    private var clickDelivered = false

    func onLoaded() { settle(true, nil) }
    func onFailed(_ message: String) { settle(false, message) }

    func onImpression() {
        lock.lock()
        guard !impression else {
            lock.unlock()
            return
        }
        impression = true
        lock.unlock()
        Main.post { self.deliver() }
    }

    func onClicked() {
        lock.lock()
        guard !clicked else {
            lock.unlock()
            return
        }
        clicked = true
        lock.unlock()
        Main.post { self.deliver() }
    }

    /// Main thread: the handlers are set, deliver anything that already happened.
    func release() {
        held = false
        deliver()
    }

    private func settle(_ success: Bool, _ message: String?) {
        lock.lock()
        guard settled == nil else {
            lock.unlock()
            return
        }
        settled = (success, message)
        lock.unlock()
        Main.post { self.deliver() }
    }

    /// Main thread.
    private func deliver() {
        guard !held else { return }
        lock.lock()
        let settle = settledDelivered ? nil : settled
        if settle != nil { settledDelivered = true }
        let fireImpression = impression && !impressionDelivered
        if fireImpression { impressionDelivered = true }
        let fireClick = clicked && !clickDelivered
        if fireClick { clickDelivered = true }
        lock.unlock()
        if let settle = settle { onSettled?(settle.0, settle.1) }
        if fireImpression { onImpressionOnce?() }
        if fireClick { onClickOnce?() }
    }
}

/// A Qartvelo Ads banner creative (an image or an HTML5 web view) with its "Ad" badge.
final class BannerCreativeView: UIView {
    private let onTap: (() -> Void)?

    /// `onTap`: nil when `content` handles taps itself (HTML5).
    init(content imageView: UIView, test: Bool, onTap: (() -> Void)?) {
        self.onTap = onTap
        super.init(frame: .zero)
        imageView.translatesAutoresizingMaskIntoConstraints = false
        imageView.isUserInteractionEnabled = true
        if onTap != nil { imageView.addGestureRecognizer(UITapGestureRecognizer(target: self, action: #selector(tapped))) }
        addSubview(imageView)

        let badge = PillLabel()
        badge.text = test ? Strings.testAd : Strings.ad
        badge.font = .systemFont(ofSize: 9, weight: .medium)
        badge.insets = UIEdgeInsets(top: 0, left: 4, bottom: 0, right: 4)
        badge.layer.cornerRadius = 3
        badge.layer.maskedCorners = [.layerMaxXMinYCorner, .layerMaxXMaxYCorner]
        badge.accessibilityLabel = Strings.aboutAds
        badge.accessibilityTraits = .link
        badge.isUserInteractionEnabled = true
        // Opens the Qartvelo Ads website (not the advertiser) and consumes the tap.
        badge.addGestureRecognizer(UITapGestureRecognizer(target: self, action: #selector(badgeTapped)))
        badge.translatesAutoresizingMaskIntoConstraints = false
        addSubview(badge)

        NSLayoutConstraint.activate([
            imageView.leadingAnchor.constraint(equalTo: leadingAnchor),
            imageView.trailingAnchor.constraint(equalTo: trailingAnchor),
            imageView.topAnchor.constraint(equalTo: topAnchor),
            imageView.bottomAnchor.constraint(equalTo: bottomAnchor),
            badge.leadingAnchor.constraint(equalTo: leadingAnchor),
            badge.topAnchor.constraint(equalTo: topAnchor),
        ])
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) is not supported")
    }

    @objc private func tapped() {
        onTap?()
    }

    @objc private func badgeTapped() {
        AboutLink.open()
    }
}
