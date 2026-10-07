# @qartvelo/react-native-ads

React Native plugin for the Qartvelo Ads Android SDK: banner, interstitial and rewarded ads from direct
Qartvelo Ads campaigns, with automatic fallback to your AdMob ad units.

All ad logic (campaign selection, timeouts, AdMob fallback, request de-duplication, reward-once)
runs in the native Kotlin SDK. This package is a New Architecture bridge: a TurboModule plus a
Fabric banner component. Android only for now; on iOS every promise rejects with
`unsupported_platform`.

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

Android setup (Maven repository for `com.qartvelo.ads:core`, the optional AdMob adapter via
`QartveloAds_admobEnabled=true`, the AdMob App ID), the full API, events, error codes, testing and
troubleshooting are documented at
[developers.qartvelo.com/react-native](https://developers.qartvelo.com/react-native/installation/).
AI agents can use [llms.txt](https://developers.qartvelo.com/_llms-txt/react-native.txt) or the
[MCP server](https://developers.qartvelo.com/ai/mcp-server/).

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
