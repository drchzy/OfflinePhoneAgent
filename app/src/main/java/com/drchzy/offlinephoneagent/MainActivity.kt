package com.drchzy.offlinephoneagent

import android.appwidget.AppWidgetManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.unit.IntOffset
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors

private val Ink = Color(0xFF171819)
private val Paper = Color(0xFFF7F6F3)
private val Night = Color(0xFF101112)
private val NightCard = Color(0xFF1C1E1F)
private val Champagne = Color(0xFFD9CBAF)
private val Muted = Color(0xFF979997)

class MainActivity : ComponentActivity() {
    private val executor = Executors.newFixedThreadPool(3)
    private val movieApi by lazy { MovieApi(applicationContext) }

    var loggedIn by mutableStateOf(false)
        private set
    var codexAccountName by mutableStateOf("")
        private set
    var codeInfo by mutableStateOf("")
        private set
    var codexStatus by mutableStateOf("登录后即可同步用量")
        private set
    var codexLoading by mutableStateOf(false)
        private set
    var usage by mutableStateOf<CodexUsage?>(null)
        private set
    var movies by mutableStateOf<List<MovieItem>>(emptyList())
        private set
    var movieLoading by mutableStateOf(false)
        private set
    var movieMessage by mutableStateOf("欢迎使用 Movie")
        private set
    var detail by mutableStateOf<MovieDetail?>(null)
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = AndroidColor.rgb(16, 17, 18)
        window.navigationBarColor = AndroidColor.rgb(16, 17, 18)
        val auth = CodexAuthManager(this)
        loggedIn = auth.currentTokens() != null
        codexAccountName = if (loggedIn) auth.displayName() else ""
        MovieHttpLog.initialize(this)
        setContent { OfflineApplication(this) }
        if (loggedIn) refreshCodex()
        AppUpdater.checkForUpdate(this, silent = true)
    }

    override fun onResume() {
        super.onResume()
        val auth = CodexAuthManager(this)
        loggedIn = auth.currentTokens() != null
        codexAccountName = if (loggedIn) auth.displayName() else ""
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    fun loginCodex() {
        if (codexLoading) return
        codexLoading = true
        codexStatus = "正在申请授权码…"
        executor.execute {
            try {
                val auth = CodexAuthManager(applicationContext)
                val info = auth.requestDeviceCode()
                runOnUiThread {
                    codeInfo = info.userCode
                    codexStatus = "在浏览器完成授权，授权码已复制"
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Codex device code", info.userCode))
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(info.verificationUrl)))
                }
                auth.completeDeviceLogin(info)
                runOnUiThread {
                    loggedIn = true
                    codexAccountName = auth.displayName()
                    codexLoading = false
                    codeInfo = ""
                    codexStatus = "账号已连接"
                    refreshCodex()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    codexLoading = false
                    codexStatus = "授权失败：" + (e.message ?: "请重试")
                }
            }
        }
    }

    fun refreshCodex() {
        if (codexLoading) return
        codexLoading = true
        codexStatus = "正在同步用量…"
        executor.execute {
            try {
                val fresh = CodexUsageClient(applicationContext).fetch()
                runOnUiThread {
                    usage = fresh
                    loggedIn = true
                    codexAccountName = CodexAuthManager(applicationContext).displayName()
                    codexLoading = false
                    codexStatus = "同步成功 · " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())
                    CodexUsageWidgetProvider.refreshAll(applicationContext)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    codexLoading = false
                    codexStatus = "同步失败：" + (e.message ?: "请重试")
                }
            }
        }
    }

    /** 退出仅移除本机凭据。刷新中禁用退出，防止并发刷新把 Token 重新保存。 */
    fun logoutCodex() {
        if (codexLoading || !loggedIn) return
        CodexAuthManager(applicationContext).logout()
        loggedIn = false
        codexAccountName = ""
        usage = null
        codeInfo = ""
        codexStatus = "已退出登录"
        CodexUsageWidgetProvider.refreshAll(applicationContext)
        Toast.makeText(this, "已退出 Codex（仅清除本机凭据）", Toast.LENGTH_SHORT).show()
    }

    fun pinWidget() {
        val manager = AppWidgetManager.getInstance(this)
        if (manager.isRequestPinAppWidgetSupported) {
            val component = ComponentName(this, CodexUsageWidgetProvider::class.java)
            if (manager.requestPinAppWidget(component, null, null)) return
        }
        Toast.makeText(this, "请长按桌面，在小组件列表中选择 OfflinePhoneAgent", Toast.LENGTH_LONG).show()
    }

    fun loadMovies(keyword: String = "") {
        if (movieLoading) return
        movieLoading = true
        movieMessage = if (keyword.isBlank()) "正在加载精选影片…" else "正在搜索…"
        detail = null
        executor.execute {
            try {
                val result = if (keyword.isBlank()) movieApi.recommendations() else movieApi.search(keyword)
                runOnUiThread {
                    movies = result
                    movieLoading = false
                    movieMessage = if (result.isEmpty()) "暂无结果，请检查服务器或搜索其他关键词" else "共找到 " + result.size + " 部影片"
                }
            } catch (e: Exception) {
                runOnUiThread {
                    movieLoading = false
                    movieMessage = e.message ?: "请求失败"
                }
            }
        }
    }

    fun openDetail(movie: MovieItem) {
        movieLoading = true
        movieMessage = "正在获取影片详情…"
        executor.execute {
            try {
                val result = movieApi.detail(movie.id)
                runOnUiThread {
                    detail = result
                    movieLoading = false
                }
            } catch (e: Exception) {
                runOnUiThread {
                    movieLoading = false
                    movieMessage = "加载详情失败：" + (e.message ?: "请重试")
                }
            }
        }
    }

    fun closeDetail() { detail = null }

    fun play(episode: MovieEpisode, movieName: String) {
        movieLoading = true
        movieMessage = "正在准备播放…"
        executor.execute {
            try {
                val address = movieApi.playback(episode)
                runOnUiThread {
                    movieLoading = false
                    MoviePlaybackSession.detail = detail
                    startActivity(Intent(this, MoviePlayerActivity::class.java).apply {
                        putExtra(MoviePlayerActivity.EXTRA_URL, address.url)
                        putExtra(MoviePlayerActivity.EXTRA_MOVIE_ID, detail?.movie?.id.orEmpty())
                        putExtra(MoviePlayerActivity.EXTRA_EPISODE_ID, episode.id)
                        putExtra(MoviePlayerActivity.EXTRA_LINE_CODE, episode.fromCode)
                        putExtra(MoviePlayerActivity.EXTRA_EPISODE_NAME, episode.name)
                        putExtra(MoviePlayerActivity.EXTRA_TITLE, movieName + " · " + episode.name)
                        putExtra(MoviePlayerActivity.EXTRA_HEADERS, org.json.JSONObject(address.headers).toString())
                        putExtra(MoviePlayerActivity.EXTRA_AGENT, address.userAgent)
                    })
                }
            } catch (e: Exception) {
                runOnUiThread {
                    movieLoading = false
                    movieMessage = "播放失败：" + (e.message ?: "地址无效或无播放权限")
                    Toast.makeText(this, movieMessage, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    fun getMovieSettings(): MovieSettings = movieApi.settings()
    fun saveMovieSettings(settings: MovieSettings) {
        movieApi.save(settings)
        movies = emptyList()
        detail = null
        movieMessage = "配置已保存，请刷新"
        Toast.makeText(this, "Movie 设置已保存", Toast.LENGTH_SHORT).show()
    }

    fun updateNow() { AppUpdater.checkForUpdate(this, silent = false) }
}

@Composable
private fun OfflineApplication(host: MainActivity) {
    var dark by rememberSaveable { mutableStateOf(true) }
    val scheme = if (dark) darkColorScheme(
        primary = Champagne, onPrimary = Night,
        background = Color(0xFF14101F), surface = Color(0xFF24202D),
        onBackground = Paper, onSurface = Paper,
        surfaceVariant = Color(0xFF353043), onSurfaceVariant = Muted
    ) else lightColorScheme(
        primary = Ink, onPrimary = Color.White,
        background = Paper, surface = Color.White,
        onBackground = Ink, onSurface = Ink,
        surfaceVariant = Color(0xFFECEAE6), onSurfaceVariant = Color(0xFF71726F)
    )
    MaterialTheme(colorScheme = scheme) {
        var page by rememberSaveable { mutableStateOf("Codex") }
        var showSettings by remember { mutableStateOf(false) }
        val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
        val scope = rememberCoroutineScope()
        val screenWidth = LocalConfiguration.current.screenWidthDp
        val drawerWidth = minOf(310, (screenWidth * 0.83f).toInt()).dp
        val foreground = MaterialTheme.colorScheme.onBackground
        val transition = rememberInfiniteTransition(label = "ambient-gradient")
        val glow by transition.animateFloat(
            initialValue = 0.0f, targetValue = 1.0f,
            animationSpec = infiniteRepeatable(tween(7000), RepeatMode.Reverse),
            label = "violet-glow"
        )
        val bgBrush = if (dark) Brush.linearGradient(
            listOf(
                lerp(Color(0xFF16141D), Color(0xFF221B35), glow),
                Color(0xFF171421),
                lerp(Color(0xFF22182D), Color(0xFF10151E), glow)
            )
        ) else Brush.verticalGradient(listOf(Paper, Color(0xFFF2F0F8)))
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                ModalDrawerSheet(
                    modifier = Modifier.width(drawerWidth)
                        .statusBarsPadding().navigationBarsPadding(),
                    drawerContainerColor = MaterialTheme.colorScheme.surface,
                    drawerShape = RoundedCornerShape(topEnd = 24.dp, bottomEnd = 24.dp)
                ) {
                    Column(
                        Modifier.fillMaxHeight()
                            .padding(start = 17.dp, end = 17.dp, top = 20.dp, bottom = 15.dp)
                    ) {
                        Text("OFFLINE", fontSize = 23.sp,
                            fontWeight = FontWeight.Black, letterSpacing = 3.sp)
                        Text("YOUR PERSONAL SPACE", fontSize = 10.sp,
                            letterSpacing = 2.sp, color = Muted)
                        Spacer(Modifier.height(25.dp))
                        NavigationDrawerItem(
                            icon = { Icon(Icons.Outlined.AutoAwesome, null) },
                            label = { Text("Codex") }, selected = page == "Codex",
                            onClick = { page = "Codex"; scope.launch { drawerState.close() } }
                        )
                        Spacer(Modifier.height(5.dp))
                        NavigationDrawerItem(
                            icon = { Icon(Icons.Outlined.Movie, null) },
                            label = { Text("Movie") }, selected = page == "Movie",
                            onClick = { page = "Movie"; scope.launch { drawerState.close() } }
                        )
                        Spacer(Modifier.height(5.dp))
                        val logging by MovieHttpLog.enabled.collectAsState()
                        NavigationDrawerItem(
                            icon = { Icon(Icons.Outlined.BugReport, null) },
                            label = { Text(if (logging) "网络日志 · 采集中" else "网络日志") },
                            selected = page == "日志",
                            onClick = { page = "日志"; scope.launch { drawerState.close() } }
                        )
                        Spacer(Modifier.weight(1f))
                        HorizontalDivider(color = foreground.copy(alpha = 0.10f))
                        Spacer(Modifier.height(8.dp))
                        NavigationDrawerItem(
                            icon = { Icon(Icons.Outlined.Tune, null) },
                            label = { Text("偏好设置") },
                            selected = false,
                            onClick = { showSettings = true; scope.launch { drawerState.close() } }
                        )
                        NavigationDrawerItem(
                            icon = { Icon(Icons.Outlined.SystemUpdate, null) },
                            label = { Text("检查更新") },
                            selected = false,
                            onClick = { scope.launch { drawerState.close() }; host.updateNow() }
                        )
                        Spacer(Modifier.height(9.dp))
                        Text("OfflinePhoneAgent", fontSize = 12.sp,
                            fontWeight = FontWeight.Medium)
                        Text("v" + BuildConfig.VERSION_NAME, fontSize = 10.sp, color = Muted)
                    }
                }
            }
        ) {
            Scaffold(
                containerColor = Color.Transparent,
                // 透明背景缺少自动前景色映射，指定与暗色主题匹配的文字颜色。
                contentColor = foreground,
                contentWindowInsets = WindowInsets.safeDrawing,
                topBar = {
                    Row(
                        Modifier.fillMaxWidth().statusBarsPadding()
                            .height(54.dp).padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { scope.launch { drawerState.open() } },
                            modifier = Modifier.size(44.dp)) {
                            Icon(Icons.Outlined.Menu, "打开侧栏", Modifier.size(23.dp), tint = foreground)
                        }
                        Spacer(Modifier.width(11.dp))
                        Text(page, fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                            color = foreground, modifier = Modifier.weight(1f))
                    }
                }
            ) { padding ->
                Box(Modifier.fillMaxSize().background(bgBrush)) {
                    Crossfade(
                        targetState = page, animationSpec = tween(260),
                        label = "page-navigation"
                    ) { target ->
                        Box(
                            Modifier.padding(padding).fillMaxSize()
                                .pointerInput(target, host.detail) {
                                    var offset = 0f
                                    var edge = false
                                    detectHorizontalDragGestures(
                                        onDragStart = {
                                            edge = it.x < 52.dp.toPx()
                                            offset = 0f
                                        },
                                        onHorizontalDrag = { _, amount -> offset += amount },
                                        onDragEnd = {
                                            if (edge && offset > 100.dp.toPx()) {
                                                if (target == "Movie" && host.detail != null) host.closeDetail()
                                                else if (target != "Codex") page = "Codex"
                                                else scope.launch { drawerState.open() }
                                            }
                                        }
                                    )
                                }
                        ) {
                            when (target) {
                                "Codex" -> CodexPage(host)
                                "Movie" -> MoviePage(host)
                                else -> NetworkLogPage()
                            }
                        }
                    }
                }
            }
        }
        if (showSettings) {
            SettingsPanel(
                dark = dark,
                toggleTheme = { dark = !dark },
                initial = host.getMovieSettings(),
                onSave = host::saveMovieSettings,
                onUpdate = host::updateNow,
                onDismiss = { showSettings = false }
            )
        }
    }
}

@Composable
private fun SectionTitle(eyebrow: String, title: String, subtitle: String) {
    Column {
        Text(eyebrow.uppercase(), fontSize = 10.sp, color = Muted, letterSpacing = 2.2.sp)
        Spacer(Modifier.height(7.dp))
        Text(title, fontSize = 32.sp, fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground, letterSpacing = (-1).sp)
        Spacer(Modifier.height(5.dp))
        Text(subtitle, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CodexPage(host: MainActivity) {
    val textColor = MaterialTheme.colorScheme.onSurface
    var confirmLogout by remember { mutableStateOf(false) }
    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text("退出 Codex？") },
            text = { Text("将清除本机保存的登录凭据和用量缓存。不会退出其他设备，也不会影响 Movie 配置。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmLogout = false
                    host.logoutCodex()
                }) { Text("确认退出") }
            },
            dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("取消") } }
        )
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(start = 23.dp, end = 23.dp, top = 12.dp, bottom = 35.dp)
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(24.dp),
            tonalElevation = 2.dp
        ) {
            Row(
                Modifier.fillMaxWidth().padding(21.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.size(42.dp).clip(CircleShape).background(
                        if (host.loggedIn) Champagne.copy(alpha = 0.19f)
                        else Muted.copy(alpha = 0.12f)
                    ), contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Outlined.AutoAwesome, null, tint = if (host.loggedIn) Champagne else Muted)
                }
                Spacer(Modifier.width(15.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (host.loggedIn) "账户已连接" else "尚未连接账户",
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface)
                    if (host.loggedIn && host.codexAccountName.isNotBlank()) {
                        Text(host.codexAccountName, fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(host.codexStatus, fontSize = 12.sp, color = Muted, maxLines = 3)
                }
                if (host.loggedIn) {
                    IconButton(
                        onClick = { confirmLogout = true },
                        enabled = !host.codexLoading,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(Icons.Outlined.Logout, contentDescription = "退出 Codex",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(19.dp))
                    }
                }
            }
        }
        if (host.codeInfo.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Surface(
                color = Champagne.copy(alpha = 0.12f),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(Modifier.fillMaxWidth().padding(18.dp)) {
                    Text("浏览器授权码", fontSize = 12.sp, color = Muted)
                    Text(host.codeInfo, fontSize = 25.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
                }
            }
        }
        Spacer(Modifier.height(29.dp))
        Text("使用额度", fontSize = 19.sp, fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            UsageCard("5 小时", host.usage?.fiveHour, Modifier.weight(1f))
            UsageCard("每周", host.usage?.weekly, Modifier.weight(1f))
        }
        Spacer(Modifier.height(20.dp))
        ResetCreditsSection(host.usage)
        Spacer(Modifier.height(27.dp))
        Text("快捷操作", fontSize = 19.sp, fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.height(15.dp))
        Button(
            onClick = { if (host.loggedIn) host.refreshCodex() else host.loginCodex() },
            enabled = !host.codexLoading,
            modifier = Modifier.fillMaxWidth().height(55.dp),
            shape = RoundedCornerShape(17.dp)
        ) {
            if (host.codexLoading) CircularProgressIndicator(Modifier.size(19.dp), strokeWidth = 2.dp)
            else Icon(if (host.loggedIn) Icons.Outlined.Refresh else Icons.Outlined.Login, null)
            Spacer(Modifier.width(10.dp))
            Text(if (host.loggedIn) "同步最新用量" else "连接 Codex", fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = host::pinWidget,
            modifier = Modifier.fillMaxWidth().height(55.dp),
            shape = RoundedCornerShape(17.dp)
        ) {
            Icon(Icons.Outlined.DashboardCustomize, null)
            Spacer(Modifier.width(10.dp))
            Text("添加桌面小组件", color = textColor)
        }
        Spacer(Modifier.height(25.dp))
        Text(
            "账户名称来自登录凭据；额度与重置卡来自当前账户的只读接口。",
            fontSize = 11.sp, color = Muted, lineHeight = 18.sp
        )
    }
}

@Composable
private fun ResetCreditsSection(usage: CodexUsage?) {
    val count = usage?.availableResetCount
    val cards = usage?.resetCards.orEmpty()
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Codex 重置卡", fontSize = 19.sp, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f))
            Text(
                if (count == null) "数量未知" else "可用 $count 张",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Spacer(Modifier.height(11.dp))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(Modifier.padding(17.dp)) {
                when {
                    usage == null -> Text("登录后同步用量即可查看重置卡。", color = Muted, fontSize = 12.sp)
                    count == 0 -> Text("暂无可用重置卡", color = Muted, fontSize = 12.sp)
                    cards.isEmpty() -> Text(
                        if (count == null) "接口未返回重置卡数量与到期信息"
                        else "有 $count 张可用重置卡，但接口没有返回逐张到期时间",
                        color = Muted, fontSize = 12.sp
                    )
                    else -> {
                        cards.forEachIndexed { index, card ->
                            if (index > 0) {
                                Spacer(Modifier.height(9.dp))
                                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = .10f))
                                Spacer(Modifier.height(9.dp))
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    card.title.ifBlank { "重置卡 ${index + 1}" },
                                    fontWeight = FontWeight.Medium, fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                                val expiry = card.expiresAtSeconds?.let {
                                    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                                        .format(Date(it * 1000L))
                                } ?: "未注明到期时间"
                                Text("到期：$expiry", color = Muted, fontSize = 11.sp)
                            }
                        }
                    }
                }
                val note = usage?.resetCardsMessage.orEmpty()
                if (note.isNotBlank()) {
                    Spacer(Modifier.height(9.dp))
                    Text(note, fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun UsageCard(title: String, window: UsageWindow?, modifier: Modifier = Modifier) {
    val remaining = (window?.remainingPercent?.toFloat() ?: 0f).coerceIn(0f, 100f)
    val animated by animateFloatAsState(remaining / 100f, animationSpec = tween(800), label = title)
    val onSurface = MaterialTheme.colorScheme.onSurface
    Surface(modifier = modifier, color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(25.dp)) {
        Column(Modifier.fillMaxWidth().padding(vertical = 23.dp, horizontal = 13.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, fontSize = 13.sp, color = Muted, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(20.dp))
            Box(Modifier.size(115.dp), contentAlignment = Alignment.Center) {
                val track = onSurface.copy(alpha = 0.09f)
                Canvas(Modifier.fillMaxSize()) {
                    drawArc(track, -90f, 360f, false, style = Stroke(8.dp.toPx(), cap = StrokeCap.Round))
                    drawArc(Champagne, -90f, 360f * animated, false, style = Stroke(8.dp.toPx(), cap = StrokeCap.Round))
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (window == null) "--" else remaining.toInt().toString() + "%",
                        fontWeight = FontWeight.Bold, fontSize = 27.sp, color = onSurface)
                    Text("可用额度", fontSize = 10.sp, color = Muted)
                }
            }
            Spacer(Modifier.height(20.dp))
            val reset = window?.resetAtSeconds?.takeIf { it > 0 }?.let {
                DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it * 1000))
            } ?: "等待同步"
            Text("重置 · " + reset, fontSize = 10.sp, color = Muted, maxLines = 2)
        }
    }
}

@Composable
private fun MoviePage(host: MainActivity) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("movie_search", Context.MODE_PRIVATE) }
    val history = remember {
        mutableStateListOf<String>().apply {
            addAll(prefs.getString("history", "").orEmpty()
                .split("|").filter { it.isNotBlank() }.take(15))
        }
    }
    var query by rememberSaveable { mutableStateOf("") }
    var showDiagnostics by remember { mutableStateOf(false) }
    val gridState = rememberLazyGridState()
    val compact by remember {
        derivedStateOf {
            gridState.firstVisibleItemIndex > 0 || gridState.firstVisibleItemScrollOffset > 100
        }
    }
    val searchAction = {
        val term = query.trim()
        if (term.isNotEmpty()) {
            history.remove(term)
            history.add(0, term)
            while (history.size > 15) history.removeAt(history.lastIndex)
            prefs.edit().putString("history", history.joinToString("|")).apply()
        }
        host.loadMovies(term)
    }

    if (showDiagnostics) MovieDiagnosticsDialog(host) { showDiagnostics = false }
    LaunchedEffect(Unit) {
        if (host.movies.isEmpty() && !host.movieLoading) host.loadMovies()
    }
    val selected = host.detail
    if (selected != null) {
        DetailPage(selected, host::closeDetail, { ep -> host.play(ep, selected.movie.name) },
            host.movieLoading)
        return
    }

    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 17.dp)
                .padding(top = if (compact) 4.dp else 8.dp)
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                placeholder = { Text("搜索电影、剧集、演员…", fontSize = 13.sp) },
                leadingIcon = { Icon(Icons.Outlined.Search, null, Modifier.size(19.dp)) },
                trailingIcon = {
                    IconButton(onClick = searchAction, enabled = !host.movieLoading) {
                        Icon(Icons.Outlined.ArrowForward, "搜索", Modifier.size(20.dp))
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { searchAction() }),
                textStyle = LocalTextStyle.current.copy(fontSize = 14.sp)
            )
            AnimatedVisibility(visible = !compact && history.isNotEmpty()) {
                Column {
                    Spacer(Modifier.height(5.dp))
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        Text("最近", fontSize = 11.sp, color = Muted)
                        history.take(9).forEach { term ->
                            SuggestionChip(
                                onClick = { query = term; host.loadMovies(term) },
                                label = { Text(term, fontSize = 11.sp, maxLines = 1) }
                            )
                        }
                        TextButton(onClick = {
                            history.clear()
                            prefs.edit().remove("history").apply()
                        }) { Text("清除", fontSize = 11.sp) }
                    }
                }
            }
            Spacer(Modifier.height(7.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (query.isBlank()) "为你精选" else "搜索结果",
                    fontSize = if (compact) 15.sp else 18.sp,
                    fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { showDiagnostics = true }) {
                    Icon(Icons.Outlined.BugReport, null, Modifier.size(15.dp))
                    Spacer(Modifier.width(3.dp))
                    Text("接口", fontSize = 11.sp)
                }
                TextButton(onClick = { host.loadMovies(query.trim()) },
                    enabled = !host.movieLoading) {
                    Icon(Icons.Outlined.Refresh, null, Modifier.size(16.dp))
                    Text("刷新", fontSize = 11.sp)
                }
            }
            if (host.movieMessage.isNotBlank()) {
                Text(host.movieMessage, fontSize = 11.sp, color = Muted, maxLines = 2)
            }
            Spacer(Modifier.height(7.dp))
        }
        if (host.movieLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (host.movies.isEmpty() && !host.movieLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Outlined.MovieFilter, null, Modifier.size(37.dp), tint = Muted)
                    Spacer(Modifier.height(12.dp))
                    Text("影片列表尚未就绪", fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onBackground)
                    Text("请检查连接或尝试搜索", color = Muted, fontSize = 11.sp)
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 152.dp),
                state = gridState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 17.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(13.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                items(host.movies, key = { it.id }) { movie ->
                    MovieTile(movie, onClick = { host.openDetail(movie) })
                }
            }
        }
    }
}

