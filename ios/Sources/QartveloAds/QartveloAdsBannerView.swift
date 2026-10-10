import UIKit

/// Banner container. Set `placementId`, optionally a `delegate`, add it to your layout and call
/// `load()`. The view renders a Qartvelo Ads banner, or your own AdMob banner through the optional
/// `QartveloAdsAdMob` adapter when Qartvelo Ads has no fill or fails.
///
/// Banners are owned by a per-placement controller: re-creating this view (navigation, cell reuse,
/// React Native re-renders) and calling `load()` again reuses the loaded banner instead of requesting a
/// new one. Refresh pauses while the view is not in a window or is hidden. Call `destroy()` when the
/// view is permanently removed.
///
/// The view sizes itself through `intrinsicContentSize` (zero height until an ad is loaded); with
/// Auto Layout, pin its leading and trailing edges and leave the height to the ad.
@objc public final class QartveloAdsBannerView: UIView {
    @objc public var placementId: String?
    @objc public weak var delegate: QartveloAdsDelegate?
    /// Presents the fallback network's click-through screens. Defaults to the nearest view controller.
    @objc public weak var rootViewController: UIViewController?
    /// Compact anchored sizing by default. Set false for an inline creative such as 300x250.
    @objc public var usesAdaptiveSize: Bool = true {
        didSet { setNeedsLayout() }
    }

    /// `.anchored` (default) or `.inline` for banners inside scrolling content. Inline wins over
    /// `usesAdaptiveSize`. Set it before `load()`.
    @objc public var sizing: QartveloBannerSizing = .anchored {
        didSet { setNeedsLayout() }
    }

    /// The most an inline banner may be tall, in points (default 250, at least 32).
    @objc public var inlineMaxHeight: CGFloat = 250 {
        didSet { setNeedsLayout() }
    }

    /// Google's inline adaptive minimum.
    static let minInlineHeight: CGFloat = 32

    private var controller: BannerController?
    private var contentSize: CGSize = .zero
    private var contentWidthConstraint: NSLayoutConstraint?
    private var contentHeightConstraint: NSLayoutConstraint?
    private var appActive = true
    private var observers: [NSObjectProtocol] = []
    private(set) weak var renderedContent: AnyObject?

    @objc public convenience init(placementId: String) {
        self.init(frame: .zero)
        self.placementId = placementId
    }

    public override init(frame: CGRect) {
        super.init(frame: frame)
        setUp()
    }

    public required init?(coder: NSCoder) {
        super.init(coder: coder)
        setUp()
    }

    deinit {
        visibilityTimer?.invalidate()
        observers.forEach { NotificationCenter.default.removeObserver($0) }
    }

    private func setUp() {
        clipsToBounds = true
        let center = NotificationCenter.default
        observers.append(center.addObserver(forName: UIApplication.didEnterBackgroundNotification, object: nil, queue: .main) { [weak self] _ in
            self?.appActive = false
            self?.notifyVisibility()
        })
        observers.append(center.addObserver(forName: UIApplication.willEnterForegroundNotification, object: nil, queue: .main) { [weak self] _ in
            self?.appActive = true
            self?.notifyVisibility()
        })
    }

    /// Idempotent: repeated calls on the same view never trigger extra requests.
    @objc public func load() {
        Main.run { [self] in
            guard let id = placementId?.trimmingCharacters(in: .whitespacesAndNewlines), !id.isEmpty else {
                fail("", QartveloAdsError(.invalidPlacement, "placementId is not set"))
                return
            }
            guard let engine = QartveloAds.engine() else {
                fail(id, QartveloAdsError(.notInitialized, "Call QartveloAds.initialize() before loading banners"))
                return
            }
            let next = engine.banner(id)
            if let previous = controller, previous !== next { previous.detach(self) }
            controller = next
            next.attach(self)
            updateVisibilityTimer()
        }
    }

    /// Releases this view from its placement controller; the loaded banner stays cached for reuse.
    @objc public func destroy() {
        Main.run { [self] in
            controller?.detach(self)
            controller = nil
            updateVisibilityTimer()
            clear()
        }
    }

    public override var intrinsicContentSize: CGSize {
        CGSize(width: contentSize.width > 0 ? contentSize.width : UIView.noIntrinsicMetric, height: contentSize.height)
    }

    public override func didMoveToWindow() {
        super.didMoveToWindow()
        updateVisibilityTimer()
        notifyVisibility()
    }

    public override func layoutSubviews() {
        super.layoutSubviews()
        controller?.onLayoutChanged(self)
    }

    public override var isHidden: Bool {
        didSet { notifyVisibility() }
    }

    public override var alpha: CGFloat {
        didSet { notifyVisibility() }
    }

    // MARK: - Internal

