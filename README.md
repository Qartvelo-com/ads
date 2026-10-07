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
| Android core | JitPack | `com.qartvelo.ads:core:0.3.1` |
| Android AdMob adapter (optional) | JitPack | `com.qartvelo.ads:admob:0.3.1` |
| Same, GitHub Packages | `maven.pkg.github.com/Qartvelo-com/ads` | `com.qartvelo.ads:core:0.3.1` |
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
    implementation("com.qartvelo.ads:core:0.3.1")
    implementation("com.qartvelo.ads:admob:0.3.1") // optional
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

Add JitPack to `android/build.gradle` as shown in the
[React Native guide](https://developers.qartvelo.com/react-native/installation/).

## Documentation

**https://developers.qartvelo.com** (source in [`developers/`](developers/)):

- [Quickstart](https://developers.qartvelo.com/get-started/quickstart/)
- [Android SDK](https://developers.qartvelo.com/android/installation/) and
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
react-native/packages/react-native-qartvelo-ads/   @qartvelo/react-native-ads
react-native/example/          React Native example app
developers/                    developer docs site (Astro Starlight) and the MCP server (developers/mcp)
docs/                          pointers to the docs site
```

## Development

```sh
cd android && ./gradlew :qartvelo-ads-core:testDebugUnitTest :qartvelo-ads-admob:testDebugUnitTest
cd android && ./gradlew publishToMavenLocal        # com.qartvelo.ads:*:0.3.1 into ~/.m2

cd react-native/packages/react-native-qartvelo-ads && npm ci && npm test && npm run typecheck
cd react-native/example && npm install && npx react-native run-android   # uses the local SDK build
```

The samples default to `http://10.0.2.2:8000/` (a backend on your machine, seen from the Android
emulator); point them at `https://ads.qartvelo.com/` for the live service.

## Releasing

Bump `version` in `android/build.gradle.kts`, `SDK_VERSION` in `QartveloAds.kt`, the npm
`package.json` files (React Native plugin and `developers/mcp`) and the version strings in
`developers/` (see its README), then push a matching tag (`git tag 0.2.1 && git push origin 0.2.1`). The
[Publish](.github/workflows/publish.yml) workflow publishes to GitHub Packages (and npm when the
`NPM_TOKEN` secret is set); JitPack builds the tag on first request.

## License

[MIT](LICENSE)
