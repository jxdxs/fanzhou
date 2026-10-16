package com.kevin.fanzhou.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.kevin.fanzhou.R
import com.kevin.fanzhou.Routes
import com.kevin.fanzhou.cloud.CloudDrive
import com.kevin.fanzhou.cloud.CloudFile
import com.kevin.fanzhou.cloud.VideoThumbnails
import com.kevin.fanzhou.data.CloudFav
import com.kevin.fanzhou.data.CloudRecent
import com.kevin.fanzhou.data.FolderStatCache
import com.kevin.fanzhou.data.PlayQueue
import com.kevin.fanzhou.data.Repo
import com.kevin.fanzhou.data.Tmdb
import com.kevin.fanzhou.data.WebDavClient
import com.kevin.fanzhou.player.PlayerCore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val VIEW_LIST = "list"
private const val VIEW_GRID = "grid"
private const val VIEW_POSTER = "poster"

private val FolderColor = Color(0xFFF2B33D)
private val VideoColor = Color(0xFF4F8CFF)
private val SubColor = Color(0xFF3AA757)
private val OtherColor = Color(0xFF6B7280)

private fun posKey(sid: String, id: String) = "cloud:$sid:$id"

private val SYSTEM_ZONE = java.time.ZoneId.systemDefault()
private val CLOUD_DATE_CURRENT_YEAR = java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm")
private val CLOUD_DATE_OTHER_YEAR = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd")
private val FULL_DATE_FORMATTER = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

fun cloudDateText(ms: Long): String {
    if (ms <= 0) return ""
    val ldt = java.time.Instant.ofEpochMilli(ms).atZone(SYSTEM_ZONE).toLocalDateTime()
    val thisYear = java.time.Year.now(SYSTEM_ZONE).value
    val fmt = if (ldt.year == thisYear) CLOUD_DATE_CURRENT_YEAR else CLOUD_DATE_OTHER_YEAR
    return ldt.format(fmt)
}

private fun fullDateText(ms: Long): String =
    if (ms <= 0) "未知" else java.time.Instant.ofEpochMilli(ms).atZone(SYSTEM_ZONE).format(FULL_DATE_FORMATTER)

fun folderDesc(f: CloudFile, sid: String? = null): String {
    if (!f.isDir) return ""
    val stat = if (sid != null) FolderStatCache.get(sid, f.id) else null
    if (stat != null) {
        return when {
            stat.videoCount > 0 && stat.videoCount != stat.total -> "${stat.videoCount} 个视频 · 共 ${stat.total} 项"
            stat.videoCount > 0 && stat.videoCount == stat.total -> "${stat.total} 个视频"
            stat.total == 0 -> "空文件夹"
            else -> "共 ${stat.total} 项"
        }
    }
    if (f.childCount != null && f.childCount >= 0) {
        return if (f.childCount == 0) "空文件夹" else "${f.childCount} 项"
    }
    if (f.size > 0) {
        return "文件夹 · ${sizeText(f.size)}"
    }
    return "文件夹"
}

private fun metaLine(f: CloudFile, sid: String? = null): String = listOf(
    if (f.isDir) folderDesc(f, sid) else sizeText(f.size),
    if (f.duration > 0) fmtMs(f.duration) else "",
    cloudDateText(f.modified),
).filter { it.isNotEmpty() }.joinToString(" · ")

