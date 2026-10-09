package com.qartvelo.sdk

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.qartvelo.sdk.fallback.FallbackAdapter
import com.qartvelo.sdk.internal.QartveloAdsActivity
import com.qartvelo.sdk.internal.TestHooks
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
abstract class SdkTest {
    protected val server = MockWebServer()
    protected val backend = FakeBackend()
    protected val listener = RecordingListener("call")
    protected val global = RecordingListener("global")
    protected val app: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun baseSetUp() {
        ShadowLog.stream = System.out
        QartveloAds.resetForTests()
        TestHooks.reset()
        TestHooks.eventRetryBaseMs = 5
        server.dispatcher = backend
        server.start()
        backend.baseUrl = server.url("/").toString()
        backend.defaultCreatives()
        QartveloAds.addEventListener(global)
    }

    @After
    fun baseTearDown() {
        QartveloAds.resetForTests()
        TestHooks.reset()
        try {
            server.shutdown()
        } catch (_: Throwable) {
        }
    }

    protected fun options(
        timeoutMs: Long = 800,
        testMode: Boolean = false,
        forceNoFill: Boolean = false,
        admobFallback: Boolean = true,
        units: Map<String, String> = emptyMap(),
        testModeInDebugBuilds: Boolean = false,
        admobTestUnitsInDebugBuilds: Boolean = false,
    ) = QartveloAdsOptions(
        admobFallback = admobFallback,
        requestTimeoutMs = timeoutMs,
        testMode = testMode,
        testForceNoFill = forceNoFill,
        logLevel = QartveloAdsLogLevel.DEBUG,
        baseUrl = backend.baseUrl,
        admobAdUnits = units,
        testModeInDebugBuilds = testModeInDebugBuilds,
        admobTestUnitsInDebugBuilds = admobTestUnitsInDebugBuilds,
    )

    /** Initializes against the fake backend and waits for the first attempt to finish. */
    protected fun init(options: QartveloAdsOptions = options(), adapter: FallbackAdapter? = null): Boolean {
        adapter?.let { QartveloAds.registerFallbackAdapter(it) }
        var result: Boolean? = null
        QartveloAds.initialize(app, APP_KEY, options) { success, _ -> result = success }
        awaitMain(message = "initialization") { result != null }
        return result!!
    }

    protected fun hostActivity(): ActivityController<Activity> = Robolectric.buildActivity(Activity::class.java).setup()

    /** The QartveloAdsActivity launch requested by [host], driven through its lifecycle. */
    internal fun launchedAdActivity(host: Activity): ActivityController<QartveloAdsActivity> {
        val intent: Intent = shadowOf(host).nextStartedActivity
            ?: throw AssertionError("no activity was started")
        check(intent.component?.className == QartveloAdsActivity::class.java.name) { "unexpected activity $intent" }
        return Robolectric.buildActivity(QartveloAdsActivity::class.java, intent).setup()
    }

    protected fun loadAndWait(format: AdFormat, placement: String, l: RecordingListener = listener) {
        if (format == AdFormat.REWARDED) QartveloAds.loadRewarded(placement, l) else QartveloAds.loadInterstitial(placement, l)
        awaitMain(message = "load outcome for $placement: $l") { l.has("loaded:$placement") || l.has("loadFailed:$placement") }
    }

    companion object {
        const val APP_KEY = "app_test_key_0001"
    }
}
