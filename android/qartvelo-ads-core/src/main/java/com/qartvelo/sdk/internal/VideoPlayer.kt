package com.qartvelo.sdk.internal

import android.content.Context
import android.net.Uri
import android.view.SurfaceView
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import java.io.File

/**
 * Minimal video surface used by [QartveloAdsActivity]. The player lives in [ShowSession] (application
 * context) and is re-attached to each new activity, so rotation never restarts playback.
 */
internal interface AdVideoPlayer {
    var listener: Listener?
    val positionMs: Long
    val durationMs: Long

    fun prepare(file: File)
    fun attach(container: AspectFitLayout)
    fun detach()
    fun play()
    fun pause()
    fun release()

    interface Listener {
        fun onFirstFrame()
        fun onCompleted()
        fun onError(message: String)
    }

    companion object {
        fun create(context: Context): AdVideoPlayer =
            TestHooks.videoPlayerFactory?.invoke(context.applicationContext) ?: ExoAdVideoPlayer(context.applicationContext)
    }
}

internal class ExoAdVideoPlayer(context: Context) : AdVideoPlayer {
    private val player: ExoPlayer = ExoPlayer.Builder(context).build().apply {
        setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
            /* handleAudioFocus = */ true,
        )
        repeatMode = Player.REPEAT_MODE_OFF
    }
    private var surface: SurfaceView? = null
    private var container: AspectFitLayout? = null
    override var listener: AdVideoPlayer.Listener? = null

    private val events = object : Player.Listener {
        override fun onRenderedFirstFrame() {
            listener?.onFirstFrame()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) listener?.onCompleted()
        }

        override fun onPlayerError(error: PlaybackException) {
            OurLog.e("Video playback failed: ${error.errorCodeName}")
            listener?.onError(error.errorCodeName)
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            applyAspect(videoSize)
        }
    }

    override val positionMs: Long get() = player.currentPosition
    override val durationMs: Long get() = player.duration.takeIf { it != C.TIME_UNSET } ?: 0L

    override fun prepare(file: File) {
        player.addListener(events)
        player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
        player.prepare()
    }

    override fun attach(container: AspectFitLayout) {
        detach()
        val view = SurfaceView(container.context)
        container.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        player.setVideoSurfaceView(view)
        surface = view
        this.container = container
        applyAspect(player.videoSize)
    }

    override fun detach() {
        surface?.let { view ->
            player.clearVideoSurfaceView(view)
            (view.parent as? ViewGroup)?.removeView(view)
        }
        surface = null
        container = null
    }

    override fun play() {
        player.play()
    }

    override fun pause() {
        player.pause()
    }

    override fun release() {
        detach()
        listener = null
        player.removeListener(events)
        player.release()
    }

    private fun applyAspect(size: VideoSize) {
        if (size.width > 0 && size.height > 0) {
            container?.setAspectRatio(size.width * size.pixelWidthHeightRatio / size.height)
        }
    }
}

/** Centers its children at a fixed aspect ratio inside the available space (letterboxing). */
internal class AspectFitLayout(context: Context) : FrameLayout(context) {
    private var ratio = 0f

    fun setAspectRatio(value: Float) {
        if (value > 0f && value != ratio) {
            ratio = value
            requestLayout()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        if (ratio <= 0f || measuredWidth == 0 || measuredHeight == 0) return
        var width = measuredWidth
        var height = measuredHeight
        if (width.toFloat() / height > ratio) width = (height * ratio).toInt() else height = (width / ratio).toInt()
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY),
        )
    }
}
