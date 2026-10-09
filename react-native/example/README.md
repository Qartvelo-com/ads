# Qartvelo Ads React Native example

Android demo of `@qartvelo/react-native-ads` (package `com.qartvelo.rnexample`, React Native 0.87.1). It
consumes the local package through `file:../packages/react-native-qartvelo-ads` and shows:

- initialization with app key `app_demo_rn_example_0001` and an editable base URL
  (default `http://10.0.2.2:8000/`, the host machine from the Android emulator);
- a `home_banner` banner, with buttons to unmount/remount it and to re-render the screen
  (neither issues a new ad request);
- `game_end` interstitial and `reward_coins` rewarded load/show, with the reward result;
- switches for test mode and forced Qartvelo Ads no-fill, which demonstrates the AdMob fallback
  (Google test ads);
- a live log of every SDK event.

## Run

1. Install the Qartvelo Ads Android SDK into `~/.m2`: `cd ../../android && ./gradlew publishToMavenLocal`.
2. Seed and start the backend: `php artisan migrate:fresh --seed && php artisan serve --port=8000`.
3. Build the package once: `cd ../packages/react-native-qartvelo-ads && npm install && npm run build`.
4. `npm install`, then `npx react-native start` and, in a second terminal,
   `npx react-native run-android` (or `cd android && ./gradlew assembleDebug`).

Initialization options apply once per process: force-stop the app to change the switches.

Configuration used here (see the [React Native guide](https://developers.qartvelo.com/react-native/installation/)):

- `app.json`: the AdMob config is the `"@qartvelo/react-native-ads"` key, with Google's sample App IDs as
  `androidAppId` and `iosAppId`. Whenever `admob` is present, `androidAppId` is required for Android builds and
  `iosAppId` for iOS builds. No Gradle, manifest, Podfile or `Info.plist` edits are needed for AdMob;
- `android/gradle.properties`: `QartveloAds_useMavenLocal=true` resolves the Qartvelo Ads SDK from Maven Local
  during development (publish it with `./gradlew publishToMavenLocal` in `../../android`), and
  `android/build.gradle` lists `mavenLocal()` for the `com.qartvelo.ads` group;
- iOS: `pod install` links the adapter from `app.json`;
- `app/src/debug`: cleartext HTTP allowed for `10.0.2.2` and `localhost` in debug builds only.

Checks: `npm test`, `npm run typecheck`, `npm run lint`.
