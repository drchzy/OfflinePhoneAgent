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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.geometry.Offset
import android.os.SystemClock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.roundToInt
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
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
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
        const val EXTRA_MOVIE_NAME = "movie_name_for_history"
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
    private var savedEpisodeName = ""
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
        // 保留小数累积手势位移，避免音量档位取整后每次小滑动都变为 0。
        volumeLevel = (volumeLevel + delta).coerceIn(0f, 1f)
        val next = (volumeLevel * max).roundToInt().coerceIn(0, max)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, next, 0)
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
        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val volumeMax = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        volumeLevel = audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / volumeMax
        val screenBrightness = android.provider.Settings.System.getInt(
            contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS, 128
        )
        brightnessLevel = (screenBrightness / 255f).coerceIn(0.02f, 1f)
        movieId = intent.getStringExtra(EXTRA_MOVIE_ID).orEmpty()
        episodeId = intent.getStringExtra(EXTRA_EPISODE_ID).orEmpty()
        lineCode = intent.getStringExtra(EXTRA_LINE_CODE).orEmpty()
        episodeName = intent.getStringExtra(EXTRA_EPISODE_NAME).orEmpty()
        savedEpisodeName = intent.getStringExtra(EXTRA_MOVIE_NAME).orEmpty()
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
            override fun onPlaybackStateChanged(state: Int) {
                if (playback === next && state == Player.STATE_ENDED) {
                    playFollowingEpisode()
                }
            }
        })
        mediaUrl = address.url
        // 从本地播放记录恢复未看完剧集的播放位置；已看完的剧集从头开始。
        val previousPosition = MovieWatchHistory.all(applicationContext).firstOrNull {
            it.movieId == movieId && it.lineCode == lineCode &&
                (episodeId.isNotBlank() && it.episodeId == episodeId ||
                    episodeId.isBlank() && it.episodeName == episodeName)
        }?.resumeMs ?: 0L
        val previous = playback
        playback = next
        playing = false
        previous?.release()
        errorText = ""
        next.setMediaItem(MediaItem.fromUri(address.url))
        if (previousPosition > 0L) next.seekTo(previousPosition)
        next.prepare()
        next.playWhenReady = !backgrounded || isInPictureInPictureMode
        next.playbackParameters = PlaybackParameters(speed)
    }

    /** 每 5 秒以及离开、切集时保存进度，不保存真实视频 URL 或认证请求头。 */
    fun checkpoint(completed: Boolean = false) {
        val active = playback ?: return
        val duration = active.duration.takeIf { it > 0 && it != C.TIME_UNSET } ?: 0L
        MovieWatchHistory.save(
            context = applicationContext,
            movieId = movieId,
            movieName = movieDetail?.movie?.name?.takeIf { it.isNotBlank() }
                ?: savedEpisodeName,
            episodeId = episodeId,
            episodeName = episodeName,
            lineCode = lineCode,
            positionMs = if (completed && duration > 0) duration else active.currentPosition,
            durationMs = duration,
            completed = completed
        )
    }

    /** 正常播完后自动播放同一线路的下一集；最后一集停止，不循环重播。 */
    private fun playFollowingEpisode() {
        checkpoint(completed = true)
        if (changingEpisode || isFinishing || isDestroyed) return
        val line = movieDetail?.lines?.firstOrNull { it.code == lineCode } ?: return
        val current = line.episodes.indexOfFirst {
            (episodeId.isNotBlank() && it.id == episodeId) ||
                (episodeId.isBlank() && it.name == episodeName)
        }
        if (current >= 0 && current + 1 < line.episodes.size) {
            choose(line.episodes[current + 1])
        }
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
        checkpoint()
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

    /** 当前线路的前一集或后一集；到达边界时不发起网络请求。 */
    private fun adjacentEpisode(offset: Int): MovieEpisode? {
        val line = movieDetail?.lines?.firstOrNull { it.code == lineCode } ?: return null
        val current = line.episodes.indexOfFirst { ep ->
            (episodeId.isNotBlank() && ep.id == episodeId) ||
                (episodeId.isBlank() && ep.name == episodeName)
        }
        if (current < 0) return null
        return line.episodes.getOrNull(current + offset)
    }

    fun canNavigateEpisode(offset: Int): Boolean =
        !changingEpisode && adjacentEpisode(offset) != null

    fun navigateEpisode(offset: Int) {
        if (changingEpisode) return
        adjacentEpisode(offset)?.let { choose(it) }
    }

    fun playerTitle(): String {
        val name = movieDetail?.movie?.name?.takeIf { it.isNotBlank() }
            ?: savedEpisodeName.ifBlank { intent.getStringExtra(EXTRA_TITLE).orEmpty() }
        val line = movieDetail?.lines?.firstOrNull { it.code == lineCode }
        val index = line?.episodes?.indexOfFirst {
            (episodeId.isNotBlank() && it.id == episodeId) ||
                (episodeId.isBlank() && it.name == episodeName)
        } ?: -1
        val episode = if (episodeName.isNotBlank()) episodeName else
            if (index >= 0) "第${index + 1}集" else ""
        return if (episode.isBlank()) name else "$name · $episode"
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
        checkpoint()
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
        checkpoint()
        if (!isInPictureInPictureMode) playback?.pause()
        super.onStop()
    }

    override fun onDestroy() {
        checkpoint()
        worker.shutdownNow()
        playback?.release()
        playback = null
        super.onDestroy()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MoviePlayerScreen(host: MoviePlayerActivity) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val player = host.playback
    var showControls by remember { mutableStateOf(true) }
    var controlReset by remember { mutableIntStateOf(0) }
    var episodePickerOpen by remember { mutableStateOf(false) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var seeking by remember { mutableStateOf(false) }
    var fraction by remember { mutableFloatStateOf(0f) }
    var kbps by remember { mutableLongStateOf(0L) }
    var gestureLabel by remember { mutableStateOf("") }
    var gestureWidth by remember { mutableIntStateOf(1) }
    var gestureHeight by remember { mutableIntStateOf(1) }
    var previewSeek by remember { mutableLongStateOf(-1L) }
    var speedMenu by remember { mutableStateOf(false) }
    var lineMenu by remember { mutableStateOf(false) }
    var showCast by remember { mutableStateOf(false) }
    var castBusy by remember { mutableStateOf(false) }
    var castDevices by remember { mutableStateOf<List<DlnaDevice>>(emptyList()) }
    var castMessage by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    BackHandler(enabled = host.fullscreen) { host.toggleFullscreen() }

    // 无交互 5 秒自动隐藏：弹出菜单、选集面板、投屏对话框时不计时。
    LaunchedEffect(showControls, controlReset, speedMenu, lineMenu, episodePickerOpen, showCast) {
        if (showControls && !speedMenu && !lineMenu && !episodePickerOpen && !showCast) {
            delay(5_000L)
            showControls = false
        }
    }

    LaunchedEffect(player) {
        var lastSave = 0L
        while (isActive) {
            position = player?.currentPosition?.coerceAtLeast(0L) ?: 0L
            val now = player?.duration ?: 0L
            duration = if (now == C.TIME_UNSET || now < 0L) 0L else now
            val elapsed = android.os.SystemClock.elapsedRealtime()
            if (player != null && player.isPlaying && position > 0L &&
                elapsed - lastSave >= 5_000L
            ) {
                host.checkpoint()
                lastSave = elapsed
            }
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
    if (episodePickerOpen) {
        ModalBottomSheet(
            onDismissRequest = { episodePickerOpen = false },
            containerColor = SoftPlayer
        ) {
            Box(Modifier.fillMaxWidth().fillMaxHeight(0.82f)) {
                EpisodeBrowser(
                    host,
                    modifier = Modifier.fillMaxSize(),
                    startExpanded = true,
                    onEpisodeSelected = {
                        episodePickerOpen = false
                        showControls = false
                    }
                )
            }
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
                // 同一个事件识别器处理点按、长按及两个轴向的滑动，避免事件冲突。
                Box(
                    Modifier.fillMaxSize()
                        .onSizeChanged {
                            gestureWidth = it.width.coerceAtLeast(1)
                            gestureHeight = it.height.coerceAtLeast(1)
                        }
                        .pointerInput(player, host.fullscreen) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val downAt = SystemClock.uptimeMillis()
                                val w = size.width.coerceAtLeast(1).toFloat()
                                val h = size.height.coerceAtLeast(1).toFloat()
                                val left = down.position.x < w / 2f
                                val base = player?.currentPosition?.coerceAtLeast(0L) ?: 0L
                                val available = player?.duration?.takeIf {
                                    it > 0L && it != C.TIME_UNSET
                                } ?: 0L
                                val slop = viewConfiguration.touchSlop
                                var last = down.position
                                var moved = Offset.Zero
                                var direction = 0 // 0=点击/长按；1=横向进度；2=纵向调节
                                var held = false
                                var lastTick = 0L
                                var pressed = true
                                while (pressed) {
                                    val event = withTimeoutOrNull(45L) { awaitPointerEvent() }
                                    if (event != null) {
                                        val change = event.changes.firstOrNull { it.id == down.id }
                                            ?: break
                                        if (!change.pressed) {
                                            pressed = false
                                            break
                                        }
                                        val delta = change.position - last
                                        last = change.position
                                        moved += delta
                                        if (direction == 0 && !held &&
                                            (abs(moved.x) > slop || abs(moved.y) > slop)
                                        ) {
                                            direction = if (abs(moved.x) >= abs(moved.y)) 1 else 2
                                        }
                                        when (direction) {
                                            1 -> {
                                                if (available > 0L) {
                                                    val shift = (moved.x / w * 120_000f).toLong()
                                                    previewSeek = (base + shift).coerceIn(0L, available)
                                                    gestureLabel = "预览进度  " + clock(previewSeek)
                                                }
                                                change.consume()
                                            }
                                            2 -> {
                                                val amount = -delta.y / h * 2.8f
                                                if (left) {
                                                    host.brightness(amount)
                                                    gestureLabel = "亮度 " +
                                                        (host.brightnessLevel * 100).roundToInt() + "%"
                                                } else {
                                                    host.volume(amount)
                                                    gestureLabel = "音量 " +
                                                        (host.volumeLevel * 100).roundToInt() + "%"
                                                }
                                                change.consume()
                                            }
                                        }
                                    }
                                    if (direction == 0 && SystemClock.uptimeMillis() - downAt >= 490L) {
                                        held = true
                                    }
                                    if (held && direction == 0 && player != null) {
                                        val now = SystemClock.uptimeMillis()
                                        if (lastTick == 0L || now - lastTick >= 350L) {
                                            val desired = player.currentPosition + if (left) -2000L else 2000L
                                            player.seekTo(
                                                if (available > 0L) desired.coerceIn(0L, available)
                                                else desired.coerceAtLeast(0L)
                                            )
                                            gestureLabel = if (left) "长按快退  -2 秒" else "长按快进  +2 秒"
                                            lastTick = now
                                        }
                                    }
                                }
                                if (direction == 1 && previewSeek >= 0L) {
                                    player?.seekTo(previewSeek)
                                    previewSeek = -1L
                                } else if (direction == 0 && !held) {
                                    showControls = !showControls
                                    controlReset++
                                } else {
                                    controlReset++
                                }
                            }
                        }
                )

                if (gestureLabel.isNotBlank()) {
                    Surface(
                        color = Color.Black.copy(alpha = 0.72f),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text(
                            gestureLabel, Modifier.padding(horizontal = 13.dp, vertical = 9.dp),
                            color = Color.White, fontSize = 12.sp
                        )
                    }
                }
                if (host.changingEpisode) CircularProgressIndicator(Modifier.size(28.dp))
                // 所有叠加信息（包括网速、全屏返回）随轻点视频同步隐藏。
                if (showControls) {
                    // 顶部一行自适应：网速 / 当前剧集 / 投屏与画中画。
                    Row(
                        modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth()
                            .background(Brush.verticalGradient(listOf(
                                Color.Black.copy(alpha = .62f), Color.Transparent
                            )))
                            .padding(horizontal = 5.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (host.fullscreen) {
                            IconButton(
                                onClick = { host.toggleFullscreen(); controlReset++ },
                                modifier = Modifier.size(34.dp)
                            ) {
                                Icon(Icons.Outlined.ArrowBack, "退出全屏",
                                    Modifier.size(19.dp), tint = Color.White)
                            }
                        }
                        Text("↓ $kbps KB/s", color = Color.White, fontSize = 10.sp,
                            maxLines = 1, modifier = Modifier.padding(horizontal = 4.dp))
                        Text(
                            host.playerTitle(), color = Color.White,
                            fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.weight(1f)
                                .padding(horizontal = 4.dp)
                        )
                        IconButton(
                            onClick = { showCast = true; controlReset++ },
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(Icons.Outlined.Cast, "投屏",
                                Modifier.size(19.dp), tint = Color.White)
                        }
                        IconButton(
                            onClick = { host.minimizeToPiP(); controlReset++ },
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(Icons.Outlined.PictureInPictureAlt, "画中画",
                                Modifier.size(19.dp), tint = Color.White)
                        }
                    }
                    // 手动切集不挤占窄屏底部进度条，按钮放在视频两侧。
                    IconButton(
                        onClick = { host.navigateEpisode(-1); controlReset++ },
                        enabled = host.canNavigateEpisode(-1),
                        modifier = Modifier.align(Alignment.CenterStart).padding(start = 12.dp)
                            .size(42.dp).background(
                                Color.Black.copy(alpha = .38f), RoundedCornerShape(22.dp))
                    ) {
                        Icon(Icons.Outlined.SkipPrevious, "上一集",
                            Modifier.size(27.dp),
                            tint = if (host.canNavigateEpisode(-1)) Color.White
                                else Color.White.copy(alpha = .32f))
                    }
                    IconButton(
                        onClick = { host.navigateEpisode(1); controlReset++ },
                        enabled = host.canNavigateEpisode(1),
                        modifier = Modifier.align(Alignment.CenterEnd).padding(end = 12.dp)
                            .size(42.dp).background(
                                Color.Black.copy(alpha = .38f), RoundedCornerShape(22.dp))
                    ) {
                        Icon(Icons.Outlined.SkipNext, "下一集",
                            Modifier.size(27.dp),
                            tint = if (host.canNavigateEpisode(1)) Color.White
                                else Color.White.copy(alpha = .32f))
                    }
                    IconButton(
                        onClick = {
                                if (host.playing) player?.pause() else player?.play()
                                controlReset++
                            },
                        modifier = Modifier.align(Alignment.Center).size(39.dp)
                            .background(Color.Black.copy(alpha = 0.44f), RoundedCornerShape(20.dp))
                    ) {
                        Icon(if (host.playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                            "暂停或播放", Modifier.size(23.dp), tint = Color.White)
                    }
                    Row(
                        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                            .background(Brush.verticalGradient(listOf(
                                Color.Transparent, Color.Black.copy(alpha = .85f))))
                            .padding(horizontal = 5.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        IconButton(
                            onClick = {
                                if (host.playing) player?.pause() else player?.play()
                                controlReset++
                            },
                            modifier = Modifier.size(38.dp)
                        ) {
                            Icon(
                                if (host.playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                                "播放或暂停", Modifier.size(25.dp), tint = Color.White
                            )
                        }
                        // 进度轨道与全部按钮同一行，宽度自适应手机和平板。
                        // 轨道厚度 4dp，透明触控区高 40dp，兼顾拖动和视觉清爽。
                        Canvas(
                            modifier = Modifier.weight(1f).height(42.dp)
                                .padding(horizontal = 4.dp)
                                .pointerInput(player, duration) {
                                    awaitEachGesture {
                                        val down = awaitFirstDown(requireUnconsumed = false)
                                        controlReset++
                                        val w = size.width.coerceAtLeast(1).toFloat()
                                        if (duration > 0L) {
                                            seeking = true
                                            fraction = (down.position.x / w).coerceIn(0f, 1f)
                                        }
                                        while (true) {
                                            val e = awaitPointerEvent()
                                            val ch = e.changes.firstOrNull { it.id == down.id }
                                                ?: break
                                            if (!ch.pressed) break
                                            if (duration > 0L) {
                                                fraction = (ch.position.x / w).coerceIn(0f, 1f)
                                            }
                                            ch.consume()
                                        }
                                        if (duration > 0L) {
                                            player?.seekTo((duration * fraction).toLong())
                                        }
                                        seeking = false
                                    }
                                }
                        ) {
                            val center = size.height / 2f
                            val ratio = if (duration > 0L) {
                                (if (seeking) fraction else position.toFloat() / duration)
                                    .coerceIn(0f, 1f)
                            } else 0f
                            drawLine(
                                Color.White.copy(alpha = .40f),
                                start = Offset(0f, center),
                                end = Offset(size.width, center),
                                strokeWidth = 5.dp.toPx(),
                                cap = androidx.compose.ui.graphics.StrokeCap.Round
                            )
                            drawLine(
                                AccentPlayer,
                                start = Offset(0f, center),
                                end = Offset(size.width * ratio, center),
                                strokeWidth = 5.dp.toPx(),
                                cap = androidx.compose.ui.graphics.StrokeCap.Round
                            )
                            drawCircle(
                                AccentPlayer, radius = 5.0.dp.toPx(),
                                center = Offset(size.width * ratio, center)
                            )
                        }
                        Box {
                            TextButton(
                                onClick = { speedMenu = true; controlReset++ },
                                modifier = Modifier.height(34.dp).widthIn(min = 40.dp),
                                contentPadding = PaddingValues(horizontal = 1.dp)
                            ) {
                                Text(host.speed.toString() + "×", fontSize = 11.sp, color = Color.White)
                            }
                            DropdownMenu(
                                expanded = speedMenu,
                                onDismissRequest = { speedMenu = false }
                            ) {
                                listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f).forEach { rate ->
                                    DropdownMenuItem(
                                        text = { Text(rate.toString() + "×") },
                                        onClick = { host.applySpeed(rate); speedMenu = false }
                                    )
                                }
                            }
                        }
                        Box {
                            IconButton(
                                onClick = { lineMenu = true; controlReset++ },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(Icons.Outlined.List, "切换线路",
                                    Modifier.size(22.dp), tint = Color.White)
                            }
                            DropdownMenu(
                                expanded = lineMenu,
                                onDismissRequest = { lineMenu = false },
                                modifier = Modifier.heightIn(max = 220.dp)
                                    .widthIn(min = 165.dp, max = 260.dp)
                            ) {
                                host.movieDetail?.lines.orEmpty().forEachIndexed { index, line ->
                                    DropdownMenuItem(
                                        text = { Text(line.name, maxLines = 1) },
                                        onClick = {
                                            host.selectLine(index, true)
                                            lineMenu = false
                                        }
                                    )
                                }
                            }
                        }
                        TextButton(
                            onClick = {
                                episodePickerOpen = true
                                controlReset++
                            },
                            modifier = Modifier.height(38.dp),
                            contentPadding = PaddingValues(horizontal = 2.dp)
                        ) {
                            Text("选集", fontSize = 12.sp, color = Color.White)
                        }
                        IconButton(
                            onClick = { host.toggleFullscreen(); controlReset++ },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                if (host.fullscreen) Icons.Outlined.FullscreenExit
                                else Icons.Outlined.Fullscreen,
                                "全屏", Modifier.size(22.dp), tint = Color.White
                            )
                        }
                        // 正在播放时间和总时长合并为一组，固定在控制栏右下角。
                        Text(
                            clock(if (seeking) (fraction * duration).toLong() else position) +
                                " / " + clock(duration),
                            color = Color.White,
                            fontSize = 10.sp,
                            maxLines = 1
                        )
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
                host.playerTitle(),
                modifier = Modifier.weight(1f), fontSize = 15.sp,
                color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
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
            }
        } else if (isTabletLandscape) {
            Row(Modifier.fillMaxSize()) {
                Column(Modifier.weight(1.7f).fillMaxHeight()) {
                    header()
                    BoxWithConstraints(
                        Modifier.fillMaxWidth().weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        val fittedWidth = minOf(maxWidth, maxHeight * (16f / 9f))
                        surface(Modifier.width(fittedWidth).aspectRatio(16f / 9f))
                    }
                    Text("左右滑动调整进度 · 左侧上下调亮度 · 右侧上下调音量",
                        Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        color = HintPlayer, fontSize = 11.sp)
                }
                EpisodeBrowser(
                    host, Modifier.weight(1f).fillMaxHeight(), startExpanded = true
                )
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
private fun EpisodeBrowser(
    host: MoviePlayerActivity,
    modifier: Modifier = Modifier,
    startExpanded: Boolean = false,
    onEpisodeSelected: () -> Unit = {}
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val watchVersion by MovieWatchHistory.revision.collectAsState()
    val history = remember(host.movieDetail?.movie?.id, watchVersion) {
        MovieWatchHistory.all(context).filter {
            it.movieId == host.movieDetail?.movie?.id
        }
    }
    val mostRecent = history.firstOrNull()
    val currentLine = host.movieDetail?.lines?.getOrNull(host.browseLineIndex)
    val episodes = currentLine?.episodes.orEmpty()
    var rangeIndex by remember(host.movieDetail?.movie?.id, host.browseLineIndex) {
        mutableIntStateOf(0)
    }
    var descending by remember(host.movieDetail?.movie?.id, host.browseLineIndex) {
        mutableStateOf(false)
    }
    var expanded by remember(host.movieDetail?.movie?.id, host.browseLineIndex) {
        mutableStateOf(startExpanded)
    }
    val rangeCount = (episodes.size + 99) / 100
    val selectedEpisodeIndex =
        if (currentLine?.code == host.lineCode) episodes.indexOfFirst {
            (host.episodeId.isNotBlank() && it.id == host.episodeId) ||
                (host.episodeId.isBlank() && it.name == host.episodeName)
        } else -1
    val currentRange = episodes.drop(rangeIndex * 100).take(100)
    val visibleEpisodes = if (descending) currentRange.reversed() else currentRange
    val gridState = rememberLazyGridState()
    var displayColumns by remember { mutableIntStateOf(4) }

    // 正常自动切集 / 手动选集后，把当前集的 100 集分段和选中项同步滚到首行。
    LaunchedEffect(selectedEpisodeIndex, host.episodeId, host.episodeName, host.lineCode) {
        if (selectedEpisodeIndex >= 0) {
            rangeIndex = selectedEpisodeIndex / 100
        }
    }
    LaunchedEffect(selectedEpisodeIndex, rangeIndex, descending, displayColumns) {
        if (selectedEpisodeIndex >= 0 && selectedEpisodeIndex / 100 == rangeIndex) {
            val localIndex = selectedEpisodeIndex - rangeIndex * 100
            val actualIndex = if (descending) currentRange.lastIndex - localIndex else localIndex
            if (actualIndex >= 0) {
                // Grid scrollToItem 会将目标集所在的网格行滚到顶部。
                gridState.scrollToItem(actualIndex)
            }
        } else if (rangeIndex >= 0) {
            gridState.scrollToItem(0)
        }
    }

    Column(modifier) {
        Column(
            Modifier.fillMaxWidth()
                .padding(start = 13.dp, end = 13.dp, top = 10.dp, bottom = 5.dp)
        ) {
            Text(
                host.movieDetail?.movie?.name.orEmpty().ifBlank { "正在播放" } +
                    " · " + host.episodeName,
                fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color.White,
                maxLines = 2, overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            host.movieDetail?.movie?.let { movie ->
                Text(
                    movie.year + " · " + movie.type + " · " + movie.dynamic,
                    color = HintPlayer, fontSize = 11.sp, maxLines = 1
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    movie.summary.ifBlank { "暂无简介" },
                    color = HintPlayer, fontSize = 11.sp, maxLines = 2, lineHeight = 16.sp,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (host.errorText.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(host.errorText, color = Color(0xFFFFB09E),
                    fontSize = 11.sp, maxLines = 3)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("全部剧集", fontSize = 16.sp,
                    fontWeight = FontWeight.Bold, color = Color.White)
                Text("  " + episodes.size + " 集", color = HintPlayer, fontSize = 11.sp)
                Spacer(Modifier.weight(1f))
                if (!startExpanded && episodes.size > 9) {
                    TextButton(onClick = { expanded = !expanded }) {
                        Icon(
                            if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                            null, Modifier.size(17.dp)
                        )
                        Text(if (expanded) "收起" else "展开", fontSize = 11.sp)
                    }
                }
                if (host.loadingList || host.changingEpisode) {
                    CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp)
                } else if (host.movieDetail == null) {
                    TextButton(onClick = host::loadEpisodeList) {
                        Text("重试", fontSize = 12.sp)
                    }
                }
            }

            // 固定在剧集网格上方，不跟随下方剧集滑动；线路只在播放器工具条切换。
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (rangeCount > 1) {
                    Row(
                        Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        repeat(rangeCount) { index ->
                            val first = index * 100 + 1
                            val last = minOf((index + 1) * 100, episodes.size)
                            FilterChip(
                                selected = rangeIndex == index,
                                onClick = {
                                    rangeIndex = index
                                    expanded = false
                                },
                                label = { Text("$first–$last", fontSize = 11.sp) }
                            )
                        }
                    }
                } else {
                    Text("选择剧集", color = HintPlayer, fontSize = 11.sp,
                        modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.width(6.dp))
                TextButton(
                    onClick = { descending = !descending },
                    modifier = Modifier.height(40.dp),
                    contentPadding = PaddingValues(horizontal = 7.dp)
                ) {
                    Icon(Icons.Outlined.SwapVert, "切换排序", Modifier.size(17.dp))
                    Text(if (descending) "倒序" else "正序", fontSize = 12.sp)
                }
            }
        }
        if (episodes.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text(
                    if (host.loadingList) "正在加载剧集…" else "暂无可播放剧集",
                    color = HintPlayer, fontSize = 12.sp
                )
            }
        } else {
            // 未展开也允许网格自身滚动：只显示三行高度，但可浏览本段全部剧集。
            // 标签及排序条留在固定的 Column 内，不参与网格滑动。
            BoxWithConstraints(
                modifier = if (expanded || startExpanded) Modifier.fillMaxWidth().weight(1f)
                else Modifier.fillMaxWidth().height(156.dp)
            ) {
                val columns = maxOf(2, (maxWidth.value / 83f).toInt())
                SideEffect { displayColumns = columns }
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    state = gridState,
                    modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                    userScrollEnabled = true,
                    contentPadding = PaddingValues(
                        bottom = maxOf(15.dp, maxHeight - 58.dp)
                    ),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(visibleEpisodes.size) { index ->
                        val episode = visibleEpisodes[index]
                        val selected = currentLine?.code == host.lineCode &&
                            ((episode.id.isNotBlank() && episode.id == host.episodeId) ||
                                (episode.id.isBlank() && episode.name == host.episodeName))
                        val watched = history.firstOrNull {
                            it.lineCode == episode.fromCode &&
                                (episode.id.isNotBlank() && it.episodeId == episode.id ||
                                    episode.id.isBlank() && it.episodeName == episode.name)
                        }
                        val marker = when {
                            selected -> "正在播放"
                            mostRecent != null && mostRecent.lineCode == episode.fromCode &&
                                mostRecent.episodeId == episode.id && watched != null -> "上次看到"
                            watched?.watched == true -> "已看过"
                            watched != null && watched.positionMs > 0L -> "看过一部分"
                            else -> ""
                        }
                        Surface(
                            onClick = {
                                host.choose(episode)
                                onEpisodeSelected()
                            },
                            enabled = !host.changingEpisode,
                            shape = RoundedCornerShape(11.dp),
                            color = if (selected) AccentPlayer else SoftPlayer,
                            contentColor = if (selected) InkPlayer else Color.White
                        ) {
                            Column(
                                Modifier.fillMaxWidth().height(49.dp).padding(horizontal = 3.dp),
                                verticalArrangement = Arrangement.Center,
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    episode.name.ifBlank {
                                        "第 " + (rangeIndex * 100 + index + 1) + " 集"
                                    },
                                    fontSize = 11.sp,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                                if (marker.isNotBlank()) {
                                    Text(marker, fontSize = 9.sp,
                                        color = if (selected) InkPlayer else AccentPlayer,
                                        maxLines = 1)
                                }
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
