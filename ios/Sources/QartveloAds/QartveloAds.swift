import UIKit

/// Entry point of the Qartvelo Ads SDK. Every method is safe to call from any thread, never throws, and
/// delivers callbacks on the main thread. No networking or disk access happens on the caller's thread.
@objc public final class QartveloAds: NSObject {
    @objc public static let sdkVersion = "0.5.0"

    private static let lock = NSLock()
    private static var currentEngine: Engine?
    private static var privacy = QartveloAdsPrivacy()
    private static var registered: QartveloFallbackAdapter?
    private static var logLevelOverride: QartveloAdsLogLevel?

    private override init() {
        super.init()
    }

    /// Initializes the SDK once (idempotent). Later calls are ignored apart from `completion`, which
    /// receives the result of the first initialization. `success == false` still leaves the SDK usable:
    /// it runs on the last cached remote configuration (if any), retries the session lazily and can
    /// fall back to AdMob. In test mode, backend registration failures do not block local test ads.
    @objc public static func initialize(
        appKey: String,
        options: QartveloAdsOptions = QartveloAdsOptions(),
        completion: ((Bool, QartveloAdsError?) -> Void)? = nil
    ) {
        Main.run {
            lock.lock()
            if let existing = currentEngine {
                lock.unlock()
                if existing.appKey != appKey.trimmingCharacters(in: .whitespacesAndNewlines) || existing.options !== options {
                    Log.i("QartveloAds is already initialized; ignoring the new appKey/options")
                }
                existing.addInitCompletion(completion)
                return
            }
            Log.level = logLevelOverride ?? options.logLevel
            let key = appKey.trimmingCharacters(in: .whitespacesAndNewlines)
            if key.isEmpty && !Engine.shouldUseTestMode(options) {
                lock.unlock()
                Log.e("QartveloAds.initialize called with an empty appKey")
                if let completion = completion {
                    Main.post { completion(false, QartveloAdsError(.notInitialized, "appKey is empty")) }
                }
                return
            }
            let created = Engine(appKey: key, options: options)
            currentEngine = created
            lock.unlock()
            created.start(completion)
        }
    }

    /// True once the first initialization attempt has finished (possibly in offline/fallback mode).
    @objc public static var isInitialized: Bool {
        engine()?.isReady == true
    }

    @objc public static func loadInterstitial(_ placementId: String, delegate: QartveloAdsDelegate? = nil) {
        load(placementId, format: .interstitial, delegate: delegate)
    }

    @objc public static func showInterstitial(_ placementId: String, from viewController: UIViewController, delegate: QartveloAdsDelegate? = nil) {
        show(placementId, format: .interstitial, from: viewController, delegate: delegate)
    }

    /// Main thread.
    @objc public static func isInterstitialReady(_ placementId: String) -> Bool {
        isReady(placementId, format: .interstitial)
    }

    @objc public static func loadRewarded(_ placementId: String, delegate: QartveloAdsDelegate? = nil) {
        load(placementId, format: .rewarded, delegate: delegate)
    }

    @objc public static func showRewarded(_ placementId: String, from viewController: UIViewController, delegate: QartveloAdsDelegate? = nil) {
        show(placementId, format: .rewarded, from: viewController, delegate: delegate)
    }

    /// Main thread.
    @objc public static func isRewardedReady(_ placementId: String) -> Bool {
        isReady(placementId, format: .rewarded)
    }

    /// Observes events of every placement and format (in addition to per-call delegates). Held weakly.
    @objc public static func addObserver(_ observer: QartveloAdsDelegate) {
        Listeners.add(observer)
    }

    @objc public static func removeObserver(_ observer: QartveloAdsDelegate) {
        Listeners.remove(observer)
    }

    @objc public static func setLogLevel(_ level: QartveloAdsLogLevel) {
        lock.lock()
        logLevelOverride = level
        lock.unlock()
        Log.level = level
    }

    /// Updates privacy signals; they are forwarded to the fallback adapter. Consent is never assumed.
    public static func setPrivacy(_ privacy: QartveloAdsPrivacy) {
        lock.lock()
        self.privacy = privacy
        lock.unlock()
        if let engine = engine() {
            Main.run { engine.updateAdapterSettings() }
        }
    }

    /// Registers a fallback adapter explicitly; otherwise `QartveloAdsAdMob` is discovered automatically.
    public static func registerFallbackAdapter(_ adapter: QartveloFallbackAdapter) {
        lock.lock()
        registered = adapter
        lock.unlock()
        if let engine = engine() {
            Main.run { engine.installAdapter(adapter) }
        }
    }

    // MARK: - Internals

    static func engine() -> Engine? {
        lock.lock()
        defer { lock.unlock() }
        return currentEngine
    }

    static func registeredAdapter() -> QartveloFallbackAdapter? {
        lock.lock()
        defer { lock.unlock() }
        return registered
    }

    static func currentPrivacy() -> QartveloAdsPrivacy {
        lock.lock()
        defer { lock.unlock() }
        return privacy
    }

    // Calls resolve the engine on the main thread, after any `initialize` posted from the same thread.
    private static func load(_ placementId: String, format: QartveloAdFormat, delegate: QartveloAdsDelegate?) {
        let id = placementId.trimmingCharacters(in: .whitespacesAndNewlines)
        Main.run {
            guard let engine = engine() else {
                fail(delegate, id, .notInitialized, "Call QartveloAds.initialize() first")
                return
            }
            guard !id.isEmpty else {
                fail(delegate, id, .invalidPlacement, "placementId is empty")
                return
            }
            engine.whenReady { controller(engine, id, format).load(delegate) }
        }
    }

    private static func show(_ placementId: String, format: QartveloAdFormat, from viewController: UIViewController, delegate: QartveloAdsDelegate?) {
        let id = placementId.trimmingCharacters(in: .whitespacesAndNewlines)
        Main.run {
            guard let engine = engine() else {
                fail(delegate, id, .notInitialized, "Call QartveloAds.initialize() first")
                return
            }
            guard !id.isEmpty else {
                fail(delegate, id, .invalidPlacement, "placementId is empty")
                return
            }
            if engine.loadsReady {
                controller(engine, id, format).show(from: viewController, delegate: delegate)
            } else {
                // Nothing can have been loaded before start-up finishes.
                Listeners.emit(delegate) { $0.qartveloAdNoAdAvailable?(placementId: id, format: format) }
            }
        }
    }

    private static func isReady(_ placementId: String, format: QartveloAdFormat) -> Bool {
        guard Thread.isMainThread else {
            Log.e("isInterstitialReady/isRewardedReady must be called on the main thread")
            return false
        }
        return engine()?.existingFullscreen(placementId.trimmingCharacters(in: .whitespacesAndNewlines), format: format)?.isReady() == true
    }

    private static func controller(_ engine: Engine, _ id: String, _ format: QartveloAdFormat) -> FullscreenController {
        format == .rewarded ? engine.rewarded(id) : engine.interstitial(id)
    }

    private static func fail(_ delegate: QartveloAdsDelegate?, _ placementId: String, _ code: QartveloAdsErrorCode, _ message: String) {
        Log.e("\(code): \(message)")
        let error = QartveloAdsError(code, message)
        Listeners.emit(delegate) { $0.qartveloAdDidFailToLoad?(placementId: placementId, error: error) }
    }

    /// Tests only: tears down all state so each test starts from a clean process-like state.
    static func resetForTests() {
        lock.lock()
        currentEngine = nil
        registered = nil
        privacy = QartveloAdsPrivacy()
        logLevelOverride = nil
        lock.unlock()
        Listeners.clear()
        TestHooks.reset()
    }
}