/** Thumbnail with provider headers; falls back to a colored type icon underneath. */
@Composable
fun CloudThumb(
    f: CloudFile,
    headers: Map<String, String>,
    modifier: Modifier,
    iconSize: Int = 28,
    sid: String? = null,
) {
    val (icon, color) = when {
        f.isDir -> R.drawable.ic_folder to FolderColor
        f.isVideo -> R.drawable.ic_movie to VideoColor
        f.isSubtitle -> R.drawable.ic_subtitle to SubColor
        else -> R.drawable.ic_file to OtherColor
    }
    val ctx = LocalContext.current
    var localThumb by remember(sid, f.id) {
        mutableStateOf(if (sid != null && f.isVideo && f.thumb == null) VideoThumbnails.getCached(ctx, sid, f.id) else null)
    }
    if (sid != null && f.isVideo && f.thumb == null && localThumb == null) {
        LaunchedEffect(sid, f.id) {
            val extracted = VideoThumbnails.loadOrExtract(ctx, sid, f)
            if (extracted != null) localThumb = extracted
        }
    }

    val model: Any? = f.thumb ?: localThumb
    Box(modifier.background(color.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
        Icon(painterResource(icon), null, tint = color, modifier = Modifier.size(iconSize.dp))
        if (model != null) {
            val req = remember(model) {
                ImageRequest.Builder(ctx).data(model).crossfade(true).apply {
                    if (model == f.thumb) {
                        headers.forEach { (k, v) -> if (v.isNotEmpty()) addHeader(k, v) }
                    }
                }.build()
            }
            AsyncImage(model = req, contentDescription = f.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun DurationTag(text: String, modifier: Modifier) {
    Box(
        modifier.padding(4.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xAA000000)).padding(horizontal = 4.dp, vertical = 1.dp)
    ) { Text(text, fontSize = 10.sp, color = Color.White) }
}

@Composable
private fun ThumbWithProgress(
    f: CloudFile,
    headers: Map<String, String>,
    pos: Long,
    modifier: Modifier,
    iconSize: Int = 28,
    sid: String? = null,
) {
    val store = Repo.store
    val key = remember(sid, f.id) { if (sid != null) posKey(sid, f.id) else null }
    val progress = remember(key, f.duration, pos) {
        if (key != null && f.isVideo) store.progress(key, f.duration) else 0f
    }
    val isFin = remember(key) {
        if (key != null && f.isVideo) store.isFinished(key) else false
    }
    val dur = if (f.duration > 0) f.duration else (if (key != null) store.duration(key) else 0L)

    Box(modifier.aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp))) {
        CloudThumb(f, headers, Modifier.fillMaxSize(), iconSize, sid = sid)
        if (dur > 0) DurationTag(fmtMs(dur), Modifier.align(Alignment.BottomEnd))
        if (isFin) CornerBadge("✓", Modifier.align(Alignment.TopEnd))
        if (progress > 0.01f) ProgressStrip(progress, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun Skeleton(grid: Boolean, modifier: Modifier) {
    val t = rememberInfiniteTransition(label = "sk")
    val a by t.animateFloat(0.35f, 0.75f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "a")
    val block = Modifier.clip(RoundedCornerShape(6.dp)).background(Surface2).alpha(a)
    Column(modifier.padding(horizontal = 16.dp, vertical = 8.dp).alpha(a)) {
        if (grid) repeat(4) {
            Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                repeat(2) {
                    Column(Modifier.weight(1f)) {
                        Box(block.fillMaxWidth().aspectRatio(16f / 9f))
                        Spacer(Modifier.height(8.dp)); Box(block.fillMaxWidth(0.8f).height(12.dp))
                        Spacer(Modifier.height(6.dp)); Box(block.fillMaxWidth(0.5f).height(10.dp))
                    }
                }
            }
        } else repeat(8) {
            Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(block.width(120.dp).aspectRatio(16f / 9f))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Box(block.fillMaxWidth(0.9f).height(13.dp)); Spacer(Modifier.height(8.dp))
                    Box(block.fillMaxWidth(0.55f).height(10.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun CloudScreen(nav: NavHostController, sid: String, dirId: String?, title: String?, path: List<String> = emptyList()) {
    val drive = remember(sid) { CloudDrive.get(sid) } ?: return ErrorBox("媒体源不存在")
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val store = Repo.store
    val dir = dirId ?: drive.rootId
    val isRoot = dirId == null
    val hasTmdb = store.tmdbKey.isNotEmpty()
    val headers = drive.thumbHeaders

    val statVer by FolderStatCache.version.collectAsState()
    var entries by remember { mutableStateOf<List<CloudFile>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var refreshing by remember { mutableStateOf(false) }
    var usage by remember { mutableStateOf<Pair<Long, Long>?>(null) }

    var view by remember { mutableStateOf(store.cloudView(sid).let { if (it == VIEW_POSTER && !hasTmdb) VIEW_LIST else it }) }
    var sortKey by remember { mutableStateOf(store.cloudSort) }
    var sortDesc by remember { mutableStateOf(store.cloudSortDesc) }
    var foldersFirst by remember { mutableStateOf(store.cloudFoldersFirst) }
    var videosOnly by remember { mutableStateOf(store.cloudVideosOnly) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var sortMenu by remember { mutableStateOf(false) }
    var sheetFile by remember { mutableStateOf<CloudFile?>(null) }
    var infoFile by remember { mutableStateOf<CloudFile?>(null) }
    var renameFile by remember { mutableStateOf<CloudFile?>(null) }
    var deleteFile by remember { mutableStateOf<CloudFile?>(null) }
    var deleteRecent by remember { mutableStateOf<CloudRecent?>(null) }
    var deleteFav by remember { mutableStateOf<CloudFav?>(null) }
    var qualityFolderTarget by remember { mutableStateOf<CloudFile?>(null) }
    val opScope = rememberCoroutineScope()
    fun fileOp(done: String, op: suspend () -> Unit) {
        opScope.launch {
            val err = try { op(); null } catch (e: CancellationException) { throw e } catch (e: Exception) { e.message ?: e.javaClass.simpleName }
            android.widget.Toast.makeText(context, err ?: done, android.widget.Toast.LENGTH_SHORT).show()
            if (err == null) { refreshing = true; reload++ }
        }
    }

    // Re-read saved positions / recent list whenever we come back from the player.
    var resumeTick by remember { mutableIntStateOf(0) }
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) resumeTick++ }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    if (isRoot) LaunchedEffect(reload) { usage = runCatching { drive.usage() }.getOrNull() ?: usage }
    LaunchedEffect(dir, reload) {
        error = null
        try { entries = drive.list(dir) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: e.javaClass.simpleName }
        refreshing = false
    }

    LaunchedEffect(error) {
        val e = error
        if (e != null && entries != null) android.widget.Toast.makeText(context, "刷新失败：$e", android.widget.Toast.LENGTH_SHORT).show()
    }

    LaunchedEffect(entries) {
        val list = entries ?: return@LaunchedEffect
        FolderStatCache.put(sid, dir, list)
        // Prefetch only the first screen (16 items) to avoid overloading network & memory.
        // Offscreen items will be lazily extracted when scrolled into view.
        val vids = list.filter { it.isVideo && it.thumb == null }.take(16)
        if (vids.isNotEmpty()) {
            vids.forEach { f ->
                launch {
                    VideoThumbnails.loadOrExtract(context, sid, f)
                }
            }
        }
    }

    val all = entries ?: emptyList()
    val shown = remember(all, sortKey, sortDesc, foldersFirst, videosOnly, query) {
        val q = query.trim()
        val base = all.filter { (!videosOnly || it.isDir || it.isVideo) && (q.isEmpty() || it.name.contains(q, ignoreCase = true)) }
        var cmp: Comparator<CloudFile> = when (sortKey) {
            "time" -> compareBy<CloudFile> { it.modified }.thenBy { WebDavClient.naturalKey(it.name) }
            "size" -> compareBy<CloudFile> { it.size }.thenBy { WebDavClient.naturalKey(it.name) }
            else -> compareBy { WebDavClient.naturalKey(it.name) }
        }
        if (sortDesc) cmp = cmp.reversed()
        if (foldersFirst) cmp = compareBy<CloudFile> { !it.isDir }.then(cmp)
        base.sortedWith(cmp)
    }
    val videos = shown.filter { it.isVideo }
    var recentTick by remember { mutableIntStateOf(0) }
    val recent = remember(sid, resumeTick, recentTick) { if (isRoot) store.cloudRecent(sid) else emptyList() }
    val crumbs = listOf(drive.source.name) + path + listOfNotNull(if (isRoot) null else (title ?: ""))
    val depth = crumbs.size - 1

    var favs by remember { mutableStateOf(store.cloudFavs(sid)) }
    LaunchedEffect(resumeTick) { favs = store.cloudFavs(sid) }
    fun toggleFav(f: CloudFile) {
        val fav = CloudFav(
            f.id, f.name, f.isDir, f.extra, f.size, f.thumb, f.duration,
            dirId = if (f.isDir) null else dir, dirName = if (f.isDir) null else title,
            path = if (f.isDir) (if (isRoot) emptyList() else path + (title ?: "")) else emptyList(),
            at = System.currentTimeMillis(),
        )
        val now = store.toggleCloudFav(sid, fav)
        favs = store.cloudFavs(sid)
        android.widget.Toast.makeText(context, if (now) "已收藏" else "已取消收藏", android.widget.Toast.LENGTH_SHORT).show()
    }
    fun removeFav(fav: CloudFav) {
        store.removeCloudFav(sid, fav.id)
        favs = store.cloudFavs(sid)
        android.widget.Toast.makeText(context, "已取消收藏", android.widget.Toast.LENGTH_SHORT).show()
    }
    fun removeRecent(r: CloudRecent) {
        store.removeCloudRecent(sid, r.id)
        recentTick++
        android.widget.Toast.makeText(context, "已删除播放记录", android.widget.Toast.LENGTH_SHORT).show()
    }
    fun addRecent(f: CloudFile) = store.addCloudRecent(
        sid, CloudRecent(f.id, f.name, f.extra, f.size, f.thumb, f.duration, dirId ?: dir, title, System.currentTimeMillis())
    )
    fun playItems(fromStart: CloudFile?) = videos.map { v ->
        drive.playItem(v, all).copy(cloudFolderKey = "$sid:$dir").let { if (v == fromStart) it.copy(startMs = 0) else it }
    }
    fun play(f: CloudFile, fromStart: Boolean = false, mpv: Boolean = false) {
        addRecent(f)
        val list = playItems(if (fromStart) f else null)
        val idx = videos.indexOf(f).coerceAtLeast(0)
        if (mpv) {
            if (!PlayerCore.mpvAvailable) {
                android.widget.Toast.makeText(context, "此设备无法加载 MPV 内核", android.widget.Toast.LENGTH_SHORT).show(); return
            }
            PlayQueue.set(list, idx)
            PlayerCore.launch(context, mpv = true, forced = true)
        } else PlayerCore.start(context, list, idx)
    }
    fun open(f: CloudFile) {
        when {
            f.isDir -> nav.navigate(Routes.cloud(sid, f.id, f.name, if (isRoot) emptyList() else path + (title ?: "")))
            f.isVideo -> play(f)
            else -> infoFile = f
        }
    }
    fun playRecent(r: CloudRecent) {
        val f = CloudFile(r.id, r.name, false, r.size, r.extra, thumb = r.thumb, duration = r.duration)
        store.addCloudRecent(sid, r.copy(at = System.currentTimeMillis()))
        val targetDir = r.dirId ?: dir
        opScope.launch {
            val sibs = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { drive.list(targetDir) }.getOrNull()
            }
            val vids = sibs?.filter { it.isVideo }
                ?.sortedWith(compareBy { WebDavClient.naturalKey(it.name) })
                .orEmpty()
            val idx = vids.indexOfFirst { it.id == r.id }
                .takeIf { it >= 0 }
                ?: vids.indexOfFirst { it.name == r.name }.takeIf { it >= 0 }
                ?: vids.indexOfFirst { it.name.substringBeforeLast('.') == r.name.substringBeforeLast('.') }.takeIf { it >= 0 }
                ?: 0
            if (vids.isNotEmpty()) {
                val items = vids.mapIndexed { i, it ->
                    drive.playItem(it, sibs ?: vids).copy(
                        cloudFolderKey = "$sid:$targetDir",
                        directResume = (i == idx)
                    )
                }
                PlayerCore.start(context, items, idx)
            } else {
                PlayerCore.start(context, listOf(drive.playItem(f, emptyList()).copy(cloudFolderKey = "$sid:$targetDir", directResume = true)), 0)
            }
        }
    }
    fun playFav(fav: CloudFav) = playRecent(
        CloudRecent(fav.id, fav.name, fav.extra, fav.size, fav.thumb, fav.duration, fav.dirId, fav.dirName, System.currentTimeMillis())
    )
    fun setView(v: String) { view = v; store.setCloudView(sid, v) }

    Scaffold(topBar = {
        Column(Modifier.background(Bg)) {
            TopAppBar(
                title = {
                    if (searching) TextField(
                        query, { query = it }, singleLine = true,
                        placeholder = { Text("在当前文件夹中搜索", fontSize = 15.sp) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                        ),
                    ) else Text(
                        title ?: drive.source.name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = if (!isRoot) Modifier.combinedClickable(
                            onClick = {},
                            onLongClick = { qualityFolderTarget = CloudFile(id = dir, name = title ?: "当前文件夹", isDir = true) }
                        ) else Modifier
                    )
                },
                navigationIcon = { BackAndHome(nav, showHome = !isRoot) },
                actions = {
                    if (searching) IconButton(onClick = { searching = false; query = "" }) { Icon(Icons.Filled.Close, "关闭搜索") }
                    else IconButton(onClick = { searching = true }) { Icon(Icons.Filled.Search, "搜索") }
                    if (!isRoot) {
                        val currentDirQ = store.qualityForFolder("$sid:$dir")
                        val hasCustomQ = !currentDirQ.isNullOrEmpty() && currentDirQ != "原画"
                        IconButton(onClick = {
                            qualityFolderTarget = CloudFile(id = dir, name = title ?: "当前文件夹", isDir = true)
                        }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_decoder),
                                contentDescription = "设置当前文件夹画质",
                                tint = if (hasCustomQ) Accent else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                    Box {
                        IconButton(onClick = { sortMenu = true }) { Icon(painterResource(R.drawable.ic_sort), "排序") }
                        DropdownMenu(sortMenu, { sortMenu = false }) {
                            Text("排序方式", fontSize = 12.sp, color = TextDim, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                            listOf("name" to "名称", "time" to "修改时间", "size" to "大小").forEach { (k, l) ->
                                MenuRow(l, sortKey == k) { sortKey = k; store.cloudSort = k }
                            }
                            HorizontalDivider()
                            MenuRow("升序", !sortDesc) { sortDesc = false; store.cloudSortDesc = false }
                            MenuRow("降序", sortDesc) { sortDesc = true; store.cloudSortDesc = true }
                            HorizontalDivider()
                            MenuRow("文件夹置顶", foldersFirst) { foldersFirst = !foldersFirst; store.cloudFoldersFirst = foldersFirst }
                            MenuRow("只显示视频", videosOnly) { videosOnly = !videosOnly; store.cloudVideosOnly = videosOnly }
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("当前文件夹信息") },
                                leadingIcon = { Icon(Icons.Filled.Info, null, Modifier.size(20.dp)) },
                                onClick = {
                                    sortMenu = false
                                    infoFile = CloudFile(id = dir, name = title ?: drive.source.name, isDir = true)
                                },
                            )
                        }
                    }
                    IconButton(onClick = {
                        val next = if (view == VIEW_GRID) VIEW_LIST else VIEW_GRID
                        setView(next)
                    }) {
                        val isGrid = view == VIEW_GRID
                        Icon(
                            painter = painterResource(if (isGrid) R.drawable.ic_view_list else R.drawable.ic_grid),
                            contentDescription = if (isGrid) "切换为列表" else "切换为网格"
                        )
                    }
                    IconButton(onClick = { refreshing = true; reload++ }) { Icon(Icons.Filled.Refresh, "刷新") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Bg),
            )
            if (depth > 0) Breadcrumbs(crumbs) { k -> repeat(depth - k) { nav.popBackStack() } }
        }
    }) { pad ->
        val expired = error?.let { e -> listOf("登录", "401", "失效", "过期", "token", "Token", "cookie", "Cookie", "Unauthorized").any { it in e } } == true
        PullToRefreshBox(
            isRefreshing = refreshing, onRefresh = { refreshing = true; reload++ },
            modifier = Modifier.padding(pad).fillMaxSize(),
        ) {
            val fill = Modifier.fillMaxSize()
            val header: (@Composable () -> Unit)? = if (isRoot) ({
                RootHeader(
                    drive, usage, recent, headers, favs,
                    onRecent = ::playRecent,
                    onRecentRemove = { deleteRecent = it },
                    onFavVideo = ::playFav,
                    onFavDir = { fav -> nav.navigate(Routes.cloud(sid, fav.id, fav.name, fav.path)) },
                    onFavRemove = { deleteFav = it },
                )
            }) else null
            when {
                error != null && entries == null || error != null && !refreshing && expired -> Column(
                    fill.verticalScroll(rememberScrollState()).padding(24.dp),
                    verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Spacer(Modifier.height(80.dp))
                    if (expired) {
                        Text(if (drive.source.isCloud) "登录已失效" else "认证失败", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Spacer(Modifier.height(6.dp))
                        Text(error ?: "", color = TextDim, fontSize = 13.sp)
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = { nav.navigate(Routes.editSource(drive.source.type, sid)) }) { Text("重新登录") }
                        TextButton(onClick = { reload++ }) { Text("重试") }
                    } else {
                        ErrorBox("加载失败：$error", { reload++ }, Modifier)
                        TextButton(onClick = { nav.navigate(Routes.editSource(drive.source.type, sid)) }) { Text("重新登录") }
                    }
                }
                entries == null -> Column(fill) {
                    header?.invoke()
                    Skeleton(view != VIEW_LIST, Modifier.fillMaxWidth())
                }
                shown.isEmpty() -> Column(fill.verticalScroll(rememberScrollState())) {
                    header?.invoke()
                    EmptyState(
                        when {
                            query.isNotBlank() -> "没有匹配「${query.trim()}」的文件"
                            all.isEmpty() -> "文件夹是空的"
                            else -> "此文件夹没有视频"
                        },
                        if (videosOnly && all.isNotEmpty() && query.isBlank()) ({ videosOnly = false; store.cloudVideosOnly = false }) else null,
                    )
                }
                view == VIEW_LIST -> LazyColumn(fill, contentPadding = PaddingValues(bottom = 24.dp)) {
                    if (header != null) item(key = "_header") { header() }
                    item(key = "_count") { CountLine(shown) }
                    items(shown, key = { it.id }) { f ->
                        val pos = remember(f.id, resumeTick) { if (f.isVideo) store.position(posKey(sid, f.id)) else 0L }
                        FileRow(f, headers, pos, sid, onClick = { open(f) }, onLong = { sheetFile = f })
                    }
                }
                view == VIEW_GRID -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(160.dp), modifier = fill,
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    if (header != null) item(key = "_header", span = { GridItemSpan(maxLineSpan) }) { header() }
                    item(key = "_count", span = { GridItemSpan(maxLineSpan) }) { CountLine(shown, Modifier.padding(horizontal = 4.dp)) }
                    items(shown.size, key = { shown[it].id }) { i ->
                        val f = shown[i]
                        val key = remember(sid, f.id) { posKey(sid, f.id) }
                        val pos = remember(f.id, resumeTick) { if (f.isVideo) store.position(key) else 0L }
                        val isFin = remember(key, resumeTick) { if (f.isVideo) store.isFinished(key) else false }
                        Column(
                            Modifier.alpha(if (f.isDir || f.isVideo) 1f else 0.45f)
                                .combinedClickable(onClick = { open(f) }, onLongClick = { sheetFile = f })
                        ) {
                            ThumbWithProgress(f, headers, pos, Modifier.fillMaxWidth(), 36, sid = sid)
                            Text(f.name, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                            val meta = metaLine(f, sid) + when {
                                isFin -> " · 已看完"
                                pos > 0 -> " · 看到 ${fmtMs(pos)}"
                                else -> ""
                            }
                            Text(meta.trim(' ', '·'), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 11.sp, color = if (isFin) Accent else TextDim)
                        }
                    }
                }
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(store.gridColumnsMin.dp), modifier = fill,
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    if (header != null) item(key = "_header", span = { GridItemSpan(maxLineSpan) }) { header() }
                    items(shown.size, key = { shown[it].id }) { i ->
                        val f = shown[i]
                        val localThumb = remember(sid, f.id) { if (f.isVideo) VideoThumbnails.getCached(context, sid, f.id) else null }
                        val poster by produceState<String?>(null, f.id) { value = if (f.isDir || f.isVideo) Tmdb.poster(f.name) else null }
                        val key = remember(sid, f.id) { posKey(sid, f.id) }
                        val pos = remember(f.id, resumeTick) { if (f.isVideo) store.position(key) else 0L }
                        val progress = remember(key, f.duration, resumeTick) { if (f.isVideo) store.progress(key, f.duration) else 0f }
                        val isFin = remember(key, resumeTick) { if (f.isVideo) store.isFinished(key) else false }
                        PosterCard(
                            title = if (f.isDir || !f.isVideo) f.name else Tmdb.guess(f.name).title,
                            subtitle = if (f.isDir) folderDesc(f, sid) else sizeText(f.size),
                            imageUrl = poster ?: localThumb?.absolutePath ?: f.thumb,
                            modifier = Modifier.alpha(if (f.isDir || f.isVideo) 1f else 0.45f),
                            progress = progress,
                            played = isFin,
                            badge = if (isFin) null else if (pos > 0) "续" else null,
                            onLongClick = { sheetFile = f },
                        ) { open(f) }
                    }
                }
            }
        }
    }

    sheetFile?.let { f ->
        CloudItemMenuDialog(
            f = f,
            headers = headers,
            sid = sid,
            currentDir = dir,
            currentDirTitle = title,
            store = store,
            onDismiss = { sheetFile = null },
            onOpen = { open(f) },
            onPlay = { mpv, fromStart -> play(f, mpv = mpv, fromStart = fromStart) },
            onToggleFav = { toggleFav(f) },
            onRename = { renameFile = f },
            onDelete = { deleteFile = f },
            onInfo = { infoFile = f },
            onSetFolderQuality = { target -> qualityFolderTarget = target },
        )
    }

    qualityFolderTarget?.let { target ->
        FolderQualityDialog(
            folder = target,
            sid = sid,
            drive = drive,
            sampleVideo = if (target.id == dir) videos.firstOrNull() else null,
            store = store,
            onDismiss = { qualityFolderTarget = null },
        )
    }

    renameFile?.let { f ->
        RenameDialog(f.name, f.isDir, { renameFile = null }) { n -> renameFile = null; fileOp("已重命名") { drive.rename(f, n) } }
    }
    deleteFile?.let { f ->
        DeleteDialog(f.name, f.isDir, toTrash = true, onDismiss = { deleteFile = null }) { deleteFile = null; fileOp("已移到回收站") { drive.delete(f) } }
    }
    deleteRecent?.let { r ->
        AlertDialog(
            onDismissRequest = { deleteRecent = null },
            title = { Text("删除播放记录？") },
            text = { Text("确定要删除「${r.name.substringBeforeLast('.')}」的播放记录吗？") },
            confirmButton = {
                TextButton(onClick = {
                    deleteRecent = null
                    removeRecent(r)
                }) {
                    Text("删除", color = Color(0xFFE5484D))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteRecent = null }) {
                    Text("取消")
                }
            },
        )
    }
    deleteFav?.let { fav ->
        val itemType = if (fav.isDir) "收藏目录" else "收藏视频"
        val displayName = if (fav.isDir) fav.name else fav.name.substringBeforeLast('.')
        AlertDialog(
            onDismissRequest = { deleteFav = null },
            title = { Text("取消$itemType？") },
            text = { Text("确定要取消收藏「$displayName」吗？") },
            confirmButton = {
                TextButton(onClick = {
                    deleteFav = null
                    removeFav(fav)
                }) {
                    Text("取消收藏", color = Color(0xFFE5484D))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteFav = null }) {
                    Text("取消")
                }
            },
        )
    }

    infoFile?.let { f ->
        if (f.isDir) {
            FolderInfoDialog(
                f = f,
                sid = sid,
                drive = drive,
                crumbs = crumbs,
                currentDir = dir,
                onDismiss = { infoFile = null },
            )
        } else {
            val fullPath = "/" + (crumbs.drop(1) + f.name).joinToString("/")
            AlertDialog(
                onDismissRequest = { infoFile = null },
                confirmButton = { TextButton(onClick = { infoFile = null }) { Text("关闭") } },
                dismissButton = {
                    TextButton(onClick = {
                        clipboard.setText(AnnotatedString(f.name)); infoFile = null
                        android.widget.Toast.makeText(context, "已复制文件名", android.widget.Toast.LENGTH_SHORT).show()
                    }) { Text("复制文件名") }
                },
                title = { Text("文件信息") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        InfoLine("名称", f.name)
                        InfoLine("大小", sizeText(f.size).ifEmpty { "未知" } + if (f.size > 0) "（${"%,d".format(f.size)} 字节）" else "")
                        if (f.duration > 0) InfoLine("时长", fmtMs(f.duration))
                        InfoLine("修改时间", fullDateText(f.modified))
                        InfoLine("位置", "${drive.source.typeLabel} · $fullPath")
                        val p = store.position(posKey(sid, f.id))
                        if (p > 0) InfoLine("播放进度", "看到 ${fmtMs(p)}")
                    }
                },
            )
        }
    }
}

@Composable
private fun FolderInfoDialog(
    f: CloudFile,
    sid: String,
    drive: CloudDrive,
    crumbs: List<String>,
    currentDir: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val isCurrent = f.id == currentDir
    val fullPath = if (isCurrent) {
        "/" + crumbs.drop(1).joinToString("/")
    } else {
        "/" + (crumbs.drop(1) + f.name).joinToString("/")
    }

    var stat by remember(f.id) { mutableStateOf(FolderStatCache.get(sid, f.id)) }
    var loading by remember(f.id) { mutableStateOf(true) }
    var error by remember(f.id) { mutableStateOf<String?>(null) }

    LaunchedEffect(f.id) {
        loading = true
        error = null
        try {
            val list = withContext(Dispatchers.IO) { drive.list(f.id) }
            val s = FolderStatCache.put(sid, f.id, list)
            stat = s
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "获取统计失败"
        } finally {
            loading = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        dismissButton = {
            TextButton(onClick = {
                clipboard.setText(AnnotatedString(f.name))
                onDismiss()
                android.widget.Toast.makeText(context, "已复制文件夹名", android.widget.Toast.LENGTH_SHORT).show()
            }) { Text("复制名称") }
        },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_folder), null, tint = FolderColor, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                Text("文件夹信息", fontWeight = FontWeight.Bold, fontSize = 17.sp)
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                InfoLine("名称", f.name)

                val currentStat = stat
                if (currentStat != null) {
                    val detail = listOfNotNull(
                        if (currentStat.videoCount > 0) "${currentStat.videoCount} 个视频" else null,
                        if (currentStat.dirCount > 0) "${currentStat.dirCount} 个子文件夹" else null,
                        if (currentStat.otherCount > 0) "${currentStat.otherCount} 个其他文件" else null,
                    ).joinToString("，")
                    InfoLine("包含内容", if (currentStat.total == 0) "空文件夹" else "共 ${currentStat.total} 项" + if (detail.isNotEmpty()) "（$detail）" else "")

                    val sizeVal = if (currentStat.totalSize > 0) currentStat.totalSize else f.size
                    if (sizeVal > 0) {
                        InfoLine("包含文件总大小", sizeText(sizeVal) + "（${"%,d".format(sizeVal)} 字节）")
                    } else {
                        InfoLine("包含文件总大小", if (currentStat.total == 0) "0 字节" else "未知")
                    }
                } else if (f.childCount != null) {
                    InfoLine("包含内容", if (f.childCount == 0) "空文件夹" else "共 ${f.childCount} 项")
                    if (f.size > 0) InfoLine("包含文件总大小", sizeText(f.size) + "（${"%,d".format(f.size)} 字节）")
                }

                if (loading) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(vertical = 4.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = Accent)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (currentStat != null) "正在刷新最新统计…" else "正在深度统计文件夹内容…",
                            fontSize = 12.sp,
                            color = TextDim,
                        )
                    }
                } else if (error != null && currentStat == null) {
                    Text("统计提示：$error", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                }

                if (f.modified > 0) {
                    InfoLine("修改时间", fullDateText(f.modified))
                }
                InfoLine("位置", "${drive.source.typeLabel} · $fullPath")
            }
        },
    )
}

@Composable
private fun InfoLine(label: String, value: String) {
    Column {
        Text(label, fontSize = 12.sp, color = TextDim)
        Text(value, fontSize = 14.sp)
    }
}

@Composable
private fun MenuRow(text: String, checked: Boolean, icon: Int? = null, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text) }, onClick = onClick,
        leadingIcon = icon?.let { { Icon(painterResource(it), null, Modifier.size(20.dp)) } },
        trailingIcon = { if (checked) Icon(Icons.Filled.Check, null, tint = Accent, modifier = Modifier.size(20.dp)) else Spacer(Modifier.size(20.dp)) },
    )
}

