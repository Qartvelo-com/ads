# Qartvelo Ads privacy

Qartvelo Ads targets ads by **context**, not by person. The SDK and backend are built so that no
cross-app profile of a user can exist.

## Contextual targeting only

Campaigns can target: country (derived on the server from the request's network address), app,
app category, placement, ad format, app language and Android major version. There is no
behavioral, interest or audience targeting, and no retargeting.

## Data the SDK sends

| Request | Fields |
|---|---|
| `POST /api/v1/sdk/initialize` | app key, package name, SDK version, app version, platform, OS version, test flag |
| `POST /api/v1/ads/request` | app key, placement code, format, session token, device language, Android major version, app and SDK version, screen width/height in pixels, test flags |
| `POST /api/v1/events/impression`, `click`, `reward` | request id, signed impression token, reward completion flag |
| `POST /api/v1/events/fallback` | session token, placement code, fallback reason |

The HTTP user agent contains the SDK version, Android version and package name.

## Data the SDK never collects

- No Advertising ID (GAID), Android ID, IMEI, serial number or any other device identifier.
- No precise or coarse location from the device; no location permission is requested.
- No contacts, accounts, installed-app lists, call logs, SMS, photos or files.
- No names, emails, phone numbers or other personal details.
- No persistent Qartvelo Ads user identifier: nothing is stored that could recognize the same person
  across apps or across sessions.

## Ephemeral session

At start-up the backend issues a short-lived, signed session token (default lifetime one hour)
that carries a **random** session id, the app id, package name, timestamps and the test flag. The
token is kept in memory only, never written to disk and never logged; a new one is created on the
next app start or when it expires. Frequency caps use this session id, so they reset with the
session by design.

The SDK stores on the device only: the last remote placement configuration (no user data) in a
private SharedPreferences file, and downloaded creatives in the app's cache directory, deleted once
the ads that use them expire.

## Server side

- The raw IP address is used transiently to infer the country and is never stored. Security logs
  keep only a salted hash where an address is needed for fraud checks.
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

- Every field defaults to `null` (unknown). The SDK never assumes or claims consent on behalf of
  your app and never shows consent UI.
- You can call `setPrivacy` at any time, before or after `initialize`; new values are forwarded to
  the fallback adapter immediately.
- Qartvelo Ads itself serves contextual ads that do not depend on consent for personalization; the
  signals mainly govern the AdMob fallback.

## AdMob fallback and consent

When you use the AdMob adapter, Google's SDK is subject to Google's policies and your agreement
with Google. You are responsible for collecting consent where required (for example with Google's
User Messaging Platform or a certified CMP) before ads are requested.

The adapter respects your Google Mobile Ads configuration and only adds restrictions:

- `childDirected = true` sets Google's age-restricted treatment to `CHILD`;
  `underAgeOfConsent = true` sets it to `TEEN`. `false` or `null` leaves your own
  `RequestConfiguration` untouched, and a stricter value you set yourself is never relaxed.
- `consentGiven = false` requests non-personalized AdMob ads (`npa=1`).
- The adapter never writes TCF/UMP consent strings and never grants consent.

## Retention

- Raw security logs (`suspicious_events`) are pruned after `raw_log_retention_days`
  (admin setting, default 30 days) by the daily `ourads:prune-logs` command.
- Impressions, clicks and reward events are retained for billing, payouts and fraud review, but
  the same command strips the per-event `session_hash` and `country` from impressions older than
  `raw_log_retention_days`. Dashboards and long-term reports read aggregated daily statistics.
- Raw IP addresses are never stored: only a salted hash of the client network appears in
  `suspicious_events`, and `CF-IPCountry` is trusted only from configured proxies.
- On the device: the cached configuration is replaced on every successful start; creative files are
  removed when their ads expire (at most the ad lifetime, 30 minutes by default) or on the next start.
- Removing the app removes everything the SDK stored on the device.
