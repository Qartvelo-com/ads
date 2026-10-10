package com.qartvelo.sdk.internal

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.MutableContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.qartvelo.sdk.R
import java.io.File
import java.lang.ref.WeakReference
import kotlin.math.ceil

/**
 * Full-screen renderer for QartveloAds interstitial and rewarded creatives. All durable state lives in
 * [ShowSession]; this activity only draws it, so re-creation is safe at any point.
 */
internal class QartveloAdsActivity : Activity() {
    private var session: ShowSession? = null
    private var closeButton: View? = null
    private var countdown: TextView? = null
    private var imageView: ImageView? = null
    private var videoContainer: AspectFitLayout? = null
    private var html5Container: FrameLayout? = null
    private var confirmDialog: AlertDialog? = null
    private var backCallback: Any? = null
    private var resumedState = false
    private val ui = Handler(Looper.getMainLooper())
    private val showCloseTask = Runnable { setCloseVisible() }
    private val tick = object : Runnable {
        override fun run() {
            updateCountdown()
            ui.postDelayed(this, TICK_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val s = intent?.getStringExtra(EXTRA_SESSION)?.let(ShowRegistry::get)
        if (s == null || s.closed) {
            // Process was recreated or the show already ended: nothing to render, emit nothing.
            finish()
            return
        }
        session = s
        try {
            enterImmersive()
            setContentView(buildLayout(s))
            registerBack()
            when (s.ad.creativeType) {
                CreativeType.IMAGE -> loadImage(s)
                CreativeType.VIDEO -> attachVideo(s)
                CreativeType.HTML5 -> attachHtml5(s)
            }
            if (s.rendered) scheduleClose(s)
            if (s.confirmVisible) showConfirm(s)
        } catch (t: Throwable) {
            OurLog.e("QartveloAds ad activity failed to start", t)
            failRender(s)
        }
    }

    override fun onResume() {
        super.onResume()
        resumedState = true
        val s = session ?: return
        if (s.ad.creativeType == CreativeType.VIDEO) {
            if (!s.videoCompleted && !s.confirmVisible && !s.playbackFailed) guard("play") { s.player?.play() }
            ui.removeCallbacks(tick)
            ui.post(tick)
        }
        guard("html5 resume") { s.html5?.resume() }
    }

    override fun onPause() {
        resumedState = false
        ui.removeCallbacks(tick)
        guard("pause") { session?.player?.pause() }
        guard("html5 pause") { session?.html5?.pause() }
        super.onPause()
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        unregisterBack()
        confirmDialog?.let { dialog ->
            // Keep session.confirmVisible so a re-created activity shows the dialog again.
            dialog.setOnCancelListener(null)
            guard("dialog dismiss") { dialog.dismiss() }
        }
        confirmDialog = null
        session?.let { s ->
            s.player?.let { p ->
                p.listener = null
                guard("detach") { p.detach() }
            }
            if (!isFinishing) {
                // Re-creation: keep the web view (and its state) for the next activity, without this one.
                s.html5?.view?.let { (it.parent as? ViewGroup)?.removeView(it) }
                s.html5Context?.baseContext = applicationContext
            }
            if (isFinishing) s.close()
        }
        super.onDestroy()
    }

    @Deprecated("Pre-API-33 back handling; API 33+ uses OnBackInvokedCallback as well.")
    override fun onBackPressed() {
        onCloseRequested()
    }

    // ---- layout ---------------------------------------------------------------------------------

    private fun buildLayout(s: ShowSession): View {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        when (s.ad.creativeType) {
            CreativeType.IMAGE -> {
                val image = ImageView(this).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    contentDescription = getString(R.string.qartvelo_ad_label)
                    if (s.ad.clickUrl != null) setOnClickListener { session?.onClick(this@QartveloAdsActivity) }
                }
                imageView = image
                root.addView(image, FrameLayout.LayoutParams(match, match))
            }
            CreativeType.VIDEO -> {
                val container = AspectFitLayout(this)
                videoContainer = container
                root.addView(container, FrameLayout.LayoutParams(match, match, Gravity.CENTER))
            }
            CreativeType.HTML5 -> {
                // The ad lays itself out for the screen; taps reach it through the web view.
                val container = FrameLayout(this)
                html5Container = container
                root.addView(container, FrameLayout.LayoutParams(match, match))
            }
        }

        val overlay = FrameLayout(this)
        root.addView(overlay, FrameLayout.LayoutParams(match, match))
        ViewCompat.setOnApplyWindowInsetsListener(overlay) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        overlay.addView(
            pill(getString(if (s.ad.test) R.string.qartvelo_test_ad_label else R.string.qartvelo_ad_label), 12f).apply {
                // Opens the Qartvelo Ads website (not the advertiser); not counted as a click.
                contentDescription = getString(R.string.qartvelo_about_ads)
                setOnClickListener { AboutLink.open(this@QartveloAdsActivity) }
            },
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START)
                .apply { setMargins(dp(12), dp(16), 0, 0) },
        )

        val close = ImageButton(this).apply {
            setImageResource(R.drawable.qartvelo_ic_close)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x99000000.toInt())
            }
            contentDescription = getString(R.string.qartvelo_close)
            visibility = View.GONE
            setOnClickListener { onCloseRequested() }
        }
        closeButton = close
        overlay.addView(
            close,
            FrameLayout.LayoutParams(dp(40), dp(40), Gravity.TOP or Gravity.END).apply { setMargins(0, dp(12), dp(12), 0) },
        )

        if (s.ad.creativeType == CreativeType.VIDEO) {
            val counter = pill("", 13f).apply { visibility = View.GONE }
            countdown = counter
            overlay.addView(
                counter,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END)
                    .apply { setMargins(0, dp(20), dp(64), 0) },
            )
            if (s.ad.clickUrl != null) {
                val cta = pill(getString(R.string.qartvelo_learn_more), 15f).apply {
                    setPadding(dp(16), dp(10), dp(16), dp(10))
                    setOnClickListener { session?.onClick(this@QartveloAdsActivity) }
                }
                overlay.addView(
                    cta,
                    FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END)
                        .apply { setMargins(0, 0, dp(16), dp(24)) },
                )
            }
        }
        if (s.videoCompleted || s.playbackFailed) close.visibility = View.VISIBLE
        return root
    }

    private fun pill(text: String, sizeSp: Float) = TextView(this).apply {
        this.text = text
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setPadding(dp(8), dp(3), dp(8), dp(3))
        background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(0x99000000.toInt())
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun enterImmersive() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    // ---- rendering ------------------------------------------------------------------------------

    private fun loadImage(s: ShowSession) {
        val file = s.ad.file
        if (file == null) {
            failRender(s)
            return
        }
        val metrics = resources.displayMetrics
        val maxW = metrics.widthPixels.coerceAtLeast(1)
        val maxH = metrics.heightPixels.coerceAtLeast(1)
        s.runInBackground {
            val bitmap = decodeSampled(file, maxW, maxH)
            runOnUiThread {
                if (isDestroyed || session !== s || s.closed) return@runOnUiThread
                if (bitmap == null) {
                    failRender(s)
                } else {
                    imageView?.setImageBitmap(bitmap)
                    s.onRendered()
                    scheduleClose(s)
                }
            }
        }
    }

    private fun attachVideo(s: ShowSession) {
        val container = videoContainer ?: return failRender(s)
        val player = s.player ?: AdVideoPlayer.create(this).also { created ->
            s.player = created
            created.prepare(s.ad.file ?: throw IllegalStateException("creative file missing"))
        }
        player.listener = object : AdVideoPlayer.Listener {
            override fun onFirstFrame() {
                if (session !== s) return
                s.onRendered()
                scheduleClose(s)
            }

            override fun onCompleted() {
                if (session !== s) return
                s.onVideoCompleted()
                setCloseVisible()
                updateCountdown()
            }

            override fun onError(message: String) {
                if (session !== s) return
                if (!s.rendered) {
                    failRender(s)
                } else {
                    s.onPlaybackFailed()
                    setCloseVisible()
                }
            }
        }
        player.attach(container)
    }

    /**
     * Shows the HTML5 creative: the impression counts when the runtime reports ready (the view has
     * its size), a creative that is not ready in time is a show failure. The surface outlives this
     * activity, so callbacks act on whichever activity draws the show then.
     */
    private fun attachHtml5(s: ShowSession) {
        val container = html5Container ?: return failRender(s)
        s.activity = WeakReference(this)
        val existing = s.html5
        if (existing != null) {
            s.html5Context?.baseContext = this
            (existing.view.parent as? ViewGroup)?.removeView(existing.view)
            container.addView(existing.view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            return
        }
        val wrapper = MutableContextWrapper(this)
        val surface = Html5AdView.create(wrapper, s.ad)
        s.html5Context = wrapper
        s.html5 = surface
        container.addView(surface.view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        fun current(): QartveloAdsActivity? = (s.activity?.get() as? QartveloAdsActivity)?.takeIf { !it.isDestroyed && it.session === s }
        surface.load(
            onReady = {
                if (!s.closed) {
                    s.onRendered()
                    current()?.scheduleClose(s)
                }
            },
            onFailed = { if (!s.rendered && !s.closed) current()?.failRender(s) ?: s.onRenderFailed() },
            onClick = { current()?.let { s.onClick(it) } },
        )
    }

    private fun scheduleClose(s: ShowSession) {
        ui.removeCallbacks(showCloseTask)
        if (s.videoCompleted || s.playbackFailed) {
            setCloseVisible()
            return
        }
        val delay = closeDelayMs(s) - (Clock.elapsed() - s.renderedAtElapsed)
        if (delay <= 0) setCloseVisible() else ui.postDelayed(showCloseTask, delay)
    }

    private fun closeDelayMs(s: ShowSession): Long = when {
        s.isRewarded -> REWARDED_CLOSE_DELAY_MS
        s.ad.creativeType == CreativeType.VIDEO -> VIDEO_CLOSE_DELAY_MS
        else -> IMAGE_CLOSE_DELAY_MS
    }

    private fun setCloseVisible() {
        closeButton?.visibility = View.VISIBLE
    }

    private fun updateCountdown() {
        val s = session ?: return
        val view = countdown ?: return
        val player = s.player
        val duration = player?.durationMs ?: 0L
        if (s.videoCompleted) {
            if (s.isRewarded) {
                view.text = getString(R.string.qartvelo_reward_earned)
                view.visibility = View.VISIBLE
            } else {
                view.visibility = View.GONE
            }
            return
        }
        if (player == null || duration <= 0 || s.playbackFailed) {
            view.visibility = View.GONE
            return
        }
        val seconds = ceil((duration - player.positionMs).coerceAtLeast(0) / 1000.0).toInt()
        view.text = if (s.isRewarded) getString(R.string.qartvelo_reward_in, seconds) else getString(R.string.qartvelo_seconds_left, seconds)
        view.visibility = View.VISIBLE
    }

    // ---- closing --------------------------------------------------------------------------------

    private fun onCloseRequested() {
        val s = session ?: return finish()
        if (closeButton?.visibility != View.VISIBLE) return // Not closable yet.
        if (s.isRewarded && !s.videoCompleted && !s.playbackFailed) showConfirm(s) else closeAd(s)
    }

    private fun showConfirm(s: ShowSession) {
        if (confirmDialog?.isShowing == true) return
        s.confirmVisible = true
        guard("pause") { s.player?.pause() }
        val resume = {
            s.confirmVisible = false
            confirmDialog = null
            if (resumedState && !s.closed) guard("play") { s.player?.play() }
        }
        confirmDialog = AlertDialog.Builder(this)
            .setTitle(R.string.qartvelo_skip_title)
            .setMessage(R.string.qartvelo_skip_message)
            .setPositiveButton(R.string.qartvelo_skip_confirm) { _, _ ->
                s.confirmVisible = false
                confirmDialog = null
                closeAd(s)
            }
            .setNegativeButton(R.string.qartvelo_skip_resume) { _, _ -> resume() }
            .setOnCancelListener { resume() }
            .show()
    }

    private fun closeAd(s: ShowSession) {
        s.close()
        finish()
    }

    private fun failRender(s: ShowSession) {
        s.onRenderFailed()
        finish()
    }

    private fun registerBack() {
        if (Build.VERSION.SDK_INT >= 33) {
            val callback = OnBackInvokedCallback { onCloseRequested() }
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback)
            backCallback = callback
        }
    }

    private fun unregisterBack() {
        if (Build.VERSION.SDK_INT >= 33) {
            (backCallback as? OnBackInvokedCallback)?.let {
                guard("unregister back") { onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it) }
            }
        }
        backCallback = null
    }

    companion object {
        private const val EXTRA_SESSION = "com.qartvelo.sdk.SESSION_ID"
        const val IMAGE_CLOSE_DELAY_MS = 2_000L
        const val VIDEO_CLOSE_DELAY_MS = 5_000L
        const val REWARDED_CLOSE_DELAY_MS = 2_000L
        private const val TICK_MS = 250L

        fun intent(context: Context, sessionId: String): Intent =
            Intent(context, QartveloAdsActivity::class.java).putExtra(EXTRA_SESSION, sessionId)

        /** Decodes [file] downsampled to roughly the screen size. Returns null if undecodable. */
        fun decodeSampled(file: File, maxW: Int, maxH: Int): Bitmap? = try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                null
            } else {
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= maxW && bounds.outHeight / (sample * 2) >= maxH) sample *= 2
                BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
            }
        } catch (t: Throwable) {
            OurLog.e("Creative decode failed", t)
            null
        }
    }
}
