# AdMob fallback

Qartvelo Ads is the primary ad source. When Qartvelo Ads has no eligible campaign, fails, is switched off or
exceeds its time budget, the SDK can show an ad from **your own** Google AdMob account instead, so a
placement is not left empty.

## Your AdMob account, your ad units, your revenue

- You must create **your own** AdMob account, your own AdMob app and your own ad units (one per
  Qartvelo Ads placement and format), and provide those ids to QartveloAds.
- Qartvelo Ads never owns, shares, proxies or pools AdMob inventory. Every publisher uses their own
  AdMob App ID and ad unit ids; Qartvelo Ads never routes several publishers through one AdMob account.
- Google pays AdMob revenue directly to you under your agreement with Google. Qartvelo Ads does not
  receive, report, take a share of, or have access to your AdMob revenue or AdMob account.
- The AdMob SDK is Google's official `com.google.android.gms:play-services-ads` artifact, used as a
  normal Gradle dependency. Qartvelo Ads does not bundle or modify a private copy of it.
- You remain responsible for complying with AdMob policies (placement, invalid traffic, consent).

## Setup

1. In AdMob, create an app for your package and ad units matching your Qartvelo Ads placements:
   banner units for banner placements, interstitial units for interstitial placements, rewarded
   units for rewarded placements.
2. Add the AdMob App ID to your `AndroidManifest.xml`
   (`com.google.android.gms.ads.APPLICATION_ID` meta-data), as Google requires.
3. Add the adapter dependency: `implementation("com.github.Qartvelo-com.qartvelo-ads-sdk:qartvelo-ads-admob:0.2.0")` (JitPack).
4. Map each placement code to your ad unit id, either in the publisher dashboard (placement form,
   "AdMob ad unit ID") or in code:

   ```kotlin
   QartveloAdsOptions(
       admobFallback = true,
       admobAdUnits = mapOf(
           "home_banner" to "ca-app-pub-XXX/111",
           "game_end" to "ca-app-pub-XXX/222",
           "reward_coins" to "ca-app-pub-XXX/333",
       ),
   )
   ```

   The in-code mapping wins over the dashboard value for the same placement code.

No other code is needed: the SDK discovers the adapter automatically, initializes Google Mobile Ads
once on a background thread and reports AdMob ads through the same `QartveloAdsListener` callbacks with
`info.source == AdSource.ADMOB`.

## How the SDK decides

Fallback is **possible** for a placement only when all of these hold:

- the `qartvelo-ads-admob` adapter is present (or a custom adapter was registered with
  `QartveloAds.registerFallbackAdapter`);
- `QartveloAdsOptions.admobFallback` is `true` and the remote config has `fallback_enabled = true`;
- the placement's `fallback_provider` is `admob` (and the ad response did not say `fallback: none`);
- an ad unit id is known for the placement (in-code mapping, else dashboard), or test mode is on.

Interstitial and rewarded loads:

1. A still-valid cached Qartvelo Ads ad completes the load immediately.
2. Otherwise the SDK requests Qartvelo Ads and, if fallback is possible, **preloads AdMob in parallel**.
3. Qartvelo Ads fill: the creative is downloaded and validated first; then `onLoaded(source = OURADS)`.
4. Qartvelo Ads no-fill, timeout, network error, creative failure or kill switch:
   `onFallbackStarted(reason)`, then `onLoaded(source = ADMOB)` as soon as AdMob is ready, or
   `onNoAdAvailable` + `onLoadFailed` if AdMob also has nothing.
5. Show prefers a valid Qartvelo Ads ad, then a ready AdMob ad, else `onNoAdAvailable`. If an Qartvelo Ads
   creative fails to render before anything was displayed, a ready AdMob ad is shown instead.

Banners request Qartvelo Ads first; on failure the AdMob adaptive banner is created in the same view and
keeps refreshing itself (AdMob refresh settings apply). The next successful Qartvelo Ads refresh replaces
it.

Reasons reported in `onFallbackStarted`: `no_fill`, `timeout`, `error`, `creative_failed`,
`disabled`. The SDK also sends a fire-and-forget `POST /api/v1/events/fallback` so your Qartvelo Ads
reports show fallback counts. It contains only the placement code and the reason, never AdMob data.

## Timeouts and remote configuration

- The Qartvelo Ads request budget (session plus ad request) defaults to 800 ms. Users never wait for it:
  loads are asynchronous and AdMob is already preloading.
- Effective timeout: the placement's remote `request_timeout_ms`, else
  `QartveloAdsOptions.requestTimeoutMs`, clamped to 100..10000 ms.
- Creative downloads have their own longer limits (images 10 s, video 45 s) because an Qartvelo Ads ad is
  only reported as loaded once its creative is on the device.
- The backend can, without an app update: disable Qartvelo Ads globally or per publisher, app or
  placement (the SDK goes straight to AdMob), disable fallback, change the timeout, and change the
  AdMob unit for a placement. The last configuration is cached for offline starts.
- If the Qartvelo Ads API is unreachable at start-up, the SDK runs on the cached configuration and falls
  back quickly; repeated session attempts are throttled so an outage never blocks your UI.

## Test mode

With `QartveloAdsOptions(testMode = true)` the adapter replaces every unit with Google's public test units
(banner `ca-app-pub-3940256099942544/9214589741`, interstitial
`ca-app-pub-3940256099942544/1033173712`, rewarded `ca-app-pub-3940256099942544/5224354917`), and
Qartvelo Ads serves only non-billable test creatives. Combine with `testForceNoFill = true` to see the
fallback path every time. Never ship a release build with test mode or Google's test ids.

## Consent

The adapter does not show consent forms and does not set consent. It respects the configuration
you make with Google's UMP SDK or your CMP, and only adds restrictions you pass through
`QartveloAds.setPrivacy` (child-directed or under-age treatment, non-personalized ads when consent was
refused). See `docs/privacy.md`.
