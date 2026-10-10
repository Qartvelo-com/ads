@testable import QartveloAds
import UIKit
import XCTest

/// HTML5 ads: path rules, the web view policy, the request, the bundle download and the banner
/// rules (with a fake web view).
final class Html5Tests: XCTestCase {
    private let base = URL(string: "https://ads.test/")!
    private var surfaces: [FakeSurface] = []
    private var window: UIWindow?
    private var views: [QartveloAdsBannerView] = []

    final class FakeSurface: Html5Surface {
        let view = UIView()
        var onReady: (() -> Void)?
        var onFailed: ((String) -> Void)?
        var onClick: (() -> Void)?
        var paused = false
        var destroyed = false

        func load(onReady: @escaping () -> Void, onFailed: @escaping (String) -> Void, onClick: @escaping () -> Void) {
            self.onReady = onReady
            self.onFailed = onFailed
            self.onClick = onClick
        }

        func pause() { paused = true }
        func resume() { paused = false }
        func destroy() { destroyed = true }
    }

    override func setUp() {
        super.setUp()
        QartveloAds.resetForTests()
        StubURLProtocol.reset()
        TestHooks.urlProtocols = [StubURLProtocol.self]
        TestHooks.simulator = false
        TestHooks.developmentBuild = false
        TestHooks.appStoreBuild = true
        TestHooks.html5SurfaceFactory = { [weak self] _ in
            let surface = FakeSurface()
            self?.surfaces.append(surface)
            return surface
        }
        StubURLProtocol.route("/api/v1/sdk/initialize", json: [
            "session_token": "sess_1",
            "session_expires_at": "2099-01-01T00:00:00Z",
            "config": ["serving_enabled": true],
            "placements": [
                ["code": "home_banner", "format": "banner", "admob_ad_unit_id": "ca-app-pub-1/3", "request_timeout_ms": 5000],
                ["code": "level_end", "format": "interstitial", "request_timeout_ms": 5000],
                ["code": "coins", "format": "rewarded", "request_timeout_ms": 5000],
            ],
        ])
        for file in ["index.html", "style.css", "main.js", "m/a.png", "m/b.png"] {
            StubURLProtocol.route("/b/\(file)", body: Data("x-\(file)".utf8), contentType: "text/plain")
        }
    }

    override func tearDown() {
        views.forEach { $0.destroy() }
        views = []
        window?.isHidden = true
        window = nil
        QartveloAds.resetForTests()
        StubURLProtocol.reset()
        super.tearDown()
    }

    private func fill(_ format: String, files: [String] = ["index.html", "style.css", "main.js", "m/a.png"], width: Int = 320, height: Int = 50) -> JSON {
        [
            "status": "fill", "request_id": "req_h5",
            "ad": [
                "id": "ad_h5", "campaign_id": "cmp_7", "creative_id": "crt_h5", "format": format, "creative_type": "html5",
                "creative_url": "https://ads.test/b/index.html", "click_url": "https://advertiser.example/",
                "width": width, "height": height, "impression_token": "imp_h5", "expires_at": "2099-01-01T00:00:00Z", "test": false,
                "layouts": [
                    ["family": "served", "width": width, "height": height, "files": files],
                    ["family": "other", "width": height, "height": width, "files": ["index.html", "m/b.png"]],
                ],
            ],
        ]
    }

    // MARK: - Pure rules

    func testPathsMustStayInsideTheBundle() {
        XCTAssertTrue(Html5Files.isSafePath("index.html"))
        XCTAssertTrue(Html5Files.isSafePath("m/3f2a.jpg"))
        for bad in ["", "../x", "m/../../x", "/abs", "https://x", "a\\b", "m//a", "./a", "a?b", "a#b", "%2e%2e/x", "a b"] {
            XCTAssertFalse(Html5Files.isSafePath(bad), bad)
        }
        XCTAssertEqual(Html5Files.base(of: URL(string: "https://ads.test/b/index.html")!)?.absoluteString, "https://ads.test/b/")
        XCTAssertNil(Html5Files.base(of: URL(string: "https://ads.test/b/main.js")!))
    }

