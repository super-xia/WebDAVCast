package com.webdavcast.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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
            MaterialTheme(
                colorScheme = androidx.compose.material3.lightColorScheme(
                    primary = androidx.compose.ui.graphics.Color(0xFF3B5BDB),
                    onPrimary = androidx.compose.ui.graphics.Color.White,
                    primaryContainer = androidx.compose.ui.graphics.Color(0xFFE0E6FF),
                    onPrimaryContainer = androidx.compose.ui.graphics.Color(0xFF1A2B6E),
                    secondary = androidx.compose.ui.graphics.Color(0xFF5B6BAE),
                    background = androidx.compose.ui.graphics.Color(0xFFF6F7FB),
                    surface = androidx.compose.ui.graphics.Color.White,
                    surfaceVariant = androidx.compose.ui.graphics.Color(0xFFF0F2FA),
                    error = androidx.compose.ui.graphics.Color(0xFFD64545),
                )
            ) {
                WebdavCastApp()
            }
        }
    }
}

private enum class Screen { Setup, Browse, Player, History }

@Composable
fun WebdavCastApp() {
    val ctx = LocalContext.current
    // rememberSaveable: 跨 Activity 重建(如旋转/系统回收)保留状态
    var screen by rememberSaveable { mutableStateOf(Screen.Setup) }
    var serverHost by rememberSaveable { mutableStateOf("") }
    var serverPort by rememberSaveable { mutableStateOf(0) }
    var serverName by rememberSaveable { mutableStateOf("") }
    var serverPath by rememberSaveable { mutableStateOf("") }
    // 冷启动恢复上次服务器(host|port|name|basePath), 让历史记录也能直达目录
    remember {
        val ls = Prefs.getLastServer(ctx).split("|")
        if (serverHost.isBlank() && ls.size >= 4 && ls[1].toIntOrNull() != null) {
            serverHost = ls[0]; serverPort = ls[1].toInt()
            serverName = ls[2]; serverPath = ls[3]
        }
    }
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
    // 进入播放页的来源是否为历史: 从历史进入时返回直接回历史, 不回文件目录
    var fromHistory by rememberSaveable { mutableStateOf(false) }

    when (screen) {
        Screen.Setup -> SetupScreen(
            onConnected = { s ->
                serverHost = s.host
                serverPort = s.port
                serverName = s.name
                serverPath = s.basePath
                Prefs.setLastServer(ctx, s.host, s.port, s.name, s.basePath)
                path = "/"
                browseSavedIndex = 0
                screen = Screen.Browse
            },
            onOpenHistory = { screen = Screen.History }
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
                fromHistory = false
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
        Screen.History -> HistoryScreen(
            onPlay = { h ->
                // 从历史续播: 若服务器地址已变(如 a.com→b.com), 用当前服务器替换URL前缀重定位
                fromHistory = true
                videoUrl = relocateUrl(h.url, server)
                videoEntryRaw = "${h.title}|0|${videoUrl}|${h.durationMs}|"
                // 记住视频所在目录, 返回时回到该目录而不是首页
                // 服务器为空时从last_server恢复(冷启动后首次点历史, 也能知道basePath)
                val srv = server ?: run {
                    val ls = Prefs.getLastServer(ctx).split("|")
                    if (ls.size >= 4 && ls[1].toIntOrNull() != null) {
                        ServerEntry(ls[0], ls[1].toInt(), ls[2], ls[3])
                    } else null
                }
                videoUrl = relocateUrl(h.url, srv)
                val dir = if (h.dirPath.isNotEmpty()) h.dirPath else inferDirFromUrl(videoUrl, srv)
                path = dir
                screen = Screen.Player
            },
            onBack = { screen = Screen.Setup }
        )
        Screen.Player -> PlayerScreen(
            server = server, url = videoUrl, entry = videoEntry,
            currentPath = path,
            onSwitchEpisode = { e ->
                val s = server
                if (s != null) {
                    videoUrl = urlFor(s, e.href)
                    videoEntryRaw = "${e.name}|0|${e.href}|${e.size}|${e.modified}"
                }
            },
            onSystemBack = {
                // 从历史进入: 返回直接回历史页; 从目录进入: 恢复服务器后回原目录(否则回首页)
                if (fromHistory) {
                    screen = Screen.History
                } else if (server == null) {
                    val ls = Prefs.getLastServer(ctx).split("|")
                    if (ls.size >= 4 && ls[1].toIntOrNull() != null) {
                        serverHost = ls[0]; serverPort = ls[1].toInt()
                        serverName = ls[2]; serverPath = ls[3]
                        screen = Screen.Browse
                    } else screen = Screen.Setup
                } else screen = Screen.Browse
            },
        )
    }
}

@Composable
private fun SetupScreen(
    onConnected: (ServerEntry) -> Unit,
    onOpenHistory: () -> Unit = {},
) {
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

        // 播放记录管理
        Row(
            Modifier.fillMaxWidth().padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val histCount = Prefs.getHistoryForList(ctx).size
            Text(
                if (histCount > 0) "📺 已看 $histCount 个视频,点此继续看" else "📺 暂无观看记录",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f).clickable { if (histCount > 0) onOpenHistory() }
            )
            androidx.compose.material3.TextButton(
                onClick = {
                    Prefs.clearHistory(ctx)
                    android.widget.Toast.makeText(ctx, "已清除播放记录", android.widget.Toast.LENGTH_SHORT).show()
                }
            ) {
                Text("清除记录", fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun HistoryScreen(
    onPlay: (PlayHistory) -> Unit,
    onBack: () -> Unit,
) {
    val ctx = LocalContext.current
    var historyList by remember { mutableStateOf(Prefs.getHistoryForList(ctx)) }
    val scope = rememberCoroutineScope()
    // 系统返回键: 回首页(否则直接退到桌面)
    androidx.activity.compose.BackHandler(onBack = onBack)

    Column(Modifier.fillMaxSize()) {
        // 顶部栏
        Row(
            Modifier.fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("◀", Modifier.clickable(onClick = onBack).padding(10.dp), fontSize = 20.sp)
            Spacer(Modifier.width(4.dp))
            Text("播放记录", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }
        androidx.compose.material3.HorizontalDivider(Modifier.fillMaxWidth())

        if (historyList.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("暂无观看记录\n看过的视频会出现在这里,可点击继续播放",
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(Modifier.weight(1f)) {
                items(historyList) { h ->
                    Card(
                        modifier = Modifier.fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 5.dp)
                            .clickable { onPlay(h) },
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                    ) {
                        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp, horizontal = 14.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text("🎬", fontSize = 22.sp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(h.title, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Spacer(Modifier.height(3.dp))
                                if (h.durationMs > 0 && h.positionMs > 0) {
                                    Text("已看 ${formatMs(h.positionMs)} / ${formatMs(h.durationMs)} · 继续播放 ▶",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.primary)
                                } else {
                                    Text("已看完 · 从头播放", fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            Text("›", fontSize = 22.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            Button(onClick = {
                Prefs.clearHistory(ctx)
                historyList = emptyList()
                android.widget.Toast.makeText(ctx, "已清除播放记录", android.widget.Toast.LENGTH_SHORT).show()
            }, modifier = Modifier.fillMaxWidth().padding(14.dp).height(48.dp)) {
                Text("清除全部记录", fontSize = 15.sp, color = MaterialTheme.colorScheme.error)
            }
        }
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
                    Card(
                        modifier = Modifier.fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 5.dp)
                            .clickable {
                                when {
                                    e.isDir -> onOpen(relativePath(server!!, e.href))
                                    playable -> onWatch(urlFor(server!!, e.href), e)
                                    // 非视频文件: 不播放(无操作或可加预览)
                                    else -> {}
                                }
                            },
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                    ) {
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 13.dp, horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(icon, fontSize = 24.sp)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(e.name, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    if (e.isDir) "文件夹" else "$typeText · ${formatSize(e.size)}",
                                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (playable) {
                                    val hv = Prefs.getHistoryByUrl(ctx, urlFor(server!!, e.href))
                                    if (hv != null && hv.durationMs > 0 && hv.positionMs > 0) {
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            "已看 ${formatMs(hv.positionMs)} / ${formatMs(hv.durationMs)}",
                                            fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                        }
                        Text("›", fontSize = 22.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
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

/** 历史URL重定位: 服务器地址变了(a.com→b.com)时, 用当前服务器替换URL前缀。
 * 例: http://a.com:8080/dav/cc/nh.mp4 → http://b.com:8080/dav/cc/nh.mp4
 * server 为 null 时原样返回。 */
private fun relocateUrl(oldUrl: String, server: ServerEntry?): String {
    if (server == null || oldUrl.isBlank()) return oldUrl
    val scheme = if (oldUrl.startsWith("https://")) "https" else "http"
    // 取旧URL去掉 scheme://host:port 后的路径部分
    val afterScheme = oldUrl.substringAfter("://", "")
    val pathPart = afterScheme.substringAfter("/", "")
    return "$scheme://${server.host}:${server.port}/${pathPart.trimStart('/')}"
}

/** 从视频URL推断所在目录(相对路径, 去 basePath 前缀)。
 * 例: http://b.com:8080/dav/cc/nh.mp4, basePath=/dav → "/cc/"
 * 例: http://b.com:8080/nh.mp4, basePath=/dav → "/" */
private fun inferDirFromUrl(url: String, server: ServerEntry?): String {
    if (url.isBlank()) return "/"
    val pathPart = url.substringAfter("://", "").substringAfter("/", "")
    // 去掉文件名, 剩目录部分(dav/cc)
    val dir = pathPart.substringBeforeLast("/", "")
    var rel = dir.trim('/')
    // 去掉 basePath 前缀
    val bp = server?.basePath?.trim('/') ?: ""
    if (bp.isNotEmpty() && rel.startsWith(bp)) rel = rel.removePrefix(bp).trimStart('/')
    return if (rel.isEmpty()) "/" else "/$rel/"
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
    currentPath: String = "",
    onSwitchEpisode: (DavEntry) -> Unit,
    onSystemBack: () -> Unit,
) {
    val name = entry?.name ?: ""
    val videoSize = entry?.size ?: 0L
    val videoModified = entry?.modified ?: ""
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val user = Prefs.getUser(ctx)
    val pass = Prefs.getPass(ctx)
    // 全屏状态(rememberSaveable: Activity重建后状态不丢失, 保证与真实方向一致)
    var isFullscreen by rememberSaveable { mutableStateOf(false) }
    val activity = LocalContext.current as? android.app.Activity
    // 非全屏视频高度: dp单位按当前屏幕宽度算16:9, 退出全屏必与刚进去一致
    val videoHeight = (LocalConfiguration.current.screenWidthDp * 9f / 16f).dp

    /** 切换全屏: 横屏沉浸式(看电影) / 恢复竖屏 */
    fun toggleFullscreen() {
        isFullscreen = !isFullscreen
        val act = activity ?: return
        if (isFullscreen) {
            // 全屏播放保持屏幕常亮, 不自动息屏
            act.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            act.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            act.window?.decorView?.systemUiVisibility = (
                android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or
                    android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )
        } else {
            // 退出全屏恢复系统息屏
            act.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            act.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            act.window?.decorView?.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_VISIBLE
        }
    }

    // 兜底: 每次重组强制让常亮标志与全屏状态一致(防止任何路径残留标志)
    androidx.compose.runtime.SideEffect {
        val act = activity ?: return@SideEffect
        if (isFullscreen) act.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else act.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    // 离开播放页时清除常亮标志, 避免残留
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            activity?.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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
    // 滑动快进快退提示 & 倍速
    var seekHint by remember { mutableStateOf("") }
    val speeds = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
    var speed by remember { mutableStateOf(1.0f) }

    // 自绘控制条状态: 可见性 + 播放进度轮询
    var controlsVisible by remember { mutableStateOf(true) }
    var isPlaying by remember { mutableStateOf(true) }
    var positionMs by remember { mutableStateOf(0L) }
    var durationMs by remember { mutableStateOf(0L) }
    var lastControlAction by remember { mutableStateOf(System.currentTimeMillis()) }
    // 进度条拖动状态: 拖动中只更新显示, 松手才 seek(避免每帧 seek 卡顿)
    var dragging by remember { mutableStateOf(false) }
    var dragPosMs by remember { mutableStateOf(0L) }

    // ===== 选集/上下集: 当前目录下的可播放视频清单(自然序) =====
    var episodes by remember { mutableStateOf<List<DavEntry>?>(null) }
    var episodesLoading by remember { mutableStateOf(false) }
    var showEpisodes by remember { mutableStateOf(false) }
    // 当前播放文件名(用于在选集里定位); URL 可能含编码, 统一解码后再比较
    val currentName = remember(url, entry) {
        val raw = entry?.name?.takeIf { it.isNotBlank() } ?: url.substringAfterLast('/')
        try { java.net.URLDecoder.decode(raw, "UTF-8") } catch (e: Exception) { raw }
    }
    val currentIdx = remember(episodes, currentName) {
        episodes?.indexOfFirst { it.name == currentName } ?: -1
    }

    // 进入播放页即拉取当前目录的视频清单(供选集/上下集跳转)
    LaunchedEffect(server, currentPath) {
        val s = server ?: return@LaunchedEffect
        if (currentPath.isBlank()) return@LaunchedEffect
        episodesLoading = true
        episodes = null
        val client = WebDavClient("http://${s.host}:${s.port}${s.basePath}", user, pass)
        val result = client.list(currentPath)
        episodesLoading = false
        if (result.error == null) {
            episodes = result.entries
                .filter { !it.isDir && fileType(it.name).third }
                .sortedWith { x, y -> naturalCompare(x.name, y.name) }
        }
    }

    // 用 rememberUpdatedState 让监听器/回调读到最新 url/name(切集后不残留旧值)
    val latestUrl by rememberUpdatedState(url)
    val latestName by rememberUpdatedState(name)

    // 切到指定集: 先保存当前进度, 再通知父级换 URL/条目(触发 LaunchedEffect(url) 重新装载)
    fun switchToEpisode(e: DavEntry) {
        if (server == null) return
        val pos = player.currentPosition
        val dur = player.duration
        if (dur > 0 && pos > 0) {
            Prefs.saveHistory(ctx, latestUrl, latestName.ifEmpty { latestUrl.substringAfterLast('/') }, pos, dur, currentPath)
        }
        onSwitchEpisode(e)
        showEpisodes = false
        lastControlAction = System.currentTimeMillis()
    }

    fun gotoPrev() {
        val list = episodes
        if (list == null) {
            android.widget.Toast.makeText(ctx, "选集加载中…", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        if (currentIdx <= 0) {
            android.widget.Toast.makeText(ctx, "已经是第一集", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        switchToEpisode(list[currentIdx - 1])
    }

    fun gotoNext() {
        val list = episodes
        if (list == null) {
            android.widget.Toast.makeText(ctx, "选集加载中…", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        if (currentIdx < 0 || currentIdx >= list.size - 1) {
            android.widget.Toast.makeText(ctx, "已经是最后一集", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        switchToEpisode(list[currentIdx + 1])
    }

    // 进度轮询: 更新播放状态/进度/总时长(驱动控制条UI)
    LaunchedEffect(url) {
        while (true) {
            if (url.isNotEmpty()) {
                isPlaying = player.isPlaying
                positionMs = player.currentPosition
                val d = player.duration
                if (d > 0) durationMs = d
            }
            kotlinx.coroutines.delay(300)
        }
    }

    LaunchedEffect(url) {
        if (url.isNotEmpty()) {
            playError = ""
            // 不强制 MIME,让 ExoPlayer 自动识别(支持 mp4/mkv/ts 等更多格式)
            val item = MediaItem.Builder()
                .setUri(url)
                .build()
            player.setMediaItem(item)
            // 续播: 有历史进度且未播完(>10秒)则从保存位置继续
            val hv = Prefs.getHistoryByUrl(ctx, url)
            if (hv != null && hv.durationMs > 0 && hv.positionMs >= 10_000 && hv.positionMs < hv.durationMs - 5_000) {
                player.seekTo(hv.positionMs)
                android.widget.Toast.makeText(
                    ctx, "⏯ 从 ${formatMs(hv.positionMs)} 继续播放", android.widget.Toast.LENGTH_SHORT
                ).show()
            }
            player.prepare()
            player.playWhenReady = true
        }
    }

    // 进度节流自动保存(每5秒一次, 播放中才记)
    LaunchedEffect(url) {
        if (url.isEmpty()) return@LaunchedEffect
        var lastSaved = 0L
        while (true) {
            kotlinx.coroutines.delay(5000)
            val pos = player.currentPosition
            val dur = player.duration
            // 仅在真正播放(大于上次已保存)且未播完时保存, 避免暂停/缓冲时写乱
            if (dur > 0 && pos > 0 && pos > lastSaved + 3_000 && pos < dur - 1_000) {
                lastSaved = pos
                Prefs.saveHistory(ctx, url, name.ifEmpty { url.substringAfterLast('/') }, pos, dur, currentPath)
            }
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
                playError = "播放失败 [${error.errorCodeName}]: $detail\nURL: $latestUrl"
            }

            // 播放完提示
            override fun onPlaybackStateChanged(state: Int) {
                if (state == androidx.media3.common.Player.STATE_ENDED) {
                    android.widget.Toast.makeText(
                        ctx, "🎬 播放完毕:${latestName.ifEmpty { "视频" }}", android.widget.Toast.LENGTH_SHORT
                    ).show()
                    // 播完即视为进度清零(下次从头)
                    Prefs.saveHistory(ctx, latestUrl, latestName.ifEmpty { latestUrl.substringAfterLast('/') }, 0L, 0L, currentPath)
                }
            }
        })
    }

    // 锁屏/回桌面时暂停(ON_STOP: 切后台、锁屏都会触发, 视频不该继续播)
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) {
                if (player.isPlaying) player.pause()
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    // 离开播放页/退出时保存最终进度(从上次记录位置续播)
    DisposableEffect(player) {
        onDispose {
            val pos = player.currentPosition
            val dur = player.duration
            if (dur > 0 && pos > 0) {
                Prefs.saveHistory(ctx, latestUrl, latestName.ifEmpty { latestUrl.substringAfterLast('/') }, pos, dur, currentPath)
            }
            player.release()
        }
    }

    // 布局: 视频区始终同一 View(attach 复用, 不重建), 全屏时占满;
    // 非全屏 16:9 顶置, 视频高度与刚进去完全一致(从不依赖横屏残留宽度)
    Column(Modifier.fillMaxSize()) {
        if (url.isNotEmpty()) {
            Box(
                modifier = if (isFullscreen) Modifier.weight(1f).fillMaxWidth().background(androidx.compose.ui.graphics.Color.Black)
                else Modifier.fillMaxWidth().height(videoHeight).background(androidx.compose.ui.graphics.Color.Black)
            ) {
            AndroidView(
                factory = { ctx ->
                    androidx.media3.ui.PlayerView(ctx).also { v ->
                        v.player = player
                        // 自绘控制条: 禁用内置控制器(样式完全可控, 透明底板)
                        v.useController = false
                        v.setShowBuffering(androidx.media3.ui.PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                        v.resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                        v.setShutterBackgroundColor(android.graphics.Color.BLACK)
                        v.setBackgroundColor(android.graphics.Color.BLACK)
                        // 手势(View层, 兼容所有版本): 水平滑动=快进快退; 全屏时: 左双击=退5s/右双击=进5s/中双击=暂停,
                        // 左区垂直滑动=亮度, 右区垂直滑动=音量
                        val am = ctx.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
                        val act = ctx as? android.app.Activity
                        var lastTapTime = 0L
                        var lastTapX = 0f
                        var downX = 0f
                        var downY = 0f
                        var lastY = 0f
                        var accH = 0f
                        var accVol = 0f
                        var vMode = 0  // 0=未定, 1=水平(快进快退), 2=垂直(亮度音量)
                        // 单击/双击延迟判定: 单击=切换控制条; 双击(全屏)=三分区
                        val tapHandler = android.os.Handler(android.os.Looper.getMainLooper())
                        val singleTapTask = object : Runnable {
                            override fun run() {
                                controlsVisible = !controlsVisible
                                lastControlAction = System.currentTimeMillis()
                            }
                        }
                        v.setOnTouchListener { _, ev ->
                            when (ev.actionMasked) {
                                android.view.MotionEvent.ACTION_DOWN -> {
                                    downX = ev.x; downY = ev.y; lastY = ev.y
                                    lastTapX = ev.x
                                    accH = 0f; accVol = 0f; vMode = 0
                                }
                                android.view.MotionEvent.ACTION_MOVE -> {
                                    if (vMode == 0) {
                                        val dx = ev.x - downX
                                        val dy = ev.y - downY
                                        vMode = when {
                                            kotlin.math.abs(dx) > 30 && kotlin.math.abs(dx) > kotlin.math.abs(dy) -> 1
                                            isFullscreen && kotlin.math.abs(dy) > 30 && kotlin.math.abs(dy) > kotlin.math.abs(dx) -> 2
                                            else -> 0
                                        }
                                    }
                                    if (vMode == 1) {
                                        accH += ev.x - downX
                                        downX = ev.x
                                        if (kotlin.math.abs(accH) >= 120f) {
                                            val dir = if (accH < 0) -1 else 1  // 左滑=快退, 右滑=快进
                                            accH = 0f
                                            val cur = player.currentPosition
                                            val dur = player.duration
                                            val maxDur = if (dur > 0) dur else 0L
                                            val target = (cur + dir * 10_000L).coerceIn(0L, maxDur)
                                            if (dur > 0) player.seekTo(target)
                                            seekHint = "${if (dir > 0) "⏩ 快进" else "⏪ 快退"} ${formatMs(target)} / ${formatMs(maxDur)}"
                                        }
                                    } else if (vMode == 2) {
                                        // 垂直滑动: 左区=亮度, 右区=音量(上滑升, 下滑降)
                                        val dy = ev.y - lastY
                                        lastY = ev.y
                                        val w = v.width
                                        if (downX < w / 3f) {  // 左区亮度
                                            val win = act?.window ?: return@setOnTouchListener false
                                            val cur = win.attributes.screenBrightness
                                            val base = if (cur >= 0f) cur else 0.5f
                                            val next = (base - dy * 0.003f).coerceIn(0.01f, 1.0f)
                                            val attrs = win.attributes
                                            attrs.screenBrightness = next
                                            win.attributes = attrs
                                            seekHint = "☀ 亮度 ${(next * 100).toInt()}"
                                        } else if (downX > w * 2 / 3f) {  // 右区音量(一次变2格, 适中灵敏度)
                                            accVol -= dy
                                            val maxV = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
                                            if (maxV > 0 && kotlin.math.abs(accVol) >= 15f) {
                                                val curV = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
                                                val direction = if (accVol > 0) 1 else -1
                                                val nextV = (curV + direction * 2).coerceIn(0, maxV)
                                                am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, nextV, 0)
                                                accVol = 0f
                                                seekHint = "🔊 音量 ${(nextV * 100 / maxV)}"
                                            }
                                        }
                                    }
                                }
                                android.view.MotionEvent.ACTION_UP -> {
                                    // 单击/双击: 双击(全屏)三分区; 单击延迟250ms后切换控制条
                                    if (vMode == 0) {
                                        val now = System.currentTimeMillis()
                                        if (now - lastTapTime < 300 && kotlin.math.abs(ev.x - lastTapX) < 100f) {
                                            // 双击到达: 取消待执行的单击, 执行三分区
                                            tapHandler.removeCallbacks(singleTapTask)
                                            lastTapTime = 0L
                                            if (isFullscreen) {
                                                val w = v.width
                                                val third = w / 3f
                                                val cur = player.currentPosition
                                                val dur = player.duration
                                                val maxDur = if (dur > 0) dur else 0L
                                                when {
                                                    ev.x < third -> {
                                                        val t = (cur - 5_000L).coerceAtLeast(0L)
                                                        player.seekTo(t)
                                                        seekHint = "⏪ 5秒 ${formatMs(t)} / ${formatMs(maxDur)}"
                                                    }
                                                    ev.x > third * 2 -> {
                                                        val t = (cur + 5_000L).coerceAtMost(maxDur)
                                                        player.seekTo(t)
                                                        seekHint = "⏩ 5秒 ${formatMs(t)} / ${formatMs(maxDur)}"
                                                    }
                                                    else -> {
                                                        player.playWhenReady = !player.playWhenReady
                                                        seekHint = if (player.playWhenReady) "▶ 播放" else "⏸ 暂停"
                                                    }
                                                }
                                            }
                                        } else {
                                            // 可能是单击: 记录时间, 250ms后若无第二次点击则切换控制条
                                            lastTapTime = now
                                            lastTapX = ev.x
                                            tapHandler.removeCallbacks(singleTapTask)
                                            tapHandler.postDelayed(singleTapTask, 250)
                                        }
                                    } else {
                                        // 滑动结束: 取消待执行单击
                                        tapHandler.removeCallbacks(singleTapTask)
                                    }
                                    vMode = 0
                                }
                            }
                            true  // 消费触摸: useController=false 后 PlayerView 不消费 DOWN, 返回false会导致后续 MOVE/UP 不再派发, 手势失效; 自绘控制条不需要内置控制器
                        }

                    }
                },
                update = { v ->
                    v.player = player
                    v.resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                    v.setShutterBackgroundColor(android.graphics.Color.BLACK)
                    v.setBackgroundColor(android.graphics.Color.BLACK)
                },
                modifier = Modifier.matchParentSize().background(androidx.compose.ui.graphics.Color.Black)
            )

            // 滑动快进快退提示: 覆盖在视频正中央, 沉浸式白字粗体黑阴影(与控制条同风格)
            if (seekHint.isNotEmpty()) {
                Text(
                    seekHint,
                    fontSize = 22.sp, fontWeight = FontWeight.Black,
                    color = androidx.compose.ui.graphics.Color.White,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    // 纯透明沉浸: 无背景块无阴影, 白字直接浮在画面上
                    modifier = Modifier.align(Alignment.Center)
                )
            }
            // 自动消失
            LaunchedEffect(seekHint) {
                if (seekHint.isNotEmpty()) {
                    kotlinx.coroutines.delay(1800)
                    seekHint = ""
                }
            }

            // 控制条自动隐藏: 交互后3秒收起(基于最后操作时间, 不被进度轮询干扰)
            LaunchedEffect(controlsVisible) {
                while (controlsVisible) {
                    kotlinx.coroutines.delay(1000)
                    if (System.currentTimeMillis() - lastControlAction > 3000) {
                        controlsVisible = false
                    }
                }
            }

            // 全屏且控件隐藏时, 右上角显示系统时间 HH:mm(秒级刷新)
            var clockNow by remember { mutableStateOf("") }
            LaunchedEffect(isFullscreen, controlsVisible) {
                if (!isFullscreen || controlsVisible) { clockNow = ""; return@LaunchedEffect }
                while (true) {
                    clockNow = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                        .format(java.util.Date())
                    kotlinx.coroutines.delay(1000)
                }
            }

            // ===== 全屏顶栏: 文件名 + 选集(半透明黑底) =====
            if (controlsVisible && isFullscreen) {
                Row(
                    Modifier.align(Alignment.TopStart).fillMaxWidth()
                        .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.5f))
                        .clickable(
                            interactionSource = androidx.compose.foundation.interaction.MutableInteractionSource(),
                            indication = null
                        ) { /* 吃掉点击 */ }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        name.ifEmpty { "视频" },
                        fontSize = 15.sp, color = androidx.compose.ui.graphics.Color.White,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        "选集 ▾",
                        fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                        color = androidx.compose.ui.graphics.Color.White,
                        modifier = Modifier.clickable {
                            lastControlAction = System.currentTimeMillis()
                            showEpisodes = true
                        }.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }

            // 控件隐藏时右上角时钟(仅全屏, 无底板透明)
            if (isFullscreen && !controlsVisible) {
                Text(
                    clockNow,
                    fontSize = 13.sp, color = androidx.compose.ui.graphics.Color.White,
                    modifier = Modifier.align(Alignment.TopEnd)
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                )
            }

            // ===== 自绘控制条(透明底板) =====
            if (controlsVisible) {
                Column(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.4f))
                        .clickable(
                            interactionSource = androidx.compose.foundation.interaction.MutableInteractionSource(),
                            indication = null
                        ) { /* 控制条区域点击吃掉事件, 不触发视频点击 */ }
                ) {
                    // 按钮行: 上一集 | 快退5s | 播放/暂停 | 快进5s | 下一集 | 倍速
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Spacer(Modifier.weight(0.3f))
                        // 上一集
                        Icon(CtrlIcons.SkipPrevious, null, tint = Color.White,
                            modifier = Modifier.padding(8.dp).clickable {
                                lastControlAction = System.currentTimeMillis()
                                gotoPrev()
                            }.size(22.dp))
                        Spacer(Modifier.weight(1f))
                        // 快退5s
                        Icon(CtrlIcons.FastRewind, null, tint = Color.White,
                            modifier = Modifier.padding(8.dp).clickable {
                                lastControlAction = System.currentTimeMillis()
                            player.seekTo((player.currentPosition - 5_000).coerceAtLeast(0))
                            }.size(26.dp))
                        Spacer(Modifier.weight(1f))
                        // 播放/暂停(居中大)
                        Icon(
                            if (isPlaying) CtrlIcons.Pause else CtrlIcons.PlayArrow, null,
                            tint = Color.White,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp).clickable {
                                lastControlAction = System.currentTimeMillis()
                            if (player.isPlaying) player.pause() else player.play()
                                isPlaying = player.isPlaying
                            }.size(40.dp)
                        )
                        Spacer(Modifier.weight(1f))
                        // 快进5s
                        Icon(CtrlIcons.FastForward, null, tint = Color.White,
                            modifier = Modifier.padding(8.dp).clickable {
                                lastControlAction = System.currentTimeMillis()
                            val d = player.duration
                                player.seekTo((player.currentPosition + 5_000).coerceAtMost(if (d > 0) d else Long.MAX_VALUE))
                            }.size(26.dp))
                        Spacer(Modifier.weight(1f))
                        // 下一集
                        Icon(CtrlIcons.SkipNext, null, tint = Color.White,
                            modifier = Modifier.padding(8.dp).clickable {
                                lastControlAction = System.currentTimeMillis()
                                gotoNext()
                            }.size(22.dp))
                        Spacer(Modifier.weight(0.3f))
                        // 倍速(最右, 点击切换档位)
                        val speedLabel = if (speed == speed.toInt().toFloat()) "${speed.toInt()}x"
                            else "${speed.toString().trimEnd('0').trimEnd('.')}x"
                        Text(
                            speedLabel,
                            fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp).clickable {
                                lastControlAction = System.currentTimeMillis()
                            val idx = (speeds.indexOfFirst { kotlin.math.abs(it - speed) < 0.01f } + 1) % speeds.size
                                speed = speeds[idx]
                                player.setPlaybackSpeed(speed)
                            }
                        )
                    }
                    // 进度条行: 当前时间 | 进度条 | 总时长 | 全屏
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 拖动中显示拖动位置, 否则显示实际进度
                        val showPos = if (dragging) dragPosMs else positionMs
                        Text(formatMs(showPos), fontSize = 11.sp,
                            color = androidx.compose.ui.graphics.Color.White)
                        // 进度条: 点击跳转 + 水平拖动seek
                        val prog = if (durationMs > 0) (showPos.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
                        Box(
                            Modifier.weight(1f).padding(horizontal = 6.dp).height(24.dp)
                                .pointerInput(url) {
                                    // 进度条拖动: 拖动中只更新显示, 松手才 seek(避免每帧 seek 卡顿)
                                    detectHorizontalDragGestures(
                                        onDragStart = { offset ->
                                            dragging = true
                                            val d = player.duration
                                            if (d > 0) {
                                                dragPosMs = (offset.x / size.width * d).toLong().coerceIn(0, d)
                                            }
                                            lastControlAction = System.currentTimeMillis()
                                        },
                                        onHorizontalDrag = { change, _ ->
                                            change.consume()
                                            val d = player.duration
                                            if (d > 0) {
                                                dragPosMs = (change.position.x / size.width * d).toLong().coerceIn(0, d)
                                            }
                                            lastControlAction = System.currentTimeMillis()
                                        },
                                        onDragEnd = {
                                            dragging = false
                                            val d = player.duration
                                            if (d > 0) {
                                                player.seekTo(dragPosMs.coerceIn(0, d))
                                            }
                                            lastControlAction = System.currentTimeMillis()
                                        },
                                        onDragCancel = {
                                            dragging = false
                                        }
                                    )
                                }
                        ) {
                            // 轨道
                            Box(Modifier.fillMaxWidth().height(3.dp).align(Alignment.CenterStart)
                                .background(androidx.compose.ui.graphics.Color.White.copy(alpha = 0.3f)))
                            // 已播
                            Box(Modifier.fillMaxWidth(prog).height(3.dp).align(Alignment.CenterStart)
                                .background(androidx.compose.ui.graphics.Color.White))
                            // 滑块(用 fillMaxWidth 比例定位)
                            Box(Modifier.fillMaxWidth(prog).height(3.dp).align(Alignment.CenterStart)
                                .background(androidx.compose.ui.graphics.Color.White))
                        }
                        Text(formatMs(durationMs), fontSize = 11.sp,
                            color = androidx.compose.ui.graphics.Color.White)
                        // 全屏(进度条右侧小按钮)
                        Icon(CtrlIcons.Fullscreen, null, tint = Color.White,
                            modifier = Modifier.padding(horizontal = 8.dp).clickable { toggleFullscreen() }.size(18.dp))
                    }
                }
            }
            } // end Box(视频+提示覆盖层)
        } // end if(url.isNotEmpty())

        // 非全屏才显示: 文件名 + 投屏区(全屏时纯视频, 退出后完整恢复)
        if (!isFullscreen) {
        Card(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("🎬", fontSize = 18.sp)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(name.ifEmpty { "视频" }, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val hv = Prefs.getHistoryByUrl(ctx, url)
                    if (hv != null && hv.durationMs > 0 && hv.positionMs > 0) {
                        Spacer(Modifier.height(3.dp))
                        Text("已看到 ${formatMs(hv.positionMs)} / ${formatMs(hv.durationMs)}",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                    }
                }
                // 选集入口: 打开当前目录视频清单
                Text(
                    "选集 ▾",
                    fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable {
                            lastControlAction = System.currentTimeMillis()
                            showEpisodes = true
                        }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        }

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
        // 选集弹窗: 列出当前目录所有可播放视频, 点击切集
        if (showEpisodes) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showEpisodes = false },
                title = { Text("选集${episodes?.let { "(${it.size} 集)" } ?: ""}") },
                text = {
                    Column(Modifier.fillMaxWidth()) {
                        when {
                            episodesLoading -> Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(20.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("加载中…")
                            }
                            episodes == null || episodes!!.isEmpty() -> Text("该目录暂无视频或未连接服务器")
                            else -> {
                                val list = episodes!!
                                val epListState = rememberLazyListState()
                                val density = LocalDensity.current
                                // 打开弹窗时滚动到当前集并居中
                                LaunchedEffect(showEpisodes, currentIdx, list) {
                                    if (currentIdx >= 0) {
                                        kotlinx.coroutines.delay(50)
                                        val viewport = epListState.layoutInfo.viewportSize.height
                                        val itemPx = with(density) { 44.dp.toPx() }.toInt()
                                        if (viewport > 0 && itemPx > 0) {
                                            epListState.scrollToItem(currentIdx, (itemPx - viewport) / 2)
                                        } else {
                                            epListState.scrollToItem(currentIdx)
                                        }
                                    }
                                }
                                LazyColumn(state = epListState, modifier = Modifier.height(360.dp)) {
                                    itemsIndexed(list) { i, e ->
                                        val isCur = i == currentIdx
                                        Row(
                                            Modifier.fillMaxWidth()
                                                .height(44.dp)
                                                .clickable { switchToEpisode(e) }
                                                .background(if (isCur) MaterialTheme.colorScheme.primaryContainer else androidx.compose.ui.graphics.Color.Transparent)
                                                .padding(vertical = 10.dp, horizontal = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                "${i + 1}.",
                                                fontSize = 14.sp,
                                                fontWeight = if (isCur) FontWeight.Bold else FontWeight.Normal,
                                                color = if (isCur) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                            )
                                            Spacer(Modifier.width(8.dp))
                                            Text(
                                                e.name, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                                fontWeight = if (isCur) FontWeight.Bold else FontWeight.Normal,
                                                color = if (isCur) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = { showEpisodes = false }) { Text("关闭") }
                },
            )
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
/** 毫秒转 mm:ss / h:mm:ss 显示。 */
/** dp 转 px。 */
private fun dp(v: Float): Int = (v * android.util.TypedValue.applyDimension(
    android.util.TypedValue.COMPLEX_UNIT_DIP, v, android.content.res.Resources.getSystem().displayMetrics)).toInt()
private fun formatMs(ms: Long): String {
    if (ms <= 0) return "0:00"
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "$h:%02d:%02d".format(m, s) else "%d:%02d".format(m, s)
}

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

/** 自然排序: 数字段按数值大小比较(第1集 < 第2集 < 第10集), 非数字段按字典序。 */
private fun naturalCompare(a: String, b: String): Int {
    var i = 0
    var j = 0
    while (i < a.length && j < b.length) {
        val ca = a[i]; val cb = b[j]
        if (ca.isDigit() && cb.isDigit()) {
            var ni = i; while (ni < a.length && a[ni].isDigit()) ni++
            var nj = j; while (nj < b.length && b[nj].isDigit()) nj++
            val na = a.substring(i, ni).trimStart('0').let { if (it.isEmpty()) "0" else it }
            val nb = b.substring(j, nj).trimStart('0').let { if (it.isEmpty()) "0" else it }
            val cmp = when {
                na.length != nb.length -> na.length - nb.length
                else -> na.compareTo(nb)
            }
            if (cmp != 0) return if (cmp > 0) 1 else -1
            i = ni; j = nj
        } else {
            val c = ca.compareTo(cb)
            if (c != 0) return c
            i++; j++
        }
    }
    return (a.length - i) - (b.length - j)
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
