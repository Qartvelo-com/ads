import UIKit

enum AppConfig {
    static let appKey = "app_dYWiPE5Pp2vvc0fuHkYi3ggo"
    static let bannerPlacement = "test_banner"
    static let interstitialPlacement = "test_interstitial"
    static let rewardedPlacement = "test_rewarded"
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
