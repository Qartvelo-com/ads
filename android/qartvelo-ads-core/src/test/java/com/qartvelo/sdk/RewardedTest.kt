package com.qartvelo.sdk

import android.app.AlertDialog
import android.view.View
import com.qartvelo.sdk.internal.QartveloAdsActivity
import com.qartvelo.sdk.internal.TestHooks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.shadows.ShadowAlertDialog

class RewardedTest : SdkTest() {
    private val players = mutableListOf<FakePlayer>()

    @Before
    fun installFakePlayer() {
        TestHooks.videoPlayerFactory = { FakePlayer().also { players.add(it) } }
    }

    @Test
    fun ourAdsRewardIsDeliveredExactlyOnceAfterCompletion() {
        backend.adResponses.add(backend.fill("rewarded"))
        assertTrue(init())
        loadAndWait(AdFormat.REWARDED, "reward_coins")
        assertEquals(listOf("loaded:reward_coins:QARTVELO"), listener.events)
        assertTrue(QartveloAds.isRewardedReady("reward_coins"))

        val host = hostActivity().get()
        QartveloAds.showRewarded(host, "reward_coins", listener)
        val ad = launchedAdActivity(host)
        val player = players.single()
        assertEquals(1, player.prepareCount)
        assertTrue("playback starts on resume", player.playing)
        assertFalse(listener.has("shown"))

        player.firstFrame()
        awaitMain { listener.has("impression") }
        assertFalse("no reward before completion", listener.has("reward"))

        // Early close asks for confirmation; resuming keeps the reward possible.
        advance(QartveloAdsActivity.REWARDED_CLOSE_DELAY_MS + 50)
        val close = ad.get().window.decorView.findByDescription("Close ad")!!
        assertEquals(View.VISIBLE, close.visibility)
        close.performClick()
        val dialog = ShadowAlertDialog.getLatestAlertDialog() as AlertDialog
        assertTrue(dialog.isShowing)
        assertFalse(player.playing)
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        settle(50) // AlertDialog delivers button clicks through a Handler.
        assertTrue(player.playing)
        assertFalse(listener.has("dismissed"))

        player.complete()
        player.complete()
        ad.get().window.decorView.findByDescription("Close ad")!!.performClick()
        awaitMain { listener.has("dismissed") }
        awaitMain(message = "reward event") { backend.count("/api/v1/events/reward") == 1 }
        settle()

        assertEquals(1, listener.count("reward"))
        assertEquals(QartveloAdsReward(), listener.rewards.single())
        val reward = backend.bodies("/api/v1/events/reward").single()
        assertTrue(reward.getBoolean("completion"))
        assertEquals(1, backend.count("/api/v1/events/reward"))
        assertEquals(
            listOf("loaded:reward_coins:QARTVELO", "shown:reward_coins:QARTVELO", "impression:reward_coins:QARTVELO",
                "reward:reward_coins:QARTVELO", "dismissed:reward_coins:QARTVELO"),
            listener.events,
        )
        assertTrue(player.released)
        assertEquals(0, listener.offMainThread)
    }

    @Test
    fun skippingForfeitsTheReward() {
        backend.adResponses.add(backend.fill("rewarded"))
        assertTrue(init())
        loadAndWait(AdFormat.REWARDED, "reward_coins")
        val host = hostActivity().get()
        QartveloAds.showRewarded(host, "reward_coins", listener)
        val ad = launchedAdActivity(host)
        players.single().firstFrame()
        awaitMain { listener.has("impression") }
        advance(QartveloAdsActivity.REWARDED_CLOSE_DELAY_MS + 50)
        ad.get().window.decorView.findByDescription("Close ad")!!.performClick()
        (ShadowAlertDialog.getLatestAlertDialog() as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        awaitMain { listener.has("dismissed") }
        players.single().complete() // late callbacks after close are ignored
        awaitMain(message = "incomplete reward event") { backend.count("/api/v1/events/reward") == 1 }
        settle()
        assertFalse(listener.has("reward"))
        assertFalse(backend.bodies("/api/v1/events/reward").single().getBoolean("completion"))
    }

    @Test
    fun fallbackRewardIsDeliveredExactlyOnce() {
        // The fake network reports the reward three times, from a background thread.
        val adapter = FakeAdapter(rewardTimes = 3)
        assertTrue(init(adapter = adapter))
        loadAndWait(AdFormat.REWARDED, "reward_coins")
        assertEquals(listOf("fallbackStarted:reward_coins:no_fill", "loaded:reward_coins:ADMOB"), listener.events)

        val host = hostActivity().get()
        QartveloAds.showRewarded(host, "reward_coins", listener)
        awaitMain { listener.has("dismissed") }
        settle()
        assertEquals(1, listener.count("reward:reward_coins:ADMOB"))
        assertEquals(1, global.count("reward"))
        assertEquals(QartveloAdsReward("coins", 5), listener.rewards.single())
        assertEquals("duplicate dismiss swallowed", 1, listener.count("dismissed"))
        assertEquals(0, listener.offMainThread + global.offMainThread)
    }

    @Test
    fun playbackErrorBeforeFirstFrameFallsBackToReadyAdMob() {
        val adapter = FakeAdapter()
        backend.adResponses.add(backend.fill("rewarded"))
        assertTrue(init(adapter = adapter))
        loadAndWait(AdFormat.REWARDED, "reward_coins")
        assertEquals(listOf("loaded:reward_coins:QARTVELO"), listener.events)
        awaitMain(message = "AdMob preload") { adapter.isRewardedReady("reward_coins") }

        val host = hostActivity().get()
        QartveloAds.showRewarded(host, "reward_coins", listener)
        val ad = launchedAdActivity(host)
        players.single().listener!!.onError("decoder failure")
        awaitMain { listener.has("dismissed") }
        assertTrue(ad.get().isFinishing)
        assertEquals("no QartveloAds impression for an unrendered creative", 0, backend.count("/api/v1/events/impression"))
        assertEquals(listOf("r:reward_coins"), adapter.shows)
        assertEquals(1, listener.count("reward:reward_coins:ADMOB"))
    }
}
