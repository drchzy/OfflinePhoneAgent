package com.drchzy.offlinephoneagent

import android.content.pm.ActivityInfo
import android.app.PictureInPictureParams
import android.content.Context
import android.content.res.Configuration
import android.media.AudioManager
import android.net.TrafficStats
import android.os.Process
import android.util.Rational
import android.view.WindowManager
import android.os.Bundle
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.json.JSONObject
import java.util.concurrent.Executors

private val InkPlayer = Color(0xFF101112)
private val SoftPlayer = Color(0xFF1D1F20)
private val AccentPlayer = Color(0xFFD9CBAF)
private val HintPlayer = Color(0xFF999B9B)

// 同一进程内优先复用详情页已取得的全部剧集，避免打开播放器后重复请求。
// 进程重启时仍通过 movieId 重新加载，保持恢复能力。
internal object MoviePlaybackSession {
    @Volatile var detail: MovieDetail? = null
}

/** 精简播放器：全屏、完整线路和剧集切换、后台暂停与资源释放。 */
class MoviePlayerActivity : ComponentActivity() {
    companion object {
        const val EXTRA_URL = "movie_play_url"
        const val EXTRA_TITLE = "movie_play_title"
        const val EXTRA_HEADERS = "movie_play_headers"
        const val EXTRA_AGENT = "movie_play_agent"
        const val EXTRA_MOVIE_ID = "movie_id"
        const val EXTRA_EPISODE_ID = "movie_episode_id"
        const val EXTRA_LINE_CODE = "movie_line_code"
        const val EXTRA_EPISODE_NAME = "movie_episode_name"
    }

    private val worker = Executors.newSingleThreadExecutor()
    var playback by mutableStateOf<ExoPlayer?>(null)
        private set
    var movieDetail by mutableStateOf<MovieDetail?>(null)
        private set
    var loadingList by mutableStateOf(false)
        private set
    var changingEpisode by mutableStateOf(false)
        private set
    var playing by mutableStateOf(false)
        private set
    var fullscreen by mutableStateOf(false)
        private set
    var errorText by mutableStateOf("")
        private set
    var episodeId by mutableStateOf("")
        private set
    var episodeName by mutableStateOf("")
        private set
    var lineCode by mutableStateOf("")
        private set
    private var movieId = ""
    private var shouldResume = false
    private var backgrounded = false
    var speed by mutableFloatStateOf(1f)
        private set
    var pictureInPicture by mutableStateOf(false)
        private set
    var mediaUrl by mutableStateOf("")
        private set
    var brightnessLevel by mutableFloatStateOf(0.5f)
        private set
    var volumeLevel by mutableFloatStateOf(0.5f)
        private set

    fun setSpeed(value: Float) {
        speed = value
        playback?.playbackParameters = PlaybackParameters(value)
    }

    fun brightness(delta: Float) {
        val attrs = window.attributes
        val start = if (attrs.screenBrightness < 0f) brightnessLevel else attrs.screenBrightness
        brightnessLevel = (start + delta).coerceIn(0.02f, 1f)
        attrs.screenBrightness = brightnessLevel
        window.attributes = attrs
    }

