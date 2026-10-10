import UIKit

enum AppConfig {
    /// `-local`: the local backend (http://127.0.0.1:8000) with the seeded iOS demo app
    /// (`php artisan db:seed --class=DemoInventorySeeder`), instead of the production test app.
    static var local: Bool { ProcessInfo.processInfo.arguments.contains("-local") }
    static var baseURL: URL? { local ? URL(string: "http://127.0.0.1:8000/") : nil }
    static var appKey: String { local ? "app_demo_sample_ios_0001" : "app_dYWiPE5Pp2vvc0fuHkYi3ggo" }
    static var bannerPlacement: String { local ? "home_banner" : "test_banner" }
    static var inlineBannerPlacement: String { local ? "inline_banner" : "test_inline_banner" }
    static var interstitialPlacement: String { local ? "game_end" : "test_interstitial" }
    static var rewardedPlacement: String { local ? "reward_coins" : "test_rewarded" }
    static var autoRun: Bool { ProcessInfo.processInfo.arguments.contains("-autoRun") || bannerOnly }
    static var forceNoFill: Bool { ProcessInfo.processInfo.arguments.contains("-forceNoFill") }
    static var bannerOnly: Bool { ProcessInfo.processInfo.arguments.contains("-bannerOnly") }
    static var bannerWidth: CGFloat? {
        let args = ProcessInfo.processInfo.arguments
        guard let index = args.firstIndex(of: "-bannerWidth"), args.indices.contains(index + 1),
              let width = Double(args[index + 1]), width.isFinite, width > 0 else { return nil }
        return CGFloat(width)
    }
}

func qa(_ message: String) {
    print("QA: \(message)")
}
