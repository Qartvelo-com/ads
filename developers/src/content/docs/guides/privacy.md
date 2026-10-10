---
title: Privacy
description: What the SDK collects and never collects, consent signals, retention, and Google Play Data safety.
---

Qartvelo Ads targets ads by **context**, not by person. The SDK and backend are built so that no cross-app profile of a user can exist.

## Contextual targeting only

Campaigns can target country (derived on the server from the request's network address), app, app category, ad format, content language and Android major version. There is no behavioural, interest or audience targeting, and no retargeting.

When the country cannot be determined, traffic is recorded as `ZZ` (`unknown`) and only matches campaigns without a country restriction. It is never assigned to Georgia by default.

## Data the SDK sends

| Request | Fields |
|---|---|
| `POST /api/v1/sdk/initialize` | app key, package name, SDK version, app version, platform, OS version, test flag |
| `POST /api/v1/ads/request` | app key, placement code, format, session token, device language, Android version, app and SDK version, screen width and height in pixels, test flags |
| `POST /api/v1/events/impression`, `click`, `reward` | request id, signed impression token, reward completion flag |
| `POST /api/v1/events/fallback` | session token, placement code, fallback reason |

The HTTP user agent contains the SDK version, Android version and package name.

When a user taps the **Ad** badge on a Qartvelo Ads creative, the browser opens `https://ads.qartvelo.com/?ref=<app package name>`. Only the app's package name is passed; nothing about the user.

## Data the SDK never collects

- No Advertising ID (GAID), Android ID, IMEI, serial number or any other device identifier.
- No precise or coarse location from the device; no location permission is requested.
- No contacts, accounts, installed-app lists, call logs, SMS, photos or files.
- No names, emails, phone numbers or other personal details.
- No persistent user identifier: nothing is stored that could recognize the same person across apps or sessions.

## Ephemeral session

At start-up the backend issues a short-lived signed session token (one hour by default) carrying a **random** session id, the app id, package name, timestamps and the test flag. The token is kept in memory only, never written to disk and never logged; a new one is created on the next start or when it expires. Frequency caps use this session id, so they reset with the session by design.

On the device the SDK stores only the last remote placement configuration (no user data) in a private SharedPreferences file, and downloaded creatives in the app's cache directory, deleted once their ads expire.

## Server side

- The IP address is used transiently to infer an approximate location (country, region and city, from Cloudflare's visitor location headers or a location database on our own server) and is never stored. Nothing more precise than a city is kept. Security logs keep only a salted hash of the client network where needed for fraud checks.
- Each impression stores the country, region, city and the device language the SDK reported, so advertisers and publishers can break their reports down by location and language.
- Impression tokens are stored as SHA-256 hashes only.
- Long-term reporting uses aggregated daily statistics per app, placement and campaign.

## Consent API

```kotlin
QartveloAds.setPrivacy(
    QartveloAdsPrivacy(
        consentGiven = true,        // result of YOUR consent flow; null = unknown
        childDirected = false,      // app or request treated as child-directed; null = unknown
        underAgeOfConsent = null,   // user under the age of consent; null = unknown
    ),
)
```

```ts
QartveloAds.setPrivacy({ consentGiven: true, childDirected: false });
```

- Every field defaults to unknown. The SDK never assumes or claims consent and never shows consent UI.
- Call it any time, before or after `initialize`; new values are forwarded to the fallback adapter immediately.
- Qartvelo Ads serves contextual ads that do not depend on consent for personalization; the signals mainly govern the AdMob fallback.

## AdMob fallback and consent

Google's SDK is subject to Google's policies and your agreement with Google. Collect consent where required (for example with Google's User Messaging Platform or a certified CMP) before ads are requested. The adapter respects your Google Mobile Ads configuration and only adds restrictions:

- `childDirected = true` sets Google's age-restricted treatment to `CHILD`; `underAgeOfConsent = true` sets it to `TEEN`. `false` or `null` leaves your own `RequestConfiguration` untouched, and a stricter value you set yourself is never relaxed.
- `consentGiven = false` requests non-personalized AdMob ads (`npa=1`).
- The adapter never writes TCF/UMP consent strings and never grants consent.

## Retention

- Raw security logs are deleted after the retention period (default 30 days).
- Impressions, clicks and reward events are kept for billing, payouts and fraud review, but the per-event session hash, country, region and city are removed after the retention period.
- On the device, the cached configuration is replaced on every successful start; creative files are removed when their ads expire (at most 30 minutes) or on the next start. Uninstalling the app removes everything.

## Google Play Data safety

Use the tables above when filling in your Data safety form. The Qartvelo Ads SDK itself does not collect personal information, device identifiers or location from the device, and all traffic is encrypted in transit (HTTPS). The server does derive an approximate location (down to the city) from each ad request's IP address and stores it with ad events for reporting, so consider declaring **Approximate location** as collected, for *Advertising or marketing* and *Analytics*. Ad interaction events (impressions, clicks, reward completions) are sent to serve and bill ads and to prevent fraud. If you use the AdMob fallback, also include Google Mobile Ads' disclosures, which Google publishes for its SDK. You are responsible for your app's declarations.
