@testable import QartveloAds
import UIKit
import XCTest

/// Inline banners: the request, the fitted slot and the fallback. The stub server answers with a 300x250 creative.
final class InlineBannerTests: XCTestCase {
    private let base = URL(string: "https://ads.test/")!
    private var views: [QartveloAdsBannerView] = []

    override func setUp() {
        super.setUp()
        QartveloAds.resetForTests()
        StubURLProtocol.reset()
        TestHooks.urlProtocols = [StubURLProtocol.self]
        TestHooks.simulator = false
        TestHooks.developmentBuild = false
        TestHooks.appStoreBuild = true
        StubURLProtocol.route("/api/v1/sdk/initialize", json: [
            "session_token": "sess_1",
            "session_expires_at": "2099-01-01T00:00:00Z",
            "config": ["serving_enabled": true],
            "placements": [["code": "home_banner", "format": "banner", "admob_ad_unit_id": "ca-app-pub-1/3", "request_timeout_ms": 5000]],
        ])
        let png = UIGraphicsImageRenderer(size: CGSize(width: 300, height: 250)).pngData { context in
            UIColor.blue.setFill()
            context.fill(CGRect(x: 0, y: 0, width: 300, height: 250))
        }
        StubURLProtocol.route("/creatives/rect.png", body: png, contentType: "image/png")
        StubURLProtocol.route("/api/v1/ads/request", json: [
            "status": "fill", "request_id": "req_rect",
            "ad": [
                "id": "ad_rect", "campaign_id": "cmp_7", "creative_id": "crt_rect",
                "format": "banner", "creative_type": "image", "creative_url": "https://ads.test/creatives/rect.png",
                "width": 300, "height": 250, "impression_token": "imp_rect", "expires_at": "2099-01-01T00:00:00Z", "test": false,
            ],
        ])
    }

    override func tearDown() {
        views.forEach { $0.destroy() }
        views = []
        QartveloAds.resetForTests()
        StubURLProtocol.reset()
        super.tearDown()
    }

    func testFitMatchesTheServerRule() {
        XCTAssertEqual(InlineBannerFit.size(width: 320, height: 50, slotWidth: 400, maxHeight: 250), CGSize(width: 400, height: 62))
        XCTAssertEqual(InlineBannerFit.size(width: 300, height: 250, slotWidth: 400, maxHeight: 250), CGSize(width: 300, height: 250))
        XCTAssertEqual(InlineBannerFit.size(width: 300, height: 250, slotWidth: 400, maxHeight: 100), CGSize(width: 120, height: 100))
        XCTAssertEqual(InlineBannerFit.size(width: 0, height: 50, slotWidth: 400, maxHeight: 250), .zero)
        XCTAssertEqual(InlineBannerFit.size(width: 320, height: 50, slotWidth: 400, maxHeight: -1), .zero)
    }

    func testInlineRequestSendsModeAndMaxHeightInPixels() throws {
        let view = try loadBanner(width: 400) { $0.sizing = .inline; $0.inlineMaxHeight = 250 }
        let request = try XCTUnwrap(StubURLProtocol.requests("/api/v1/ads/request").first)
        XCTAssertEqual(request["banner_mode"] as? String, "inline")
        XCTAssertEqual(request["banner_max_height"] as? Int, Int(ceil(250 * UIScreen.main.scale)))
        XCTAssertEqual(request["screen_width"] as? Int, Int(400 * UIScreen.main.scale))
        XCTAssertNil(request["banner_height"])
        XCTAssertEqual(view.intrinsicContentSize, CGSize(width: 300, height: 250))
    }

    func testTinyOrNegativeMaxHeightIsClampedTo32() throws {
        _ = try loadBanner(width: 400) { $0.sizing = .inline; $0.inlineMaxHeight = -10 }
        let request = try XCTUnwrap(StubURLProtocol.requests("/api/v1/ads/request").first)
        XCTAssertEqual(request["banner_max_height"] as? Int, Int(ceil(32 * UIScreen.main.scale)))
    }

    func testSlotRefitsOnWidthChangeWithoutANewRequest() throws {
        let view = try loadBanner(width: 400) { $0.sizing = .inline; $0.inlineMaxHeight = 250 }
        view.frame.size.width = 200
        view.setNeedsLayout()
        view.layoutIfNeeded()
        XCTAssertEqual(view.intrinsicContentSize.width, 200, accuracy: 0.5)
        XCTAssertEqual(view.intrinsicContentSize.height, 166, accuracy: 0.5)
        XCTAssertEqual(StubURLProtocol.requests("/api/v1/ads/request").count, 1)
    }

    func testAViewSwitchedToInlineDoesNotReuseTheAnchoredAd() throws {
        let view = try loadBanner(width: 400) { _ in }
        XCTAssertNotNil(StubURLProtocol.requests("/api/v1/ads/request").first?["banner_height"])
        let second = expectation(description: "second request")
        let delegate = RecordingDelegate()
        delegate.onEvent = { if $0 == "loaded:QARTVELO" { second.fulfill() } }
        view.delegate = delegate
        view.destroy()
        view.sizing = .inline
        view.load()
        wait(for: [second], timeout: 5)
        XCTAssertEqual(StubURLProtocol.requests("/api/v1/ads/request").last?["banner_mode"] as? String, "inline")
        XCTAssertEqual(view.intrinsicContentSize, CGSize(width: 300, height: 250))
    }