@Composable
private fun MovieTile(item: MovieItem, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
            Box(Modifier.fillMaxWidth().aspectRatio(0.73f)) {
                if (item.cover.isNotBlank()) {
                    AsyncImage(
                        model = item.cover, contentDescription = item.name,
                        modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop
                    )
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.Movie, null, Modifier.size(48.dp), tint = Muted)
                    }
                }
                Surface(
                    modifier = Modifier.align(Alignment.BottomStart).padding(10.dp),
                    color = Color.Black.copy(alpha = 0.68f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(item.year.ifBlank { "MOVIE" }, Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                        fontSize = 10.sp, color = Color.White)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(item.name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(item.type.ifBlank { item.dynamic.ifBlank { "立即查看" } },
            fontSize = 11.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun DetailPage(detail: MovieDetail, onBack: () -> Unit, onPlay: (MovieEpisode) -> Unit, busy: Boolean) {
    var lineIndex by remember(detail.movie.id) { mutableIntStateOf(0) }
    val line = detail.lines.getOrNull(lineIndex)
    var rangeIndex by remember(detail.movie.id, lineIndex) { mutableIntStateOf(0) }
    var episodesExpanded by remember(detail.movie.id, lineIndex, rangeIndex) { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 23.dp)) {
        TextButton(onClick = onBack) {
            Icon(Icons.Outlined.ArrowBack, null, Modifier.size(18.dp))
            Spacer(Modifier.width(7.dp))
            Text("返回影片列表")
        }
        Spacer(Modifier.height(13.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(19.dp)) {
            Surface(Modifier.width(125.dp).height(183.dp), shape = RoundedCornerShape(17.dp)) {
                if (detail.movie.cover.isNotBlank())
                    AsyncImage(model = detail.movie.cover, contentDescription = null, contentScale = ContentScale.Crop)
                else Box(contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Movie, null) }
            }
            Column(Modifier.weight(1f)) {
                Text(detail.movie.name, fontSize = 24.sp, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground)
                Spacer(Modifier.height(12.dp))
                Text(detail.movie.year + "  ·  " + detail.movie.type, color = Muted, fontSize = 12.sp)
                Spacer(Modifier.height(20.dp))
                Text(detail.movie.dynamic, color = Champagne, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(24.dp))
        Text("影片简介", fontWeight = FontWeight.Bold, fontSize = 18.sp,
            color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.height(9.dp))
        Text(detail.movie.summary.ifBlank { "暂无简介" }, color = Muted, fontSize = 13.sp, lineHeight = 21.sp)
        Spacer(Modifier.height(28.dp))
        Text("播放线路", fontWeight = FontWeight.Bold, fontSize = 18.sp,
            color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.height(12.dp))
        if (detail.lines.isEmpty()) {
            Text("当前暂无可用播放线路", color = Muted, fontSize = 13.sp)
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                detail.lines.forEachIndexed { index, source ->
                    FilterChip(
                        selected = lineIndex == index,
                        onClick = { lineIndex = index },
                        label = { Text(source.name, maxLines = 1) }
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            val allEpisodes = line?.episodes.orEmpty()
            val rangeCount = (allEpisodes.size + 99) / 100
            if (rangeCount > 1) {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    repeat(rangeCount) { index ->
                        val first = index * 100 + 1
                        val last = minOf((index + 1) * 100, allEpisodes.size)
                        FilterChip(
                            selected = rangeIndex == index,
                            onClick = { rangeIndex = index },
                            label = { Text("$first–$last", fontSize = 12.sp) }
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
            }
            val rangeEpisodes = allEpisodes.drop(rangeIndex * 100).take(100)
            val allRows = rangeEpisodes.chunked(3)
            (if (episodesExpanded) allRows else allRows.take(3)).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    row.forEach { episode ->
                        Surface(
                            modifier = Modifier.weight(1f).padding(bottom = 8.dp),
                            shape = RoundedCornerShape(13.dp),
                            color = MaterialTheme.colorScheme.surface,
                            onClick = { if (!busy) onPlay(episode) }
                        ) {
                            Text(episode.name, Modifier.padding(vertical = 16.dp, horizontal = 8.dp),
                                fontSize = 12.sp, maxLines = 1, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                overflow = TextOverflow.Ellipsis)
                        }
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            if (allRows.size > 3) {
                TextButton(
                    onClick = { episodesExpanded = !episodesExpanded },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        if (episodesExpanded) Icons.Outlined.ExpandLess
                        else Icons.Outlined.ExpandMore,
                        contentDescription = null, modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(if (episodesExpanded) "收起当前分段" else "展开本段 · 共 ${rangeEpisodes.size} 集")
                }
            }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 10.dp))
        Spacer(Modifier.height(36.dp))
    }
}

@Composable
private fun SettingsPanel(
    dark: Boolean,
    toggleTheme: () -> Unit,
    initial: MovieSettings,
    onSave: (MovieSettings) -> Unit,
    onUpdate: () -> Unit,
    onDismiss: () -> Unit
) {
    var apiUrl by remember { mutableStateOf(initial.baseUrl) }
    var configUrl by remember { mutableStateOf(initial.configUrl) }
    var key by remember { mutableStateOf(initial.aesKey) }
    var iv by remember { mutableStateOf(initial.aesIv) }
    var hmac by remember { mutableStateOf(initial.hmacKey) }
    var rsaPublicKey by remember { mutableStateOf(initial.rsaPublicKey) }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(25.dp),
        title = { Text("偏好设置", fontSize = 23.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("深色模式", Modifier.weight(1f), fontSize = 14.sp)
                    Switch(checked = dark, onCheckedChange = { toggleTheme() })
                }
                Spacer(Modifier.height(9.dp))
                OutlinedButton(onClick = onUpdate, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.SystemUpdate, null, Modifier.size(17.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("检查应用更新")
                }
                Spacer(Modifier.height(23.dp))
                Text("MOVIE · API 连接", fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp, color = Muted)
                Spacer(Modifier.height(10.dp))
                Text("留空 API 地址则从动态配置获取。签名参数仅保存在本机，不会提交到 GitHub。",
                    fontSize = 11.sp, color = Muted, lineHeight = 17.sp)
                Spacer(Modifier.height(11.dp))
                listOf("API BaseURL" to apiUrl, "配置 JSON 地址" to configUrl).forEachIndexed { idx, pair ->
                    OutlinedTextField(
                        value = pair.second,
                        onValueChange = { if (idx == 0) apiUrl = it else configUrl = it },
                        label = { Text(pair.first) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        textStyle = LocalTextStyle.current.copy(fontSize = 12.sp)
                    )
                    Spacer(Modifier.height(8.dp))
                }
                OutlinedTextField(
                    value = key, onValueChange = { key = it },
                    label = { Text("AES Key（16 字节）") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = iv, onValueChange = { iv = it },
                    label = { Text("AES IV（16 字节）") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = hmac, onValueChange = { hmac = it },
                    label = { Text("HMAC Key") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "业务请求使用 RSA/PKCS1Padding；上方 AES Key/IV 仅供需要 AES 的响应解密使用。",
                    fontSize = 11.sp, color = Muted, lineHeight = 17.sp
                )
                OutlinedTextField(
                    value = rsaPublicKey,
                    onValueChange = { rsaPublicKey = it },
                    label = { Text("RSA 公钥（完整 X.509 Base64）") },
                    placeholder = { Text("粘贴完整公钥，不要包含 ...") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 5,
                    textStyle = LocalTextStyle.current.copy(fontSize = 12.sp)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(MovieSettings(apiUrl, configUrl, key, iv, hmac, rsaPublicKey))
                onDismiss()
            }) { Text("保存设置") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
