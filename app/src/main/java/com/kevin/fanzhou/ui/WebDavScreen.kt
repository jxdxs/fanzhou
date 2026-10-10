package com.kevin.fanzhou.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.kevin.fanzhou.Routes
import com.kevin.fanzhou.data.*
import com.kevin.fanzhou.player.PlayerCore
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun WebDavScreen(nav: NavHostController, sid: String, url: String?) {
    val source = remember(sid) { Repo.store.get(sid) } ?: return ErrorBox("媒体源不存在")
    val client = remember(source) { Repo.browser(source) }
    val context = LocalContext.current
    val dirUrl = url ?: client.rootUrl
    var entries by remember { mutableStateOf<List<DavEntry>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    val hasTmdb = Repo.store.tmdbKey.isNotEmpty()
    var grid by remember { mutableStateOf(hasTmdb) }
    var sort by remember { mutableStateOf(Repo.store.folderSort) }
    var videosOnly by remember { mutableStateOf(Repo.store.folderVideosOnly) }
    var menu by remember { mutableStateOf(false) }
    var actionEntry by remember { mutableStateOf<DavEntry?>(null) }
    var renameEntry by remember { mutableStateOf<DavEntry?>(null) }
    var deleteEntry by remember { mutableStateOf<DavEntry?>(null) }
    val scope = rememberCoroutineScope()
    fun fileOp(done: String, op: suspend () -> Unit) {
        scope.launch {
            val err = try { op(); null } catch (e: Exception) { e.message ?: e.javaClass.simpleName }
            android.widget.Toast.makeText(context, err ?: done, android.widget.Toast.LENGTH_SHORT).show()
            if (err == null) reload++
        }
    }

    LaunchedEffect(dirUrl, reload) {
        error = null
        try { entries = client.list(dirUrl) } catch (e: Exception) { error = e.message }
    }

    val sorted = (entries ?: emptyList()).let { list ->
        val files = when (sort) {
            "date" -> list.sortedByDescending { it.modifiedMs }
            "size" -> list.sortedByDescending { it.size }
            else -> list.sortedBy { WebDavClient.naturalKey(it.name) }
        }
        files.sortedBy { !it.isDir }
    }
    val shown = sorted.filter { it.isDir || it.isVideo || !videosOnly }
    val videos = shown.filter { it.isVideo }
    fun open(e: DavEntry) {
        if (e.isDir) nav.navigate(Routes.dav(sid, e.href))
        else if (!e.isVideo) android.widget.Toast.makeText(context, "不是视频文件", android.widget.Toast.LENGTH_SHORT).show()
        else {
            val all = entries ?: emptyList()
            PlayerCore.start(context, videos.map { client.playItem(it, all) }, videos.indexOf(e).coerceAtLeast(0))
        }
    }
    val title = if (url == null) source.name else client.folderName(dirUrl)

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { BackAndHome(nav, showHome = url != null) },
            actions = {
                if (hasTmdb) TextButton(onClick = { grid = !grid }) { Text(if (grid) "列表" else "海报") }
                IconButton(onClick = { reload++ }) { Icon(Icons.Filled.Refresh, "刷新") }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "排序与筛选") }
                    DropdownMenu(menu, { menu = false }) {
                        listOf("name" to "按名称排序", "date" to "按修改时间排序", "size" to "按大小排序").forEach { (k, l) ->
                            DropdownMenuItem(
                                text = { Text((if (sort == k) "✓ " else "　 ") + l) },
                                onClick = { sort = k; Repo.store.folderSort = k; menu = false },
                            )
                        }
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text((if (videosOnly) "✓ " else "　 ") + "只显示视频") },
                            onClick = { videosOnly = !videosOnly; Repo.store.folderVideosOnly = videosOnly; menu = false },
                        )
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Bg),
        )
    }) { pad ->
        val m = Modifier.padding(pad).fillMaxSize()
        when {
            error != null -> ErrorBox("加载失败：$error", { reload++ }, m)
            entries == null -> LoadingBox(m)
            shown.isEmpty() -> ErrorBox("此文件夹没有视频", modifier = m)
            grid -> LazyVerticalGrid(
                columns = GridCells.Adaptive(Repo.store.gridColumnsMin.dp), modifier = m,
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                items(shown, key = { it.href }) { e ->
                    val poster by produceState<String?>(null, e.href) { value = if (e.isDir || e.isVideo) Tmdb.poster(e.name) else null }
                    val pos = remember(e.href) { if (e.isVideo) Repo.store.position(e.href) else 0L }
                    PosterCard(
                        title = if (e.isDir) e.name else Tmdb.guess(e.name).title,
                        subtitle = if (e.isDir) "文件夹" else sizeText(e.size),
                        imageUrl = poster, badge = if (pos > 0) "续" else null,
                        onLongClick = { actionEntry = e },
                    ) { open(e) }
                }
            }
            else -> LazyColumn(m) {
                items(shown, key = { it.href }) { e ->
                    val pos = remember(e.href) { if (e.isVideo) Repo.store.position(e.href) else 0L }
                    ListItem(
                        headlineContent = { Text(e.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        supportingContent = {
                            Text(
                                if (e.isDir) "文件夹" else listOfNotNull(sizeText(e.size), if (pos > 0) "看到 ${fmtMs(pos)}" else null).joinToString("  ·  "),
                                fontSize = 12.sp, color = TextDim,
                            )
                        },
                        leadingContent = {
                            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                                if (e.isDir) Text("📁", fontSize = 22.sp)
                                else if (e.isVideo) Icon(Icons.Filled.PlayArrow, null, tint = Accent)
                                else Text("📄", fontSize = 20.sp)
                            }
                        },
                        modifier = Modifier.combinedClickable(onClick = { open(e) }, onLongClick = { actionEntry = e }),
                        colors = ListItemDefaults.colors(containerColor = Bg),
                    )
                }
            }
        }
    }

    actionEntry?.let { e ->
        ModalBottomSheet(onDismissRequest = { actionEntry = null }, containerColor = Surface1) {
            Text(e.name, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 15.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            if (e.isVideo) ListItem(
                headlineContent = { Text("播放") }, leadingContent = { Icon(Icons.Filled.PlayArrow, null) },
                modifier = Modifier.clickable { actionEntry = null; open(e) }, colors = ListItemDefaults.colors(containerColor = Surface1),
            )
            ListItem(
                headlineContent = { Text("重命名") }, leadingContent = { Icon(Icons.Filled.Edit, null) },
                modifier = Modifier.clickable { actionEntry = null; renameEntry = e }, colors = ListItemDefaults.colors(containerColor = Surface1),
            )
            ListItem(
                headlineContent = { Text("删除", color = MaterialTheme.colorScheme.error) },
                leadingContent = { Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.error) },
                modifier = Modifier.clickable { actionEntry = null; deleteEntry = e }, colors = ListItemDefaults.colors(containerColor = Surface1),
            )
            Spacer(Modifier.height(24.dp))
        }
    }
    renameEntry?.let { e ->
        RenameDialog(e.name, e.isDir, { renameEntry = null }) { n -> renameEntry = null; fileOp("已重命名") { client.rename(e, n) } }
    }
    deleteEntry?.let { e ->
        DeleteDialog(e.name, e.isDir, toTrash = false, onDismiss = { deleteEntry = null }) { deleteEntry = null; fileOp("已删除") { client.delete(e) } }
    }
}

fun sizeText(b: Long): String = when {
    b <= 0 -> ""
    b > 1L shl 30 -> "%.2f GB".format(b / (1024.0 * 1024 * 1024))
    else -> "%.0f MB".format(b / (1024.0 * 1024))
}