    fun volume(delta: Float) {
        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val current = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val next = (current + delta * max).toInt().coerceIn(0, max)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, next, 0)
        volumeLevel = next.toFloat() / max
    }

    fun minimizeToPiP() {
        if (isInPictureInPictureMode) return
        val params = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(16, 9)).build()
        runCatching {
            enterPictureInPictureMode(params)
        }.onFailure {
            errorText = "画中画不可用：" + (it.message ?: "请检查系统悬浮窗口支持")
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean,
        newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        pictureInPicture = isInPictureInPictureMode
        if (isInPictureInPictureMode) {
            fullscreen = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(16, 17, 18)
        window.navigationBarColor = android.graphics.Color.rgb(16, 17, 18)
        movieId = intent.getStringExtra(EXTRA_MOVIE_ID).orEmpty()
        episodeId = intent.getStringExtra(EXTRA_EPISODE_ID).orEmpty()
        lineCode = intent.getStringExtra(EXTRA_LINE_CODE).orEmpty()
        episodeName = intent.getStringExtra(EXTRA_EPISODE_NAME).orEmpty()
        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        if (!url.startsWith("https://") && !url.startsWith("http://")) {
            finish()
            return
        }
        val raw = runCatching {
            JSONObject(intent.getStringExtra(EXTRA_HEADERS).orEmpty().ifBlank { "{}" })
        }.getOrDefault(JSONObject())
        val headers = linkedMapOf<String, String>()
        for (name in raw.keys()) headers[name] = raw.optString(name)
        playAddress(PlayAddress(url, intent.getStringExtra(EXTRA_AGENT).orEmpty(), headers))
        movieDetail = MoviePlaybackSession.detail?.takeIf { it.movie.id == movieId }
        if (movieDetail == null) loadEpisodeList()
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = AccentPlayer,
                    onPrimary = InkPlayer,
                    background = InkPlayer,
                    surface = SoftPlayer,
                    onSurface = Color.White
                )
            ) { MoviePlayerScreen(this) }
        }
    }

    /** 每条线路可能有独立的 UA/headers，因此切集时重建数据源。 */
    private fun playAddress(address: PlayAddress) {
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(address.headers)
        if (address.userAgent.isNotBlank()) http.setUserAgent(address.userAgent)
        val next = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http)))
            .build()
        next.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (playback === next) playing = isPlaying
            }
            override fun onPlayerError(error: PlaybackException) {
                if (playback === next) errorText = "播放失败：" + (error.message ?: "可尝试其他线路")
            }
        })
        mediaUrl = address.url
        val previous = playback
        playback = next
        playing = false
        previous?.release()
        errorText = ""
        next.setMediaItem(MediaItem.fromUri(address.url))
        next.prepare()
        next.playWhenReady = !backgrounded || isInPictureInPictureMode
        next.playbackParameters = PlaybackParameters(speed)
    }

    fun loadEpisodeList() {
        if (movieId.isBlank() || loadingList) return
        loadingList = true
        worker.execute {
            val result = runCatching { MovieApi(applicationContext).detail(movieId) }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                loadingList = false
                result.onSuccess { movieDetail = it }
                    .onFailure { errorText = "获取剧集失败：" + (it.message ?: "网络错误") }
            }
        }
    }

    fun choose(episode: MovieEpisode) {
        if (changingEpisode) return
        if (episode.id.isNotBlank() && episode.id == episodeId && episode.fromCode == lineCode) return
        changingEpisode = true
        errorText = ""
        worker.execute {
            val result = runCatching { MovieApi(applicationContext).playback(episode) }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                changingEpisode = false
                result.onSuccess {
                    episodeId = episode.id
                    episodeName = episode.name
                    lineCode = episode.fromCode
                    playAddress(it)
                }.onFailure { errorText = "切换失败：" + (it.message ?: "请重试") }
            }
        }
    }

    fun toggleFullscreen() {
        fullscreen = !fullscreen
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (fullscreen) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    override fun onPause() {
        shouldResume = playback?.playWhenReady == true
        backgrounded = true
        if (!isInPictureInPictureMode) playback?.pause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        backgrounded = false
        if (shouldResume) playback?.play()
        shouldResume = false
    }

    override fun onStop() {
        if (!isInPictureInPictureMode) playback?.pause()
        super.onStop()
    }

    override fun onDestroy() {
        worker.shutdownNow()
        playback?.release()
        playback = null
        super.onDestroy()
    }
}

