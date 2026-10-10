import UIKit
import QartveloAds
import QartveloAdsAdMob

@main
final class AppDelegate: UIResponder, UIApplicationDelegate {
    var window: UIWindow?

    func application(_ application: UIApplication,
                     didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        setvbuf(stdout, nil, _IOLBF, 0)
        qa("launch sdk=\(QartveloAds.sdkVersion) forceNoFill=\(AppConfig.forceNoFill)")
        let root: UIViewController
        if ProcessInfo.processInfo.arguments.contains("-compareBanners") {
            root = BannerComparisonViewController()
        } else if AppConfig.autoRun {
            root = QAViewController()
        } else {
            root = TestAdsViewController()
        }
        let window = UIWindow(frame: UIScreen.main.bounds)
        window.rootViewController = root
        window.makeKeyAndVisible()
        self.window = window

        QartveloAds.registerFallbackAdapter(QartveloAdMobFallbackAdapter())
        let options = QartveloAdsOptions()
        options.logLevel = .debug
        options.testMode = true
        options.testForceNoFill = AppConfig.forceNoFill
        if let baseURL = AppConfig.baseURL { options.baseURL = baseURL }
        QartveloAds.initialize(appKey: AppConfig.appKey, options: options) { [weak root] success, error in
            qa("init success=\(success) error=\(error.map { "\($0)" } ?? "none")")
            (root as? TestAdsViewController)?.sdkDidInitialize(success: success, error: error)
        }
        return true
    }
}
