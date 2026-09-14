package com.webdavcast.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import kotlinx.coroutines.launch

/** 首页:设置(B.com + 账号) → 选服务器 → 浏览目录 → 播放/投屏。全中文。 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appContext = applicationContext
        setContent {
            MaterialTheme {
                WebdavCastApp()
            }
        }
    }
}

private enum class Screen { Setup, Browse, Player }

@Composable
fun WebdavCastApp() {
    // rememberSaveable: 跨 Activity 重建(如旋转/系统回收)保留状态
    var screen by rememberSaveable { mutableStateOf(Screen.Setup) }
    var serverHost by rememberSaveable { mutableStateOf("") }
    var serverPort by rememberSaveable { mutableStateOf(0) }
    var serverName by rememberSaveable { mutableStateOf("") }
    var serverPath by rememberSaveable { mutableStateOf("") }
    var path by rememberSaveable { mutableStateOf("/") }
    var videoUrl by rememberSaveable { mutableStateOf("") }
    // DavEntry 不是 Parcelable: 保存时拆成字段字符串, 恢复时重建(进程被杀后回来不崩、文件名还在)
    var videoEntryRaw by rememberSaveable { mutableStateOf<String?>(null) }
    val videoEntry = videoEntryRaw?.let { raw ->
        val p = raw.split("|", limit = 5)
        if (p.size >= 3) DavEntry(p[0], p[1] == "1", p[2], p.getOrElse(3) { "" }.toLongOrNull() ?: 0L, p.getOrElse(4) { "" }) else null
    }
    // 从保存的字段重建 server(ServerEntry 是普通 data class, 直接存需要自定义 saver)
    val server = remember(serverHost, serverPort, serverName, serverPath) {
        if (serverHost.isNotBlank() && serverPort > 0) {
            ServerEntry(serverHost, serverPort, serverName, serverPath)
        } else null
    }
    // 浏览列表滚动状态提升到父级: 进入播放页再返回时保留滚动位置
    val browseListState = rememberLazyListState()
    // 记录进入播放页前的滚动位置(返回时恢复)
    var browseSavedIndex by rememberSaveable { mutableStateOf(0) }

    when (screen) {
        Screen.Setup -> SetupScreen(
            onConnected = { s ->
                serverHost = s.host
                serverPort = s.port
                serverName = s.name
                serverPath = s.basePath
                path = "/"
                browseSavedIndex = 0
                screen = Screen.Browse
            }
        )
        Screen.Browse -> BrowseScreen(
            server = server, path = path, listState = browseListState,
            restoreIndex = browseSavedIndex,
            onOpen = { p ->
                // 进入新目录: 从顶部开始
                browseSavedIndex = 0
                path = p
            },
            onWatch = { url, entry ->
                // 记住当前位置, 返回时恢复
                browseSavedIndex = browseListState.firstVisibleItemIndex
                videoUrl = url
                videoEntryRaw = "${entry.name}|${if (entry.isDir) 1 else 0}|${entry.href}|${entry.size}|${entry.modified}"
                screen = Screen.Player
            },
            // 返回: 不在根目录则回上级, 根目录才回首页
            onBack = {
                if (path != "/") path = parentPathOf(path)
                else screen = Screen.Setup
            },
            // 系统返回键: 与箭头一致(回上级, 根目录才退出)
            onSystemBack = {
                if (path != "/") path = parentPathOf(path)
                else screen = Screen.Setup
            },
        )
        Screen.Player -> PlayerScreen(
            server = server, url = videoUrl, entry = videoEntry,
            onBack = { screen = Screen.Browse },
            onSystemBack = { screen = Screen.Browse },
        )
    }
}

@Composable
private fun SetupScreen(onConnected: (ServerEntry) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    var bcom by remember { mutableStateOf(Prefs.getBcom(ctx)) }
    var user by remember { mutableStateOf(Prefs.getUser(ctx)) }
    var pass by remember { mutableStateOf(Prefs.getPass(ctx)) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var servers by remember { mutableStateOf<List<ServerEntry>>(emptyList()) }

    fun refresh() {
        loading = true
        error = ""
        scope.launch {
            val list = AddressResolver(ctx).fetch()
            loading = false
            if (list.isEmpty()) {
                error = "没拿到服务器列表,请检查网址或网络"
            } else {
                servers = list
                // 加载成功写缓存, 下次启动直接用缓存不自动刷新
                Prefs.setServerCache(ctx, AddressResolver.encode(list))
                // 记住上次选择的服务器,自动选中
                val last = Prefs.getLast(ctx)
                list.firstOrNull { "$it.host:${it.port}" == last }?.let {
                    onConnected(it)
                }
            }
        }
    }

    // 启动只读缓存, 不自动刷新(刷新由用户点按钮触发)
    LaunchedEffect(Unit) {
        servers = AddressResolver.decode(Prefs.getServerCache(ctx))
        if (servers.isEmpty() && bcom.isNotBlank()) {
            // 首次使用无缓存时自动拉一次
            refresh()
        }
    }

    Column(
        Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("WebDAV 视频播放 + 投屏", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text("① 填地址 → ② 选服务器 → ③ 看视频", fontSize = 14.sp)

        OutlinedTextField(
            value = bcom, onValueChange = { bcom = it },
            label = { Text("服务器清单页地址") },
            singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = user, onValueChange = { user = it },
            label = { Text("WebDAV 用户名") },
            singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = pass, onValueChange = { pass = it },
            label = { Text("WebDAV 密码") },
            singleLine = true, modifier = Modifier.fillMaxWidth()
        )

        Button(
            onClick = {
                keyboard?.hide() // 收起键盘, 避免挤压下方列表
                Prefs.setBcom(ctx, bcom)
                Prefs.setUser(ctx, user)
                Prefs.setPass(ctx, pass)
                refresh()
            },
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) {
            Text("查询可用的服务器", fontSize = 17.sp)
        }

        if (loading) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.width(22.dp).height(22.dp))
                Spacer(Modifier.width(10.dp))
                Text("正在查询…")
            }
        }
        if (error.isNotEmpty()) {
            Text(error, color = MaterialTheme.colorScheme.error)
        }

        if (servers.isNotEmpty()) {
            Text("选一个服务器:", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            LazyColumn(Modifier.weight(1f)) {
                items(servers) { s ->
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable {
                                Prefs.setLast(ctx, s.host + ":" + s.port)
                                onConnected(s)
                            }
                            .padding(vertical = 14.dp, horizontal = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("▸ ", fontSize = 20.sp)
                        Column {
                            Text(s.display, fontSize = 19.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
        Text("提示:每次打开自动刷新服务器列表,记住上次选的", fontSize = 12.sp)
    }
}

@Composable
private fun BrowseScreen(
    server: ServerEntry?,
    path: String,
    listState: androidx.compose.foundation.lazy.LazyListState,
    restoreIndex: Int,
    onOpen: (String) -> Unit,
    onWatch: (String, DavEntry) -> Unit,
    onBack: () -> Unit,
    onSystemBack: () -> Unit = onBack,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    // 拦截系统返回键: 回上级目录/根目录才回首页
    androidx.activity.compose.BackHandler(onBack = onSystemBack)
    var entries by remember { mutableStateOf<List<DavEntry>?>(null) }
    var error by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    // 请求序号: 防止快速切换目录时旧请求晚到覆盖新结果
    var reqSeq by remember { mutableStateOf(0) }
    // 从播放页返回时恢复之前的滚动位置(等数据加载完成后执行, 否则列表还没渲染滚动无效)
    LaunchedEffect(restoreIndex, entries) {
        if (restoreIndex > 0 && entries != null) {
            listState.scrollToItem(restoreIndex)
        }
    }

    fun load() {
        val s = server ?: return
        val currentPath = path
        val seq = ++reqSeq
        loading = true
        entries = null
        error = ""
        scope.launch {
            val client = WebDavClient(
                "http://${s.host}:${s.port}${s.basePath}",
                Prefs.getUser(ctx), Prefs.getPass(ctx)
            )
            val result = client.list(currentPath)
            if (seq != reqSeq) return@launch // 已被更新的请求取代
            loading = false
            if (result.error != null) {
                error = result.error
            } else if (result.entries.isEmpty()) {
                error = "目录是空的"
            } else {
                entries = result.entries
            }
        }
    }

    LaunchedEffect(path, server) { load() }

    Column(Modifier.fillMaxSize()) {
        // 顶部栏: 返回 + 路径
        Row(
            Modifier.fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("◀", Modifier.clickable(onClick = onBack).padding(10.dp), fontSize = 20.sp)
            Spacer(Modifier.width(4.dp))
            Column {
                Text("WebDAV", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    path.ifEmpty { "/" },
                    fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        androidx.compose.material3.HorizontalDivider(Modifier.fillMaxWidth())

        if (loading) {
            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        if (error.isNotEmpty()) {
            Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        }
        if (!loading && error.isEmpty()) {
            LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
                if (path != "/") item {
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable { onOpen(parentPathOf(path)) }
                            .padding(vertical = 12.dp, horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("↩", fontSize = 22.sp)
                        Spacer(Modifier.width(12.dp))
                        Text("返回上级", fontSize = 16.sp)
                    }
                }
                items(entries.orEmpty()) { e ->
                    val (icon, typeText, playable) = if (e.isDir) Triple("📁", "文件夹", false)
                    else fileType(e.name)
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable {
                                when {
                                    e.isDir -> onOpen(relativePath(server!!, e.href))
                                    playable -> onWatch(urlFor(server!!, e.href), e)
                                    // 非视频文件: 不播放(无操作或可加预览)
                                    else -> {}
                                }
                            }
                            .padding(vertical = 14.dp, horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(icon, fontSize = 24.sp)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(e.name, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                if (e.isDir) "文件夹" else "$typeText · ${formatSize(e.size)}",
                                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text("›", fontSize = 22.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

private fun urlFor(s: ServerEntry, href: String): String {
    if (href.startsWith("http")) return href
    // href 可能: 含 basePath(/dav/yd/a.mp4) 或 只有相对路径(/yd/a.mp4)
    var p = href
    if (s.basePath.isNotEmpty() && !p.startsWith(s.basePath)) {
        p = s.basePath + p
    }
    // 路径含中文/全角括号等特殊字符时做 URL 编码(保留 / 与 . )
    // 先解码再编码: 无论服务器 href 是已编码(%E7%81%AB)还是未编码(中文)都得到正确结果
    val encoded = try {
        val decoded = java.net.URLDecoder.decode(p, "UTF-8")
        val parts = decoded.split("/")
        parts.joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
    } catch (e: Exception) {
        p
    }
    return "http://${s.host}:${s.port}$encoded"
}

/** href 转内部相对路径(支持绝对URL/相对路径, 去 basePath 前缀, 避免 urlOf 重复拼接)。 */
private fun relativePath(s: ServerEntry, href: String): String {
    // 1. 提取路径部分: http://host:port/dav/电影/ -> /dav/电影/
    var p = href
    if (p.startsWith("http://") || p.startsWith("https://")) {
        p = p.substringAfter("://").substringAfter("/", "").let { "/$it" }
    }
    // 2. 去掉 basePath 前缀: /dav/电影/ -> /电影/
    if (s.basePath.isNotEmpty() && p.startsWith(s.basePath)) {
        p = p.removePrefix(s.basePath)
    }
    // 3. 保证以 / 开头并带尾斜杠
    return p.trimStart('/').let { if (it.isEmpty()) "/" else "/$it".let { q -> if (q.endsWith("/")) q else "$q/" } }
}

