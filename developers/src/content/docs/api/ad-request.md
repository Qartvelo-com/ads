---
title: POST /ads/request
description: Request an ad for a placement and receive a fill with a signed impression token, or a no-fill.
---

```http
POST /api/v1/ads/request
Content-Type: application/json
```

## Request

| Field | Type | Required | Notes |
|---|---|---|---|
| `app_key` | string | yes | Must be the app the session was issued for |
| `placement` | string, max 64 | yes | Placement code (`[a-z0-9_]{2,64}`) |
| `format` | `banner` \| `interstitial` \| `rewarded` | yes | Must match the placement's format |
| `session_token` | string | yes | From `/sdk/initialize` |
| `language` | string, max 35 | no | Content language such as `ka` or `en-US` (primary subtag used). Defaults to the app's language |
| `android_version` | string | no | Android only. `14` or `14.0.1`; the major version is used for targeting |
| `os_version` | string, max 32 | no | iOS: the system version, for example `18` (informational) |
| `app_version`, `sdk_version` | string | no | Informational |
| `screen_width`, `screen_height` | integer 0..20000 | no | Pixels. Used to pick a banner that fits and an interstitial matching the orientation. For banners, `screen_width` is the slot width |
| `banner_height` | integer 1..20000 | no | Anchored banners: the slot height in pixels. See [Banner sizing](#banner-sizing) |
| `banner_mode` | string | no | `inline` for banners inside scrolling content; any other value (or none) means anchored |
| `banner_max_height` | integer 1..20000 | no | With `banner_mode: "inline"`: the most the ad may be tall, in pixels. Ignored otherwise |
| `supported_creative_types` | array of strings | no | Creative types the SDK can show: `image`, `video`, `html5`. Missing or empty means `["image","video"]`; unknown values are ignored |
| `test_mode` | boolean | no | Serve a never-billed test ad (also implied by a test session) |
| `test_force_no_fill` | boolean | no | Always answer `no_fill` with reason `test_no_fill` |

```json
{"app_key":"app_xxxxxxxxxxxxxxxxxxxxxxxx","placement":"game_end","format":"interstitial",
 "session_token":"<opaque>","language":"ka","android_version":"14","app_version":"1.0.0",
 "sdk_version":"0.6.0","screen_width":1080,"screen_height":2400}
```

SDKs 0.6.0 and later send `"supported_creative_types":["image","video","html5"]` for banner and
interstitial requests and `["image","video"]` for rewarded. An inline banner request adds:

```json
{"format":"banner","screen_width":1080,"banner_mode":"inline","banner_max_height":750}
```

## Banner sizing

- **Anchored** (default): send `screen_width` and `banner_height`, the slot in pixels. The server
  selects the approved horizontal creative with the closest aspect ratio, leaving out rectangles whose
  width is less than twice their height. Without `banner_height`, the widest creative that fits
  `screen_width` wins.
- **Inline**: send `banner_mode: "inline"`, `screen_width` and `banner_max_height`, and no
  `banner_height`. Each candidate (an image creative, or each layout of an HTML5 ad) is scaled to fit
  `screen_width` x `banner_max_height` keeping its proportions, and the one with the largest area is
  served. Rectangles are allowed. `ad.width` and `ad.height` are the chosen creative's or layout's
  own size; the SDK sizes the slot with the same rule.

## Response 200: fill

```json
{
  "status": "fill",
  "request_id": "req_01k9xyz...",
  "ad": {
    "id": "ad_3f2a9c01d4e5b6a7",
    "campaign_id": "cmp_12",
    "creative_id": "cr_34",
    "format": "interstitial",
    "creative_type": "image",
    "creative_url": "https://cdn.example.com/creatives/12/01k....png",
    "click_url": "https://advertiser.example",
    "width": 1080,
    "height": 1920,
    "duration_seconds": null,
    "impression_token": "<opaque>",
    "expires_at": "2026-10-07T18:40:00Z",
    "test": false
  }
}
```

| Field | Meaning |
|---|---|
| `request_id` | Send with every event for this ad |
| `ad.creative_type` | `image` (PNG, JPEG, WebP, GIF), `video` (MP4, H.264) or `html5` (only when the request listed it) |
| `ad.creative_url` | Download before reporting the ad as loaded. For `html5`, the bundle's `index.html` |
| `ad.click_url` | Advertiser landing page, opened on tap after the click event is queued |
| `ad.width`, `ad.height` | Creative size in pixels. For `html5`, the size of the layout that matches the request |
| `ad.layouts` | `html5` only: every layout of the bundle with the files it loads. See [HTML5 fill](#html5-fill) |
| `ad.duration_seconds` | Video length, `null` for images |
| `ad.impression_token` | Opaque, signed, single-use |
| `ad.expires_at` | 30 minutes after the request. Never show the ad after this |
| `ad.test` | `true` for test ads: never billed, show them labelled "Test ad". An approved app gets the live creative it would win; otherwise the built-in test ad (`campaign_id` `cmp_test`, `creative_id` `cr_test_{format}`) |

### HTML5 fill

HTML5 ads are built from designs made in the Qartvelo Ads editor, for banners and interstitials. They
are only served to requests whose `supported_creative_types` lists `html5`; an HTML5 creative wins
over images in the same campaign because it fits any slot of its format. Every HTML5 design also has
static image versions, so older SDKs keep serving the campaign.

```json
{"creative_type":"html5","creative_url":"https://ads.qartvelo.com/api/v1/bundles/01m4.../index.html",
 "width":320,"height":50,"layouts":[
 {"family":"strip","width":320,"height":50,"files":["index.html","style.css","main.js","m/3f2a.jpg"]},
 {"family":"tall","width":320,"height":100,"files":["index.html","style.css","main.js","m/9c1d.jpg"]},
 {"family":"rect","width":300,"height":250,"files":["index.html","style.css","main.js","m/77b0.png"]}]}
```

- `width` and `height` are the layout that matches the request: for anchored banners the closest
  ratio to `screen_width`/`banner_height`, for inline banners the layout that shows biggest, for
  interstitials the screen's orientation.
- `layouts[].files` are paths relative to the bundle base (`creative_url` without `index.html`). Download
  the served layout's files before reporting the ad as loaded; another layout (after a resize) loads
  its files from the bundle URL.
- The ad runtime sets `window.__qartvelo.ready` to `true` after its first render. Count the impression
  only when the ad is ready and visible. The SDKs treat an ad that is not ready within 6 seconds as a
  creative failure.
- On a tap the ad navigates to `qartvelo://click`. Treat that, and any other navigation after the first
  load, as one click: send the click event and open `click_url` yourself.
- Call `window.__qartvelo.pause()` when the ad leaves the screen or the app goes to the background, and
  `resume()` when it is back.

In test mode, requests that list `html5` get the built-in animated HTML5 test ads for banners and
interstitials (`creative_id` `cr_test_banner_html5` or `cr_test_interstitial_html5`). Rewarded slots
and other SDKs keep the image and video test ads.

## Response 200: no fill

```json
{"status":"no_fill","request_id":"req_01k9xyz...","fallback":"admob","reason":"no_eligible_campaign"}
```

`fallback` is the placement's provider: `admob` or `none`.

| Reason | Meaning |
|---|---|
| `no_eligible_campaign` | No campaign matched, or none had budget left |
| `serving_disabled` | Global, publisher or app kill switch (or publisher/app not approved outside test mode) |
| `placement_disabled` | Placement paused, switched off, or disabled by an admin |
| `frequency_capped` | The placement's frequency cap for this session is reached |
| `test_no_fill` | `test_force_no_fill` was set |

## Selection

1. Session verified, app key matches the session, app approved (unless test).
2. Placement found and format matches; kill switches; placement frequency cap.
3. Test mode returns a test ad (the top-ranked live creative for an approved app, without reserving budget, else the built-in one). Otherwise:
4. Eligible campaigns: active, within schedule, budget left, advertiser approved with balance, country matches (or any), at least one approved creative of the requested format.
5. Targeting: platforms, formats, languages, app ids, app categories, Android versions. Empty means any. A campaign that targets Android versions only runs on Android apps and is skipped when the request has no version.
6. Campaign frequency cap for this session.
7. Highest CPM bid first (ties random), with daily budget pacing.
8. The cost of one impression is reserved atomically; if a campaign cannot reserve, the next one is tried.

Country comes from the network address at Qartvelo Ads' edge, never from the device. The IP address is not stored.

## Errors

| HTTP | Code | When |
|---|---|---|
| 401 | `invalid_session` | Token malformed, forged or issued for another app |
| 401 | `session_expired` | Initialize again |
| 403 | `app_not_approved` | App not approved and not a test session |
| 404 | `placement_not_found` | No placement with that code in this app |
| 422 | `format_mismatch` | `format` differs from the placement |
| 422 | `validation_failed` | Missing or invalid fields |
| 429 | `rate_limited` | See [rate limits](/api/errors-and-limits/#rate-limits) |
