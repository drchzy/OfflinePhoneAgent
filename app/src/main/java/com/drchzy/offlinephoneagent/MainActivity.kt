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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
        loggedIn = CodexAuthManager(this).currentTokens() != null
        setContent { OfflineApplication(this) }
        if (loggedIn) refreshCodex()
        AppUpdater.checkForUpdate(this, silent = true)
    }

    override fun onResume() {
        super.onResume()
        loggedIn = CodexAuthManager(this).currentTokens() != null
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
                    startActivity(Intent(this, MoviePlayerActivity::class.java).apply {
                        putExtra(MoviePlayerActivity.EXTRA_URL, address.url)
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
        background = Night, surface = NightCard,
        onBackground = Paper, onSurface = Paper,
        surfaceVariant = Color(0xFF292B2C), onSurfaceVariant = Muted
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
        val background = MaterialTheme.colorScheme.background
        val foreground = MaterialTheme.colorScheme.onBackground

        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                ModalDrawerSheet(
                    modifier = Modifier.width(294.dp),
                    drawerContainerColor = MaterialTheme.colorScheme.surface,
                    drawerShape = RoundedCornerShape(topEnd = 28.dp, bottomEnd = 28.dp)
                ) {
                    Column(Modifier.padding(horizontal = 24.dp, vertical = 30.dp)) {
                        Text("OFFLINE", fontSize = 27.sp, fontWeight = FontWeight.Black, letterSpacing = 4.sp)
                        Spacer(Modifier.height(6.dp))
                        Text("YOUR PERSONAL SPACE", fontSize = 10.sp, letterSpacing = 2.sp, color = Muted)
                        Spacer(Modifier.height(38.dp))
                        NavigationDrawerItem(
                            icon = { Icon(Icons.Outlined.AutoAwesome, null) },
                            label = { Text("Codex", fontWeight = FontWeight.SemiBold) },
                            selected = page == "Codex",
                            onClick = { page = "Codex"; scope.launch { drawerState.close() } },
                            shape = RoundedCornerShape(16.dp)
                        )
                        Spacer(Modifier.height(10.dp))
                        NavigationDrawerItem(
                            icon = { Icon(Icons.Outlined.Movie, null) },
                            label = { Text("Movie", fontWeight = FontWeight.SemiBold) },
                            selected = page == "Movie",
                            onClick = { page = "Movie"; scope.launch { drawerState.close() } },
                            shape = RoundedCornerShape(16.dp)
                        )
                        Spacer(Modifier.weight(1f))
                        HorizontalDivider(color = foreground.copy(alpha = 0.09f))
                        Spacer(Modifier.height(20.dp))
                        Text("OfflinePhoneAgent", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Text("A quiet place for powerful tools.", fontSize = 11.sp, color = Muted)
                    }
                }
            }
        ) {
            Scaffold(
                containerColor = background,
                topBar = {
                    Row(
                        Modifier.fillMaxWidth().height(76.dp).padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Outlined.Menu, "打开侧边导航")
                        }
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text("WORKSPACE", fontSize = 10.sp, color = Muted, letterSpacing = 2.sp)
                            Text(page, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                        }
                        IconButton(onClick = { showSettings = true }) {
                            Icon(Icons.Outlined.Tune, "设置")
                        }
                    }
                }
            ) { padding ->
                Crossfade(targetState = page, animationSpec = tween(220), label = "navigation") { destination ->
                    Box(Modifier.padding(padding).fillMaxSize()) {
                        if (destination == "Codex") {
                            CodexPage(host)
                        } else {
                            MoviePage(host)
                            MovieHttpFloatingPanel()
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
        Text(title, fontSize = 32.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp)
        Spacer(Modifier.height(5.dp))
        Text(subtitle, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CodexPage(host: MainActivity) {
    val textColor = MaterialTheme.colorScheme.onSurface
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(start = 23.dp, end = 23.dp, top = 22.dp, bottom = 35.dp)
    ) {
        SectionTitle("YOUR AI CONSOLE", "Codex Overview", "让每一次创作都尽在掌握。")
        Spacer(Modifier.height(30.dp))
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
                    Text(if (host.loggedIn) "账户已连接" else "尚未连接账户", fontWeight = FontWeight.SemiBold)
                    Text(host.codexStatus, fontSize = 12.sp, color = Muted, maxLines = 3)
                }
                if (host.loggedIn) {
                    Icon(Icons.Outlined.CheckCircle, null, tint = Champagne, modifier = Modifier.size(20.dp))
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
        Text("使用额度", fontSize = 19.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            UsageCard("5 小时", host.usage?.fiveHour, Modifier.weight(1f))
            UsageCard("每周", host.usage?.weekly, Modifier.weight(1f))
        }
        Spacer(Modifier.height(28.dp))
        Text("快捷操作", fontSize = 19.sp, fontWeight = FontWeight.Bold)
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
            "已保留设备授权、加密 Token 存储、桌面小组件及应用内升级。",
            fontSize = 11.sp, color = Muted, lineHeight = 18.sp
        )
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
    var query by rememberSaveable { mutableStateOf("") }
    var showDiagnostics by remember { mutableStateOf(false) }
    if (showDiagnostics) {
        MovieDiagnosticsDialog(host, onDismiss = { showDiagnostics = false })
    }
    LaunchedEffect(Unit) {
        if (host.movies.isEmpty() && !host.movieLoading) host.loadMovies()
    }
    val selected = host.detail
    if (selected != null) {
        DetailPage(selected, host::closeDetail, { episode -> host.play(episode, selected.movie.name) }, host.movieLoading)
        return
    }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 23.dp).padding(top = 20.dp)) {
            SectionTitle("DISCOVER & WATCH", "Movie Library", "发现值得细细品味的光影。")
            Spacer(Modifier.height(24.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(18.dp),
                placeholder = { Text("搜索电影、剧集、演员…", color = Muted) },
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                trailingIcon = {
                    IconButton(onClick = { host.loadMovies(query.trim()) }, enabled = !host.movieLoading) {
                        Icon(Icons.Outlined.ArrowForward, "执行搜索")
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { host.loadMovies(query.trim()) })
            )
            Spacer(Modifier.height(24.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (query.isEmpty()) "为你精选" else "搜索结果", fontSize = 19.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { showDiagnostics = true }) {
                    Icon(Icons.Outlined.BugReport, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(3.dp))
                    Text("接口测试", fontSize = 12.sp)
                }
                TextButton(onClick = { host.loadMovies(query.trim()) }, enabled = !host.movieLoading) {
                    Icon(Icons.Outlined.Refresh, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("刷新")
                }
            }
            Text(host.movieMessage, fontSize = 12.sp, color = Muted, maxLines = 3)
            Spacer(Modifier.height(12.dp))
        }
        if (host.movieLoading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 23.dp))
        if (host.movies.isEmpty() && !host.movieLoading) {
            Column(
                Modifier.fillMaxWidth().weight(1f).padding(28.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(Icons.Outlined.MovieFilter, null, Modifier.size(44.dp), tint = Muted)
                Spacer(Modifier.height(15.dp))
                Text("影片列表尚未就绪", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Text("请检查 Movie 服务配置或尝试搜索", color = Muted, fontSize = 12.sp)
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 23.dp, end = 23.dp, bottom = 28.dp, top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp)
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
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(item.type.ifBlank { item.dynamic.ifBlank { "立即查看" } },
            fontSize = 11.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun DetailPage(detail: MovieDetail, onBack: () -> Unit, onPlay: (MovieEpisode) -> Unit, busy: Boolean) {
    var lineIndex by remember(detail.movie.id) { mutableIntStateOf(0) }
    val line = detail.lines.getOrNull(lineIndex)
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
                Text(detail.movie.name, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                Text(detail.movie.year + "  ·  " + detail.movie.type, color = Muted, fontSize = 12.sp)
                Spacer(Modifier.height(20.dp))
                Text(detail.movie.dynamic, color = Champagne, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(24.dp))
        Text("影片简介", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Spacer(Modifier.height(9.dp))
        Text(detail.movie.summary.ifBlank { "暂无简介" }, color = Muted, fontSize = 13.sp, lineHeight = 21.sp)
        Spacer(Modifier.height(28.dp))
        Text("播放线路", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Spacer(Modifier.height(12.dp))
        if (detail.lines.isEmpty()) {
            Text("当前暂无可用播放线路", color = Muted, fontSize = 13.sp)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                detail.lines.take(3).forEachIndexed { index, source ->
                    FilterChip(
                        selected = lineIndex == index,
                        onClick = { lineIndex = index },
                        label = { Text(source.name, maxLines = 1) }
                    )
                }
            }
            Spacer(Modifier.height(15.dp))
            line?.episodes?.chunked(3)?.forEach { row ->
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