@Composable
private fun SheetRow(
    vector: ImageVector?,
    res: Int?,
    text: String,
    tint: Color = TextDim,
    textColor: Color = Color.Unspecified,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (vector != null) Icon(vector, null, tint = tint, modifier = Modifier.size(19.dp))
        else if (res != null) Icon(painterResource(res), null, tint = tint, modifier = Modifier.size(19.dp))
        Spacer(Modifier.width(14.dp))
        Text(text, fontSize = 14.sp, color = textColor, maxLines = 1, softWrap = false)
    }
}

@Composable
private fun CloudItemMenuDialog(
    f: CloudFile,
    headers: Map<String, String>,
    sid: String,
    currentDir: String?,
    currentDirTitle: String?,
    store: com.kevin.fanzhou.data.Store,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onPlay: (Boolean, Boolean) -> Unit,
    onToggleFav: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onInfo: () -> Unit,
    onSetFolderQuality: (CloudFile) -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val pos = store.position(posKey(sid, f.id))
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Surface1,
            tonalElevation = 6.dp,
            modifier = Modifier
                .padding(horizontal = 24.dp, vertical = 24.dp)
                .wrapContentSize(),
        ) {
            Layout(
                content = {
                    // 0: Header (thumbnail + file name + meta)
                    Row(
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ThumbWithProgress(f, headers, pos, Modifier.width(44.dp), 14, sid = sid)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                f.name,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                            )
                            val m = metaLine(f, sid)
                            if (m.isNotEmpty()) {
                                Text(
                                    m,
                                    fontSize = 11.sp,
                                    color = TextDim,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                    // 1: Divider
                    HorizontalDivider(color = Color.White.copy(alpha = 0.08f), thickness = 0.8.dp)
                    // 2: Menu items column (intrinsic max width)
                    Column(
                        modifier = Modifier
                            .width(IntrinsicSize.Max)
                            .widthIn(min = 160.dp)
                            .padding(vertical = 4.dp),
                    ) {
                        if (f.isDir) {
                            SheetRow(null, R.drawable.ic_folder, "打开") { onDismiss(); onOpen() }
                            val currentQ = store.qualityForFolder("$sid:${f.id}")
                            val qDesc = if (currentQ.isNullOrEmpty()) "原画" else currentQ
                            SheetRow(null, R.drawable.ic_decoder, "文件夹画质（当前：$qDesc）") {
                                onDismiss()
                                onSetFolderQuality(f)
                            }
                        }
                        if (f.isVideo) {
                            SheetRow(Icons.Filled.PlayArrow, null, if (pos > 0) "继续播放（${fmtMs(pos)}）" else "播放") {
                                onDismiss(); onPlay(false, false)
                            }
                            SheetRow(null, R.drawable.ic_core, "用 MPV 播放") { onDismiss(); onPlay(true, false) }
                            SheetRow(null, R.drawable.ic_replay, "从头播放") { onDismiss(); onPlay(false, true) }
                            val currentDirQ = store.qualityForFolder("$sid:$currentDir")
                            val qDesc = if (currentDirQ.isNullOrEmpty()) "原画" else currentDirQ
                            SheetRow(null, R.drawable.ic_decoder, "所在目录画质（当前：$qDesc）") {
                                onDismiss()
                                onSetFolderQuality(CloudFile(id = currentDir ?: "", name = currentDirTitle ?: "当前文件夹", isDir = true))
                            }
                        }
                        if (f.isDir || f.isVideo) {
                            val favved = store.isCloudFav(sid, f.id)
                            SheetRow(Icons.Filled.Star, null, if (favved) "取消收藏" else "收藏") {
                                onDismiss(); onToggleFav()
                            }
                        }
                        SheetRow(null, R.drawable.ic_copy, if (f.isDir) "复制文件夹名" else "复制文件名") {
                            clipboard.setText(AnnotatedString(f.name))
                            onDismiss()
                            android.widget.Toast.makeText(context, if (f.isDir) "已复制文件夹名" else "已复制文件名", android.widget.Toast.LENGTH_SHORT).show()
                        }
                        SheetRow(Icons.Filled.Info, null, if (f.isDir) "文件夹信息" else "文件信息") { onDismiss(); onInfo() }
                        if (!f.id.endsWith("/root")) {
                            SheetRow(Icons.Filled.Edit, null, "重命名") { onDismiss(); onRename() }
                            SheetRow(
                                Icons.Filled.Delete, null, "删除",
                                tint = MaterialTheme.colorScheme.error,
                                textColor = MaterialTheme.colorScheme.error,
                            ) { onDismiss(); onDelete() }
                        }
                    }
                }
            ) { measurables, constraints ->
                val headerMeasurable = measurables[0]
                val dividerMeasurable = measurables[1]
                val menuMeasurable = measurables[2]

                val menuPlaceable = menuMeasurable.measure(constraints.copy(minWidth = 0))
                val targetWidth = menuPlaceable.width.coerceIn(constraints.minWidth, constraints.maxWidth)

                val exactConstraints = constraints.copy(minWidth = targetWidth, maxWidth = targetWidth)
                val headerPlaceable = headerMeasurable.measure(exactConstraints)
                val dividerPlaceable = dividerMeasurable.measure(exactConstraints)

                val totalHeight = headerPlaceable.height + dividerPlaceable.height + menuPlaceable.height

                layout(targetWidth, totalHeight) {
                    var y = 0
                    headerPlaceable.placeRelative(0, y)
                    y += headerPlaceable.height
                    dividerPlaceable.placeRelative(0, y)
                    y += dividerPlaceable.height
                    menuPlaceable.placeRelative(0, y)
                }
            }
        }
    }
}

