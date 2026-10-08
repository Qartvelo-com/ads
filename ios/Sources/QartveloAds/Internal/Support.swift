import Foundation
import os.log
import UIKit

// MARK: - Logging

enum Log {
    static var level: QartveloAdsLogLevel = .error
    private static let log = OSLog(subsystem: "com.qartvelo.ads", category: "QartveloAds")

    static func e(_ message: @autoclosure () -> String) {
        guard level.rawValue >= QartveloAdsLogLevel.error.rawValue else { return }
        os_log("%{public}@", log: log, type: .error, message())
    }

    static func i(_ message: @autoclosure () -> String) {
        guard level.rawValue >= QartveloAdsLogLevel.info.rawValue else { return }
        os_log("%{public}@", log: log, type: .info, message())
    }

    static func d(_ message: @autoclosure () -> String) {
        guard level.rawValue >= QartveloAdsLogLevel.debug.rawValue else { return }
        os_log("%{public}@", log: log, type: .debug, message())
    }
}

// MARK: - Time and threads

/// Monotonic milliseconds (not affected by wall-clock changes).
enum Clock {
    static func now() -> Int64 {
        Int64(ProcessInfo.processInfo.systemUptime * 1000)
    }
}

enum Main {
    /// Runs now when already on the main thread, otherwise asynchronously on it.
    static func run(_ block: @escaping () -> Void) {
        if Thread.isMainThread {
            block()
        } else {
            DispatchQueue.main.async(execute: block)
        }
    }

    static func post(_ block: @escaping () -> Void) {
        DispatchQueue.main.async(execute: block)
    }

    @discardableResult
    static func postDelayed(_ milliseconds: Int64, _ block: @escaping () -> Void) -> DispatchWorkItem {
        let item = DispatchWorkItem(block: block)
        DispatchQueue.main.asyncAfter(deadline: .now() + .milliseconds(Int(max(0, milliseconds))), execute: item)
        return item
    }
}

/// Overrides for unit tests.
enum TestHooks {
    static var simulator: Bool?
    static var developmentBuild: Bool?
    static var appStoreBuild: Bool?
    static var eventRetryBaseMs: Int64?
    static var creativeGraceMs: Int64?
    /// URL protocols put in front of the SDK's own URLSession (request stubs).
    static var urlProtocols: [AnyClass] = []

    static func reset() {
        simulator = nil
        developmentBuild = nil
        appStoreBuild = nil
        eventRetryBaseMs = nil
        creativeGraceMs = nil
        urlProtocols = []
    }
}

// MARK: - Delegates

/// Global observers plus per-call delegates. Main thread only.
enum Listeners {
    private final class WeakDelegate {
        weak var value: QartveloAdsDelegate?
        init(_ value: QartveloAdsDelegate) { self.value = value }
    }

    private static var observers: [WeakDelegate] = []

    static func add(_ delegate: QartveloAdsDelegate) {
        Main.run {
            observers.removeAll { $0.value == nil || $0.value === delegate }
            observers.append(WeakDelegate(delegate))
        }
    }

    static func remove(_ delegate: QartveloAdsDelegate) {
        Main.run { observers.removeAll { $0.value == nil || $0.value === delegate } }
    }

    static func clear() {
        observers.removeAll()
    }

    /// Calls `event` on each distinct target delegate and, unless `includeGlobal` is false, on the
    /// global observers. Always on the main thread.
    static func emit(_ targets: [QartveloAdsDelegate?], includeGlobal: Bool = true, _ event: @escaping (QartveloAdsDelegate) -> Void) {
        Main.run {
            var seen: [ObjectIdentifier] = []
            var all: [QartveloAdsDelegate] = targets.compactMap { $0 }
            if includeGlobal {
                all += observers.compactMap { $0.value }
            }
            for delegate in all where !seen.contains(ObjectIdentifier(delegate)) {
                seen.append(ObjectIdentifier(delegate))
                event(delegate)
            }
        }
    }

    static func emit(_ target: QartveloAdsDelegate?, _ event: @escaping (QartveloAdsDelegate) -> Void) {
        emit([target], event)
    }
}

// MARK: - Environment

enum AppEnvironment {
    /// The iOS Simulator is always in test mode, like AdMob test devices: its traffic is never billed.
    static var isSimulator: Bool {
        if let override = TestHooks.simulator { return override }
        #if targetEnvironment(simulator)
        return true
        #else
        return false
        #endif
    }

    /// A development build signed with `get-task-allow`, allowing Xcode to attach a debugger.
    /// Ad hoc and TestFlight builds need the separate install-source check below.
    static var isDevelopmentBuild: Bool {
        TestHooks.developmentBuild ?? signedForDevelopment
    }

