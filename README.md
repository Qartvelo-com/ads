# Qartvelo Ads SDK

Mobile SDKs for [Qartvelo Ads](https://ads.qartvelo.com), the direct-sold ad network for Georgian
Android apps. Qartvelo Ads campaigns are served first; when there is no eligible campaign, the
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
| Android core | JitPack | `com.github.Qartvelo-com.qartvelo-ads-sdk:qartvelo-ads-core:0.2.0` |
| Android AdMob adapter (optional) | JitPack | `com.github.Qartvelo-com.qartvelo-ads-sdk:qartvelo-ads-admob:0.2.0` |
| Same, GitHub Packages | `maven.pkg.github.com/Qartvelo-com/qartvelo-ads-sdk` | `com.qartvelo:qartvelo-ads-core:0.2.0` |
| React Native (Android) | npm | `npm install @qartvelo/react-native-ads` |

Android only for now; the APIs are shaped so an iOS SDK can be added later.

## Android quick start

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

// app/build.gradle.kts
dependencies {
    implementation("com.github.Qartvelo-com.qartvelo-ads-sdk:qartvelo-ads-core:0.2.0")
    implementation("com.github.Qartvelo-com.qartvelo-ads-sdk:qartvelo-ads-admob:0.2.0") // optional
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

Full guide: [docs/android-integration.md](docs/android-integration.md).

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

Add JitPack to `android/build.gradle` as shown in
[docs/react-native-integration.md](docs/react-native-integration.md).

## Documentation

- [Android integration](docs/android-integration.md)
- [React Native integration](docs/react-native-integration.md)
- [AdMob fallback](docs/admob-fallback.md): you use your own AdMob app and ad units; Qartvelo Ads
  never owns, proxies or receives your AdMob revenue.
- [Privacy](docs/privacy.md): contextual targeting only, no advertising ID, no GPS, no persistent
  user identifier.

## Repository layout

```
android/qartvelo-ads-core/     Kotlin SDK (com.qartvelo.sdk)
android/qartvelo-ads-admob/    optional AdMob fallback adapter (com.qartvelo.admob)
android/sample-app/            native sample (com.qartvelo.ads)
react-native/packages/react-native-qartvelo-ads/   @qartvelo/react-native-ads
react-native/example/          React Native example app
docs/                          integration guides
```

## Development

```sh
cd android && ./gradlew :qartvelo-ads-core:testDebugUnitTest :qartvelo-ads-admob:testDebugUnitTest
cd android && ./gradlew publishToMavenLocal        # com.qartvelo:*:0.2.0 into ~/.m2

cd react-native/packages/react-native-qartvelo-ads && npm ci && npm test && npm run typecheck
cd react-native/example && npm install && npx react-native run-android   # uses the local SDK build
```

The samples default to `http://10.0.2.2:8000/` (a backend on your machine, seen from the Android
emulator); point them at `https://ads.qartvelo.com/` for the live service.

## Releasing

Bump `version` in `android/build.gradle.kts`, `SDK_VERSION` in `QartveloAds.kt` and the npm
`package.json`, then push a matching tag (`git tag 0.2.1 && git push origin 0.2.1`). The
[Publish](.github/workflows/publish.yml) workflow publishes to GitHub Packages (and npm when the
`NPM_TOKEN` secret is set); JitPack builds the tag on first request.

## License

[MIT](LICENSE)