@Composable
private fun PlayerScreen(
    server: ServerEntry?,
    url: String,
    entry: DavEntry?,
    onBack: () -> Unit,
    onSystemBack: () -> Unit = onBack,
) {
    val name = entry?.name ?: ""
    val videoSize = entry?.size ?: 0L
    val videoModified = entry?.modified ?: ""
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val user = Prefs.getUser(ctx)
    val pass = Prefs.getPass(ctx)
    // 全屏状态
    var isFullscreen by remember { mutableStateOf(false) }
    val activity = LocalContext.current as? android.app.Activity
    // 非全屏视频高度: dp单位按当前屏幕宽度算16:9, 退出全屏必与刚进去一致
    val videoHeight = (LocalConfiguration.current.screenWidthDp * 9f / 16f).dp

    /** 切换全屏: 横屏沉浸式(看电影) / 恢复竖屏 */
    fun toggleFullscreen() {
        isFullscreen = !isFullscreen
        val act = activity ?: return
        if (isFullscreen) {
            act.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            act.window?.decorView?.systemUiVisibility = (
                android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or
                    android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )
        } else {
            act.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            act.window?.decorView?.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_VISIBLE
        }
    }

    // 拦截系统返回键: 全屏时先退全屏, 否则回文件列表
    androidx.activity.compose.BackHandler(onBack = {
        if (isFullscreen) toggleFullscreen() else onSystemBack()
    })

    val player = remember {
        // 用 OkHttpDataSource: OkHttp 自动处理中文URL编码、302重定向跟随、Basic认证头
        val okHttp = okhttp3.OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        val dsFactory = androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(okHttp)
            .setUserAgent("WebDAVCast/1.0")
            .setDefaultRequestProperties(
                mapOf("Authorization" to BasicAuth.header(user, pass))
            )
        ExoPlayer.Builder(ctx).setMediaSourceFactory(
            ProgressiveMediaSource.Factory(dsFactory)
        ).build()
    }

    var devices by remember { mutableStateOf<List<DlnaDevice>?>(null) }
    var casting by remember { mutableStateOf(false) }
    var castMsg by remember { mutableStateOf("") }
    var manualIp by remember { mutableStateOf("") }
    var showManual by remember { mutableStateOf(false) }
    var playError by remember { mutableStateOf("") }

    LaunchedEffect(url) {
        if (url.isNotEmpty()) {
            playError = ""
            // 不强制 MIME,让 ExoPlayer 自动识别(支持 mp4/mkv/ts 等更多格式)
            val item = MediaItem.Builder()
                .setUri(url)
                .build()
            player.setMediaItem(item)
            player.prepare()
            player.playWhenReady = true
        }
    }

    // 播放错误监听: 失败时把具体原因(HTTP状态码+URL)显示出来
    LaunchedEffect(player) {
        player.addListener(object : androidx.media3.common.Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                var detail = error.message ?: error.cause?.message ?: "未知原因"
                // 提取 HTTP 状态码(常见于 HttpDataSource.InvalidResponseCodeException)
                val cause = error.cause
                if (cause is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) {
                    detail = "HTTP ${cause.responseCode}: ${detail}"
                }
                playError = "播放失败 [${error.errorCodeName}]: $detail\nURL: $url"
            }
        })
    }

    DisposableEffect(Unit) {
        onDispose { player.release() }
    }

    // 布局: 视频区始终同一 View(attach 复用, 不重建), 全屏时占满;
    // 非全屏 16:9 顶置, 视频高度与刚进去完全一致(从不依赖横屏残留宽度)
    Column(Modifier.fillMaxSize()) {
        if (url.isNotEmpty()) {
            AndroidView(
                factory = { ctx ->
                    androidx.media3.ui.PlayerView(ctx).also { v ->
                        v.player = player
                        // 完整控制: 播放/暂停、进度条、前后跳、全屏
                        v.useController = true
                        v.setShowBuffering(androidx.media3.ui.PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                        // 视频按原始比例显示(不裁剪); 填充区黑色不刺眼(PlayerView+内容帧+Compose三层全黑)
                        v.resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                        v.setShutterBackgroundColor(android.graphics.Color.BLACK)
                        v.setBackgroundColor(android.graphics.Color.BLACK)
                        v.findViewById<android.view.View>(androidx.media3.ui.R.id.exo_content_frame)?.setBackgroundColor(android.graphics.Color.BLACK)
                        // 全屏按钮: 官方点击回调(控制器inflate/重建不丢) + 可见
                        v.setFullscreenButtonClickListener(
                            object : androidx.media3.ui.PlayerView.FullscreenButtonClickListener {
                                override fun onFullscreenButtonClick(isFullScreen: Boolean) {
                                    toggleFullscreen()
                                }
                            }
                        )
                        // 按钮可见(默认主题会隐藏它): 控制器inflate后补挂
                        v.post {
                            v.findViewById<android.widget.ImageButton>(androidx.media3.ui.R.id.exo_fullscreen)?.apply {
                                visibility = android.view.View.VISIBLE
                                setOnClickListener { toggleFullscreen() }
                            }
                            Unit
                        }
                    }
                },
                update = { v ->
                    v.player = player
                    v.resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                    v.setShutterBackgroundColor(android.graphics.Color.BLACK)
                    v.setBackgroundColor(android.graphics.Color.BLACK)
                },
                modifier = if (isFullscreen) {
                    Modifier.fillMaxWidth().weight(1f).background(androidx.compose.ui.graphics.Color.Black)
                } else {
                    // 非全屏: 16:9横屏小窗顶置(退出全屏与刚进去完全一致)
                    Modifier.fillMaxWidth().height(videoHeight).background(androidx.compose.ui.graphics.Color.Black)
                }
            )
        }

        // 非全屏才显示: 文件名 + 投屏区(全屏时纯视频, 退出后完整恢复)
        if (!isFullscreen) {
        Text(
            name.ifEmpty { "视频" },
            fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
        )

        if (playError.isNotEmpty()) {
            Text(
                playError,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                fontSize = 13.sp
            )
        }

        // 投屏区
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = {
                    devices = null
                    castMsg = ""
                    scope.launch {
                        devices = DlnaCaster().discover()
                        if (devices.orEmpty().isEmpty()) {
                            castMsg = "没发现电视,可点右侧按钮手动输入电视 IP"
                            showManual = true
                        }
                    }
                },
                modifier = Modifier.weight(1f).height(50.dp)
            ) {
                Text("📺 搜索电视", fontSize = 16.sp)
            }
            Spacer(Modifier.width(10.dp))
            Button(
                onClick = { showManual = !showManual },
                modifier = Modifier.width(100.dp).height(50.dp)
            ) {
                Text("手动", fontSize = 15.sp)
            }
        }

        // 投屏模式: 直连(退出App也能播)
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
            Text("投屏:直连模式(退出App也能播)", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        when {
            devices == null -> Unit
            devices.orEmpty().isEmpty() -> Text(
                "没找到电视。请确认电视已开机、和手机在同一 Wi-Fi",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp), fontSize = 14.sp
            )
            else -> {
                Text("找到 ${devices!!.size} 台电视,点一下投屏:", modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp), fontSize = 14.sp)
                devices.orEmpty().forEach { d ->
                    Button(
                        onClick = {
                            casting = true
                            castMsg = ""
                            scope.launch {
                                val (ok, msg) = castDirect(d, url, name, videoSize, videoModified, server)
                                casting = false
                                castMsg = msg
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .height(48.dp)
                    ) {
                        Text("📺 ${d.friendlyName}", fontSize = 15.sp)
                    }
                }
            }
        }

        if (showManual) {
            OutlinedTextField(
                value = manualIp, onValueChange = { manualIp = it },
                label = { Text("电视 IP(如 192.168.1.100 或 192.168.1.100:49152)") },
                singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
            )
            Button(
                onClick = {
                    casting = true
                    castMsg = ""
                    scope.launch {
                        val (ok, msg) = castDirectManual(manualIp, url, name, videoSize, videoModified, server)
                        casting = false
                        castMsg = msg
                    }
                },
                modifier = Modifier.fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .height(48.dp)
            ) {
                Text("投到这台电视", fontSize = 16.sp)
            }
        }

        if (casting) CircularProgressIndicator(Modifier.padding(12.dp))
        if (castMsg.isNotEmpty()) {
            Text(castMsg, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 16.dp))
        }

        Spacer(Modifier.weight(1f))
        } // end if (!isFullscreen) —— 投屏面板(文件名/搜索/手动)全在此分支内, 全屏纯视频
        if (!isFullscreen) {
            Button(onClick = onBack, Modifier.fillMaxWidth().height(52.dp)) {
                Text("← 返回", fontSize = 17.sp)
            }
        }
    }
}