@Composable
private fun MoviePlayerScreen(host: MoviePlayerActivity) {
    val player = host.playback
    var showControls by remember { mutableStateOf(true) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var seeking by remember { mutableStateOf(false) }
    var fraction by remember { mutableFloatStateOf(0f) }
    var lineIndex by remember(host.movieDetail?.movie?.id) { mutableIntStateOf(0) }

    BackHandler(host.fullscreen) { host.toggleFullscreen() }

    LaunchedEffect(player) {
        while (isActive) {
            position = player?.currentPosition?.coerceAtLeast(0L) ?: 0L
            val d = player?.duration ?: 0L
            duration = if (d == C.TIME_UNSET || d < 0L) 0L else d
            delay(450L)
        }
    }
    LaunchedEffect(host.movieDetail) {
        val index = host.movieDetail?.lines?.indexOfFirst { it.code == host.lineCode } ?: -1
        if (index >= 0) lineIndex = index
    }

    val playerSurface: @Composable (Modifier) -> Unit = { modifier ->
        Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
            if (player != null) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context ->
                        PlayerView(context).apply {
                            layoutParams = FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                            useController = false
                            keepScreenOn = true
                            this.player = player
                        }
                    },
                    update = { it.player = player }
                )
            }
            Box(Modifier.fillMaxSize().clickable { showControls = !showControls })
            if (host.changingEpisode) CircularProgressIndicator(Modifier.size(34.dp))
            if (showControls) {
                IconButton(
                    onClick = { if (host.playing) player?.pause() else player?.play() },
                    modifier = Modifier.align(Alignment.Center).size(45.dp)
                        .background(Color.Black.copy(alpha = 0.42f), RoundedCornerShape(24.dp))
                ) {
                    Icon(
                        if (host.playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                        if (host.playing) "暂停" else "播放",
                        Modifier.size(25.dp),
                        tint = Color.White
                    )
                }
                Row(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.60f))
                        .padding(start = 10.dp, end = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(clock(if (seeking) (fraction * duration).toLong() else position),
                        fontSize = 10.sp, color = Color.White)
                    Slider(
                        value = if (seeking) fraction else
                            if (duration > 0L) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f,
                        onValueChange = { fraction = it; seeking = true },
                        onValueChangeFinished = {
                            if (duration > 0L) player?.seekTo((fraction * duration).toLong())
                            seeking = false
                        },
                        enabled = duration > 0,
                        modifier = Modifier.weight(1f).padding(horizontal = 7.dp).height(33.dp)
                    )
                    Text(clock(duration), fontSize = 10.sp, color = Color.White)
                    IconButton(onClick = host::toggleFullscreen, modifier = Modifier.size(36.dp)) {
                        Icon(
                            if (host.fullscreen) Icons.Outlined.FullscreenExit else Icons.Outlined.Fullscreen,
                            "切换全屏", Modifier.size(20.dp), tint = Color.White
                        )
                    }
                }
            }
        }
    }

    if (host.fullscreen) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            playerSurface(Modifier.fillMaxSize())
            IconButton(
                onClick = host::toggleFullscreen,
                modifier = Modifier.align(Alignment.TopStart).padding(10.dp).size(38.dp)
                    .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(20.dp))
            ) {
                Icon(Icons.Outlined.ArrowBack, "退出全屏", Modifier.size(20.dp), tint = Color.White)
            }
        }
    } else {
        Column(Modifier.fillMaxSize().background(InkPlayer)) {
            Row(
                Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 13.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = host::finish, modifier = Modifier.size(38.dp)) {
                    Icon(Icons.Outlined.ArrowBack, "返回", Modifier.size(20.dp), tint = Color.White)
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    host.movieDetail?.movie?.name?.takeIf { it.isNotBlank() }
                        ?: host.intent.getStringExtra(MoviePlayerActivity.EXTRA_TITLE).orEmpty(),
                    Modifier.weight(1f),
                    fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                IconButton(onClick = host::toggleFullscreen, modifier = Modifier.size(38.dp)) {
                    Icon(Icons.Outlined.Fullscreen, "全屏", Modifier.size(20.dp), tint = Color.White)
                }
            }
            playerSurface(Modifier.fillMaxWidth().aspectRatio(16f / 9f))
            Column(Modifier.fillMaxWidth().padding(horizontal = 17.dp, vertical = 13.dp)) {
                Text(
                    host.episodeName.ifBlank { "正在播放" },
                    color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(4.dp))
                Text("切换线路和剧集 · 点击全屏图标进入横屏", color = HintPlayer, fontSize = 11.sp)
                if (host.errorText.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(host.errorText, fontSize = 12.sp, color = Color(0xFFFFAB96), maxLines = 4)
                }
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("全部剧集", fontSize = 17.sp, color = Color.White, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    if (host.loadingList || host.changingEpisode) {
                        CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp)
                    } else if (host.movieDetail == null) {
                        TextButton(onClick = host::loadEpisodeList) {
                            Icon(Icons.Outlined.Refresh, "重试", Modifier.size(15.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("重试", fontSize = 12.sp)
                        }
                    }
                }
                val lines = host.movieDetail?.lines.orEmpty()
                if (lines.isNotEmpty()) {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        lines.forEachIndexed { index, line ->
                            FilterChip(
                                selected = index == lineIndex,
                                onClick = { lineIndex = index },
                                label = { Text(line.name, fontSize = 12.sp, maxLines = 1) }
                            )
                        }
                    }
                }
            }
            val currentLine = host.movieDetail?.lines?.getOrNull(lineIndex)
            val episodes = currentLine?.episodes.orEmpty()
            if (host.movieDetail == null || episodes.isEmpty()) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Text(
                        if (host.loadingList) "正在加载完整剧集…" else
                            if (host.movieDetail == null) "剧集列表暂不可用，请重试"
                            else "该线路暂无剧集",
                        fontSize = 12.sp, color = HintPlayer
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
                    modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 17.dp),
                    contentPadding = PaddingValues(bottom = 30.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(9.dp)
                ) {
                    items(episodes.size) { index ->
                        val ep = episodes[index]
                        val selected = currentLine?.code == host.lineCode &&
                            ((ep.id.isNotBlank() && ep.id == host.episodeId) ||
                                (ep.id.isBlank() && ep.name == host.episodeName))
                        Surface(
                            onClick = { host.choose(ep) },
                            enabled = !host.changingEpisode,
                            shape = RoundedCornerShape(12.dp),
                            color = if (selected) AccentPlayer else SoftPlayer,
                            contentColor = if (selected) InkPlayer else Color.White
                        ) {
                            Box(Modifier.fillMaxWidth().height(46.dp).padding(horizontal = 4.dp),
                                contentAlignment = Alignment.Center) {
                                Text(
                                    ep.name.ifBlank { "第 " + (index + 1) + " 集" },
                                    fontSize = 12.sp,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun clock(millis: Long): String {
    val total = millis.coerceAtLeast(0) / 1000L
    return "%02d:%02d".format(total / 60L, total % 60L)
}
