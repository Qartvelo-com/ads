import Foundation
import UIKit

/// Why Qartvelo Ads could not serve a load; `reason` is the `qartveloAdDidStartFallback` string.
struct QartveloAdsFailure {
    static let noFill = "no_fill"
    static let timeout = "timeout"
    static let error = "error"
    static let creativeFailed = "creative_failed"
    static let disabled = "disabled"

    let reason: String
    let error: QartveloAdsError
    var serverFallback: String?
}

enum FetchOutcome {
    case success(ServedAd)
    case failure(QartveloAdsFailure)
}

/// Everything created by one `QartveloAds.initialize` call. Controllers and mutable state are confined
/// to the main thread; network and disk work runs on `io`, `downloads` and the session queue.
final class Engine {
    static let defaultTimeoutMs: Int64 = 800
    static let minTimeoutMs: Int64 = 100
    static let maxTimeoutMs: Int64 = 10_000
    static let minInitTimeoutMs: Int64 = 3_000
    static let fallbackTelemetryTimeoutMs: Int64 = 5_000
    static let defaultConfigTtlSeconds: Int64 = 3_600
    static let disabledRecheckMs: Int64 = 5 * 60_000
    static let configRetryMs: Int64 = 60_000

    let appKey: String
    let options: QartveloAdsOptions
    let io = DispatchQueue(label: "com.qartvelo.ads.io", qos: .utility, attributes: .concurrent)
    private let downloads = DispatchQueue(label: "com.qartvelo.ads.downloads", qos: .utility, attributes: .concurrent)

    let api: ApiClient
    let creatives: CreativeCache
    let events: EventQueue
    private(set) var sessions: SessionManager!
    private let configStore: ConfigStore

    private let stateLock = NSLock()
    private let device: DeviceInfo
    private var storedRemoteConfig: RemoteConfig?
    /// When the backend last delivered config (0 = only cached or none).
    private var configFetchedAt: Int64 = 0
    /// When a config refresh was last started or delivered; throttles refreshes.
    private var configCheckedAt: Int64 = 0

    var remoteConfig: RemoteConfig? {
        stateLock.lock()
        defer { stateLock.unlock() }
        return storedRemoteConfig
    }

    // Main-thread state.
    private(set) var fallbackAdapter: QartveloFallbackAdapter?
    /// True once the first initialization attempt has finished (`QartveloAds.isInitialized`).
    private(set) var isReady = false
    /// True once loads may run: the cached config has been read and the fallback adapter installed,
    /// and either a cached config exists, the first initialization finished, or the load grace passed.
    /// Loads never wait for a slow `/sdk/initialize`; the ad request acquires the session within its
    /// own timeout, so a hanging backend falls back quickly.
    private(set) var loadsReady = false
    private var pending: [() -> Void] = []
    private var initCompletions: [(Bool, QartveloAdsError?) -> Void] = []
    private var initOutcome: (Bool, QartveloAdsError?)?
    private var interstitials: [String: FullscreenController] = [:]
    private var rewardeds: [String: FullscreenController] = [:]
    private var banners: [String: BannerController] = [:]

    /// Only one full-screen ad (Qartvelo Ads or fallback) may be on screen at a time.
    var fullscreenShowing = false

    let simulator: Bool

    /// Test mode comes from SDK options and the current installation, never cached remote config.
    /// An App Store installation can return to live traffic on the next launch after its explicit
    /// test option is turned off; a previous test session must not keep it on Google test units.
    /// The Simulator and every installation outside the App Store always use test mode, regardless
    /// of either test option. This includes development, ad hoc, enterprise and TestFlight builds.
    /// App Store installations may explicitly request test mode too.
    let testMode: Bool

    static func shouldUseTestMode(_ options: QartveloAdsOptions) -> Bool {
        options.testMode || !AppEnvironment.isAppStoreBuild
    }

    /// Main thread.
    init(appKey: String, options: QartveloAdsOptions) {
        self.appKey = appKey
        self.options = options
        let device = DeviceInfo.collect()
        let simulator = AppEnvironment.isSimulator
        let client = ApiClient(
            baseURL: options.baseURL,
            userAgent: "QartveloAds-iOS/\(QartveloAds.sdkVersion) (iOS \(device.osVersion); \(device.bundleId))"
        )
        self.device = device
        self.simulator = simulator
        testMode = Self.shouldUseTestMode(options)
        api = client
        creatives = CreativeCache(api: client)
        events = EventQueue(api: client)
        configStore = ConfigStore(appKey: appKey)
        sessions = SessionManager(
            api: client,
            initBody: { [unowned self] in initBody() },
            initTimeoutMs: max(Int64(options.requestTimeoutMs), Self.minInitTimeoutMs),
            onInitialized: { [unowned self] result in onSessionInitialized(result) },
            onFailed: { [unowned self] error in onSessionFailed(error) }
        )
    }

