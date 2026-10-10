---
title: Banner
description: Add a QartveloAdsBannerView in XML or code, listen to its events and manage its lifecycle.
---

## In XML

```xml title="res/layout/activity_main.xml"
<com.qartvelo.sdk.QartveloAdsBannerView
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:id="@+id/banner"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    app:qartvelo_placementId="home_banner" />
```

```kotlin title="MainActivity.kt"
class MainActivity : AppCompatActivity() {
    private lateinit var banner: QartveloAdsBannerView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        banner = findViewById(R.id.banner)
        banner.listener = object : QartveloAdsListener {
            override fun onLoaded(info: QartveloAdsAdInfo) {
                // info.source is AdSource.QARTVELO or AdSource.ADMOB
            }
            override fun onNoAdAvailable(placementId: String, format: AdFormat) {
                banner.visibility = View.GONE
            }
        }
        banner.load() // idempotent
    }

    override fun onDestroy() {
        banner.destroy() // the loaded banner stays cached for the next view
        super.onDestroy()
    }
}
```

## In code

```kotlin
val banner = QartveloAdsBannerView(this).apply {
    placementId = "home_banner"
    layoutParams = ViewGroup.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
}
container.addView(banner)
banner.load()
```

## In Jetpack Compose

```kotlin
@Composable
fun QartveloBanner(placementId: String, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier.fillMaxWidth(),
        factory = { context ->
            QartveloAdsBannerView(context).apply {
                this.placementId = placementId
                load()
            }
        },
        onRelease = { it.destroy() },
    )
}
```

## Size

By default the view reserves a **compact anchored adaptive slot**: the full width of the view and
**50 to 90 dp** tall. Give it `match_parent` width and `wrap_content` height. It stays at height 0
until an ad loads.

With the AdMob adapter, a Qartvelo Ads banner takes exactly the height of Google's anchored adaptive
banner for the same width, so switching to the fallback never moves your layout. Without the adapter
the height is `width × 50 / 320`, kept between 50 and 90 dp and at most 15% of the screen height (64 dp
on a 411 dp wide phone). This approximates a compact banner; it is not Google's algorithm.

The ad request reports the slot in **pixels** (`screen_width` and `banner_height`), and the backend
picks the approved horizontal creative closest to the slot's proportions; inline rectangles such as
300x250 are not chosen for an adaptive slot. The image is drawn aspect-fit, never stretched or
cropped, so an older creative may leave space around it. Advertisers upload **960x150, 1320x204 and
2184x270** adaptive artwork, plus the **320x50, 320x100, 300x250, 468x60 and 728x90** standard sizes.

When the view's width changes, the slot height follows at once, without a new request or impression.

For the earlier sizing that took the creative's own size, set `banner.usesAdaptiveSize = false`
before `load()`. Those requests leave out `banner_height`. For a banner inside scrolling content, use
[inline sizing](#inline-banners) instead.

## Inline banners

Since 0.6.0. The default (anchored) sizing is for a banner pinned to the top or bottom of the screen.
For a banner **inside scrolling content**, such as a feed, an article or a `RecyclerView`, use inline
sizing. The ad can then be a rectangle such as 300x250, not just a strip:

```kotlin
val banner = QartveloAdsBannerView(this).apply {
    placementId = "feed_banner"
    sizing = BannerSizing.INLINE
    inlineMaxHeightDp = 250 // the default; at least 32
    layoutParams = ViewGroup.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
}
container.addView(banner)
banner.load()
```

Set `sizing` and `inlineMaxHeightDp` in code before `load()`; there is no XML attribute for them.
Inline sizing wins over `usesAdaptiveSize`.

- **Sizing rule.** The ad takes the biggest size that fits the view's width and `inlineMaxHeightDp`,
  keeping its proportions. The view is exactly as tall as that ad, with no reserved empty space, and
  the ad is centered horizontally. When the width changes, the ad is refitted without a new request.
- **Request.** Inline banners send `banner_mode: "inline"` and `banner_max_height` (pixels, like
  `screen_width`) and no `banner_height`. The backend scales every candidate to fit the slot and
  serves the one that shows biggest ([Ad request](/api/ad-request/#banner-sizing)).
- **AdMob fallback.** With the AdMob adapter, the fallback is Google's inline adaptive banner
  (`AdSize.getInlineAdaptiveBannerAdSize(width, maxHeight)`) for the same width and max height.
  Custom adapters can implement [`InlineBannerFallback`](/guides/custom-fallback-adapter/#inline-banners);
  adapters without it get their anchored banner.
- **Two placements.** Banners of the same placement code share one ad. An anchored banner and an
  inline banner on the same screen need two placement codes.

## HTML5 ads

Since 0.6.0, banners can show HTML5 ads made in the Qartvelo Ads editor. There is nothing to change in
your app:

- The SDK downloads the ad's files ahead (at most 2 MB per ad) and shows them in a locked `WebView`:
  JavaScript runs, but there is no JavaScript bridge, no DOM storage, no file or content access, no
  third-party cookies, and only the ad's own files load.
- The impression counts when the ad reports it is ready and the banner is visible. An ad that is not
  ready within 6 seconds is a creative failure, and the banner falls back to AdMob.
- A tap counts as one click and opens the advertiser's page outside your app.
- Animations pause while the banner is hidden or the app is in the background.

SDKs before 0.6.0 receive the static image versions of the same design.

## Behaviour

- **One controller per placement code.** A re-created view (rotation, list recycling, Compose recomposition, React Native re-render) that calls `load()` again re-attaches to the loaded banner; no new request is made.
- **Refresh** happens no faster than the placement's `banner_refresh_seconds` (at least 30 s), and only while the view is attached and visible. It pauses when the Activity stops or the view is hidden or detached.
- **Fallback**: on Qartvelo Ads no-fill, timeout, error or creative failure the view renders your AdMob anchored adaptive banner instead (Google's inline adaptive banner for [inline banners](#inline-banners)). A visible AdMob banner refreshes itself (AdMob settings apply); a later Qartvelo Ads fill replaces it.
- **One visible banner per placement code.** Use distinct placement codes for banners that are on screen at the same time, for example an anchored and an inline banner.
- **No leaks.** The view never holds an Activity after it is detached; AdMob views are re-parented through a context wrapper.
- The **Ad** badge in the corner of a Qartvelo Ads banner opens the Qartvelo Ads website (`https://ads.qartvelo.com/?ref=<your package name>`). Tapping it is not an ad click: no click event, no `onClicked`, no advertiser page.

## Banner events

Banners use the same [`QartveloAdsListener`](/android/events/): `onLoaded`, `onLoadFailed`, `onShown`, `onImpression`, `onClicked`, `onFallbackStarted` and `onNoAdAvailable`. `onDismissed` and `onReward` do not apply.

## Errors

| Situation | Callback |
|---|---|
| `placementId` not set | `onLoadFailed(INVALID_PLACEMENT)` |
| `load()` before `QartveloAds.initialize` | `onLoadFailed(NOT_INITIALIZED)` |
| The code belongs to a non-banner placement, or does not exist | `onLoadFailed(INVALID_PLACEMENT)` |
| No source has an ad | `onNoAdAvailable`, then `onLoadFailed` with the Qartvelo Ads failure (`NO_FILL`, `TIMEOUT`, `NETWORK_ERROR` or `CREATIVE_FAILED`) |

After a failure the banner keeps its refresh schedule and tries again; you do not need to call `load()` again.
