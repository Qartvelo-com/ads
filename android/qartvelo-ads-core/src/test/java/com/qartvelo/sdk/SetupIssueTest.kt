package com.qartvelo.sdk

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

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
}