    // MARK: - Initialization

    /// Main thread. Loads the cached config, discovers the adapter and requests a session.
    func start(_ completion: ((Bool, QartveloAdsError?) -> Void)?) {
        addInitCompletion(completion)
        if simulator {
            Log.i("Simulator: test mode is on, ads are labelled \"Test ad\" and never billed")
        } else if testMode && !options.testMode {
            Log.i("Non-App Store install: test mode is on, ads are labelled \"Test ad\" and never counted or billed")
        }
        let adapter = QartveloAds.registeredAdapter() ?? FallbackDiscovery.discover()
        if testMode && appKey.isEmpty {
            installAdapter(adapter)
            Log.i("Empty app key in test mode; using public server test creatives without an app session")
            finishInit(nil)
            return
        }
        io.async { [self] in
            if let cached = configStore.load() {
                stateLock.lock()
                if storedRemoteConfig == nil {
                    storedRemoteConfig = cached
                    Log.i("Using cached remote configuration until the backend answers")
                }
                stateLock.unlock()
            }
            creatives.purgeExpired()
            let hasConfig = remoteConfig != nil
            Main.post { [self] in
                installAdapter(adapter)
                if hasConfig {
                    openLoads()
                } else {
                    Main.postDelayed(loadGraceMs()) { [self] in openLoads() }
                }
            }
            guard api.isConfigured else {
                Main.post { [self] in
                    if testMode {
                        Log.i("Backend URL unavailable in test mode; public server test creatives are unavailable")
                        finishInit(nil)
                    } else {
                        finishInit(QartveloAdsError(.internalError, "Invalid baseURL"))
                    }
                }
                return
            }
            stateLock.lock()
            configCheckedAt = Clock.now()
            stateLock.unlock()
            sessions.refreshAsync(force: true) { [self] error in
                Main.post { [self] in
                    if let error = error, testMode {
                        Log.i("Backend initialization unavailable in test mode; public test creatives are ready (\(SessionManager.describe(error)))")
                        finishInit(nil)
                    } else {
                        finishInit(error.map { toInitError($0) })
                    }
                }
            }
        }
    }

    /// Without any cached config, the first loads wait this long for `/sdk/initialize` so they know
    /// the server's placement settings and AdMob units; with a cached config they do not wait at all.
    private func loadGraceMs() -> Int64 {
        let local = options.requestTimeoutMs > 0 ? Int64(options.requestTimeoutMs) : Self.defaultTimeoutMs
        return min(max(local, Self.minTimeoutMs), Self.maxTimeoutMs)
    }

    /// Main thread.
    func addInitCompletion(_ completion: ((Bool, QartveloAdsError?) -> Void)?) {
        guard let completion = completion else { return }
        if let outcome = initOutcome {
            Main.post { completion(outcome.0, outcome.1) }
        } else {
            initCompletions.append(completion)
        }
    }

    /// Runs `block` on the main thread once loads may run (see `loadsReady`).
    func whenReady(_ block: @escaping () -> Void) {
        Main.run { [self] in
            if loadsReady { block() } else { pending.append(block) }
        }
    }

    /// Main thread. Releases loads queued during start-up.
    private func openLoads() {
        guard !loadsReady else { return }
        loadsReady = true
        let queued = pending
        pending.removeAll()
        queued.forEach { $0() }
    }

    private func finishInit(_ error: QartveloAdsError?) {
        guard initOutcome == nil else { return }
        let outcome = (error == nil, error)
        initOutcome = outcome
        isReady = true
        if let error = error {
            Log.e("QartveloAds initialization failed: \(error.message)")
        } else {
            Log.i("QartveloAds initialized")
        }
        let completions = initCompletions
        initCompletions.removeAll()
        completions.forEach { completion in Main.post { completion(outcome.0, outcome.1) } }
        openLoads()
    }

    private func onSessionInitialized(_ result: InitResult) {
        stateLock.lock()
        storedRemoteConfig = result.config
        configFetchedAt = Clock.now()
        configCheckedAt = configFetchedAt
        stateLock.unlock()
        configStore.save(result.cacheableJSON)
        Main.post { [self] in updateAdapterSettings() }
    }

