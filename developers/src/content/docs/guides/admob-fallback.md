---
title: AdMob fallback
description: How the SDK falls back to your own AdMob ad units, and how to set it up.
---

Qartvelo Ads is the primary ad source. When it has no eligible campaign, fails, is switched off or exceeds its time budget, the SDK can show an ad from **your own** Google AdMob account instead, so a placement is never left empty.

## Your AdMob account, your ad units, your revenue

- You create your own AdMob account, your own AdMob app and your own ad units (one per placement and format) and give those ids to Qartvelo Ads.
- Qartvelo Ads never owns, shares, proxies or pools AdMob inventory and never routes several publishers through one AdMob account.
- Google pays AdMob revenue directly to you. Qartvelo Ads does not receive, report, take a share of, or have access to your AdMob revenue or account.
- The adapter uses Google's official SDK as a normal dependency (`com.google.android.gms:play-services-ads` on Android, `Google-Mobile-Ads-SDK` 12 on iOS), never a modified copy.
- You remain responsible for complying with AdMob policies (placement, invalid traffic, consent).

## Setup

1. In AdMob, create an app for your package and ad units that match your placements: banner units for banner placements, interstitial units for interstitial placements, rewarded units for rewarded placements.
2. Add the AdMob **App ID** (contains `~`) to `AndroidManifest.xml` as `com.google.android.gms.ads.APPLICATION_ID` meta-data, or on iOS to `Info.plist` as `GADApplicationIdentifier`. In React Native the config in step 3 writes both for you.
3. Add the adapter: `implementation("com.qartvelo.ads:admob:0.5.1")`. In React Native, add the `admob` config instead (the Expo plugin entry, or the `"@qartvelo/react-native-ads"` key in `app.json`; see [React Native installation](/react-native/installation/#3-admob-fallback-optional)). On iOS add the `QartveloAdsAdMob` product (or pod) and call `QartveloAds.registerFallbackAdapter(QartveloAdMobFallbackAdapter())` before `initialize` ([iOS installation](/ios/installation/#register-the-admob-adapter)).
4. Map each placement to its ad unit **ID** (contains `/`), either on the placement in the dashboard (fallback provider **AdMob**) or in code:

   ```kotlin
   QartveloAdsOptions(
       admobAdUnits = mapOf(
           "home_banner" to "ca-app-pub-XXX/111",
           "game_end" to "ca-app-pub-XXX/222",
           "reward_coins" to "ca-app-pub-XXX/333",
       ),
   )
   ```

   On iOS: `options.admobAdUnits = ["game_end": "ca-app-pub-XXX/222"]`. The in-code mapping wins over the dashboard value for the same placement code.

No other code is needed. The SDK discovers the adapter automatically, initializes Google Mobile Ads once on a background thread and reports AdMob ads through the same callbacks with `info.source == AdSource.ADMOB` (`'admob'` in React Native).

## When a fallback is possible

All of these must hold for a placement:

- the adapter is present (or a custom adapter was registered with `QartveloAds.registerFallbackAdapter`);
- `admobFallback` is `true` in the options and the remote config allows fallback;
- the placement's fallback provider is `admob` (and the ad response did not say `fallback: none`);
- an ad unit id is known for the placement (in code or in the dashboard), or test mode is on.

## Decision flow

**Interstitial and rewarded loads**

1. A still-valid cached Qartvelo Ads ad completes the load immediately.
2. Otherwise the SDK requests Qartvelo Ads and, if a fallback is possible, **preloads AdMob in parallel**.
3. Qartvelo Ads fill: the creative is downloaded and validated, then `onLoaded(source = QARTVELO)`.
4. No fill, timeout, network error, creative failure or kill switch: `onFallbackStarted(reason)`, then `onLoaded(source = ADMOB)` as soon as AdMob is ready, or `onNoAdAvailable` + `onLoadFailed` if AdMob also has nothing.
5. Show prefers a valid Qartvelo Ads ad, then a ready AdMob ad, else `onNoAdAvailable`.

**Banners** request Qartvelo Ads first. On failure, the AdMob anchored adaptive banner is created in the same view and refreshes itself according to your AdMob settings. The next successful Qartvelo Ads refresh replaces it.

## Fallback reasons

| Reason | Meaning |
|---|---|
| `no_fill` | The backend had no eligible campaign (or test mode forced it) |
| `timeout` | No answer within the request timeout, or the creative was too slow |
| `error` | Network or server error |
| `creative_failed` | The creative could not be downloaded, decoded or rendered |
| `disabled` | Qartvelo Ads is switched off for this placement, app or publisher |

The SDK also sends a fire-and-forget `POST /api/v1/events/fallback` with the placement code and the reason (never any AdMob data), so your reports show fallback counts.

## Timeouts and remote configuration

- The Qartvelo Ads request budget (session plus ad request) defaults to 800 ms. Users never wait for it: loads are asynchronous and AdMob is already preloading.
- Effective timeout: the placement's remote value, else `requestTimeoutMs`, clamped to 100..10000 ms.
- Creative downloads have separate limits (images 10 s, video 45 s), because a Qartvelo Ads ad is only reported as loaded once its creative is on the device.
- Without an app update, the dashboard can switch Qartvelo Ads off per placement (the SDK goes straight to AdMob), change the fallback provider, change the timeout and change the AdMob unit. The last configuration is cached for offline starts.
- If the API is unreachable at start-up, the SDK runs on the cached configuration and falls back quickly; repeated session attempts are throttled.

## Test mode

With `testMode = true` the adapter replaces every unit with Google's public test units and Qartvelo Ads serves only non-billable test creatives. Add `testForceNoFill = true` to see the fallback every time. Never ship a release with test mode or Google's test ids.

## Consent

The adapter does not show consent forms and does not set consent. It respects your Google UMP / CMP configuration and only adds the restrictions you pass through `QartveloAds.setPrivacy`. See [Privacy](/guides/privacy/).