    func testRequestsAreServedFetchedOrBlocked() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory.appendingPathComponent("m"), withIntermediateDirectories: true)
        try Data("<html>".utf8).write(to: directory.appendingPathComponent("index.html"))
        try Data([1]).write(to: directory.appendingPathComponent("m/a.png"))
        defer { try? FileManager.default.removeItem(at: directory) }
        let bundleBase = URL(string: "https://ads.test/b/")!
        let page = Html5Policy.pageBase

        guard case .serve(_, let mime) = Html5Policy.intercept(directory: directory, bundleBase: bundleBase, url: page + "index.html", method: "GET") else {
            return XCTFail("index.html is served from the download")
        }
        XCTAssertEqual(mime, "text/html")
        XCTAssertEqual(Html5Policy.intercept(directory: directory, bundleBase: bundleBase, url: page + "m/b.png", method: "GET"),
                       .network(URL(string: "https://ads.test/b/m/b.png")!, mimeType: "image/png"))
        XCTAssertEqual(Html5Policy.intercept(directory: directory, bundleBase: bundleBase, url: "https://evil.example/x.js", method: "GET"), .block)
        XCTAssertEqual(Html5Policy.intercept(directory: directory, bundleBase: bundleBase, url: page + "index.html", method: "POST"), .block)
        XCTAssertEqual(Html5Policy.intercept(directory: directory, bundleBase: bundleBase, url: page + "m/%2e%2e/%2e%2e/secret.png", method: "GET"), .block)
        XCTAssertEqual(Html5Policy.intercept(directory: directory, bundleBase: bundleBase, url: page + "data.json", method: "GET"), .block)
        XCTAssertEqual(Html5Policy.headers(mimeType: "text/html")["X-Content-Type-Options"], "nosniff")
        XCTAssertTrue(Html5Policy.isClick("qartvelo://click", firstLoadDone: false))
        XCTAssertTrue(Html5Policy.isClick("https://advertiser.example/", firstLoadDone: true))
        XCTAssertFalse(Html5Policy.isClick("https://advertiser.example/", firstLoadDone: false))
    }

    // MARK: - Request and download

    func testInterstitialsOfferHtml5AndRewardedDoesNot() throws {
        StubURLProtocol.route("/api/v1/ads/request", json: ["status": "no_fill", "request_id": "req_nf", "fallback": "none"])
        try initialize()
        _ = load(.interstitial, "level_end")
        _ = load(.rewarded, "coins")
        let bodies = StubURLProtocol.requests("/api/v1/ads/request")
        XCTAssertEqual(bodies.first?["supported_creative_types"] as? [String], ["image", "video", "html5"])
        XCTAssertEqual(bodies.last?["supported_creative_types"] as? [String], ["image", "video"])
    }

    func testAnHtml5FillDownloadsTheServedLayoutsFiles() throws {
        StubURLProtocol.route("/api/v1/ads/request", json: fill("interstitial", width: 360, height: 640))
        try initialize()
        let delegate = load(.interstitial, "level_end")
        XCTAssertEqual(delegate.events, ["loaded:QARTVELO"])
        for file in ["index.html", "style.css", "main.js", "m/a.png"] {
            XCTAssertEqual(StubURLProtocol.requests("/b/\(file)").count, 1, file)
        }
        XCTAssertEqual(StubURLProtocol.requests("/b/m/b.png").count, 0, "other layouts load lazily")
    }

    func testUnsafeOrMissingFilesAreACreativeFailure() throws {
        try initialize()
        for files in [["index.html", "../x.js"], ["index.html", "/abs.js"], ["index.html", "https://evil.example/x.js"], ["main.js"], [], ["index.html", "missing.js"]] {
            StubURLProtocol.route("/api/v1/ads/request", json: fill("interstitial", files: files, width: 360, height: 640))
            let delegate = load(.interstitial, "level_end")
            XCTAssertFalse(delegate.events.contains("loaded:QARTVELO"), "\(files)")
        }
    }

    // MARK: - Banners

    func testABannerIsCountedOnlyOnceReadyWithOneClick() throws {
        StubURLProtocol.route("/api/v1/ads/request", json: fill("banner"))
        try initialize()
        let delegate = RecordingDelegate()
        let view = banner(delegate)
        view.load()
        let surface = try waitForSurface()

        XCTAssertFalse(delegate.events.contains("loaded:QARTVELO"))
        XCTAssertEqual(surface.view.superview?.alpha, 0, "loads hidden in the slot")
        surface.onClick?()
        surface.onReady?()
        XCTAssertEqual(delegate.events, ["loaded:QARTVELO"])
        XCTAssertEqual(view.intrinsicContentSize.height, view.adaptiveBannerSize.height)
        XCTAssertTrue(surface.view.isDescendant(of: view))

        surface.onClick?()
        surface.onClick?()
        let clicks = expectation(description: "click event")
        DispatchQueue.main.asyncAfter(deadline: .now() + 1) { clicks.fulfill() }
        wait(for: [clicks], timeout: 3)
        XCTAssertEqual(StubURLProtocol.requests("/api/v1/events/click").count, 1, "one click per impression; none before it")
    }

    func testABannerThatNeverGetsReadyIsACreativeFailure() throws {
        StubURLProtocol.route("/api/v1/ads/request", json: fill("banner"))
        try initialize()
        let delegate = RecordingDelegate()
        let failed = expectation(description: "no ad")
        delegate.onEvent = { if $0 == "noAd" { failed.fulfill() } }
        let view = banner(delegate)
        view.load()
        let surface = try waitForSurface()

        surface.onFailed?("not ready within 6000 ms")
        wait(for: [failed], timeout: 5)
        XCTAssertTrue(surface.destroyed)
        XCTAssertTrue(StubURLProtocol.requests("/api/v1/events/impression").isEmpty)
    }

    func testAnInlineSlotSizesTheWebViewLikeAnImage() throws {
        StubURLProtocol.route("/api/v1/ads/request", json: fill("banner", width: 300, height: 250))
        try initialize()
        let view = banner(RecordingDelegate()) { $0.sizing = .inline; $0.inlineMaxHeight = 250 }
        view.load()
        let surface = try waitForSurface()
        XCTAssertEqual(surface.view.superview?.frame.size, CGSize(width: 300, height: 250), "staged at its final size")
        surface.onReady?()
        XCTAssertEqual(view.intrinsicContentSize, CGSize(width: 300, height: 250))
    }

    func testAHiddenHostPausesTheAd() throws {
        StubURLProtocol.route("/api/v1/ads/request", json: fill("banner"))
        try initialize()
        let view = banner(RecordingDelegate())
        view.load()
        let surface = try waitForSurface()
        surface.onReady?()

        view.isHidden = true
        XCTAssertTrue(surface.paused)
        view.isHidden = false
        XCTAssertFalse(surface.paused)
        view.destroy()
    }

    // MARK: - Helpers

    private func initialize() throws {
        let options = QartveloAdsOptions()
        options.baseURL = base
        let initialized = expectation(description: "initialized")
        QartveloAds.initialize(appKey: "app_\(UUID().uuidString)", options: options) { success, _ in
            XCTAssertTrue(success)
            initialized.fulfill()
        }
        wait(for: [initialized], timeout: 5)
    }

    private func load(_ format: QartveloAdFormat, _ placement: String) -> RecordingDelegate {
        let delegate = RecordingDelegate()
        let done = expectation(description: "load outcome")
        delegate.onEvent = { if $0.hasPrefix("loaded") || $0.hasPrefix("failed") { done.fulfill() } }
        if format == .rewarded { QartveloAds.loadRewarded(placement, delegate: delegate) } else { QartveloAds.loadInterstitial(placement, delegate: delegate) }
        wait(for: [done], timeout: 5)
        return delegate
    }

    private func banner(_ delegate: RecordingDelegate, configure: (QartveloAdsBannerView) -> Void = { _ in }) -> QartveloAdsBannerView {
        let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 400, height: 800))
        window.rootViewController = UIViewController()
        window.isHidden = false
        self.window = window
        let view = QartveloAdsBannerView(frame: CGRect(x: 0, y: 0, width: 400, height: 0))
        view.placementId = "home_banner"
        view.delegate = delegate
        configure(view)
        window.rootViewController?.view.addSubview(view)
        objc_setAssociatedObject(view, "delegate", delegate, .OBJC_ASSOCIATION_RETAIN)
        views.append(view)
        return view
    }

    private func waitForSurface() throws -> FakeSurface {
        let created = expectation(description: "surface")
        func check() {
            if let surface = surfaces.last, surface.onReady != nil { created.fulfill() } else { DispatchQueue.main.asyncAfter(deadline: .now() + 0.02, execute: check) }
        }
        check()
        wait(for: [created], timeout: 5)
        return try XCTUnwrap(surfaces.last)
    }
}
