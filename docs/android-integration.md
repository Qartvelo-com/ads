# Qartvelo Ads Android SDK integration

The Qartvelo Ads Android SDK serves direct-sold Qartvelo Ads campaigns (banner, interstitial, rewarded) and,
when Qartvelo Ads has nothing to show, automatically falls back to **your own** AdMob ad units through an
optional adapter module.

| Artifact (JitPack) | Contents | Required |
|---|---|---|
| `com.qartvelo.ads:core:0.3.0` | API client, caching, rendering, event tracking, banner view | yes |
| `com.qartvelo.ads:admob:0.3.0` | Google Mobile Ads fallback adapter (depends on `play-services-ads`) | optional |

The same artifacts are on GitHub Packages as `com.qartvelo.ads:core` and
`com.qartvelo.ads:admob` (see below).

Requirements: `minSdk 23`, `compileSdk` 35 or newer, Java 17 toolchain, AndroidX.
Core pulls in OkHttp 4.12, Media3 ExoPlayer 1.8 and AndroidX core. It does not depend on Google
Mobile Ads; only the adapter does (`play-services-ads` 25.4.0, resolved normally, never shaded, so
your own version wins if it is newer).

## 1. Gradle setup

The SDK is published from [Qartvelo-com/ads](https://github.com/Qartvelo-com/ads).
JitPack needs no account or token:

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}
```

```kotlin
// app/build.gradle.kts
dependencies {
    implementation("com.qartvelo.ads:core:0.3.0")
    // Optional AdMob fallback. Leave it out to run Qartvelo Ads only.
    implementation("com.qartvelo.ads:admob:0.3.0")
}
```

**GitHub Packages alternative.** Add the repository
`https://maven.pkg.github.com/Qartvelo-com/ads` with credentials (your GitHub user and a
token with `read:packages`; GitHub Packages always requires one, even for public packages) and
depend on `com.qartvelo.ads:core:0.3.0` / `com.qartvelo.ads:admob:0.3.0`.

**Developing the SDK itself.** `./gradlew publishToMavenLocal` in `android/` installs
`com.qartvelo.ads:*:0.3.0` into `~/.m2`; add `mavenLocal()` to use it.

Without `qartvelo-ads-admob` the SDK works normally and reports `onNoAdAvailable` when Qartvelo Ads has no ad.
The adapter is discovered automatically at runtime (`com.qartvelo.admob.AdMobFallbackAdapter`); no
code change is needed when you add or remove it. Both libraries ship consumer ProGuard/R8 rules, so
minified release builds need no extra configuration.

## 2. AndroidManifest requirements

`qartvelo-ads-core` merges the `INTERNET` permission and its full-screen ad activity automatically.