/** 全局上下文(用于无 Compose 处读取 Prefs)。 */
private var appContext: android.content.Context? = null

/**
 * 直连投屏: 把账号密码内嵌进 URL (http://user:pass@host:port/dav/...),
 * 电视直接访问 WebDAV 服务器(同Wi-Fi同出口IP可通), 不依赖手机进程, 退出App也能播。
 * 注: 需要电视的播放器支持 URL 内嵌 Basic 认证(大多数智能电视支持)。
 */
private suspend fun castDirect(
    device: DlnaDevice,
    url: String,
    name: String,
    size: Long,
    modified: String,
    server: ServerEntry?,
): Pair<Boolean, String> {
    val ctx = appContext ?: return false to "上下文不可用"
    val authUrl = buildAuthUrl(url, Prefs.getUser(ctx), Prefs.getPass(ctx))
    val ok = DlnaCaster().play(device, authUrl, name, size, modified)
    return if (ok) {
        true to "已投到 ${device.friendlyName}(直连,退出App也能播)"
    } else {
        false to "投屏命令失败,请确认电视支持内嵌认证的URL"
    }
}

/** 手动直连投屏: 填电视 IP + 内嵌认证 URL。 */
private suspend fun castDirectManual(
    manualIp: String,
    url: String,
    name: String,
    size: Long,
    modified: String,
    server: ServerEntry?,
): Pair<Boolean, String> {
    val ctx = appContext ?: return false to "上下文不可用"
    val authUrl = buildAuthUrl(url, Prefs.getUser(ctx), Prefs.getPass(ctx))
    val (ok, msg) = DlnaCaster().playManual(manualIp, authUrl, name, size, modified)
    return if (ok) true to msg else false to msg
}

