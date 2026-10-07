package com.qartvelo.sdk

import android.app.AlertDialog
import android.view.View
import com.qartvelo.sdk.internal.QartveloAdsActivity
import com.qartvelo.sdk.internal.TestHooks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.shadows.ShadowAlertDialog

/** Activity re-creation (rotation without configChanges, "don't keep activities") must not replay. */
class ConfigurationChangeTest : SdkTest() {

    @Test
    fun imageInterstitialSurvivesRecreationWithoutDoubleEvents() {
        backend.adResponses.add(backend.fill("interstitial"))
        assertTrue(init())
        loadAndWait(AdFormat.INTERSTITIAL, "game_end")
        val host = hostActivity().get()
        QartveloAds.showInterstitial(host, "game_end", listener)
        val ad = launchedAdActivity(host)
        awaitMain { listener.has("impression") }

        ad.recreate()
        settle()
        ad.recreate()
        settle()

        assertEquals(1, listener.count("shown"))
        assertEquals(1, listener.count("impression"))
        assertEquals(1, backend.count("/api/v1/events/impression"))
        assertFalse(listener.has("dismissed"))

        advance(QartveloAdsActivity.IMAGE_CLOSE_DELAY_MS + 50)
        val close = ad.get().window.decorView.findByDescription("Close ad")!!
        assertEquals(View.VISIBLE, close.visibility)
        close.performClick()
        awaitMain { listener.has("dismissed") }
        settle()
        assertEquals(1, listener.count("dismissed"))
    }

    @Test
    fun rewardedVideoKeepsPlaybackStateAcrossRecreation() {
        val players = mutableListOf<FakePlayer>()
        TestHooks.videoPlayerFactory = { FakePlayer().also { players.add(it) } }
        backend.adResponses.add(backend.fill("rewarded"))
        assertTrue(init())
        loadAndWait(AdFormat.REWARDED, "reward_coins")
        val host = hostActivity().get()
        QartveloAds.showRewarded(host, "reward_coins", listener)
        val ad = launchedAdActivity(host)
        val player = players.single()
        player.firstFrame()
        player.positionMs = 7_000
        awaitMain { listener.has("impression") }

        ad.recreate()
        // A re-attached surface renders its first frame again; it must not count twice.
        player.firstFrame()
        settle()

        assertEquals("the session keeps one player", 1, players.size)
        assertEquals("never re-prepared, so playback is not replayed", 1, player.prepareCount)
        assertEquals(2, player.attachCount)
        assertEquals(7_000, player.positionMs)
        assertTrue(player.playing)
        assertEquals(1, listener.count("shown"))
        assertEquals(1, listener.count("impression"))

        // The skip confirmation survives re-creation too.
        advance(QartveloAdsActivity.REWARDED_CLOSE_DELAY_MS + 50)
        ad.get().window.decorView.findByDescription("Close ad")!!.performClick()
        ad.recreate()
        val dialog = ShadowAlertDialog.getLatestAlertDialog() as AlertDialog
        assertTrue("dialog re-shown after re-creation", dialog.isShowing)
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        settle(50) // AlertDialog delivers button clicks through a Handler.

        player.complete()
        ad.recreate()
        player.complete()
        settle()
        val close = ad.get().window.decorView.findByDescription("Close ad")!!
        assertEquals("closable after completion", View.VISIBLE, close.visibility)
        close.performClick()
        awaitMain { listener.has("dismissed") }
        awaitMain { backend.count("/api/v1/events/reward") == 1 }
        settle()
        assertEquals(1, listener.count("reward"))
        assertEquals(1, listener.count("dismissed"))
        assertEquals(1, backend.count("/api/v1/events/reward"))
        assertEquals(1, backend.count("/api/v1/events/impression"))
    }

    @Test
    fun recreatedActivityAfterShowEndedFinishesSilently() {
        backend.adResponses.add(backend.fill("interstitial"))
        assertTrue(init())
        loadAndWait(AdFormat.INTERSTITIAL, "game_end")
        val host = hostActivity().get()
        QartveloAds.showInterstitial(host, "game_end", listener)
        val ad = launchedAdActivity(host)
        awaitMain { listener.has("impression") }
        val intent = ad.get().intent
        advance(QartveloAdsActivity.IMAGE_CLOSE_DELAY_MS + 50)
        ad.get().window.decorView.findByDescription("Close ad")!!.performClick()
        awaitMain { listener.has("dismissed") }

        // e.g. the system restores the task later: the stale session id renders nothing.
        val stale = org.robolectric.Robolectric.buildActivity(QartveloAdsActivity::class.java, intent).setup()
        assertTrue(stale.get().isFinishing)
        settle()
        assertEquals(1, listener.count("shown"))
        assertEquals(1, listener.count("dismissed"))
    }
}
