---
title: Account and apps
description: Create a publisher account, register your Android or iOS app and manage its app key.
---

## Create a publisher account

1. Go to [ads.qartvelo.com/register](https://ads.qartvelo.com/register).
2. Choose **Publisher**, enter your name, company name, email and a password.
3. Confirm your email address. The dashboard opens after verification.

New publisher accounts start as **pending** until a Qartvelo Ads admin approves them. While pending you can already register apps, create placements and test with [test mode](/get-started/test-mode/); live ads are served only once the account and the app are approved.

| Account status | What it means |
|---|---|
| pending | Waiting for review. Apps and placements can be managed; only test ads are served |
| approved | Live ads can be served to approved apps |
| suspended | Serving is stopped; apps and placements are read-only |
| rejected | The account was not accepted |

## Register an app

Under **Apps**, choose **Add app** and fill in:

| Field | Rules |
|---|---|
| Name | Shown in your dashboard and to advertisers who target specific apps |
| Platform | **Android** or **iOS**. Locked after creation; an app key only works on its platform. Publish both versions of a game as two apps |
| Package name / Bundle ID | Your Android `applicationId` (for example `com.example.game`) or iOS bundle ID (for example `ge.example.Game`). Must be unique per platform on Qartvelo Ads. **Locked after the app is reviewed**, because the SDK validates it on every start |
| Category | One of: games, news, entertainment, education, lifestyle, sports, finance, shopping, social, tools, travel, health, music, other. Advertisers can target categories |
| Default language | `ka`, `en` or `ru`. Used for language targeting when the device does not report a supported language |

Each new app is **pending** until an admin approves it. Apps can also be **rejected** or **suspended**.

:::caution[Package name must match exactly]
The backend compares the package name sent by the SDK with the registered one. If your debug build uses an `applicationIdSuffix` such as `.debug`, it is a different package: initialization fails with `package_mismatch`. Register the suffix-less id and test with the same package, or remove the suffix for ad testing.
:::

## Credentials

The app page shows two credentials:

| Credential | Where it goes | Secret? |
|---|---|---|
| **App key** `app_` + 24 characters | In your app code (`QartveloAds.initialize`) | No. It is public by design and is only accepted together with the registered package name |
| **SDK secret** | Nowhere in the app. Store it on your server | Yes. Shown once when created; generate a new one if lost. Reserved for server-to-server features |

Never embed the SDK secret in an APK or JavaScript bundle.

### Rotating credentials

- **Generate new app key**: builds that still use the old key stop receiving Qartvelo Ads ads and fall back to AdMob until you ship an update with the new key. Only rotate a key that was abused.
- **Generate new SDK secret**: the old secret stops working immediately; the new one is shown once.

## The Integration panel

The app page has an **Integration** panel with Kotlin and React Native snippets already filled in with your app key, the API URL and your placement codes. Copy them as a starting point; the rest of this documentation explains every option.

## Deleting an app

Apps without traffic history can be deleted from the app page. Apps that have served traffic are kept for billing and reporting.