/** 把账号密码内嵌进 URL: http://user:pass@host:port/dav/... */
private fun buildAuthUrl(url: String, user: String, pass: String): String {
    return try {
        val encUser = java.net.URLEncoder.encode(user, "UTF-8").replace("+", "%20")
        val encPass = java.net.URLEncoder.encode(pass, "UTF-8").replace("+", "%20")
        val idx = url.indexOf("://")
        if (idx <= 0) url
        else url.substring(0, idx + 3) + encUser + ":" + encPass + "@" + url.substring(idx + 3)
    } catch (e: Exception) {
        url
    }
}

/** 文件大小格式化: 12345678 -> 11.8 MB */
private fun formatSize(bytes: Long): String {
    if (bytes <= 0) return ""
    val mb = bytes / 1024.0 / 1024.0
    return if (mb >= 1024) {
        String.format("%.1f GB", mb / 1024.0)
    } else {
        String.format("%.1f MB", mb)
    }
}

/** 计算上级路径: "/电影/" -> "/", "/电影/子目录/" -> "/电影/" */
private fun parentPathOf(path: String): String {
    val trimmed = path.trimEnd('/')
    if (trimmed.isEmpty()) return "/"
    val parent = trimmed.substringBeforeLast('/')
    return if (parent.isEmpty()) "/" else "$parent/"
}

