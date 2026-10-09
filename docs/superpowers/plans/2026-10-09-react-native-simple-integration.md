# Simple React Native and Expo Integration (SDK 0.5.0) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A React Native or Expo app adds Qartvelo Ads with the AdMob fallback through one config entry and one `initialize` call, with no native edits and no helper code.

**Architecture:** The AdMob setup moves into the package: an Expo config plugin writes it at prebuild, and bare React Native builds read the same object from `app.json` (Gradle for Android, the podspec plus an Xcode build phase for iOS). The native SDKs gain debug-safe AdMob units and setup-issue reporting, the JavaScript layer gains per-platform options, `preload`, `loadIfNeeded` and development warnings, and the backend adds `details` to two errors.

**Tech Stack:** Kotlin (Android SDK, Robolectric, MockWebServer), Swift (iOS SDK, XCTest), TypeScript (React Native TurboModule, Jest), Expo config plugins (`expo/config-plugins`, Expo SDK 55), Groovy (Gradle), Ruby (CocoaPods, Xcode build phase, minitest), PHP (Laravel, PHPUnit), GitHub Actions.

**Spec:** `docs/superpowers/specs/2026-10-09-react-native-simple-integration-design.md` (in the `myAds/sdk` repository). Read it before starting.

## Global Constraints

- Repositories: `/Users/kakha13/Developer/myAds` (backend and `docs/`) and `/Users/kakha13/Developer/myAds/sdk` (everything else) are separate git repositories. Work on branch `claude/rn-simple-integration` in both (it exists in `sdk`; create it in `myAds` before Task 3).
- Version: every SDK (Android, iOS, AdMob adapters, React Native package) ships as `0.5.0`.
- AdMob App ID format: `^ca-app-pub-\d{16}~\d{10}$`. A mismatch fails the build or prebuild with a message that names the key (`admob.androidAppId` or `admob.iosAppId`).
- Config key for bare React Native: top-level `"@qartvelo/react-native-ads"` in the app's `app.json`. In an `app.json` that has an `expo` object, that key is an error that points to the plugin entry.
- Setup issue codes: `package_mismatch`, `platform_mismatch`, `unknown_placement`, `format_mismatch`. Each is reported once per process per code and placement.
- Expo plugin: import from `expo/config-plugins`; `expo` is a devDependency (`~55.0.19`) and an optional peerDependency (`>=52.0.0`).
- Ruby scripts run on macOS system Ruby 2.6: no endless methods, no pattern matching.
- Never edit `react-native/packages/react-native-qartvelo-ads/ios/sdk/**` by hand; regenerate it with `node scripts/sync-ios-sdk.cjs` (it is not tracked in git).
- Writing rule for code comments, docs and commit messages: never use the em dash or the en dash; use a plain hyphen.
- Every commit message ends with:
  ```
  Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
  ```
- Do not publish anything (Maven Central, npm, CocoaPods, backend deploy). The maintainer releases.
- Backward compatibility: `QartveloAds_admobEnabled=true`, the Podfile `ENV['QARTVELO_ADS_ADMOB_ENABLED']`, a string `appKey` and string `admobAdUnits` values keep working unchanged.

## Notes on the spec

Exploration refined these points; they are binding for this plan:

1. The iOS build phase is declared in `react-native.config.js` under `dependency.platforms.ios.scriptPhases`, the mechanism `react-native-google-mobile-ads` uses. Autolinking adds it to the app target (Expo autolinking honors it too).
2. `setupIssue` travels through the existing `onAdEvent` codegen emitter as `{ type: 'setupIssue', placementId, error: { code, message } }`; an empty `placementId` means none. No codegen spec change.
3. `admobTestUnitsInDebugBuilds` is computed in the Android core `Engine` and passed through the existing `FallbackSettings.testMode`, which the AdMob adapter already uses only to pick Google's test units. No adapter API change.
4. Kotlin 2.1 coverage comes from the new Expo SDK 55 example (React Native 0.83, Kotlin 2.1.20) plus a CI check that the published adapter POM declares `play-services-ads` with `runtime` scope, instead of a separate React Native 0.79 app.
5. Unknown and mismatched placements are only judged against placement lists fetched from the backend in this process (never the disk cache). Codes used before that list arrives are checked when it arrives.
6. Until 0.5.0 is on Maven Central, CI resolves `com.qartvelo.ads` from Maven Local through a Gradle init script.

## Review Focus

The five inputs most likely to bite users that the spec's own tests do not pin; each has a test in the named task:

1. A bare app upgrading from 0.4.x that keeps the same AdMob App ID in its own `AndroidManifest.xml` or `Info.plist` and adds the `app.json` key must still build (identical IDs are fine; only different IDs fail). Tests: Task 13 (`same existing App ID passes`), Task 15 (`test_same_existing_app_id_passes_and_different_one_fails`), Task 14 Step 6.
2. An Expo app (`app.json` with an `expo` object, plugin entry, no top-level key) must be untouched by the bare readers on both platforms. Tests: Task 15 (`test_expo_projects_without_the_key_are_left_alone`), Task 16 Expo example CI build.
3. A setup issue reported during initialization, before the app registers any listener, must still print the development warning. Test: Task 11 (`warns in development without any app listener`).
4. `showRewarded(code, { loadIfNeeded: true })` whose load fails must resolve `{ shown: false, rewarded: false }`, never reject, and still reload a preloaded placement. Test: Task 12 (`resolves shown false when the load fails` and `reloads even when the show fails`).
5. On the iOS Simulator and TestFlight, test mode reports a rejected initialization as success; the setup issue must still be reported. Test: Task 8 (`testPackageMismatchIsReportedEvenWhenTestModeHidesTheInitFailure`).

---

### Task 1: Commit the Android AdMob banner size fix

The fix is already in the working tree of `myAds/sdk` (uncommitted): `AdMobBannerSize` in `AdMobSupport.kt`, its use in `AdMobFallbackAdapter.kt`, the test `bannerUsesTheStandardAnchoredAdaptiveSize` and an "Unreleased" changelog entry.

**Files:**
- Already modified: `android/qartvelo-ads-admob/src/main/java/com/qartvelo/admob/AdMobSupport.kt`, `android/qartvelo-ads-admob/src/main/java/com/qartvelo/admob/AdMobFallbackAdapter.kt`, `android/qartvelo-ads-admob/src/test/java/com/qartvelo/admob/AdMobFallbackAdapterTest.kt`, `developers/src/content/docs/resources/changelog.md`

**Interfaces:**
- Produces: `internal object AdMobBannerSize { fun forWidth(context: Context, widthDp: Int): AdSize }`.

- [ ] **Step 1: Run the adapter tests**

Run (from `myAds/sdk/android`): `./gradlew :qartvelo-ads-admob:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, 6 tests, 0 failures.

- [ ] **Step 2: Commit**

```bash
cd /Users/kakha13/Developer/myAds/sdk
git add android/qartvelo-ads-admob developers/src/content/docs/resources/changelog.md
git commit -F - <<'EOF'
Request the standard anchored AdMob banner on Android

The adapter asked Google for the large anchored adaptive banner, about twice
as tall on phones (411x128 instead of 411x64 at 411 dp), unlike iOS and the
docs. The size choice now lives in AdMobBannerSize, with a test.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
```

---

### Task 2: Version 0.5.0

**Files:**
- Modify: `android/build.gradle.kts:13`
- Modify: `ios/Sources/QartveloAds/QartveloAds.swift:6`
- Modify: `QartveloAds.podspec:3`, `QartveloAdsAdMob.podspec:3`
- Modify: `react-native/packages/react-native-qartvelo-ads/package.json:3` (and its `package-lock.json`)
- Modify: `react-native/packages/react-native-qartvelo-ads/android/build.gradle:6,23`
- Modify: `developers/mcp/package.json:3` (and its `package-lock.json` if present), `developers/mcp/src/snippets.js:4`
- Modify: `developers/public/openapi.yaml:4,46`, `developers/astro.config.mjs:145`

**Interfaces:**
- Produces: `QartveloAds.SDK_VERSION` (Android, from Gradle) and `QartveloAds.sdkVersion` (iOS) equal `"0.5.0"`; RN Android default `QartveloAds_sdkVersion` is `0.5.0`.

- [ ] **Step 1: Replace the version everywhere it is defined**

Run from `myAds/sdk`:

```bash
sed -i '' 's/version = "0.4.1"/version = "0.5.0"/' android/build.gradle.kts
sed -i '' 's/sdkVersion = "0.4.1"/sdkVersion = "0.5.0"/' ios/Sources/QartveloAds/QartveloAds.swift
sed -i '' "s/s.version          = '0.4.1'/s.version          = '0.5.0'/" QartveloAds.podspec QartveloAdsAdMob.podspec
sed -i '' 's/QartveloAds_sdkVersion=0.4.1 /QartveloAds_sdkVersion=0.5.0 /; s/"QartveloAds_sdkVersion", "0.4.1"/"QartveloAds_sdkVersion", "0.5.0"/' react-native/packages/react-native-qartvelo-ads/android/build.gradle
sed -i '' "s/SDK_VERSION = '0.4.1'/SDK_VERSION = '0.5.0'/" developers/mcp/src/snippets.js
sed -i '' 's/version: 0.4.1/version: 0.5.0/; s/sdk_version: 0.4.1/sdk_version: 0.5.0/' developers/public/openapi.yaml
sed -i '' 's/Current version: 0.4.1/Current version: 0.5.0/; s/core:0.4.1/core:0.5.0/; s/admob:0.4.1/admob:0.5.0/' developers/astro.config.mjs
(cd react-native/packages/react-native-qartvelo-ads && npm version 0.5.0 --no-git-tag-version)
(cd developers/mcp && npm version 0.5.0 --no-git-tag-version)
```

- [ ] **Step 2: Verify nothing defines 0.4.1 any more**

Run: `grep -rn --exclude-dir={node_modules,build,.gradle,dist,.git,qa-out-spm-release,Pods,lib,sdk} -E "0\.4\.1" . | grep -v -E "changelog|package-lock|\.md:|\.mdx:|/docs/"`
Expected: no output.

- [ ] **Step 3: Regenerate the React Native iOS copy and compile**

Run:
```bash
(cd react-native/packages/react-native-qartvelo-ads && node scripts/sync-ios-sdk.cjs)
(cd android && ./gradlew :qartvelo-ads-core:compileDebugKotlin :qartvelo-ads-admob:compileDebugKotlin)
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add -A android/build.gradle.kts ios/Sources/QartveloAds/QartveloAds.swift QartveloAds.podspec QartveloAdsAdMob.podspec react-native/packages/react-native-qartvelo-ads/package.json react-native/packages/react-native-qartvelo-ads/package-lock.json react-native/packages/react-native-qartvelo-ads/android/build.gradle developers/mcp developers/public/openapi.yaml developers/astro.config.mjs
git commit -F - <<'EOF'
Set the SDK version to 0.5.0

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
```

---

### Task 3: Backend error details for package and platform mismatches

Repository: `/Users/kakha13/Developer/myAds`. First run `git switch -c claude/rn-simple-integration` there.

**Files:**
- Modify: `backend/app/Exceptions/ApiException.php` (constructor and `render()`)
- Modify: `backend/app/Services/AdServing/SdkInitializer.php:51,55`
- Test: `backend/tests/Feature/Api/SdkInitializeTest.php`, `backend/tests/Feature/Api/IosPlatformTest.php`
- Modify: `docs/CONTRACT.md` (section 5, lines 138-141), `docs/api.md` (error table, lines 30-43)

**Interfaces:**
- Produces: `package_mismatch` responses carry `error.details = { "registered_package": string, "platform": "android"|"ios" }`; `platform_mismatch` responses carry `error.details = { "platform": "android"|"ios" }` (the key's platform). Tasks 6 and 8 parse these.

- [ ] **Step 1: Write the failing tests**

Add to `SdkInitializeTest` (after `test_returns_403_and_logs_suspicious_event_when_package_does_not_match`):

```php
    public function test_package_mismatch_names_the_registered_package_and_platform(): void
    {
        $app = $this->approvedApp();

        $this->postJson('/api/v1/sdk/initialize', ['app_key' => $app->sdk_app_key, 'package_name' => 'com.attacker.clone'])
            ->assertForbidden()
            ->assertJsonPath('error.code', 'package_mismatch')
            ->assertJsonPath('error.details.registered_package', $app->package_name)
            ->assertJsonPath('error.details.platform', 'android');
    }
```

Add to `IosPlatformTest`:

```php
    public function test_platform_mismatch_names_the_platform_of_the_key(): void
    {
        $ios = PublisherApp::factory()->ios()->approved()->create();

        $this->postJson('/api/v1/sdk/initialize', ['app_key' => $ios->sdk_app_key, 'package_name' => $ios->package_name, 'platform' => 'android'])
            ->assertForbidden()
            ->assertJsonPath('error.code', 'platform_mismatch')
            ->assertJsonPath('error.details.platform', 'ios');
    }
```

- [ ] **Step 2: Run them to see them fail**

Run (from `myAds/backend`): `php artisan test --compact --filter='test_package_mismatch_names_the_registered_package_and_platform|test_platform_mismatch_names_the_platform_of_the_key'`
Expected: 2 failures on `error.details...` (path missing).

- [ ] **Step 3: Implement**

In `ApiException.php`, replace the constructor and `render()` with:

```php
    /**
     * @param  array<string, string>  $headers
     * @param  array<string, mixed>  $details  Sent as "error.details" when not empty.
     */
    public function __construct(
        public readonly string $errorCode,
        string $message,
        public readonly int $status = 400,
        public readonly array $headers = [],
        public readonly array $details = [],
    ) {
        parent::__construct($message);
    }

    public function render(): JsonResponse
    {
        return self::envelope(
            $this->errorCode,
            $this->getMessage(),
            $this->status,
            $this->headers,
            $this->details === [] ? [] : ['details' => $this->details],
        );
    }
```

In `SdkInitializer.php`, replace the two throws (lines 51 and 55) with:

```php
            throw new ApiException('package_mismatch', 'The package name does not match the app registered for this key.', 403, details: [
                'registered_package' => $app->package_name,
                'platform' => $app->platform->value,
            ]);
```

```php
            throw new ApiException('platform_mismatch', "This app key belongs to the {$app->platform->label()} app. Register the app for each platform and use its own key.", 403, details: [
                'platform' => $app->platform->value,
            ]);
```

- [ ] **Step 4: Run the two test files and format**

Run:
```bash
php artisan test --compact tests/Feature/Api/SdkInitializeTest.php tests/Feature/Api/IosPlatformTest.php
vendor/bin/pint --format agent app/Exceptions/ApiException.php app/Services/AdServing/SdkInitializer.php tests/Feature/Api/SdkInitializeTest.php tests/Feature/Api/IosPlatformTest.php
```
Expected: all tests pass (the exact-body `invalid_app_key` test still passes because empty details add nothing).

- [ ] **Step 5: Document the field**

In `docs/api.md`, in the error-codes table (lines 30-43), make the `package_mismatch` row read "The package name or bundle ID does not match the app registered for this key. `details.registered_package` and `details.platform` name the registered app." and add a row for `platform_mismatch` (HTTP 403): "The app key belongs to the app of another platform. `details.platform` names the key's platform (`android` or `ios`)." In `docs/CONTRACT.md` section 5 (lines 138-141), add after the error envelope description: "Errors may carry an optional `details` object with machine-readable context; clients must ignore unknown members. `package_mismatch` sends `registered_package` and `platform`; `platform_mismatch` sends `platform`." and list `platform_mismatch` next to `package_mismatch`.

- [ ] **Step 6: Commit (in `myAds`)**

```bash
cd /Users/kakha13/Developer/myAds
git add backend/app/Exceptions/ApiException.php backend/app/Services/AdServing/SdkInitializer.php backend/tests/Feature/Api/SdkInitializeTest.php backend/tests/Feature/Api/IosPlatformTest.php docs/CONTRACT.md docs/api.md
git commit -F - <<'EOF'
Name the registered app in package and platform mismatch errors

Both SDK initialize errors now carry error.details so the SDKs can tell the
developer which package or platform the key belongs to.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
```

---

### Task 4: AdMob adapter brings play-services-ads at runtime scope

**Files:**
- Create: `android/scripts/check-admob-pom.py`
- Modify: `android/qartvelo-ads-admob/build.gradle.kts` (dependencies block, lines 49-57)
- Modify: `.github/workflows/ci.yml` (job `android`)

**Interfaces:**
- Produces: the published `com.qartvelo.ads:admob` POM declares `play-services-ads` with `<scope>runtime</scope>`.

- [ ] **Step 1: Write the check**

Create `android/scripts/check-admob-pom.py`:

```python
"""Fails unless the admob POM declares play-services-ads with runtime scope.

Compile scope puts Google's classes (Kotlin 2.3 metadata in play-services-ads 25.4) on the compile
classpath of every module that depends on the adapter, which the Kotlin 2.1 compiler of React
Native 0.83 rejects. Usage: python3 scripts/check-admob-pom.py <path to admob-*.pom>
"""
import sys
import xml.etree.ElementTree as ET

NS = {"m": "http://maven.apache.org/POM/4.0.0"}

root = ET.parse(sys.argv[1]).getroot()
for dependency in root.findall("m:dependencies/m:dependency", NS):
    if dependency.findtext("m:artifactId", namespaces=NS) == "play-services-ads":
        scope = dependency.findtext("m:scope", namespaces=NS)
        if scope != "runtime":
            sys.exit(f"play-services-ads has scope {scope!r}, expected 'runtime'")
        print("play-services-ads: runtime scope")
        break
else:
    sys.exit("play-services-ads is missing from the admob POM")
```

- [ ] **Step 2: Run it against the current POM to see it fail**

Run (from `myAds/sdk/android`):
```bash
./gradlew :qartvelo-ads-core:publishAllPublicationsToStagingRepository :qartvelo-ads-admob:publishAllPublicationsToStagingRepository
python3 scripts/check-admob-pom.py build/staging-deploy/com/qartvelo/ads/admob/0.5.0/admob-0.5.0.pom
```
Expected: exit 1 with `play-services-ads has scope 'compile', expected 'runtime'`.

- [ ] **Step 3: Change the dependency**

In `android/qartvelo-ads-admob/build.gradle.kts`, replace:

```kotlin
    // Google's official artifact, resolved normally (never shaded) so host apps can align versions.
    api(libs.play.services.ads)
```

with:

```kotlin
    // Google's official artifact, resolved normally (never shaded) so host apps can align versions.
    // Runtime scope for consumers: nothing outside this module compiles against Google's API, and
    // its Kotlin 2.3 metadata breaks hosts on older Kotlin compilers (React Native 0.83 uses 2.1).
    implementation(libs.play.services.ads)
