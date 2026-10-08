@testable import QartveloAds
import XCTest

final class WireTests: XCTestCase {
    func testRemoteConfigDefaultsAndBannerRefreshFloor() throws {
        let json = try XCTUnwrap(try JSONSerialization.jsonObject(with: Data("""
        {
          "config": {"serving_enabled": false, "request_timeout_ms": 1200, "config_ttl_seconds": 600},
          "placements": [
            {"code": "home_banner", "format": "banner", "banner_refresh_seconds": 10, "admob_ad_unit_id": "ca-app-pub-1/2"},
            {"code": "level_end", "format": "interstitial", "ourads_enabled": false, "fallback_provider": "none", "request_timeout_ms": true},
            {"format": "rewarded"}
          ]
        }
        """.utf8)) as? JSON)

        let config = RemoteConfig.parse(json)

        XCTAssertFalse(config.servingEnabled)
        XCTAssertTrue(config.fallbackEnabled)
        XCTAssertEqual(config.requestTimeoutMs, 1200)
        XCTAssertEqual(config.configTtlSeconds, 600)
        XCTAssertEqual(config.placements.count, 2)

        let banner = try XCTUnwrap(config.placements["home_banner"])
        XCTAssertEqual(banner.format, .banner)
        XCTAssertEqual(banner.bannerRefreshSeconds, RemoteConfig.minBannerRefreshSeconds)
        XCTAssertEqual(banner.admobAdUnitId, "ca-app-pub-1/2")
        XCTAssertTrue(banner.qartveloEnabled)
        XCTAssertEqual(banner.fallbackProvider, "admob")

        let interstitial = try XCTUnwrap(config.placements["level_end"])
        XCTAssertFalse(interstitial.qartveloEnabled)
        XCTAssertEqual(interstitial.fallbackProvider, "none")
        XCTAssertNil(interstitial.requestTimeoutMs, "a JSON boolean is not a timeout")
        XCTAssertEqual(interstitial.bannerRefreshSeconds, RemoteConfig.defaultBannerRefreshSeconds)
    }

    func testEmptyConfigUsesSafeDefaults() {
        let config = RemoteConfig.parse([:])

        XCTAssertTrue(config.servingEnabled)
        XCTAssertTrue(config.fallbackEnabled)
        XCTAssertNil(config.requestTimeoutMs)
        XCTAssertEqual(config.configTtlSeconds, 3600)
        XCTAssertTrue(config.placements.isEmpty)
    }

    func testWireDates() {
        XCTAssertEqual(WireDates.isoMillis("2026-01-01T00:00:00Z"), 1_767_225_600_000)
        XCTAssertEqual(WireDates.isoMillis("2026-01-01T00:00:00.250Z"), 1_767_225_600_250)
        XCTAssertNil(WireDates.isoMillis("yesterday"))
        XCTAssertEqual(WireDates.httpMillis("Thu, 01 Jan 2026 00:00:00 GMT"), 1_767_225_600_000)
    }

    func testOnlyWebUrlsAreAccepted() {
        XCTAssertTrue(isWebURL("https://example.ge/offer"))
        XCTAssertTrue(isWebURL("http://example.ge"))
        XCTAssertFalse(isWebURL("javascript:alert(1)"))
        XCTAssertFalse(isWebURL("itms-apps://apps.apple.com"))
        XCTAssertFalse(isWebURL("https://"))
    }

    func testFormatWireNames() {
        XCTAssertEqual(QartveloAdFormat(wireName: "Rewarded"), .rewarded)
        XCTAssertNil(QartveloAdFormat(wireName: "native"))
        XCTAssertEqual(QartveloAdsErrorCode.noFill.description, "NO_FILL")
        XCTAssertEqual(QartveloAdSource.admob.description, "ADMOB")
    }
}
