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

## Behaviour

- **One controller per placement code.** A re-created view (rotation, list recycling, Compose recomposition, React Native re-render) that calls `load()` again re-attaches to the loaded banner; no new request is made.
- **Refresh** happens no faster than the placement's `banner_refresh_seconds` (at least 30 s), and only while the view is attached and visible. It pauses when the Activity stops or the view is hidden or detached.
- **Fallback**: on Qartvelo Ads no-fill, timeout, error or creative failure the view renders your AdMob anchored adaptive banner instead. A visible AdMob banner refreshes itself (AdMob settings apply); a later Qartvelo Ads fill replaces it.
- **One visible banner per placement code.** Use distinct placement codes for banners that are on screen at the same time.
- **No leaks.** The view never holds an Activity after it is detached; AdMob views are re-parented through a context wrapper.
- The **Ad** badge in the corner of a Qartvelo Ads banner opens the Qartvelo Ads website (`https://ads.qartvelo.com/?ref=<your package name>`). Tapping it is not an ad click: no click event, no `onClicked`, no advertiser page.
- Qartvelo Ads banner creatives keep their aspect ratio within the view width. Supported creative sizes are 320x50, 320x100, 300x250, 468x60 and 728x90.

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
