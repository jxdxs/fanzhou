package com.kevin.fanzhou.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.kevin.fanzhou.Routes
import com.kevin.fanzhou.data.JfItem
import com.kevin.fanzhou.data.Repo
import com.kevin.fanzhou.player.PlayerCore

private val sorts = listOf(
    Triple("名称", "SortName", "Ascending"),
    Triple("最近添加", "DateCreated", "Descending"),
    Triple("上映年份", "ProductionYear,SortName", "Descending"),
    Triple("评分", "CommunityRating", "Descending"),
    Triple("最近播放", "DatePlayed", "Descending"),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(nav: NavHostController, sid: String, parentId: String, title: String) {
    val client = remember(sid) { Repo.jellyfin(sid) } ?: return ErrorBox("媒体源不存在")
    val context = LocalContext.current
    var parent by remember { mutableStateOf<JfItem?>(null) }
    val items = remember { mutableStateListOf<JfItem>() }
    var total by remember { mutableIntStateOf(-1) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val ui = remember { context.getSharedPreferences("ui", android.content.Context.MODE_PRIVATE) }
    // Sort field + direction, remembered per library.
    var sortIdx by rememberSaveable { mutableIntStateOf(ui.getInt("lib_sort_$parentId", 0).coerceIn(0, sorts.lastIndex)) }
    var asc by rememberSaveable { mutableStateOf(ui.getBoolean("lib_asc_$parentId", sorts[sortIdx].third == "Ascending")) }
    fun saveSort() { ui.edit().putInt("lib_sort_$parentId", sortIdx).putBoolean("lib_asc_$parentId", asc).apply() }
    var sortMenu by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }

    suspend fun loadMore() {
        if (loading || (total >= 0 && items.size >= total)) return
        loading = true
        try {
            if (parent == null) parent = runCatching { client.item(parentId) }.getOrNull()
            val s = sorts[sortIdx]
            val page = client.items(parent, parentId, items.size, 60, s.second, if (asc) "Ascending" else "Descending")
            total = page.total
            items.addAll(page.items)
        } catch (e: Exception) { error = e.message }
        loading = false
    }

    LaunchedEffect(sortIdx, asc, reload) { items.clear(); total = -1; error = null; loadMore() }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(if (total > 0) "$title ($total)" else title) },
            navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
            actions = {
                IconButton(onClick = { nav.navigate(Routes.search(sid)) }) { Icon(Icons.Filled.Search, "搜索") }
                Box {
                    TextButton(onClick = { sortMenu = true }) { Text(sorts[sortIdx].first + if (asc) " ↑" else " ↓") }
                    DropdownMenu(sortMenu, onDismissRequest = { sortMenu = false }) {
                        sorts.forEachIndexed { i, s ->
                            DropdownMenuItem(
                                text = { Text(s.first) },
                                trailingIcon = { if (i == sortIdx) Text(if (asc) "↑" else "↓") },
                                onClick = {
                                    // Tapping the current field flips direction; a new field starts at its natural direction.
                                    if (i == sortIdx) asc = !asc else { sortIdx = i; asc = s.third == "Ascending" }
                                    saveSort(); sortMenu = false
                                },
                            )
                        }
                        HorizontalDivider()
                        DropdownMenuItem(text = { Text("正序（升序）") }, trailingIcon = { if (asc) Text("✓") },
                            onClick = { asc = true; saveSort(); sortMenu = false })
                        DropdownMenuItem(text = { Text("倒序（降序）") }, trailingIcon = { if (!asc) Text("✓") },
                            onClick = { asc = false; saveSort(); sortMenu = false })
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Bg),
        )
    }) { pad ->
        when {
            error != null && items.isEmpty() -> ErrorBox("加载失败：$error", { reload++ }, Modifier.padding(pad).fillMaxSize())
            items.isEmpty() && (loading || total < 0) -> LoadingBox(Modifier.padding(pad).fillMaxSize())
            items.isEmpty() -> ErrorBox("这里还没有内容", modifier = Modifier.padding(pad).fillMaxSize())
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(Repo.store.gridColumnsMin.dp),
                modifier = Modifier.padding(pad).fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                itemsIndexed(items, key = { _, it -> it.id }) { i, it ->
                    if (i >= items.size - 12) LaunchedEffect(items.size) { loadMore() }
                    PosterCard(
                        title = it.name, subtitle = it.year?.toString(), imageUrl = client.primaryImage(it),
                        progress = it.progress, played = it.userData?.played == true,
                        badge = it.userData?.unplayed?.takeIf { n -> n > 0 }?.toString(),
                    ) {
                        when {
                            it.type in setOf("Movie", "Series", "Episode") -> nav.navigate(Routes.detail(sid, it.id))
                            it.type == "Season" -> nav.navigate(Routes.detail(sid, it.seriesId ?: it.id))
                            it.isFolder -> nav.navigate(Routes.library(sid, it.id, it.name))
                            else -> PlayerCore.start(context, listOf(client.playItem(it)), 0)
                        }
                    }
                }
            }
        }
    }
}
