# @qartvelo/react-native-ads

React Native plugin for the Qartvelo Ads Android and iOS SDKs: banner, interstitial and rewarded ads from direct
Qartvelo Ads campaigns, with automatic fallback to your AdMob ad units.

All ad logic (campaign selection, timeouts, AdMob fallback, request de-duplication, reward-once)
runs in the native SDKs. This package is a New Architecture bridge: a TurboModule plus a
Fabric banner component. Android and iOS share the same JavaScript API.

```tsx
import { QartveloAds, QartveloAdsBanner } from '@qartvelo/react-native-ads';

await QartveloAds.initialize({ appKey: 'app_xxx', requestTimeoutMs: 800, testMode: false });

await QartveloAds.loadInterstitial('game_end');
await QartveloAds.showInterstitial('game_end'); // resolves { shown, source } on dismiss

await QartveloAds.loadRewarded('reward_coins');
const result = await QartveloAds.showRewarded('reward_coins');
if (result.rewarded) {
  // grant result.reward
}

const subscription = QartveloAds.addListener('fallbackStarted', (e) => console.log(e.reason));
subscription.remove();

<QartveloAdsBanner placementId="home_banner" style={{ width: '100%' }} />;
```

Native setup (iOS CocoaPods autolinking, Android Maven repository for `com.qartvelo.ads:core`, the optional AdMob adapter and
its App IDs through an Expo plugin entry or an `app.json` key), the full API, events, error codes, testing and
troubleshooting are documented at
[developers.qartvelo.com/react-native](https://developers.qartvelo.com/react-native/installation/).
AI agents can use [llms.txt](https://developers.qartvelo.com/_llms-txt/react-native.txt) or the
[MCP server](https://developers.qartvelo.com/ai/mcp-server/).

## iOS installation

```sh
npm install @qartvelo/react-native-ads
cd ios && pod install && cd ..
npx react-native run-ios
```

The npm package includes the canonical Swift SDK sources and privacy manifest. CocoaPods autolinks
`RNQartveloAds`; you do not need a separate SPM dependency or a Qartvelo CocoaPods spec repository.
Do not link a second copy of the native SDK into the same target.

For the optional AdMob fallback, add the Expo plugin entry or the `app.json` key described at [developers.qartvelo.com/react-native/installation](https://developers.qartvelo.com/react-native/installation/); no Podfile or Gradle edits are needed.
Android and iOS ad unit IDs differ: pass them per platform as `{ android, ios }` in `admobAdUnits`.
Simulator and every non-App Store installation always use non-billable test traffic, even with
`testMode: false`. `testModeInDebugBuilds` applies only to Android.

## Development

```sh
npm install
npm test            # Jest, native module mocked
npm run typecheck   # tsc
npm run lint        # ESLint + Prettier
npm run build       # react-native-builder-bob -> lib/
```

Kotlin unit tests run through the example app:
`cd ../../example/android && ./gradlew :qartvelo_react-native-ads:testDebugUnitTest`.

`npm run build` and `npm pack` synchronize iOS sources from `sdk/ios/Sources`. Never edit
`ios/sdk` copies. Build the iOS example with CocoaPods and Xcode to check the real bridge.