    var availableBannerWidth: CGFloat {
        bounds.width > 0 ? bounds.width : (window?.bounds.width ?? UIScreen.main.bounds.width)
    }

    /// The API's existing screen_width field is in pixels on both native SDKs.
    var bannerRequestWidth: Int {
        Int(min(20_000, max(1, floor(availableBannerWidth * (window?.screen.scale ?? UIScreen.main.scale)))))
    }

    var adaptiveBannerSize: CGSize {
        let screenHeight = window?.bounds.height ?? UIScreen.main.bounds.height
        return QartveloAds.engine()?.adaptiveBannerSize(width: availableBannerWidth, screenHeight: screenHeight)
            ?? AdaptiveBannerLayout.size(width: availableBannerWidth, screenHeight: screenHeight)
    }

    var clampedInlineMaxHeight: CGFloat {
        max(Self.minInlineHeight, inlineMaxHeight.isFinite ? inlineMaxHeight : 250)
    }

    /// Inline banners: the max height in pixels, like `screen_width`.
    var bannerRequestMaxHeight: Int? {
        guard sizing == .inline else { return nil }
        return Int(min(20_000, ceil(clampedInlineMaxHeight * (window?.screen.scale ?? UIScreen.main.scale))))
    }

    var bannerRequestHeight: Int? {
        guard usesAdaptiveSize, sizing == .anchored else { return nil }
        return Int(ceil(adaptiveBannerSize.height * (window?.screen.scale ?? UIScreen.main.scale)))
    }

    /// In a window, not hidden, and at least half on screen: inside the window and every clipping
    /// ancestor (a list or scroll view laying the banner out below the fold does not count).
    var isVisibleForAds: Bool {
        guard let window = window, appActive, bounds.width > 0, bounds.height > 0 else { return false }
        var visible = convert(bounds, to: nil).intersection(window.bounds)
        var current: UIView? = self
        while let view = current {
            if view.isHidden || view.alpha < 0.01 { return false }
            if view !== self, view.clipsToBounds { visible = visible.intersection(view.convert(view.bounds, to: nil)) }
            current = view.superview
        }
        guard !visible.isNull else { return false }
        return visible.width * visible.height * 2 >= bounds.width * bounds.height
    }

    /// Scrolling moves the banner on or off screen without telling it: re-check a few times a
    /// second while it is in a window.
    private var visibilityTimer: Timer?

    private func updateVisibilityTimer() {
        visibilityTimer?.invalidate()
        visibilityTimer = nil
        guard window != nil, controller != nil else { return }
        let timer = Timer(timeInterval: 0.25, repeats: true) { [weak self] _ in self?.notifyVisibility() }
        RunLoop.main.add(timer, forMode: .common)
        visibilityTimer = timer
    }

    var hostViewController: UIViewController? {
        if let explicit = rootViewController { return explicit }
        var responder: UIResponder? = self
        while let next = responder?.next {
            if let viewController = next as? UIViewController { return viewController }
            responder = next
        }
        return window?.rootViewController
    }

    func show(_ subview: UIView, size: CGSize, content: AnyObject) {
        clear()
        subview.translatesAutoresizingMaskIntoConstraints = false
        addSubview(subview)
        let width = subview.widthAnchor.constraint(equalToConstant: size.width)
        let height = subview.heightAnchor.constraint(equalToConstant: size.height)
        NSLayoutConstraint.activate([
            subview.centerXAnchor.constraint(equalTo: centerXAnchor),
            subview.centerYAnchor.constraint(equalTo: centerYAnchor),
            width,
            height,
        ])
        contentWidthConstraint = width
        contentHeightConstraint = height
        renderedContent = content
        contentSize = size
        invalidateIntrinsicContentSize()
    }

    func updateContentSize(_ size: CGSize) {
        guard contentSize != size else { return }
        contentWidthConstraint?.constant = size.width
        contentHeightConstraint?.constant = size.height
        contentSize = size
        invalidateIntrinsicContentSize()
    }

    func clear() {
        if let width = contentWidthConstraint { width.isActive = false }
        if let height = contentHeightConstraint { height.isActive = false }
        contentWidthConstraint = nil
        contentHeightConstraint = nil
        subviews.forEach { $0.removeFromSuperview() }
        renderedContent = nil
        contentSize = .zero
        invalidateIntrinsicContentSize()
    }

    private func notifyVisibility() {
        controller?.onVisibilityChanged(self, visible: isVisibleForAds)
    }

    private func fail(_ placementId: String, _ error: QartveloAdsError) {
        Log.e("Banner load failed: \(error.message)")
        Listeners.emit(delegate) { $0.qartveloAdDidFailToLoad?(placementId: placementId, error: error) }
    }
}
