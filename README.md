# Qartvelo Ads SDK

Mobile SDKs for [Qartvelo Ads](https://ads.qartvelo.com), the direct-sold ad network for Georgian
Android and iOS apps. Qartvelo Ads campaigns are served first; when there is no eligible campaign, the
request fails or it times out, the SDK automatically shows **your own** AdMob ad unit for the same
placement.

```
React Native app ──> @qartvelo/react-native-ads ──> Qartvelo Ads Kotlin SDK
                                                         │
                                       Qartvelo Ads ad available?
                                         yes │         │ no / timeout
                                    Qartvelo Ads ad   AdMob adapter ──> your AdMob account
```

| Package | Where | Install |
|---|---|---|
| Android core | Maven Central | `com.qartvelo.ads:core:0.4.0` |
| Android AdMob adapter (optional) | Maven Central | `com.qartvelo.ads:admob:0.4.0` |
| Same, also on | JitPack, GitHub Packages (`maven.pkg.github.com/Qartvelo-com/ads`) | `com.qartvelo.ads:core:0.4.0` |
| iOS core | Swift Package Manager (`https://github.com/Qartvelo-com/ads`) or CocoaPods | `QartveloAds` 0.4.0 |
| iOS AdMob adapter (optional) | same | `QartveloAdsAdMob` 0.4.0 |
| React Native (Android) | npm | `npm install @qartvelo/react-native-ads` |

The React Native plugin is Android only for now; native iOS apps use the Swift SDK.

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
    implementation("com.qartvelo.ads:core:0.4.0")
    implementation("com.qartvelo.ads:admob:0.4.0") // optional
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

In Xcode, **File > Add Package Dependencies**, `https://github.com/Qartvelo-com/ads`, products
`QartveloAds` and (optional) `QartveloAdsAdMob`. Or with CocoaPods: `pod 'QartveloAds', '~> 0.4'`.

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

The native SDK comes from Maven Central, which React Native projects already use; see the
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
ios/Sources/QartveloAds/       Swift SDK (Package.swift and the podspecs are at the repository root)
ios/Sources/QartveloAdsAdMob/  optional AdMob fallback adapter
ios/Tests/                     XCTest unit tests
react-native/packages/react-native-qartvelo-ads/   @qartvelo/react-native-ads
react-native/example/          React Native example app
developers/                    developer docs site (Astro Starlight) and the MCP server (developers/mcp)
docs/                          pointers to the docs site
```

## Development

```sh
cd android && ./gradlew :qartvelo-ads-core:testDebugUnitTest :qartvelo-ads-admob:testDebugUnitTest
cd android && ./gradlew publishToMavenLocal        # com.qartvelo.ads:*:0.4.0 into ~/.m2

# iOS (on a Mac): open Package.swift in Xcode and run the tests, or
xcodebuild test -scheme QartveloAds-Package -destination 'platform=iOS Simulator,name=iPhone 16'

cd react-native/packages/react-native-qartvelo-ads && npm ci && npm test && npm run typecheck
cd react-native/example && npm install && npx react-native run-android   # uses the local SDK build
```

The samples default to `http://10.0.2.2:8000/` (a backend on your machine, seen from the Android
emulator); point them at `https://ads.qartvelo.com/` for the live service.

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
