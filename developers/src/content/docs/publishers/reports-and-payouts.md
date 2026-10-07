---
title: Reports and payouts
description: Understand your traffic metrics, revenue share and how earnings are paid out.
---

## Metrics

The publisher dashboard reports per day, app and placement. Days follow the `Asia/Tbilisi` timezone.

| Metric | Meaning |
|---|---|
| Requests | Qartvelo Ads ad requests from the SDK |
| Matches | Requests that returned an ad (`fill`) |
| No fills | Requests that returned `no_fill` |
| Fill rate | Matches / requests |
| Impressions | Accepted impression events (billed) |
| Clicks | Accepted clicks (at most one per impression) |
| CTR | Clicks / impressions |
| Revenue | Your share of the advertiser spend on your impressions |
| Fallbacks | Times the SDK handed a request to your fallback (no fill, timeout, error, creative failure) |

Requests, matches, no fills and fallbacks can lag up to one minute; impressions, clicks and revenue are updated in real time. Test traffic is never counted. Reports can be exported as CSV.

AdMob impressions and revenue are **not** in Qartvelo Ads reports: they belong to your AdMob account and appear in AdMob. Qartvelo Ads only counts how often a fallback happened.

## Revenue share

You earn a percentage of what advertisers pay for impressions in your apps. The platform default is **70%**; an individual agreement can set a different share for your account. All amounts are in **GEL**.

Revenue per impression = campaign CPM bid / 1000 x your revenue share.

## Balances and payouts

| Balance | Meaning |
|---|---|
| Pending | Earned from impressions, not yet reviewed |
| Approved | Reviewed by Qartvelo Ads and scheduled for payment |
| Paid | Paid out to you |

Payouts are manual: Qartvelo Ads reviews earnings (including fraud review), approves an amount, which creates a payout in the **Payouts** page, and marks it paid once transferred. Contact Qartvelo Ads for payment details and schedule.

## Invalid traffic

Impressions and clicks that fail validation are never billed and never earn revenue. This includes duplicates and replays, events after the ad expired, clicks less than a second after the impression, implausibly high click-through rates and unusually high request or impression rates from one session. See [fraud rules](/api/errors-and-limits/#fraud-rules). Never click your own live ads: use [test mode](/get-started/test-mode/).
