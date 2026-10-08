---
title: Initialization
description: Initialize the SDK once, configure options, and pass privacy signals.
---

Initialize once, as early as possible, normally in `Application.onCreate`. The call returns immediately; networking happens in the background.

```kotlin title="MyApp.kt"
import com.qartvelo.sdk.QartveloAds
import com.qartvelo.sdk.QartveloAdsLogLevel
import com.qartvelo.sdk.QartveloAdsOptions
import com.qartvelo.sdk.QartveloAdsPrivacy

class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()

        // Optional, any time: privacy signals from your consent flow (see Privacy guide)
        QartveloAds.setPrivacy(QartveloAdsPrivacy(consentGiven = consentOrNull()))

        QartveloAds.initialize(
            context = this,
            appKey = "app_xxxxxxxxxxxxxxxxxxxxxxxx",
            options = QartveloAdsOptions(
                testMode = BuildConfig.DEBUG,
                logLevel = if (BuildConfig.DEBUG) QartveloAdsLogLevel.DEBUG else QartveloAdsLogLevel.ERROR,
            ),
        ) { success, error ->
            // Main thread. success == false still leaves the SDK usable.
        }
    }
}
```

## Options

`QartveloAdsOptions` is a data class; every field has a default.

| Option | Type | Default | Meaning |
|---|---|---|---|
| `admobFallback` | `Boolean` | `true` | Allow the AdMob adapter to serve your units when Qartvelo Ads cannot |
| `requestTimeoutMs` | `Long` | `800` | Qartvelo Ads time budget before falling back. A per-placement value from the dashboard wins. Clamped to 100..10000 |
| `testMode` | `Boolean` | `false` | Non-billable test ads labelled "Test ad"; AdMob uses Google's test units. See [Test mode](/get-started/test-mode/) |
| `testModeInDebugBuilds` | `Boolean` | `true` | Turn test mode on automatically when the app is debuggable (debug builds) |
| `testForceNoFill` | `Boolean` | `false` | Force Qartvelo Ads `no_fill` to exercise the fallback |
| `logLevel` | `QartveloAdsLogLevel` | `ERROR` | `NONE`, `ERROR`, `INFO`, `DEBUG`. Logcat tag `QartveloAds` |
| `baseUrl` | `String` | `https://ads.qartvelo.com/` | API origin. Change only for a self-hosted or local backend |
| `admobAdUnits` | `Map<String, String>` | empty | Placement code to your AdMob ad unit id. Wins over the dashboard value |

## Behaviour

- **Idempotent.** Only the first call counts. Later calls are ignored (a different app key or options are logged at `INFO`), but their listener still receives the first result. Restart the process to change options.
- **Only the application context is kept.** Passing an Activity is safe.
- **Never blocks loads on a slow backend.** With a cached remote config, loads start immediately and the Qartvelo Ads request obtains its session within its own timeout. Only the very first start (no cache yet) holds loads for up to `requestTimeoutMs` while the first configuration arrives.
- **Failure is not fatal.** If initialization fails (offline, backend down, key rejected), the listener gets `success = false`, the SDK runs on its cached config and can still fall back to AdMob. The session is retried lazily and throttled, so an outage never blocks your UI.
- `QartveloAds.isInitialized()` becomes `true` when the first attempt has finished, successfully or not.
- Loads before `initialize` was called at all fail with `NOT_INITIALIZED`.

## Initialization listener

```kotlin
fun interface QartveloAdsInitListener {
    fun onInitialized(success: Boolean, error: QartveloAdsError?)
}
```

Typical errors: `NETWORK_ERROR` and `TIMEOUT` (backend unreachable), `NOT_INITIALIZED` (empty or rejected app key, wrong package name, app not approved outside test mode).

## Runtime settings

```kotlin
QartveloAds.setLogLevel(QartveloAdsLogLevel.DEBUG)   // overrides options.logLevel
QartveloAds.setPrivacy(QartveloAdsPrivacy(childDirected = true))
```

Both can be called before or after `initialize`. See the [Privacy guide](/guides/privacy/) for what the privacy signals do.

## App key safety

The app key is public and identifies your app; the backend accepts it only together with the package name registered in the dashboard. Do not put the SDK secret in your app.
