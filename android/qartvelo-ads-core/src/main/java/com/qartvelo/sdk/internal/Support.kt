package com.qartvelo.sdk.internal

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.net.Uri
import android.os.Build
import com.qartvelo.sdk.DEFAULT_BASE_URL
import com.qartvelo.sdk.QartveloAds
import com.qartvelo.sdk.QartveloAdsListener
import com.qartvelo.sdk.fallback.FallbackAdapter
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/** Global observers plus per-call listeners. Delivery is always posted to the main thread, in order. */
internal object Listeners {
    private val global = CopyOnWriteArrayList<QartveloAdsListener>()

    fun add(listener: QartveloAdsListener) {
        global.addIfAbsent(listener)
    }

    fun remove(listener: QartveloAdsListener) {
        global.remove(listener)
    }

    fun clear() = global.clear()

    /**
     * Delivers [block] once to every distinct listener among [targets] and, unless [includeGlobal] is
     * false, to the global observers.
     */
    fun emit(
        targets: Collection<QartveloAdsListener?>,
        event: String,
        includeGlobal: Boolean = true,
        block: (QartveloAdsListener) -> Unit,
    ) {
        val local = targets.filterNotNull()
        Main.post {
            val all = LinkedHashSet<QartveloAdsListener>(local)
            if (includeGlobal) all.addAll(global)
            for (listener in all) {
                try {
                    block(listener)
                } catch (t: Throwable) {
                    OurLog.e("Listener threw from $event", t)
                }
            }
        }
    }

    fun emit(target: QartveloAdsListener?, event: String, block: (QartveloAdsListener) -> Unit): Unit =
        emit(listOf(target), event, block = block)
}

/** Internal seams so JVM tests can substitute slow or platform-bound collaborators. */
internal object TestHooks {
    const val ADMOB_ADAPTER_CLASS = "com.qartvelo.admob.AdMobFallbackAdapter"

    @Volatile
    var adapterClassName: String = ADMOB_ADAPTER_CLASS

    @Volatile
    var eventRetryBaseMs: Long? = null

    @Volatile
    var videoPlayerFactory: ((Context) -> AdVideoPlayer)? = null

    @Volatile
    var creativeGraceMs: Long? = null

    /** Overrides emulator detection (Robolectric is not detected as an emulator). */
    @Volatile
    var emulator: Boolean? = null

    fun reset() {
        adapterClassName = ADMOB_ADAPTER_CLASS
        eventRetryBaseMs = null
        videoPlayerFactory = null
        creativeGraceMs = null
        emulator = null
    }
}

/**
 * Emulators are always in test mode, like AdMob test devices: emulator traffic is never billed.
 * Only strong, well-known build markers of the Android Studio emulator, Genymotion and common PC
 * players are used, so real phones are never mistaken for emulators.
 */
internal object Emulator {
    private val detected: Boolean by lazy {
        detect(Build.FINGERPRINT, Build.HARDWARE, Build.PRODUCT, Build.MODEL, Build.MANUFACTURER, Build.BRAND, Build.DEVICE)
    }

    fun current(): Boolean = TestHooks.emulator ?: detected

    fun detect(
        fingerprint: String?,
        hardware: String?,
        product: String?,
        model: String?,
        manufacturer: String?,
        brand: String?,
        device: String?,
    ): Boolean {
        val f = fingerprint.orEmpty().lowercase(Locale.ROOT)
        val h = hardware.orEmpty().lowercase(Locale.ROOT)
        val p = product.orEmpty().lowercase(Locale.ROOT)
        val m = model.orEmpty().lowercase(Locale.ROOT)
        return h == "goldfish" || h == "ranchu" || h.startsWith("vbox86") ||
            f.startsWith("generic") || f.contains("/sdk_gphone") || f.contains("/sdk_google") || f.contains("vbox86p") ||
            p == "sdk" || p.startsWith("sdk_") || p.contains("google_sdk") || p.contains("vbox86p") ||
            p.contains("emulator") || p.contains("simulator") ||
            m.contains("emulator") || m.startsWith("android sdk built for") || m.contains("google_sdk") ||
            manufacturer.orEmpty().contains("Genymotion", ignoreCase = true) ||
            (brand.orEmpty().startsWith("generic") && device.orEmpty().startsWith("generic"))
    }
}

/**
 * Finds the optional AdMob adapter without a compile-time dependency. Apps that ship only
 * `qartvelo-ads-core` hit the catch below instead of ClassNotFoundException/NoClassDefFoundError.
 */
internal object FallbackDiscovery {
    fun discover(className: String = TestHooks.adapterClassName): FallbackAdapter? = try {
        val instance = Class.forName(className).getDeclaredConstructor().newInstance()
        (instance as? FallbackAdapter).also {
            if (it != null) OurLog.i("Fallback adapter found: ${it.networkName}")
        }
    } catch (_: Throwable) {
        OurLog.i("No fallback adapter on the classpath; QartveloAds-only mode")
        null
    }
}

/** Contextual, non-identifying device facts sent with requests. No advertising id, no IP, no GPS. */
internal class DeviceInfo(
    val packageName: String,
    val appVersion: String,
    val osVersion: String,
    val androidMajor: String,
    val screenWidth: Int,
    val screenHeight: Int,
) {
    companion object {
        /** Cheap fields only; [collect] fills in the package manager lookup off the main thread. */
        fun basic(context: Context): DeviceInfo = build(context, appVersion = "unknown")

        fun collect(context: Context): DeviceInfo {
            val version = try {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            } catch (_: Throwable) {
                null
            }
            return build(context, version ?: "unknown")
        }

        private fun build(context: Context, appVersion: String): DeviceInfo {
            val metrics = Resources.getSystem().displayMetrics
            val release = Build.VERSION.RELEASE ?: ""
            return DeviceInfo(
                packageName = context.packageName,
                appVersion = appVersion,
                osVersion = release,
                androidMajor = release.substringBefore('.').ifEmpty { Build.VERSION.SDK_INT.toString() },
                screenWidth = metrics.widthPixels,
                screenHeight = metrics.heightPixels,
            )
        }
    }
}

/** Opens advertiser destinations with a plain, browsable VIEW intent. Only http(s) is allowed. */
internal object ClickOpener {
    fun open(context: Context, url: String): Boolean {
        val uri = try {
            Uri.parse(url)
        } catch (_: Throwable) {
            return false
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "https" && scheme != "http") {
            OurLog.e("Refusing to open non-http destination")
            return false
        }
        val intent = Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            OurLog.e("No app can open the advertiser destination")
            false
        } catch (t: Throwable) {
            OurLog.e("Failed to open advertiser destination", t)
            false
        }
    }
}

/**
 * The "Ad" badge on Qartvelo Ads creatives links to the Qartvelo Ads website with
 * `ref=<host app package>`, so the network can see which app a visitor came from. It is not an ad
 * click: no event is sent and the advertiser's destination is not opened.
 */
internal object AboutLink {
    fun url(context: Context): String {
        val base = QartveloAds.engine()?.options?.baseUrl?.takeIf { it.isNotBlank() } ?: DEFAULT_BASE_URL
        return Uri.parse(base).buildUpon()
            .path("/")
            .clearQuery()
            .appendQueryParameter("ref", context.packageName)
            .build()
            .toString()
    }

    fun open(context: Context): Boolean = ClickOpener.open(context, url(context))
}
