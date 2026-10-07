---
title: Prompts
description: Copy-paste prompts for common Qartvelo Ads tasks with AI coding assistants.
---

Replace the placeholders in angle brackets. Each prompt points the assistant at the right docs, so it works with any tool that can read URLs; with the [MCP server](/ai/mcp-server/) installed the agent uses its tools instead.

## Integrate from scratch

```text
Integrate the Qartvelo Ads SDK into this Android app.
Docs: https://developers.qartvelo.com/_llms-txt/android-sdk.txt
App key: <app_...>. Placements: <home_banner (banner), level_done (interstitial), extra_life (rewarded)>.
- Initialize in the Application class with testMode = BuildConfig.DEBUG.
- Banner on <MainActivity>, interstitial after <a level ends>, rewarded behind the <"Extra life"> button.
- Continue the flow from onDismissed, onNoAdAvailable and onLoadFailed. Grant rewards only in onReward.
- Keep my existing AdMob App ID and ad units as the fallback.
Show me the diff and anything I must do in the dashboard.
```

## React Native

```text
Add @qartvelo/react-native-ads to this React Native app following
https://developers.qartvelo.com/_llms-txt/react-native.txt.
App key <app_...>, placements <home_banner banner, game_end interstitial, reward_coins rewarded>.
Enable the AdMob fallback (QartveloAds_admobEnabled=true) and use testMode: __DEV__.
Create a small hook for the rewarded ad that exposes ready and show().
```

## Migrate from AdMob only

```text
This app shows AdMob ads directly. Put Qartvelo Ads in front of them without losing AdMob revenue:
read https://developers.qartvelo.com/guides/admob-fallback.md, create a placement code for each
existing AdMob ad unit, map them with admobAdUnits, and replace the direct AdMob load/show calls with
QartveloAds calls. Keep the AdMob App ID in the manifest. List the placements I need to create in the
dashboard with their format and ad unit id.
```

## Debug

```text
Qartvelo Ads is not showing ads. Here is `adb logcat -s QartveloAds` with DEBUG logging:
<paste log>
Use https://developers.qartvelo.com/resources/troubleshooting.md and the error codes in
https://developers.qartvelo.com/android/events.md to find the cause and the fix.
```

## Review before release

```text
Review this project's Qartvelo Ads integration against
https://developers.qartvelo.com/android/release-checklist.md and
https://developers.qartvelo.com/guides/privacy.md. Report every item that fails, with file and line.
```

## Build a custom client

```text
Write a <Kotlin Multiplatform / Flutter / Unity> client for the Qartvelo Ads REST API from
https://developers.qartvelo.com/openapi.yaml and https://developers.qartvelo.com/_llms-txt/rest-api.txt.
Follow the "Client requirements" section exactly (impression once on screen, click after a tap, reward
only on rewarded placements, treat rejections as final, honour Retry-After).
```
