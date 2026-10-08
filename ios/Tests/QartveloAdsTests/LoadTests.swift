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
        let png = UIGraphicsImageRenderer(size: CGSize(width: 4, height: 4)).pngData { context in
            UIColor.red.setFill()
            context.fill(CGRect(x: 0, y: 0, width: 4, height: 4))
        }
        StubURLProtocol.route("/creatives/full.png", body: png, contentType: "image/png")
        StubURLProtocol.route("/api/v1/ads/request", json: [
            "status": "fill",
            "request_id": "req_1",
            "ad": [
                "id": "ad_1",
                "campaign_id": "cmp_7",
                "creative_id": "crt_9",
                "format": "interstitial",
                "creative_type": "image",
                "creative_url": "https://ads.test/creatives/full.png",
                "click_url": "https://example.ge/offer",
                "width": 1080,
                "height": 1920,
                "impression_token": "imp_1",
                "expires_at": "2099-01-01T00:00:00Z",
                "test": false,
            ],
        ])

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
        XCTAssertNotNil(adBody["os_version"] as? String)
        XCTAssertNil(adBody["android_version"])
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

    func testLoadBeforeInitializeFails() {
        let delegate = RecordingDelegate()
        let done = expectation(description: "callback")
        delegate.onEvent = { _ in done.fulfill() }

        QartveloAds.loadInterstitial("level_end", delegate: delegate)

        wait(for: [done], timeout: 2)
        XCTAssertEqual(delegate.events, ["failed:NOT_INITIALIZED"])
    }

    /// Initializes against the stub backend and loads `placementId`, waiting for the load to end.
    private func load(_ placementId: String) throws -> RecordingDelegate {
        let options = QartveloAdsOptions()
        options.baseURL = base
        options.requestTimeoutMs = 5_000
        options.logLevel = .debug
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
}
