package com.qartvelo.sample

import android.app.Application
import android.content.Context
import com.qartvelo.sdk.AdFormat
import com.qartvelo.sdk.QartveloAds
import com.qartvelo.sdk.QartveloAdsAdInfo
import com.qartvelo.sdk.QartveloAdsError
import com.qartvelo.sdk.QartveloAdsListener
import com.qartvelo.sdk.QartveloAdsLogLevel
import com.qartvelo.sdk.QartveloAdsOptions
import com.qartvelo.sdk.QartveloAdsPrivacy
import com.qartvelo.sdk.QartveloAdsReward
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SampleApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val settings = SampleSettings.load(this)

        // The demo has no consent UI, so it reports "unknown"; real apps pass their CMP result.
        QartveloAds.setPrivacy(QartveloAdsPrivacy(consentGiven = null, childDirected = false))
        QartveloAds.addEventListener(EventLog.globalListener)

        QartveloAds.initialize(
            context = this,
            appKey = APP_KEY,
            options = QartveloAdsOptions(
                admobFallback = true,
                requestTimeoutMs = 800,
                testMode = settings.testMode,
                testForceNoFill = settings.forceNoFill,
                logLevel = if (BuildConfig.DEBUG) QartveloAdsLogLevel.DEBUG else QartveloAdsLogLevel.ERROR,
                baseUrl = settings.baseUrl,
                admobAdUnits = ADMOB_UNITS,
            ),
        ) { success, error ->
            EventLog.add(if (success) "initialized" else "initialize failed: ${error?.code} ${error?.message}")
        }
    }

    companion object {
        const val APP_KEY = "app_demo_sample_android_0001"
        const val INTERSTITIAL = "game_end"
        const val REWARDED = "reward_coins"
        const val BANNER = "home_banner"
        const val INLINE_BANNER = "inline_banner"

        /** The publisher's own AdMob units per placement (Google's public test units for this demo). */
        val ADMOB_UNITS = mapOf(
            BANNER to "ca-app-pub-3940256099942544/9214589741",
            INLINE_BANNER to "ca-app-pub-3940256099942544/9214589741",
            INTERSTITIAL to "ca-app-pub-3940256099942544/1033173712",
            REWARDED to "ca-app-pub-3940256099942544/5224354917",
        )
    }
}

data class SampleSettings(val baseUrl: String, val testMode: Boolean, val forceNoFill: Boolean) {
    fun save(context: Context) {
        prefs(context).edit()
            .putString(KEY_URL, baseUrl)
            .putBoolean(KEY_TEST, testMode)
            .putBoolean(KEY_NO_FILL, forceNoFill)
            .commit()
    }

    companion object {
        const val DEFAULT_BASE_URL = "http://10.0.2.2:8000/"
        private const val KEY_URL = "base_url"
        private const val KEY_TEST = "test_mode"
        private const val KEY_NO_FILL = "force_no_fill"

        fun load(context: Context): SampleSettings {
            val p = prefs(context)
            return SampleSettings(
                baseUrl = p.getString(KEY_URL, null) ?: DEFAULT_BASE_URL,
                testMode = p.getBoolean(KEY_TEST, false),
                forceNoFill = p.getBoolean(KEY_NO_FILL, false),
            )
        }

        private fun prefs(context: Context) = context.getSharedPreferences("sample_settings", Context.MODE_PRIVATE)
    }
}

/** In-memory event log shown on screen. Main thread only (SDK callbacks arrive on the main thread). */
object EventLog {
    private const val MAX_LINES = 200
    private val lines = ArrayDeque<String>()
    private val observers = LinkedHashSet<() -> Unit>()
    private val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    val text: String get() = lines.joinToString("\n")

    fun add(message: String) {
        lines.addFirst("${time.format(Date())}  $message")
        while (lines.size > MAX_LINES) lines.removeLast()
        observers.toList().forEach { it() }
    }

    fun clear() {
        lines.clear()
        observers.toList().forEach { it() }
    }

    fun observe(observer: () -> Unit) {
        observers.add(observer)
    }

    fun unobserve(observer: () -> Unit) {
        observers.remove(observer)
    }

    private fun AdFormat.label() = name.lowercase()
    private fun QartveloAdsAdInfo.label() = "$placementId/${format.label()} via ${source.name.lowercase()}" +
        (campaignId?.let { " campaign=$it" } ?: "")

    /** Logs every event of every placement through QartveloAds.addEventListener. */
    val globalListener = object : QartveloAdsListener {
        override fun onLoaded(info: QartveloAdsAdInfo) = add("loaded ${info.label()}")
        override fun onLoadFailed(placementId: String, error: QartveloAdsError) = add("loadFailed $placementId ${error.code}: ${error.message}")
        override fun onShown(info: QartveloAdsAdInfo) = add("shown ${info.label()}")
        override fun onImpression(info: QartveloAdsAdInfo) = add("impression ${info.label()}")
        override fun onClicked(info: QartveloAdsAdInfo) = add("clicked ${info.label()}")
        override fun onDismissed(info: QartveloAdsAdInfo) = add("dismissed ${info.label()}")
        override fun onReward(info: QartveloAdsAdInfo, reward: QartveloAdsReward) = add("REWARD ${reward.amount} ${reward.type} ${info.label()}")
        override fun onFallbackStarted(placementId: String, format: AdFormat, reason: String) =
            add("fallbackStarted $placementId/${format.label()} reason=$reason")
        override fun onNoAdAvailable(placementId: String, format: AdFormat) = add("noAdAvailable $placementId/${format.label()}")
    }
}
