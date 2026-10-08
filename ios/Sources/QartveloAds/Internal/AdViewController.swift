import AVFoundation
import ImageIO
import UIKit

/// Full-screen renderer for Qartvelo Ads interstitial and rewarded creatives. Event state lives in
/// `ShowSession`; this view controller only draws it.
final class AdViewController: UIViewController {
    static let imageCloseDelayMs: Int64 = 2_000
    static let videoCloseDelayMs: Int64 = 5_000
    static let rewardedCloseDelayMs: Int64 = 2_000
    private static let tickInterval: TimeInterval = 0.25

    private let session: ShowSession
    private let closeButton = UIButton(type: .custom)
    private let badge = PillLabel()
    private let countdown = PillLabel()
    private var imageView: UIImageView?
    private var playerView: PlayerView?
    private var player: AVPlayer?
    private var observations: [NSKeyValueObservation] = []
    private var notificationTokens: [NSObjectProtocol] = []
    private var tickTimer: Timer?
    private var closeTask: DispatchWorkItem?
    private var confirmVisible = false
    private var failing = false
    /// A render failure before the presentation finished waits for it: UIKit ignores a dismissal
    /// while the presentation transition is still running.
    private var appeared = false
    private var failurePending = false
    private var active = true

    init(session: ShowSession) {
        self.session = session
        super.init(nibName: nil, bundle: nil)
        modalPresentationStyle = .fullScreen
        modalTransitionStyle = .crossDissolve
        isModalInPresentation = true
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) is not supported")
    }

    deinit {
        tickTimer?.invalidate()
        notificationTokens.forEach { NotificationCenter.default.removeObserver($0) }
    }

    override var prefersStatusBarHidden: Bool { true }
    override var prefersHomeIndicatorAutoHidden: Bool { true }
    override var supportedInterfaceOrientations: UIInterfaceOrientationMask { .all }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        switch session.ad.creativeType {
        case .image: buildImage()
        case .video: buildVideo()
        }
        buildOverlay()
        observeAppState()
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        appeared = true
        if failurePending {
            failurePending = false
            failRender()
            return
        }
        if session.ad.creativeType == .video, !session.videoCompleted, !confirmVisible, !session.playbackFailed {
            player?.play()
        }
    }

    override func viewDidDisappear(_ animated: Bool) {
        super.viewDidDisappear(animated)
        guard isBeingDismissed || presentingViewController == nil else { return }
        teardown()
        // A render failure reports from the dismissal completion instead, so the fallback can present.
        if !failing { session.close() }
    }

    // MARK: - Layout

    private func buildImage() {
        let imageView = UIImageView()
        imageView.contentMode = .scaleAspectFit
        imageView.accessibilityLabel = Strings.ad
        imageView.translatesAutoresizingMaskIntoConstraints = false
        if session.ad.clickURL != nil {
            imageView.isUserInteractionEnabled = true
            imageView.addGestureRecognizer(UITapGestureRecognizer(target: self, action: #selector(adTapped)))
        }
        view.addSubview(imageView)
        pin(imageView, to: view)
        self.imageView = imageView
        loadImage()
    }

    private func buildVideo() {
        guard let file = session.ad.file else {
            DispatchQueue.main.async { [weak self] in self?.failRender() }
            return
        }
        let playerView = PlayerView()
        playerView.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(playerView)
        pin(playerView, to: view)
        self.playerView = playerView

        let item = AVPlayerItem(url: file)
        let player = AVPlayer(playerItem: item)
        player.actionAtItemEnd = .pause
        playerView.playerLayer.player = player
        playerView.playerLayer.videoGravity = .resizeAspect
        self.player = player

        observations.append(playerView.playerLayer.observe(\.isReadyForDisplay, options: [.initial, .new]) { [weak self] layer, _ in
            guard layer.isReadyForDisplay else { return }
            Main.run { self?.onFirstFrame() }
        })
        observations.append(item.observe(\.status, options: [.new]) { [weak self] item, _ in
            guard item.status == .failed else { return }
            Main.run { self?.onVideoError() }
        })
        let center = NotificationCenter.default
        notificationTokens.append(center.addObserver(forName: .AVPlayerItemDidPlayToEndTime, object: item, queue: .main) { [weak self] _ in
            self?.onVideoCompleted()
        })
        notificationTokens.append(center.addObserver(forName: .AVPlayerItemFailedToPlayToEndTime, object: item, queue: .main) { [weak self] _ in
            self?.onVideoError()
        })
    }

    private func buildOverlay() {
        let guide = view.safeAreaLayoutGuide

        badge.text = session.ad.test ? Strings.testAd : Strings.ad
        badge.font = .systemFont(ofSize: 12, weight: .medium)
        badge.accessibilityLabel = Strings.aboutAds
        badge.accessibilityTraits = .link
        badge.isUserInteractionEnabled = true
        // Opens the Qartvelo Ads website (not the advertiser); not counted as a click.
        badge.addGestureRecognizer(UITapGestureRecognizer(target: self, action: #selector(badgeTapped)))
        add(badge)
        NSLayoutConstraint.activate([
            badge.leadingAnchor.constraint(equalTo: guide.leadingAnchor, constant: 12),
            badge.topAnchor.constraint(equalTo: guide.topAnchor, constant: 16),
        ])

        closeButton.backgroundColor = UIColor.black.withAlphaComponent(0.6)
        closeButton.layer.cornerRadius = 20
        closeButton.tintColor = .white
        let symbol = UIImage.SymbolConfiguration(pointSize: 16, weight: .bold)
        closeButton.setImage(UIImage(systemName: "xmark", withConfiguration: symbol), for: .normal)
        closeButton.accessibilityLabel = Strings.close
        closeButton.isHidden = !(session.videoCompleted || session.playbackFailed)
        closeButton.addTarget(self, action: #selector(closeTapped), for: .touchUpInside)
        add(closeButton)
        NSLayoutConstraint.activate([
            closeButton.widthAnchor.constraint(equalToConstant: 40),
            closeButton.heightAnchor.constraint(equalToConstant: 40),
            closeButton.trailingAnchor.constraint(equalTo: guide.trailingAnchor, constant: -12),
            closeButton.topAnchor.constraint(equalTo: guide.topAnchor, constant: 12),
        ])

        guard session.ad.creativeType == .video else { return }
        countdown.font = .monospacedDigitSystemFont(ofSize: 13, weight: .medium)
        countdown.isHidden = true
        add(countdown)
        NSLayoutConstraint.activate([
            countdown.trailingAnchor.constraint(equalTo: closeButton.leadingAnchor, constant: -12),
            countdown.centerYAnchor.constraint(equalTo: closeButton.centerYAnchor),
        ])

        if session.ad.clickURL != nil {
            let cta = PillLabel()
            cta.text = Strings.learnMore
            cta.font = .systemFont(ofSize: 15, weight: .semibold)
            cta.insets = UIEdgeInsets(top: 10, left: 16, bottom: 10, right: 16)
            cta.accessibilityTraits = .link
            cta.isUserInteractionEnabled = true
            cta.addGestureRecognizer(UITapGestureRecognizer(target: self, action: #selector(adTapped)))
            add(cta)
            NSLayoutConstraint.activate([
                cta.trailingAnchor.constraint(equalTo: guide.trailingAnchor, constant: -16),
                cta.bottomAnchor.constraint(equalTo: guide.bottomAnchor, constant: -24),
            ])
        }
    }

    private func add(_ subview: UIView) {
        subview.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(subview)
    }

    private func pin(_ subview: UIView, to container: UIView) {
        NSLayoutConstraint.activate([
            subview.leadingAnchor.constraint(equalTo: container.leadingAnchor),
            subview.trailingAnchor.constraint(equalTo: container.trailingAnchor),
            subview.topAnchor.constraint(equalTo: container.topAnchor),
            subview.bottomAnchor.constraint(equalTo: container.bottomAnchor),
        ])
    }

    // MARK: - Rendering

    private func loadImage() {
        guard let file = session.ad.file else {
            DispatchQueue.main.async { [weak self] in self?.failRender() }
            return
        }
        let screen = UIScreen.main
        let maxPixels = max(screen.bounds.width, screen.bounds.height) * screen.scale
        session.runInBackground { [weak self] in
            let image = Self.decode(file, maxPixels: maxPixels)
            Main.post {
                guard let self = self, !self.session.closed, !self.failing else { return }
                guard let image = image else {
                    self.failRender()
                    return
                }
                self.imageView?.image = image
                self.session.onRendered()
                self.scheduleClose()
            }
        }
    }

    /// Decodes `file` downsampled to roughly the screen size. Returns nil if undecodable.
    static func decode(_ file: URL, maxPixels: CGFloat) -> UIImage? {
        guard let source = CGImageSourceCreateWithURL(file as CFURL, [kCGImageSourceShouldCache: false] as CFDictionary) else { return nil }
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceShouldCacheImmediately: true,
            kCGImageSourceThumbnailMaxPixelSize: max(1, maxPixels),
        ]
        guard let image = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary) else { return nil }
        return UIImage(cgImage: image)
    }

    private func onFirstFrame() {
        guard !session.closed, !failing, !session.rendered else { return }
        session.onRendered()
        scheduleClose()
        startTicking()
    }

    private func onVideoCompleted() {
        session.onVideoCompleted()
        setCloseVisible()
        updateCountdown()
    }

    private func onVideoError() {
        guard !session.closed, !failing else { return }
        if !session.rendered {
            failRender()
        } else {
            session.onPlaybackFailed()
            setCloseVisible()
            updateCountdown()
        }
    }

    private func scheduleClose() {
        closeTask?.cancel()
        if session.videoCompleted || session.playbackFailed {
            setCloseVisible()
            return
        }
        let delay = closeDelayMs() - (Clock.now() - session.renderedAt)
        if delay <= 0 {
            setCloseVisible()
        } else {
            closeTask = Main.postDelayed(delay) { [weak self] in self?.setCloseVisible() }
        }
    }

    private func closeDelayMs() -> Int64 {
        if session.isRewarded { return Self.rewardedCloseDelayMs }
        return session.ad.creativeType == .video ? Self.videoCloseDelayMs : Self.imageCloseDelayMs
    }

    private func setCloseVisible() {
        closeButton.isHidden = false
    }

    private func startTicking() {
        tickTimer?.invalidate()
        updateCountdown()
        let timer = Timer(timeInterval: Self.tickInterval, repeats: true) { [weak self] _ in self?.updateCountdown() }
        RunLoop.main.add(timer, forMode: .common)
        tickTimer = timer
    }

    private func updateCountdown() {
        if session.videoCompleted {
            if session.isRewarded {
                countdown.text = Strings.rewardEarned
                countdown.isHidden = false
            } else {
                countdown.isHidden = true
            }
            tickTimer?.invalidate()
            return
        }
        guard let player = player, let item = player.currentItem, !session.playbackFailed else {
            countdown.isHidden = true
            return
        }
        let duration = item.duration.seconds
        guard duration.isFinite, duration > 0 else {
            countdown.isHidden = true
            return
        }
        let seconds = Int(ceil(max(0, duration - player.currentTime().seconds)))
        countdown.text = session.isRewarded ? Strings.rewardIn(seconds) : Strings.secondsLeft(seconds)
        countdown.isHidden = false
    }

    // MARK: - App state

    private func observeAppState() {
        let center = NotificationCenter.default
        notificationTokens.append(center.addObserver(forName: UIApplication.willResignActiveNotification, object: nil, queue: .main) { [weak self] _ in
            self?.active = false
            self?.player?.pause()
        })
        notificationTokens.append(center.addObserver(forName: UIApplication.didBecomeActiveNotification, object: nil, queue: .main) { [weak self] _ in
            guard let self = self else { return }
            self.active = true
            self.resumeIfPossible()
        })
    }

    private func resumeIfPossible() {
        guard active, !confirmVisible, !session.closed, !session.videoCompleted, !session.playbackFailed else { return }
        player?.play()
    }

    // MARK: - Actions

    @objc private func adTapped() {
        session.onClick()
    }

    @objc private func badgeTapped() {
        AboutLink.open()
    }

    @objc private func closeTapped() {
        guard !closeButton.isHidden else { return } // Not closable yet.
        if session.isRewarded && !session.videoCompleted && !session.playbackFailed {
            showConfirm()
        } else {
            closeAd()
        }
    }

    private func showConfirm() {
        guard !confirmVisible else { return }
        confirmVisible = true
        player?.pause()
        let alert = UIAlertController(title: Strings.skipTitle, message: Strings.skipMessage, preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: Strings.skipResume, style: .cancel) { [weak self] _ in
            self?.confirmVisible = false
            self?.resumeIfPossible()
        })
        alert.addAction(UIAlertAction(title: Strings.skipConfirm, style: .destructive) { [weak self] _ in
            self?.confirmVisible = false
            self?.closeAd()
        })
        present(alert, animated: true)
    }

    private func closeAd() {
        teardown()
        let session = self.session
        if let presenting = presentingViewController {
            presenting.dismiss(animated: true) { session.close() }
        } else {
            session.close()
        }
    }

    private func failRender() {
        guard !failing, !session.closed else { return }
        guard appeared else {
            failurePending = true
            return
        }
        failing = true
        teardown()
        let session = self.session
        if let presenting = presentingViewController {
            presenting.dismiss(animated: false) { session.onRenderFailed() }
        } else {
            session.onRenderFailed()
        }
    }

    private func teardown() {
        closeTask?.cancel()
        tickTimer?.invalidate()
        tickTimer = nil
        player?.pause()
        observations.forEach { $0.invalidate() }
        observations.removeAll()
    }
}

/// A view backed by an `AVPlayerLayer`, so the video follows the view's bounds.
final class PlayerView: UIView {
    override class var layerClass: AnyClass { AVPlayerLayer.self }

    // swiftlint:disable:next force_cast
    var playerLayer: AVPlayerLayer { layer as! AVPlayerLayer }
}

/// White text on a translucent black rounded background.
final class PillLabel: UILabel {
    var insets = UIEdgeInsets(top: 3, left: 8, bottom: 3, right: 8) {
        didSet { invalidateIntrinsicContentSize() }
    }

    override init(frame: CGRect) {
        super.init(frame: frame)
        textColor = .white
        backgroundColor = UIColor.black.withAlphaComponent(0.6)
        layer.cornerRadius = 12
        layer.masksToBounds = true
        textAlignment = .center
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) is not supported")
    }

    override func drawText(in rect: CGRect) {
        super.drawText(in: rect.inset(by: insets))
    }

    override var intrinsicContentSize: CGSize {
        let size = super.intrinsicContentSize
        return CGSize(width: size.width + insets.left + insets.right, height: size.height + insets.top + insets.bottom)
    }
}