```

- [ ] **Step 4: Re-run the check and the tests**

Run:
```bash
rm -rf build/staging-deploy
./gradlew :qartvelo-ads-core:publishAllPublicationsToStagingRepository :qartvelo-ads-admob:publishAllPublicationsToStagingRepository :qartvelo-ads-admob:testDebugUnitTest :sample-app:assembleDebug
python3 scripts/check-admob-pom.py build/staging-deploy/com/qartvelo/ads/admob/0.5.0/admob-0.5.0.pom
```
Expected: BUILD SUCCESSFUL; prints `play-services-ads: runtime scope`.

- [ ] **Step 5: Run the check in CI**

In `.github/workflows/ci.yml`, job `android`, append to the `Maven Central bundle (unsigned)` step's `run` block (after the `for a in core admob` loop):

```bash
          python3 scripts/check-admob-pom.py build/staging-deploy/com/qartvelo/ads/admob/*/admob-*.pom
```

- [ ] **Step 6: Commit**

```bash
git add android/scripts/check-admob-pom.py android/qartvelo-ads-admob/build.gradle.kts .github/workflows/ci.yml
git commit -F - <<'EOF'
Keep play-services-ads off consumers' compile classpath

The admob POM declared play-services-ads 25.4.0 at compile scope, so the
React Native bridge compiled against its Kotlin 2.3 metadata, which the
Kotlin 2.1 compiler of React Native 0.83 rejects. CI now checks the scope.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
```

---

### Task 5: Android `admobTestUnitsInDebugBuilds`

**Files:**
- Modify: `android/qartvelo-ads-core/src/main/java/com/qartvelo/sdk/QartveloAdsOptions.kt` (append a parameter)
- Modify: `android/qartvelo-ads-core/src/main/java/com/qartvelo/sdk/internal/Engine.kt` (lines 111-133 and 248)
- Modify: `android/qartvelo-ads-core/src/test/java/com/qartvelo/sdk/SdkTest.kt` (`options()` helper, lines 51-67)
- Test: `android/qartvelo-ads-core/src/test/java/com/qartvelo/sdk/FallbackTest.kt`

**Interfaces:**
- Produces: `QartveloAdsOptions.admobTestUnitsInDebugBuilds: Boolean = true` (last constructor parameter). `FallbackSettings.testMode` is true when Qartvelo test mode is on, or when this option is on and the app is debuggable. Task 10 reads the option from the React Native bridge.

- [ ] **Step 1: Add the test helper parameter and the failing test**

In `SdkTest.options(...)`, add the parameter `admobTestUnitsInDebugBuilds: Boolean = false,` after `testModeInDebugBuilds: Boolean = false,` and pass `admobTestUnitsInDebugBuilds = admobTestUnitsInDebugBuilds,` to `QartveloAdsOptions(...)` (the default `false` keeps every existing test unaffected).

Add to `FallbackTest` (import `android.content.pm.ApplicationInfo` if missing):

```kotlin
    @Test
    fun debuggableBuildsGiveTheAdapterTestUnitsUnlessOptedOut() {
        val info = app.applicationInfo
        val original = info.flags
        try {
            info.flags = original or ApplicationInfo.FLAG_DEBUGGABLE
            val debug = FakeAdapter()
            assertTrue(init(options(admobTestUnitsInDebugBuilds = true), debug))
            assertFalse("Qartvelo test mode stays off", backend.bodies("/api/v1/sdk/initialize").last().getBoolean("test_mode"))
            assertEquals(true, debug.settings?.testMode)

            QartveloAds.resetForTests()
            val optedOut = FakeAdapter()
            assertTrue(init(options(admobTestUnitsInDebugBuilds = false), optedOut))
            assertEquals(false, optedOut.settings?.testMode)

            QartveloAds.resetForTests()
            info.flags = original and ApplicationInfo.FLAG_DEBUGGABLE.inv()
            val release = FakeAdapter()
            assertTrue(init(options(admobTestUnitsInDebugBuilds = true), release))
            assertEquals(false, release.settings?.testMode)
        } finally {
            info.flags = original
        }
    }
```

- [ ] **Step 2: Run it to see it fail**

Run (from `myAds/sdk/android`): `./gradlew :qartvelo-ads-core:testDebugUnitTest --tests com.qartvelo.sdk.FallbackTest`
Expected: compilation error, `No parameter with name 'admobTestUnitsInDebugBuilds'`.

- [ ] **Step 3: Implement**

Append to `QartveloAdsOptions` (after `testModeInDebugBuilds`):

```kotlin
    /**
     * Use the fallback network's public test units (Google's for AdMob) in debuggable builds, even
     * when test mode is off, so development never requests live AdMob ads. Release builds are not
     * debuggable, so they are unaffected. Set to false to request your real AdMob units from a debug
     * build.
     */
    val admobTestUnitsInDebugBuilds: Boolean = true,
