---
title: Placements
description: Configure ad slots, fallback, timeouts, frequency caps and banner refresh from the dashboard.
---

A placement is one ad slot in your app, like an AdMob ad unit. Your code addresses it only by its **code**; everything else is remote configuration you can change at any time without an app update.

To add one, open the app and choose **Add placement**: pick the format, give it a name (the code is filled in from the name, and you can change it), and add your AdMob ad unit for the fallback. Request timeout, banner refresh, frequency cap and the serving switches are under **Advanced settings** with working defaults.

## Fields

| Field | Values | Default | Notes |
|---|---|---|---|
| Name | text | | For your reports |
| Code | `[a-z0-9_]{2,64}` | | Unique per app, for example `home_banner`. Used in code: `loadInterstitial("game_end")` |
| Format | `banner`, `interstitial`, `rewarded` | | Cannot change after the placement received traffic; create a new placement instead |
| Qartvelo Ads enabled | on / off | on | Off sends every request straight to the fallback |
| Fallback provider | `admob`, `none` | `admob` | What the SDK shows when Qartvelo Ads cannot fill |
| AdMob ad unit ID | `ca-app-pub-<publisher>/<unit>` | | Required when the fallback is AdMob. Your own unit, of the same format |
| Request timeout | 100 to 5000 ms | 800 | Qartvelo Ads time budget before falling back. Wins over the SDK option |
| Frequency cap | count (1 to 1000) per `session`, `hour` or `day` | none | Maximum Qartvelo Ads impressions of this placement per session id |
| Banner refresh | 30 to 3600 s | 60 | Banners only |
| Status | `active`, `paused` | active | Paused placements are not served by Qartvelo Ads (fallback still applies) |

:::tip[App ID vs ad unit ID]
The AdMob **App ID** contains `~` and belongs in `AndroidManifest.xml`. The **ad unit ID** contains `/` and belongs on the placement. The dashboard rejects an App ID pasted into the ad unit field.
:::

## Naming placement codes

- Name by location and moment, not by network: `level_complete`, `settings_banner`, `revive_reward`.
- Use one code per simultaneously visible banner. Two banners on screen at once need two banner placements.
- Keep codes stable. Reports are per placement, so renaming a code in code without a matching placement leads to `placement_not_found` / `INVALID_PLACEMENT`.

## Overriding AdMob units in code

The SDK option `admobAdUnits` maps placement codes to AdMob unit ids and wins over the dashboard value. Use it if you prefer to keep unit ids in your build configuration:

```kotlin
QartveloAdsOptions(
    admobAdUnits = mapOf(
        "home_banner" to "ca-app-pub-XXX/111",
        "game_end" to "ca-app-pub-XXX/222",
    ),
)
```

## Admin controls

Qartvelo Ads admins can also disable a placement, an app or a publisher. A placement disabled by an admin behaves like a paused placement and cannot be re-enabled from your dashboard. The SDK re-checks a disabled placement at most every 5 minutes, so re-enabling reaches running apps without a restart.

## How the effective timeout is chosen

1. The placement's request timeout from the dashboard, if the SDK has a config for it.
2. Otherwise `QartveloAdsOptions.requestTimeoutMs` (default 800).
3. The SDK clamps the result to 100..10000 ms.

The timeout covers the Qartvelo Ads session and ad request only. Creative downloads have their own limits (10 s for images, 45 s for video), and users never wait for either: loads are asynchronous and AdMob preloads in parallel.
