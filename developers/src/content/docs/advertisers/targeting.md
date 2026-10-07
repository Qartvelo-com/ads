---
title: Targeting
description: Contextual targeting options for campaigns. No personal data or user profiles.
---

Qartvelo Ads targeting is **contextual only**: it uses the app and the request, never a person's history. There are no advertising IDs, audiences or retargeting. Every option is optional; empty means "any".

| Option | Values | Matched against |
|---|---|---|
| Country | ISO 3166-1 alpha-2 code, for example `GE` | Derived from the network address at the edge. Requests without a known country count as Georgia |
| Languages | `ka`, `en`, `ru` (several) | The device language, else the app's default language |
| Android versions | `6` to `16` (several) | The device's Android major version. Requests without a version do not match a campaign that targets versions |
| App categories | games, news, entertainment, education, lifestyle, sports, finance, shopping, social, tools, travel, health, music, other | The publisher's app category |
| Apps | Up to 500 approved apps | Specific apps |
| Formats | banner, interstitial, rewarded | The placement's format |
| Frequency cap | 1 to 1000 per session, hour or day | Impressions of this campaign within one viewer session |

A viewer session is a random id that lasts about an hour and is never linked to a person, so frequency caps are approximate by design.

## Tips

- Start broad and narrow down using the per-app and per-placement breakdown in Reports.
- Language targeting is the best proxy for the creative's language: target `ka` for Georgian-language creatives.
- Rewarded video usually has the highest attention; banners the lowest price.