/** 判断 newPath 是否比 oldPath 更深(进入子目录), 用于决定是否滚动回顶部。 */
private fun isDeeperPath(oldPath: String, newPath: String): Boolean {
    val o = oldPath.trimEnd('/')
    val n = newPath.trimEnd('/')
    return n.startsWith(o + "/") && n.length > o.length
}

private val VIDEO_EXTS = setOf("mp4","mkv","avi","mov","wmv","flv","webm","m4v","3gp","ts","m2ts","mts","mpg","mpeg","rmvb","rm","vob")
private val IMAGE_EXTS = setOf("jpg","jpeg","png","gif","bmp","webp","heic")
private val AUDIO_EXTS = setOf("mp3","aac","flac","wav","ogg","m4a","opus")
private val ARCHIVE_EXTS = setOf("zip","rar","7z","tar","gz","bz2","xz")

/** 按扩展名判断文件类型, 返回 [图标, 类型文字, 是否可播放]。 */
private fun fileType(name: String): Triple<String, String, Boolean> {
    val ext = name.substringAfterLast('.', "").lowercase()
    return when {
        ext in VIDEO_EXTS -> Triple("🎬", "视频", true)
        ext in IMAGE_EXTS -> Triple("🖼️", "图片", false)
        ext in AUDIO_EXTS -> Triple("🎵", "音频", false)
        ext in ARCHIVE_EXTS -> Triple("📦", "压缩包", false)
        ext.isBlank() -> Triple("📄", "文件夹?无扩展名", false)
        else -> Triple("📄", "文件 ($ext)", false)
    }
}
