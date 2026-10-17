package com.drchzy.offlinephoneagent

import android.os.Bundle
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import org.json.JSONObject

/**
 * 使用 Media3 原生播放器，按接口返回的线路 UA、headers 播放合法可访问的视频。
 * Android 设备负责安装与播放；本项目不实现绕过授权或付费限制。
 */
class MoviePlayerActivity : ComponentActivity() {
    companion object {
        const val EXTRA_URL = "movie_play_url"
        const val EXTRA_TITLE = "movie_play_title"
        const val EXTRA_HEADERS = "movie_play_headers"
        const val EXTRA_AGENT = "movie_play_agent"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val agent = intent.getStringExtra(EXTRA_AGENT).orEmpty()
        val values = JSONObject(intent.getStringExtra(EXTRA_HEADERS).orEmpty().ifBlank { "{}" })
        val headers = linkedMapOf<String, String>()
        for (key in values.keys()) headers[key] = values.optString(key)

        if (!url.startsWith("https://") && !url.startsWith("http://")) {
            finish()
            return
        }

        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(false)
            .setDefaultRequestProperties(headers)
        if (agent.isNotBlank()) http.setUserAgent(agent)

        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http)))
            .build()
        player.setMediaItem(MediaItem.fromUri(url))
        player.prepare()
        player.playWhenReady = true

        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Color(0xFFD9CBAF),
                surface = Color(0xFF171819),
                background = Color(0xFF101112)
            )) {
                Scaffold(
                    containerColor = Color(0xFF101112),
                    topBar = {
                        Row(Modifier.fillMaxWidth().height(66.dp),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            IconButton(onClick = { finish() }) {
                                Icon(Icons.Outlined.ArrowBack, "返回")
                            }
                            Text(title, maxLines = 1, color = Color.White)
                        }
                    }
                ) { padding ->
                    Column(Modifier.fillMaxSize().padding(padding)) {
                        AndroidView(
                            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                            factory = { ctx ->
                                PlayerView(ctx).apply {
                                    layoutParams = FrameLayout.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.MATCH_PARENT
                                    )
                                    useController = true
                                    this.player = player
                                }
                            }
                        )
                        Spacer(Modifier.height(18.dp))
                        Text("MEDIA PLAYER", Modifier.padding(horizontal = 24.dp), color = Color.Gray)
                        Text(title, Modifier.padding(24.dp), color = Color.White)
                    }
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
    }
}
