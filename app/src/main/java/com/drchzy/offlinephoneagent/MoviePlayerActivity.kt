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
import androidx.compose.ui.input.pointer.consumeAllChanges
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
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
    var browseLineIndex by mutableIntStateOf(0)
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

    fun applySpeed(value: Float) {
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
        browseLineIndex = movieDetail?.lines?.indexOfFirst { it.code == lineCode }
            ?.takeIf { it >= 0 } ?: 0
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
                result.onSuccess {
                    movieDetail = it
                    browseLineIndex = it.lines.indexOfFirst { line -> line.code == lineCode }
                        .takeIf { index -> index >= 0 } ?: 0
                }
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
                    browseLineIndex = movieDetail?.lines?.indexOfFirst { line -> line.code == lineCode }
                        ?.takeIf { index -> index >= 0 } ?: 0
                    playAddress(it)
                }.onFailure { errorText = "切换失败：" + (it.message ?: "请重试") }
            }
        }
    }

    fun selectLine(index: Int, startPlaying: Boolean = false) {
        val list = movieDetail?.lines.orEmpty()
        if (index !in list.indices) return
        val previous = list.getOrNull(browseLineIndex)
        val playingIndex = previous?.episodes?.indexOfFirst {
            (it.id.isNotBlank() && it.id == episodeId) || it.name == episodeName
        }?.coerceAtLeast(0) ?: 0
        browseLineIndex = index
        if (startPlaying) {
            val next = list[index].episodes.getOrNull(playingIndex)
                ?: list[index].episodes.firstOrNull()
            if (next != null) choose(next)
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
    val context = androidx.compose.ui.platform.LocalContext.current
    val player = host.playback
    var showControls by remember { mutableStateOf(true) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var seeking by remember { mutableStateOf(false) }
    var fraction by remember { mutableFloatStateOf(0f) }
    var kbps by remember { mutableLongStateOf(0L) }
    var gestureLabel by remember { mutableStateOf("") }
    var gestureWidth by remember { mutableIntStateOf(1) }
    var gestureHeight by remember { mutableIntStateOf(1) }
    var speedMenu by remember { mutableStateOf(false) }
    var lineMenu by remember { mutableStateOf(false) }
    var showCast by remember { mutableStateOf(false) }
    var castBusy by remember { mutableStateOf(false) }
    var castDevices by remember { mutableStateOf<List<DlnaDevice>>(emptyList()) }
    var castMessage by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    BackHandler(enabled = host.fullscreen) { host.toggleFullscreen() }

    LaunchedEffect(player) {
        while (isActive) {
            position = player?.currentPosition?.coerceAtLeast(0L) ?: 0L
            val now = player?.duration ?: 0L
            duration = if (now == C.TIME_UNSET || now < 0L) 0L else now
            delay(450L)
        }
    }
    LaunchedEffect(Unit) {
        var previous = TrafficStats.getUidRxBytes(Process.myUid())
        while (isActive) {
            delay(1000L)
            val now = TrafficStats.getUidRxBytes(Process.myUid())
            kbps = if (now > previous && previous >= 0) (now - previous) / 1024L else 0L
            previous = now
        }
    }
    LaunchedEffect(gestureLabel) {
        if (gestureLabel.isNotEmpty()) {
            delay(1300L)
            gestureLabel = ""
        }
    }
    LaunchedEffect(showCast) {
        if (showCast) {
            castBusy = true
            castMessage = ""
            castDevices = withContext(Dispatchers.IO) {
                runCatching { DlnaCast.discover(context) }.getOrElse {
                    castMessage = it.message ?: "设备发现失败"
                    emptyList()
                }
            }
            castBusy = false
        }
    }
    if (showCast) {
        AlertDialog(
            onDismissRequest = { showCast = false },
            title = { Text("DLNA 投屏") },
            text = {
                Column {
                    Text("请连接与电视相同的 Wi-Fi。仅支持可直接访问的视频链接；部分需登录的播放线路无法投屏。",
                        fontSize = 12.sp, lineHeight = 18.sp)
                    Spacer(Modifier.height(12.dp))
                    if (castBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    else if (castDevices.isEmpty()) Text(
                        castMessage.ifBlank { "没有发现 DLNA 电视或投影设备。请确认设备支持 UPnP/AVTransport。" },
                        fontSize = 12.sp
                    )
                    else castDevices.forEach { device ->
                        TextButton(onClick = {
                            castBusy = true
                            scope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    runCatching { DlnaCast.play(device, host.mediaUrl) }
                                }
                                castBusy = false
                                result.onSuccess {
                                    host.playback?.pause()
                                    showCast = false
                                    android.widget.Toast.makeText(context,
                                        "已发送投屏指令至 " + device.name,
                                        android.widget.Toast.LENGTH_LONG).show()
                                }.onFailure {
                                    castMessage = it.message ?: "投屏失败"
                                }
                            }
                        }) {
                            Icon(Icons.Outlined.Cast, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(7.dp))
                            Text(device.name)
                        }
                    }
                    if (castMessage.isNotBlank() && castDevices.isNotEmpty()) {
                        Text(castMessage, color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showCast = false }) { Text("关闭") } },
            dismissButton = {
                TextButton(onClick = {
                    showCast = false
                    showCast = true
                }) { Text("刷新") }
            }
        )
    }

    val surface: @Composable (Modifier) -> Unit = { modifier ->
        Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
            if (player != null) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        PlayerView(ctx).apply {
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

            if (!host.pictureInPicture) {
                Box(
                    Modifier.fillMaxSize()
                        .onSizeChanged {
                            gestureWidth = it.width.coerceAtLeast(1)
                            gestureHeight = it.height.coerceAtLeast(1)
                        }
                        .pointerInput(player) {
                            detectTapGestures(
                                onTap = { showControls = !showControls },
                                onLongPress = {
                                    host.applySpeed(2f)
                                    gestureLabel = "长按 2× 快进"
                                },
                                onPress = {
                                    val before = host.speed
                                    tryAwaitRelease()
                                    if (host.speed == 2f && before != 2f) host.applySpeed(before)
                                }
                            )
                        }
                        .pointerInput(gestureWidth, gestureHeight) {
                            var onLeft = true
                            detectVerticalDragGestures(
                                onDragStart = { onLeft = it.x < gestureWidth / 2f },
                                onVerticalDrag = { change, dy ->
                                    change.consume()
                                    val amount = -dy / gestureHeight.toFloat().coerceAtLeast(1f)
                                    if (onLeft) {
                                        host.brightness(amount)
                                        gestureLabel = "亮度 " + (host.brightnessLevel * 100).toInt() + "%"
                                    } else {
                                        host.volume(amount)
                                        gestureLabel = "音量 " + (host.volumeLevel * 100).toInt() + "%"
                                    }
                                }
                            )
                        }
                )
                if (gestureLabel.isNotBlank()) {
                    Surface(shape = RoundedCornerShape(18.dp),
                        color = Color.Black.copy(alpha = 0.70f)) {
                        Text(gestureLabel, Modifier.padding(horizontal = 16.dp, vertical = 11.dp),
                            color = Color.White, fontSize = 13.sp)
                    }
                }
                if (host.changingEpisode) CircularProgressIndicator(Modifier.size(29.dp))
                if (showControls) {
                    IconButton(
                        onClick = { if (host.playing) player?.pause() else player?.play() },
                        modifier = Modifier.align(Alignment.Center).size(40.dp)
                            .background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(20.dp))
                    ) {
                        Icon(if (host.playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                            "播放暂停", Modifier.size(24.dp), tint = Color.White)
                    }
                    Column(
                        Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                            .background(Brush.verticalGradient(listOf(
                                Color.Transparent, Color.Black.copy(alpha = 0.86f))))
                            .padding(horizontal = 7.dp, vertical = 1.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(clock(if (seeking) (fraction * duration).toLong() else position),
                                color = Color.White, fontSize = 10.sp)
                            Slider(
                                value = if (seeking) fraction else
                                    if (duration > 0L)
                                        (position.toFloat() / duration).coerceIn(0f, 1f) else 0f,
                                onValueChange = { fraction = it; seeking = true },
                                onValueChangeFinished = {
                                    if (duration > 0L) player?.seekTo((fraction * duration).toLong())
                                    seeking = false
                                },
                                enabled = duration > 0L,
                                modifier = Modifier.weight(1f).padding(horizontal = 7.dp).height(29.dp)
                            )
                            Text(clock(duration), color = Color.White, fontSize = 10.sp)
                        }
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            IconButton(
                                onClick = { if (host.playing) player?.pause() else player?.play() },
                                modifier = Modifier.size(30.dp)
                            ) {
                                Icon(if (host.playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                                    "暂停播放", Modifier.size(19.dp), tint = Color.White)
                            }
                            Text("↓" + kbps + " KB/s", fontSize = 10.sp, color = Color.White)
                            Spacer(Modifier.weight(1f))
                            Box {
                                TextButton(onClick = { speedMenu = true },
                                    contentPadding = PaddingValues(horizontal = 4.dp)) {
                                    Text(host.speed.toString() + "×", fontSize = 11.sp,
                                        color = Color.White)
                                }
                                DropdownMenu(expanded = speedMenu, onDismissRequest = { speedMenu = false }) {
                                    listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f).forEach { rate ->
                                        DropdownMenuItem(text = { Text(rate.toString() + "×") },
                                            onClick = { host.applySpeed(rate); speedMenu = false })
                                    }
                                }
                            }
                            Box {
                                IconButton(onClick = { lineMenu = true }, modifier = Modifier.size(31.dp)) {
                                    Icon(Icons.Outlined.List, "切换线路", Modifier.size(18.dp),
                                        tint = Color.White)
                                }
                                DropdownMenu(expanded = lineMenu, onDismissRequest = { lineMenu = false }) {
                                    host.movieDetail?.lines.orEmpty().forEachIndexed { index, line ->
                                        DropdownMenuItem(text = { Text(line.name) },
                                            onClick = {
                                                host.selectLine(index, true)
                                                lineMenu = false
                                            })
                                    }
                                }
                            }
                            IconButton(onClick = { showCast = true }, modifier = Modifier.size(31.dp)) {
                                Icon(Icons.Outlined.Cast, "DLNA投屏", Modifier.size(18.dp),
                                    tint = Color.White)
                            }
                            IconButton(onClick = host::minimizeToPiP, modifier = Modifier.size(31.dp)) {
                                Icon(Icons.Outlined.PictureInPictureAlt, "画中画", Modifier.size(18.dp),
                                    tint = Color.White)
                            }
                            IconButton(onClick = host::toggleFullscreen, modifier = Modifier.size(31.dp)) {
                                Icon(if (host.fullscreen) Icons.Outlined.FullscreenExit
                                    else Icons.Outlined.Fullscreen, "全屏", Modifier.size(20.dp),
                                    tint = Color.White)
                            }
                        }
                    }
                }
            }
        }
    }
    val header: @Composable () -> Unit = {
        Row(
            Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { host.finish() }, modifier = Modifier.size(39.dp)) {
                Icon(Icons.Outlined.ArrowBack, "返回", Modifier.size(19.dp), tint = Color.White)
            }
            Text(
                host.movieDetail?.movie?.name?.takeIf { it.isNotBlank() }
                    ?: host.intent.getStringExtra(MoviePlayerActivity.EXTRA_TITLE).orEmpty(),
                modifier = Modifier.weight(1f), fontSize = 15.sp,
                color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            IconButton(onClick = { showCast = true }, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Outlined.Cast, "DLNA投屏", Modifier.size(20.dp), tint = Color.White)
            }
            IconButton(onClick = host::minimizeToPiP, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Outlined.PictureInPictureAlt, "最小化画中画",
                    Modifier.size(20.dp), tint = Color.White)
            }
            IconButton(onClick = host::toggleFullscreen, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Outlined.Fullscreen, "全屏", Modifier.size(20.dp), tint = Color.White)
            }
        }
    }

    BoxWithConstraints(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(Color(0xFF15131E), Color(0xFF201B2C), Color(0xFF13141C))))
            .then(if (host.fullscreen || host.pictureInPicture) Modifier
                else Modifier.statusBarsPadding().navigationBarsPadding())
    ) {
        val isTabletLandscape = maxWidth >= 720.dp && maxWidth > maxHeight
        if (host.pictureInPicture) {
            surface(Modifier.fillMaxSize())
        } else if (host.fullscreen) {
            Box(Modifier.fillMaxSize()) {
                surface(Modifier.fillMaxSize())
                IconButton(onClick = host::toggleFullscreen,
                    modifier = Modifier.align(Alignment.TopStart).padding(10.dp).size(34.dp)
                        .background(Color.Black.copy(alpha = 0.50f), RoundedCornerShape(18.dp))) {
                    Icon(Icons.Outlined.ArrowBack, "退出全屏", Modifier.size(19.dp),
                        tint = Color.White)
                }
            }
        } else if (isTabletLandscape) {
            Row(Modifier.fillMaxSize()) {
                Column(Modifier.weight(1.35f)) {
                    header()
                    surface(Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                    Spacer(Modifier.height(10.dp))
                    Text("左侧滑动调亮度 · 右侧调音量 · 长按 2× 快进",
                        Modifier.padding(horizontal = 15.dp), color = HintPlayer, fontSize = 11.sp)
                }
                EpisodeBrowser(host, Modifier.weight(1f).fillMaxHeight())
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                header()
                surface(Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                EpisodeBrowser(host, Modifier.fillMaxWidth().weight(1f))
            }
        }
    }
}

@Composable
private fun EpisodeBrowser(host: MoviePlayerActivity, modifier: Modifier = Modifier) {
    // 每次切换播放线路都恢复折叠状态，避免长剧集列表占满页面。
    var expanded by remember(host.movieDetail?.movie?.id, host.browseLineIndex) {
        mutableStateOf(false)
    }
    val current = host.movieDetail?.lines?.getOrNull(host.browseLineIndex)
    val episodes = current?.episodes.orEmpty()
    Column(modifier) {
        Column(Modifier.fillMaxWidth().padding(start = 15.dp, end = 15.dp, top = 10.dp)) {
            Text(host.movieDetail?.movie?.name.orEmpty().ifBlank { "正在播放" } +
                " · " + host.episodeName, color = Color.White,
                fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(5.dp))
            val movie = host.movieDetail?.movie
            if (movie != null) {
                Text(movie.year + " · " + movie.type + " · " + movie.dynamic,
                    color = HintPlayer, fontSize = 11.sp, maxLines = 1)
                Spacer(Modifier.height(4.dp))
                Text(movie.summary.ifBlank { "暂无简介" }, color = HintPlayer,
                    fontSize = 11.sp, maxLines = 2, lineHeight = 16.sp,
                    overflow = TextOverflow.Ellipsis)
            }
            if (host.errorText.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(host.errorText, color = Color(0xFFFFB09E), fontSize = 11.sp,
                    maxLines = 3)
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("全部剧集", color = Color.White,
                    fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text("  " + episodes.size + " 集", color = HintPlayer, fontSize = 11.sp)
                Spacer(Modifier.weight(1f))
                if (episodes.size > 8) {
                    TextButton(onClick = { expanded = !expanded }) {
                        Icon(
                            if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                            contentDescription = null, modifier = Modifier.size(17.dp)
                        )
                        Text(if (expanded) "收起" else "展开全部", fontSize = 11.sp)
                    }
                }
                if (host.loadingList || host.changingEpisode) {
                    CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp)
                } else if (host.movieDetail == null) {
                    TextButton(onClick = host::loadEpisodeList) {
                        Icon(Icons.Outlined.Refresh, "重试", Modifier.size(15.dp))
                        Text("重试")
                    }
                }
            }
            val lines = host.movieDetail?.lines.orEmpty()
            if (lines.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    lines.forEachIndexed { index, line ->
                        FilterChip(
                            selected = index == host.browseLineIndex,
                            onClick = { host.selectLine(index) },
                            label = { Text(line.name, fontSize = 11.sp, maxLines = 1) }
                        )
                    }
                }
            }
            Spacer(Modifier.height(5.dp))
        }
        if (episodes.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(if (host.loadingList) "正在加载完整剧集…" else "该线路暂无可选剧集",
                    color = HintPlayer, fontSize = 12.sp)
            }
        } else {
            // 适配手机/平板宽度：默认最多显示三行，展开后列表才独立滚动。
            BoxWithConstraints(
                modifier = if (expanded) Modifier.fillMaxWidth().weight(1f)
                else Modifier.fillMaxWidth().height(153.dp)
            ) {
                val columns = maxOf(2, (maxWidth.value / 83f).toInt())
                val shown = if (expanded) episodes else episodes.take(columns * 3)
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    modifier = Modifier.fillMaxSize().padding(horizontal = 13.dp),
                    userScrollEnabled = expanded,
                    contentPadding = PaddingValues(bottom = if (expanded) 24.dp else 0.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(shown.size) { index ->
                        val ep = shown[index]
                        val selected = current?.code == host.lineCode &&
                            ((ep.id.isNotBlank() && ep.id == host.episodeId) ||
                                (ep.id.isBlank() && ep.name == host.episodeName))
                        Surface(
                            onClick = { host.choose(ep) },
                            enabled = !host.changingEpisode,
                            shape = RoundedCornerShape(11.dp),
                            color = if (selected) AccentPlayer else SoftPlayer,
                            contentColor = if (selected) InkPlayer else Color.White
                        ) {
                            Box(Modifier.fillMaxWidth().height(42.dp).padding(horizontal = 3.dp),
                                contentAlignment = Alignment.Center) {
                                Text(ep.name.ifBlank { "第 " + (index + 1) + " 集" },
                                    fontSize = 11.sp,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
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
