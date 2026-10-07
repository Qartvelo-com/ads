---
title: Creative specifications
description: Allowed formats, sizes, file types and limits for banner, interstitial and rewarded creatives.
---

Upload creatives on the campaign page. Each creative is reviewed separately and serves only once **approved**; pending and rejected creatives are never shown. Approved creatives are locked; to change one, upload a new creative.

## Formats

| Format | Creative type | Where it appears |
|---|---|---|
| Banner | Image | A strip inside the app's UI, refreshed every 30 s or more |
| Interstitial | Image or video | Full screen at a natural break; the user can close it |
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

## Destination URL

Every creative needs a landing page URL starting with `https://`. It opens in the user's browser when they tap the ad. Make sure it works on mobile.

## Review guidelines

Qartvelo Ads reviews every creative and its landing page before it can serve. If a creative is rejected, the reason is shown on it; fix the issue and upload a corrected creative. Pending or rejected creatives can still have their destination URL edited.

## Formats targeted by the campaign

If a campaign targets specific formats, you can only upload creatives of those formats. A campaign serves a format only when it has at least one approved creative of that format.
