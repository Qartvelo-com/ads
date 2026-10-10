package com.qartvelo.sample

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import com.qartvelo.sdk.AdFormat
import com.qartvelo.sdk.BannerSizing
import com.qartvelo.sdk.QartveloAdsAdInfo
import com.qartvelo.sdk.QartveloAdsBannerView
import com.qartvelo.sdk.QartveloAdsError
import com.qartvelo.sdk.QartveloAdsListener

/** Banner demo. Rotating re-creates this activity; the banner controller reuses the loaded ad. */
class BannerActivity : Activity() {
    private lateinit var banner: QartveloAdsBannerView
    private lateinit var inlineBanner: QartveloAdsBannerView
    private lateinit var log: TextView
    private val refresh: () -> Unit = { log.text = EventLog.text }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_banner)
        log = findViewById(R.id.log)
        banner = findViewById(R.id.banner)
        // Per-view listener (global events are logged by EventLog.globalListener as well).
        banner.listener = object : QartveloAdsListener {
            override fun onLoaded(info: QartveloAdsAdInfo) {
                title = "Banner via ${info.source.name.lowercase()}"
            }

            override fun onLoadFailed(placementId: String, error: QartveloAdsError) {
                title = "Banner: ${error.code}"
            }

            override fun onNoAdAvailable(placementId: String, format: AdFormat) {
                title = "Banner: no ad"
            }
        }
        banner.load()

        // A second placement: banners of one placement share one ad, so each slot needs its own.
        inlineBanner = findViewById(R.id.inline_banner)
        inlineBanner.sizing = BannerSizing.INLINE
        inlineBanner.inlineMaxHeightDp = 250
        inlineBanner.load()
    }

    override fun onStart() {
        super.onStart()
        EventLog.observe(refresh)
        refresh()
    }

    override fun onStop() {
        EventLog.unobserve(refresh)
        super.onStop()
    }

    override fun onDestroy() {
        banner.destroy()
        inlineBanner.destroy()
        super.onDestroy()
    }
}
