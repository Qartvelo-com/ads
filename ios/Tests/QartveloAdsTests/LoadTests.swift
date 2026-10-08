@testable import QartveloAds
import UIKit
import XCTest

/// Full loads against a stubbed backend.
final class LoadTests: XCTestCase {
    private let base = URL(string: "https://ads.test/")!

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
            "placements": [["code": "level_end", "format": "interstitial", "admob_ad_unit_id": "ca-app-pub-1/2"]],
        ])
    }

    override func tearDown() {
        QartveloAds.resetForTests()
        StubURLProtocol.reset()
        super.tearDown()
    }

    func testInterstitialLoadsFromQartveloAds() throws {
        stubInterstitialFill(test: false)

        let delegate = try load("level_end")

        XCTAssertEqual(delegate.events, ["loaded:QARTVELO"])
        XCTAssertEqual(delegate.loaded.first?.campaignId, "cmp_7")
        XCTAssertTrue(QartveloAds.isInterstitialReady("level_end"))

        let initBody = try XCTUnwrap(StubURLProtocol.requests("/api/v1/sdk/initialize").first)
        XCTAssertEqual(initBody["platform"] as? String, "ios")
        XCTAssertEqual(initBody["sdk_version"] as? String, QartveloAds.sdkVersion)
        XCTAssertEqual(initBody["is_emulator"] as? Bool, false)
        XCTAssertEqual(initBody["test_mode"] as? Bool, false)

        let adBody = try XCTUnwrap(StubURLProtocol.requests("/api/v1/ads/request").first)
        XCTAssertEqual(adBody["placement"] as? String, "level_end")
        XCTAssertEqual(adBody["format"] as? String, "interstitial")
        XCTAssertEqual(adBody["session_token"] as? String, "sess_1")
        XCTAssertEqual(adBody["test_mode"] as? Bool, false, "App Store production traffic remains live")
        XCTAssertNotNil(adBody["os_version"] as? String)
        XCTAssertNil(adBody["android_version"])
    }

    func testSimulatorLoadsRealCampaignAsTestEvenWithTestOptionsOff() throws {
        TestHooks.simulator = true
        try assertRealCampaignTestLoad(simulator: true)
    }

    func testNonAppStoreInstallLoadsRealCampaignAsTestEvenWithTestOptionsOff() throws {
        TestHooks.appStoreBuild = false
        try assertRealCampaignTestLoad(simulator: false)
    }

    private func assertRealCampaignTestLoad(simulator: Bool) throws {
        stubInterstitialFill(test: true)
        let delegate = try load("level_end") {
            $0.testMode = false
            $0.testModeInDebugBuilds = false
        }
        XCTAssertEqual(delegate.events, ["loaded:QARTVELO"])
        XCTAssertEqual(delegate.loaded.first?.campaignId, "cmp_7", "real campaign creative can still be shown")
        XCTAssertEqual(delegate.loaded.first?.creativeId, "crt_9")
        let initialize = try XCTUnwrap(StubURLProtocol.requests("/api/v1/sdk/initialize").first)
        let request = try XCTUnwrap(StubURLProtocol.requests("/api/v1/ads/request").first)
        XCTAssertEqual(initialize["test_mode"] as? Bool, true, "the session must be non-billable")
        XCTAssertEqual(initialize["is_emulator"] as? Bool, simulator)
        XCTAssertEqual(request["test_mode"] as? Bool, true, "each ad request preserves test traffic")
        XCTAssertTrue(QartveloAds.engine()?.testMode == true)
    }

    func testNoFillWithoutFallbackReportsNoAd() throws {
        StubURLProtocol.route("/api/v1/ads/request", json: ["status": "no_fill", "request_id": "req_2", "reason": "no_campaign"])

        let delegate = try load("level_end")

        XCTAssertEqual(delegate.events, ["noAd", "failed:NO_FILL"])
    }

    func testNoFillFallsBackToTheAdapter() throws {
        StubURLProtocol.route("/api/v1/ads/request", json: ["status": "no_fill", "request_id": "req_3"])
        let adapter = StubAdapter()
        QartveloAds.registerFallbackAdapter(adapter)

        let delegate = try load("level_end")

        XCTAssertEqual(delegate.events, ["fallback:no_fill", "loaded:ADMOB"])
        XCTAssertTrue(QartveloAds.isInterstitialReady("level_end"))
    }

    func testPackageMismatchDownloadsPublicTestCreativeInSimulator() throws {
        TestHooks.simulator = true
        let png = UIGraphicsImageRenderer(size: CGSize(width: 4, height: 4)).pngData { context in
            UIColor.red.setFill()
            context.fill(CGRect(x: 0, y: 0, width: 4, height: 4))
        }
        StubURLProtocol.route("/test-ads/interstitial_1080x1920.png", body: png, contentType: "image/png")
        StubURLProtocol.route("/api/v1/sdk/initialize", status: 403, json: [
            "error": ["code": "package_mismatch", "message": "bundle id mismatch"],
        ])
        let options = QartveloAdsOptions()
        options.baseURL = base
        options.requestTimeoutMs = 1_000
        options.logLevel = .debug

        let initialized = expectation(description: "test mode initializes despite backend rejection")
        QartveloAds.initialize(appKey: "app_for_another_bundle", options: options) { success, error in
            XCTAssertTrue(success, "\(String(describing: error))")
            XCTAssertNil(error)
            initialized.fulfill()
        }
        wait(for: [initialized], timeout: 5)

        let delegate = RecordingDelegate()
        let loaded = expectation(description: "server test creative loads")
        delegate.onEvent = { if $0 == "loaded:QARTVELO" { loaded.fulfill() } }
        QartveloAds.loadInterstitial("level_end", delegate: delegate)
        wait(for: [loaded], timeout: 5)

        XCTAssertEqual(delegate.events, ["loaded:QARTVELO"])
        XCTAssertEqual(delegate.loaded.first?.campaignId, "cmp_test")
        XCTAssertEqual(delegate.loaded.first?.creativeId, "cr_test_interstitial")
        XCTAssertTrue(QartveloAds.isInterstitialReady("level_end"))
        XCTAssertTrue(StubURLProtocol.requests("/api/v1/ads/request").isEmpty)
        XCTAssertEqual(StubURLProtocol.requests("/test-ads/interstitial_1080x1920.png").count, 1)
    }

    func testEmptyAppKeyDownloadsPublicTestCreativeWithoutSession() throws {
        TestHooks.simulator = true
        // CreativeCache checks the ISO-BMFF ftyp marker before the ad is handed to the player.
        let mp4Header = Data([0, 0, 0, 16]) + Data("ftypisom".utf8) + Data([0, 0, 0, 0])
        StubURLProtocol.route("/test-ads/rewarded.mp4", body: mp4Header, contentType: "video/mp4")
        let options = QartveloAdsOptions()
        options.baseURL = base
        let initialized = expectation(description: "test mode initializes without an app key")
        QartveloAds.initialize(appKey: "", options: options) { success, error in
            XCTAssertTrue(success, "\(String(describing: error))")
            initialized.fulfill()
        }
        wait(for: [initialized], timeout: 2)

        let delegate = RecordingDelegate()
        let loaded = expectation(description: "server test creative loads without an app key")
        delegate.onEvent = { if $0 == "loaded:QARTVELO" { loaded.fulfill() } }
        QartveloAds.loadRewarded("reward_coins", delegate: delegate)
        wait(for: [loaded], timeout: 5)

        XCTAssertEqual(delegate.loaded.first?.campaignId, "cmp_test")
        XCTAssertEqual(delegate.loaded.first?.creativeId, "cr_test_rewarded")
        XCTAssertTrue(StubURLProtocol.requests("/api/v1/sdk/initialize").isEmpty)
        XCTAssertEqual(StubURLProtocol.requests("/api/v1/ads/request").count, 0)
        XCTAssertEqual(StubURLProtocol.requests("/test-ads/rewarded.mp4").count, 1)
    }

    func testForceNoFillUsesAdMobTestFallbackWithoutRegisteredApp() throws {
        TestHooks.simulator = true
        let adapter = StubAdapter()
        QartveloAds.registerFallbackAdapter(adapter)
        let options = QartveloAdsOptions()
        options.baseURL = base
        options.testForceNoFill = true
        let initialized = expectation(description: "local test mode initializes")
        QartveloAds.initialize(appKey: "", options: options) { success, error in
            XCTAssertTrue(success, "\(String(describing: error))")
            initialized.fulfill()
        }
        wait(for: [initialized], timeout: 2)

        let delegate = RecordingDelegate()
        let loaded = expectation(description: "forced no-fill loads fallback")
        delegate.onEvent = { if $0 == "loaded:ADMOB" { loaded.fulfill() } }
        QartveloAds.loadInterstitial("level_end", delegate: delegate)
        wait(for: [loaded], timeout: 5)

        XCTAssertEqual(delegate.events, ["fallback:no_fill", "loaded:ADMOB"])
        XCTAssertTrue(StubURLProtocol.requests("/api/v1/ads/request").isEmpty)
    }

    func testLoadBeforeInitializeFails() {
        let delegate = RecordingDelegate()
        let done = expectation(description: "callback")
        delegate.onEvent = { _ in done.fulfill() }

        QartveloAds.loadInterstitial("level_end", delegate: delegate)

        wait(for: [done], timeout: 2)
        XCTAssertEqual(delegate.events, ["failed:NOT_INITIALIZED"])
    }

    func testBannerRequestsContainerPixelsAndResizesTheCachedCreative() throws {
        let path = "/creatives/\(UUID().uuidString).png"
        let png = UIGraphicsImageRenderer(size: CGSize(width: 728, height: 90)).pngData { context in
            UIColor.red.setFill()
            context.fill(CGRect(x: 0, y: 0, width: 728, height: 90))
        }
        StubURLProtocol.route(path, body: png, contentType: "image/png")
        StubURLProtocol.route("/api/v1/ads/request", json: [
            "status": "fill", "request_id": "req_banner",
            "ad": [
                "id": "ad_banner", "campaign_id": "cmp_7", "creative_id": "crt_banner",
                "format": "banner", "creative_type": "image", "creative_url": "https://ads.test\(path)",
                "width": 728, "height": 90, "impression_token": "imp_banner", "expires_at": "2099-01-01T00:00:00Z", "test": false,
            ],
        ])
        let options = QartveloAdsOptions()
        options.baseURL = base
        let initialized = expectation(description: "initialized")
        QartveloAds.initialize(appKey: "app_\(UUID().uuidString)", options: options) { success, _ in
            XCTAssertTrue(success)
            initialized.fulfill()
        }
        wait(for: [initialized], timeout: 5)
        let delegate = RecordingDelegate()
        let loaded = expectation(description: "banner loaded")
        delegate.onEvent = { if $0 == "loaded:QARTVELO" { loaded.fulfill() } }
        let view = QartveloAdsBannerView(frame: CGRect(x: 0, y: 0, width: 220, height: 0))
        view.placementId = "home_banner"
        view.delegate = delegate
        defer { view.destroy() }

        view.load()
        wait(for: [loaded], timeout: 5)

        let request = try XCTUnwrap(StubURLProtocol.requests("/api/v1/ads/request").first)
        XCTAssertEqual(request["screen_width"] as? Int, Int(220 * UIScreen.main.scale))
        XCTAssertEqual(view.intrinsicContentSize.width, 220)
        XCTAssertEqual(request["banner_height"] as? Int, Int(50 * UIScreen.main.scale))
        XCTAssertEqual(view.intrinsicContentSize.height, 50)
        let content = try XCTUnwrap(view.subviews.first)

        view.frame.size.width = 110
        view.setNeedsLayout()
        view.layoutIfNeeded()
        view.load()

        XCTAssertEqual(view.intrinsicContentSize.width, 110)
        XCTAssertEqual(view.intrinsicContentSize.height, 50)
        view.usesAdaptiveSize = false
        view.layoutIfNeeded()
        XCTAssertEqual(view.intrinsicContentSize.height, 110 * 90 / 728, accuracy: 0.001)
        XCTAssertTrue(view.subviews.first === content, "resizing must reuse the creative")
        XCTAssertEqual(StubURLProtocol.requests("/api/v1/ads/request").count, 1)
        XCTAssertTrue(StubURLProtocol.requests("/api/v1/events/impression").isEmpty, "an off-screen banner must not record an impression")
    }

    func testAnonymousBannerDownloadsTheSizeChosenForItsContainer() {
        TestHooks.simulator = true
        let png = UIGraphicsImageRenderer(size: CGSize(width: 4, height: 4)).pngData { context in
            UIColor.blue.setFill()
            context.fill(CGRect(x: 0, y: 0, width: 4, height: 4))
        }
        StubURLProtocol.route("/test-ads/banner_468x60.png", body: png, contentType: "image/png")
        let options = QartveloAdsOptions()
        options.baseURL = base
        let initialized = expectation(description: "initialized without registration")
        QartveloAds.initialize(appKey: "", options: options) { success, _ in
            XCTAssertTrue(success)
            initialized.fulfill()
        }
        wait(for: [initialized], timeout: 2)
        let done = expectation(description: "468-wide public creative downloaded")

        QartveloAds.engine()?.fetchAd("home", format: .banner, timeoutMs: 1000, bannerWidth: 600) { outcome in
            guard case .success(let ad) = outcome else {
                XCTFail("public banner failed")
                done.fulfill()
                return
            }
            XCTAssertEqual(ad.creativeURL.path, "/test-ads/banner_468x60.png")
            XCTAssertEqual(ad.width, 468)
            XCTAssertEqual(ad.height, 60)
            XCTAssertTrue(ad.test && ad.localTest)
            done.fulfill()
        }
        wait(for: [done], timeout: 5)

        XCTAssertEqual(StubURLProtocol.requests("/test-ads/banner_468x60.png").count, 1)
        XCTAssertTrue(StubURLProtocol.requests("/api/v1/ads/request").isEmpty)
    }

    func testAnonymousAdaptiveBannerDownloadsPhoneArtwork() {
        TestHooks.simulator = true
        let png = UIGraphicsImageRenderer(size: CGSize(width: 4, height: 4)).pngData { context in
            UIColor.blue.setFill()
            context.fill(CGRect(x: 0, y: 0, width: 4, height: 4))
        }
        StubURLProtocol.route("/test-ads/banner_adaptive_v1_1320x204.png", body: png, contentType: "image/png")
        let options = QartveloAdsOptions()
        options.baseURL = base
        let initialized = expectation(description: "anonymous initialization")
        QartveloAds.initialize(appKey: "", options: options) { _, _ in initialized.fulfill() }
        wait(for: [initialized], timeout: 2)
        let done = expectation(description: "adaptive public image")

        QartveloAds.engine()?.fetchAd("home", format: .banner, timeoutMs: 1000, bannerWidth: 1320, bannerHeight: 204) { outcome in
            if case .success(let ad) = outcome {
                XCTAssertEqual(ad.width, 1320)
                XCTAssertEqual(ad.height, 204)
                XCTAssertTrue(ad.test && ad.localTest)
            } else { XCTFail("adaptive public banner failed") }
            done.fulfill()
        }
        wait(for: [done], timeout: 5)
        XCTAssertEqual(StubURLProtocol.requests("/test-ads/banner_adaptive_v1_1320x204.png").count, 1)
        XCTAssertTrue(StubURLProtocol.requests("/api/v1/ads/request").isEmpty)
    }

    /// Initializes against the stub backend and loads `placementId`, waiting for the load to end.
    private func load(_ placementId: String, configure: (QartveloAdsOptions) -> Void = { _ in }) throws -> RecordingDelegate {
        let options = QartveloAdsOptions()
        options.baseURL = base
        options.requestTimeoutMs = 5_000
        options.logLevel = .debug
        configure(options)
        let initialized = expectation(description: "initialized")
        QartveloAds.initialize(appKey: "app_\(UUID().uuidString)", options: options) { success, error in
            XCTAssertTrue(success, "\(String(describing: error))")
            initialized.fulfill()
        }
        wait(for: [initialized], timeout: 10)

        let delegate = RecordingDelegate()
        let finished = expectation(description: "load finished")
        delegate.onEvent = { event in
            if event.hasPrefix("loaded") || event.hasPrefix("failed") { finished.fulfill() }
        }
        QartveloAds.loadInterstitial(placementId, delegate: delegate)
        wait(for: [finished], timeout: 10)
        return delegate
    }

    private func stubInterstitialFill(test: Bool) {
        let png = UIGraphicsImageRenderer(size: CGSize(width: 4, height: 4)).pngData { context in
            UIColor.red.setFill()
            context.fill(CGRect(x: 0, y: 0, width: 4, height: 4))
        }
        StubURLProtocol.route("/creatives/full.png", body: png, contentType: "image/png")
        StubURLProtocol.route("/api/v1/ads/request", json: [
            "status": "fill",
            "request_id": "req_1",
            "ad": [
                "id": "ad_1", "campaign_id": "cmp_7", "creative_id": "crt_9",
                "format": "interstitial", "creative_type": "image",
                "creative_url": "https://ads.test/creatives/full.png",
                "click_url": "https://example.ge/offer", "width": 1080, "height": 1920,
                "impression_token": "imp_1", "expires_at": "2099-01-01T00:00:00Z", "test": test,
            ],
        ])
    }
}
