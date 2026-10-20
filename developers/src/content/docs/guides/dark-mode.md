---
title: Dark mode
description: How HTML5 ads switch to their dark version when the app is in dark mode, and what publishers need for it.
---

HTML5 ads can come in a light and a dark version. By default the ad picks the version that matches the app it is shown in, by itself, with no extra code. Publishers can also choose to always show the light or the dark version (see [Ad color mode](#ad-color-mode)). Image and video ads are not affected.

## How the ad knows the app's mode

HTML5 ads run in a web view inside your app. A web view reports a light or dark color scheme to the page it shows, and the ad reads it the way any web page reads `prefers-color-scheme`:

| Platform | What the ad follows |
|---|---|
| iOS | The app's light or dark appearance, including an app that forces one mode (`UIUserInterfaceStyle` or `overrideUserInterfaceStyle`) |
| Android | The `isLightTheme` attribute of the app's theme. Day/night themes such as `Theme.Material3.DayNight` or `Theme.AppCompat.DayNight` set it for you |
| React Native | The native theme underneath: on iOS the app's appearance, on Android the `isLightTheme` of the activity's theme |

If the mode changes while the ad is on screen, the ad switches at once.

## For publishers

### Ad color mode

Each app has an **Ad color mode** setting in the publisher dashboard: open the app under **Apps** and choose **Edit app**. It applies to HTML5 ads that come in a light and a dark version:

| Ad color mode | What these ads show |
|---|---|
| **Match the app** (default) | The version that matches your app's light or dark theme, as described on this page |
| **Always light** | The light version, whatever your app's theme |
| **Always dark** | The dark version, whatever your app's theme |

You can change it at any time, without an app update: the SDK picks it up the next time it loads its configuration from Qartvelo Ads. The setting needs SDK 0.6.1 or newer. Older SDKs ignore it and always match the app.

### Matching the app

With **Match the app**, there is nothing to do in most apps. Check these if your app has a dark mode:

- **Android with a day/night theme:** works as is. If your app draws its own dark screens (for example with Jetpack Compose) on top of a theme that is always light, ads see a light app and show their light version. Use a `DayNight` parent theme so `isLightTheme` matches what users see, or set the ad color mode to **Always dark** if your app is always dark.
- **Android with its own theme switch:** switching with `AppCompatDelegate.setDefaultNightMode()` updates the theme, and ads follow it.
- **iOS:** ads follow `overrideUserInterfaceStyle` of the window or view controller they are shown in.
- **Algorithmic darkening:** the SDK never turns on WebView's automatic darkening for ads. Ads with a dark version also declare that they handle dark mode themselves, so browsers do not darken them a second time.

Ads without a dark version look the same in light and dark apps.

## For advertisers

Make the dark version in the ad editor: see [Light and dark versions](/advertisers/creatives/#light-and-dark-versions).

- Keep enough contrast in both versions; check the dark one on the campaign page with the light/dark switch on the preview.
- Logos with dark lettering disappear on a dark background. In the dark version, give the logo the **Invert** filter (a black logo becomes white), or replace it with a light version of your logo.
- Bright photos glare in a dark app: the **Dim** filter in the dark version tones them down.
- Reports do not split impressions by light or dark mode.
