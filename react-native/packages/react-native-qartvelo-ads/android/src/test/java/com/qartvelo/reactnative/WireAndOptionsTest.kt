package com.qartvelo.reactnative

import com.qartvelo.sdk.AdFormat
import com.qartvelo.sdk.AdSource
import com.qartvelo.sdk.DEFAULT_BASE_URL
import com.qartvelo.sdk.QartveloAdsAdInfo
import com.qartvelo.sdk.QartveloAdsError
import com.qartvelo.sdk.QartveloAdsErrorCode
import com.qartvelo.sdk.QartveloAdsLogLevel
import com.qartvelo.sdk.QartveloAdsOptions
import com.qartvelo.sdk.QartveloAdsPrivacy
import com.qartvelo.sdk.QartveloAdsReward
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class WireAndOptionsTest {
    private val events = mutableListOf<Map<String, Any?>>()
    private val forwarder = AdEventForwarder(PlacementFormats::get) { events += it }

    @After
    fun tearDown() {
        PlacementFormats.clear()
    }

    @Test
    fun forwardsEveryCallbackAsOneEventWithTheContractPayload() {
        val admob = QartveloAdsAdInfo("reward_coins", AdFormat.REWARDED, AdSource.ADMOB)

        forwarder.onFallbackStarted("reward_coins", AdFormat.REWARDED, "timeout")
        forwarder.onLoaded(admob)
        forwarder.onShown(admob)
        forwarder.onImpression(admob)
        forwarder.onClicked(admob)
        forwarder.onReward(admob, QartveloAdsReward("coins", 10))
        forwarder.onDismissed(admob)
        forwarder.onNoAdAvailable("reward_coins", AdFormat.REWARDED)

        assertEquals(
            listOf("fallbackStarted", "loaded", "shown", "impression", "clicked", "rewarded", "dismissed", "noAdAvailable"),
            events.map { it["type"] },
        )
        assertEquals(
            mapOf("type" to "fallbackStarted", "placementId" to "reward_coins", "format" to "rewarded", "reason" to "timeout"),
            events[0],
        )
        assertEquals(
            mapOf(
                "type" to "rewarded",
                "placementId" to "reward_coins",
                "format" to "rewarded",
                "source" to "admob",
                "reward" to mapOf("type" to "coins", "amount" to 10.0),
            ),
            events[5],
        )
    }

    @Test
    fun loadFailuresGetTheFormatLastUsedForThePlacement() {
        PlacementFormats.record("game_end", AdFormat.INTERSTITIAL)

        forwarder.onLoadFailed("game_end", QartveloAdsError(QartveloAdsErrorCode.TIMEOUT, "slow"))
        forwarder.onLoadFailed("never_loaded", QartveloAdsError(QartveloAdsErrorCode.NOT_INITIALIZED, "init first"))

        assertEquals(
            mapOf(
                "type" to "loadFailed",
                "placementId" to "game_end",
                "format" to "interstitial",
                "error" to mapOf("code" to "timeout", "message" to "slow"),
            ),
            events[0],
        )
        assertEquals(null, events[1]["format"])
        assertEquals(mapOf("code" to "not_initialized", "message" to "init first"), events[1]["error"])
    }

    @Test
    fun bannerEventsAreFlattened() {
        val flat = Wire.flatten(
            mapOf(
                "type" to "loadFailed",
                "placementId" to "home_banner",
                "format" to "banner",
                "error" to mapOf("code" to "no_fill", "message" to "none"),
            ),
        )
        assertEquals(
            mapOf(
                "type" to "loadFailed",
                "placementId" to "home_banner",
                "format" to "banner",
                "errorCode" to "no_fill",
                "errorMessage" to "none",
            ),
            flat,
        )
    }

    @Test
    fun parsesInitOptionsAndKeepsSdkDefaultsForMissingKeys() {
        val minimal = Options.parseInit(mapOf("appKey" to " app_demo_rn_example_0001 "))
        assertEquals("app_demo_rn_example_0001", minimal.appKey)
        assertEquals(QartveloAdsOptions(), minimal.options)

        val full = Options.parseInit(
            mapOf(
                "appKey" to "app_x",
                "requestTimeoutMs" to 1200.0,
                "testMode" to true,
                "testForceNoFill" to true,
                "admobFallback" to false,
                "logLevel" to "debug",
                "baseUrl" to "http://10.0.2.2:8000/",
                "admobAdUnits" to mapOf("game_end" to "ca-app-pub-3940256099942544/1033173712", "bad" to ""),
            ),
        )
        assertEquals(
            QartveloAdsOptions(
                admobFallback = false,
                requestTimeoutMs = 1200,
                testMode = true,
                testForceNoFill = true,
                logLevel = QartveloAdsLogLevel.DEBUG,
                baseUrl = "http://10.0.2.2:8000/",
                admobAdUnits = mapOf("game_end" to "ca-app-pub-3940256099942544/1033173712"),
            ),
            full.options,
        )
        assertEquals(DEFAULT_BASE_URL, minimal.options.baseUrl)
    }

    @Test
    fun rejectsInvalidInitOptions() {
        assertThrows(IllegalArgumentException::class.java) { Options.parseInit(mapOf("appKey" to " ")) }
        assertThrows(IllegalArgumentException::class.java) { Options.parseInit(mapOf("appKey" to "a", "testMode" to "yes")) }
        assertThrows(IllegalArgumentException::class.java) { Options.parseInit(mapOf("appKey" to "a", "requestTimeoutMs" to -1.0)) }
        assertThrows(IllegalArgumentException::class.java) { Options.parseInit(mapOf("appKey" to "a", "baseUrl" to "ftp://x")) }
        assertThrows(IllegalArgumentException::class.java) { Options.parseLogLevel("verbose") }
    }

    @Test
    fun privacyKeepsUnknownSignalsNull() {
        assertEquals(QartveloAdsPrivacy(consentGiven = false), Options.parsePrivacy(mapOf("consentGiven" to false)))
        assertEquals(QartveloAdsPrivacy(), Options.parsePrivacy(emptyMap()))
    }
}
