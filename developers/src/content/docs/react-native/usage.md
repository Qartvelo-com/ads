---
title: Usage
description: Initialize, show banners, interstitials and rewarded ads from React Native.
---

## Initialize

Call `initialize` once, as early as possible, for example in your root component.

```tsx title="App.tsx"
import { useEffect } from 'react';
import { QartveloAds, isQartveloAdsError } from '@qartvelo/react-native-ads';

export default function App() {
  useEffect(() => {
    QartveloAds.setPrivacy({ consentGiven: undefined }); // see Privacy guide
    QartveloAds.initialize({
      appKey: 'app_xxxxxxxxxxxxxxxxxxxxxxxx',
      testMode: __DEV__,
      logLevel: __DEV__ ? 'debug' : 'error',
    }).catch((error) => {
      // Not fatal: the SDK keeps working on its cached config and the AdMob fallback.
      if (isQartveloAdsError(error)) console.warn(error.code, error.message);
    });
  }, []);

  return <Root />;
}
```

| Option | Type | Default | Meaning |
|---|---|---|---|
| `appKey` | `string` | required | Publisher app key (`app_...`) |
| `baseUrl` | `string` | `https://ads.qartvelo.com/` | API origin; change only for a self-hosted or local backend |
| `requestTimeoutMs` | `number` | `800` | Qartvelo Ads time budget before falling back. The dashboard value per placement wins |
| `testMode` | `boolean` | `false` | Non-billable test ads labelled "Test ad"; AdMob uses Google's test units |
| `testModeInDebugBuilds` | `boolean` | `true` | Turn test mode on automatically in debuggable Android builds (debug and Expo development builds) |
| `testForceNoFill` | `boolean` | `false` | Force Qartvelo Ads "no fill" to see the AdMob fallback |
| `admobFallback` | `boolean` | `true` | Allow the AdMob adapter (needs `QartveloAds_admobEnabled=true`) |
| `logLevel` | `'none' \| 'error' \| 'info' \| 'debug'` | `'error'` | Logcat verbosity (tag `QartveloAds`) |
| `admobAdUnits` | `Record<string, string>` | `{}` | Placement code to AdMob unit id; overrides the dashboard |

- Initialization is idempotent for the life of the **process**. Later calls resolve or reject with the first result and ignore new options. Restart the app to change them; a JS reload is not enough.
- A rejection (`network_error`, `timeout`, or `not_initialized` for a rejected key) does not disable ads.
- Loads issued while initialization is still running wait for it.
- `await QartveloAds.isInitialized()` is `true` once the first attempt finished.

## Banner

```tsx
import { QartveloAdsBanner } from '@qartvelo/react-native-ads';

<QartveloAdsBanner
  placementId="home_banner"
  style={{ width: '100%' }}
  onLoaded={(e) => console.log('banner from', e.source)}
  onLoadFailed={(e) => console.log(e.error.code)}
  onNoAdAvailable={() => setShowBanner(false)}
/>
```

- Collapsed (height 0) until an ad is rendered, then takes the creative's height. Set an explicit `height` to reserve space instead.
- Props: `placementId` (required), `style`, `onLoaded`, `onLoadFailed`, `onShown`, `onImpression`, `onClicked`, `onFallbackStarted`, `onNoAdAvailable`, `onSizeChange`.
- Re-renders never reach the SDK. Changing `placementId` loads the new placement.
- Remounting (navigation, list recycling) does not request a new ad: the SDK keeps one banner per placement and the new view re-attaches. The component's `onLoaded` fires again for the new view; global listeners do not get a second `loaded`.
- Refresh follows the placement's `banner_refresh_seconds` (minimum 30 s) and pauses while off screen or in the background.
- One visible banner per placement code at a time.
- A banner mounted before `initialize()` waits and loads as soon as initialization starts.

## Interstitial

```tsx
async function onLevelComplete() {
  try {
    const result = await QartveloAds.showInterstitial('game_end'); // resolves on dismiss
    if (!result.shown) {
      // nothing was ready
    }
  } catch (error) {
    // already_showing, show_failed, ...
  } finally {
    continueGame();
    QartveloAds.loadInterstitial('game_end').catch(() => {}); // preload the next one
  }
}

// Preload when the level starts
QartveloAds.loadInterstitial('game_end').catch(() => {});
```

- `loadInterstitial` resolves with `AdInfo` (`{ placementId, format, source, campaignId?, creativeId? }`) when an ad from Qartvelo Ads or AdMob is ready, and rejects when no source has one. Concurrent loads share one request.
- `showInterstitial` resolves `{ shown: true, source }` after the ad is dismissed, or `{ shown: false }` immediately when nothing was ready. It rejects with `already_showing` while another full-screen ad is visible and with `show_failed` if the ad could not be displayed.
- `isInterstitialReady(placementId)` resolves whether a show would display an ad now.
- A Qartvelo Ads ad is valid for 30 minutes after loading.

## Rewarded

```tsx
await QartveloAds.loadRewarded('reward_coins');

const result = await QartveloAds.showRewarded('reward_coins');
if (result.rewarded) {
  grantCoins(50);
}
```

- `result.rewarded` is `true` exactly once per show and only after the SDK confirmed completion, whichever network served the ad. A skipped ad resolves `{ shown: true, rewarded: false }`.
- `result.reward` is `{ type, amount }`: `{ type: 'reward', amount: 1 }` for Qartvelo Ads, your AdMob unit's settings for AdMob.
- Grant the reward from the promise result **or** the `rewarded` event, never from both.

## Hooks pattern

```tsx
import { useCallback, useEffect, useState } from 'react';
import { QartveloAds } from '@qartvelo/react-native-ads';

export function useRewarded(placementId: string) {
  const [ready, setReady] = useState(false);

  const load = useCallback(() => {
    setReady(false);
    QartveloAds.loadRewarded(placementId).then(() => setReady(true), () => setReady(false));
  }, [placementId]);

  useEffect(load, [load]);

  const show = useCallback(async () => {
    const result = await QartveloAds.showRewarded(placementId);
    load();
    return result.rewarded;
  }, [placementId, load]);

  return { ready, show };
}
```

## Privacy and logs

```ts
QartveloAds.setPrivacy({ consentGiven: false, childDirected: true });
QartveloAds.setLogLevel('debug');
```

Omitted privacy fields stay unknown; consent is never assumed. See [Privacy](/guides/privacy/).