    private let issuesLock = NSLock()
    private var reportedSetupIssues = Set<String>()

    /// Logs a setup issue once per process and tells the global observers. Any thread.
    func reportSetupIssue(_ code: String, _ message: String, placementId: String? = nil) {
        issuesLock.lock()
        let isNew = reportedSetupIssues.insert("\(code)|\(placementId ?? "")").inserted
        issuesLock.unlock()
        guard isNew else { return }
        Log.e("Setup issue (\(code)): \(message)")
        let issue = QartveloAdsSetupIssue(code: code, message: message, placementId: placementId)
        Listeners.emit([]) { $0.qartveloAdsDidReportSetupIssue?(issue) }
    }

    /// Session queue. Turns rejected app keys into setup issues, also in test mode where the failed
    /// initialization itself is reported to the app as success.
    private func onSessionFailed(_ error: Error) {
        guard let api = error as? ApiError else { return }
        let running = device.bundleId
        switch api.code {
        case QartveloAdsSetupIssue.packageMismatch:
            let message: String
            if let registered = api.details?.string("registered_package") {
                message = "This app key is registered for '\(registered)', but this app is '\(running)'. Use the key of the app registered for '\(running)', or correct the bundle ID in the Qartvelo Ads dashboard."
            } else {
                message = "This app key is not registered for '\(running)'. Use the key of the app registered for '\(running)', or correct the bundle ID in the Qartvelo Ads dashboard."
            }
            reportSetupIssue(QartveloAdsSetupIssue.packageMismatch, message)
        case QartveloAdsSetupIssue.platformMismatch:
            let owner = api.details?.string("platform") == "android" ? "the Android app" : "an app of another platform"
            reportSetupIssue(QartveloAdsSetupIssue.platformMismatch, "This app key belongs to \(owner). Register this iOS app in the Qartvelo Ads dashboard and use its own key.")
        default:
            break
        }
    }

    private func toInitError(_ error: Error) -> QartveloAdsError {
        switch error {
        case let api as ApiError: return QartveloAdsError(.notInitialized, "\(api.code): rejected by the Qartvelo Ads backend")
        case is TimeoutError: return QartveloAdsError(.timeout, "Qartvelo Ads backend did not answer in time")
        case is NetworkError: return QartveloAdsError(.networkError, "Qartvelo Ads backend unreachable")
        default: return QartveloAdsError(.internalError, String(describing: type(of: error)))
        }
    }

    // MARK: - Fallback adapter

    /// Main thread.
    func installAdapter(_ adapter: QartveloFallbackAdapter?) {
        guard let adapter = adapter, adapter !== fallbackAdapter else { return }
        adapter.initialize(settings: fallbackSettings())
        fallbackAdapter = adapter
    }

    /// Main thread.
    func updateAdapterSettings() {
        fallbackAdapter?.updateSettings(fallbackSettings())
    }

    private func fallbackSettings() -> QartveloFallbackSettings {
        QartveloFallbackSettings(testMode: testMode, privacy: QartveloAds.currentPrivacy())
    }

    /// The fallback ad unit for a placement, or nil when fallback is impossible: adapter missing,
    /// disabled locally or remotely, or no unit configured. In test mode the adapter substitutes
    /// Google's test units, so an unmapped placement still falls back (with an empty id).
    func fallbackUnit(_ placementId: String, placement: PlacementConfig?, serverFallback: String? = nil) -> String? {
        guard options.admobFallback, fallbackAdapter != nil else { return nil }
        if remoteConfig?.fallbackEnabled == false { return nil }
        if placement?.fallbackProvider == "none" || serverFallback == "none" { return nil }
        let local = options.admobAdUnits[placementId].flatMap { $0.trimmingCharacters(in: .whitespaces).isEmpty ? nil : $0 }
        if let unit = local ?? placement?.admobAdUnitId { return unit }
        return testMode ? "" : nil
    }

    /// Fire-and-forget fallback telemetry (`/events/fallback`).
    func reportFallback(_ placementId: String, reason: String) {
        guard let token = sessions.currentToken() else { return }
        let wireReason = reason == QartveloAdsFailure.disabled ? QartveloAdsFailure.noFill : reason
        let body: JSON = ["session_token": token, "placement": placementId, "reason": wireReason]
        io.async { [self] in
            do {
                _ = try api.postEvent("api/v1/events/fallback", body: body, timeoutMs: Self.fallbackTelemetryTimeoutMs)
            } catch {
                Log.d("Fallback telemetry not delivered")
            }
        }
    }

