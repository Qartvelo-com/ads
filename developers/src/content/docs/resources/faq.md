---
title: FAQ
description: Frequently asked questions about Qartvelo Ads for publishers and developers.
---

### Do I have to remove AdMob?

No. Keep your AdMob account and ad units; Qartvelo Ads uses them as the fallback for every request it cannot fill. AdMob revenue keeps going directly to you.

### Does the SDK slow down my app?

No network or disk work happens on your calling thread, loads are asynchronous, the Qartvelo Ads request has an 800 ms budget by default, and AdMob preloads in parallel. Start-up does not wait for the backend when a cached configuration exists.

### Does Qartvelo Ads support iOS?

Yes, natively since 0.4.0: the [iOS SDK](/ios/installation/) for Swift and Objective-C (Swift Package Manager or CocoaPods), with the same placements, fallback and test mode as Android. Register the iOS version as its own app (platform iOS, bundle ID). The React Native plugin is still Android only: it rejects with `unsupported_platform` on iOS, so shared code keeps working; use `QartveloAds.isSupported()` to hide ad UI there.

### Is the app key a secret?

No. It is public by design and is only accepted together with your registered package name. The SDK secret is the secret; never put it in an app.

### Can I test before my app is approved?

Yes. Test mode works for pending apps and pending accounts.

### Can I use one placement code for two banners?

Only one banner per placement code can be visible at a time. Create one placement per simultaneously visible banner.

### How is my revenue calculated?

Each Qartvelo Ads impression earns the campaign's CPM bid / 1000 times your revenue share (70% by default), in GEL. See [Reports and payouts](/publishers/reports-and-payouts/).

### What does the SDK collect?

No advertising ID, no device identifiers, no location, no personal data. See [Privacy](/guides/privacy/).

### Do I need a consent dialog for Qartvelo Ads?

Qartvelo Ads ads are contextual and do not use personal data for personalization. Google's AdMob, used as fallback, has its own consent requirements; collect consent with your CMP where required and pass the result with `setPrivacy`.

### Can I use Qartvelo Ads with another network than AdMob?

Yes, by implementing the [fallback adapter interface](/guides/custom-fallback-adapter/).

### Which Android versions are supported?

Android 6.0 (API 23) and newer for native apps; React Native requires API 24.

### Where is the source code?

[github.com/Qartvelo-com/ads](https://github.com/Qartvelo-com/ads), MIT licensed. It includes a native sample app and a React Native example.
