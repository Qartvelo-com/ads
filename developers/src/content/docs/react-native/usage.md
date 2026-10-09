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
      appKey: { android: 'app_xxxxxxxxxxxxxxxxxxxxxxxx', ios: 'app_yyyyyyyyyyyyyyyyyyyyyyyy' },
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
| `appKey` | `string \| { android, ios }` | required | App key, or one per platform (a key only works on its own platform) |
| `baseUrl` | `string` | `https://ads.qartvelo.com/` | API origin; change only for a self-hosted or local backend |
| `requestTimeoutMs` | `number` | `800` | Qartvelo Ads time budget before falling back. The dashboard value per placement wins |
| `testMode` | `boolean` | `false` | Non-billable test ads labelled "Test ad"; AdMob uses Google's test units |
| `testModeInDebugBuilds` | `boolean` | `true` | Turn test mode on automatically in debuggable Android builds (debug and Expo development builds); ignored on iOS, where all non-App Store installs always use test mode |
| `testForceNoFill` | `boolean` | `false` | Force Qartvelo Ads "no fill" to see the AdMob fallback |
| `admobFallback` | `boolean` | `true` | Allow the AdMob adapter (needs the AdMob setup in [Installation](/react-native/installation/#3-admob-fallback-optional)) |
| `logLevel` | `'none' \| 'error' \| 'info' \| 'debug'` | `'error'` | Logcat verbosity (tag `QartveloAds`) |
| `admobAdUnits` | `Record<string, string \| { android, ios }>` | `{}` | Placement code to AdMob unit id, or one per platform; overrides the dashboard |
| `admobTestUnitsInDebugBuilds` | `boolean` | `true` | Google's test units for the AdMob fallback in debuggable Android builds; ignored on iOS |
| `preload` | `{ interstitial?: string[]; rewarded?: string[] }` | none | Load these placements at start-up and again after each show |

- Initialization is idempotent for the life of the **process**. Later calls resolve or reject with the first result and ignore new options, except `preload`: the placements listed in a later call are still loaded and reloaded after each show. Restart the app to change the other options; a JS reload is not enough.
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

- Collapsed (height 0) until an ad is rendered, then takes the height of a compact anchored adaptive banner at the component's width: 50 to 90 dp on Android, 50 to 90 points on iOS. That is the same height as the AdMob fallback banner, so the layout does not jump when one replaces the other. Set an explicit `height` to reserve space instead.
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

## Preload and loadIfNeeded

```tsx
await QartveloAds.initialize({
  appKey: { android: 'app_...', ios: 'app_...' },
  preload: { interstitial: ['game_end'], rewarded: ['reward_coins'] },
});

// A break in the game: shows a preloaded ad, or resolves { shown: false } at once.
await QartveloAds.showInterstitial('game_end');

// The user asked for a reward: waits for a load when nothing is ready.
const result = await QartveloAds.showRewarded('reward_coins', { loadIfNeeded: true });
if (result.rewarded) grantCoins(50);
```

Preloaded placements reload after every show. With `loadIfNeeded`, a failed load resolves
`{ shown: false }` (and `rewarded: false`) instead of rejecting.

## AdMob fallback

When Qartvelo Ads has no ad for a placement (no fill, a timeout, a network error, a creative that
fails to load, or Qartvelo Ads switched off for the placement), the SDK shows an ad from **your own**
AdMob account instead. Banners, interstitials and rewarded ads all fall back, and your code stays the
same: `showInterstitial` and `showRewarded` resolve the same way, and `source` is `'admob'` for an
AdMob ad. How it works and who gets paid: [AdMob fallback](/guides/admob-fallback/).

1. **Native setup, once.** Add your AdMob App IDs with the config plugin (Expo) or in `app.json` (bare
   React Native), as in [Installation](/react-native/installation/#3-admob-fallback-optional), then
   rebuild the app.
2. **An AdMob ad unit per placement**, of the same format (banner, interstitial or rewarded). AdMob
   unit ids differ per platform, so pass `{ android, ios }`:

   ```tsx
   QartveloAds.initialize({
     appKey: { android: 'app_...', ios: 'app_...' },
     admobAdUnits: {
       home_banner: { android: 'ca-app-pub-XXX/111', ios: 'ca-app-pub-XXX/222' },
       game_end: { android: 'ca-app-pub-XXX/333', ios: 'ca-app-pub-XXX/444' },
       reward_coins: { android: 'ca-app-pub-XXX/555', ios: 'ca-app-pub-XXX/666' },
     },
     preload: { interstitial: ['game_end'], rewarded: ['reward_coins'] },
   });
   ```

   A unit given here wins over the AdMob unit set on the placement in the dashboard. A placement with
   no unit in either place has no fallback.
3. **Check it** in a development build: add `testForceNoFill: true` to `initialize`, restart the
   app, and every placement shows Google's test ads ("Test Ad"). Remove it before you release.

- **Test units.** Debuggable Android builds use Google's test units for the fallback
  (`admobTestUnitsInDebugBuilds`, default true), and so does every iOS install outside the App Store,
  TestFlight included. Store builds use your real units. Never tap your own live AdMob ads.
- **Events.** The banner's `onFallbackStarted` and the global `fallbackStarted` event report the
  reason: `no_fill`, `timeout`, `error`, `creative_failed` or `disabled`. `loaded` and the show
  results carry `source: 'admob'`. See [Events and errors](/react-native/events-and-errors/).
- **Banner size.** A Qartvelo Ads banner reserves the same anchored adaptive slot as the AdMob
  banner, so both are the same height.
- **Consent.** The fallback uses your own Google Mobile Ads setup: run Google's consent flow (UMP)
  yourself where it is required. The SDK only forwards the signals you pass to `setPrivacy`; see
  [Privacy](/guides/privacy/#admob-fallback-and-consent).
- **Turn it off** with `admobFallback: false`.

## Setup issues

In development, the SDK prints setup problems once with `console.warn` (they show in LogBox): an app
key registered for another package or platform, or a placement code that the dashboard does not have
(or has with another format). Listen for them yourself with
`QartveloAds.addListener('setupIssue', (issue) => ...)`; `issue.code` is `package_mismatch`,
`platform_mismatch`, `unknown_placement` or `format_mismatch`.

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
