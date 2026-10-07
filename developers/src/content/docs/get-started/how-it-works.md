---
title: How it works
description: The life of an ad request, from SDK start-up to impression, fallback and billing.
---

This page explains what the SDK does on your behalf. You do not need to implement any of it, but knowing it helps you place ads well and read your reports.

## Start-up

1. Your app calls `QartveloAds.initialize(context, appKey, options)` once, usually in `Application.onCreate`.
2. The SDK calls `POST /api/v1/sdk/initialize` with the app key and your package name. The backend checks that the key belongs to an app registered with that exact package name, and returns:
   - a **session token**, valid for one hour, that carries a random session id (never a user or device id);
   - the **remote configuration**: kill switches, per-placement request timeout, fallback provider, your AdMob unit id, frequency cap and banner refresh interval.
3. The SDK caches the configuration on disk. On the next start, loads begin immediately from the cached copy while a fresh one is fetched, so a slow or unreachable backend never delays your UI.

## Loading a full-screen ad

```
loadInterstitial("game_end")
 ├─ Qartvelo Ads request (bounded by the placement timeout, default 800 ms)
 │    fill ──> download the creative ──> onLoaded(source = QARTVELO)
 │    no fill / timeout / error ──> onFallbackStarted(reason) ──> onLoaded(source = ADMOB)
 └─ AdMob preload in parallel (only when a fallback is possible)
```

- **Fill**: the backend selected a campaign and returned a creative URL plus a signed impression token. The SDK downloads the image or video first; an ad is only reported as loaded once it can be displayed instantly.
- **No fill**: no eligible campaign, the placement or app is switched off, the frequency cap was reached, or test mode forced it. The response says which fallback to use (`admob` or `none`).
- **Timeout**: the backend did not answer within the request timeout. AdMob, already preloading, takes over.
- **Slow creative**: if the Qartvelo Ads creative is still downloading 1.5 s after the timeout, a ready AdMob ad completes the load. The Qartvelo Ads ad keeps downloading and is used by the next show.

Concurrent loads of the same placement share one request. A Qartvelo Ads ad is valid for 30 minutes; the SDK never shows an expired ad.

## Showing

`show*` displays the best ready ad: a valid Qartvelo Ads ad first, otherwise a ready AdMob ad, otherwise `onNoAdAvailable`. When the creative is on screen the SDK reports the **impression** to the backend; taps report a **click** (at most one per ad) before the browser opens. Rewarded ads report the completion once the video finished.

Events are sent on a background queue, in order (impression before click), and are retried on network errors. Each impression token can be used exactly once, so replays and duplicates are rejected by the server and never billed.

## Banners

A banner placement has one controller per placement code. The view requests an ad, shows it, and refreshes no faster than the placement's `banner_refresh_seconds` (at least 30 s), only while the view is visible and the Activity is started. If Qartvelo Ads cannot fill, your AdMob adaptive banner is shown in the same view; a later Qartvelo Ads fill replaces it.

## Selection and billing on the server

For each request the backend picks the highest CPM bid among campaigns that are active, within their schedule and budget, approved, and match the request's context (country, language, Android version, app, app category and format). Campaign and placement frequency caps use the session id. The chosen campaign's cost for one impression is reserved atomically, so budgets can never be overspent, and is charged when the impression event arrives. Publishers earn their revenue share of every billed impression. Clicks are recorded but not billed (all campaigns are CPM).

## Remote control

Everything below can change without an app update:

| Setting | Where | Effect in the app |
|---|---|---|
| Qartvelo Ads on/off per placement | Publisher dashboard | SDK goes straight to the fallback; re-checks at most every 5 minutes |
| Fallback provider and AdMob unit | Publisher dashboard | Next load uses the new unit |
| Request timeout | Publisher dashboard | Next load |
| Banner refresh interval | Publisher dashboard | Next refresh |
| App, publisher or global kill switch | Qartvelo Ads admins | All placements fall back |
