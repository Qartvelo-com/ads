import XCTest
@testable import QartveloAds

final class SetupIssueTests: XCTestCase {
    private let base = URL(string: "https://ads.test/")!
    private var observer: RecordingDelegate!

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
            "placements": [
                ["code": "level_end", "format": "interstitial"],
                ["code": "home_banner", "format": "banner"],
            ],
        ])
        observer = RecordingDelegate()
        QartveloAds.addObserver(observer)
    }

    override func tearDown() {
        QartveloAds.resetForTests()
        StubURLProtocol.reset()
        super.tearDown()
    }

    private func initialize() {
        let options = QartveloAdsOptions()
        options.baseURL = base
        options.requestTimeoutMs = 2_000
        let done = expectation(description: "initialize finished")
        QartveloAds.initialize(appKey: "app_\(UUID().uuidString)", options: options) { _, _ in done.fulfill() }
        wait(for: [done], timeout: 10)
    }

    /// Runs `trigger` and waits until the observer has received a setup issue.
    private func expectIssue(_ trigger: () -> Void) -> QartveloAdsSetupIssue {
        let found = expectation(description: "setup issue")
        found.assertForOverFulfill = false
        observer.onEvent = { if $0.hasPrefix("setupIssue") { found.fulfill() } }
        trigger()
        wait(for: [found], timeout: 10)
        return observer.setupIssues[0]
    }

    private func drainMain() {
        let drained = expectation(description: "main queue drained")
        DispatchQueue.main.async { drained.fulfill() }
        wait(for: [drained], timeout: 5)
    }

    private func rejectInit(_ code: String, details: [String: Any]?) {
        var error: [String: Any] = ["code": code, "message": "rejected"]
        if let details = details { error["details"] = details }
        StubURLProtocol.route("/api/v1/sdk/initialize", status: 403, json: ["error": error])
    }

    func testPackageMismatchNamesBothBundleIdsOnce() {
        rejectInit("package_mismatch", details: ["registered_package": "com.kakha13.beergame", "platform": "android"])
        let issue = expectIssue { initialize() }
        XCTAssertEqual(issue.code, QartveloAdsSetupIssue.packageMismatch)
        XCTAssertNil(issue.placementId)
        XCTAssertTrue(issue.message.contains("com.kakha13.beergame"), issue.message)
        drainMain()
        XCTAssertEqual(observer.setupIssues.count, 1)
    }

    func testPackageMismatchIsReportedEvenWhenTestModeHidesTheInitFailure() {
        TestHooks.simulator = true
        rejectInit("package_mismatch", details: ["registered_package": "com.kakha13.beergame", "platform": "android"])
        let issue = expectIssue { initialize() }
        XCTAssertEqual(issue.code, QartveloAdsSetupIssue.packageMismatch)
    }

    func testOlderBackendsWithoutDetailsStillGetAMessage() {
        rejectInit("package_mismatch", details: nil)
        let issue = expectIssue { initialize() }
        XCTAssertTrue(issue.message.contains("is not registered for"), issue.message)
    }

    func testPlatformMismatchNamesThePlatformOfTheKey() {
        rejectInit("platform_mismatch", details: ["platform": "android"])
        let issue = expectIssue { initialize() }
        XCTAssertEqual(issue.code, QartveloAdsSetupIssue.platformMismatch)
        XCTAssertTrue(issue.message.contains("the Android app"), issue.message)
    }

    func testOtherInitErrorsAreNotSetupIssues() {
        StubURLProtocol.route("/api/v1/sdk/initialize", status: 401, json: ["error": ["code": "invalid_app_key", "message": "Unknown app key."]])
        initialize()
        drainMain()
        XCTAssertTrue(observer.setupIssues.isEmpty)
    }

    /// Loads an interstitial and waits for the load to end.
    private func load(_ placementId: String) {
        let delegate = RecordingDelegate()
        let finished = expectation(description: "load of \(placementId) finished")
        finished.assertForOverFulfill = false
        delegate.onEvent = { event in
            if event.hasPrefix("loaded") || event.hasPrefix("failed") { finished.fulfill() }
        }
        QartveloAds.loadInterstitial(placementId, delegate: delegate)
        wait(for: [finished], timeout: 10)
    }

    func testUnknownPlacementIsReportedOncePerCode() {
        initialize()
        load("level_up")
        load("level_up")
        drainMain()
        XCTAssertEqual(observer.setupIssues.map(\.code), [QartveloAdsSetupIssue.unknownPlacement])
        XCTAssertEqual(observer.setupIssues.first?.placementId, "level_up")
        XCTAssertTrue(observer.setupIssues.first?.message.contains("format interstitial") == true)
    }

    func testFormatMismatchIsReported() {
        initialize()
        load("home_banner")
        drainMain()
        XCTAssertEqual(observer.setupIssues.map(\.code), [QartveloAdsSetupIssue.formatMismatch])
        XCTAssertTrue(observer.setupIssues.first?.message.contains("format banner") == true)
    }

    func testKnownPlacementsReportNothing() {
        initialize()
        load("level_end")
        drainMain()
        XCTAssertTrue(observer.setupIssues.isEmpty)
    }

    func testRejectedKeysNeverJudgePlacements() {
        rejectInit("package_mismatch", details: nil)
        initialize()
        load("level_up")
        drainMain()
        XCTAssertEqual(observer.setupIssues.map(\.code), [QartveloAdsSetupIssue.packageMismatch])
    }
}