    // MARK: - Placements

    func placement(_ placementId: String) -> PlacementConfig? {
        remoteConfig?.placements[placementId]
    }

    func qartveloEnabled(_ placement: PlacementConfig?) -> Bool {
        remoteConfig?.servingEnabled != false && placement?.qartveloEnabled != false
    }

    /// Remote per-placement timeout wins, otherwise the publisher's option.
    func effectiveTimeoutMs(_ placement: PlacementConfig?) -> Int64 {
        let local = options.requestTimeoutMs > 0 ? Int64(options.requestTimeoutMs) : Self.defaultTimeoutMs
        return min(max(placement?.requestTimeoutMs ?? local, Self.minTimeoutMs), Self.maxTimeoutMs)
    }

    /// Main thread.
    func interstitial(_ placementId: String) -> FullscreenController {
        if let existing = interstitials[placementId] { return existing }
        let created = FullscreenController(engine: self, placementId: placementId, format: .interstitial)
        interstitials[placementId] = created
        return created
    }

    /// Main thread.
    func rewarded(_ placementId: String) -> FullscreenController {
        if let existing = rewardeds[placementId] { return existing }
        let created = FullscreenController(engine: self, placementId: placementId, format: .rewarded)
        rewardeds[placementId] = created
        return created
    }

    /// Main thread.
    func existingFullscreen(_ placementId: String, format: QartveloAdFormat) -> FullscreenController? {
        format == .rewarded ? rewardeds[placementId] : interstitials[placementId]
    }

    /// Main thread. One controller per placement survives view re-creation.
    func banner(_ placementId: String) -> BannerController {
        if let existing = banners[placementId] { return existing }
        let created = BannerController(engine: self, placementId: placementId)
        banners[placementId] = created
        return created
    }

    func adaptiveBannerSize(width: CGFloat, screenHeight: CGFloat) -> CGSize {
        AdaptiveBannerLayout.size(width: width, screenHeight: screenHeight, preferred: fallbackAdapter?.adaptiveBannerSize(width: width) ?? .zero)
    }

    // MARK: - Fetch pipeline

    /// Requests a Qartvelo Ads ad bounded by `timeoutMs` (session acquisition included), then
    /// pre-downloads and validates the creative so that a creative failure becomes a fallback rather
    /// than a broken show. `onResult` is called exactly once, on the main thread. Main thread.
    func fetchAd(_ placementId: String, format: QartveloAdFormat, timeoutMs: Int64, bannerWidth: Int? = nil, bannerHeight: Int? = nil, onResult: @escaping (FetchOutcome) -> Void) {
        var finished = false
        let finish: (FetchOutcome) -> Void = { outcome in
            guard !finished else { return }
            finished = true
            onResult(outcome)
        }
        let useTestCreative: (QartveloAdsFailure) -> Void = { [self] fallback in
            guard !finished else { return }
            // Reserve the callback while the public asset is being downloaded. A late API response
            // must not replace the fallback that was selected after timeout or request failure.
            finished = true
            serverTestOutcome(placementId, format: format, bannerWidth: bannerWidth, bannerHeight: bannerHeight, fallback: fallback) { outcome in onResult(outcome) }
        }
        if testMode && options.testForceNoFill {
            Log.i("Forced no fill for '\(placementId)'; using test fallback")
            finish(.failure(failure(QartveloAdsFailure.noFill)))
            return
        }
        if testMode && appKey.isEmpty {
            useTestCreative(failure(QartveloAdsFailure.error))
            return
        }

        let tracker = CallTracker()
        var responded = false
        let timeout = Main.postDelayed(timeoutMs) { [self] in
            if !responded && !finished {
                tracker.cancel()
                if testMode {
                    useTestCreative(failure(QartveloAdsFailure.timeout))
                } else {
                    finish(.failure(failure(QartveloAdsFailure.timeout)))
                }
            }
        }
        let deadline = Clock.now() + timeoutMs
        io.async { [self] in
            let result: Result<AdResponse, Error>
            do {
                result = .success(try requestWithSessionRetry(placementId, format: format, bannerWidth: bannerWidth, bannerHeight: bannerHeight, deadline: deadline, tracker: tracker))
            } catch {
                result = .failure(error)
            }
            Main.post { [self] in
                guard !finished else { return }
                responded = true
                timeout.cancel()
                switch result {
                case .success(.fill(let ad)):
                    prepareCreative(ad, format: format, tracker: tracker) { [self] outcome in
                        if case .failure(let creativeFailure) = outcome, testMode {
                            useTestCreative(creativeFailure)
                        } else {
                            finish(outcome)
                        }
                    }
                case .success(.noFill(_, let fallback, let reason)):
                    Log.i("Qartvelo Ads no fill for '\(placementId)' (\(reason ?? "unknown"))")
                    let noFill = failure(QartveloAdsFailure.noFill, serverFallback: fallback)
                    if testMode { useTestCreative(noFill) } else { finish(.failure(noFill)) }
                case .failure(let error):
                    let requestFailure = failureFor(error)
                    if testMode { useTestCreative(requestFailure) } else { finish(.failure(requestFailure)) }
                }
            }
        }
    }