@Composable
private fun FolderQualityDialog(
    folder: CloudFile,
    sid: String,
    drive: CloudDrive,
    sampleVideo: CloudFile?,
    store: com.kevin.fanzhou.data.Store,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val folderKey = "$sid:${folder.id}"
    val currentQuality = remember(folder.id) { store.qualityForFolder(folderKey) } ?: "原画"

    var detectedQualities by remember { mutableStateOf<List<String>>(emptyList()) }
    var loadingQualities by remember { mutableStateOf(false) }

    LaunchedEffect(folder.id, sampleVideo?.id) {
        if (sampleVideo != null) {
            loadingQualities = true
            val qs = withContext(Dispatchers.IO) {
                runCatching { drive.qualities(sampleVideo) }.getOrNull().orEmpty()
            }
            if (qs.isNotEmpty()) {
                detectedQualities = qs.map { it.first }
            }
            loadingQualities = false
        }
    }

    val defaultOptions = listOf("原画", "4K 超高清", "2K 极清", "超清 1080P", "高清 720P", "标清 480P", "流畅 360P")
    val allOptions = remember(detectedQualities) {
        if (detectedQualities.isNotEmpty()) {
            listOf("原画") + detectedQualities.filter { it != "原画" }
        } else {
            defaultOptions
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("设置文件夹画质", fontWeight = FontWeight.Bold, fontSize = 17.sp)
        },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                Text(
                    "文件夹：「${folder.name}」",
                    fontSize = 13.sp,
                    color = Accent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "设置后，该文件夹下所有视频将默认以该画质起播（原画无法播放时自动按此画质播放，杜绝卡死）。",
                    fontSize = 12.sp,
                    color = TextDim,
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
                )

                if (loadingQualities) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(vertical = 8.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("正在探测该网盘支持的画质档位…", fontSize = 12.sp, color = TextDim)
                    }
                }

                allOptions.forEach { opt ->
                    val isChecked = (opt == "原画" && (currentQuality == "原画" || currentQuality.isEmpty())) || opt == currentQuality
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                val chosen = if (opt == "原画") null else opt
                                store.setQualityForFolder(folderKey, chosen)
                                store.setQualityForSeries("cloud:$folderKey", chosen)
                                android.widget.Toast.makeText(
                                    context,
                                    if (chosen == null) "已恢复「${folder.name}」为默认原画" else "已设置「${folder.name}」全部视频画质为：$opt",
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                                onDismiss()
                            }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = isChecked,
                            onClick = null,
                            colors = RadioButtonDefaults.colors(selectedColor = Accent)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            opt,
                            fontSize = 14.sp,
                            fontWeight = if (isChecked) FontWeight.Bold else FontWeight.Normal,
                            color = if (isChecked) Accent else Color.White
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        }
    )
}

