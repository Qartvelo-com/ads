# Simple React Native and Expo integration (SDK 0.5.0)

Status: design approved in conversation on 2026-10-09, spec awaiting review.

## Why

Integrating `@qartvelo/react-native-ads` 0.4.1 with the AdMob fallback into an Expo app (Truth or
Dare, Expo SDK 55, React Native 0.83) needed all of this by hand:

1. A custom config plugin (`plugins/withQartveloAdsAdMob.js`, about 80 lines) to set
   `QartveloAds_admobEnabled`, the Podfile `ENV['QARTVELO_ADS_ADMOB_ENABLED']`, the AdMob App ID in
   `AndroidManifest.xml` and `Info.plist`, 50 SKAdNetwork identifiers and the measurement delay.
2. `Platform.select` for the app key (a key only works on its own platform) and for every AdMob
   unit ID.
3. A hand-written list of Google's test unit IDs, because AdMob only switches to test units while
   Qartvelo test mode is on, and the app keeps test mode off in debug builds on purpose.
4. Helper code for "show the interstitial only if ready, then preload the next one" and "load the
   reward video if needed, then show it".
5. Setup mistakes found late: an iOS app registered with the Android package name
   (`package_mismatch`) and an iOS app without placements only showed up as one log line, and were
   diagnosed with `curl` against the API.
6. Two build problems: a transient JitPack timeout broke the Android build because the RN module
   adds JitPack and GitHub Packages for every app, and `com.qartvelo.ads:admob` exposes
   `play-services-ads` 25.4.0 (Kotlin 2.3 metadata) at compile scope, which the Kotlin 2.1
   compiler of React Native 0.83 rejects.

## Goal and success criteria

A React Native app adds Qartvelo Ads with the AdMob fallback through one config entry and one
`initialize` call, with no native edits and no helper code.

1. **Expo:** one `plugins` entry in `app.json` plus `QartveloAds.initialize(...)`. Builds on Expo
   SDK 55 (React Native 0.83, Kotlin 2.1) for Android and iOS.
2. **Bare React Native:** one top-level key in `app.json`, `pod install`, plus `initialize`.
3. **Truth or Dare** migrates to 0.5.0, deletes its config plugin, its Kotlin workaround, its
   Google test ID list and most of `src/utils/qartveloAds.ts`, with the same ad behavior.
4. A wrong bundle ID, a key for the other platform, or a placement missing from the dashboard is
   shown in development as a warning that says what to fix.
5. Apps on 0.4.x build and behave the same after upgrading (all changes are additive).

## Out of scope

- A consent form (Google UMP or another CMP).
- A dashboard feature to copy placements from another app.
- Changes to ad serving, fallback decisions or banner refresh.
- Special support for `app.config.ts` beyond the config plugin (the plugin already works there).

## Section 1: one config, native setup

### Config object

The same object is used by the Expo plugin and by bare React Native builds:

```json
{
  "admob": {
    "androidAppId": "ca-app-pub-0000000000000000~0000000000",
    "iosAppId": "ca-app-pub-0000000000000000~0000000000",
    "delayAppMeasurementInit": true,
    "skAdNetworkItems": ["example123.skadnetwork"]
  }
}
```

- `admob` present: the AdMob adapter is linked on both platforms. Absent: core SDK only.
- `androidAppId` and `iosAppId` are required when `admob` is present. A value that does not match
  `ca-app-pub-<16 digits>~<10 digits>` fails the build or prebuild with a message naming the key.
  Only the platform being built is validated, so an Android-only app can omit `iosAppId`.
- `delayAppMeasurementInit` is optional, default `false`. It writes
  `com.google.android.gms.ads.DELAY_APP_MEASUREMENT_INIT` and `GADDelayAppMeasurementInit`.
- `skAdNetworkItems` is optional. The package ships Google's recommended SKAdNetwork list
  (`plugin/skadnetwork.json`), and these identifiers are appended to it without duplicates.

### Expo

`["@qartvelo/react-native-ads", { "admob": { ... } }]` in `plugins`. A new `app.plugin.js`
(compiled from `plugin/src`) applies, at prebuild:

- `QartveloAds_admobEnabled=true` in `gradle.properties`;
- the `com.google.android.gms.ads.APPLICATION_ID` and optional measurement-delay meta-data in the
  main application of `AndroidManifest.xml`;
- `ENV['QARTVELO_ADS_ADMOB_ENABLED'] = 'true'` in the Podfile, as a generated block after the
  `podfile_properties` line (before `use_native_modules!`);
- `GADApplicationIdentifier`, `GADDelayAppMeasurementInit` and `SKAdNetworkItems` in `Info.plist`.

Running the plugin twice produces the same files.

### Bare React Native

