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

The height comes from the ad (`intrinsicContentSize`): zero until an ad is loaded, then the creative's height scaled to fit the width. Pin the leading and trailing edges and leave the height free.

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

// BannerAd(placementId: "home_banner").frame(height: 50)
```

## Behaviour

- **One banner per placement.** Re-creating the view (navigation, cell reuse, SwiftUI updates) and calling `load()` again reuses the loaded banner instead of making a new request. `load()` is idempotent.
- **Refresh** happens every `banner_refresh_seconds` from the dashboard (at least 30 s, default 60 s), only while the view is in a window, not hidden and the app is in the foreground.
- **Impression** is recorded the first time the banner is actually visible, never for a banner that was loaded but not seen.
- **Clicks** open the advertiser's page in Safari after the impression. The small "Ad" badge opens the Qartvelo Ads website and is not a click.
- The AdMob fallback uses an anchored adaptive banner for the view's width. Set `rootViewController` if the banner is not inside a view controller's view (the SDK uses the nearest view controller otherwise).
- Call `destroy()` when the view is removed for good; the loaded banner stays cached for the next view with the same placement.