    func testAnchoredBannersAreUnchanged() throws {
        _ = try loadBanner(width: 400) { _ in }
        let request = try XCTUnwrap(StubURLProtocol.requests("/api/v1/ads/request").first)
        XCTAssertNil(request["banner_mode"])
        XCTAssertNil(request["banner_max_height"])
        XCTAssertNotNil(request["banner_height"])
    }

    func testInlineFallbackUsesTheAdaptersInlineBanner() throws {
        StubURLProtocol.route("/api/v1/ads/request", json: ["status": "no_fill", "request_id": "req_nf", "fallback": "admob"])
        let adapter = RecordingBannerAdapter(supportsInline: true)
        _ = try loadBanner(width: 400, adapter: adapter, expect: "loaded:ADMOB") { $0.sizing = .inline }
        XCTAssertEqual(adapter.bannerCalls, ["inline 400x250"])
    }

    func testAnAdapterWithoutInlineSupportGetsTheAnchoredBanner() throws {
        StubURLProtocol.route("/api/v1/ads/request", json: ["status": "no_fill", "request_id": "req_nf", "fallback": "admob"])
        let adapter = RecordingBannerAdapter(supportsInline: false)
        _ = try loadBanner(width: 400, adapter: adapter, expect: "loaded:ADMOB") { $0.sizing = .inline }
        XCTAssertEqual(adapter.bannerCalls, ["anchored 400"])
    }

    func testInlineTestAdIsTheBiggestTestBanner() {
        XCTAssertEqual(ServerTestAds.creative(for: .banner, availableWidth: 1200, availableHeight: nil, inlineMaxHeight: 750).filename, "banner_300x250.png")
        XCTAssertEqual(ServerTestAds.creative(for: .banner, availableWidth: 1200, availableHeight: nil, inlineMaxHeight: 300).filename, "banner_320x100.png")
    }

    // MARK: - Helpers

    private func loadBanner(
        width: CGFloat,
        adapter: QartveloFallbackAdapter? = nil,
        expect: String = "loaded:QARTVELO",
        configure: (QartveloAdsBannerView) -> Void
    ) throws -> QartveloAdsBannerView {
        if let adapter = adapter { QartveloAds.registerFallbackAdapter(adapter) }
        let options = QartveloAdsOptions()
        options.baseURL = base
        let initialized = expectation(description: "initialized")
        QartveloAds.initialize(appKey: "app_\(UUID().uuidString)", options: options) { success, _ in
            XCTAssertTrue(success)
            initialized.fulfill()
        }
        wait(for: [initialized], timeout: 5)
        let delegate = RecordingDelegate()
        let loaded = expectation(description: expect)
        delegate.onEvent = { if $0 == expect { loaded.fulfill() } }
        let view = QartveloAdsBannerView(frame: CGRect(x: 0, y: 0, width: width, height: 0))
        view.placementId = "home_banner"
        view.delegate = delegate
        configure(view)
        views.append(view)
        view.load()
        wait(for: [loaded], timeout: 5)
        objc_setAssociatedObject(view, "delegate", delegate, .OBJC_ASSOCIATION_RETAIN)
        return view
    }
}

/// Records which banner kind the SDK asked for; banners load at once.
final class RecordingBannerAdapter: QartveloFallbackAdapter {
    let networkName = "recording"
    let supportsInline: Bool
    var bannerCalls: [String] = []

    init(supportsInline: Bool) {
        self.supportsInline = supportsInline
    }

    func initialize(settings: QartveloFallbackSettings) {}
    func updateSettings(_ settings: QartveloFallbackSettings) {}
    func loadInterstitial(placementId: String, adUnitId: String, callback: QartveloFallbackLoadCallback) { callback.onFailed("no") }
    func isInterstitialReady(placementId: String) -> Bool { false }
    func showInterstitial(placementId: String, from viewController: UIViewController, callback: QartveloFallbackShowCallback) {}
    func loadRewarded(placementId: String, adUnitId: String, callback: QartveloFallbackLoadCallback) { callback.onFailed("no") }
    func isRewardedReady(placementId: String) -> Bool { false }
    func showRewarded(placementId: String, from viewController: UIViewController, callback: QartveloFallbackShowCallback) {}

    func createBanner(
        placementId: String, adUnitId: String, width: CGFloat, rootViewController: UIViewController?,
        callback: QartveloFallbackBannerCallback
    ) -> QartveloFallbackBanner {
        bannerCalls.append("anchored \(Int(width))")
        return loadedBanner(callback, height: 50)
    }

    func createInlineBanner(
        placementId: String, adUnitId: String, width: CGFloat, maxHeight: CGFloat,
        rootViewController: UIViewController?, callback: QartveloFallbackBannerCallback
    ) -> QartveloFallbackBanner? {
        guard supportsInline else { return nil }
        bannerCalls.append("inline \(Int(width))x\(Int(maxHeight))")
        return loadedBanner(callback, height: maxHeight)
    }

    private func loadedBanner(_ callback: QartveloFallbackBannerCallback, height: CGFloat) -> QartveloFallbackBanner {
        let banner = StubBanner()
        banner.view.frame.size = CGSize(width: 320, height: height)
        DispatchQueue.main.async { callback.onLoaded() }
        return banner
    }
}