@Composable
private fun Breadcrumbs(crumbs: List<String>, onPick: (Int) -> Unit) {
    val scroll = rememberScrollState()
    LaunchedEffect(crumbs.size) { scroll.scrollTo(scroll.maxValue) }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(scroll).padding(horizontal = 12.dp).padding(bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        crumbs.forEachIndexed { i, c ->
            val last = i == crumbs.lastIndex
            Text(
                c.ifEmpty { "…" }, fontSize = 13.sp, maxLines = 1,
                color = if (last) MaterialTheme.colorScheme.onSurface else Accent,
                fontWeight = if (last) FontWeight.Medium else FontWeight.Normal,
                modifier = Modifier.clip(RoundedCornerShape(6.dp))
                    .then(if (last) Modifier else Modifier.clickable { onPick(i) })
                    .padding(horizontal = 6.dp, vertical = 4.dp),
            )
            if (!last) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = TextDim, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun CountLine(shown: List<CloudFile>, modifier: Modifier = Modifier) {
    val dirs = shown.count { it.isDir }
    val vids = shown.count { it.isVideo }
    val others = shown.size - dirs - vids
    val text = listOfNotNull(
        if (dirs > 0) "$dirs 个文件夹" else null,
        if (vids > 0) "$vids 个视频" else null,
        if (others > 0) "$others 个其他文件" else null,
    ).joinToString(" · ")
    Text(text, fontSize = 12.sp, color = TextDim, modifier = modifier.padding(start = 16.dp, top = 6.dp, bottom = 4.dp))
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(f: CloudFile, headers: Map<String, String>, pos: Long, sid: String, onClick: () -> Unit, onLong: () -> Unit) {
    val store = Repo.store
    val key = remember(sid, f.id) { posKey(sid, f.id) }
    val isFin = remember(key) { if (f.isVideo) store.isFinished(key) else false }
    val dim = !(f.isDir || f.isVideo)
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLong)
            .alpha(if (dim) 0.45f else 1f).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (f.isVideo || f.thumb != null) ThumbWithProgress(f, headers, pos, Modifier.width(120.dp), 26, sid = sid)
        else Box(Modifier.width(120.dp).aspectRatio(16f / 9f), contentAlignment = Alignment.Center) {
            CloudThumb(f, headers, Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)), 28, sid = sid)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(f.name, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 14.sp, lineHeight = 18.sp)
            Spacer(Modifier.height(4.dp))
            val meta = metaLine(f, sid)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (meta.isNotEmpty()) Text(meta, fontSize = 11.sp, color = TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (isFin) {
                    Text(
                        (if (meta.isNotEmpty()) " · " else "") + "已看完", fontSize = 11.sp, color = Accent, maxLines = 1,
                    )
                } else if (pos > 0) {
                    Text(
                        (if (meta.isNotEmpty()) " · " else "") + "看到 ${fmtMs(pos)}", fontSize = 11.sp, color = Accent, maxLines = 1,
                    )
                }
            }
        }
        if (f.isDir) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = TextDim)
    }
}

