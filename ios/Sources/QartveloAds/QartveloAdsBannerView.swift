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

    private var controller: BannerController?
    private var contentSize: CGSize = .zero
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
        }
    }

    /// Releases this view from its placement controller; the loaded banner stays cached for reuse.
    @objc public func destroy() {
        Main.run { [self] in
            controller?.detach(self)
            controller = nil
            clear()
        }
    }

    public override var intrinsicContentSize: CGSize {
        CGSize(width: contentSize.width > 0 ? contentSize.width : UIView.noIntrinsicMetric, height: contentSize.height)
    }

    public override func didMoveToWindow() {
        super.didMoveToWindow()
        notifyVisibility()
    }

    public override var isHidden: Bool {
        didSet { notifyVisibility() }
    }

    public override var alpha: CGFloat {
        didSet { notifyVisibility() }
    }

    // MARK: - Internal

    var isVisibleForAds: Bool {
        guard window != nil, appActive else { return false }
        var current: UIView? = self
        while let view = current {
            if view.isHidden || view.alpha < 0.01 { return false }
            current = view.superview
        }
        return true
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
        NSLayoutConstraint.activate([
            subview.centerXAnchor.constraint(equalTo: centerXAnchor),
            subview.centerYAnchor.constraint(equalTo: centerYAnchor),
            subview.widthAnchor.constraint(equalToConstant: size.width),
            subview.heightAnchor.constraint(equalToConstant: size.height),
        ])
        renderedContent = content
        contentSize = size
        invalidateIntrinsicContentSize()
    }

    func clear() {
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