    private func serverTestOutcome(_ placementId: String, format: QartveloAdFormat, bannerWidth: Int?, bannerHeight: Int?, fallback: QartveloAdsFailure, done: @escaping (FetchOutcome) -> Void) {
        do {
            let creative = ServerTestAds.creative(for: format, availableWidth: bannerWidth ?? device.screenWidth, availableHeight: bannerHeight)
            guard let url = api.testCreativeURL(creative.filename) else {
                throw NetworkError(message: "invalid base URL for public test creative")
            }
            let ad = ServerTestAds.make(placementId: placementId, format: format, creative: creative, creativeURL: url)
            downloads.async { [self] in
                do {
                    ad.file = try creatives.fetch(ad)
                    Log.i("Serving public Qartvelo test creative \(creative.filename) for '\(placementId)' after backend unavailable")
                    Main.post { done(.success(ad)) }
                } catch {
                    Log.e("Public Qartvelo test creative unavailable: \(SessionManager.describe(error))")
                    Main.post { done(.failure(fallback)) }
                }
            }
        } catch {
            Log.e("Public Qartvelo test creative unavailable: \(SessionManager.describe(error))")
            Main.post { done(.failure(fallback)) }
        }
    }

    /// Called before every load decision (full-screen and banner), including loads that Qartvelo Ads
    /// itself would not serve, so a kill switch that is turned back on reaches a running app.
    /// Re-fetches remote config (and the session) in the background when it is stale:
    /// - after `config_ttl_seconds`;
    /// - sooner (at most `disabledRecheckMs`) while Qartvelo Ads is switched off for `placement`;
    /// - every `configRetryMs` while only a cached config (or none) is known, e.g. after an offline start.
    /// Returns true when a refresh was started; the caller then asks the backend for an ad even if the
    /// stale config says Qartvelo Ads is off, because `/ads/request` re-checks every kill switch itself.
    func refreshConfigIfStale(_ placement: PlacementConfig?) -> Bool {
        // Without an app key there can be no valid session or remote placement config. Test-mode
        // fallback uses the public `/test-ads/` files directly and must not trigger API requests.
        guard api.isConfigured, !(testMode && appKey.isEmpty) else { return false }
        let enabled = qartveloEnabled(placement)
        let ttlMs = (remoteConfig?.configTtlSeconds ?? Self.defaultConfigTtlSeconds) * 1000
        stateLock.lock()
        let intervalMs: Int64
        if configFetchedAt == 0 {
            intervalMs = Self.configRetryMs
        } else if !enabled {
            intervalMs = min(ttlMs, Self.disabledRecheckMs)
        } else {
            intervalMs = ttlMs
        }
        let now = Clock.now()
        if now - configCheckedAt < intervalMs {
            stateLock.unlock()
            return false
        }
        configCheckedAt = now // At most one refresh per window, even if it fails.
        stateLock.unlock()
        Log.d("Remote configuration is stale; refreshing")
        sessions.refreshAsync(force: true)
        return true
    }

    private func prepareCreative(_ ad: ServedAd, format: QartveloAdFormat, tracker: CallTracker, done: @escaping (FetchOutcome) -> Void) {
        if ad.format != format || Clock.now() >= ad.expiresAt - ServedAd.expiryMarginMs {
            Log.e("Discarding unusable Qartvelo Ads response (format mismatch or already expired)")
            done(.failure(failure(QartveloAdsFailure.error)))
            return
        }
        downloads.async { [self] in
            let outcome: FetchOutcome
            do {
                ad.file = try creatives.fetch(ad, tracker: tracker)
                Log.d("Creative ready for \(ad.requestId)")
                outcome = .success(ad)
            } catch {
                Log.e("Creative unavailable: \(SessionManager.describe(error))")
                outcome = .failure(failure(QartveloAdsFailure.creativeFailed))
            }
            Main.post { done(outcome) }
        }
    }