The same object under a top-level `"@qartvelo/react-native-ads"` key in the app's `app.json`.

- **Android:** `android/build.gradle` of the RN module reads `app.json` from the project root (the
  parent of the host's `android` directory). When `admob` is present it adds `com.qartvelo.ads:admob` and switches the module
  to an alternate manifest (`android/src/admob/AndroidManifest.xml`) that declares the App ID
  meta-data with a `${qartveloAdmobAppId}` placeholder filled from `manifestPlaceholders`.
- **iOS:** `RNQartveloAds.podspec` reads `app.json` from the project root (the parent of the
  host's `ios` directory). When
  `admob` is present it adds the adapter sources and `Google-Mobile-Ads-SDK`, and a CocoaPods
  script phase writes `GADApplicationIdentifier`, `GADDelayAppMeasurementInit` and the
  SKAdNetwork list into the built app's `Info.plist` on every build. This is the mechanism
  `react-native-google-mobile-ads` uses ("[CP-User] [RNGoogleMobileAds] Configuration"); the plan
  confirms its exact wiring against that implementation before writing ours.
- Android and iOS readers share the validation rules above. In an Expo project (an `app.json`
  with an `expo` object) the top-level key is an error that points to the plugin entry, so the
  two paths never both write the App ID.
- If the host app's own manifest or `Info.plist` already declares a different AdMob App ID, the
  build fails with a message (manifest merge conflict on Android, explicit check on iOS).

### Backward compatibility

`QartveloAds_admobEnabled=true` and the Podfile `ENV` line keep enabling the adapter, with the App
ID set by the app as in 0.4.x. They are documented as the legacy setup.

### Packaging fixes

- The RN module's Android repositories become `google()` and `mavenCentral()` only. JitPack and
  GitHub Packages are added only when `QartveloAds_sdkVersion` is older than 0.3.4 (the first
  release on Maven Central). `mavenLocal()` is added only with `QartveloAds_useMavenLocal=true`.
- `com.qartvelo.ads:admob` declares `play-services-ads` with `implementation`, so the published
  POM uses `runtime` scope. Modules that depend on the adapter, such as the RN bridge, no longer
  compile against Google's Kotlin 2.3 metadata. The bridge never references Google's API.

## Section 2: JavaScript API

All additions are optional. Existing calls keep their behavior.

### Per-platform values

```ts
QartveloAds.initialize({
  appKey: { android: 'app_xxxxxxxxxxxxxxxxxxxxxxxx', ios: 'app_yyyyyyyyyyyyyyyyyyyyyyyy' },
  admobAdUnits: {
    home_banner: { android: 'ca-app-pub-0000000000000000/1111111111', ios: 'ca-app-pub-0000000000000000/2222222222' },
    game_end: 'ca-app-pub-0000000000000000/3333333333',
  },
  preload: { interstitial: ['game_end'], rewarded: ['reward_coins'] },
});
```

- `appKey: string | { android?: string; ios?: string }`.
- `admobAdUnits: Record<string, string | { android?: string; ios?: string }>`. A placement whose
  object has no value for the current platform is left out (the dashboard unit applies).
- `wire.ts` resolves both for `Platform.OS` before calling native code. When `appKey` has no value
  for the current platform, `initialize` rejects with `invalid_argument` and the message
  `appKey has no value for <platform>`. Loads then reject with `not_initialized` and banners stay
  collapsed, as today without `initialize`.

### Safe AdMob units in debug builds

New option `admobTestUnitsInDebugBuilds?: boolean`, default `true`.

- **Android:** `QartveloAdsOptions.admobTestUnitsInDebugBuilds` in the core SDK is passed to the
  adapter settings. The adapter resolves units with Google's test units when test mode is on, or
  when this option is on and the app is debuggable (`ApplicationInfo.FLAG_DEBUGGABLE`, the check
  `Engine` already uses for `testModeInDebugBuilds`). Units configured in the dashboard are
  covered too. Qartvelo's own ads are not affected, so a debug build with
  `testModeInDebugBuilds: false` still shows live Qartvelo campaigns while AdMob stays on test
  units.
- **iOS:** accepted and ignored, because every install outside the App Store already uses test
  units.

### Helpers

- `preload?: { interstitial?: string[]; rewarded?: string[] }`. The JS layer issues the loads right
  after `initialize` is called (loads wait for initialization) and reloads a listed placement
  after each `showInterstitial` or `showRewarded` of it settles. Load errors are ignored here;
  setup problems surface through `setupIssue`.
- `showInterstitial(placementId, { loadIfNeeded?: boolean })` and
  `showRewarded(placementId, { loadIfNeeded?: boolean })`, default `false`. With `true`, when no ad
  is ready the call awaits a load first. A failed load resolves `{ shown: false }` (and
  `rewarded: false` for reward videos) instead of rejecting.
- The docs state the existing behavior that `showInterstitial` without the option resolves
  `{ shown: false }` immediately when nothing is ready, so apps need no `isInterstitialReady`
  check before it.

### Setup checks

A new event `setupIssue` with payload
`{ code: 'package_mismatch' | 'platform_mismatch' | 'unknown_placement' | 'format_mismatch'; message: string; placementId?: string }`.

- Both native SDKs emit it (and log it at warning level) when:
  - initialization fails with `package_mismatch`: the message names the registered package or
    bundle ID and the running one;
  - initialization fails with `platform_mismatch`: the message names the key's platform;
  - a load, show or banner uses a placement code that is not in the placement list from
    initialization (`unknown_placement`), or uses it with another format (`format_mismatch`).
    The message names the platform, the code and the format to create in the dashboard.
- Each issue is emitted once per process per code and placement.
- In development (`__DEV__`) the JS layer prints each event once with `console.warn`, so it shows
  in LogBox. Release builds only emit the event; apps may listen to it.

### Backend change

`SdkInitializer` (`backend/app/Services/AdServing/SdkInitializer.php`) adds `details` to two
errors:

- `package_mismatch`: `{ "registered_package": "<package or bundle ID>", "platform": "android|ios" }`;
- `platform_mismatch`: `{ "platform": "android|ios" }` (the key's platform).

App keys and store packages are public, so this exposes nothing secret. Older SDKs ignore the
field.

## Section 3: testing, docs, rollout, migration

### Testing

- **JS (Jest):** option resolution for strings and per-platform objects, including the missing
  platform error; `preload` loading after `initialize` and reloading after show; `loadIfNeeded`
  success and failure; `setupIssue` printed once and only in `__DEV__`.
- **Config plugin (Jest with `@expo/config-plugins`):** apply the plugin to fixture
  `AndroidManifest.xml`, `Info.plist`, `gradle.properties` and Podfile contents and assert the
  output; applying twice changes nothing; an App ID without `~` throws a message naming the key;
  the bundled SKAdNetwork list merges with `skAdNetworkItems` without duplicates.
- **Android (JUnit and Robolectric):** `admobTestUnitsInDebugBuilds` with and without the
  debuggable flag; `setupIssue` emission for each code; the existing banner size test stays.
- **iOS (XCTest):** `setupIssue` emission for each code.
- **Backend (PHPUnit):** `SdkInitializeTest` asserts the new `details` for both errors.
- **CI build matrix (`.github/workflows/ci.yml`):**
  1. the bare React Native 0.87 example, Android and iOS, configured through the `app.json` key;
  2. a bare React Native 0.79 (Kotlin 2.1) Android build with the AdMob adapter;
  3. a new minimal Expo SDK 55 example, Android and iOS, using the plugin, with a check that the
     App IDs are in the generated `AndroidManifest.xml` and `Info.plist`.
- **Manual:** Truth or Dare on an Android emulator and the iOS Simulator: banner sizes, the AdMob
  fallback with `testForceNoFill`, and the setup warnings with a deliberately wrong key.

### Docs

- React Native installation page: an Expo section (one plugin entry) and a bare React Native
  section (one `app.json` key), plus a short legacy setup section for the 0.4.x flags.
- API reference and usage pages: per-platform values, `admobTestUnitsInDebugBuilds`, `preload`,
  `loadIfNeeded`, `setupIssue`.
- Troubleshooting: a table of setup issues and their fixes.
- Changelog entry for 0.5.0, and the AI and MCP integration guidance in the repo.

### Release order

The maintainer publishes each step.

1. Backend: the `details` on `package_mismatch` and `platform_mismatch`.
2. Android SDK and AdMob adapter 0.5.0 on Maven Central (includes the banner size fix and the
   runtime-scoped `play-services-ads`); iOS sources ship inside the npm package.
3. npm `@qartvelo/react-native-ads` 0.5.0.

### Truth or Dare migration (after 0.5.0 is published)

- `app.json`: replace `./plugins/withQartveloAdsAdMob` with `"@qartvelo/react-native-ads"` and the
  same options. Delete `plugins/withQartveloAdsAdMob.js`, which also removes the Kotlin metadata
  workaround.
- `src/config/ads.ts`: per-platform `appKey` and `admobAdUnits` objects; delete the Google test
  unit ID list.
- `src/utils/qartveloAds.ts`: reduce to `initialize` with `preload`, a plain
  `showInterstitial(code)`, and `showRewarded(code, { loadIfNeeded: true })`.
- Bump `expo.version` (the runtime version) so OTA updates never reach binaries without the new
  native code, then `npx expo prebuild --clean` and rebuild both platforms.
