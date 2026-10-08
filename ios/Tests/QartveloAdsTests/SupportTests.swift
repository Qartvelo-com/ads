@testable import QartveloAds
import XCTest

final class SupportTests: XCTestCase {
    override func tearDown() {
        QartveloAds.resetForTests()
        super.tearDown()
    }

    func testProvisioningProfileDebugEntitlement() {
        XCTAssertTrue(AppEnvironment.provisionAllowsDebugging(profile(getTaskAllow: true)))
        XCTAssertFalse(AppEnvironment.provisionAllowsDebugging(profile(getTaskAllow: false)))
        XCTAssertFalse(AppEnvironment.provisionAllowsDebugging(Data("not a profile".utf8)))
    }

    func testAppStoreInstallRequiresProductionReceiptAndNoProvisioningProfile() {
        let production = URL(fileURLWithPath: "/app/_MASReceipt/receipt")
        let sandbox = URL(fileURLWithPath: "/app/_MASReceipt/sandboxReceipt")
        XCTAssertTrue(AppEnvironment.isAppStoreInstall(receiptURL: production, receiptExists: true, hasProvisioningProfile: false))
        XCTAssertFalse(AppEnvironment.isAppStoreInstall(receiptURL: sandbox, receiptExists: true, hasProvisioningProfile: false), "TestFlight is test traffic")
        XCTAssertFalse(AppEnvironment.isAppStoreInstall(receiptURL: nil, receiptExists: false, hasProvisioningProfile: false), "unknown install source stays in test mode")
        XCTAssertFalse(AppEnvironment.isAppStoreInstall(receiptURL: production, receiptExists: false, hasProvisioningProfile: false), "receipt URL alone does not prove an App Store install")
        XCTAssertFalse(AppEnvironment.isAppStoreInstall(receiptURL: production, receiptExists: true, hasProvisioningProfile: true), "sideloaded development, ad hoc and enterprise builds stay in test mode")
    }

    func testTestModeOnlyAllowsAppStoreProductionTraffic() {
        func engineTestMode(simulator: Bool, development: Bool, appStore: Bool, configure: (QartveloAdsOptions) -> Void = { _ in }) -> Bool {
            TestHooks.simulator = simulator
            TestHooks.developmentBuild = development
            TestHooks.appStoreBuild = appStore
            let options = QartveloAdsOptions()
            configure(options)
            return Engine(appKey: "app_test", options: options).testMode
        }

        XCTAssertFalse(engineTestMode(simulator: false, development: false, appStore: true))
        XCTAssertTrue(engineTestMode(simulator: false, development: false, appStore: false))
        XCTAssertTrue(engineTestMode(simulator: false, development: true, appStore: true))
        XCTAssertTrue(engineTestMode(simulator: false, development: true, appStore: false) { $0.testModeInDebugBuilds = false })
        XCTAssertTrue(engineTestMode(simulator: false, development: false, appStore: false) { $0.testModeInDebugBuilds = false })
        XCTAssertTrue(engineTestMode(simulator: true, development: false, appStore: true) { $0.testModeInDebugBuilds = false })
        XCTAssertTrue(engineTestMode(simulator: false, development: false, appStore: true) { $0.testMode = true })
    }

    func testAboutLinkCarriesTheBundleId() {
        let url = AboutLink.url(baseURL: URL(string: "https://ads.qartvelo.com/")!, bundleId: "ge.example.Game")
        XCTAssertEqual(url?.absoluteString, "https://ads.qartvelo.com/?ref=ge.example.Game")
    }

    func testPublicTestBannerMatchesTheBackendWidestFitRule() {
        for (available, width, height) in [(0, 300, 250), (299, 300, 250), (300, 300, 250), (467, 320, 0), (468, 468, 60), (727, 468, 60), (728, 728, 90), (1320, 728, 90)] {
            let creative = ServerTestAds.creative(for: .banner, availableWidth: available)
            XCTAssertEqual(creative.width, width)
            if width == 320 {
                XCTAssertTrue([50, 100].contains(creative.height))
            } else {
                XCTAssertEqual(creative.height, height)
            }
            XCTAssertEqual(creative.filename, "banner_\(creative.width)x\(creative.height).png")
            let ad = ServerTestAds.make(placementId: "home", format: .banner, creative: creative, creativeURL: URL(string: "https://ads.test/test-ads/\(creative.filename)")!)
            XCTAssertEqual(ad.width, creative.width)
            XCTAssertEqual(ad.height, creative.height)
            XCTAssertTrue(ad.test)
            XCTAssertTrue(ad.localTest, "public assets must not enqueue billable events")
        }
    }

