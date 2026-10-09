---
title: Targeting
description: Contextual targeting options for campaigns. No personal data or user profiles.
---

Qartvelo Ads targeting is **contextual only**: it uses the app and the request, never a person's history. There are no advertising IDs, audiences or retargeting. Every option is optional; empty means "any".

| Option | Values | Matched against |
|---|---|---|
| Country | ISO 3166-1 alpha-2 code, for example `GE` | Derived from a trusted Cloudflare header, then optional GeoIP lookup. Unresolved requests are `unknown` (`ZZ`) and only match campaigns without a country restriction |
| Languages | `ka`, `en`, `ru` (several) | The device language, else the app's default language |
| Platforms | Android, iOS (several) | The app's platform |
| Android versions | `6` to `16` (several) | The device's Android major version. A campaign that targets versions runs on Android apps only |
| App categories | games, news, entertainment, education, lifestyle, sports, finance, shopping, social, tools, travel, health, music, other | The publisher's app category |
| Apps | Up to 500 approved apps | Specific apps |
| Formats | banner, interstitial, rewarded | The placement's format |
| Frequency cap | 1 to 1000 per session, hour or day | Impressions of this campaign within one viewer session |

A viewer session is a random id that lasts about an hour and is never linked to a person, so frequency caps are approximate by design.

## Unknown country

If the country cannot be determined, the request is never assigned to Georgia or another real country. Missing or invalid country headers, Cloudflare's `XX`/`T1` values and failed GeoIP lookups resolve to `ZZ` (`unknown`) unless another trusted lookup determines a country. Headers from untrusted clients are ignored.

Unknown traffic cannot match a campaign targeting `GE` or any other specific country. It can match a campaign set to **Any country**, subject to the remaining targeting and budget rules; otherwise the request returns no fill and the configured fallback applies. Served impressions retain `ZZ` in the country field for the normal raw-log retention period. The old `OURADS_DEFAULT_COUNTRY` setting is no longer used.

## Tips

- Start broad and narrow down using the per-app and per-placement breakdown in Reports.
- Language targeting is the best proxy for the creative's language: target `ka` for Georgian-language creatives.
- Rewarded video usually has the highest attention; banners the lowest price.
