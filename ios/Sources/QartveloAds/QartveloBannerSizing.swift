import Foundation

/// How a banner takes its size. `anchored` (default): Google's anchored adaptive slot, full width
/// and 50 to 90 points tall, for banners pinned to a screen edge. `inline`: for banners inside
/// scrolling content; the ad takes the biggest size that fits the width and `inlineMaxHeight`,
/// keeping its proportions.
@objc public enum QartveloBannerSizing: Int {
    case anchored
    case inline
}
