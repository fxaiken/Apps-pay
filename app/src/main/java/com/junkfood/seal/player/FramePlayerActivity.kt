package com.junkfood.seal.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.io.File

/**
 * FRAME SAVE: plays a saved video inside the app, full screen, and picks up
 * where it was left the last time.
 */
class FramePlayerActivity : ComponentActivity() {
    private var player: ExoPlayer? = null
    private lateinit var key: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra(EXTRA_PATH)
        if (path == null) {
            finish()
            return
        }
        key = path
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).run {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        val uri = if (path.startsWith("content://")) Uri.parse(path) else Uri.fromFile(File(path))
        val exo = ExoPlayer.Builder(this).build().also { player = it }
        val view = PlayerView(this).apply {
            this.player = exo
            setBackgroundColor(android.graphics.Color.BLACK)
            setShowNextButton(false)
            setShowPreviousButton(false)
            controllerShowTimeoutMs = 2500
        }
        setContentView(view)

        exo.setMediaItem(MediaItem.fromUri(uri))
        val resume = positions().getLong(key, 0L)
        if (resume > 0) exo.seekTo(resume)
        exo.prepare()
        exo.playWhenReady = true
    }

    override fun onPause() {
        super.onPause()
        player?.let { exo ->
            val at = exo.currentPosition
            val done = exo.duration > 0 && at > exo.duration - 5_000
            positions().edit().putLong(key, if (done) 0L else at).apply()
            exo.pause()
        }
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }

    private fun positions() = getSharedPreferences("frame_player_positions", Context.MODE_PRIVATE)

    companion object {
        private const val EXTRA_PATH = "path"

        fun intent(context: Context, path: String) =
            Intent(context, FramePlayerActivity::class.java)
                .putExtra(EXTRA_PATH, path)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        private val playable = Regex("""\.(mp4|mkv|webm|mov|m4v|3gp|avi|flv|ts|mp3|m4a|aac|opus|ogg|wav|flac)$""", RegexOption.IGNORE_CASE)

        fun canPlay(path: String) = playable.containsMatchIn(path) && File(path).exists()
    }
}