    /// Only a physical App Store installation may serve live ads. A missing receipt, a sandbox
    /// receipt (TestFlight), or an embedded provisioning profile keeps traffic in test mode.
    /// This uses the receipt API available on the SDK's minimum supported version, iOS 13.
    static var isAppStoreBuild: Bool {
        guard !isSimulator, !isDevelopmentBuild else { return false }
        if let override = TestHooks.appStoreBuild { return override }
        let bundle = Bundle.main
        let receiptURL = bundle.appStoreReceiptURL
        return isAppStoreInstall(
            receiptURL: receiptURL,
            receiptExists: receiptURL.map { FileManager.default.fileExists(atPath: $0.path) } ?? false,
            hasProvisioningProfile: bundle.url(forResource: "embedded", withExtension: "mobileprovision") != nil
        )
    }

    static func isAppStoreInstall(receiptURL: URL?, receiptExists: Bool, hasProvisioningProfile: Bool) -> Bool {
        !hasProvisioningProfile && receiptExists && receiptURL?.lastPathComponent == "receipt"
    }

    private static let signedForDevelopment: Bool = {
        guard let url = Bundle.main.url(forResource: "embedded", withExtension: "mobileprovision"),
              let data = try? Data(contentsOf: url) else {
            return false
        }
        return provisionAllowsDebugging(data)
    }()

    /// Reads `Entitlements.get-task-allow` from a provisioning profile (a signed blob with a plist inside).
    static func provisionAllowsDebugging(_ data: Data) -> Bool {
        guard let text = String(data: data, encoding: .isoLatin1),
              let start = text.range(of: "<?xml"),
              let end = text.range(of: "</plist>", range: start.lowerBound..<text.endIndex) else {
            return false
        }
        let plistText = String(text[start.lowerBound..<end.upperBound])
        guard let plistData = plistText.data(using: .isoLatin1),
              let plist = try? PropertyListSerialization.propertyList(from: plistData, options: [], format: nil) as? [String: Any],
              let entitlements = plist["Entitlements"] as? [String: Any] else {
            return false
        }
        return entitlements["get-task-allow"] as? Bool ?? false
    }
}

struct DeviceInfo {
    let bundleId: String
    let appVersion: String
    let osVersion: String
    let osMajor: String
    let screenWidth: Int
    let screenHeight: Int

    /// Main thread (UIKit).
    static func collect() -> DeviceInfo {
        let bundle = Bundle.main
        let version = UIDevice.current.systemVersion
        let screen = UIScreen.main
        let size = screen.nativeBounds.size // Pixels, portrait-up.
        return DeviceInfo(
            bundleId: bundle.bundleIdentifier ?? "unknown",
            appVersion: (bundle.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String) ?? "unknown",
            osVersion: version,
            osMajor: version.split(separator: ".").first.map(String.init) ?? version,
            screenWidth: Int(size.width),
            screenHeight: Int(size.height)
        )
    }
}

// MARK: - Links

/// Opens advertiser destinations; only http(s) is allowed.
enum ClickOpener {
    @discardableResult
    static func open(_ string: String) -> Bool {
        guard let url = URL(string: string), let scheme = url.scheme?.lowercased(), scheme == "https" || scheme == "http" else {
            Log.e("Refusing to open a non-web click URL")
            return false
        }
        UIApplication.shared.open(url, options: [:], completionHandler: nil)
        return true
    }
}

/// The "Ad" badge links to the Qartvelo Ads website with `?ref=<bundle id>`. Not an ad click.
enum AboutLink {
    static func url(baseURL: URL, bundleId: String) -> URL? {
        guard var components = URLComponents(url: baseURL, resolvingAgainstBaseURL: false) else { return nil }
        components.queryItems = [URLQueryItem(name: "ref", value: bundleId)]
        return components.url
    }

    static func open() {
        let base = QartveloAds.engine()?.options.baseURL ?? qartveloAdsDefaultBaseURL
        guard let url = url(baseURL: base, bundleId: Bundle.main.bundleIdentifier ?? "") else { return }
        UIApplication.shared.open(url, options: [:], completionHandler: nil)
    }
}

/// Finds the optional AdMob adapter without a compile-time dependency.
enum FallbackDiscovery {
    static let adMobClassName = "QartveloAdMobFallbackAdapter"

    static func discover() -> QartveloFallbackAdapter? {
        guard let type = NSClassFromString(adMobClassName) as? NSObject.Type else {
            Log.i("QartveloAdsAdMob not linked; running without a fallback network")
            return nil
        }
        return type.init() as? QartveloFallbackAdapter
    }
}
