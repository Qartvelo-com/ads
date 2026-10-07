---
title: Events
description: Report impressions, clicks, rewards and fallbacks. Every event is validated, de-duplicated and fraud-checked.
---

## POST /events/impression, /events/click

```json
{"request_id":"req_01k9xyz...","impression_token":"<opaque>"}
```

| Field | Type | Required |
|---|---|---|
| `request_id` | string, max 64 | yes |
| `impression_token` | string, max 2048 | yes |

## POST /events/reward

Adds a required `completion` flag:

```json
{"request_id":"req_01k9xyz...","impression_token":"<opaque>","completion":true}
```

## Responses

| Response | Meaning |
|---|---|
| 200 `{"status":"accepted"}` | Impression or click recorded |
| 200 `{"status":"accepted","rewarded":true}` | Reward recorded; `rewarded` mirrors `completion` |
| 409 `{"status":"rejected","reason":"duplicate"}` | This token was already used for this event type |
| 422 `{"status":"rejected","reason":"invalid_token"}` | Bad signature or payload, wrong token type, or a reward on a non-rewarded placement |
| 422 `{"status":"rejected","reason":"expired_token"}` | Impression after the ad expired, or click/reward more than one hour after that |
| 422 `{"status":"rejected","reason":"request_mismatch"}` | The token belongs to another `request_id` |
| 422 `{"status":"rejected","reason":"no_impression"}` | Click or reward without an accepted impression for this token |
| 422 `{"status":"rejected","reason":"suspicious"}` | A fraud rule rejected the event |
| 422 `{"error":{"code":"validation_failed",...}}` | Missing fields |
| 429 `{"error":{"code":"rate_limited",...}}` | Rate limited |

Treat every `rejected` response as **final**: retrying cannot succeed. Rejected events are never billed.

## Rules

- **Impression**: accepted only before the ad's `expires_at`, exactly once per token. This is the billing event: the campaign is charged one impression (CPM bid / 1000) and the publisher is credited their revenue share. Test tokens are validated the same way but never billed.
- **Click**: requires the accepted impression for the same request and token, at most one per impression, up to one hour after the ad's expiry. Clicks are counted, not billed.
- **Reward**: requires the accepted impression on a **rewarded** placement; only one reward event per impression is ever stored and the first report wins (`completion:false` cannot be upgraded later). Grant the in-app reward on confirmed completion; the server response is for reporting and auditing.

## POST /events/fallback

Fire-and-forget telemetry that the client fell back to another network. It feeds the **Fallbacks** column in publisher reports. Test sessions are ignored.

```json
{"session_token":"<opaque>","placement":"game_end","reason":"timeout"}
```

| Field | Values |
|---|---|
| `session_token` | From `/sdk/initialize` |
| `placement` | Placement code |
| `reason` | `no_fill`, `timeout`, `error`, `creative_failed` |

Response 200 `{"status":"accepted"}`. Errors: `invalid_session` / `session_expired` 401, `placement_not_found` 404, `validation_failed` 422. The SDK maps its internal `disabled` reason to `no_fill`.
