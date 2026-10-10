import CoreGraphics

/// An inline slot's size for an ad: scaled to fit the width and max height, keeping proportions. Same rule as the server.
enum InlineBannerFit {
    static func size(width: CGFloat, height: CGFloat, slotWidth: CGFloat, maxHeight: CGFloat) -> CGSize {
        guard width > 0, height > 0, slotWidth > 0, maxHeight > 0,
              width.isFinite, height.isFinite, slotWidth.isFinite, maxHeight.isFinite else { return .zero }
        let scale = min(slotWidth / width, maxHeight / height)
        return CGSize(width: floor(width * scale), height: floor(height * scale))
    }
}
