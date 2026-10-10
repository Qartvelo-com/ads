---
title: Banner
description: Show a refreshing banner with QartveloAdsBannerView in UIKit or SwiftUI.
---

`QartveloAdsBannerView` shows a Qartvelo Ads banner, or your own AdMob banner when Qartvelo Ads has nothing to show and the adapter is registered.

## UIKit

```swift
import QartveloAds

final class HomeViewController: UIViewController, QartveloAdsDelegate {
    private let banner = QartveloAdsBannerView(placementId: "home_banner")

    override func viewDidLoad() {
        super.viewDidLoad()
        banner.delegate = self
        banner.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(banner)
        NSLayoutConstraint.activate([
            banner.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            banner.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            banner.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor),
        ])
        banner.load()
    }

    func qartveloAdDidLoad(_ info: QartveloAdsAdInfo) {
        // info.source is .qartvelo or .admob
    }
}
```

The view reports zero intrinsic height until an ad loads. By default, Qartvelo uses a **compact anchored adaptive slot** at the container's full width, with a height of **50 to 90 points**. Pin its leading and trailing edges and leave the height free.

When the AdMob adapter is registered, both networks use Google's standard anchored adaptive size calculation, so their heights match for the same width and orientation. Without the adapter, Qartvelo calculates `floor(width × 50 / 320)`, bounded to 50 to 90 points and a 15% screen-height cap (with a 50-point minimum). This independent calculation approximates a compact banner; it is not Google's algorithm.

The API receives `screen_width` and `banner_height` in **pixels**. For adaptive requests, the backend chooses the approved horizontal creative with the closest aspect ratio to that slot; inline rectangles such as 300x250 are excluded. Images use aspect fit and remain fully visible without stretching or cropping. A legacy creative may leave space around the image. Advertiser uploads accept **960x150, 1320x204 and 2184x270** Retina adaptive artwork, plus **320x50, 320x100, 300x250, 468x60 and 728x90** standard sizes. The dashboard includes sizing guidance and a copyable AI banner prompt.

Public test artwork uses **960x150, 1320x204 and 2184x270** PNGs selected by slot proportions. These server-hosted images provide sharper lettering on Retina displays, including when app registration is unavailable.

For the previous creative-height sizing, set `banner.usesAdaptiveSize = false` before `load()`. Those requests omit `banner_height` and retain the backend's widest-fit selection rule. For a banner inside scrolling content, use [inline sizing](#inline-banners) instead.

When the container width changes, the loaded Qartvelo creative and adaptive height update immediately without another request or impression. The next scheduled refresh selects artwork for the new dimensions. AdMob applies a changed width on the next scheduled fallback load, preserving the placement's refresh interval.

## Inline banners

Since 0.6.0. The default (anchored) sizing is for a banner pinned to the top or bottom of the screen. For a banner **inside scrolling content**, such as a feed, an article or a `UICollectionView`, use inline sizing. The ad can then be a rectangle such as 300x250, not just a strip:

```swift
let banner = QartveloAdsBannerView(placementId: "feed_banner")
banner.sizing = .inline
banner.inlineMaxHeight = 250 // points; the default, at least 32
stackView.addArrangedSubview(banner)
banner.load()
```

Set `sizing` and `inlineMaxHeight` before `load()`. Inline sizing wins over `usesAdaptiveSize`. `QartveloBannerSizing` is available to Objective-C as `QartveloBannerSizingAnchored` and `QartveloBannerSizingInline`.

- **Sizing rule.** The ad takes the biggest size that fits the view's width and `inlineMaxHeight`, keeping its proportions. The view's intrinsic height is exactly that ad's height, with no reserved empty space, and the ad is centered horizontally. When the width changes, the ad is refitted without a new request.
- **Request.** Inline banners send `banner_mode: "inline"` and `banner_max_height` (pixels, like `screen_width`) and no `banner_height`. The backend scales every candidate to fit the slot and serves the one that shows biggest ([Ad request](/api/ad-request/#banner-sizing)).
- **AdMob fallback.** With `QartveloAdsAdMob`, the fallback is Google's inline adaptive banner (`inlineAdaptiveBanner(width:maxHeight:)`) for the same width and max height. Custom adapters can implement [`createInlineBanner`](/guides/custom-fallback-adapter/#inline-banners); adapters without it get their anchored banner.
- **Two placements.** Banners of the same placement share one ad. An anchored banner and an inline banner on the same screen need two placements.

## HTML5 ads

Since 0.6.0, banners can show HTML5 ads made in the Qartvelo Ads editor. There is nothing to change in your app:

- The SDK downloads the ad's files ahead (at most 2 MB per ad) and shows them in a locked `WKWebView`: JavaScript runs, but there is no script message handler, the data store is non-persistent, the files are served from a private `qartvelo-bundle` scheme and content rules block every `http` and `https` load, so only the ad's own files load.
- The impression counts when the ad reports it is ready and the banner is visible. An ad that is not ready within 6 seconds is a creative failure, and the banner falls back to AdMob.
- A tap counts as one click and opens the advertiser's page in Safari.
- Animations pause while the banner is hidden or the app is in the background.

SDKs before 0.6.0 receive the static image versions of the same design.

## SwiftUI

```swift
import QartveloAds
import SwiftUI

struct BannerAd: UIViewRepresentable {
    let placementId: String

    func makeUIView(context: Context) -> QartveloAdsBannerView {
        let view = QartveloAdsBannerView(placementId: placementId)
        view.load()
        return view
    }

    func updateUIView(_ view: QartveloAdsBannerView, context: Context) {}
}

// Match your SwiftUI container height to the banner's intrinsic height; it is not always 50.
```

## Behaviour

- **One banner per placement.** Re-creating the view (navigation, cell reuse, SwiftUI updates) and calling `load()` again reuses the loaded banner instead of making a new request. `load()` is idempotent. Banners on screen at the same time need distinct placements.
- **Refresh** happens every `banner_refresh_seconds` from the dashboard (at least 30 s, default 60 s), only while the view is in a window, not hidden and the app is in the foreground.
- **Impression** is recorded the first time the banner is actually visible, never for a banner that was loaded but not seen.
- **Clicks** open the advertiser's page in Safari after the impression. The small "Ad" badge opens the Qartvelo Ads website and is not a click.
- The AdMob fallback uses an anchored adaptive banner for the view's width (an inline adaptive banner for [inline banners](#inline-banners)). Set `rootViewController` if the banner is not inside a view controller's view (the SDK uses the nearest view controller otherwise).
- Call `destroy()` when the view is removed for good; the loaded banner stays cached for the next view with the same placement.
