package com.qartvelo.sample

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import com.qartvelo.sdk.QartveloAds
import kotlin.system.exitProcess

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var log: TextView
    private val refresh: () -> Unit = { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        log = findViewById(R.id.log)

        val settings = SampleSettings.load(this)
        val baseUrl = findViewById<EditText>(R.id.base_url).apply { setText(settings.baseUrl) }
        val testMode = findViewById<Switch>(R.id.test_mode).apply { isChecked = settings.testMode }
        val forceNoFill = findViewById<Switch>(R.id.force_no_fill).apply { isChecked = settings.forceNoFill }

        // QartveloAds.initialize is idempotent, so new options take effect after a process restart.
        findViewById<Button>(R.id.apply).setOnClickListener {
            SampleSettings(
                baseUrl = baseUrl.text.toString().trim().ifEmpty { SampleSettings.DEFAULT_BASE_URL },
                testMode = testMode.isChecked,
                forceNoFill = forceNoFill.isChecked,
            ).save(this)
            restartProcess()
        }

        findViewById<Button>(R.id.load_interstitial).setOnClickListener {
            EventLog.add("> loadInterstitial(${SampleApp.INTERSTITIAL})")
            QartveloAds.loadInterstitial(SampleApp.INTERSTITIAL)
        }
        findViewById<Button>(R.id.show_interstitial).setOnClickListener {
            EventLog.add("> showInterstitial(${SampleApp.INTERSTITIAL}) ready=${QartveloAds.isInterstitialReady(SampleApp.INTERSTITIAL)}")
            QartveloAds.showInterstitial(this, SampleApp.INTERSTITIAL)
        }
        findViewById<Button>(R.id.load_rewarded).setOnClickListener {
            EventLog.add("> loadRewarded(${SampleApp.REWARDED})")
            QartveloAds.loadRewarded(SampleApp.REWARDED)
        }
        findViewById<Button>(R.id.show_rewarded).setOnClickListener {
            EventLog.add("> showRewarded(${SampleApp.REWARDED}) ready=${QartveloAds.isRewardedReady(SampleApp.REWARDED)}")
            QartveloAds.showRewarded(this, SampleApp.REWARDED)
        }
        findViewById<Button>(R.id.open_banner).setOnClickListener {
            startActivity(Intent(this, BannerActivity::class.java))
        }
        findViewById<Button>(R.id.clear_log).setOnClickListener { EventLog.clear() }
    }

    override fun onStart() {
        super.onStart()
        EventLog.observe(refresh)
        render()
    }

    override fun onStop() {
        EventLog.unobserve(refresh)
        super.onStop()
    }

    private fun render() {
        val settings = SampleSettings.load(this)
        status.text = "SDK ${QartveloAds.SDK_VERSION}  initialized=${QartveloAds.isInitialized()}  " +
            "testMode=${settings.testMode}  forceNoFill=${settings.forceNoFill}"
        log.text = EventLog.text
    }

    private fun restartProcess() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        startActivity(intent)
        finishAffinity()
        exitProcess(0)
    }
}