    /// Blocking. One transparent retry when the backend reports the session expired or invalid.
    private func requestWithSessionRetry(_ placementId: String, format: QartveloAdFormat, bannerWidth: Int?, bannerHeight: Int?, deadline: Int64, tracker: CallTracker) throws -> AdResponse {
        var token = try sessions.acquire(deadline: deadline)
        do {
            return try api.requestAd(body: adRequestBody(placementId, format: format, token: token, bannerWidth: bannerWidth, bannerHeight: bannerHeight), timeoutMs: remaining(deadline), tracker: tracker)
        } catch let error as ApiError where error.isSessionError {
            Log.i("Session rejected (\(error.code)); refreshing once")
            sessions.invalidate(token)
            token = try sessions.acquire(deadline: deadline)
            return try api.requestAd(body: adRequestBody(placementId, format: format, token: token, bannerWidth: bannerWidth, bannerHeight: bannerHeight), timeoutMs: remaining(deadline), tracker: tracker)
        }
    }

    private func remaining(_ deadline: Int64) throws -> Int64 {
        let left = deadline - Clock.now()
        if left <= 0 { throw TimeoutError(message: "Qartvelo Ads request budget exhausted") }
        return left
    }

    private func failureFor(_ error: Error) -> QartveloAdsFailure {
        switch error {
        case is TimeoutError:
            return failure(QartveloAdsFailure.timeout)
        case let api as ApiError where api.code == "placement_not_found" || api.code == "format_mismatch":
            return QartveloAdsFailure(
                reason: QartveloAdsFailure.error,
                error: QartveloAdsError(.invalidPlacement, "\(api.code): check the placement code and format")
            )
        case is ApiError, is NetworkError:
            Log.i("Qartvelo Ads request failed: \(SessionManager.describe(error))")
            return failure(QartveloAdsFailure.error)
        default:
            Log.e("Unexpected Qartvelo Ads request failure: \(error)")
            return QartveloAdsFailure(reason: QartveloAdsFailure.error, error: QartveloAdsError(.internalError, String(describing: type(of: error))))
        }
    }

    func failure(_ reason: String, serverFallback: String? = nil) -> QartveloAdsFailure {
        let error: QartveloAdsError
        switch reason {
        case QartveloAdsFailure.noFill, QartveloAdsFailure.disabled:
            error = QartveloAdsError(.noFill, "No Qartvelo Ads campaign available")
        case QartveloAdsFailure.timeout:
            error = QartveloAdsError(.timeout, "Qartvelo Ads did not answer within the timeout")
        case QartveloAdsFailure.creativeFailed:
            error = QartveloAdsError(.creativeFailed, "Qartvelo Ads creative could not be loaded")
        default:
            error = QartveloAdsError(.networkError, "Qartvelo Ads request failed")
        }
        return QartveloAdsFailure(reason: reason, error: error, serverFallback: serverFallback)
    }

    // MARK: - Request bodies

    private func initBody() -> JSON {
        [
            "app_key": appKey,
            "package_name": device.bundleId,
            "sdk_version": QartveloAds.sdkVersion,
            "app_version": device.appVersion,
            "platform": "ios",
            "os_version": device.osVersion,
            "test_mode": testMode,
            "is_emulator": simulator,
        ]
    }

    private func adRequestBody(_ placementId: String, format: QartveloAdFormat, token: String, bannerWidth: Int?, bannerHeight: Int?) -> JSON {
        var body: JSON = [
            "app_key": appKey,
            "placement": placementId,
            "format": format.wireName,
            "session_token": token,
            "language": Self.language(),
            "os_version": device.osMajor,
            "app_version": device.appVersion,
            "sdk_version": QartveloAds.sdkVersion,
            "screen_width": format == .banner ? (bannerWidth ?? device.screenWidth) : device.screenWidth,
            "screen_height": device.screenHeight,
            "test_mode": testMode,
            "test_force_no_fill": options.testForceNoFill,
        ]
        if format == .banner, let height = bannerHeight { body["banner_height"] = height }
        return body
    }

    /// Two-letter language of the user's preferred language, for example `ka` or `en`.
    static func language() -> String {
        guard let preferred = Locale.preferredLanguages.first else { return "en" }
        let code = preferred.prefix { $0 != "-" && $0 != "_" }
        return code.isEmpty ? "en" : code.lowercased()
    }
}
