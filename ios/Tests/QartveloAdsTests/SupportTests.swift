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

    func testTestModeFollowsSimulatorDevelopmentBuildsAndTheOption() {
        func engineTestMode(simulator: Bool, development: Bool, configure: (QartveloAdsOptions) -> Void = { _ in }) -> Bool {
            TestHooks.simulator = simulator
            TestHooks.developmentBuild = development
            let options = QartveloAdsOptions()
            configure(options)
            return Engine(appKey: "app_test", options: options).testMode
        }

        XCTAssertFalse(engineTestMode(simulator: false, development: false))
        XCTAssertTrue(engineTestMode(simulator: true, development: false))
        XCTAssertTrue(engineTestMode(simulator: false, development: true))
        XCTAssertFalse(engineTestMode(simulator: false, development: true) { $0.testModeInDebugBuilds = false })
        XCTAssertTrue(engineTestMode(simulator: true, development: true) { $0.testModeInDebugBuilds = false })
        XCTAssertTrue(engineTestMode(simulator: false, development: false) { $0.testMode = true })
    }

    func testAboutLinkCarriesTheBundleId() {
        let url = AboutLink.url(baseURL: URL(string: "https://ads.qartvelo.com/")!, bundleId: "ge.example.Game")
        XCTAssertEqual(url?.absoluteString, "https://ads.qartvelo.com/?ref=ge.example.Game")
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
