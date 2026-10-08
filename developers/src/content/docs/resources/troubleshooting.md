---
title: Troubleshooting
description: Diagnose initialization failures, missing ads, fallback problems and build errors.
---

Start with debug logging: `logLevel = QartveloAdsLogLevel.DEBUG` (`logLevel: 'debug'` in React Native), then `adb logcat -s QartveloAds`. Logs show request timing, fallback decisions and event delivery (never tokens).

## Initialization fails

| Symptom | Cause and fix |
|---|---|
| `NOT_INITIALIZED` with `invalid_app_key` in the log | Wrong app key. Copy it from the app page in the dashboard |
| `package_mismatch` | The running `applicationId` (or iOS bundle ID) differs from the registered one. Check `applicationIdSuffix` in debug builds and flavors, or per-configuration bundle IDs in Xcode |
| `platform_mismatch` | The iOS app uses the Android app's key or the other way round. Register each platform as its own app and use its key |
| `app_not_approved` | The app is still pending. Use test mode until it is approved |
| `NETWORK_ERROR` / `TIMEOUT` | No connectivity, a custom `baseUrl` that is wrong, or cleartext HTTP blocked for a local backend |
| The listener never reports a second result | `initialize` is idempotent; only the first call's options count. Restart the process |

Initialization failure is not fatal: the SDK keeps running on its cached configuration and can fall back to AdMob.

## No ads appear

1. Is the placement code exactly the one in the dashboard, and the format right? A wrong format gives `INVALID_PLACEMENT` (`format_mismatch` / `placement_not_found` in the log).
2. In test mode you should always get a **TEST AD** creative unless `testForceNoFill` is on. If test mode works but live does not, check that the account, app and placement are approved and active.
3. Live no-fill is normal when no campaign targets your app's category, language or users' Android version. Configure an AdMob fallback so the slot is still filled.
4. `onFallbackStarted` with reason `disabled` means Qartvelo Ads is switched off for the placement, app or account.
5. For full-screen ads, make sure you call `show*` only after `onLoaded`, from a resumed Activity, and that no other full-screen ad is on screen (`ALREADY_SHOWING`).

## Fallback does not show AdMob

| Check | |
|---|---|
| Adapter included | `com.qartvelo.ads:admob` (Android) or `QartveloAds_admobEnabled=true` (React Native) |
| Option | `admobFallback` is not `false` |
| Placement | Fallback provider is **AdMob**, not **None** |
| Unit id | Set on the placement or in `admobAdUnits`, and of the same format as the placement. An App ID (with `~`) is not an ad unit id |
| AdMob side | New AdMob units can take hours to serve; AdMob may also have no fill. Test with `testMode` (Google test units) first |

## Build errors

| Error | Fix |
|---|---|
| `Could not find com.qartvelo.ads:core:0.4.1` | Add `mavenCentral()` to the repositories used for dependencies (`dependencyResolutionManagement` or `allprojects`). Versions before 0.3.4 are only on JitPack (`maven("https://jitpack.io")`) |
| `AAPT: error: attribute qartvelo_placementId not found` | Declare `xmlns:app="http://schemas.android.com/apk/res-auto"` and make sure `com.qartvelo.ads:core` is a dependency of that module |
| `attribute ourads_placementId not found` | Old attribute name from pre-release snippets. Use `app:qartvelo_placementId` |
| `Unresolved reference: OURADS` | The enum value is `AdSource.QARTVELO` |
| Crash at start: "The Google Mobile Ads SDK was initialized incorrectly" | The AdMob adapter is present but the AdMob App ID meta-data is missing from the manifest |
| Duplicate class / version conflicts with `play-services-ads` | The adapter depends on 25.4.0 normally; your newer version wins. Align with Gradle's dependency resolution if you pin an older one |

## Events and reporting

- Impressions are counted only when the ad is actually on screen; loads alone count as requests.
- Requests, no-fills and fallbacks in reports can lag up to a minute; impressions and revenue are real time.
- Test traffic never appears in reports.
- Clicking your own ads is detected (fast clicks, high CTR) and not counted.

Still stuck? Open an issue at [github.com/Qartvelo-com/ads/issues](https://github.com/Qartvelo-com/ads/issues) with the SDK version, a DEBUG log and the placement code (never your SDK secret).
