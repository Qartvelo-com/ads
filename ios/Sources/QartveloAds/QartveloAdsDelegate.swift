import Foundation

/// Ad lifecycle callbacks. Every method is optional and is called on the main thread.
///
/// Load outcome: exactly one of `qartveloAdDidLoad` or `qartveloAdDidFailToLoad` ends each load.
/// When the failure is "no ad from any source", `qartveloAdNoAdAvailable` comes right before it.
/// When Qartvelo Ads cannot serve and a fallback is possible, `qartveloAdDidStartFallback` comes first.
///
/// Show outcome: `qartveloAdDidShow` then `qartveloAdDidRecordImpression` when the creative is on
/// screen, `qartveloAdDidClick` at most once, `qartveloAd(_:didEarnReward:)` at most once (rewarded
/// only, after the video completed) and finally `qartveloAdDidDismiss`. If nothing can be shown,
/// `qartveloAdNoAdAvailable` is called instead; show-time errors such as `alreadyShowing` arrive
/// through `qartveloAdDidFailToLoad`.
@objc public protocol QartveloAdsDelegate: AnyObject {
    @objc optional func qartveloAdDidLoad(_ info: QartveloAdsAdInfo)
    @objc optional func qartveloAdDidFailToLoad(placementId: String, error: QartveloAdsError)
    @objc optional func qartveloAdDidShow(_ info: QartveloAdsAdInfo)
    @objc optional func qartveloAdDidRecordImpression(_ info: QartveloAdsAdInfo)
    @objc optional func qartveloAdDidClick(_ info: QartveloAdsAdInfo)
    @objc optional func qartveloAdDidDismiss(_ info: QartveloAdsAdInfo)
    @objc optional func qartveloAd(_ info: QartveloAdsAdInfo, didEarnReward reward: QartveloAdsReward)
    @objc optional func qartveloAdDidStartFallback(placementId: String, format: QartveloAdFormat, reason: String)
    @objc optional func qartveloAdNoAdAvailable(placementId: String, format: QartveloAdFormat)
}
