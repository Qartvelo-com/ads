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
| `screen_width`, `screen_height` | integer 0..20000 | no | Pixels. Used to pick a banner that fits and an interstitial matching the orientation |
| `test_mode` | boolean | no | Serve a never-billed test ad (also implied by a test session) |
| `test_force_no_fill` | boolean | no | Always answer `no_fill` with reason `test_no_fill` |

```json
{"app_key":"app_xxxxxxxxxxxxxxxxxxxxxxxx","placement":"game_end","format":"interstitial",
 "session_token":"<opaque>","language":"ka","android_version":"14","app_version":"1.0.0",
 "sdk_version":"0.4.0","screen_width":1080,"screen_height":2400}
```

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
| `ad.creative_type` | `image` (PNG, JPEG, WebP, GIF) or `video` (MP4, H.264) |
| `ad.creative_url` | Download before reporting the ad as loaded |
| `ad.click_url` | Advertiser landing page, opened on tap after the click event is queued |
| `ad.width`, `ad.height` | Creative size in pixels |
| `ad.duration_seconds` | Video length, `null` for images |
| `ad.impression_token` | Opaque, signed, single-use |
| `ad.expires_at` | 30 minutes after the request. Never show the ad after this |
| `ad.test` | `true` for test ads: never billed, show them labelled "Test ad". An approved app gets the live creative it would win; otherwise the built-in test ad (`campaign_id` `cmp_test`, `creative_id` `cr_test_{format}`) |

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
