package com.tikview.app

import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import org.json.JSONObject

/** Full-screen viewer for videos, recordings and lives (FLV, HLS, MP4, TS). */
@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {
    private var player: ExoPlayer? = null
    private var retries = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val url = intent.getStringExtra("url") ?: return finish()
        val live = intent.getBooleanExtra("live", false)
        val title = intent.getStringExtra("title") ?: ""

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val view = PlayerView(this).apply {
            setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
            keepScreenOn = true
        }
        val label = TextView(this).apply {
            text = if (live) "● LIVE  $title" else title
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setShadowLayer(6f, 0f, 0f, Color.BLACK)
            maxLines = 2
            setPadding(48, 96, 48, 24)
        }
        root.addView(view, FrameLayout.LayoutParams(-1, -1))
        root.addView(label, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        setContentView(root)
        view.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v ->
            label.visibility = v
        })

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, root).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        // Headers TikTok expects (User-Agent, Referer, …)
        val headers = mutableMapOf<String, String>()
        intent.getStringExtra("headers")?.let { h ->
            val o = JSONObject(h)
            o.keys().forEach { k -> headers[k] = o.getString(k) }
        }
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
        headers.remove("User-Agent")?.let { http.setUserAgent(it) }
        http.setDefaultRequestProperties(headers)

        val p = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http)))
            .build()
        player = p
        view.player = p

        val item = MediaItem.Builder().setUri(url).apply {
            if (url.contains(".m3u8")) setMimeType(MimeTypes.APPLICATION_M3U8)
        }.build()
        p.setMediaItem(item)
        p.repeatMode = if (live) Player.REPEAT_MODE_OFF else Player.REPEAT_MODE_ONE
        p.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                if (live && retries < 3) {  // brief network hiccups on lives
                    retries++
                    p.seekToDefaultPosition()
                    p.prepare()
                    return
                }
                Toast.makeText(
                    this@PlayerActivity,
                    if (live) "The live stopped or can't be played." else "Can't play this video.",
                    Toast.LENGTH_LONG
                ).show()
                finish()
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) retries = 0
                if (live && state == Player.STATE_ENDED) {
                    Toast.makeText(this@PlayerActivity, "The live has ended.", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        })
        p.prepare()
        p.playWhenReady = true
        view.visibility = View.VISIBLE
    }

    override fun onStop() {
        super.onStop()
        player?.pause()
    }

    override fun onStart() {
        super.onStart()
        player?.play()
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }
}
