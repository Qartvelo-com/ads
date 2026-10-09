package com.qartvelo.sdk

import android.app.Activity
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.Robolectric

class SetupIssueTest : SdkTest() {
    private val issues = mutableListOf<QartveloAdsSetupIssue>()

    @Before
    fun collectIssues() {
        QartveloAds.addEventListener(object : QartveloAdsListener {
            override fun onSetupIssue(issue: QartveloAdsSetupIssue) {
                issues += issue
            }
        })
    }

    private fun rejectInit(code: String, details: JSONObject?) {
        backend.initStatus = 403
        backend.initErrorCode = code
        backend.initErrorDetails = details
    }

    @Test
    fun packageMismatchNamesBothPackagesOnce() {
        rejectInit("package_mismatch", JSONObject().put("registered_package", "com.other.app").put("platform", "android"))
        init()
        awaitMain(message = "setup issue") { issues.isNotEmpty() }
        val issue = issues.single()
        assertEquals(QartveloAdsSetupIssue.PACKAGE_MISMATCH, issue.code)
        assertNull(issue.placementId)
        assertTrue(issue.message, issue.message.contains("com.other.app") && issue.message.contains(app.packageName))

        QartveloAds.loadInterstitial("game_end", listener)
        awaitMain { listener.has("loadFailed") }
        assertEquals("reported once per process", 1, issues.size)
    }

    @Test
    fun olderBackendsWithoutDetailsStillGetAMessage() {
        rejectInit("package_mismatch", null)
        init()
        awaitMain(message = "setup issue") { issues.isNotEmpty() }
        assertTrue(issues.single().message, issues.single().message.contains(app.packageName))
    }

    @Test
    fun aJsonNullRegisteredPackageGetsTheFallbackMessage() {
        rejectInit("package_mismatch", JSONObject().put("registered_package", JSONObject.NULL).put("platform", "android"))
        init()
        awaitMain(message = "setup issue") { issues.isNotEmpty() }
        val message = issues.single().message
        assertTrue(message, message.contains("is not registered for '${app.packageName}'"))
        assertTrue(message, !message.contains("'null'"))
    }

    @Test
    fun platformMismatchNamesThePlatformOfTheKey() {
        rejectInit("platform_mismatch", JSONObject().put("platform", "ios"))
        init()
        awaitMain(message = "setup issue") { issues.isNotEmpty() }
        assertEquals(QartveloAdsSetupIssue.PLATFORM_MISMATCH, issues.single().code)
        assertTrue(issues.single().message, issues.single().message.contains("the iOS app"))
    }

    @Test
    fun otherInitErrorsAreNotSetupIssues() {
        backend.initStatus = 401
        init()
        QartveloAds.loadInterstitial("game_end", listener)
        awaitMain { listener.has("loadFailed") }
        assertTrue(issues.toString(), issues.isEmpty())
    }

    @Test
    fun unknownPlacementIsReportedOncePerCode() {
        assertTrue(init())
        loadAndWait(AdFormat.INTERSTITIAL, "level_up")
        loadAndWait(AdFormat.INTERSTITIAL, "level_up")
        awaitMain(message = "setup issue") { issues.isNotEmpty() }
        val issue = issues.single()
        assertEquals(QartveloAdsSetupIssue.UNKNOWN_PLACEMENT, issue.code)
        assertEquals("level_up", issue.placementId)
        assertTrue(issue.message, issue.message.contains("format interstitial"))
    }

    @Test
    fun formatMismatchIsReported() {
        assertTrue(init())
        loadAndWait(AdFormat.INTERSTITIAL, "home_banner")
        awaitMain(message = "setup issue") { issues.isNotEmpty() }
        assertEquals(QartveloAdsSetupIssue.FORMAT_MISMATCH, issues.single().code)
        assertTrue(issues.single().message, issues.single().message.contains("format banner"))
    }

    @Test
    fun knownPlacementsReportNothing() {
        assertTrue(init())
        loadAndWait(AdFormat.INTERSTITIAL, "game_end")
        loadAndWait(AdFormat.REWARDED, "reward_coins")
        assertTrue(issues.toString(), issues.isEmpty())
    }

    @Test
    fun showChecksThePlacementToo() {
        assertTrue(init())
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        QartveloAds.showRewarded(activity, "missing_reward", listener)
        awaitMain(message = "setup issue") { issues.isNotEmpty() }
        assertEquals("missing_reward", issues.single().placementId)
    }

    @Test
    fun placementsUsedBeforeConfigArrivesAreCheckedOnceItDoes() {
        backend.initDelayMs = 300
        QartveloAds.initialize(app, APP_KEY, options()) { _, _ -> }
        QartveloAds.loadInterstitial("early_code", listener)
        awaitMain(message = "setup issue for early_code") { issues.any { it.placementId == "early_code" } }
        assertEquals(QartveloAdsSetupIssue.UNKNOWN_PLACEMENT, issues.single().code)
    }

    @Test
    fun placementsParkedBeforeFreshConfigAreJudgedWhenItArrives() {
        // No cached config: loads wait for the grace period, then run while the backend is still silent.
        backend.initDelayMs = 1_000
        QartveloAds.initialize(app, APP_KEY, options(timeoutMs = 300)) { _, _ -> }
        awaitMain(message = "initialize request") { backend.count("/api/v1/sdk/initialize") == 1 }
        settle(50)
        QartveloAds.loadInterstitial("early_code", listener)
        advance(300)
        settle(50)
        assertTrue("config has not arrived, so nothing is judged yet: $issues", issues.isEmpty())

        awaitMain(message = "setup issue for early_code") { issues.any { it.placementId == "early_code" } }
        val issue = issues.single()
        assertEquals(QartveloAdsSetupIssue.UNKNOWN_PLACEMENT, issue.code)
        assertTrue(issue.message, issue.message.contains("format interstitial"))
    }

    @Test
    fun rejectedKeysNeverJudgePlacements() {
        rejectInit("package_mismatch", null)
        init()
        loadAndWait(AdFormat.INTERSTITIAL, "level_up")
        awaitMain(message = "setup issue") { issues.isNotEmpty() }
        assertEquals(listOf(QartveloAdsSetupIssue.PACKAGE_MISMATCH), issues.map { it.code })
    }
}
