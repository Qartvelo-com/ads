---
title: Creative specifications
description: Allowed formats, sizes, file types and limits for banner, interstitial and rewarded creatives.
---

Add creatives (ads) when you create the campaign, or later on the campaign page; you can drop several files at once, and the format of each is detected for you. Each creative is reviewed separately and serves only once **approved**; pending and rejected creatives are never shown. Approved creatives are locked; to change one, upload a new creative.

## Formats

| Format | Creative type | Where it appears |
|---|---|---|
| Banner | Image or HTML5 | A strip inside the app's UI, refreshed every 30 s or more |
| Interstitial | Image, video or HTML5 | Full screen at a natural break; the user can close it |
| Rewarded | Video | Full screen, opted in by the user in exchange for an in-app reward |

## Images

| | |
|---|---|
| File types | PNG, JPEG, WebP, GIF |
| Maximum size | 1 MB |
| Maximum dimension | 4096 px |
| Banner sizes | Exactly one of 320x50, 320x100, 300x250, 468x60, 728x90 |
| Interstitial size | Short side at least 320 px and long side at least 480 px. Use 1080x1920 (portrait) or 1920x1080 (landscape); the ad server prefers creatives matching the device orientation |

Supply several banner sizes in one campaign: the ad server picks a size that fits the device's screen width.

## Video

| | |
|---|---|
| Container / codec | MP4, H.264 |
| Maximum size | 30 MB |
| Duration | 5 to 60 seconds |
| Recommended | 720x1280 or 1080x1920 portrait, AAC audio, 15 to 30 s, `faststart` |

Videos are downloaded completely before they are shown, so a smaller file loads on more devices in time. Rewarded videos must be watched to the end to grant the reward.

## HTML5 ads

HTML5 ads are made in the ad editor on the dashboard (**Designs**) and exported to a campaign; they cannot be uploaded as files. One HTML5 ad fits every banner size (or both interstitial orientations), can be animated, and stays small enough to load quickly. Every export also creates static images in the standard sizes, so apps on older SDK versions keep showing your campaign.

### Light and dark versions

An HTML5 ad can have a **dark version** that is shown automatically when the app around it is in dark mode, so your ad never looks like a bright box in a dark app.

1. In the editor, switch the view above the canvas from **Light** to **Dark**. The first time, a dark version is made for you from your light colors: a deep background, light text, and your buttons kept in their brand color.
2. Adjust it. In the dark view, the colors, the background and the images you change only change the dark version; for example, swap your logo for a white one. Text, positions and animations are shared, so you edit them once.
3. Export as usual. The ad switches between the versions by itself, also when the user changes the phone's mode while the ad is on screen.

Ads with a dark version are labeled **Light + dark** on the campaign page, and their preview has a light/dark switch. Static image exports use the light version. See [Dark mode](/guides/dark-mode/) for how apps tell the ad which mode they are in.

## Destination URL

Every creative needs a landing page URL starting with `https://`. It opens in the user's browser when they tap the ad. Make sure it works on mobile.

## Review guidelines

Qartvelo Ads reviews every creative and its landing page before it can serve. If a creative is rejected, the reason is shown on it; fix the issue and upload a corrected creative. Pending or rejected creatives can still have their destination URL edited.

## Formats targeted by the campaign

If a campaign targets specific formats, you can only upload creatives of those formats. A campaign serves a format only when it has at least one approved creative of that format.
