---
title: Dark mode
description: How HTML5 ads switch to their dark version when the app is in dark mode, and what publishers need for it.
---

HTML5 ads can come in a light and a dark version. The ad picks the version that matches the app it is shown in, by itself: no SDK update, no extra code and no new setting. Image and video ads are not affected.

## How the ad knows the app's mode

HTML5 ads run in a web view inside your app. A web view reports a light or dark color scheme to the page it shows, and the ad reads it the way any web page reads `prefers-color-scheme`:

| Platform | What the ad follows |
|---|---|
| iOS | The app's light or dark appearance, including an app that forces one mode (`UIUserInterfaceStyle` or `overrideUserInterfaceStyle`) |
| Android | The `isLightTheme` attribute of the app's theme. Day/night themes such as `Theme.Material3.DayNight` or `Theme.AppCompat.DayNight` set it for you |
| React Native | The native theme underneath: on iOS the app's appearance, on Android the `isLightTheme` of the activity's theme |

If the mode changes while the ad is on screen, the ad switches at once.

## For publishers

Nothing to do in most apps. Check these if your app has a dark mode:

- **Android with a day/night theme:** works as is. If your app draws its own dark screens (for example with Jetpack Compose) on top of a theme that is always light, ads see a light app and show their light version. Use a `DayNight` parent theme so `isLightTheme` matches what users see.
- **Android with its own theme switch:** switching with `AppCompatDelegate.setDefaultNightMode()` updates the theme, and ads follow it.
- **iOS:** ads follow `overrideUserInterfaceStyle` of the window or view controller they are shown in.
- **Algorithmic darkening:** the SDK never turns on WebView's automatic darkening for ads. Ads with a dark version also declare that they handle dark mode themselves, so browsers do not darken them a second time.

Ads without a dark version look the same in light and dark apps.

## For advertisers

Make the dark version in the ad editor: see [Light and dark versions](/advertisers/creatives/#light-and-dark-versions).

- Keep enough contrast in both versions; check the dark one on the campaign page with the light/dark switch on the preview.
- Logos with dark lettering disappear on a dark background. Upload a light version of your logo and use it in the dark version.
- Reports do not split impressions by light or dark mode.
