import UIKit

/// Compact anchored slot, independent of creative dimensions and display pixel density.
enum AdaptiveBannerLayout {
    static func size(width: CGFloat, screenHeight: CGFloat, preferred: CGSize = .zero) -> CGSize {
        let width = width.isFinite ? max(1, width) : 320
        if preferred.width.isFinite, preferred.height.isFinite,
           abs(preferred.width - width) < 0.5, (50...90).contains(preferred.height) {
            return CGSize(width: width, height: preferred.height)
        }
        let screenCap = screenHeight.isFinite && screenHeight > 0 ? floor(screenHeight * 0.15) : 90
        return CGSize(width: width, height: max(50, min(90, screenCap, floor(width * 50 / 320))))
    }
}