    func testAdaptiveSizeUsesLogicalWidthAndBoundsTheHeight() {
        for (width, height) in [(320.0, 50.0), (375, 58), (390, 60), (393, 61), (402, 62), (414, 64), (430, 67), (440, 68), (728, 90)] {
            XCTAssertEqual(AdaptiveBannerLayout.size(width: width, screenHeight: 956), CGSize(width: width, height: height))
        }
        XCTAssertEqual(AdaptiveBannerLayout.size(width: 844, screenHeight: 390).height, 58)
        XCTAssertEqual(AdaptiveBannerLayout.size(width: 220, screenHeight: 956).height, 50)
        XCTAssertEqual(AdaptiveBannerLayout.size(width: 393, screenHeight: 956, preferred: CGSize(width: 393, height: 63)).height, 63)
        XCTAssertEqual(AdaptiveBannerLayout.size(width: 393, screenHeight: 956, preferred: CGSize(width: 440, height: 68)).height, 61)
        XCTAssertEqual(AdaptiveBannerLayout.size(width: 393, screenHeight: 956, preferred: CGSize(width: 393, height: 250)).height, 61)
    }

    func testCoreUsesTheRegisteredAdaptersAdaptiveHeight() {
        let engine = Engine(appKey: "app_test", options: QartveloAdsOptions())
        let adapter = StubAdapter()
        adapter.preferredBannerHeight = 63
        engine.installAdapter(adapter)
        XCTAssertEqual(engine.adaptiveBannerSize(width: 393, screenHeight: 956).height, 63)
        adapter.preferredBannerHeight = 0
        XCTAssertEqual(engine.adaptiveBannerSize(width: 393, screenHeight: 956).height, 61)
    }

    func testAdaptivePublicArtworkMatchesTheRequestedAspectRatio() {
        for (width, height, filename) in [(960, 150, "960x150"), (1320, 204, "1320x204"), (2184, 270, "2184x270"), (780, 120, "1320x204")] {
            let creative = ServerTestAds.creative(for: .banner, availableWidth: width, availableHeight: height)
            XCTAssertEqual(creative.filename, "banner_adaptive_v1_\(filename).png")
            XCTAssertTrue(creative.width >= width || filename == "2184x270")
        }
    }

    func testImageSignaturesAndCacheNames() {
        XCTAssertTrue(CreativeCache.hasImageSignature([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0]))
        XCTAssertTrue(CreativeCache.hasImageSignature([0xFF, 0xD8, 0xFF, 0xE0, 0, 0, 0, 0, 0, 0, 0, 0]))
        XCTAssertTrue(CreativeCache.hasImageSignature(Array("RIFF\0\0\0\0WEBP".utf8)))
        XCTAssertFalse(CreativeCache.hasImageSignature(Array("<!DOCTYPE html>".utf8)))
        XCTAssertFalse(CreativeCache.hasImageSignature([0x89, 0x50]))

        XCTAssertEqual(CreativeCache.fileExtension(URL(string: "https://cdn.example/a/b.PNG?sig=1")!), ".png")
        XCTAssertEqual(CreativeCache.fileExtension(URL(string: "https://cdn.example/a/creative")!), "")
        XCTAssertEqual(CreativeCache.hash("https://cdn.example/a.png").count, 32)
    }

    func testFallbackUnitResolution() {
        TestHooks.simulator = false
        TestHooks.developmentBuild = false
        TestHooks.appStoreBuild = true
        let options = QartveloAdsOptions()
        options.admobAdUnits = ["level_end": "ca-app-pub-1/local"]
        let engine = Engine(appKey: "app_test", options: options)
        let placement = PlacementConfig(
            code: "level_end", format: .interstitial, qartveloEnabled: true, fallbackProvider: "admob",
            admobAdUnitId: "ca-app-pub-1/server", requestTimeoutMs: nil, bannerRefreshSeconds: 60
        )

        XCTAssertNil(engine.fallbackUnit("level_end", placement: placement), "no adapter installed")

        engine.installAdapter(StubAdapter())
        XCTAssertEqual(engine.fallbackUnit("level_end", placement: placement), "ca-app-pub-1/local")
        XCTAssertEqual(engine.fallbackUnit("other", placement: placement), "ca-app-pub-1/server")
        XCTAssertNil(engine.fallbackUnit("level_end", placement: placement, serverFallback: "none"))
        XCTAssertNil(engine.fallbackUnit("unknown", placement: nil), "nothing configured outside test mode")
    }

    func testEffectiveTimeoutIsClamped() {
        let options = QartveloAdsOptions()
        options.requestTimeoutMs = 50
        let engine = Engine(appKey: "app_test", options: options)
        XCTAssertEqual(engine.effectiveTimeoutMs(nil), Engine.minTimeoutMs)
        let slow = PlacementConfig(
            code: "x", format: nil, qartveloEnabled: true, fallbackProvider: "admob",
            admobAdUnitId: nil, requestTimeoutMs: 60_000, bannerRefreshSeconds: 60
        )
        XCTAssertEqual(engine.effectiveTimeoutMs(slow), Engine.maxTimeoutMs)
    }

    private func profile(getTaskAllow: Bool) -> Data {
        let plist = """
        <?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
        <plist version="1.0"><dict>
        <key>Entitlements</key><dict><key>get-task-allow</key><\(getTaskAllow ? "true" : "false")/></dict>
        </dict></plist>
        """
        // A real profile wraps the plist in a CMS signature; surround it with binary noise.
        return Data([0x30, 0x82, 0x0F, 0x00]) + Data(plist.utf8) + Data([0xA0, 0x82, 0x00])
    }
}