```

In `Engine.kt`, insert this line right before the existing KDoc of `testMode` (line 113):

```kotlin
    private val debuggable: Boolean = (appContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
```

replace the `testMode` initializer itself (lines 121-122, keep the KDoc above it) with:

```kotlin
    val testMode: Boolean = options.testMode || emulator || (options.testModeInDebugBuilds && debuggable)
```

and insert right after it:

```kotlin

    /**
     * The fallback network uses its public test units in test mode and, unless
     * [QartveloAdsOptions.admobTestUnitsInDebugBuilds] is false, in every debuggable build: a
     * developer never requests live AdMob ads, even while watching live Qartvelo Ads campaigns.
     */
    private val fallbackTestUnits: Boolean = testMode || (options.admobTestUnitsInDebugBuilds && debuggable)
```

In `start(...)`, after the existing `if (emulator) { ... } else if (testMode && !options.testMode) { ... }` block, add:

```kotlin
        if (fallbackTestUnits && !testMode) {
            OurLog.i("Debuggable build: the AdMob fallback uses Google's test units (admobTestUnitsInDebugBuilds)")
        }
```

Replace `fallbackSettings()` (line 248) with:

```kotlin
    private fun fallbackSettings() = FallbackSettings(testMode = fallbackTestUnits, privacy = QartveloAds.currentPrivacy())
```

- [ ] **Step 4: Run the core and adapter tests**

Run: `./gradlew :qartvelo-ads-core:testDebugUnitTest :qartvelo-ads-admob:testDebugUnitTest`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add android/qartvelo-ads-core
git commit -F - <<'EOF'
Use Google's test units for the AdMob fallback in debug builds

New option admobTestUnitsInDebugBuilds (default true): debuggable builds
never request live AdMob ads, even when Qartvelo test mode is off.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
```

---

### Task 6: Android setup issues for rejected app keys

**Files:**
- Create: `android/qartvelo-ads-core/src/main/java/com/qartvelo/sdk/QartveloAdsSetupIssue.kt`
- Modify: `android/qartvelo-ads-core/src/main/java/com/qartvelo/sdk/QartveloAdsListener.kt` (interface body, lines 15-25)
- Modify: `android/qartvelo-ads-core/src/main/java/com/qartvelo/sdk/internal/Wire.kt:107-110` (`ApiException`)
- Modify: `android/qartvelo-ads-core/src/main/java/com/qartvelo/sdk/internal/ApiClient.kt:143-149` (`postJson` error branch)
- Modify: `android/qartvelo-ads-core/src/main/java/com/qartvelo/sdk/internal/SessionManager.kt:18-25,89-95`
- Modify: `android/qartvelo-ads-core/src/main/java/com/qartvelo/sdk/internal/Engine.kt:61-67` and near `onSessionInitialized` (214-220)
- Modify: `android/qartvelo-ads-core/src/test/java/com/qartvelo/sdk/TestSupport.kt` (`FakeBackend`: fields at 124-133, `error()` at 183-184, initialize branch at 197-199)
- Test: create `android/qartvelo-ads-core/src/test/java/com/qartvelo/sdk/SetupIssueTest.kt`

**Interfaces:**
- Produces: `public data class QartveloAdsSetupIssue(val code: String, val message: String, val placementId: String?)` with constants `PACKAGE_MISMATCH`, `PLATFORM_MISMATCH`, `UNKNOWN_PLACEMENT`, `FORMAT_MISMATCH`; `QartveloAdsListener.onSetupIssue(issue: QartveloAdsSetupIssue)` (default empty, global listeners only); `internal fun Engine.reportSetupIssue(code: String, message: String, placementId: String? = null)`. Tasks 7 and 11 use these.

- [ ] **Step 1: Extend the fake backend**

In `FakeBackend` (`TestSupport.kt`), add next to `var initStatus = 200`:

```kotlin
    var initErrorCode = "invalid_app_key"
    var initErrorDetails: JSONObject? = null
```

Replace `error(...)`:

```kotlin
    fun error(status: Int, code: String, details: JSONObject? = null): MockResponse {
        val error = JSONObject().put("code", code).put("message", code)
        if (details != null) error.put("details", details)
        return json(JSONObject().put("error", error), status)
    }
```

In `dispatch`, replace `error(initStatus, "invalid_app_key")` with `error(initStatus, initErrorCode, initErrorDetails)`.

- [ ] **Step 2: Write the failing tests**

Create `SetupIssueTest.kt`:

```kotlin
package com.qartvelo.sdk

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SetupIssueTest : SdkTest() {
    private val issues = mutableListOf<QartveloAdsSetupIssue>()

    @Before
    fun collectIssues() {
        QartveloAds.addEventListener(object : QartveloAdsListener {
            override fun onSetupIssue(issue: QartveloAdsSetupIssue) {
                issues += issue
            }
        })
    }

    private fun rejectInit(code: String, details: JSONObject?) {
        backend.initStatus = 403
        backend.initErrorCode = code
        backend.initErrorDetails = details
    }

    @Test
    fun packageMismatchNamesBothPackagesOnce() {
        rejectInit("package_mismatch", JSONObject().put("registered_package", "com.other.app").put("platform", "android"))
        init()
        awaitMain(message = "setup issue") { issues.isNotEmpty() }
        val issue = issues.single()
        assertEquals(QartveloAdsSetupIssue.PACKAGE_MISMATCH, issue.code)
        assertNull(issue.placementId)
        assertTrue(issue.message, issue.message.contains("com.other.app") && issue.message.contains(app.packageName))

        QartveloAds.loadInterstitial("game_end", listener)
        awaitMain { listener.has("loadFailed") }
        assertEquals("reported once per process", 1, issues.size)
    }

    @Test
    fun olderBackendsWithoutDetailsStillGetAMessage() {
        rejectInit("package_mismatch", null)
        init()
        awaitMain(message = "setup issue") { issues.isNotEmpty() }
        assertTrue(issues.single().message, issues.single().message.contains(app.packageName))
    }

    @Test
    fun platformMismatchNamesThePlatformOfTheKey() {
        rejectInit("platform_mismatch", JSONObject().put("platform", "ios"))
        init()
        awaitMain(message = "setup issue") { issues.isNotEmpty() }
        assertEquals(QartveloAdsSetupIssue.PLATFORM_MISMATCH, issues.single().code)
        assertTrue(issues.single().message, issues.single().message.contains("the iOS app"))
    }

    @Test
    fun otherInitErrorsAreNotSetupIssues() {
        backend.initStatus = 401
        init()
        QartveloAds.loadInterstitial("game_end", listener)
        awaitMain { listener.has("loadFailed") }
        assertTrue(issues.toString(), issues.isEmpty())
    }
}
```

- [ ] **Step 3: Run them to see them fail**

Run: `./gradlew :qartvelo-ads-core:testDebugUnitTest --tests com.qartvelo.sdk.SetupIssueTest`
Expected: compilation errors (`Unresolved reference: QartveloAdsSetupIssue`, `onSetupIssue`).

- [ ] **Step 4: Implement**

Create `QartveloAdsSetupIssue.kt`:

```kotlin
package com.qartvelo.sdk

/**
 * A setup problem the app developer has to fix, such as an app key registered for another package or
 * a placement code missing from the dashboard. Reported once per process through
 * [QartveloAdsListener.onSetupIssue] (global listeners) and logged as an error.
 */
public data class QartveloAdsSetupIssue(
    /** One of [PACKAGE_MISMATCH], [PLATFORM_MISMATCH], [UNKNOWN_PLACEMENT], [FORMAT_MISMATCH]. */
    val code: String,
    /** What is wrong and how to fix it, in English. */
    val message: String,
    /** The placement concerned, or null for app-level issues. */
    val placementId: String?,
) {
    public companion object {
        public const val PACKAGE_MISMATCH: String = "package_mismatch"
        public const val PLATFORM_MISMATCH: String = "platform_mismatch"
        public const val UNKNOWN_PLACEMENT: String = "unknown_placement"
        public const val FORMAT_MISMATCH: String = "format_mismatch"
    }
}
```

In `QartveloAdsListener`, add as the last method:

```kotlin
    /** A setup problem to fix (wrong key or package, missing placement). Delivered to global listeners. */
    public fun onSetupIssue(issue: QartveloAdsSetupIssue) {}
```

In `Wire.kt`, replace `ApiException` (add `import org.json.JSONObject` if the file lacks it):

```kotlin
/** Non-2xx API answer with the contract's error envelope. */
internal class ApiException(
    val httpStatus: Int,
    val code: String,
    message: String,
    /** The envelope's optional `error.details` object. */
    val details: JSONObject? = null,
) : IOException("$code ($httpStatus): $message") {
    val isSessionError: Boolean get() = code == "session_expired" || code == "invalid_session"
}
```

In `ApiClient.postJson`, add the argument `details = err?.optJSONObject("details"),` to the `ApiException(...)` call.

In `SessionManager`, add the constructor parameter after `onInitialized`:

```kotlin
    /** Called on the session executor with every failed initialize attempt. */
    private val onFailed: (Throwable) -> Unit = {},
```

and in `startOrJoin`'s `catch (t: Throwable)` block, after `OurLog.e("QartveloAds session request failed: ${t.message}")`, add `onFailed(t)`.

In `Engine.kt`, add `onFailed = ::onSessionFailed,` to the `SessionManager(...)` call (after `onInitialized = ::onSessionInitialized,`). Add the imports `com.qartvelo.sdk.QartveloAdsListener`, `com.qartvelo.sdk.QartveloAdsSetupIssue` and `java.util.concurrent.ConcurrentHashMap` if missing, and add after `onSessionInitialized`:

```kotlin
    private val reportedSetupIssues: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Logs a setup issue once per process and tells the global listeners. Any thread. */
    fun reportSetupIssue(code: String, message: String, placementId: String? = null) {
        if (!reportedSetupIssues.add("$code|${placementId.orEmpty()}")) return
        OurLog.e("Setup issue ($code): $message")
        val issue = QartveloAdsSetupIssue(code, message, placementId)
        Listeners.emit(emptyList<QartveloAdsListener?>(), "onSetupIssue") { it.onSetupIssue(issue) }
    }

    /** Session executor. Turns rejected app keys into setup issues. */
    private fun onSessionFailed(t: Throwable) {
        val api = t as? ApiException ?: return
        val running = appContext.packageName
        when (api.code) {
            QartveloAdsSetupIssue.PACKAGE_MISMATCH -> {
                val registered = api.details?.optString("registered_package").orEmpty()
                val message = if (registered.isNotEmpty()) {
                    "This app key is registered for '$registered', but this app is '$running'. Use the key of the app registered for '$running', or correct the package name in the Qartvelo Ads dashboard."
                } else {
                    "This app key is not registered for '$running'. Use the key of the app registered for '$running', or correct the package name in the Qartvelo Ads dashboard."
                }
                reportSetupIssue(QartveloAdsSetupIssue.PACKAGE_MISMATCH, message)
            }
            QartveloAdsSetupIssue.PLATFORM_MISMATCH -> {
                val owner = if (api.details?.optString("platform") == "ios") "the iOS app" else "an app of another platform"
                reportSetupIssue(
                    QartveloAdsSetupIssue.PLATFORM_MISMATCH,
                    "This app key belongs to $owner. Register this Android app in the Qartvelo Ads dashboard and use its own key.",
                )
            }
        }
    }
```

- [ ] **Step 5: Run all core tests**

Run: `./gradlew :qartvelo-ads-core:testDebugUnitTest`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add android/qartvelo-ads-core
git commit -F - <<'EOF'
Report rejected app keys as Android setup issues

package_mismatch and platform_mismatch now reach global listeners once per
process through onSetupIssue, naming the registered package or platform.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
```

---

### Task 7: Android setup issues for unknown or mismatched placements

**Files:**
- Modify: `android/qartvelo-ads-core/src/main/java/com/qartvelo/sdk/internal/Engine.kt` (near `placement()` at line 286, and `onSessionInitialized` at 214-220)
- Modify: `android/qartvelo-ads-core/src/main/java/com/qartvelo/sdk/internal/FullscreenController.kt:65`
- Modify: `android/qartvelo-ads-core/src/main/java/com/qartvelo/sdk/internal/BannerController.kt:144`
- Modify: `android/qartvelo-ads-core/src/main/java/com/qartvelo/sdk/QartveloAds.kt:168` (`show`)
- Test: `android/qartvelo-ads-core/src/test/java/com/qartvelo/sdk/SetupIssueTest.kt`

**Interfaces:**
- Consumes: `Engine.reportSetupIssue(...)` and `QartveloAdsSetupIssue` constants from Task 6.
- Produces: `fun Engine.checkPlacement(placementId: String, format: AdFormat)` (main thread).

- [ ] **Step 1: Write the failing tests**

Add to `SetupIssueTest` (import `android.app.Activity` and `org.robolectric.Robolectric`):

```kotlin
    @Test
    fun unknownPlacementIsReportedOncePerCode() {
        assertTrue(init())
        loadAndWait(AdFormat.INTERSTITIAL, "level_up")
        loadAndWait(AdFormat.INTERSTITIAL, "level_up")
        awaitMain(message = "setup issue") { issues.isNotEmpty() }
        val issue = issues.single()
        assertEquals(QartveloAdsSetupIssue.UNKNOWN_PLACEMENT, issue.code)
        assertEquals("level_up", issue.placementId)
        assertTrue(issue.message, issue.message.contains("interstitial placement"))
    }

    @Test
    fun formatMismatchIsReported() {
        assertTrue(init())
        loadAndWait(AdFormat.INTERSTITIAL, "home_banner")
        awaitMain(message = "setup issue") { issues.isNotEmpty() }
        assertEquals(QartveloAdsSetupIssue.FORMAT_MISMATCH, issues.single().code)
        assertTrue(issues.single().message, issues.single().message.contains("banner placement"))
    }

    @Test
    fun knownPlacementsReportNothing() {
        assertTrue(init())
        loadAndWait(AdFormat.INTERSTITIAL, "game_end")
        loadAndWait(AdFormat.REWARDED, "reward_coins")
        assertTrue(issues.toString(), issues.isEmpty())
    }

    @Test
    fun showChecksThePlacementToo() {
        assertTrue(init())
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        QartveloAds.showRewarded(activity, "missing_reward", listener)
        awaitMain(message = "setup issue") { issues.isNotEmpty() }
        assertEquals("missing_reward", issues.single().placementId)
    }

    @Test
    fun placementsUsedBeforeConfigArrivesAreCheckedOnceItDoes() {
        backend.initDelayMs = 300
        QartveloAds.initialize(app, APP_KEY, options()) { _, _ -> }
        QartveloAds.loadInterstitial("early_code", listener)
        awaitMain(message = "setup issue for early_code") { issues.any { it.placementId == "early_code" } }
        assertEquals(QartveloAdsSetupIssue.UNKNOWN_PLACEMENT, issues.single().code)
    }

    @Test
    fun rejectedKeysNeverJudgePlacements() {
        rejectInit("package_mismatch", null)
        init()
        loadAndWait(AdFormat.INTERSTITIAL, "level_up")
        awaitMain(message = "setup issue") { issues.isNotEmpty() }
        assertEquals(listOf(QartveloAdsSetupIssue.PACKAGE_MISMATCH), issues.map { it.code })
    }
```

- [ ] **Step 2: Run them to see them fail**

Run: `./gradlew :qartvelo-ads-core:testDebugUnitTest --tests com.qartvelo.sdk.SetupIssueTest`
Expected: the placement tests fail (`awaitMain` timeouts on "setup issue"); `knownPlacementsReportNothing` and `rejectedKeysNeverJudgePlacements` pass.

- [ ] **Step 3: Implement**

In `Engine.kt`, add after `fun placement(...)`:

```kotlin
    /** Placements used before fresh config arrived; checked again once it does. Main thread. */
    private val uncheckedPlacements = LinkedHashMap<String, AdFormat>()

    /**
     * Reports a placement code the dashboard does not have, or has with another format. Only config
     * fetched from the backend in this process is trusted: a cached copy can predate placements
     * created since. Main thread.
     */
    fun checkPlacement(placementId: String, format: AdFormat) {
        val config = remoteConfig
        if (configFetchedAt == 0L || config == null) {
            uncheckedPlacements[placementId] = format
            return
        }
        val placement = config.placements[placementId]
        when {
            placement == null -> reportSetupIssue(
                QartveloAdsSetupIssue.UNKNOWN_PLACEMENT,
                "Placement '$placementId' does not exist for this Android app. Create it in the Qartvelo Ads dashboard as a ${format.wireName} placement.",
                placementId,
            )
            placement.format != null && placement.format != format -> reportSetupIssue(
                QartveloAdsSetupIssue.FORMAT_MISMATCH,
                "Placement '$placementId' is a ${placement.format.wireName} placement but is used as ${format.wireName}. Use a ${format.wireName} placement code.",
                placementId,
            )
        }
    }

    /** Main thread. */
    private fun recheckPlacements() {
        val pending = LinkedHashMap(uncheckedPlacements)
        uncheckedPlacements.clear()
        pending.forEach { (id, format) -> checkPlacement(id, format) }
    }
```

In `onSessionInitialized`, replace `Main.post { updateAdapterSettings() }` with:

```kotlin
        Main.post {
            updateAdapterSettings()
            recheckPlacements()
        }
```

In `FullscreenController.load`, right after `val placement = engine.placement(placementId)`, add `engine.checkPlacement(placementId, format)`.

In `BannerController.loadNow`, right after `val placement = engine.placement(placementId)`, add `engine.checkPlacement(placementId, AdFormat.BANNER)`.

In `QartveloAds.show`, inside `else -> Main.run {`, add `e.checkPlacement(id, format)` as the first statement (before `if (e.loadsReady)`).

- [ ] **Step 4: Run all core tests**

Run: `./gradlew :qartvelo-ads-core:testDebugUnitTest`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add android/qartvelo-ads-core
git commit -F - <<'EOF'
Report unknown and mismatched placements as Android setup issues

Loads, shows and banners check the placement code against the placement list
fetched from the backend; codes used earlier are checked when it arrives.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
```

---

### Task 8: iOS setup issues for rejected app keys

**Files:**
- Create: `ios/Sources/QartveloAds/QartveloAdsSetupIssue.swift`
- Modify: `ios/Sources/QartveloAds/QartveloAdsDelegate.swift:14-24`
- Modify: `ios/Sources/QartveloAds/Models.swift:127-150` (`QartveloAdsOptions`)
- Modify: `ios/Sources/QartveloAds/Internal/Wire.swift:166-172` (`ApiError`)
- Modify: `ios/Sources/QartveloAds/Internal/ApiClient.swift:189-195`
- Modify: `ios/Sources/QartveloAds/Internal/SessionManager.swift:30-35,108-115`
- Modify: `ios/Sources/QartveloAds/Internal/Engine.swift:109-114` and after `onSessionInitialized` (229-237)
- Modify: `ios/Tests/QartveloAdsTests/Stubs.swift` (`RecordingDelegate`, lines 80-108)
- Test: create `ios/Tests/QartveloAdsTests/SetupIssueTests.swift`

**Interfaces:**
- Produces: `@objc public final class QartveloAdsSetupIssue` (`code`, `message`, `placementId: String?`, static `packageMismatch`, `platformMismatch`, `unknownPlacement`, `formatMismatch`); `@objc optional func qartveloAdsDidReportSetupIssue(_ issue: QartveloAdsSetupIssue)` on `QartveloAdsDelegate` (global observers); `func Engine.reportSetupIssue(_ code: String, _ message: String, placementId: String? = nil)`; `QartveloAdsOptions.admobTestUnitsInDebugBuilds: Bool = true` (ignored). Tasks 9 and 11 use these.

- [ ] **Step 1: Record setup issues in the test delegate**

In `RecordingDelegate` (`Stubs.swift`), add:

```swift
    var setupIssues: [QartveloAdsSetupIssue] = []

    func qartveloAdsDidReportSetupIssue(_ issue: QartveloAdsSetupIssue) {
        setupIssues.append(issue)
        record("setupIssue:\(issue.code):\(issue.placementId ?? "")")
    }
```

- [ ] **Step 2: Write the failing tests**

Create `SetupIssueTests.swift`:

```swift
import XCTest
@testable import QartveloAds

final class SetupIssueTests: XCTestCase {
    private let base = URL(string: "https://ads.test/")!
    private var observer: RecordingDelegate!

    override func setUp() {
        super.setUp()
        QartveloAds.resetForTests()
        StubURLProtocol.reset()
        TestHooks.urlProtocols = [StubURLProtocol.self]
        TestHooks.simulator = false
        TestHooks.developmentBuild = false
        TestHooks.appStoreBuild = true
        StubURLProtocol.route("/api/v1/sdk/initialize", json: [
            "session_token": "sess_1",
            "session_expires_at": "2099-01-01T00:00:00Z",
            "config": ["serving_enabled": true],
            "placements": [
                ["code": "level_end", "format": "interstitial"],
                ["code": "home_banner", "format": "banner"],
            ],
        ])
        observer = RecordingDelegate()
        QartveloAds.addObserver(observer)
    }

    override func tearDown() {
        QartveloAds.resetForTests()
        StubURLProtocol.reset()
        super.tearDown()
    }

    private func initialize() {
        let options = QartveloAdsOptions()
        options.baseURL = base
        options.requestTimeoutMs = 2_000
        let done = expectation(description: "initialize finished")
        QartveloAds.initialize(appKey: "app_\(UUID().uuidString)", options: options) { _, _ in done.fulfill() }
        wait(for: [done], timeout: 10)
    }

    /// Runs `trigger` and waits until the observer has received a setup issue.
    private func expectIssue(_ trigger: () -> Void) -> QartveloAdsSetupIssue {
        let found = expectation(description: "setup issue")
        found.assertForOverFulfill = false
        observer.onEvent = { if $0.hasPrefix("setupIssue") { found.fulfill() } }
        trigger()
        wait(for: [found], timeout: 10)
        return observer.setupIssues[0]
    }

    private func drainMain() {
        let drained = expectation(description: "main queue drained")
        DispatchQueue.main.async { drained.fulfill() }
        wait(for: [drained], timeout: 5)
    }

    private func rejectInit(_ code: String, details: [String: Any]?) {
        var error: [String: Any] = ["code": code, "message": "rejected"]
        if let details = details { error["details"] = details }
        StubURLProtocol.route("/api/v1/sdk/initialize", status: 403, json: ["error": error])
    }

    func testPackageMismatchNamesBothBundleIdsOnce() {
        rejectInit("package_mismatch", details: ["registered_package": "com.kakha13.beergame", "platform": "android"])
        let issue = expectIssue { initialize() }
        XCTAssertEqual(issue.code, QartveloAdsSetupIssue.packageMismatch)
        XCTAssertNil(issue.placementId)
        XCTAssertTrue(issue.message.contains("com.kakha13.beergame"), issue.message)
        drainMain()
        XCTAssertEqual(observer.setupIssues.count, 1)
    }

    func testPackageMismatchIsReportedEvenWhenTestModeHidesTheInitFailure() {
        TestHooks.simulator = true
        rejectInit("package_mismatch", details: ["registered_package": "com.kakha13.beergame", "platform": "android"])
        let issue = expectIssue { initialize() }
        XCTAssertEqual(issue.code, QartveloAdsSetupIssue.packageMismatch)
    }

    func testOlderBackendsWithoutDetailsStillGetAMessage() {
        rejectInit("package_mismatch", details: nil)
        let issue = expectIssue { initialize() }
        XCTAssertTrue(issue.message.contains("is not registered for"), issue.message)
    }

    func testPlatformMismatchNamesThePlatformOfTheKey() {
        rejectInit("platform_mismatch", details: ["platform": "android"])
        let issue = expectIssue { initialize() }
        XCTAssertEqual(issue.code, QartveloAdsSetupIssue.platformMismatch)
        XCTAssertTrue(issue.message.contains("the Android app"), issue.message)
    }

    func testOtherInitErrorsAreNotSetupIssues() {
        StubURLProtocol.route("/api/v1/sdk/initialize", status: 401, json: ["error": ["code": "invalid_app_key", "message": "Unknown app key."]])
        initialize()
        drainMain()
        XCTAssertTrue(observer.setupIssues.isEmpty)
    }
}
```

- [ ] **Step 3: Run them to see them fail**

Run (from `myAds/sdk`, any available iPhone simulator):
`xcodebuild test -scheme QartveloAds-Package -destination 'platform=iOS Simulator,name=iPhone 16' -only-testing:QartveloAdsTests/SetupIssueTests -skipPackagePluginValidation`
Expected: build failure, `cannot find type 'QartveloAdsSetupIssue' in scope`.

- [ ] **Step 4: Implement**

Create `QartveloAdsSetupIssue.swift`:

```swift
import Foundation

/// A setup problem the app developer has to fix, such as an app key registered for another bundle ID
/// or a placement code missing from the dashboard. Reported once per process through
/// `QartveloAdsDelegate.qartveloAdsDidReportSetupIssue(_:)` (global observers) and logged as an error.
@objc public final class QartveloAdsSetupIssue: NSObject {
    @objc public static let packageMismatch = "package_mismatch"
    @objc public static let platformMismatch = "platform_mismatch"
    @objc public static let unknownPlacement = "unknown_placement"
    @objc public static let formatMismatch = "format_mismatch"

    /// One of the constants above.
    @objc public let code: String
    /// What is wrong and how to fix it, in English.
    @objc public let message: String
    /// The placement concerned, or nil for app-level issues.
    @objc public let placementId: String?

    init(code: String, message: String, placementId: String?) {
        self.code = code
        self.message = message
        self.placementId = placementId
    }
}
```

In `QartveloAdsDelegate`, add as the last member:

```swift
    /// A setup problem to fix (wrong key or bundle ID, missing placement). Global observers only.
    @objc optional func qartveloAdsDidReportSetupIssue(_ issue: QartveloAdsSetupIssue)
```

In `QartveloAdsOptions`, add after `admobAdUnits`:

```swift
    /// Accepted for parity with Android, where debuggable builds use Google's test units for the AdMob
    /// fallback. Ignored on iOS: every installation outside the App Store already uses test units.
    @objc public var admobTestUnitsInDebugBuilds: Bool = true
```

In `Wire.swift`, add to `ApiError` after `let message: String`:

```swift
    /// The envelope's optional `error.details` object.
    var details: JSON? = nil
```

In `ApiClient.postJSON`, add `details: error?.object("details")` as the last argument of `ApiError(...)` (after `message:`).

In `SessionManager`, add the stored property `private let onFailed: (Error) -> Void`, extend the initializer to

```swift
    init(api: ApiClient, initBody: @escaping () -> JSON, initTimeoutMs: Int64, onInitialized: @escaping (InitResult) -> Void, onFailed: @escaping (Error) -> Void = { _ in }) {
        self.api = api
        self.initBody = initBody
        self.initTimeoutMs = initTimeoutMs
        self.onInitialized = onInitialized
        self.onFailed = onFailed
    }
```

and in the `catch` block, after `Log.e("QartveloAds session request failed: \(Self.describe(error))")`, add `onFailed(error)`.

In `Engine.swift`, extend the `SessionManager(...)` call with `onFailed: { [unowned self] error in onSessionFailed(error) }`, and add after `onSessionInitialized`:

```swift
    private let issuesLock = NSLock()
    private var reportedSetupIssues = Set<String>()

    /// Logs a setup issue once per process and tells the global observers. Any thread.
    func reportSetupIssue(_ code: String, _ message: String, placementId: String? = nil) {
        issuesLock.lock()
        let isNew = reportedSetupIssues.insert("\(code)|\(placementId ?? "")").inserted
        issuesLock.unlock()
        guard isNew else { return }
        Log.e("Setup issue (\(code)): \(message)")
        let issue = QartveloAdsSetupIssue(code: code, message: message, placementId: placementId)
        Listeners.emit([]) { $0.qartveloAdsDidReportSetupIssue?(issue) }
    }

    /// Session queue. Turns rejected app keys into setup issues, also in test mode where the failed
    /// initialization itself is reported to the app as success.
    private func onSessionFailed(_ error: Error) {
        guard let api = error as? ApiError else { return }
        let running = device.bundleId
        switch api.code {
        case QartveloAdsSetupIssue.packageMismatch:
            let message: String
            if let registered = api.details?.string("registered_package") {
                message = "This app key is registered for '\(registered)', but this app is '\(running)'. Use the key of the app registered for '\(running)', or correct the bundle ID in the Qartvelo Ads dashboard."
            } else {
                message = "This app key is not registered for '\(running)'. Use the key of the app registered for '\(running)', or correct the bundle ID in the Qartvelo Ads dashboard."
            }
            reportSetupIssue(QartveloAdsSetupIssue.packageMismatch, message)
        case QartveloAdsSetupIssue.platformMismatch:
            let owner = api.details?.string("platform") == "android" ? "the Android app" : "an app of another platform"
            reportSetupIssue(QartveloAdsSetupIssue.platformMismatch, "This app key belongs to \(owner). Register this iOS app in the Qartvelo Ads dashboard and use its own key.")
        default:
            break
        }
    }
```

- [ ] **Step 5: Run the whole iOS suite**

Run: `xcodebuild test -scheme QartveloAds-Package -destination 'platform=iOS Simulator,name=iPhone 16' -parallel-testing-enabled NO -skipPackagePluginValidation | tail -5`
Expected: `** TEST SUCCEEDED **`.

- [ ] **Step 6: Commit**

```bash
git add ios/Sources ios/Tests
git commit -F - <<'EOF'
Report rejected app keys as iOS setup issues

package_mismatch and platform_mismatch reach global observers once per
process, including in test mode, where the failed initialization is
reported to the app as success. QartveloAdsOptions accepts
admobTestUnitsInDebugBuilds for parity with Android and ignores it.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
```

---

### Task 9: iOS setup issues for unknown or mismatched placements

**Files:**
- Modify: `ios/Sources/QartveloAds/Internal/Engine.swift` (after `placement(_:)` at 294-296; `onSessionInitialized` at 229-237)
- Modify: `ios/Sources/QartveloAds/Internal/FullscreenController.swift:54`
- Modify: `ios/Sources/QartveloAds/Internal/BannerController.swift:127`
- Modify: `ios/Sources/QartveloAds/QartveloAds.swift:168` (`show`)
- Test: `ios/Tests/QartveloAdsTests/SetupIssueTests.swift`

**Interfaces:**
- Consumes: `Engine.reportSetupIssue` and `QartveloAdsSetupIssue` from Task 8.
- Produces: `func Engine.checkPlacement(_ placementId: String, _ format: QartveloAdFormat)` (main thread).

- [ ] **Step 1: Write the failing tests**

Add to `SetupIssueTests`:

```swift
    /// Loads an interstitial and waits for the load to end.
    private func load(_ placementId: String) {
        let delegate = RecordingDelegate()
        let finished = expectation(description: "load of \(placementId) finished")
        finished.assertForOverFulfill = false
        delegate.onEvent = { event in
            if event.hasPrefix("loaded") || event.hasPrefix("failed") { finished.fulfill() }
        }
        QartveloAds.loadInterstitial(placementId, delegate: delegate)
        wait(for: [finished], timeout: 10)
    }

    func testUnknownPlacementIsReportedOncePerCode() {
        initialize()
        load("level_up")
        load("level_up")
        drainMain()
        XCTAssertEqual(observer.setupIssues.map(\.code), [QartveloAdsSetupIssue.unknownPlacement])
        XCTAssertEqual(observer.setupIssues.first?.placementId, "level_up")
        XCTAssertTrue(observer.setupIssues.first?.message.contains("interstitial placement") == true)
    }

    func testFormatMismatchIsReported() {
        initialize()
        load("home_banner")
        drainMain()
        XCTAssertEqual(observer.setupIssues.map(\.code), [QartveloAdsSetupIssue.formatMismatch])
        XCTAssertTrue(observer.setupIssues.first?.message.contains("banner placement") == true)
    }

    func testKnownPlacementsReportNothing() {
        initialize()
        load("level_end")
        drainMain()
        XCTAssertTrue(observer.setupIssues.isEmpty)
    }

    func testRejectedKeysNeverJudgePlacements() {
        rejectInit("package_mismatch", details: nil)
        initialize()
        load("level_up")
        drainMain()
        XCTAssertEqual(observer.setupIssues.map(\.code), [QartveloAdsSetupIssue.packageMismatch])
    }
```

- [ ] **Step 2: Run them to see them fail**

Run: `xcodebuild test -scheme QartveloAds-Package -destination 'platform=iOS Simulator,name=iPhone 16' -only-testing:QartveloAdsTests/SetupIssueTests -skipPackagePluginValidation | tail -20`
Expected: `testUnknownPlacementIsReportedOncePerCode` and `testFormatMismatchIsReported` fail on empty `setupIssues`.

- [ ] **Step 3: Implement**

In `Engine.swift`, add after `placement(_:)`:

```swift
    /// Placements used before fresh config arrived; checked again once it does. Main thread.
    private var uncheckedPlacements: [String: QartveloAdFormat] = [:]

    /// Reports a placement code the dashboard does not have, or has with another format. Only config
    /// fetched from the backend in this process is trusted: a cached copy can predate placements
    /// created since. Main thread.
    func checkPlacement(_ placementId: String, _ format: QartveloAdFormat) {
        stateLock.lock()
        let fresh = configFetchedAt != 0
        let config = storedRemoteConfig
        stateLock.unlock()
        guard fresh, let config = config else {
            uncheckedPlacements[placementId] = format
            return
        }
        guard let placement = config.placements[placementId] else {
            reportSetupIssue(
                QartveloAdsSetupIssue.unknownPlacement,
                "Placement '\(placementId)' does not exist for this iOS app. Create it in the Qartvelo Ads dashboard as a \(format.wireName) placement.",
                placementId: placementId
            )
            return
        }
        if let configured = placement.format, configured != format {
            reportSetupIssue(
                QartveloAdsSetupIssue.formatMismatch,
                "Placement '\(placementId)' is a \(configured.wireName) placement but is used as \(format.wireName). Use a \(format.wireName) placement code.",
                placementId: placementId
            )
        }
    }

    /// Main thread.
    private func recheckPlacements() {
        let pending = uncheckedPlacements
        uncheckedPlacements.removeAll()
        for (placementId, format) in pending { checkPlacement(placementId, format) }
    }
```

In `onSessionInitialized`, replace `Main.post { [self] in updateAdapterSettings() }` with:

```swift
        Main.post { [self] in
            updateAdapterSettings()
            recheckPlacements()
        }
```

In `FullscreenController.load`, right after `let placement = engine.placement(placementId)`, add `engine.checkPlacement(placementId, format)`.

In `BannerController.loadNow`, right after `let placement = engine.placement(placementId)`, add `engine.checkPlacement(placementId, .banner)`.

In `QartveloAds.show` (the private static helper), add `engine.checkPlacement(id, format)` right before `if engine.loadsReady {`.

- [ ] **Step 4: Run the whole iOS suite and refresh the React Native copy**

Run:
```bash
xcodebuild test -scheme QartveloAds-Package -destination 'platform=iOS Simulator,name=iPhone 16' -parallel-testing-enabled NO -skipPackagePluginValidation | tail -5
(cd react-native/packages/react-native-qartvelo-ads && node scripts/sync-ios-sdk.cjs)
```
Expected: `** TEST SUCCEEDED **`.

- [ ] **Step 5: Commit**

```bash
git add ios/Sources ios/Tests
git commit -F - <<'EOF'
Report unknown and mismatched placements as iOS setup issues

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
```

---

### Task 10: React Native per-platform options and `admobTestUnitsInDebugBuilds`

Working directory for this and the following React Native tasks: `myAds/sdk/react-native/packages/react-native-qartvelo-ads` (called `P`).

**Files:**
- Modify: `P/src/types.ts` (`QartveloAdsInitOptions`, lines 37-63; add `PerPlatform`)
- Modify: `P/src/NativeQartveloAds.ts` (`NativeInitOptions`)
- Modify: `P/src/wire.ts` (`toNativeInitOptions`, lines 115-197)
- Modify: `P/src/index.tsx` (type exports)
- Test: `P/src/__tests__/initialize.test.ts`
- Modify: `P/android/src/main/java/com/qartvelo/reactnative/Options.kt:39-48`
- Test: `P/android/src/test/java/com/qartvelo/reactnative/WireAndOptionsTest.kt`

**Interfaces:**
- Consumes: `QartveloAdsOptions.admobTestUnitsInDebugBuilds` (Task 5).
- Produces: `export interface PerPlatform<T> { android?: T; ios?: T }`; `QartveloAdsInitOptions.appKey: string | PerPlatform<string>`; `admobAdUnits?: Record<string, string | PerPlatform<string>>`; `admobTestUnitsInDebugBuilds?: boolean`; `NativeInitOptions.admobTestUnitsInDebugBuilds?: boolean`.

- [ ] **Step 1: Write the failing tests**

In `initialize.test.ts`, inside the `describe.each(['android', 'ios'] as const)` callback, add:

```ts
    it('uses the value for the running platform', async () => {
      await QartveloAds.initialize({
        appKey: { android: 'app_android', ios: 'app_ios' },
        admobAdUnits: {
          home_banner: { android: 'ca-app-pub-1/111', ios: 'ca-app-pub-1/222' },
          game_end: 'ca-app-pub-1/333',
          other_only:
            os === 'android'
              ? { ios: 'ca-app-pub-1/444' }
              : { android: 'ca-app-pub-1/444' },
        },
        admobTestUnitsInDebugBuilds: false,
      });

      expect(fake.native.initializeSdk).toHaveBeenCalledWith({
        appKey: os === 'android' ? 'app_android' : 'app_ios',
        admobAdUnits: {
          home_banner: os === 'android' ? 'ca-app-pub-1/111' : 'ca-app-pub-1/222',
          game_end: 'ca-app-pub-1/333',
        },
        admobTestUnitsInDebugBuilds: false,
      });
    });

    it('rejects a per-platform appKey without a value for this platform', async () => {
      const promise = QartveloAds.initialize({
        appKey: os === 'android' ? { ios: 'app_ios' } : { android: 'app_android' },
      });
      await expect(promise).rejects.toMatchObject({
        code: 'invalid_argument',
        message: `appKey has no value for ${os}`,
      });
      expect(fake.native.initializeSdk).not.toHaveBeenCalled();
    });
```

and add these rows to the existing `it.each([...])` of invalid options:

```ts
      [{ appKey: 42 }, /appKey/],
      [{ appKey: { android: 7 } }, /appKey/],
      [{ appKey: 'app_x', admobAdUnits: { game_end: { android: 7 } } }, /admobAdUnits/],
      [{ appKey: 'app_x', admobTestUnitsInDebugBuilds: 'no' }, /admobTestUnitsInDebugBuilds/],
```

- [ ] **Step 2: Run them to see them fail**

Run: `npx jest src/__tests__/initialize.test.ts`
Expected: the new tests fail (`appKey must be a non-empty string` for the object form).

- [ ] **Step 3: Implement**

In `types.ts`, add before `QartveloAdsInitOptions`:

```ts
/** One value per platform. The running platform's value is used; a missing one counts as unset. */
export interface PerPlatform<T> {
  android?: T;
  ios?: T;
}
```

and in `QartveloAdsInitOptions` replace `appKey: string;` and `admobAdUnits?: Record<string, string>;` (keep their place) with:

```ts
  /** Publisher app key (`app_...`), or one key per platform. Never the server-side secret. */
  appKey: string | PerPlatform<string>;
```

```ts
  /** Placement code -> your AdMob ad unit id, or one id per platform. Overrides the dashboard unit. */
  admobAdUnits?: Record<string, string | PerPlatform<string>>;
  /**
   * Use Google's test units for the AdMob fallback in debuggable Android builds, even when test mode
   * is off. Default true. Ignored on iOS, where every non-App Store install already uses test units.
   */
  admobTestUnitsInDebugBuilds?: boolean;
```

In `NativeQartveloAds.ts`, add `admobTestUnitsInDebugBuilds?: boolean;` to `NativeInitOptions` after `testModeInDebugBuilds?: boolean;`.

In `wire.ts`, add `import { Platform } from 'react-native';` at the top and this helper after `optionalBoolean`:

```ts
/** The value for the running platform, from a string or a `{ android, ios }` object. */
function platformValue(value: unknown, name: string): string | undefined {
  if (typeof value === 'string') {
    return value.trim() || undefined;
  }
  if (typeof value === 'object' && value !== null && !Array.isArray(value)) {
    const own = (value as Record<string, unknown>)[Platform.OS];
    if (own === undefined || own === null) {
      return undefined;
    }
    if (typeof own !== 'string') {
      throw invalid(`${name}.${Platform.OS} must be a string`);
    }
    return own.trim() || undefined;
  }
  throw invalid(`${name} must be a string or an object with android and ios values`);
}
```

In `toNativeInitOptions`, replace the `appKey` block with:

```ts
  const appKey = platformValue(options.appKey, 'appKey');
  if (!appKey) {
    throw invalid(
      typeof options.appKey === 'object' && options.appKey !== null
        ? `appKey has no value for ${Platform.OS}`
        : 'appKey must be a non-empty string'
    );
  }
  const result: NativeInitOptions = { appKey };
```

after the `admobFallback` block add:

```ts
  const admobTestUnits = optionalBoolean(options, 'admobTestUnitsInDebugBuilds');
  if (admobTestUnits !== undefined) {
    result.admobTestUnitsInDebugBuilds = admobTestUnits;
  }
```

and replace the `for (const [code, unit] of Object.entries(units))` loop with:

```ts
    for (const [code, unit] of Object.entries(units)) {
      const id = platformValue(unit, `admobAdUnits["${code}"]`);
      if (!code.trim() || (typeof unit === 'string' && !id)) {
        throw invalid(`admobAdUnits["${code}"] must be a non-empty ad unit id`);
      }
      if (id) {
        copy[code.trim()] = id;
      }
    }
```

In `index.tsx`, add `PerPlatform` to the `export type { ... } from './types'` list.

- [ ] **Step 4: Run the JS checks**

Run: `npm run typecheck && npm run lint && npx jest`
Expected: all pass.

- [ ] **Step 5: Parse the option on Android**

Add to `WireAndOptionsTest`:

```kotlin
    @Test
    fun parsesAdmobTestUnitsInDebugBuilds() {
        assertTrue(Options.parseInit(mapOf("appKey" to "app_x")).options.admobTestUnitsInDebugBuilds)
        assertFalse(
            Options.parseInit(mapOf("appKey" to "app_x", "admobTestUnitsInDebugBuilds" to false))
                .options.admobTestUnitsInDebugBuilds,
        )
    }
```

In `Options.parseInit`, add to the `QartveloAdsOptions(...)` arguments, after `testModeInDebugBuilds = ...`:

```kotlin
                admobTestUnitsInDebugBuilds = bool(map, "admobTestUnitsInDebugBuilds") ?: defaults.admobTestUnitsInDebugBuilds,
```

iOS needs no bridge change: the option is ignored there (do not add it to `RCTQartveloAds.mm`).

- [ ] **Step 6: Run the Android bridge tests against the local SDK**

Run:
```bash
(cd /Users/kakha13/Developer/myAds/sdk/android && ./gradlew :qartvelo-ads-core:publishToMavenLocal :qartvelo-ads-admob:publishToMavenLocal)
(cd /Users/kakha13/Developer/myAds/sdk/react-native/example && npm install --no-audit --no-fund && cd android && ./gradlew :qartvelo_react-native-ads:testDebugUnitTest)
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
cd /Users/kakha13/Developer/myAds/sdk
git add react-native/packages/react-native-qartvelo-ads/src react-native/packages/react-native-qartvelo-ads/android
git commit -F - <<'EOF'
Accept per-platform appKey and AdMob units in React Native

initialize() takes appKey and admobAdUnits values as a string or as
{ android, ios }, and passes admobTestUnitsInDebugBuilds to Android.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
```

---

### Task 11: React Native `setupIssue` event and development warnings

**Files:**
- Modify: `P/src/types.ts` (`QartveloAdsEventMap`, lines 120-140; add `SetupIssueCode`)
- Modify: `P/src/wire.ts` (`EVENT_TYPES` at 48-57, `toEvent` at 266)
- Modify: `P/src/events.ts` (constructor, `syncNativeSubscription`, `dispatch`)
- Modify: `P/src/QartveloAds.ts` (registry construction at line 78, `initialize` at 86-90)
- Modify: `P/src/index.tsx` (type exports)
- Test: create `P/src/__tests__/setupIssue.test.ts`
- Modify: `P/android/src/main/java/com/qartvelo/reactnative/Wire.kt` (`AdEventForwarder`)
- Test: `P/android/src/test/java/com/qartvelo/reactnative/WireAndOptionsTest.kt`
- Modify: `P/ios/QartveloAdsBridge.swift` (`AdRelay`, lines 14-47)

**Interfaces:**
- Consumes: `QartveloAdsListener.onSetupIssue` (Task 6), `QartveloAdsDelegate.qartveloAdsDidReportSetupIssue` (Task 8).
- Produces: public event `setupIssue: { type: 'setupIssue'; code: SetupIssueCode; message: string; placementId?: string }`; wire payload `{ type: 'setupIssue', placementId: string ('' for none), error: { code, message } }`; `EventRegistry.keepNativeSubscription(): void`; `EventRegistry` constructor `(getNative, hooks?: { onSetupIssue?: (issue) => void })`.

- [ ] **Step 1: Write the failing tests**

Create `setupIssue.test.ts`:

```ts
import { afterEach, beforeEach, describe, expect, it, jest } from '@jest/globals';

jest.mock(
  '../NativeQartveloAds',
  () => require('../__fixtures__/fakeNative').nativeModuleMock
);

type Fake = typeof import('../__fixtures__/fakeNative').fake;
type Api = typeof import('../index').QartveloAds;

function spyWarn() {
  return jest.spyOn(console, 'warn').mockImplementation(() => {});
}

// Module state (the warned set, the kept subscription) is per module instance, so every test
// loads fresh copies of the package and of the fake native module.
let fake: Fake;
let QartveloAds: Api;
let warn: ReturnType<typeof spyWarn>;

beforeEach(() => {
  jest.resetModules();
  ({ fake } = require('../__fixtures__/fakeNative'));
  ({ QartveloAds } = require('../index'));
  require('../__fixtures__/platform').setPlatform('android');
  fake.reset();
  warn = spyWarn();
});

afterEach(() => {
  warn.mockRestore();
});

function issue(code: string, message: string, placementId = '') {
  return { type: 'setupIssue', placementId, error: { code, message } };
}

describe('setupIssue', () => {
  it('warns in development without any app listener', async () => {
    await QartveloAds.initialize({ appKey: 'app_x' });
    fake.emit(issue('package_mismatch', "This app key is registered for 'com.other'"));
    expect(warn).toHaveBeenCalledWith(
      "[QartveloAds] This app key is registered for 'com.other'"
    );
  });

  it('warns once per code and placement', async () => {
    await QartveloAds.initialize({ appKey: 'app_x' });
    fake.emit(issue('unknown_placement', 'Create level_up', 'level_up'));
    fake.emit(issue('unknown_placement', 'Create level_up', 'level_up'));
    fake.emit(issue('unknown_placement', 'Create level_two', 'level_two'));
    expect(warn).toHaveBeenCalledTimes(2);
  });

  it('delivers issues to app listeners', () => {
    const received: unknown[] = [];
    QartveloAds.addListener('setupIssue', (event) => received.push(event));
    fake.emit(issue('format_mismatch', 'Use a banner placement', 'home_banner'));
    fake.emit(issue('package_mismatch', 'Wrong key'));
    fake.emit(issue('something_new', 'Ignored'));
    expect(received).toEqual([
      {
        type: 'setupIssue',
        code: 'format_mismatch',
        message: 'Use a banner placement',
        placementId: 'home_banner',
      },
      { type: 'setupIssue', code: 'package_mismatch', message: 'Wrong key' },
    ]);
  });

  it('does not warn in release builds', async () => {
    const globals = global as unknown as { __DEV__: boolean };
    const dev = globals.__DEV__;
    globals.__DEV__ = false;
    try {
      await QartveloAds.initialize({ appKey: 'app_x' });
      const received: unknown[] = [];
      QartveloAds.addListener('setupIssue', (event) => received.push(event));
      fake.emit(issue('package_mismatch', 'Wrong key'));
      expect(received).toHaveLength(1);
      expect(warn).not.toHaveBeenCalled();
    } finally {
      globals.__DEV__ = dev;
    }
  });
});
```

- [ ] **Step 2: Run them to see them fail**

Run: `npx jest src/__tests__/setupIssue.test.ts`
Expected: failures (`Unknown event "setupIssue"`, no warnings).

- [ ] **Step 3: Implement the JavaScript side**

In `types.ts`, add:

```ts
/** Setup problems reported through the `setupIssue` event. */
export type SetupIssueCode =
  | 'package_mismatch'
  | 'platform_mismatch'
  | 'unknown_placement'
  | 'format_mismatch';
```

and add to `QartveloAdsEventMap` (after `noAdAvailable`):

```ts
  /**
   * A setup problem to fix: an app key for another package or platform, or a placement code the
   * dashboard does not have (or has with another format). Printed with `console.warn` in development.
   */
  setupIssue: {
    type: 'setupIssue';
    code: SetupIssueCode;
    message: string;
    placementId?: string;
  };
```

In `wire.ts`, add `'setupIssue'` to `EVENT_TYPES`, add `QartveloAdsEventMap` and `SetupIssueCode` to the type imports from `./types`, and add:

```ts
const SETUP_ISSUE_CODES: ReadonlySet<string> = new Set<SetupIssueCode>([
  'package_mismatch',
  'platform_mismatch',
  'unknown_placement',
  'format_mismatch',
]);

function toSetupIssue(
  raw: NativeAdEvent
): QartveloAdsEventMap['setupIssue'] | null {
  const code = nonEmpty(raw.error?.code);
  const message = nonEmpty(raw.error?.message);
  if (!code || !SETUP_ISSUE_CODES.has(code) || !message) {
    return null;
  }
  const placementId = nonEmpty(raw.placementId);
  const event: QartveloAdsEventMap['setupIssue'] = {
    type: 'setupIssue',
    code: code as SetupIssueCode,
    message,
  };
  return placementId ? { ...event, placementId } : event;
}
```

In `toEvent`, right after the `if (!raw || !isEventType(raw.type)) { return null; }` check, add:

```ts
  if (raw.type === 'setupIssue') {
    return toSetupIssue(raw);
  }
```

In `events.ts`, add `QartveloAdsEventMap` to the type imports and:

```ts
export interface EventRegistryHooks {
  /** Called with every setup issue from native, whether or not the app listens for them. */
  onSetupIssue?: (issue: QartveloAdsEventMap['setupIssue']) => void;
}
```

replace the constructor with

```ts
  private keepAlive = false;

  /** `getNative` returns null on platforms without the native module; listeners are then inert. */
  constructor(
    private readonly getNative: () => Spec | null,
    private readonly hooks: EventRegistryHooks = {}
  ) {}

  /** Keeps the native subscription open without app listeners, so hooks see every event. */
  keepNativeSubscription(): void {
    this.keepAlive = true;
    this.syncNativeSubscription();
  }
```

in `syncNativeSubscription` replace `const wanted = this.entries.size > 0;` with `const wanted = this.keepAlive || this.entries.size > 0;`, and in `dispatch`, right after `if (!event) { return; }`, add:

```ts
    if (event.type === 'setupIssue') {
      this.hooks.onSetupIssue?.(event);
    }
```

In `QartveloAds.ts`, add `QartveloAdsEventMap` to the type imports, replace `const events = new EventRegistry(availableNative);` with:

```ts
const warnedSetupIssues = new Set<string>();

/** Development builds print each setup issue once, so it shows in LogBox without app code. */
function warnSetupIssue(issue: QartveloAdsEventMap['setupIssue']): void {
  if (!__DEV__) {
    return;
  }
  const key = `${issue.code}|${issue.placementId ?? ''}`;
  if (warnedSetupIssues.has(key)) {
    return;
  }
  warnedSetupIssues.add(key);
  console.warn(`[QartveloAds] ${issue.message}`);
}

const events = new EventRegistry(availableNative, { onSetupIssue: warnSetupIssue });
```

and replace `initialize` with:

```ts
  initialize(options: QartveloAdsInitOptions): Promise<void> {
    return run(undefined, async (native) => {
      const nativeOptions = toNativeInitOptions(options);
      if (__DEV__) {
        events.keepNativeSubscription();
      }
      await native.initializeSdk(nativeOptions);
    });
  },
```

In `index.tsx`, add `SetupIssueCode` to the type exports.

- [ ] **Step 4: Run the JS checks**

Run: `npm run typecheck && npm run lint && npx jest`
Expected: all pass (`events.test.ts` never calls `initialize`, so its subscriber counts are unchanged).

- [ ] **Step 5: Forward the native callbacks**

Add to `WireAndOptionsTest` (import `com.qartvelo.sdk.QartveloAdsSetupIssue`):

```kotlin
    @Test
    fun forwardsSetupIssuesWithCodeAndMessage() {
        val events = mutableListOf<Map<String, Any?>>()
        val forwarder = AdEventForwarder({ null }) { events += it }
        forwarder.onSetupIssue(QartveloAdsSetupIssue("unknown_placement", "Create it", "level_up"))
        forwarder.onSetupIssue(QartveloAdsSetupIssue("package_mismatch", "Wrong key", null))
        assertEquals("setupIssue", events[0]["type"])
        assertEquals("level_up", events[0]["placementId"])
        assertEquals(mapOf("code" to "unknown_placement", "message" to "Create it"), events[0]["error"])
        assertEquals("", events[1]["placementId"])
    }
```

In `Wire.kt`, add to `AdEventForwarder` (import `com.qartvelo.sdk.QartveloAdsSetupIssue`):

```kotlin
    override fun onSetupIssue(issue: QartveloAdsSetupIssue) {
        val event = Wire.event("setupIssue", issue.placementId.orEmpty(), null)
        event["error"] = mapOf("code" to issue.code, "message" to issue.message)
        sink(event)
    }
```

In `ios/QartveloAdsBridge.swift`, add to `AdRelay`:

```swift
    func qartveloAdsDidReportSetupIssue(_ issue: QartveloAdsSetupIssue) {
        event?(["type": "setupIssue", "placementId": issue.placementId ?? "",
                "error": ["code": issue.code, "message": issue.message]])
    }
```

- [ ] **Step 6: Run the native bridge checks**

Run:
```bash
node scripts/sync-ios-sdk.cjs
(cd /Users/kakha13/Developer/myAds/sdk/android && ./gradlew :qartvelo-ads-core:publishToMavenLocal :qartvelo-ads-admob:publishToMavenLocal)
(cd /Users/kakha13/Developer/myAds/sdk/react-native/example/android && ./gradlew :qartvelo_react-native-ads:testDebugUnitTest)
(cd /Users/kakha13/Developer/myAds/sdk/react-native/example/ios && pod install && cd .. && xcodebuild -workspace ios/QartveloAdsExample.xcworkspace -scheme QartveloAdsExample -configuration Debug -destination 'generic/platform=iOS Simulator' -derivedDataPath qa-out-local CODE_SIGNING_ALLOWED=NO build | tail -3)
```
Expected: BUILD SUCCESSFUL and `** BUILD SUCCEEDED **`.

- [ ] **Step 7: Commit**

```bash
git add react-native/packages/react-native-qartvelo-ads/src react-native/packages/react-native-qartvelo-ads/android react-native/packages/react-native-qartvelo-ads/ios/QartveloAdsBridge.swift
git commit -F - <<'EOF'
Add the setupIssue event and development warnings to React Native

Native setup issues reach JS through the existing event stream. In
development, initialize() keeps that stream open and prints each issue once
with console.warn, so it shows in LogBox without app code.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
```

---

### Task 12: React Native `preload` and `loadIfNeeded`

**Files:**
- Modify: `P/src/types.ts` (`QartveloAdsInitOptions`; add `ShowOptions`)
- Modify: `P/src/wire.ts` (add `toPreload`, `toShowOptions`)
- Modify: `P/src/QartveloAds.ts` (`initialize`, `showInterstitial` at 103-112, `showRewarded` at 129-135)
- Modify: `P/src/index.tsx` (type exports)
- Test: create `P/src/__tests__/preload.test.ts`

**Interfaces:**
- Consumes: `events.keepNativeSubscription()` in `initialize` (Task 11).
- Produces: `QartveloAdsInitOptions.preload?: { interstitial?: string[]; rewarded?: string[] }`; `export interface ShowOptions { loadIfNeeded?: boolean }`; `showInterstitial(placementId: string, options?: ShowOptions): Promise<ShowResult>`; `showRewarded(placementId: string, options?: ShowOptions): Promise<RewardedShowResult>`.

- [ ] **Step 1: Write the failing tests**

Create `preload.test.ts`:

```ts
import { beforeEach, describe, expect, it, jest } from '@jest/globals';

jest.mock(
  '../NativeQartveloAds',
  () => require('../__fixtures__/fakeNative').nativeModuleMock
);

type FakeModule = typeof import('../__fixtures__/fakeNative');
type Api = typeof import('../index').QartveloAds;

const flush = () => new Promise((resolve) => setImmediate(resolve));

// The preloaded set is module state, so every test loads fresh copies of the package and the fake.
let fake: FakeModule['fake'];
let nativeError: FakeModule['nativeError'];
let QartveloAds: Api;

beforeEach(() => {
  jest.resetModules();
  ({ fake, nativeError } = require('../__fixtures__/fakeNative'));
  ({ QartveloAds } = require('../index'));
  require('../__fixtures__/platform').setPlatform('android');
  fake.reset();
  fake.native.loadInterstitial.mockImplementation(async (placementId) => ({
    placementId,
    format: 'interstitial',
    source: 'qartvelo',
  }));
  fake.native.loadRewarded.mockImplementation(async (placementId) => ({
    placementId,
    format: 'rewarded',
    source: 'qartvelo',
  }));
  fake.native.showInterstitial.mockResolvedValue({ shown: true, rewarded: false, source: 'qartvelo' });
  fake.native.showRewarded.mockResolvedValue({
    shown: true,
    rewarded: true,
    source: 'qartvelo',
    reward: { type: 'reward', amount: 1 },
  });
});

describe('preload', () => {
  it('loads the listed placements once, trimmed', async () => {
    await QartveloAds.initialize({
      appKey: 'app_x',
      preload: { interstitial: [' game_end ', 'game_end'], rewarded: ['reward_coins'] },
    });
    await flush();
    expect(fake.native.loadInterstitial.mock.calls).toEqual([['game_end']]);
    expect(fake.native.loadRewarded.mock.calls).toEqual([['reward_coins']]);
  });

  it('reloads a preloaded placement after each show, and only that one', async () => {
    await QartveloAds.initialize({ appKey: 'app_x', preload: { interstitial: ['game_end'] } });
    await flush();
    fake.native.loadInterstitial.mockClear();
    await QartveloAds.showInterstitial('game_end');
    await QartveloAds.showInterstitial('other_code');
    await flush();
    expect(fake.native.loadInterstitial.mock.calls).toEqual([['game_end']]);
  });

  it('reloads even when the show fails', async () => {
    await QartveloAds.initialize({ appKey: 'app_x', preload: { rewarded: ['reward_coins'] } });
    await flush();
    fake.native.loadRewarded.mockClear();
    fake.native.showRewarded.mockRejectedValueOnce(nativeError('show_failed', 'Could not show'));
    await expect(QartveloAds.showRewarded('reward_coins')).rejects.toMatchObject({ code: 'show_failed' });
    await flush();
    expect(fake.native.loadRewarded.mock.calls).toEqual([['reward_coins']]);
  });

  it('rejects invalid preload lists without initializing', async () => {
    await expect(
      QartveloAds.initialize({ appKey: 'app_x', preload: { interstitial: [''] } })
    ).rejects.toMatchObject({ code: 'invalid_argument' });
    expect(fake.native.initializeSdk).not.toHaveBeenCalled();
  });
});

describe('loadIfNeeded', () => {
  it('shows at once when an ad is ready', async () => {
    fake.native.isRewardedReady.mockResolvedValue(true);
    const result = await QartveloAds.showRewarded('reward_coins', { loadIfNeeded: true });
    expect(result.rewarded).toBe(true);
    expect(fake.native.loadRewarded).not.toHaveBeenCalled();
  });

  it('loads first when nothing is ready', async () => {
    fake.native.isRewardedReady.mockResolvedValue(false);
    const result = await QartveloAds.showRewarded('reward_coins', { loadIfNeeded: true });
    expect(fake.native.loadRewarded.mock.calls).toEqual([['reward_coins']]);
    expect(fake.native.showRewarded).toHaveBeenCalledTimes(1);
    expect(result).toMatchObject({ shown: true, rewarded: true });
  });

  it('resolves shown false when the load fails', async () => {
    fake.native.isInterstitialReady.mockResolvedValue(false);
    fake.native.loadInterstitial.mockRejectedValueOnce(nativeError('no_fill', 'No ad'));
    await expect(
      QartveloAds.showInterstitial('game_end', { loadIfNeeded: true })
    ).resolves.toEqual({ shown: false });
    fake.native.isRewardedReady.mockResolvedValue(false);
    fake.native.loadRewarded.mockRejectedValueOnce(nativeError('no_fill', 'No ad'));
    await expect(
      QartveloAds.showRewarded('reward_coins', { loadIfNeeded: true })
    ).resolves.toEqual({ shown: false, rewarded: false });
    expect(fake.native.showInterstitial).not.toHaveBeenCalled();
    expect(fake.native.showRewarded).not.toHaveBeenCalled();
  });

  it('never loads without the option', async () => {
    await QartveloAds.showRewarded('reward_coins');
    expect(fake.native.isRewardedReady).not.toHaveBeenCalled();
    expect(fake.native.loadRewarded).not.toHaveBeenCalled();
  });

  it('rejects invalid show options', async () => {
    await expect(
      QartveloAds.showRewarded('reward_coins', { loadIfNeeded: 'yes' } as never)
    ).rejects.toMatchObject({ code: 'invalid_argument' });
  });
});
```

- [ ] **Step 2: Run them to see them fail**

Run: `npx jest src/__tests__/preload.test.ts`
Expected: failures (no preload loads, `loadIfNeeded` ignored).

- [ ] **Step 3: Implement**

In `types.ts`, add to `QartveloAdsInitOptions`:

```ts
  /**
   * Placements to load right after initialization (loads wait for it) and again after each show,
   * so the next one is usually ready at once.
   */
  preload?: { interstitial?: string[]; rewarded?: string[] };
```

and after `RewardedShowResult`:

```ts
/** Options of `showInterstitial()` and `showRewarded()`. */
export interface ShowOptions {
  /** When no ad is ready, wait for a load first instead of resolving `{ shown: false }`. Default false. */
  loadIfNeeded?: boolean;
}
```

In `wire.ts`, add:

```ts
export interface PreloadPlan {
  interstitial: string[];
  rewarded: string[];
}

/** Validates `preload`, trimming and de-duplicating the placement codes. */
export function toPreload(preload: unknown): PreloadPlan {
  const plan: PreloadPlan = { interstitial: [], rewarded: [] };
  if (preload === undefined || preload === null) {
    return plan;
  }
  if (typeof preload !== 'object' || Array.isArray(preload)) {
    throw invalid('preload must be an object with interstitial and rewarded arrays');
  }
  for (const format of ['interstitial', 'rewarded'] as const) {
    const codes = (preload as Record<string, unknown>)[format];
    if (codes === undefined || codes === null) {
      continue;
    }
    if (!Array.isArray(codes)) {
      throw invalid(`preload.${format} must be an array of placement codes`);
    }
    for (const code of codes) {
      const id = typeof code === 'string' ? code.trim() : '';
      if (!id) {
        throw invalid(`preload.${format} must only contain non-empty placement codes`);
      }
      if (!plan[format].includes(id)) {
        plan[format].push(id);
      }
    }
  }
  return plan;
}

/** Validates the options of `showInterstitial()` and `showRewarded()`. */
export function toShowOptions(options: unknown): { loadIfNeeded: boolean } {
  if (options === undefined || options === null) {
    return { loadIfNeeded: false };
  }
  if (typeof options !== 'object' || Array.isArray(options)) {
    throw invalid('show options must be an object');
  }
  const value = (options as Record<string, unknown>).loadIfNeeded;
  if (value === undefined || value === null) {
    return { loadIfNeeded: false };
  }
  if (typeof value !== 'boolean') {
    throw invalid('loadIfNeeded must be a boolean');
  }
  return { loadIfNeeded: value };
}
```

In `QartveloAds.ts`, import `ShowOptions` (type) and `toPreload`, `toShowOptions`, and add after `load(...)`:

```ts
type FullscreenFormat = 'interstitial' | 'rewarded';

/** Placements from `initialize({ preload })`, reloaded after every show. */
const preloaded: Record<FullscreenFormat, Set<string>> = {
  interstitial: new Set(),
  rewarded: new Set(),
};

function preload(placementId: string, format: FullscreenFormat): void {
  load(placementId, format).catch(() => {
    // A failed preload only means the next show loads on demand or finds nothing.
  });
}

function reloadIfPreloaded(placementId: unknown, format: FullscreenFormat): void {
  const id = typeof placementId === 'string' ? placementId.trim() : '';
  if (id && preloaded[format].has(id)) {
    preload(id, format);
  }
}

/** Shows a full-screen ad; with `loadIfNeeded`, waits for a load first when none is ready. */
async function show(
  placementId: string,
  format: FullscreenFormat,
  options: ShowOptions | undefined
): Promise<RewardedShowResult> {
  try {
    return await run(placementId, async (native) => {
      const id = toPlacementId(placementId);
      const { loadIfNeeded } = toShowOptions(options);
      if (loadIfNeeded) {
        const ready =
          format === 'rewarded'
            ? await native.isRewardedReady(id)
            : await native.isInterstitialReady(id);
        if (!ready) {
          try {
            await (format === 'rewarded'
              ? native.loadRewarded(id)
              : native.loadInterstitial(id));
          } catch {
            return { shown: false, rewarded: false };
          }
        }
      }
      return toRewardedShowResult(
        format === 'rewarded'
          ? await native.showRewarded(id)
          : await native.showInterstitial(id)
      );
    });
  } finally {
    reloadIfPreloaded(placementId, format);
  }
}
```

Replace `initialize`, `showInterstitial` and `showRewarded` in the `QartveloAds` object with:

```ts
  initialize(options: QartveloAdsInitOptions): Promise<void> {
    return run(undefined, async (native) => {
      const nativeOptions = toNativeInitOptions(options);
      const plan = toPreload(options.preload);
      if (__DEV__) {
        events.keepNativeSubscription();
      }
      const started = native.initializeSdk(nativeOptions);
      for (const format of ['interstitial', 'rewarded'] as const) {
        for (const id of plan[format]) {
          preloaded[format].add(id);
          preload(id, format);
        }
      }
      await started;
    });
  },
```

```ts
  showInterstitial(placementId: string, options?: ShowOptions): Promise<ShowResult> {
    return show(placementId, 'interstitial', options).then((result) =>
      result.source
        ? { shown: result.shown, source: result.source }
        : { shown: result.shown }
    );
  },
```

```ts
  showRewarded(placementId: string, options?: ShowOptions): Promise<RewardedShowResult> {
    return show(placementId, 'rewarded', options);
  },
```

Keep the existing JSDoc comments above each method and add one line to each: "With `{ loadIfNeeded: true }`, waits for a load when no ad is ready."

In `index.tsx`, add `ShowOptions` to the type exports.

- [ ] **Step 4: Run the JS checks**

Run: `npm run typecheck && npm run lint && npx jest`
Expected: all pass.

- [ ] **Step 5: Commit**

```bash
git add react-native/packages/react-native-qartvelo-ads/src
git commit -F - <<'EOF'
Add preload and loadIfNeeded to the React Native API

initialize({ preload }) loads placements at start-up and reloads them after
every show; showInterstitial and showRewarded accept { loadIfNeeded } to wait
for a load when nothing is ready, resolving { shown: false } if it fails.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
```

---

### Task 13: Expo config plugin

**Files:**
- Create: `P/plugin/src/admob.ts`, `P/plugin/src/index.ts`, `P/plugin/src/__tests__/admob.test.ts`, `P/plugin/tsconfig.json`, `P/plugin/tsconfig.build.json`, `P/plugin/skadnetwork.json`, `P/app.plugin.js`
- Modify: `P/package.json` (`files`, `scripts`, `devDependencies`, `peerDependencies`, `peerDependenciesMeta`)
- Modify: `P/tsconfig.json` (`exclude`), `P/eslint.config.mjs` (ignores, line 27), `P/.gitignore`

**Interfaces:**
- Produces: plugin entry `["@qartvelo/react-native-ads", { admob?: { androidAppId?, iosAppId?, delayAppMeasurementInit?, skAdNetworkItems? } }]`; `P/plugin/skadnetwork.json` (Google's recommended SKAdNetwork identifiers, also read by Task 15).

- [ ] **Step 1: Add the dependencies and build wiring**

Run: `npm install --save-dev expo@~55.0.19 @types/node@^22 --no-audit --no-fund`

Then edit `package.json`:
- `files`: add `"app.plugin.js"`, `"plugin/build"`, `"plugin/skadnetwork.json"` after `"react-native.config.js"`.
- `scripts`: set `"prepare": "node scripts/sync-ios-sdk.cjs && bob build && npm run build:plugin"`, `"build": "node scripts/sync-ios-sdk.cjs && bob build && npm run build:plugin"`, `"typecheck": "tsc && tsc -p plugin/tsconfig.json"`, and add `"build:plugin": "tsc -p plugin/tsconfig.build.json"`.
- `peerDependencies`: add `"expo": ">=52.0.0"`.
- add `"peerDependenciesMeta": { "expo": { "optional": true } }`.

Add `"plugin"` to `exclude` in the root `tsconfig.json` (the plugin is Node code with its own config), `'plugin/build/'` to the ignores in `eslint.config.mjs`, and the lines `plugin/build/` and `*.tgz` to `.gitignore`.

Create `plugin/tsconfig.json` (type checks sources and tests, emits nothing):

```json
{
  "compilerOptions": {
    "target": "ES2020",
    "module": "commonjs",
    "moduleResolution": "node",
    "rootDir": "src",
    "outDir": "build",
    "strict": true,
    "esModuleInterop": true,
    "skipLibCheck": true,
    "types": ["node"],
    "noEmit": true
  },
  "include": ["src"]
}
```

Create `plugin/tsconfig.build.json` (emits `plugin/build` without the tests):

```json
{
  "extends": "./tsconfig.json",
  "compilerOptions": { "noEmit": false },
  "exclude": ["src/**/__tests__"]
}
```

Create `app.plugin.js`:

```js
module.exports = require('./plugin/build');
```

Create `plugin/skadnetwork.json` (Google's recommended list):

```json
[
  "cstr6suwn9.skadnetwork", "4fzdc2evr5.skadnetwork", "2fnua5tdw4.skadnetwork", "ydx93a7ass.skadnetwork",
  "p78axxw29g.skadnetwork", "v72qych5uu.skadnetwork", "ludvb6z3bs.skadnetwork", "cp8zw746q7.skadnetwork",
  "3sh42y64q3.skadnetwork", "c6k4g5qg8m.skadnetwork", "s39g8k73mm.skadnetwork", "wg4vff78zm.skadnetwork",
  "3qy4746246.skadnetwork", "f38h382jlk.skadnetwork", "hs6bdukanm.skadnetwork", "mlmmfzh3r3.skadnetwork",
  "v4nxqhlyqp.skadnetwork", "wzmmz9fp6w.skadnetwork", "su67r6k2v3.skadnetwork", "yclnxrl5pm.skadnetwork",
  "t38b2kh725.skadnetwork", "7ug5zh24hu.skadnetwork", "gta9lk7p23.skadnetwork", "vutu7akeur.skadnetwork",
  "y5ghdn5j9k.skadnetwork", "v9wttpbfk9.skadnetwork", "n38lu8286q.skadnetwork", "47vhws6wlr.skadnetwork",
  "kbd757ywx3.skadnetwork", "9t245vhmpl.skadnetwork", "a2p9lx4jpn.skadnetwork", "22mmun2rn5.skadnetwork",
  "44jx6755aq.skadnetwork", "k674qkevps.skadnetwork", "4468km3ulz.skadnetwork", "2u9pt9hc89.skadnetwork",
  "8s468mfl3y.skadnetwork", "klf5c3l5u5.skadnetwork", "ppxm28t8ap.skadnetwork", "kbmxgpxpgc.skadnetwork",
  "uw77j35x4d.skadnetwork", "578prtvx9j.skadnetwork", "4dzt52r2t5.skadnetwork", "tl55sbb4fm.skadnetwork",
  "c3frkrj4fj.skadnetwork", "e5fvkxwrpn.skadnetwork", "8c4e2ghe7u.skadnetwork", "3rd42ekr43.skadnetwork",
  "97r2b46745.skadnetwork", "3qcr597p9d.skadnetwork"
]
```

- [ ] **Step 2: Write the failing tests**

Create `plugin/src/__tests__/admob.test.ts`:

```ts
/**
 * @jest-environment node
 */
import { describe, expect, it } from '@jest/globals';
import { AndroidConfig, type InfoPlist } from 'expo/config-plugins';
import {
  APPLICATION_ID,
  DELAY_MEASUREMENT,
  GRADLE_PROPERTY,
  PODFILE_ENV,
  addAdMobPodfileEnv,
  appIdFor,
  googleSkAdNetworkItems,
  readOptions,
  setAdMobGradleProperty,
  setAdMobInfoPlist,
  setAdMobManifest,
} from '../admob';

const ANDROID_ID = 'ca-app-pub-3940256099942544~3347511713';
const IOS_ID = 'ca-app-pub-3940256099942544~1458002511';

function manifest(metaData: Record<string, string> = {}): AndroidConfig.Manifest.AndroidManifest {
  return {
    manifest: {
      $: { 'xmlns:android': 'http://schemas.android.com/apk/res/android' },
      application: [
        {
          $: { 'android:name': '.MainApplication' },
          'meta-data': Object.entries(metaData).map(([name, value]) => ({
            $: { 'android:name': name, 'android:value': value },
          })),
        },
      ],
    },
  } as AndroidConfig.Manifest.AndroidManifest;
}

function metaData(result: AndroidConfig.Manifest.AndroidManifest): Record<string, string> {
  const items = result.manifest.application?.[0]?.['meta-data'] ?? [];
  return Object.fromEntries(items.map((item) => [item.$['android:name'], item.$['android:value'] ?? '']));
}

describe('readOptions and appIdFor', () => {
  it('accepts no admob object (core SDK only)', () => {
    expect(readOptions({})).toEqual({});
  });

  it.each([
    [null, /plugin options/],
    [{ admob: 'x' }, /"admob" must be an object/],
    [{ admob: { androidAppId: 1 } }, /admob.androidAppId must be a string/],
    [{ admob: { delayAppMeasurementInit: 'yes' } }, /delayAppMeasurementInit/],
    [{ admob: { skAdNetworkItems: [1] } }, /skAdNetworkItems/],
  ])('rejects %p', (options, message) => {
    expect(() => readOptions(options)).toThrow(message);
  });

  it('validates the App ID of the platform being built and names the key', () => {
    expect(appIdFor({ androidAppId: ` ${ANDROID_ID} ` }, 'android')).toBe(ANDROID_ID);
    expect(() => appIdFor({ androidAppId: ANDROID_ID }, 'ios')).toThrow(/admob\.iosAppId/);
    expect(() => appIdFor({ iosAppId: 'ca-app-pub-123/456' }, 'ios')).toThrow(/admob\.iosAppId/);
  });
});

describe('Android', () => {
  it('adds the App ID, and the measurement delay only when asked', () => {
    expect(metaData(setAdMobManifest(manifest(), ANDROID_ID, false))).toEqual({ [APPLICATION_ID]: ANDROID_ID });
    expect(metaData(setAdMobManifest(manifest(), ANDROID_ID, true))).toEqual({
      [APPLICATION_ID]: ANDROID_ID,
      [DELAY_MEASUREMENT]: 'true',
    });
  });

  it('is idempotent and accepts the same existing App ID', () => {
    const once = setAdMobManifest(manifest({ [APPLICATION_ID]: ANDROID_ID }), ANDROID_ID, false);
    const twice = setAdMobManifest(once, ANDROID_ID, false);
    expect(twice.manifest.application?.[0]?.['meta-data']).toHaveLength(1);
  });

  it('refuses a different existing App ID', () => {
    expect(() =>
      setAdMobManifest(manifest({ [APPLICATION_ID]: 'ca-app-pub-1111111111111111~2222222222' }), ANDROID_ID, false)
    ).toThrow(/already sets/);
  });

  it('turns the adapter on in gradle.properties exactly once', () => {
    const props: AndroidConfig.Properties.PropertiesItem[] = [
      { type: 'property', key: GRADLE_PROPERTY, value: 'false' },
      { type: 'property', key: 'newArchEnabled', value: 'true' },
    ];
    const result = setAdMobGradleProperty(setAdMobGradleProperty(props));
    expect(result.filter((item) => item.type === 'property' && item.key === GRADLE_PROPERTY)).toEqual([
      { type: 'property', key: GRADLE_PROPERTY, value: 'true' },
    ]);
    expect(result).toContainEqual({ type: 'property', key: 'newArchEnabled', value: 'true' });
  });
});

describe('iOS', () => {
  const podfile = [
    "require 'json'",
    "podfile_properties = JSON.parse(File.read(File.join(__dir__, 'Podfile.properties.json'))) rescue {}",
    '',
    "target 'App' do",
    '  config = use_native_modules!(config_command)',
    'end',
  ].join('\n');

  it('enables the adapter in the Podfile before use_native_modules!, once', () => {
    const result = addAdMobPodfileEnv(addAdMobPodfileEnv(podfile));
    expect(result.split(PODFILE_ENV)).toHaveLength(2);
    expect(result.indexOf(PODFILE_ENV)).toBeGreaterThan(result.indexOf('podfile_properties = '));
    expect(result.indexOf(PODFILE_ENV)).toBeLessThan(result.indexOf('use_native_modules!'));
  });

  it('prepends the line when the Podfile has no podfile_properties line', () => {
    expect(addAdMobPodfileEnv("target 'App' do\nend").startsWith(PODFILE_ENV)).toBe(true);
  });

  it('writes the App ID, the delay and the SKAdNetwork list without duplicates', () => {
    const plist: InfoPlist = {
      SKAdNetworkItems: [{ SKAdNetworkIdentifier: 'cstr6suwn9.skadnetwork' }, { SKAdNetworkIdentifier: 'mine.skadnetwork' }],
    };
    const result = setAdMobInfoPlist(plist, IOS_ID, true, ['example123.skadnetwork', 'mine.skadnetwork']);
    const ids = (result.SKAdNetworkItems as { SKAdNetworkIdentifier: string }[]).map((item) => item.SKAdNetworkIdentifier);
    expect(result.GADApplicationIdentifier).toBe(IOS_ID);
    expect(result.GADDelayAppMeasurementInit).toBe(true);
    expect(new Set(ids).size).toBe(ids.length);
    expect(ids).toEqual(expect.arrayContaining([...googleSkAdNetworkItems(), 'mine.skadnetwork', 'example123.skadnetwork']));
    expect(ids).toHaveLength(googleSkAdNetworkItems().length + 2);
  });

  it('accepts the same existing App ID and refuses a different one', () => {
    expect(setAdMobInfoPlist({ GADApplicationIdentifier: IOS_ID }, IOS_ID, false, []).GADApplicationIdentifier).toBe(IOS_ID);
    expect(() =>
      setAdMobInfoPlist({ GADApplicationIdentifier: 'ca-app-pub-1111111111111111~2222222222' }, IOS_ID, false, [])
    ).toThrow(/already sets/);
  });
});
```

- [ ] **Step 3: Run them to see them fail**

Run: `npx jest plugin/src`
Expected: `Cannot find module '../admob'`.

- [ ] **Step 4: Implement**

Create `plugin/src/admob.ts`:

```ts
import fs from 'fs';
import path from 'path';
import { AndroidConfig, type InfoPlist } from 'expo/config-plugins';

export const PACKAGE = '@qartvelo/react-native-ads';
export const APPLICATION_ID = 'com.google.android.gms.ads.APPLICATION_ID';
export const DELAY_MEASUREMENT = 'com.google.android.gms.ads.DELAY_APP_MEASUREMENT_INIT';
export const GRADLE_PROPERTY = 'QartveloAds_admobEnabled';
export const PODFILE_ENV = "ENV['QARTVELO_ADS_ADMOB_ENABLED'] = 'true'";

const APP_ID = /^ca-app-pub-\d{16}~\d{10}$/;

export interface AdMobOptions {
  androidAppId?: string;
  iosAppId?: string;
  delayAppMeasurementInit?: boolean;
  skAdNetworkItems?: string[];
}

export interface PluginOptions {
  admob?: AdMobOptions;
}

function fail(message: string): never {
  throw new Error(`${PACKAGE}: ${message}`);
}

/** Validates the plugin options. Without `admob`, only the core SDK is used. */
export function readOptions(options: unknown): PluginOptions {
  if (typeof options !== 'object' || options === null || Array.isArray(options)) {
    fail('plugin options must be an object like { "admob": { "androidAppId": "...", "iosAppId": "..." } }.');
  }
  const admob = (options as { admob?: unknown }).admob;
  if (admob === undefined) {
    return {};
  }
  if (typeof admob !== 'object' || admob === null || Array.isArray(admob)) {
    fail('"admob" must be an object with androidAppId and iosAppId.');
  }
  const value = admob as Record<string, unknown>;
  for (const key of ['androidAppId', 'iosAppId'] as const) {
    if (value[key] !== undefined && typeof value[key] !== 'string') {
      fail(`admob.${key} must be a string.`);
    }
  }
  if (value.delayAppMeasurementInit !== undefined && typeof value.delayAppMeasurementInit !== 'boolean') {
    fail('admob.delayAppMeasurementInit must be a boolean.');
  }
  const items = value.skAdNetworkItems;
  if (items !== undefined && (!Array.isArray(items) || items.some((item) => typeof item !== 'string'))) {
    fail('admob.skAdNetworkItems must be an array of SKAdNetwork identifiers.');
  }
  return { admob: value as AdMobOptions };
}

/** The AdMob App ID of the platform being built, validated. */
export function appIdFor(admob: AdMobOptions, platform: 'android' | 'ios'): string {
  const key = platform === 'android' ? 'androidAppId' : 'iosAppId';
  const value = (admob[key] ?? '').trim();
  if (!APP_ID.test(value)) {
    fail(`admob.${key} must be an AdMob App ID like ca-app-pub-0000000000000000~0000000000 (found "${value}").`);
  }
  return value;
}

/** Google's recommended SKAdNetwork identifiers, shipped in plugin/skadnetwork.json. */
export function googleSkAdNetworkItems(): string[] {
  return JSON.parse(fs.readFileSync(path.join(__dirname, '..', 'skadnetwork.json'), 'utf8')) as string[];
}

export function setAdMobGradleProperty(
  properties: AndroidConfig.Properties.PropertiesItem[]
): AndroidConfig.Properties.PropertiesItem[] {
  const rest = properties.filter((item) => !(item.type === 'property' && item.key === GRADLE_PROPERTY));
  return [...rest, { type: 'property', key: GRADLE_PROPERTY, value: 'true' }];
}

export function setAdMobManifest(
  manifest: AndroidConfig.Manifest.AndroidManifest,
  appId: string,
  delayAppMeasurementInit: boolean
): AndroidConfig.Manifest.AndroidManifest {
  const existing = AndroidConfig.Manifest.getMainApplicationMetaDataValue(manifest, APPLICATION_ID);
  if (existing && existing !== appId) {
    fail(`AndroidManifest.xml already sets ${APPLICATION_ID} to ${existing}, but admob.androidAppId is ${appId}. Keep one AdMob App ID.`);
  }
  const application = AndroidConfig.Manifest.getMainApplicationOrThrow(manifest);
  AndroidConfig.Manifest.addMetaDataItemToMainApplication(application, APPLICATION_ID, appId);
  if (delayAppMeasurementInit) {
    AndroidConfig.Manifest.addMetaDataItemToMainApplication(application, DELAY_MEASUREMENT, 'true');
  }
  return manifest;
}

/** Sets the Podfile ENV that RNQartveloAds.podspec reads, before use_native_modules! runs. */
export function addAdMobPodfileEnv(contents: string): string {
  if (contents.includes(PODFILE_ENV)) {
    return contents;
  }
  const line = `${PODFILE_ENV} # ${PACKAGE}: AdMob fallback`;
  const anchor = /^podfile_properties = .*$/m.exec(contents);
  if (!anchor) {
    return `${line}\n${contents}`;
  }
  const end = anchor.index + anchor[0].length;
  return `${contents.slice(0, end)}\n${line}${contents.slice(end)}`;
}

export function setAdMobInfoPlist(
  plist: InfoPlist,
  appId: string,
  delayAppMeasurementInit: boolean,
  extraSkAdNetworkItems: string[]
): InfoPlist {
  const existing = plist.GADApplicationIdentifier;
  if (typeof existing === 'string' && existing && existing !== appId) {
    fail(`Info.plist already sets GADApplicationIdentifier to ${existing}, but admob.iosAppId is ${appId}. Keep one AdMob App ID.`);
  }
  plist.GADApplicationIdentifier = appId;
  if (delayAppMeasurementInit) {
    plist.GADDelayAppMeasurementInit = true;
  }
  const items = (Array.isArray(plist.SKAdNetworkItems) ? [...plist.SKAdNetworkItems] : []) as {
    SKAdNetworkIdentifier?: unknown;
  }[];
  const known = new Set(items.map((item) => item?.SKAdNetworkIdentifier));
  for (const raw of [...googleSkAdNetworkItems(), ...extraSkAdNetworkItems]) {
    const id = raw.trim();
    if (id && !known.has(id)) {
      known.add(id);
      items.push({ SKAdNetworkIdentifier: id });
    }
  }
  (plist as Record<string, unknown>).SKAdNetworkItems = items;
  return plist;
}
```

Create `plugin/src/index.ts`:

```ts
import fs from 'fs';
import path from 'path';
import {
  type ConfigPlugin,
  createRunOncePlugin,
  withAndroidManifest,
  withGradleProperties,
  withInfoPlist,
  withPodfile,
} from 'expo/config-plugins';
import {
  type PluginOptions,
  addAdMobPodfileEnv,
  appIdFor,
  readOptions,
  setAdMobGradleProperty,
  setAdMobInfoPlist,
  setAdMobManifest,
} from './admob';

// plugin/src and plugin/build both sit two levels below the package root.
const pkg = JSON.parse(fs.readFileSync(path.join(__dirname, '..', '..', 'package.json'), 'utf8')) as {
  name: string;
  version: string;
};

/**
 * Expo config plugin of @qartvelo/react-native-ads. With { admob: { ... } } it turns on the native
 * AdMob fallback adapter and writes the AdMob App IDs, which Google Mobile Ads needs at start-up.
 * Each App ID is validated only for the platform being prebuilt.
 */
const withQartveloAds: ConfigPlugin<PluginOptions | void> = (config, rawOptions) => {
  const { admob } = readOptions(rawOptions ?? {});
  if (!admob) {
    return config;
  }
  const delay = admob.delayAppMeasurementInit === true;

  config = withGradleProperties(config, (cfg) => {
    cfg.modResults = setAdMobGradleProperty(cfg.modResults);
    return cfg;
  });
  config = withAndroidManifest(config, (cfg) => {
    cfg.modResults = setAdMobManifest(cfg.modResults, appIdFor(admob, 'android'), delay);
    return cfg;
  });
  config = withPodfile(config, (cfg) => {
    cfg.modResults.contents = addAdMobPodfileEnv(cfg.modResults.contents);
    return cfg;
  });
  config = withInfoPlist(config, (cfg) => {
    cfg.modResults = setAdMobInfoPlist(cfg.modResults, appIdFor(admob, 'ios'), delay, admob.skAdNetworkItems ?? []);
    return cfg;
  });
  return config;
};

export default createRunOncePlugin(withQartveloAds, pkg.name, pkg.version);
```

- [ ] **Step 5: Run the checks and build the plugin**

Run: `npm run typecheck && npm run lint && npx jest && npm run build:plugin && node -e "const p=require('./app.plugin.js');console.log(typeof (p.default||p))"`
Expected: all pass; prints `function`.

- [ ] **Step 6: Commit**

```bash
git add react-native/packages/react-native-qartvelo-ads/plugin react-native/packages/react-native-qartvelo-ads/app.plugin.js react-native/packages/react-native-qartvelo-ads/package.json react-native/packages/react-native-qartvelo-ads/package-lock.json react-native/packages/react-native-qartvelo-ads/tsconfig.json react-native/packages/react-native-qartvelo-ads/eslint.config.mjs react-native/packages/react-native-qartvelo-ads/.gitignore
git commit -F - <<'EOF'
Ship an Expo config plugin for the AdMob fallback

["@qartvelo/react-native-ads", { "admob": { ... } }] turns on the native
adapter and writes the AdMob App IDs, the measurement delay and Google's
SKAdNetwork list at prebuild, validating each App ID.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
```

---

### Task 14: Bare React Native on Android reads `app.json`

**Files:**
- Modify: `P/android/build.gradle` (lines 1-50 and the `android { defaultConfig }` block)
- Create: `P/android/src/admob/AndroidManifest.xml`
- Modify (example): `react-native/example/app.json`, `react-native/example/android/gradle.properties:51`, `react-native/example/android/app/src/main/AndroidManifest.xml:15-17`

**Interfaces:**
- Produces: a top-level `"@qartvelo/react-native-ads": { "admob": { "androidAppId": ... } }` in the host's `app.json` adds `com.qartvelo.ads:admob` and the App ID meta-data; `QartveloAds_useMavenLocal=true` adds Maven Local.

- [ ] **Step 1: Rewrite the top of the module's `build.gradle`**

Replace lines 1-50 of `P/android/build.gradle` (header comment through the `repositories { ... }` block) with:

```gradle
// Android side of @qartvelo/react-native-ads. Built as part of the host app (autolinking); the Android
// Gradle, Kotlin and React Native Gradle plugins come from the host's build classpath.
//
// AdMob fallback, one of:
//   - Expo: the plugin entry ["@qartvelo/react-native-ads", { "admob": { ... } }] (sets the property below)
//   - bare React Native: "@qartvelo/react-native-ads": { "admob": { "androidAppId": ... } } in app.json
//   - legacy (0.4.x): QartveloAds_admobEnabled=true here and the App ID in the app's manifest
//
// Host gradle.properties switches:
//   QartveloAds_admobEnabled=true    adds com.qartvelo.ads:admob (AdMob fallback, play-services-ads)
//   QartveloAds_sdkVersion=0.5.0     version of the QartveloAds Android SDK artifacts
//   QartveloAds_useMavenLocal=true   also resolve com.qartvelo.ads from ~/.m2 (SDK development)

import groovy.json.JsonSlurper

apply plugin: "com.android.library"
if (project.extensions.findByName("kotlin") == null) {
  apply plugin: "kotlin-android"
}
apply plugin: "com.facebook.react"

def safeExtGet(name, fallback) {
  return rootProject.ext.has(name) ? rootProject.ext.get(name) : fallback
}

def qartveloProperty(name, fallback) {
  def value = rootProject.findProperty(name)
  return value != null ? value.toString().trim() : fallback
}

// The "@qartvelo/react-native-ads" object of the host's app.json, or null.
def qartveloAppJson() {
  def file = new File(rootProject.projectDir.parentFile, "app.json")
  if (!file.isFile()) return null
  def root = new JsonSlurper().parse(file)
  if (!(root instanceof Map)) return null
  def config = root["@qartvelo/react-native-ads"]
  if (config == null) return null
  if (root.containsKey("expo")) {
    throw new GradleException("@qartvelo/react-native-ads: in an Expo project, configure the AdMob fallback with the plugin entry [\"@qartvelo/react-native-ads\", { \"admob\": { ... } }] in expo.plugins, not with a top-level \"@qartvelo/react-native-ads\" key in app.json.")
  }
  return config
}

def qartveloVersionBefore(String version, String other) {
  def parse = { String value -> value.tokenize(".-").take(3).collect { it.isInteger() ? it.toInteger() : 0 } }
  def a = parse(version)
  def b = parse(other)
  for (int i = 0; i < 3; i++) {
    def x = i < a.size() ? a[i] : 0
    def y = i < b.size() ? b[i] : 0
    if (x != y) return x < y
  }
  return false
}

def qartveloSdkVersion = qartveloProperty("QartveloAds_sdkVersion", "0.5.0")
def qartveloAdmob = qartveloAppJson()?.get("admob")
def qartveloAdmobAppId = null
if (qartveloAdmob != null) {
  qartveloAdmobAppId = (qartveloAdmob["androidAppId"] ?: "").toString().trim()
  if (!(qartveloAdmobAppId ==~ /ca-app-pub-\d{16}~\d{10}/)) {
    throw new GradleException("@qartvelo/react-native-ads: admob.androidAppId in app.json must be an AdMob App ID like ca-app-pub-0000000000000000~0000000000 (found \"${qartveloAdmobAppId}\").")
  }
}
def qartveloAdmobEnabled = qartveloProperty("QartveloAds_admobEnabled", "false").toBoolean() || qartveloAdmob != null
def qartveloDelayMeasurement = qartveloAdmob?.get("delayAppMeasurementInit") == true

repositories {
  if (qartveloProperty("QartveloAds_useMavenLocal", "false").toBoolean()) {
    // SDK development: artifacts from `./gradlew publishToMavenLocal` (android/ project). The host app
    // must list mavenLocal() too, because its runtime classpath resolves these artifacts.
    mavenLocal {
      content { includeGroup("com.qartvelo.ads") }
    }
  }
  google()
  mavenCentral()
  if (qartveloVersionBefore(qartveloSdkVersion, "0.3.4")) {
    // Versions before 0.3.4 are only on JitPack and GitHub Packages (a token with read:packages from
    // gpr.user/gpr.key or GITHUB_ACTOR/GITHUB_TOKEN); the host app must list them too.
    maven {
      url "https://jitpack.io"
      content { includeGroup("com.qartvelo.ads") }
    }
    maven {
      url "https://maven.pkg.github.com/Qartvelo-com/ads"
      credentials {
        username = (project.findProperty("gpr.user") ?: System.getenv("GITHUB_ACTOR")) ?: ""
        password = (project.findProperty("gpr.key") ?: System.getenv("GITHUB_TOKEN")) ?: ""
      }
      content { includeGroup("com.qartvelo.ads") }
    }
  }
}
```

In the `android { defaultConfig { ... } }` block, add after `consumerProguardFiles "proguard-rules.pro"`:

```gradle
    manifestPlaceholders.putAll([
      qartveloAdmobAppId: qartveloAdmobAppId ?: "",
      qartveloDelayMeasurement: qartveloDelayMeasurement ? "true" : "false",
    ])
```

and add inside `android { ... }` after `defaultConfig { ... }`:

```gradle
  if (qartveloAdmobAppId != null) {
    sourceSets {
      main {
        // app.json sets the AdMob App ID: this manifest adds it and the measurement delay flag.
        manifest.srcFile "src/admob/AndroidManifest.xml"
      }
    }
  }
```

Create `P/android/src/admob/AndroidManifest.xml`:

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
  <uses-permission android:name="android.permission.INTERNET" />
  <application>
    <!-- From "@qartvelo/react-native-ads" -> admob in the host app's app.json. -->
    <meta-data
      android:name="com.google.android.gms.ads.APPLICATION_ID"
      android:value="${qartveloAdmobAppId}" />
    <meta-data
      android:name="com.google.android.gms.ads.DELAY_APP_MEASUREMENT_INIT"
      android:value="${qartveloDelayMeasurement}" />
  </application>
</manifest>
```

- [ ] **Step 2: Move the bare example to the `app.json` config**

Replace `react-native/example/app.json` with:

```json
{
  "name": "QartveloAdsExample",
  "displayName": "QartveloAdsExample",
  "@qartvelo/react-native-ads": {
    "admob": {
      "androidAppId": "ca-app-pub-3940256099942544~3347511713",
      "iosAppId": "ca-app-pub-3940256099942544~1458002511"
    }
  }
}
```

In `react-native/example/android/gradle.properties`, replace line 51 (`QartveloAds_admobEnabled=true`) with `QartveloAds_useMavenLocal=true`. Delete the `com.google.android.gms.ads.APPLICATION_ID` `<meta-data>` element (lines 15-17) from `react-native/example/android/app/src/main/AndroidManifest.xml`.

- [ ] **Step 3: Build the example and check the merged manifest**

Run:
```bash
(cd /Users/kakha13/Developer/myAds/sdk/android && ./gradlew :qartvelo-ads-core:publishToMavenLocal :qartvelo-ads-admob:publishToMavenLocal)
cd /Users/kakha13/Developer/myAds/sdk/react-native/example/android
./gradlew :app:assembleDebug :qartvelo_react-native-ads:testDebugUnitTest
grep -rl --include=AndroidManifest.xml 'ca-app-pub-3940256099942544~3347511713' app/build/intermediates/merged_manifest* | head -1
```
Expected: BUILD SUCCESSFUL; the grep prints a merged manifest path.

- [ ] **Step 4: Check the error paths by hand**

Temporarily set `"androidAppId": "ca-app-pub-123/456"` in `example/app.json` and run `./gradlew :app:assembleDebug`: expected failure containing `admob.androidAppId in app.json must be an AdMob App ID`. Restore it. Temporarily add `"expo": {}` to `example/app.json`: expected failure containing `in an Expo project`. Restore it.

- [ ] **Step 5: Identical App ID in the host manifest still builds (Review Focus 1)**

Temporarily put the deleted `<meta-data android:name="com.google.android.gms.ads.APPLICATION_ID" android:value="ca-app-pub-3940256099942544~3347511713"/>` back into `example/android/app/src/main/AndroidManifest.xml` and run `./gradlew :app:processDebugMainManifest`: expected BUILD SUCCESSFUL (identical values merge). Change its value to `ca-app-pub-1111111111111111~2222222222`: expected failure with a manifest merger conflict on `meta-data#com.google.android.gms.ads.APPLICATION_ID`. Remove the element again.

- [ ] **Step 6: Commit**

```bash
cd /Users/kakha13/Developer/myAds/sdk
git add react-native/packages/react-native-qartvelo-ads/android/build.gradle react-native/packages/react-native-qartvelo-ads/android/src/admob react-native/example/app.json react-native/example/android/gradle.properties react-native/example/android/app/src/main/AndroidManifest.xml
git commit -F - <<'EOF'
Read the AdMob config from app.json in bare React Native Android builds

A top-level "@qartvelo/react-native-ads" key in app.json now adds the AdMob
adapter and the App ID meta-data. The module only lists Maven Central (plus
JitPack and GitHub Packages for versions before 0.3.4, and Maven Local with
QartveloAds_useMavenLocal=true). The example uses the new config.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
```

---

### Task 15: Bare React Native on iOS reads `app.json`

**Files:**
- Create: `P/scripts/ios-config.rb`, `P/scripts/test/ios_config_test.rb`
- Modify: `P/react-native.config.js`, `P/RNQartveloAds.podspec`, `P/package.json` (`files`)
- Modify (example): `react-native/example/ios/Podfile:1-2`, `react-native/example/ios/QartveloAdsExample/Info.plist:27-28`

**Interfaces:**
- Consumes: `P/plugin/skadnetwork.json` (Task 13).
- Produces: an Xcode build phase `[QartveloAds] AdMob configuration` on the app target; the podspec links the adapter when the `app.json` key has `admob`.

- [ ] **Step 1: Write the failing tests**

Create `scripts/test/ios_config_test.rb`:

```ruby
# frozen_string_literal: true

# Runs scripts/ios-config.rb against temporary projects. macOS only (PlistBuddy, plutil):
#   ruby scripts/test/ios_config_test.rb
require 'fileutils'
require 'json'
require 'minitest/autorun'
require 'open3'
require 'tmpdir'

class IosConfigTest < Minitest::Test
  SCRIPT = File.expand_path('../ios-config.rb', __dir__)
  IOS = 'ca-app-pub-3940256099942544~1458002511'
  KEY = '@qartvelo/react-native-ads'

  def setup
    @dir = Dir.mktmpdir
    @project = File.join(@dir, 'ios')
    @products = File.join(@dir, 'build')
    FileUtils.mkdir_p([@project, File.join(@products, 'App.app')])
    @plist = File.join(@products, 'App.app', 'Info.plist')
    File.write(@plist, <<~PLIST)
      <?xml version="1.0" encoding="UTF-8"?>
      <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
      <plist version="1.0"><dict><key>CFBundleName</key><string>App</string></dict></plist>
    PLIST
  end

  def teardown
    FileUtils.remove_entry(@dir)
  end

  def write_app_json(json)
    File.write(File.join(@dir, 'app.json'), JSON.generate(json))
  end

  def run_script
    env = { 'PROJECT_DIR' => @project, 'BUILT_PRODUCTS_DIR' => @products, 'INFOPLIST_PATH' => 'App.app/Info.plist' }
    Open3.capture2e(env, 'ruby', SCRIPT)
  end

  def read(key)
    output, status = Open3.capture2e('/usr/libexec/PlistBuddy', '-c', "Print :#{key}", @plist)
    status.success? ? output.strip : nil
  end

  def sk_identifiers
    json, status = Open3.capture2('/usr/bin/plutil', '-extract', 'SKAdNetworkItems', 'json', '-o', '-', @plist)
    status.success? ? JSON.parse(json).map { |item| item['SKAdNetworkIdentifier'] } : []
  end

  def test_writes_app_id_delay_and_skadnetwork_items
    write_app_json(KEY => { 'admob' => { 'iosAppId' => IOS, 'delayAppMeasurementInit' => true, 'skAdNetworkItems' => ['example123.skadnetwork'] } })
    output, status = run_script
    assert status.success?, output
    assert_equal IOS, read('GADApplicationIdentifier')
    assert_equal 'true', read('GADDelayAppMeasurementInit')
    ids = sk_identifiers
    assert_includes ids, 'cstr6suwn9.skadnetwork'
    assert_includes ids, 'example123.skadnetwork'
    assert_equal ids.uniq, ids
  end

  def test_running_twice_adds_nothing_new
    write_app_json(KEY => { 'admob' => { 'iosAppId' => IOS } })
    run_script
    first = sk_identifiers
    output, status = run_script
    assert status.success?, output
    assert_equal first, sk_identifiers
  end

  def test_same_existing_app_id_passes_and_different_one_fails
    system('/usr/libexec/PlistBuddy', '-c', "Add :GADApplicationIdentifier string #{IOS}", @plist)
    write_app_json(KEY => { 'admob' => { 'iosAppId' => IOS } })
    output, status = run_script
    assert status.success?, output
    write_app_json(KEY => { 'admob' => { 'iosAppId' => 'ca-app-pub-1111111111111111~2222222222' } })
    output, status = run_script
    refute status.success?
    assert_match(/already sets GADApplicationIdentifier/, output)
  end

  def test_invalid_app_id_fails_with_the_key_name
    write_app_json(KEY => { 'admob' => { 'iosAppId' => 'ca-app-pub-123/456' } })
    output, status = run_script
    refute status.success?
    assert_match(/admob\.iosAppId/, output)
  end

  def test_does_nothing_without_app_json_or_the_key
    _, status = run_script
    assert status.success?
    write_app_json('name' => 'App')
    _, status = run_script
    assert status.success?
    assert_nil read('GADApplicationIdentifier')
  end

  def test_expo_projects_without_the_key_are_left_alone
    write_app_json('expo' => { 'name' => 'App', 'plugins' => [[KEY, { 'admob' => { 'iosAppId' => IOS } }]] })
    _, status = run_script
    assert status.success?
    assert_nil read('GADApplicationIdentifier')
  end

  def test_expo_projects_with_the_top_level_key_fail
    write_app_json('expo' => { 'name' => 'App' }, KEY => { 'admob' => { 'iosAppId' => IOS } })
    output, status = run_script
    refute status.success?
    assert_match(/expo\.plugins/, output)
  end
end
```

- [ ] **Step 2: Run them to see them fail**

Run: `ruby scripts/test/ios_config_test.rb`
Expected: errors, the script file does not exist.

- [ ] **Step 3: Implement the build phase script**

Create `scripts/ios-config.rb`:

```ruby
# frozen_string_literal: true

# Xcode build phase of @qartvelo/react-native-ads, added to the app target by react-native.config.js.
# Bare React Native apps configure the AdMob fallback in app.json:
#   "@qartvelo/react-native-ads": { "admob": { "iosAppId": "ca-app-pub-...~...", ... } }
# This writes GADApplicationIdentifier, GADDelayAppMeasurementInit and SKAdNetworkItems into the
# built app's Info.plist on every build. Expo apps use the config plugin instead: there this script
# does nothing unless the top-level key is present, which is an error.
require 'json'
require 'open3'

PACKAGE = '@qartvelo/react-native-ads'
APP_ID = /\Aca-app-pub-\d{16}~\d{10}\z/.freeze
PLIST_BUDDY = '/usr/libexec/PlistBuddy'

def fail_build(message)
  warn "error: #{PACKAGE}: #{message}"
  exit 1
end

def plist_buddy(plist, command)
  output, status = Open3.capture2e(PLIST_BUDDY, '-c', command, plist)
  [output.strip, status.success?]
end

app_json = File.expand_path(File.join(ENV.fetch('PROJECT_DIR'), '..', 'app.json'))
exit 0 unless File.file?(app_json)

root = JSON.parse(File.read(app_json))
config = root.is_a?(Hash) ? root[PACKAGE] : nil
exit 0 if config.nil?
if root.key?('expo')
  fail_build("in an Expo project, configure the AdMob fallback with the plugin entry in expo.plugins, not with a top-level \"#{PACKAGE}\" key in app.json.")
end
admob = config.is_a?(Hash) ? config['admob'] : nil
exit 0 if admob.nil?
fail_build('"admob" in app.json must be an object.') unless admob.is_a?(Hash)

app_id = admob['iosAppId'].to_s.strip
unless app_id =~ APP_ID
  fail_build("admob.iosAppId in app.json must be an AdMob App ID like ca-app-pub-0000000000000000~0000000000 (found \"#{app_id}\").")
end

plist = File.join(ENV.fetch('BUILT_PRODUCTS_DIR'), ENV.fetch('INFOPLIST_PATH'))
existing, found = plist_buddy(plist, 'Print :GADApplicationIdentifier')
if found && !existing.empty? && existing != app_id
  fail_build("Info.plist already sets GADApplicationIdentifier to #{existing}, but admob.iosAppId in app.json is #{app_id}. Keep one AdMob App ID.")
end
plist_buddy(plist, 'Delete :GADApplicationIdentifier')
plist_buddy(plist, "Add :GADApplicationIdentifier string #{app_id}")
if admob['delayAppMeasurementInit'] == true
  plist_buddy(plist, 'Delete :GADDelayAppMeasurementInit')
  plist_buddy(plist, 'Add :GADDelayAppMeasurementInit bool true')
end

google = JSON.parse(File.read(File.join(__dir__, '..', 'plugin', 'skadnetwork.json')))
extra = Array(admob['skAdNetworkItems']).map { |id| id.to_s.strip }.reject(&:empty?)
json, status = Open3.capture2('/usr/bin/plutil', '-extract', 'SKAdNetworkItems', 'json', '-o', '-', plist)
existing_items = status.success? ? JSON.parse(json) : []
plist_buddy(plist, 'Add :SKAdNetworkItems array') unless status.success?
current = existing_items.map { |item| item.is_a?(Hash) ? item['SKAdNetworkIdentifier'] : nil }.compact
missing = (google + extra).uniq - current
missing.each_with_index do |id, offset|
  index = existing_items.length + offset
  plist_buddy(plist, "Add :SKAdNetworkItems:#{index} dict")
  plist_buddy(plist, "Add :SKAdNetworkItems:#{index}:SKAdNetworkIdentifier string #{id}")
end
puts "note: #{PACKAGE}: AdMob App ID and #{missing.length} new SKAdNetwork identifiers written to Info.plist"
```

- [ ] **Step 4: Run the script tests**

Run: `ruby scripts/test/ios_config_test.rb`
Expected: `7 runs, ... 0 failures, 0 errors`.

- [ ] **Step 5: Wire the build phase and the podspec**

Replace `P/react-native.config.js` with:

```js
const path = require('path');

/**
 * Android and iOS autolink their native implementations. On iOS, a build phase writes the AdMob keys
 * from the app's app.json ("@qartvelo/react-native-ads" -> admob) into the built Info.plist; it does
 * nothing for apps without that key (Expo apps use the config plugin).
 */
module.exports = {
  dependency: {
    platforms: {
      ios: {
        scriptPhases: [
          {
            name: '[QartveloAds] AdMob configuration',
            script: `ruby "${path.join(__dirname, 'scripts', 'ios-config.rb')}"`,
            execution_position: 'after_compile',
            input_files: ['$(BUILT_PRODUCTS_DIR)/$(INFOPLIST_PATH)'],
          },
        ],
      },
    },
  },
};
```

In `RNQartveloAds.podspec`, add after `package = JSON.parse(...)`:

```ruby
# The AdMob fallback adapter is linked when the app's app.json has
# "@qartvelo/react-native-ads": { "admob": { ... } } (bare React Native), or with
# ENV['QARTVELO_ADS_ADMOB_ENABLED'] = 'true' in the Podfile (written by the Expo config plugin, or set
# by hand in 0.4.x setups).
def qartvelo_app_json_admob
  path = File.join(Pod::Config.instance.installation_root.to_s, '..', 'app.json')
  return nil unless File.file?(path)
  json = JSON.parse(File.read(path))
  config = json.is_a?(Hash) ? json['@qartvelo/react-native-ads'] : nil
  config.is_a?(Hash) ? config['admob'] : nil
rescue StandardError
  nil
end

qartvelo_admob_enabled = ENV['QARTVELO_ADS_ADMOB_ENABLED'] == 'true' || !qartvelo_app_json_admob.nil?
```

and replace `if ENV['QARTVELO_ADS_ADMOB_ENABLED'] == 'true'` with `if qartvelo_admob_enabled`.

In `package.json` `files`, add `"scripts/ios-config.rb"` and `"!scripts/test"`.

- [ ] **Step 6: Move the bare example's iOS setup to `app.json` and build it**

Delete lines 1-2 of `react-native/example/ios/Podfile` (the comment and `ENV['QARTVELO_ADS_ADMOB_ENABLED'] ||= 'true'`). Delete the `GADApplicationIdentifier` key and string (lines 27-28) from `react-native/example/ios/QartveloAdsExample/Info.plist`.

Run:
```bash
cd /Users/kakha13/Developer/myAds/sdk/react-native/example
npm install --no-audit --no-fund
(cd ios && pod install)
grep -q "Google-Mobile-Ads-SDK" ios/Podfile.lock && echo "adapter linked"
xcodebuild -workspace ios/QartveloAdsExample.xcworkspace -scheme QartveloAdsExample -configuration Debug -destination 'generic/platform=iOS Simulator' -derivedDataPath qa-out-local CODE_SIGNING_ALLOWED=NO build | tail -3
/usr/libexec/PlistBuddy -c 'Print :GADApplicationIdentifier' qa-out-local/Build/Products/Debug-iphonesimulator/QartveloAdsExample.app/Info.plist
```
Expected: `adapter linked`, `** BUILD SUCCEEDED **`, and `ca-app-pub-3940256099942544~1458002511`.

- [ ] **Step 7: Commit**

```bash
cd /Users/kakha13/Developer/myAds/sdk
git add react-native/packages/react-native-qartvelo-ads/scripts react-native/packages/react-native-qartvelo-ads/react-native.config.js react-native/packages/react-native-qartvelo-ads/RNQartveloAds.podspec react-native/packages/react-native-qartvelo-ads/package.json react-native/example/ios/Podfile react-native/example/ios/QartveloAdsExample/Info.plist react-native/example/ios/Podfile.lock
git commit -F - <<'EOF'
Read the AdMob config from app.json in bare React Native iOS builds

The podspec links the adapter when app.json has the "@qartvelo/react-native-ads"
admob object, and a build phase writes the App ID, the measurement delay and
the SKAdNetwork list into the built Info.plist. The example uses it.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
```

(Skip `Podfile.lock` in `git add` if the example does not track it.)

---

### Task 16: Expo example and CI

**Files:**
- Create: `react-native/example-expo/package.json`, `react-native/example-expo/app.json`, `react-native/example-expo/index.ts`, `react-native/example-expo/App.tsx`, `react-native/example-expo/tsconfig.json`, `react-native/example-expo/.gitignore`, `react-native/ci/maven-local.gradle`
- Modify: `.github/workflows/ci.yml`

**Interfaces:**
- Consumes: the plugin (Task 13), the bare readers (Tasks 14-15), the POM check (Task 4).

- [ ] **Step 1: Create the Expo example**

`react-native/example-expo/package.json`:

```json
{
  "name": "qartvelo-ads-expo-example",
  "version": "1.0.0",
  "private": true,
  "main": "index.ts",
  "scripts": {
    "android": "expo run:android",
    "ios": "expo run:ios",
    "prebuild": "expo prebuild --no-install"
  },
  "dependencies": {
    "@qartvelo/react-native-ads": "file:../packages/react-native-qartvelo-ads/qartvelo-react-native-ads-0.5.0.tgz",
    "expo": "~55.0.19",
    "react": "19.2.0",
    "react-native": "0.83.6"
  },
  "devDependencies": {
    "@types/react": "~19.2.10",
    "typescript": "~5.9.2"
  }
}
```

`react-native/example-expo/app.json`:

```json
{
  "expo": {
    "name": "QartveloAdsExpoExample",
    "slug": "qartvelo-ads-expo-example",
    "version": "1.0.0",
    "orientation": "portrait",
    "ios": { "bundleIdentifier": "com.qartvelo.example.expo" },
    "android": { "package": "com.qartvelo.example.expo" },
    "plugins": [
      [
        "@qartvelo/react-native-ads",
        {
          "admob": {
            "androidAppId": "ca-app-pub-3940256099942544~3347511713",
            "iosAppId": "ca-app-pub-3940256099942544~1458002511",
            "delayAppMeasurementInit": true
          }
        }
      ]
    ]
  }
}
```

`react-native/example-expo/index.ts`:

```ts
import { registerRootComponent } from 'expo';
import App from './App';

registerRootComponent(App);
```

`react-native/example-expo/App.tsx`:

```tsx
import { useEffect } from 'react';
import { Button, SafeAreaView, StyleSheet, Text } from 'react-native';
import { QartveloAds, QartveloAdsBanner } from '@qartvelo/react-native-ads';

// Replace with the app keys and placement codes of your apps in the Qartvelo Ads dashboard.
const APP_KEY = { android: 'app_android_key_from_dashboard', ios: 'app_ios_key_from_dashboard' };

export default function App() {
  useEffect(() => {
    QartveloAds.initialize({
      appKey: APP_KEY,
      testMode: true,
      preload: { interstitial: ['game_end'], rewarded: ['reward_coins'] },
    }).catch(() => {
      // Not fatal: the SDK keeps working on cached config and the AdMob fallback.
    });
  }, []);

  return (
    <SafeAreaView style={styles.root}>
      <Text style={styles.title}>Qartvelo Ads Expo example</Text>
      <Button
        title="Show a reward video"
        onPress={() => {
          QartveloAds.showRewarded('reward_coins', { loadIfNeeded: true }).catch(() => {});
        }}
      />
      <QartveloAdsBanner placementId="home_banner" style={styles.banner} />
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1, justifyContent: 'space-between' },
  title: { margin: 16, fontSize: 18 },
  banner: { width: '100%' },
});
```

`react-native/example-expo/tsconfig.json`:

```json
{
  "extends": "expo/tsconfig.base",
  "compilerOptions": { "strict": true }
}
```

`react-native/example-expo/.gitignore` (the example installs the package from a freshly packed tarball, exactly as users install it from npm, so it keeps no lock file):

```
node_modules/
android/
ios/
.expo/
build/
package-lock.json
```

`react-native/ci/maven-local.gradle`:

```groovy
// CI only, until the SDK version under test is on Maven Central: resolve com.qartvelo.ads from ~/.m2,
// where the job published it. Pass with ./gradlew -I <this file>.
allprojects {
  repositories {
    mavenLocal {
      content { includeGroup("com.qartvelo.ads") }
    }
  }
}
```

- [ ] **Step 2: Prebuild and build the Expo example locally**

Run:
```bash
cd /Users/kakha13/Developer/myAds/sdk/react-native/packages/react-native-qartvelo-ads && npm pack
(cd /Users/kakha13/Developer/myAds/sdk/android && ./gradlew :qartvelo-ads-core:publishToMavenLocal :qartvelo-ads-admob:publishToMavenLocal)
cd /Users/kakha13/Developer/myAds/sdk/react-native/example-expo
npm install --no-audit --no-fund
LANG=en_US.UTF-8 npx expo prebuild --no-install
grep -q 'ca-app-pub-3940256099942544~3347511713' android/app/src/main/AndroidManifest.xml && echo "android app id"
grep -q "QARTVELO_ADS_ADMOB_ENABLED" ios/Podfile && echo "podfile env"
(cd android && ./gradlew -I ../../ci/maven-local.gradle :app:assembleDebug)
(cd ios && LANG=en_US.UTF-8 pod install)
xcodebuild -workspace ios/QartveloAdsExpoExample.xcworkspace -scheme QartveloAdsExpoExample -configuration Debug -destination 'generic/platform=iOS Simulator' -derivedDataPath build CODE_SIGNING_ALLOWED=NO build | tail -3
```
Expected: `android app id`, `podfile env`, Android BUILD SUCCESSFUL (React Native 0.83, Kotlin 2.1.20, AdMob adapter), `** BUILD SUCCEEDED **`.

- [ ] **Step 3: Update CI**

In `.github/workflows/ci.yml`:

1. Job `react-native`: add `- run: npm run build:plugin` after `- run: npx bob build`.

2. Job `react-native-ios`: delete the job-level `env: QARTVELO_ADS_ADMOB_ENABLED: ${{ matrix.admob }}`; add before `Install iOS dependencies`:

```yaml
      - name: Core-only variant drops the AdMob config from app.json
        if: matrix.admob == 'false'
        run: node -e "const fs=require('fs');const f='react-native/example/app.json';const j=JSON.parse(fs.readFileSync(f,'utf8'));delete j['@qartvelo/react-native-ads'];fs.writeFileSync(f,JSON.stringify(j,null,2))"
```

and after `Build React Native iOS example`:

```yaml
      - name: The App ID from app.json reaches the built Info.plist
        if: matrix.admob == 'true'
        run: |
          plist=$(find react-native/example/qa-out-ci/build/Build/Products -path '*QartveloAdsExample.app/Info.plist' | head -1)
          test "$(/usr/libexec/PlistBuddy -c 'Print :GADApplicationIdentifier' "$plist")" = "ca-app-pub-3940256099942544~1458002511"
      - name: ios-config.rb tests
        run: ruby react-native/packages/react-native-qartvelo-ads/scripts/test/ios_config_test.rb
```

3. Add two jobs:

```yaml
  react-native-android:
    runs-on: ubuntu-latest
    timeout-minutes: 40
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: 17
      - uses: actions/setup-node@v4
        with:
          node-version: 22
      - uses: gradle/actions/setup-gradle@v4
      - name: Publish the Android SDK under test to Maven Local
        working-directory: android
        run: ./gradlew :qartvelo-ads-core:publishToMavenLocal :qartvelo-ads-admob:publishToMavenLocal
      - name: Install the package and the bare example
        run: |
          cd react-native/packages/react-native-qartvelo-ads && npm ci --no-audit --no-fund
          cd ../../example && npm ci --no-audit --no-fund
      - name: Bridge unit tests and bare example build (app.json AdMob config)
        working-directory: react-native/example/android
        run: ./gradlew -I ../../ci/maven-local.gradle :qartvelo_react-native-ads:testDebugUnitTest :app:assembleDebug
      - name: The App ID from app.json is in the merged manifest
        working-directory: react-native/example/android
        run: grep -rl --include=AndroidManifest.xml 'ca-app-pub-3940256099942544~3347511713' app/build/intermediates/merged_manifest* | grep -q .

  expo-example:
    runs-on: ${{ matrix.os }}
    timeout-minutes: 45
    strategy:
      matrix:
        include:
          - os: ubuntu-latest
            platform: android
          - os: macos-15
            platform: ios
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
        with:
          node-version: 22
      - if: matrix.platform == 'android'
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: 17
      - if: matrix.platform == 'android'
        uses: gradle/actions/setup-gradle@v4
      - name: Publish the Android SDK under test to Maven Local
        if: matrix.platform == 'android'
        working-directory: android
        run: ./gradlew :qartvelo-ads-core:publishToMavenLocal :qartvelo-ads-admob:publishToMavenLocal
      - name: Pack the package and install it in the Expo example
        run: |
          cd react-native/packages/react-native-qartvelo-ads && npm ci --no-audit --no-fund && npm pack
          cd ../../example-expo && npm install --no-audit --no-fund
      - name: Prebuild with the config plugin
        working-directory: react-native/example-expo
        run: npx expo prebuild --platform ${{ matrix.platform }} --no-install
      - name: Android build (Expo SDK 55, Kotlin 2.1, AdMob adapter)
        if: matrix.platform == 'android'
        working-directory: react-native/example-expo
        run: |
          grep -q 'ca-app-pub-3940256099942544~3347511713' android/app/src/main/AndroidManifest.xml
          grep -q 'QartveloAds_admobEnabled=true' android/gradle.properties
          cd android && ./gradlew -I ../../ci/maven-local.gradle :app:assembleDebug -PreactNativeArchitectures=x86_64
      - name: iOS build
        if: matrix.platform == 'ios'
        working-directory: react-native/example-expo
        run: |
          set -euo pipefail
          grep -q "QARTVELO_ADS_ADMOB_ENABLED" ios/Podfile
          test "$(/usr/libexec/PlistBuddy -c 'Print :GADApplicationIdentifier' ios/QartveloAdsExpoExample/Info.plist)" = "ca-app-pub-3940256099942544~1458002511"
          cd ios && pod install && cd ..
          xcodebuild -workspace ios/QartveloAdsExpoExample.xcworkspace -scheme QartveloAdsExpoExample -configuration Debug -destination 'generic/platform=iOS Simulator' -derivedDataPath build CODE_SIGNING_ALLOWED=NO build | tee expo-ios-build.log
          grep -q 'BUILD SUCCEEDED' expo-ios-build.log
```

- [ ] **Step 4: Validate the workflow file**

Run: `python3 -c "import yaml,sys;yaml.safe_load(open('.github/workflows/ci.yml'));print('ok')"`
Expected: `ok`.

- [ ] **Step 5: Commit**

```bash
git add react-native/example-expo react-native/ci .github/workflows/ci.yml
git commit -F - <<'EOF'
Add an Expo SDK 55 example and build both examples in CI

The Expo example uses the config plugin and builds on React Native 0.83
(Kotlin 2.1) with the AdMob adapter, guarding the compile-scope problem.
CI also builds the bare example from its app.json config, checks the App
IDs land in the built apps, and runs the ios-config.rb tests.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
```

---

### Task 17: Documentation and changelog

**Files:**
- Modify: `developers/src/content/docs/react-native/installation.md` (sections "Optional iOS AdMob fallback" and "3. Enable the AdMob fallback (optional)")
- Modify: `developers/src/content/docs/react-native/usage.md` (options table, new sections)
- Modify: `developers/src/content/docs/react-native/api-reference.md` (`QartveloAdsInitOptions`, show signatures, events)
- Modify: `developers/src/content/docs/react-native/troubleshooting.md`
- Modify: `developers/src/content/docs/resources/changelog.md`
- Modify: `react-native/packages/react-native-qartvelo-ads/README.md` (section "iOS installation")
- Modify: every other file under `developers/` that mentions `QartveloAds_admobEnabled` or `QARTVELO_ADS_ADMOB_ENABLED`

- [ ] **Step 1: Installation page**

In `installation.md`, replace the "Optional iOS AdMob fallback" subsection and the whole "3. Enable the AdMob fallback (optional)" section with:

````markdown
## 3. AdMob fallback (optional)

When Qartvelo Ads has no ad, the SDK can show an ad from your own AdMob account. Configure it in one
place; the App IDs (with `~`) come from your AdMob apps.

### Expo

Add the plugin to `app.json` and run `npx expo prebuild` (or build with EAS):

```json
{
  "expo": {
    "plugins": [
      [
        "@qartvelo/react-native-ads",
        {
          "admob": {
            "androidAppId": "ca-app-pub-xxxxxxxxxxxxxxxx~yyyyyyyyyy",
            "iosAppId": "ca-app-pub-xxxxxxxxxxxxxxxx~zzzzzzzzzz",
            "delayAppMeasurementInit": true
          }
        }
      ]
    ]
  }
}
```

The plugin turns on the native adapter on both platforms, writes the App IDs to `AndroidManifest.xml`
and `Info.plist`, and adds Google's recommended SKAdNetwork identifiers (`skAdNetworkItems` adds
more). An invalid App ID stops the prebuild with a message naming the key.

### Bare React Native

Put the same object under a top-level key in your app's `app.json`:

```json
{
  "name": "MyApp",
  "@qartvelo/react-native-ads": {
    "admob": {
      "androidAppId": "ca-app-pub-xxxxxxxxxxxxxxxx~yyyyyyyyyy",
      "iosAppId": "ca-app-pub-xxxxxxxxxxxxxxxx~zzzzzzzzzz"
    }
  }
}
```

Then run `pod install` and rebuild. The Android build adds the adapter and the App ID; on iOS the
podspec adds the adapter and a build phase writes the App ID and SKAdNetwork list into the built
`Info.plist` on every build.

### Legacy setup (0.4.x)

`QartveloAds_admobEnabled=true` in `android/gradle.properties` and
`ENV['QARTVELO_ADS_ADMOB_ENABLED'] = 'true'` in the Podfile still work, with the App IDs set in your
own `AndroidManifest.xml` and `Info.plist`.

In development, debuggable Android builds use Google's test units for the fallback
(`admobTestUnitsInDebugBuilds`, default true); every iOS install outside the App Store does too.
````

- [ ] **Step 2: Usage and API reference**

In `usage.md`, change the init example to `appKey: { android: 'app_...', ios: 'app_...' }`, add rows to the options table:

```markdown
| `appKey` | `string \| { android, ios }` | required | App key, or one per platform (a key only works on its own platform) |
| `admobAdUnits` | `Record<string, string \| { android, ios }>` | `{}` | Placement code to AdMob unit id, or one per platform; overrides the dashboard |
| `admobTestUnitsInDebugBuilds` | `boolean` | `true` | Google's test units for the AdMob fallback in debuggable Android builds; ignored on iOS |
| `preload` | `{ interstitial?: string[]; rewarded?: string[] }` | none | Load these placements at start-up and again after each show |
```

(replace the existing `appKey` and `admobAdUnits` rows), and add these sections after "Rewarded":

````markdown
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

## Setup issues

In development, the SDK prints setup problems once with `console.warn` (they show in LogBox): an app
key registered for another package or platform, or a placement code that the dashboard does not have
(or has with another format). Listen for them yourself with
`QartveloAds.addListener('setupIssue', (issue) => ...)`; `issue.code` is `package_mismatch`,
`platform_mismatch`, `unknown_placement` or `format_mismatch`.
````

In `api-reference.md`, update `QartveloAdsInitOptions` to:

```ts
interface PerPlatform<T> { android?: T; ios?: T }

interface QartveloAdsInitOptions {
  appKey: string | PerPlatform<string>;
  requestTimeoutMs?: number;
  testMode?: boolean;
  testForceNoFill?: boolean;
  testModeInDebugBuilds?: boolean; // default true: test mode in debuggable Android builds
  admobFallback?: boolean;
  admobTestUnitsInDebugBuilds?: boolean; // default true: Google's test units in debuggable Android builds
  logLevel?: LogLevel;
  baseUrl?: string;
  admobAdUnits?: Record<string, string | PerPlatform<string>>;
  preload?: { interstitial?: string[]; rewarded?: string[] };
}

interface ShowOptions { loadIfNeeded?: boolean }
```

change the method rows to `showInterstitial(placementId, options?: ShowOptions)` and `showRewarded(placementId, options?: ShowOptions)`, and add the event row `setupIssue` with payload `{ code: SetupIssueCode; message: string; placementId?: string }`.

- [ ] **Step 3: Troubleshooting**

Add to `troubleshooting.md`:

```markdown
## Setup warnings

| Warning code | Meaning | Fix |
|---|---|---|
| `package_mismatch` | The app key is registered for another package name or bundle ID | Use the key of the app registered for this package, or correct the package in the dashboard |
| `platform_mismatch` | The app key belongs to the app of the other platform | Register an app per platform and pass `appKey: { android, ios }` |
| `unknown_placement` | The placement code does not exist for this app | Create it in the dashboard with the format named in the warning |
| `format_mismatch` | The placement exists with another format | Use a placement of the right format, or change its format |
| Build error naming `admob.androidAppId` or `admob.iosAppId` | The AdMob App ID is missing or not an App ID (it must contain `~`) | Copy the App ID from AdMob, not an ad unit id (`/`) |
| Build error "in an Expo project" | A top-level `"@qartvelo/react-native-ads"` key in an Expo `app.json` | Move it into the plugin entry in `expo.plugins` |
| Build error "already sets ... AdMob App ID" | The app already declares a different AdMob App ID | Keep one App ID, in the Qartvelo config |
```

- [ ] **Step 4: Changelog**

In `changelog.md`, replace `## Unreleased` with `## 0.5.0` and add above the existing banner entry:

```markdown
- **One-place AdMob setup for React Native**: an Expo config plugin
  (`["@qartvelo/react-native-ads", { "admob": { ... } }]`) and, for bare React Native, the same object
  under `"@qartvelo/react-native-ads"` in `app.json`. Both enable the adapter, write the App IDs and
  Google's SKAdNetwork list, and validate the App IDs. The 0.4.x flags keep working.
- **Per-platform options**: `appKey` and `admobAdUnits` values accept `{ android, ios }`.
- **`preload` and `loadIfNeeded`** in the React Native API.
- **Setup issues**: a wrong package or platform for the app key, and placement codes the dashboard
  does not have, are reported once (`onSetupIssue` on Android, `qartveloAdsDidReportSetupIssue` on
  iOS, the `setupIssue` event and a development warning in React Native). Requires the backend's new
  `error.details`.
- **AdMob test units in debug builds**: `admobTestUnitsInDebugBuilds` (default true) on Android.
- **Packaging**: `com.qartvelo.ads:admob` brings `play-services-ads` at runtime scope, so React Native
  0.83 (Kotlin 2.1) builds with the adapter; the React Native module no longer adds JitPack and
  GitHub Packages for 0.3.4 and later.
```

- [ ] **Step 5: README and remaining mentions**

In the package `README.md`, replace the paragraph starting "For the optional AdMob fallback, set `ENV['QARTVELO_ADS_ADMOB_ENABLED'] = 'true'`" with: "For the optional AdMob fallback, add the Expo plugin entry or the `app.json` key described at [developers.qartvelo.com/react-native/installation](https://developers.qartvelo.com/react-native/installation/); no Podfile or Gradle edits are needed."

Run `grep -rln "QartveloAds_admobEnabled\|QARTVELO_ADS_ADMOB_ENABLED" developers --include=*.md --include=*.mdx --include=*.js --include=*.mjs | grep -v node_modules` and, in each file found other than `installation.md` and `changelog.md`, replace the flag instructions with a pointer to the plugin entry (Expo) or the `app.json` key (bare React Native), keeping at most one sentence that names the legacy flag.

- [ ] **Step 6: Build the docs and check dashes**

Run: `cd developers && npm run build` then `git diff | grep -nE '^\+.*[—–]' && echo "dash found" || echo "no dashes"`
Expected: docs build succeeds; prints `no dashes`.

- [ ] **Step 7: Commit**

```bash
cd /Users/kakha13/Developer/myAds/sdk
git add developers react-native/packages/react-native-qartvelo-ads/README.md
git commit -F - <<'EOF'
Document the one-place React Native setup for 0.5.0

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
```

---

### Task 18: Migrate Truth or Dare (after 0.5.0 is published)

Repository: `/Users/kakha13/Developer/truth-or-dare-expo`. Start only after the maintainer has published `com.qartvelo.ads` 0.5.0 to Maven Central and `@qartvelo/react-native-ads` 0.5.0 to npm, and after the session's uncommitted Truth or Dare changes are committed.

**Files:**
- Modify: `package.json`, `package-lock.json`, `app.json`
- Delete: `plugins/withQartveloAdsAdMob.js`
- Modify: `src/config/ads.ts`, `src/utils/qartveloAds.ts`, `README.md`, `CLAUDE.md`

- [ ] **Step 1: Upgrade and swap the plugin**

Run: `npm install @qartvelo/react-native-ads@^0.5.0`. In `app.json`, replace the plugin name `"./plugins/withQartveloAdsAdMob"` with `"@qartvelo/react-native-ads"`, wrap its options in `"admob": { ... }` (keep `androidAppId`, `iosAppId`, `delayAppMeasurementInit`, `skAdNetworkItems`; the SKAdNetwork list may be deleted, the package ships it), and change `expo.version` from `6.8` to `6.9` so OTA updates never reach older binaries. Delete `plugins/withQartveloAdsAdMob.js`.

- [ ] **Step 2: Replace `src/config/ads.ts`**

```ts
// Qartvelo Ads (https://developers.qartvelo.com) serves every ad on Android and iOS. When it has no
// ad, its native AdMob adapter shows the AdMob units below instead (Google's test units in
// development). The AdMob App IDs are set by the "@qartvelo/react-native-ads" entry in app.json.
// Each platform is a separate app in the dashboard with its own key.
export const qartveloAds = {
  appKey: { android: 'app_dYWiPE5Pp2vvc0fuHkYi3ggo', ios: 'app_Umngj0Wlo7OLMYVsP5uA2vRm' },
  testMode: false,
  // Placement codes created for both apps in the Qartvelo Ads publisher dashboard.
  placements: {
    banner: 'home_banner',
    interstitial: 'game_interstitial',
    rewarded: 'new_question_reward',
  },
  admobAdUnits: {
    home_banner: { android: 'ca-app-pub-3586067558914257/3418271126', ios: 'ca-app-pub-3586067558914257/4589119450' },
    game_interstitial: { android: 'ca-app-pub-3586067558914257/7089131122', ios: 'ca-app-pub-3586067558914257/4440590427' },
    new_question_reward: { android: 'ca-app-pub-3586067558914257/7991982305', ios: 'ca-app-pub-3586067558914257/5242173026' },
  },
};
```

- [ ] **Step 3: Replace `src/utils/qartveloAds.ts`**

```ts
import { QartveloAds } from '@qartvelo/react-native-ads';
import { qartveloAds } from '../config/ads';

// False on web and on binaries without the native module (an OTA update to an older build).
export const isQartveloAdsEnabled = (): boolean => QartveloAds.isSupported();

let initialized = false;

export const initQartveloAds = () => {
  if (initialized || !isQartveloAdsEnabled()) return;
  initialized = true;
  QartveloAds.initialize({
    appKey: qartveloAds.appKey,
    testMode: qartveloAds.testMode,
    // Keep the app's explicit test-mode toggle in control in debug builds too.
    testModeInDebugBuilds: qartveloAds.testMode,
    admobAdUnits: qartveloAds.admobAdUnits,
    logLevel: __DEV__ ? 'debug' : 'error',
    preload: {
      interstitial: [qartveloAds.placements.interstitial],
      rewarded: [qartveloAds.placements.rewarded],
    },
  }).catch((error) => console.warn('Qartvelo Ads initialization failed:', error));
};

/** Shows a preloaded interstitial if one is ready; never waits. */
export const showQartveloInterstitial = async (): Promise<boolean> => {
  if (!isQartveloAdsEnabled()) return false;
  try {
    return (await QartveloAds.showInterstitial(qartveloAds.placements.interstitial)).shown;
  } catch {
    return false;
  }
};

/** Shows a reward video, loading one first if needed. True only after a confirmed completion. */
export const showQartveloRewarded = async (): Promise<boolean> => {
  if (!isQartveloAdsEnabled()) return false;
  try {
    return (await QartveloAds.showRewarded(qartveloAds.placements.rewarded, { loadIfNeeded: true })).rewarded;
  } catch {
    return false;
  }
};
```

- [ ] **Step 4: Typecheck, prebuild clean and run on both platforms**

Run:
```bash
npx tsc --noEmit -p .
LANG=en_US.UTF-8 npx expo prebuild --clean
LANG=en_US.UTF-8 npx expo run:ios --port 8082
npx expo run:android --device Pixel_9a --port 8082
```
Expected: no type errors; both apps start; the AdMob App ID is in `android/app/src/main/AndroidManifest.xml` and `ios/TruthorDare/Info.plist`; with `testForceNoFill: true` set temporarily in `initQartveloAds`, the AdMob test banner is about 64 dp tall on Android; no setup warning in the Metro log (remove `testForceNoFill` afterwards).

- [ ] **Step 5: Update docs and commit**

In `README.md` and `CLAUDE.md`, replace the mentions of `plugins/withQartveloAdsAdMob.js` with "the `@qartvelo/react-native-ads` plugin entry in `app.json`". Then:

```bash
git add -A package.json package-lock.json app.json plugins src/config/ads.ts src/utils/qartveloAds.ts README.md CLAUDE.md
git commit -F - <<'EOF'
Use @qartvelo/react-native-ads 0.5.0 one-place setup

The package's config plugin replaces the app's own AdMob plugin and its
Kotlin workaround; per-platform options, preload and loadIfNeeded replace
the hand-written helpers. Runtime version 6.9.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
```
