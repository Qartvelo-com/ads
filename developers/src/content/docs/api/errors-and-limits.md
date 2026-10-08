---
title: Errors and limits
description: Error envelope and codes, rate limits and fraud rules of the Qartvelo Ads API.
---

## Error envelope

Every non-2xx response except event rejections uses:

```json
{"error":{"code":"validation_failed","message":"The placement field is required.","fields":{"placement":["The placement field is required."]}}}
```

`fields` is present only for `validation_failed`. Branch on `code`; `message` is human-readable and may change.

| Code | HTTP | When | What to do |
|---|---|---|---|
| `invalid_app_key` | 401 | Unknown app key | Check the key on the app page |
| `invalid_session` | 401 | Session token malformed, forged or for another app | Initialize again |
| `session_expired` | 401 | Session token expired | Initialize again |
| `package_mismatch` | 403 | Package name differs from the registered app | Fix the `applicationId` (or iOS bundle ID) or the registration |
| `platform_mismatch` | 403 | The app key belongs to the other platform's app | Use the app key of the Android or iOS app you are building |
| `app_not_approved` | 403 | App not approved and not in test mode | Use test mode until approved |
| `placement_not_found` | 404 | No placement with that code in this app | Create it, or fix the code |
| `format_mismatch` | 422 | Requested format differs from the placement | Use the matching load method |
| `validation_failed` | 422 | Missing or invalid fields | See `error.fields` |
| `rate_limited` | 429 | A rate limit was exceeded | Wait for `Retry-After` seconds |
| `server_error` | 500 | Unexpected error | Retry with backoff; fall back |

Event endpoints answer rejections with `{"status":"rejected","reason":"..."}` instead; see [Events](/api/events/).

## Rate limits

Per minute. "Network" means the client's IPv4 /24 or IPv6 /48 (hashed). Network limits are generous because mobile carriers put many users behind one carrier-grade NAT.

| Endpoint | Dimension | Limit |
|---|---|---|
| `/sdk/initialize` | network | 600 |
| `/sdk/initialize` | app key | 3000 |
| `/ads/request` | session | 120 |
| `/ads/request` | network | 6000 |
| `/ads/request` | app key | 20000 |
| `/events/impression`, `click`, `reward` | network | 12000 |
| `/events/impression`, `click`, `reward` | request id | 20 |
| `/events/fallback` | session / network | 120 / 12000 |

A `429` carries a `Retry-After` header. Contact Qartvelo Ads if a large app needs higher app-key limits.

## Fraud rules

Traffic that breaks these rules is not billed and does not earn revenue. Rejections are logged for review.

| Rule | Default | Effect |
|---|---|---|
| Ad requests per session per minute | 30 | Further requests get `no_fill` |
| Impressions per placement per session per hour | Banners: `3600 / refresh seconds x 1.5` (180 for a 30 s banner). Full-screen: 60 | Impression rejected `suspicious` |
| Impressions per session per hour, all placements | 720 | Impression rejected `suspicious` |
| Time between impression and click | At least 1 s | Click rejected `suspicious` |
| Clicks per session per hour | 20 | Click rejected `suspicious` |
| Session click-through rate | Above 50% once the session has 10+ impressions | Click rejected `suspicious` |
| Replays and duplicates | Always | `409 duplicate` |
| Package mismatch | Always | `403 package_mismatch` |
