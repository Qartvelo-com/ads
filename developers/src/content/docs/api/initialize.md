---
title: POST /sdk/initialize
description: Validate the app key and package name, and receive a session token and remote configuration.
---

```http
POST /api/v1/sdk/initialize
Content-Type: application/json
```

## Request

| Field | Type | Required | Notes |
|---|---|---|---|
| `app_key` | string, max 64 | yes | `app_` + 24 alphanumerics, from the publisher dashboard |
| `package_name` | string, max 255 | yes | Android application id, compared exactly with the registered one |
| `sdk_version` | string, max 32 | no | |
| `app_version` | string, max 64 | no | |
| `platform` | string | no | only `android` is accepted |
| `os_version` | string, max 32 | no | |
| `test_mode` | boolean | no | Skips the app approval check; the session only ever gets test ads |
| `is_emulator` | boolean | no | The device is an emulator. Emulator sessions are always test sessions |

```json
{"app_key":"app_xxxxxxxxxxxxxxxxxxxxxxxx","package_name":"com.example.app","sdk_version":"0.3.4",
 "app_version":"1.0.0","platform":"android","os_version":"14","test_mode":false}
```

## Response 200

```json
{
  "session_token": "<opaque>",
  "session_expires_at": "2026-10-07T19:40:00Z",
  "config": {
    "serving_enabled": true,
    "request_timeout_ms": 800,
    "fallback_enabled": true,
    "test_mode": false,
    "config_ttl_seconds": 3600
  },
  "placements": [
    {
      "code": "game_end",
      "format": "interstitial",
      "ourads_enabled": true,
      "fallback_provider": "admob",
      "admob_ad_unit_id": "ca-app-pub-3940256099942544/1033173712",
      "request_timeout_ms": 800,
      "frequency_cap_count": null,
      "frequency_cap_period": null,
      "banner_refresh_seconds": 60
    }
  ]
}
```

| Field | Meaning |
|---|---|
| `session_token` | Opaque, signed. Send it with ad requests and fallback telemetry. Valid until `session_expires_at` (one hour) |
| `config.serving_enabled` | `false` when a kill switch applies to the whole app (global, publisher, or app). Go straight to the fallback |
| `config.request_timeout_ms` | Default request budget for placements |
| `config.fallback_enabled` | Whether fallback is allowed at all; decide per placement with `fallback_provider` |
| `config.test_mode` | Whether this is a test session (`test_mode` or `is_emulator`). Clients must take test mode from their local option and never cache it |
| `config.config_ttl_seconds` | How long the client may cache this configuration |
| `placements[].code` / `format` | Placement code and its format |
| `placements[].ourads_enabled` | `false` when the placement is paused, switched off by the publisher or an admin, or the app is blocked. The name is historical; it means "Qartvelo Ads enabled". Re-check at most every 5 minutes while off |
| `placements[].fallback_provider` | `admob` or `none` |
| `placements[].admob_ad_unit_id` | The publisher's AdMob unit, or `null` |
| `placements[].request_timeout_ms` | Per-placement request budget (100..5000) |
| `placements[].frequency_cap_count` / `frequency_cap_period` | Placement cap per session id: `session`, `hour`, `day`, or `null` |
| `placements[].banner_refresh_seconds` | Banner refresh interval, never below 30 |

## Errors

| HTTP | Code | When |
|---|---|---|
| 401 | `invalid_app_key` | Unknown app key |
| 403 | `package_mismatch` | `package_name` differs from the registered app (logged as suspicious) |
| 403 | `app_not_approved` | App not approved and `test_mode` is false |
| 422 | `validation_failed` | Missing or invalid fields (`error.fields`) |
| 429 | `rate_limited` | Too many initializations (see `Retry-After`) |
