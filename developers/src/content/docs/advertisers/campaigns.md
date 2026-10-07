---
title: Campaigns
description: Create CPM campaigns, submit them for review and manage their lifecycle.
---

Advertisers buy impressions in Georgian Android apps with CPM campaigns: you set a price per 1000 impressions and a budget, upload creatives, choose contextual targeting, and pay only for delivered impressions from a prepaid balance.

## Get started

1. Register at [ads.qartvelo.com/register](https://ads.qartvelo.com/register) as an **Advertiser** with your company name, and verify your email.
2. Your account starts as **pending**. You can already build campaigns; they serve once your account is approved and your balance is topped up (see [Billing](/advertisers/billing/)).
3. Create a campaign, add at least one creative, then **Submit for review**.

## Campaign settings

| Field | Rules |
|---|---|
| Name | Up to 255 characters |
| CPM bid | 0.01 to 10000 GEL per 1000 impressions, up to 4 decimals. One impression costs bid / 1000 |
| Total budget | At least 1 GEL and enough for one impression |
| Daily budget | Optional; at least 1 GEL and not above the total budget. Delivery is paced evenly across the day |
| Start / end | Optional, in Georgian time (`Asia/Tbilisi`). End must be in the future and after the start |
| Frequency cap | Optional: 1 to 1000 impressions per `session`, `hour` or `day` for one viewer session |
| Targeting | See [Targeting](/advertisers/targeting/) |

Only CPM pricing is available. Clicks are tracked and reported but not charged.

## Statuses

```
draft ──submit──> pending_review ──approve──> active   (start reached)
  ^                    │                  └──> approved (scheduled; becomes active at start)
  └── edit ── rejected <┘ reject
active ⇄ paused        active / approved / paused ──> completed
```

| Status | Meaning |
|---|---|
| `draft` | Being built. Editable |
| `pending_review` | Submitted; Qartvelo Ads reviews settings and creatives |
| `approved` | Approved, waiting for its start time |
| `active` | Serving. Only active campaigns are served |
| `paused` | Stopped by you or by an admin. A campaign paused by an admin can only be resumed by an admin |
| `rejected` | Not accepted; the reason is shown. Edit and resubmit |
| `completed` | Ended at its end date, when the total budget is spent, or completed manually. Final |

- Submitting requires at least one creative that is pending review or approved, and an end date that has not passed.
- Settings and targeting can be edited only in `draft` and `rejected`. To change a running campaign, complete it and create a new one.
- Resuming requires an end date in the future and remaining budget.

## How delivery works

For every ad request, Qartvelo Ads considers active campaigns that match the request's context and have budget, then picks the **highest CPM bid** (ties are random). A daily budget is paced: the campaign is skipped while its spend today is ahead of an even pace (with 10% slack). Budgets are reserved atomically per impression, so a campaign can never overspend its total budget, its daily budget or your balance.

## Reports

The **Reports** page shows impressions, clicks, CTR, spend and effective CPM per day, campaign, creative, app and placement, and exports to CSV. Spend is updated in real time. Invalid traffic (duplicates, replays, implausible click rates) is rejected before billing and never charged.
