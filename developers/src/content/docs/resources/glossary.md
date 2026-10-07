---
title: Glossary
description: Terms used across the Qartvelo Ads documentation and dashboards.
---

| Term | Meaning |
|---|---|
| **Advertiser** | A company that buys impressions through campaigns |
| **App key** | Public identifier of a registered app (`app_` + 24 characters), passed to `initialize` |
| **CPM** | Cost per mille: the price for 1000 impressions. One impression costs CPM / 1000 |
| **Campaign** | An advertiser's budget, bid, schedule and targeting, with one or more creatives |
| **Creative** | The image or video shown as an ad, with a destination URL |
| **CTR** | Click-through rate: clicks / impressions |
| **Fallback** | Showing the publisher's own AdMob (or custom) ad when Qartvelo Ads cannot serve |
| **Fill / no fill** | Whether an ad request returned an ad |
| **Fill rate** | Fills / requests |
| **Frequency cap** | Maximum impressions per session, hour or day for one viewer session |
| **Impression** | One ad actually displayed; the billing event |
| **Impression token** | Signed, single-use token that authorizes the events of one ad |
| **Kill switch** | A setting that turns Qartvelo Ads serving off globally or for a publisher, app or placement |
| **Pacing** | Spreading a daily budget evenly over the day |
| **Placement** | One ad slot in an app, identified by its code and with one format |
| **Placement code** | The string your code uses for a placement, for example `game_end` |
| **Publisher** | A company that shows ads in its apps |
| **Remote configuration** | Placement settings delivered by `/sdk/initialize` and cached by the SDK |
| **Revenue share** | The publisher's percentage of advertiser spend (70% by default) |
| **SDK secret** | Server-side credential of an app. Never embedded in apps |
| **Session** | A random, one-hour id issued at start-up; never linked to a person or device |
| **Test mode** | SDK option that serves non-billable test creatives and Google test units |