If you use the AdMob adapter, Google requires **your own** AdMob App ID in your app manifest
(the app crashes at startup without it, by Google's design):

```xml
<application>
    <meta-data
        android:name="com.google.android.gms.ads.APPLICATION_ID"
        android:value="ca-app-pub-XXXXXXXXXXXXXXXX~YYYYYYYYYY" />
</application>
```

Local development against `http://10.0.2.2:8000` (emulator) needs cleartext HTTP. Allow it only in
debug builds, for example `src/debug/res/xml/network_security_config.xml`:

```xml
<network-security-config>
    <domain-config cleartextTrafficPermitted="true">
        <domain includeSubdomains="false">10.0.2.2</domain>
        <domain includeSubdomains="false">localhost</domain>
    </domain-config>
</network-security-config>
```

referenced from `src/debug/AndroidManifest.xml` with
`<application android:networkSecurityConfig="@xml/network_security_config" />`.
Production must use HTTPS for the API and creatives.

## 3. Initialization

Initialize once, as early as possible (usually `Application.onCreate`). The call is idempotent:
later calls are ignored except that their listener receives the first result. Only the application
context is kept.

```kotlin
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        QartveloAds.setPrivacy(QartveloAdsPrivacy(consentGiven = myCmp.consentOrNull())) // see docs/privacy.md
        QartveloAds.initialize(
            context = this,
            appKey = "app_xxxxxxxxxxxxxxxxxxxxxxxx",
            options = QartveloAdsOptions(
                admobFallback = true,
                requestTimeoutMs = 800,
                testMode = BuildConfig.DEBUG,
                baseUrl = "https://api.your-ourads-host.example/",
                admobAdUnits = mapOf(
                    "home_banner" to "ca-app-pub-XXX/111",
                    "game_end" to "ca-app-pub-XXX/222",
                    "reward_coins" to "ca-app-pub-XXX/333",
                ),
                logLevel = if (BuildConfig.DEBUG) QartveloAdsLogLevel.DEBUG else QartveloAdsLogLevel.ERROR,
            ),
        ) { success, error ->
            // success == false still leaves the SDK usable (cached config, AdMob fallback).
        }
    }
}
```

`QartveloAdsOptions`:

| Option | Default | Meaning |
|---|---|---|
| `admobFallback` | `true` | Allow the AdMob adapter to serve your units when Qartvelo Ads cannot |
| `requestTimeoutMs` | `800` | Qartvelo Ads request budget before falling back (a remote per-placement value wins) |
| `testMode` | `false` | Never serve billable campaigns; AdMob uses Google's test units |
| `testForceNoFill` | `false` | In test mode, force Qartvelo Ads `no_fill` to exercise the fallback |
| `logLevel` | `ERROR` | `NONE`, `ERROR`, `INFO`, `DEBUG` (logcat tag `QartveloAds`; tokens are never logged) |
| `baseUrl` | placeholder | Your Qartvelo Ads API origin, for example `https://api.ourads.ge/` |
| `admobAdUnits` | empty | Placement code to your AdMob unit id; wins over the dashboard mapping |

The app key is public by design. The backend validates it together with your package name and
issues a short-lived session token; never embed the server-side SDK secret in an app.

`isInitialized()` becomes true once the first initialization attempt finished. Loads never wait for
a slow backend: with a cached remote config they start right away and the Qartvelo Ads request obtains its
session within the normal request timeout, so a hanging `/sdk/initialize` falls back to AdMob after
that timeout. Only on the very first start (no cached config) are loads held for up to
`requestTimeoutMs` while the first configuration arrives. Loads before `initialize()` was called at
all fail with `NOT_INITIALIZED`.

## 4. Placement configuration

Placements are created in the publisher dashboard (code, format, fallback, AdMob unit id, request
timeout, frequency cap, banner refresh). The app only uses the placement **code**. On each start the
SDK receives the remote configuration and caches the last copy on disk, so offline or slow starts
still know timeouts, kill switches and AdMob units.

Remote settings that apply without an app update:

- `ourads_enabled` / global `serving_enabled`: kill switches; the SDK skips Qartvelo Ads and falls back.
  While a placement is switched off, the SDK re-checks with the backend at most every 5 minutes (on
  the next load), so switching Qartvelo Ads back on reaches running apps without a restart.
- `fallback_provider` (`admob` or `none`) and global `fallback_enabled`.
- `request_timeout_ms` per placement (wins over `QartveloAdsOptions.requestTimeoutMs`).
- `admob_ad_unit_id` (used when `admobAdUnits` has no entry for the code).
- `banner_refresh_seconds` (minimum 30).

## 5. Banner

```xml
<com.qartvelo.sdk.QartveloAdsBannerView
    android:id="@+id/banner"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    app:ourads_placementId="home_banner" />
```

```kotlin
val banner = findViewById<QartveloAdsBannerView>(R.id.banner)
banner.listener = object : QartveloAdsListener {
    override fun onLoaded(info: QartveloAdsAdInfo) { /* info.source is OURADS or ADMOB */ }
    override fun onNoAdAvailable(placementId: String, format: AdFormat) { banner.visibility = View.GONE }
}
banner.load()            // idempotent

override fun onDestroy() {
    banner.destroy()     // the loaded banner stays cached for the next view
    super.onDestroy()
}
```

Behaviour:

- One banner controller per placement code. A re-created view (rotation, list recycling, React
  Native re-render) calling `load()` again reuses the loaded banner; no new request is made.
- Refresh happens no faster than `banner_refresh_seconds` and only while the view is attached and
  visible; it pauses when the Activity stops or the view is hidden or detached.
- Qartvelo Ads no-fill, timeout, error or creative failure renders your AdMob adaptive banner instead.
  A visible AdMob banner keeps refreshing itself; a later Qartvelo Ads fill replaces it.
- One visible banner per placement code at a time: use distinct codes for simultaneous banners.
- The view never holds an Activity after it is detached (AdMob views are re-parented through a
  context wrapper).

## 6. Interstitial

```kotlin
QartveloAds.loadInterstitial("game_end", object : QartveloAdsListener {
    override fun onLoaded(info: QartveloAdsAdInfo) { /* ready, from info.source */ }
    override fun onLoadFailed(placementId: String, error: QartveloAdsError) { /* nothing ready */ }
})

// Later, at a natural break:
if (QartveloAds.isInterstitialReady("game_end")) {
    QartveloAds.showInterstitial(activity, "game_end", object : QartveloAdsListener {
        override fun onDismissed(info: QartveloAdsAdInfo) { continueGame() }
        override fun onNoAdAvailable(placementId: String, format: AdFormat) { continueGame() }
    })
}
```

Load preloads AdMob in parallel when fallback is possible, so a fallback is ready the moment Qartvelo Ads
cannot serve. Concurrent loads for the same placement share one request. If Qartvelo Ads answers in time
but its creative is still downloading 1.5 s after the request timeout (a slow CDN), a ready AdMob ad
finishes the load (`onFallbackStarted(timeout)`, `onLoaded(ADMOB)`); the download continues and the
Qartvelo Ads ad is used by the next show. Show priority: a valid Qartvelo Ads ad, else a ready AdMob ad, else
`onNoAdAvailable`. An Qartvelo Ads ad is shown at most once and never after its server-provided expiry.

Pass a listener to `showInterstitial` / `showRewarded`. Without one, show events go to the last load
listener only while your code still references it (the SDK holds load listeners weakly so a destroyed
Activity is never retained), plus any `addEventListener` observers.

## 7. Rewarded

```kotlin
QartveloAds.loadRewarded("reward_coins")

QartveloAds.showRewarded(activity, "reward_coins", object : QartveloAdsListener {
    override fun onReward(info: QartveloAdsAdInfo, reward: QartveloAdsReward) {
        grantCoins(reward.amount) // exactly once, only after completion, QartveloAds or AdMob
    }
    override fun onDismissed(info: QartveloAdsAdInfo) { resumeGame() }
})
```

Qartvelo Ads rewarded videos play in a full-screen Media3 player. Closing early asks for confirmation and
forfeits the reward. Rotation and Activity re-creation neither restart the video nor repeat events.
The AdMob reward (`onUserEarnedReward`) is mapped to the same single `onReward` callback.

## 8. Events

All callbacks run on the main thread. Per-call listeners receive the events of that call; global
observers receive every event of every placement:

```kotlin
QartveloAds.addEventListener(analyticsListener)
QartveloAds.removeEventListener(analyticsListener)
```

| Callback | When |
|---|---|
| `onLoaded(info)` | A load finished and a source is ready (`info.source` is the one that will show) |
| `onLoadFailed(placementId, error)` | A load ended without an ad, or a show-time error (`ALREADY_SHOWING`, `SHOW_FAILED`) |
| `onFallbackStarted(placementId, format, reason)` | Qartvelo Ads could not serve: `no_fill`, `timeout`, `error`, `creative_failed`, `disabled` |
| `onNoAdAvailable(placementId, format)` | No source can serve (emitted before `onLoadFailed` on loads; alone on shows) |
| `onShown(info)` / `onImpression(info)` | The creative is on screen; one impression per ad |
| `onClicked(info)` | First click on the ad (recorded before the browser opens) |
| `onReward(info, reward)` | Rewarded completion, at most once per show |
| `onDismissed(info)` | The full-screen ad closed |

A load ends with exactly one of `onLoaded` or `onLoadFailed`. A show ends with exactly one of
`onDismissed`, `onNoAdAvailable` or `onLoadFailed`. A show without a listener reports to the last
load listener of that placement (and to global observers).

Error codes: `NOT_INITIALIZED`, `INVALID_PLACEMENT`, `NETWORK_ERROR`, `TIMEOUT`, `NO_FILL`,
`CREATIVE_FAILED`, `AD_EXPIRED`, `SHOW_FAILED`, `ALREADY_SHOWING`, `INTERNAL_ERROR`.

## 9. Testing and test mode

- `testMode = true` sends `test_mode` to the backend, which returns built-in test creatives that are
  never billed, and makes the AdMob adapter use Google's public test units. Use it for every debug
  and QA build so you never generate invalid production traffic. Test mode is taken only from
  `QartveloAdsOptions`; it is never cached, so turning it off takes effect on the next start.
- `testForceNoFill = true` (with test mode) forces Qartvelo Ads `no_fill` so you can see the AdMob fallback.
- `logLevel = DEBUG` logs request timing, fallback decisions and event delivery (never tokens).
- The sample app in `android/sample-app` has Load/Show buttons, a banner screen, test-mode and
  force-no-fill switches, an editable base URL (default `http://10.0.2.2:8000/`) and an on-screen
  event log.
- SDK unit tests: `./gradlew :qartvelo-ads-core:testDebugUnitTest :qartvelo-ads-admob:testDebugUnitTest`.

## 10. Release checklist

- [ ] `baseUrl` points to the production HTTPS API; no cleartext config in the release manifest.
- [ ] `testMode = false` and `testForceNoFill = false` in release builds.
- [ ] Your own AdMob App ID in the manifest and your own ad unit ids per placement
      (see `docs/admob-fallback.md`); no Google test ids in production.
- [ ] Your app's package name matches the one registered in the publisher dashboard, and the app
      and placements are approved and active.
- [ ] Consent is collected by your CMP and passed with `QartveloAds.setPrivacy` (see `docs/privacy.md`).
- [ ] `logLevel` is `ERROR` or `NONE`.
- [ ] R8/minify enabled builds tested once on a device (consumer rules are bundled).
- [ ] `banner.destroy()` called when banner screens are destroyed.
