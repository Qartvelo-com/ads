---
title: Events and errors
description: Subscribe to SDK events and handle QartveloAdsError codes in React Native.
---

## Events

`QartveloAds.addListener(type, listener)` observes events of every placement and format, banners included. Remove subscriptions when the screen goes away:

```tsx
useEffect(() => {
  const subscriptions = [
    QartveloAds.addListener('fallbackStarted', (e) => log(`fallback ${e.placementId}: ${e.reason}`)),
    QartveloAds.addListener('impression', (e) => analytics.track('ad_impression', { placement: e.placementId, source: e.source })),
  ];
  return () => subscriptions.forEach((s) => s.remove());
}, []);
```

| Event | When | Extra fields |
|---|---|---|
| `loaded` | An ad is ready (Qartvelo Ads, or AdMob after a fallback) | `source`, `campaignId?`, `creativeId?` |
| `loadFailed` | A load or show failed | `error: { code, message }`; `format` may be missing for a placement this app never loaded |
| `shown` | The ad is on screen | `source`, ids |
| `impression` | The impression was counted | `source`, ids |
| `clicked` | The user tapped the ad (at most once per ad) | `source`, ids |
| `dismissed` | A full-screen ad closed | `source`, ids |
| `rewarded` | Completion confirmed for a rewarded ad | `source`, `reward: { type, amount }` |
| `fallbackStarted` | Qartvelo Ads could not serve; the fallback is being tried | `reason`: `no_fill`, `timeout`, `error`, `creative_failed`, `disabled` |
| `noAdAvailable` | No source has an ad | |

Every payload has `type`, `placementId` and `format` (`banner`, `interstitial` or `rewarded`). `source` is `qartvelo` or `admob`; `campaignId` and `creativeId` are set only for Qartvelo Ads ads.

Each `addListener` call is an independent subscription; `remove()` is safe to call twice. `QartveloAds.removeAllListeners(type?)` clears listeners in bulk. The native event stream is open only while at least one listener exists. Payloads are fully typed through `QartveloAdsEventMap`.

## Errors

Every promise rejects with a `QartveloAdsError` that has `code`, `message`, and `placementId` for placement calls. Narrow with `isQartveloAdsError`:

```ts
import { isQartveloAdsError } from '@qartvelo/react-native-ads';

try {
  await QartveloAds.loadRewarded('reward_coins');
} catch (error) {
  if (isQartveloAdsError(error) && error.code === 'no_fill') {
    hideRewardButton();
  }
}
```

| Code | Meaning |
|---|---|
| `not_initialized` | `initialize()` was not called, or the backend rejected the app key / package name |
| `invalid_placement` | Unknown placement code, or a placement used with the wrong format |
| `no_fill` | Neither Qartvelo Ads nor the fallback had an ad |
| `timeout` / `network_error` | The backend was slow or unreachable and no fallback was available |
| `creative_failed` | The creative could not be downloaded or decoded |
| `already_showing` | Another full-screen ad is on screen |
| `show_failed` | The ad could not be displayed (for example no foreground Activity) |
| `ad_expired` | The ad expired before it was shown |
| `internal_error` | Unexpected native failure, or an unknown code |
| `invalid_argument` | A JavaScript argument was rejected before reaching native code |
| `unsupported_platform` | Called on a platform without the SDK (iOS, web) |
| `module_unavailable` | The native module is missing: rebuild the Android app |
