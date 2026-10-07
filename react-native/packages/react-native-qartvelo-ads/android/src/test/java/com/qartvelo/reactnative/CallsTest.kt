package com.qartvelo.reactnative

import com.qartvelo.sdk.AdFormat
import com.qartvelo.sdk.AdSource
import com.qartvelo.sdk.QartveloAdsAdInfo
import com.qartvelo.sdk.QartveloAdsError
import com.qartvelo.sdk.QartveloAdsErrorCode
import com.qartvelo.sdk.QartveloAdsReward
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallsTest {
    private class Recorder : Completion {
        val resolved = mutableListOf<Any?>()
        val rejected = mutableListOf<Pair<String, String>>()
        val settlements: Int get() = resolved.size + rejected.size

        override fun resolve(value: Any?) {
            resolved += value
        }

        override fun reject(code: String, message: String) {
            rejected += code to message
        }
    }

    private val interstitial = QartveloAdsAdInfo("game_end", AdFormat.INTERSTITIAL, AdSource.QARTVELO, "cmp_12", "cr_34")
    private val rewardedAdMob = QartveloAdsAdInfo("reward_coins", AdFormat.REWARDED, AdSource.ADMOB)

    @Test
    fun loadResolvesOnceWithTheAdInfo() {
        val recorder = Recorder()
        val pending = PendingCalls()
        val call = LoadCall(OneShot(recorder, pending))
        assertEquals(1, pending.size)

        call.onLoaded(interstitial)
        call.onLoaded(interstitial)
        call.onLoadFailed("game_end", QartveloAdsError(QartveloAdsErrorCode.NO_FILL, "late"))

        assertEquals(1, recorder.settlements)
        assertEquals(
            mapOf(
                "placementId" to "game_end",
                "format" to "interstitial",
                "source" to "qartvelo",
                "campaignId" to "cmp_12",
                "creativeId" to "cr_34",
            ),
            recorder.resolved.single(),
        )
        assertEquals("settled calls leave the registry", 0, pending.size)
    }

    @Test
    fun loadRejectsWithLowerCaseCode() {
        val recorder = Recorder()
        val call = LoadCall(OneShot(recorder))

        call.onFallbackStarted("game_end", AdFormat.INTERSTITIAL, "no_fill")
        call.onNoAdAvailable("game_end", AdFormat.INTERSTITIAL)
        call.onLoadFailed("game_end", QartveloAdsError(QartveloAdsErrorCode.NO_FILL, "No QartveloAds campaign available"))

        assertEquals(listOf("no_fill" to "No QartveloAds campaign available"), recorder.rejected)
        assertTrue(recorder.resolved.isEmpty())
    }

    @Test
    fun interstitialShowResolvesOnDismissWithTheShownSource() {
        val recorder = Recorder()
        val call = ShowCall(AdFormat.INTERSTITIAL, OneShot(recorder))

        call.onShown(interstitial)
        call.onImpression(interstitial)
        call.onClicked(interstitial)
        assertEquals("nothing settles before dismissal", 0, recorder.settlements)
        call.onDismissed(interstitial)

        assertEquals(mapOf("shown" to true, "rewarded" to false, "source" to "qartvelo"), recorder.resolved.single())
    }

    @Test
    fun rewardedShowReportsExactlyOneRewardFromTheFallback() {
        val recorder = Recorder()
        val call = ShowCall(AdFormat.REWARDED, OneShot(recorder))

        call.onShown(rewardedAdMob)
        call.onReward(rewardedAdMob, QartveloAdsReward("coins", 10))
        call.onReward(rewardedAdMob, QartveloAdsReward("coins", 99))
        call.onDismissed(rewardedAdMob)
        call.onDismissed(rewardedAdMob)
        call.onReward(rewardedAdMob, QartveloAdsReward("coins", 5))

        assertEquals(1, recorder.settlements)
        assertEquals(
            mapOf(
                "shown" to true,
                "rewarded" to true,
                "source" to "admob",
                "reward" to mapOf("type" to "coins", "amount" to 10.0),
            ),
            recorder.resolved.single(),
        )
    }

    @Test
    fun skippedRewardedResolvesWithoutReward() {
        val recorder = Recorder()
        val call = ShowCall(AdFormat.REWARDED, OneShot(recorder))

        call.onShown(rewardedAdMob)
        call.onDismissed(rewardedAdMob)

        assertEquals(mapOf("shown" to true, "rewarded" to false, "source" to "admob"), recorder.resolved.single())
    }

    @Test
    fun interstitialNeverReportsAReward() {
        val recorder = Recorder()
        val call = ShowCall(AdFormat.INTERSTITIAL, OneShot(recorder))

        call.onReward(interstitial, QartveloAdsReward())
        call.onDismissed(interstitial)

        assertEquals(false, (recorder.resolved.single() as Map<*, *>)["rewarded"])
    }

    @Test
    fun showWithoutAnAdResolvesNotShown() {
        val recorder = Recorder()
        ShowCall(AdFormat.REWARDED, OneShot(recorder)).onNoAdAvailable("reward_coins", AdFormat.REWARDED)

        assertEquals(mapOf("shown" to false, "rewarded" to false), recorder.resolved.single())
    }

    @Test
    fun showErrorsReject() {
        val recorder = Recorder()
        val call = ShowCall(AdFormat.INTERSTITIAL, OneShot(recorder))

        call.onLoadFailed("game_end", QartveloAdsError(QartveloAdsErrorCode.ALREADY_SHOWING, "Another full-screen ad is showing"))
        call.onDismissed(interstitial)

        assertEquals(listOf("already_showing" to "Another full-screen ad is showing"), recorder.rejected)
        assertTrue(recorder.resolved.isEmpty())
    }

    @Test
    fun cancelAllDropsPendingPromisesWithoutSettlingThem() {
        val recorder = Recorder()
        val pending = PendingCalls()
        val first = OneShot(recorder, pending)
        val call = ShowCall(AdFormat.REWARDED, OneShot(recorder, pending))
        assertEquals(2, pending.size)

        pending.cancelAll()
        call.onReward(rewardedAdMob, QartveloAdsReward())
        call.onDismissed(rewardedAdMob)

        assertTrue(first.isSettled)
        assertFalse(first.resolve("late"))
        assertEquals(0, recorder.settlements)
        assertEquals(0, pending.size)
    }
}
