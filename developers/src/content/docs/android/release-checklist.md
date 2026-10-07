---
title: Release checklist
description: Everything to verify before shipping a build with Qartvelo Ads to production.
---

- [ ] The app and its placements are **approved** in the dashboard and placements are `active`.
- [ ] The release `applicationId` equals the package name registered for the app.
- [ ] `testMode = false` and `testForceNoFill = false` in release builds (for example `testMode = BuildConfig.DEBUG`).
- [ ] `baseUrl` is the default `https://ads.qartvelo.com/` (or your production HTTPS origin); no cleartext network config in the release manifest.
- [ ] Your **own** AdMob App ID is in the manifest and your own ad unit ids are set per placement (dashboard or `admobAdUnits`); no Google test ids in production. See [AdMob fallback](/guides/admob-fallback/).
- [ ] Consent is collected by your CMP where required and passed with `QartveloAds.setPrivacy`. See [Privacy](/guides/privacy/).
- [ ] `logLevel` is `ERROR` or `NONE`.
- [ ] A minified (R8) release build was tested once on a real device. Consumer rules are bundled.
- [ ] `banner.destroy()` is called when banner screens are destroyed.
- [ ] Your game or app flow continues from every terminal show callback (`onDismissed`, `onNoAdAvailable`, `onLoadFailed`).
- [ ] Rewards are granted only from `onReward` (or `result.rewarded` in React Native), never from `onDismissed`.
- [ ] Your Google Play Data safety form reflects what the SDK sends. See [Privacy](/guides/privacy/#google-play-data-safety).

After release, watch the **Fill rate** and **Fallbacks** columns in your reports. Many fallbacks with a healthy fill rate usually mean requests time out: check a debug build's `onFallbackStarted` reasons and consider raising the placement's request timeout.
