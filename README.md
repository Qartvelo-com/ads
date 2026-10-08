# Qartvelo Ads SDK

Mobile SDKs for [Qartvelo Ads](https://ads.qartvelo.com), the direct-sold ad network for Georgian
Android and iOS apps. Qartvelo Ads campaigns are served first; when there is no eligible campaign, the
request fails or it times out, the SDK automatically shows **your own** AdMob ad unit for the same
placement. In iOS test mode, if the app has no valid session, the SDK instead fetches Qartvelo's
non-billable test creative from the public server assets, so testing does not require app registration.
iOS banners use compact adaptive heights (50–90 points), matching the registered AdMob adapter's
calculated size when available. Without the adapter, the height is calculated from logical width.
Requests include container width and adaptive height in pixels; the server selects suitable horizontal
creatives, and images preserve their proportions. Retina test artwork uses 960x150, 1320x204 and
2184x270 PNGs. Set `usesAdaptiveSize = false` for inline rectangles or legacy creative-height sizing.
The iOS Simulator and installations outside the App Store always use test mode, even when the app
sets `testMode = false`; eligible real campaign creatives can be previewed without live stats or charges.

```
React Native app ──> @qartvelo/react-native-ads ──> Qartvelo Ads Kotlin SDK
                                                         │
                                       Qartvelo Ads ad available?
                                         yes │         │ no / timeout
                                    Qartvelo Ads ad   AdMob adapter ──> your AdMob account
```

| Package | Where | Install |
|---|---|---|
| Android core | Maven Central | `com.qartvelo.ads:core:0.4.1` |
| Android AdMob adapter (optional) | Maven Central | `com.qartvelo.ads:admob:0.4.1` |
| Same, also on | JitPack, GitHub Packages (`maven.pkg.github.com/Qartvelo-com/ads`) | `com.qartvelo.ads:core:0.4.1` |
| iOS core | Swift Package Manager (`https://github.com/Qartvelo-com/ads`) | `QartveloAds` 0.4.1 |
| iOS AdMob adapter (optional) | same | `QartveloAdsAdMob` 0.4.1 |
| React Native (Android and iOS) | npm | `npm install @qartvelo/react-native-ads` |

The React Native plugin bridges both native SDKs through one JavaScript API.

## Android quick start

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

// app/build.gradle.kts
dependencies {
    implementation("com.qartvelo.ads:core:0.4.1")
    implementation("com.qartvelo.ads:admob:0.4.1") // optional
}
```

```kotlin
QartveloAds.initialize(
    applicationContext,
    "app_xxx", // your app key from the Qartvelo Ads publisher dashboard
    QartveloAdsOptions(
        admobAdUnits = mapOf("game_end" to "ca-app-pub-XXX/222"),
    ),
)

QartveloAds.loadInterstitial("game_end")
QartveloAds.showInterstitial(activity, "game_end")
```

Full guide: [developers.qartvelo.com/android](https://developers.qartvelo.com/android/installation/).

## iOS quick start

1. In Xcode, choose **File > Add Package Dependencies**.
2. Paste `https://github.com/Qartvelo-com/ads`.
3. Choose **Up to Next Minor Version**, starting at **0.4.1**.
4. Add **QartveloAds** to your app target. Also add **QartveloAdsAdMob** for the optional fallback.

Xcode downloads and manages the SDK and its dependencies. For a Swift package app target:

```swift
.package(url: "https://github.com/Qartvelo-com/ads", .upToNextMinor(from: "0.4.1"))
```

CocoaPods is an alternative when the pod specifications have been published to trunk;
see the [installation guide](https://developers.qartvelo.com/ios/installation/).

```swift
import QartveloAds
import QartveloAdsAdMob // optional

QartveloAds.registerFallbackAdapter(QartveloAdMobFallbackAdapter())
let options = QartveloAdsOptions()
options.admobAdUnits = ["game_end": "ca-app-pub-XXX/222"]
QartveloAds.initialize(appKey: "app_xxx", options: options)

QartveloAds.loadInterstitial("game_end")
QartveloAds.showInterstitial("game_end", from: viewController)
```

Full guide: [developers.qartvelo.com/ios](https://developers.qartvelo.com/ios/installation/).

## React Native quick start

```sh
npm install @qartvelo/react-native-ads
```

```tsx
import { QartveloAds, QartveloAdsBanner } from '@qartvelo/react-native-ads';

await QartveloAds.initialize({ appKey: 'app_xxx', requestTimeoutMs: 800 });
await QartveloAds.loadRewarded('reward_coins');
const result = await QartveloAds.showRewarded('reward_coins');
if (result.rewarded) {
  // grant the reward
}

<QartveloAdsBanner placementId="home_banner" style={{ width: '100%' }} />;
```

Android resolves the SDK from Maven Central; iOS includes the Swift SDK and autolinks through CocoaPods. See the
[React Native guide](https://developers.qartvelo.com/react-native/installation/).

## Documentation

**https://developers.qartvelo.com** (source in [`developers/`](developers/)):

- [Quickstart](https://developers.qartvelo.com/get-started/quickstart/)
- [Android SDK](https://developers.qartvelo.com/android/installation/),
  [iOS SDK](https://developers.qartvelo.com/ios/installation/) and
  [React Native](https://developers.qartvelo.com/react-native/installation/)
- [AdMob fallback](https://developers.qartvelo.com/guides/admob-fallback/): you use your own AdMob
  app and ad units; Qartvelo Ads never owns, proxies or receives your AdMob revenue.
- [Privacy](https://developers.qartvelo.com/guides/privacy/): contextual targeting only, no
  advertising ID, no GPS, no persistent user identifier.
- [REST API](https://developers.qartvelo.com/api/overview/) with an
  [OpenAPI spec](https://developers.qartvelo.com/openapi.yaml)

### Build with AI

- [`llms.txt`](https://developers.qartvelo.com/llms.txt) /
  [`llms-full.txt`](https://developers.qartvelo.com/llms-full.txt), and every page as Markdown
  (append `.md` to its URL)
- MCP server for Claude Code, Cursor, VS Code and other agents:
  `claude mcp add qartvelo-ads -- npx -y @qartvelo/ads-mcp` ([developers/mcp](developers/mcp/))
- [Agent skill](https://developers.qartvelo.com/skills/qartvelo-ads/SKILL.md) for Claude Code,
  Cursor rules, Copilot instructions and `AGENTS.md`

## Repository layout

```
android/qartvelo-ads-core/     Kotlin SDK (com.qartvelo.sdk)
android/qartvelo-ads-admob/    optional AdMob fallback adapter (com.qartvelo.admob)
android/sample-app/            native sample (com.qartvelo.sample)
Package.swift                 GitHub SwiftPM compatibility manifest; podspecs also live at the root
ios/Package.swift             primary iOS Swift package manifest
ios/Sources/QartveloAds/       Swift SDK
ios/Sources/QartveloAdsAdMob/  optional AdMob fallback adapter
ios/Tests/                     XCTest unit tests
ios/sample-app/                iOS Simulator QA app (XcodeGen project)
react-native/packages/react-native-qartvelo-ads/   @qartvelo/react-native-ads
react-native/example/          React Native example app
developers/                    developer docs site (Astro Starlight) and the MCP server (developers/mcp)
docs/                          pointers to the docs site
```

## Development

```sh
cd android && ./gradlew :qartvelo-ads-core:testDebugUnitTest :qartvelo-ads-admob:testDebugUnitTest
cd android && ./gradlew publishToMavenLocal        # com.qartvelo.ads:*:0.4.1 into ~/.m2

# iOS (on a Mac): open ios/Package.swift in Xcode and run the tests, or
cd ios
xcodebuild test -scheme QartveloAds-Package -destination 'platform=iOS Simulator,name=iPhone 16'
cd ..

# iOS sample app, using the SDK from this checkout:
cd ios/sample-app
xcodegen generate
xcodebuild -project AdsTest.xcodeproj -scheme AdsTest -destination 'generic/platform=iOS Simulator' -derivedDataPath build build
# Install build/Build/Products/Debug-iphonesimulator/AdsTest.app with xcrun simctl.
# Launch normally for Load/Show controls for banner, interstitial and rewarded ads.
# Run the automatic QA flow after installing: ./qa-run.sh <device-udid> qa-out-qartvelo
# Exercise AdMob test ads: ./qa-run.sh <device-udid> qa-out-fallback -forceNoFill
# Exercise adaptive sizing and cached resizing in a 160-point container:
# ./qa-run.sh <device-udid> qa-out-adaptive -bannerOnly -bannerWidth 160
# Show and measure Qartvelo and AdMob banners together at the same width:
# ./qa-run.sh <device-udid> qa-out-comparison -compareBanners
cd ../..

cd react-native/packages/react-native-qartvelo-ads && npm ci && npm test && npm run typecheck
cd react-native/example && npm install && npx react-native run-android   # uses the local SDK build
```

The Android and React Native Android samples default to `http://10.0.2.2:8000/` (a backend on your machine, seen from the Android
emulator); point them at `https://ads.qartvelo.com/` for the live service.
React Native example settings are in `react-native/example/src/AppConfig.ts`; its screen starts
automatically in test mode with ad controls and an optional event log.
The iOS sample uses the SDK's `https://ads.qartvelo.com/` default; its app key and placements are in
`ios/sample-app/Sources/AppConfig.swift`. It explicitly enables test mode. The normal launch opens a
manual dashboard: load all three formats or one at a time, show/hide the adaptive banner, show a
ready interstitial or rewarded video, and inspect rewards and the latest callback. Full-screen ads
open only after tapping Show; close them using the ad's own close button. Load again after dismissal.
The automated sequence is opt-in with `-autoRun` (added by `qa-run.sh`). `-forceNoFill` exercises AdMob
test fallback; `-compareBanners` opens the banner measurement screen. QA output directories must be
new `qa-out-*` folders inside the sample app so the runner cannot overwrite source or previous results.

Keep product and dependency changes synchronized between `ios/Package.swift` and the repository-root
SwiftPM manifest so local development and GitHub installations build the same SDK.

## Releasing

Bump `version` in `android/build.gradle.kts`, `SDK_VERSION` in `QartveloAds.kt`, `sdkVersion` in
`ios/Sources/QartveloAds/QartveloAds.swift`, `s.version` in both podspecs, the npm
`package.json` files (React Native plugin and `developers/mcp`) and the version strings in
`developers/` (see its README), add a `## x.y.z` section to the changelog, and merge to `main`.
When CI passes on `main` and that version has no tag yet, the [Release](.github/workflows/release.yml)
workflow creates the tag and GitHub release (notes from the changelog) and starts
[Publish](.github/workflows/publish.yml), which publishes to GitHub Packages, Maven Central
(when the `MAVEN_CENTRAL_USERNAME` / `MAVEN_CENTRAL_PASSWORD` Central Portal user token and the
`SIGNING_KEY` / `SIGNING_PASSWORD` GPG secrets are set) and npm (when `NPM_TOKEN` is set) and CocoaPods trunk (when `COCOAPODS_TRUNK_TOKEN` is set); Swift Package
Manager and JitPack read the tag directly. Pushing a tag by hand still works.

## License

[MIT](LICENSE)
