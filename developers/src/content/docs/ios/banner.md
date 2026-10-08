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

The view reports zero intrinsic height until an ad loads. By default, Qartvelo uses a **compact anchored adaptive slot** at the container's full width, with a height of **50–90 points**. Pin its leading and trailing edges and leave the height free.

When the AdMob adapter is registered, both networks use Google's standard anchored adaptive size calculation, so their heights match for the same width and orientation. Without the adapter, Qartvelo calculates `floor(width × 50 / 320)`, bounded to 50–90 points and a 15% screen-height cap (with a 50-point minimum). This independent calculation approximates a compact banner; it is not Google's algorithm.

The API receives `screen_width` and `banner_height` in **pixels**. For adaptive requests, the backend chooses the approved horizontal creative with the closest aspect ratio to that slot; inline rectangles such as 300x250 are excluded. Images use aspect fit and remain fully visible without stretching or cropping. A legacy creative may leave space around the image. Advertiser uploads accept **960x150, 1320x204 and 2184x270** Retina adaptive artwork, plus **320x50, 320x100, 300x250, 468x60 and 728x90** standard sizes. The dashboard includes sizing guidance and a copyable AI banner prompt.

Public test artwork uses **960x150, 1320x204 and 2184x270** PNGs selected by slot proportions. These server-hosted images provide sharper lettering on Retina displays, including when app registration is unavailable.

For an inline rectangle or the previous creative-height sizing, set `banner.usesAdaptiveSize = false` before `load()`. Those requests omit `banner_height` and retain the backend's widest-fit selection rule.

When the container width changes, the loaded Qartvelo creative and adaptive height update immediately without another request or impression. The next scheduled refresh selects artwork for the new dimensions. AdMob applies a changed width on the next scheduled fallback load, preserving the placement's refresh interval.

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

- **One banner per placement.** Re-creating the view (navigation, cell reuse, SwiftUI updates) and calling `load()` again reuses the loaded banner instead of making a new request. `load()` is idempotent.
- **Refresh** happens every `banner_refresh_seconds` from the dashboard (at least 30 s, default 60 s), only while the view is in a window, not hidden and the app is in the foreground.
- **Impression** is recorded the first time the banner is actually visible, never for a banner that was loaded but not seen.
- **Clicks** open the advertiser's page in Safari after the impression. The small "Ad" badge opens the Qartvelo Ads website and is not a click.
- The AdMob fallback uses an anchored adaptive banner for the view's width. Set `rootViewController` if the banner is not inside a view controller's view (the SDK uses the nearest view controller otherwise).
- Call `destroy()` when the view is removed for good; the loaded banner stays cached for the next view with the same placement.
