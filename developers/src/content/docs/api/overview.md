---
title: REST API overview
description: The HTTP API the SDKs use, for custom clients, server-side tests and debugging.
---

The official SDKs implement this API for you. Use it directly to build a client for another platform, to write integration tests, or to debug what the SDK sends. A machine-readable description is available as [OpenAPI 3.1](/openapi.yaml).

| | |
|---|---|
| Base URL | `https://ads.qartvelo.com/api/v1` |
| Format | JSON request and response bodies, `Content-Type: application/json` |
| Methods | All endpoints are `POST` |
| Authentication | The public app key plus a session token; no cookies, no API keys |
| Caching | Responses carry `Cache-Control: no-store, private` |

## Endpoints

| Endpoint | Purpose |
|---|---|
| [`POST /sdk/initialize`](/api/initialize/) | Validate app key + package name, get a session token and the remote configuration |
| [`POST /ads/request`](/api/ad-request/) | Get an ad for a placement, or an explicit no-fill with the fallback to use |
| [`POST /events/impression`](/api/events/) | Record (and bill) one impression |
| [`POST /events/click`](/api/events/) | Record one click for an accepted impression |
| [`POST /events/reward`](/api/events/) | Record the rewarded outcome, exactly once |
| [`POST /events/fallback`](/api/events/#post-eventsfallback) | Telemetry: the client fell back to another network |

## Flow

```
initialize ──> session_token (1 h) + config
     │
ads/request (placement, format, session_token) ──> fill: request_id + ad.impression_token
     │                                            └─> no_fill: fallback = admob | none
impression (request_id, impression_token)  once, while the ad is valid (30 min)
click      (request_id, impression_token)  at most once, after the impression
reward     (request_id, impression_token, completion)  rewarded placements only
```

## Conventions

- Timestamps are ISO-8601 UTC with `Z`, for example `2026-10-07T18:40:00Z`.
- Public ids are prefixed strings: campaign `cmp_12`, creative `cr_34`, ad `ad_3f2a9c01d4e5b6a7`, request `req_01k9...`.
- Session and impression tokens are **opaque**. Do not parse, modify or log them; pass them back verbatim.
- Money never reaches the client.
- Compress responses at the edge; keep HTTP keep-alive enabled. The SDKs use a single connection pool.
- Errors use one envelope, except event rejections (see [Errors and limits](/api/errors-and-limits/)):

  ```json
  {"error":{"code":"placement_not_found","message":"Placement [game_end] does not exist in this app."}}
  ```

## Try it with curl

Every call below runs in test mode, so nothing is billed. Replace the app key and package with yours.

```bash
B=https://ads.qartvelo.com/api/v1
KEY=app_xxxxxxxxxxxxxxxxxxxxxxxx
PKG=com.example.app

# 1. Initialize in test mode
S=$(curl -s -X POST $B/sdk/initialize -H 'Content-Type: application/json' \
  -d "{\"app_key\":\"$KEY\",\"package_name\":\"$PKG\",\"platform\":\"android\",\"test_mode\":true}" \
  | jq -r .session_token)

# 2. Request an interstitial
AD=$(curl -s -X POST $B/ads/request -H 'Content-Type: application/json' \
  -d "{\"app_key\":\"$KEY\",\"placement\":\"game_end\",\"format\":\"interstitial\",\"session_token\":\"$S\",\"test_mode\":true,\"screen_width\":1080,\"screen_height\":2400}")
echo "$AD" | jq .
RID=$(echo "$AD" | jq -r .request_id); TOK=$(echo "$AD" | jq -r .ad.impression_token)

# 3. Impression, then a click at least one second later
curl -s -X POST $B/events/impression -H 'Content-Type: application/json' \
  -d "{\"request_id\":\"$RID\",\"impression_token\":\"$TOK\"}"
sleep 1
curl -s -X POST $B/events/click -H 'Content-Type: application/json' \
  -d "{\"request_id\":\"$RID\",\"impression_token\":\"$TOK\"}"

# 4. A replay is rejected: {"status":"rejected","reason":"duplicate"} (HTTP 409)
curl -s -X POST $B/events/impression -H 'Content-Type: application/json' \
  -d "{\"request_id\":\"$RID\",\"impression_token\":\"$TOK\"}"

# 5. Force a no-fill to exercise the fallback path
curl -s -X POST $B/ads/request -H 'Content-Type: application/json' \
  -d "{\"app_key\":\"$KEY\",\"placement\":\"game_end\",\"format\":\"interstitial\",\"session_token\":\"$S\",\"test_mode\":true,\"test_force_no_fill\":true}"
# {"status":"no_fill","request_id":"req_...","fallback":"admob","reason":"test_no_fill"}
```

## Client requirements

A custom client must behave like the SDKs to keep traffic valid:

- Send the impression only when the creative is actually on screen, once, and before the ad's `expires_at`.
- Send a click only after a user tap, at most once per impression, and not within one second of the impression.
- Send a reward event only for rewarded placements, once, with the real completion state; grant the reward only on completion.
- Treat every `rejected` event response as final; retry only network errors, `5xx`, `408` and `429` (honour `Retry-After`).
- Never show an ad after `expires_at`, and do not reuse a `request_id` or token.
- Initialize again when a call returns `session_expired` or `invalid_session`.
