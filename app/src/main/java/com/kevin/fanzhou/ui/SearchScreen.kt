package com.kevin.fanzhou.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.kevin.fanzhou.Routes
import com.kevin.fanzhou.data.JfItem
import com.kevin.fanzhou.data.Repo
import kotlinx.coroutines.delay

@Composable
fun SearchScreen(nav: NavHostController, sid: String) {
    val client = remember(sid) { Repo.jellyfin(sid) } ?: return ErrorBox("媒体源不存在")
    var q by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<JfItem>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(q) {
        if (q.isBlank()) { results = emptyList(); return@LaunchedEffect }
        delay(350)
        loading = true
        results = runCatching { client.search(q.trim()) }.getOrDefault(emptyList())
        loading = false
    }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
            OutlinedTextField(q, { q = it }, placeholder = { Text("搜索电影、剧集") }, singleLine = true,
                modifier = Modifier.weight(1f).focusRequester(focus))
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyVerticalGrid(
            columns = GridCells.Adaptive(Repo.store.gridColumnsMin.dp),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(results, key = { it.id }) { it ->
                PosterCard(
                    title = if (it.type == "Episode") "${it.seriesName} ${it.episodeLabel}" else it.name,
                    subtitle = if (it.type == "Episode") it.name else it.year?.toString(),
                    imageUrl = client.seriesPoster(it), played = it.userData?.played == true,
                ) { nav.navigate(Routes.detail(sid, it.id)) }
            }
        }
    }
}