@Composable
private fun EmptyState(text: String, onShowAll: (() -> Unit)?) {
    Column(Modifier.fillMaxWidth().padding(top = 80.dp, start = 24.dp, end = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(72.dp).clip(RoundedCornerShape(20.dp)).background(FolderColor.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.ic_folder), null, tint = FolderColor, modifier = Modifier.size(36.dp))
        }
        Spacer(Modifier.height(14.dp))
        Text(text, color = TextDim, fontSize = 14.sp)
        if (onShowAll != null) {
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onShowAll) { Text("显示全部文件") }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RootHeader(
    drive: CloudDrive, usage: Pair<Long, Long>?, recent: List<CloudRecent>, headers: Map<String, String>,
    favs: List<CloudFav>,
    onRecent: (CloudRecent) -> Unit,
    onRecentRemove: (CloudRecent) -> Unit,
    onFavVideo: (CloudFav) -> Unit,
    onFavDir: (CloudFav) -> Unit,
    onFavRemove: (CloudFav) -> Unit,
) {
    val s = drive.source
    Column(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth()
                .clip(RoundedCornerShape(16.dp)).background(Surface1).padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SourceBadge(s.type, 44)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(s.name.ifEmpty { s.typeLabel }, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(s.typeLabel, fontSize = 12.sp, color = TextDim)
                }
            }
            if (usage != null) {
                Spacer(Modifier.height(14.dp))
                val frac = (usage.first.toFloat() / usage.second).coerceIn(0f, 1f)
                Row {
                    Text("存储空间", fontSize = 12.sp, color = TextDim, modifier = Modifier.weight(1f))
                    Text("${sizeText(usage.first).ifEmpty { "0 MB" }} / ${sizeText(usage.second)}", fontSize = 12.sp, color = TextDim)
                }
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { frac },
                    color = if (frac > 0.9f) Color(0xFFE5484D) else Accent,
                    trackColor = Surface2,
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                    drawStopIndicator = {},
                )
                Text("已使用 ${(frac * 100).toInt()}%", fontSize = 11.sp, color = TextDim, modifier = Modifier.padding(top = 4.dp))
            }
        }
        val favDirs = favs.filter { it.isDir }
        val favVids = favs.filter { !it.isDir }
        if (favDirs.isNotEmpty()) {
            Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Star, null, tint = Accent, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("收藏目录", fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(favDirs, key = { it.id }) { fav ->
                    Column(
                        Modifier.width(150.dp).clip(RoundedCornerShape(10.dp))
                            .combinedClickable(onClick = { onFavDir(fav) }, onLongClick = { onFavRemove(fav) }),
                    ) {
                        Box(
                            Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)).background(Surface1),
                            contentAlignment = Alignment.Center,
                        ) { Icon(painterResource(R.drawable.ic_folder), null, tint = FolderColor, modifier = Modifier.size(40.dp)) }
                        Text(fav.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp, modifier = Modifier.padding(top = 5.dp))
                        Text("长按取消收藏", maxLines = 1, fontSize = 11.sp, color = TextDim)
                    }
                }
            }
        }
        if (favVids.isNotEmpty()) {
            Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Star, null, tint = Accent, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("收藏视频", fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(favVids, key = { it.id }) { fav ->
                    val f = CloudFile(fav.id, fav.name, false, fav.size, fav.extra, thumb = fav.thumb, duration = fav.duration)
                    val pos = Repo.store.position(posKey(s.id, fav.id))
                    Column(
                        Modifier.width(150.dp).clip(RoundedCornerShape(10.dp))
                            .combinedClickable(onClick = { onFavVideo(fav) }, onLongClick = { onFavRemove(fav) }),
                    ) {
                        ThumbWithProgress(f, headers, pos, Modifier.fillMaxWidth(), 26, sid = s.id)
                        Text(fav.name.substringBeforeLast('.'), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp, modifier = Modifier.padding(top = 5.dp))
                        Text(
                            listOfNotNull(fav.dirName, if (pos > 0) "看到 ${fmtMs(pos)}" else null).joinToString(" · ").ifEmpty { "根目录" },
                            maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 11.sp, color = TextDim,
                        )
                    }
                }
            }
        }
        if (recent.isNotEmpty()) {
            Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_history), null, tint = Accent, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("最近播放", fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(recent, key = { it.id }) { r ->
                    val f = CloudFile(r.id, r.name, false, r.size, r.extra, thumb = r.thumb, duration = r.duration)
                    val pos = Repo.store.position(posKey(s.id, r.id))
                    Column(
                        Modifier.width(150.dp).clip(RoundedCornerShape(10.dp))
                            .combinedClickable(onClick = { onRecent(r) }, onLongClick = { onRecentRemove(r) }),
                    ) {
                        ThumbWithProgress(f, headers, pos, Modifier.fillMaxWidth(), 26, sid = s.id)
                        Text(r.name.substringBeforeLast('.'), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp, modifier = Modifier.padding(top = 5.dp))
                        Text(
                            listOfNotNull(r.dirName, if (pos > 0) "看到 ${fmtMs(pos)}" else null).joinToString(" · ").ifEmpty { "根目录" },
                            maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 11.sp, color = TextDim,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}
