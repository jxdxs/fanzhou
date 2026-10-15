package com.kevin.fanzhou.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.kevin.fanzhou.Routes
import com.kevin.fanzhou.data.*
import com.kevin.fanzhou.player.PlayerCore
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

data class JfHome(
    val source: Source,
    val views: List<JfItem> = emptyList(),
    val resume: List<JfItem> = emptyList(),
    val nextUp: List<JfItem> = emptyList(),
    val favorites: List<JfItem> = emptyList(),
    val latest: List<Pair<JfItem, List<JfItem>>> = emptyList(),
    val error: String? = null,
)

suspend fun loadJfHome(source: Source): JfHome = try {
    val c = Repo.jellyfin(source)
    coroutineScope {
        val views = async { c.views() }
        val resume = async { runCatching { c.resume() }.getOrDefault(emptyList()) }
        val next = async { runCatching { c.nextUp() }.getOrDefault(emptyList()) }
        val fav = async { runCatching { c.favorites() }.getOrDefault(emptyList()) }
        val v = views.await()
        val latest = v.filter { it.collectionType in setOf("movies", "tvshows", null, "homevideos", "mixed") }
            .take(6)
            .map { lib -> async { lib to runCatching { c.latest(lib.id) }.getOrDefault(emptyList()) } }
            .awaitAll().filter { it.second.isNotEmpty() }
        JfHome(source, v, resume.await(), next.await(), fav.await(), latest)
    }
} catch (e: Exception) {
    JfHome(source, error = "${source.name} 连接失败：${e.message}")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(nav: NavHostController) {
    val homeUi = LocalContext.current.getSharedPreferences("ui", android.content.Context.MODE_PRIVATE)
    var davOpen by remember { mutableStateOf(homeUi.getBoolean("home_dav_open", false)) }
    val sources by Repo.store.sources.collectAsState()
    var homes by remember { mutableStateOf<List<JfHome>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var refreshKey by remember { mutableIntStateOf(0) }
    val context = LocalContext.current

    LaunchedEffect(sources, refreshKey) {
        loading = true
        homes = coroutineScope { sources.filter { it.isJellyfin }.map { async { loadJfHome(it) } }.awaitAll() }
        loading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("泛舟", fontWeight = FontWeight.Bold) },
                actions = {
                    val firstJf = sources.firstOrNull { it.isJellyfin }
                    if (firstJf != null) IconButton(onClick = { nav.navigate(Routes.search(firstJf.id)) }) { Icon(Icons.Filled.Search, "搜索") }
                    IconButton(onClick = { nav.navigate("sources") }) { Icon(Icons.Filled.Add, "添加") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Bg),
            )
        },
    ) { pad ->
        if (sources.isEmpty()) {
            EmptyHome(Modifier.padding(pad), onAddJf = { nav.navigate(Routes.editSource(Source.TYPE_JELLYFIN)) }, onAddDav = { nav.navigate("sources") })
            return@Scaffold
        }
        PullToRefreshBox(isRefreshing = loading && homes.isNotEmpty(), onRefresh = { refreshKey++ }, modifier = Modifier.padding(pad)) {
            if (loading && homes.isEmpty() && sources.any { it.isJellyfin }) { LoadingBox(); return@PullToRefreshBox }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                homes.forEach { h ->
                    val c = Repo.jellyfin(h.source)
                    if (h.error != null) {
                        item { Text(h.error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
                        return@forEach
                    }
                    if (h.resume.isNotEmpty()) {
                        item { SectionHeader("继续观看") }
                        item {
                            PosterRow(h.resume, width = 220) { it, m ->
                                LandscapeCard(
                                    title = if (it.type == "Episode") it.seriesName ?: it.name else it.name,
                                    subtitle = if (it.type == "Episode") "${it.episodeLabel} ${it.name}" else it.year?.toString(),
                                    imageUrl = c.thumb(it), modifier = m, progress = it.progress,
                                    onClick = { PlayerCore.start(context, listOf(c.playItem(it).copy(directResume = true)), 0) },
                                )
                            }
                        }
                    }
                    if (h.nextUp.isNotEmpty()) {
                        item { SectionHeader("接下来") }
                        item {
                            PosterRow(h.nextUp, width = 220) { it, m ->
                                LandscapeCard(
                                    title = it.seriesName ?: it.name, subtitle = "${it.episodeLabel ?: ""} ${it.name}",
                                    imageUrl = c.thumb(it), modifier = m,
                                    onClick = { nav.navigate(Routes.detail(h.source.id, it.seriesId ?: it.id)) },
                                )
                            }
                        }
                    }
                    if (h.favorites.isNotEmpty()) {
                        item { SectionHeader(if (homes.size > 1) "${h.source.name} · 喜爱视频" else "喜爱视频") }
                        item {
                            PosterRow(h.favorites) { it, m ->
                                PosterCard(
                                    title = if (it.type == "Episode") it.seriesName ?: it.name else it.name,
                                    subtitle = it.year?.toString(), imageUrl = c.seriesPoster(it), modifier = m,
                                    played = it.userData?.played == true,
                                    badge = it.userData?.unplayed?.takeIf { n -> n > 0 }?.toString(),
                                ) { nav.navigate(Routes.detail(h.source.id, it.id)) }
                            }
                        }
                    }
                    if (h.views.isNotEmpty()) {
                        item { SectionHeader(if (homes.size > 1) "${h.source.name} · 媒体库" else "媒体库") }
                        item {
                            PosterRow(h.views, width = 200) { it, m ->
                                LandscapeCard(it.name, null, c.primaryImage(it, 360), m) {
                                    nav.navigate(Routes.library(h.source.id, it.id, it.name))
                                }
                            }
                        }
                    }
                    h.latest.forEach { (lib, list) ->
                        item { SectionHeader("最新 · ${lib.name}", "全部") { nav.navigate(Routes.library(h.source.id, lib.id, lib.name)) } }
                        item {
                            PosterRow(list) { it, m ->
                                PosterCard(
                                    title = if (it.type == "Episode") it.seriesName ?: it.name else it.name,
                                    subtitle = it.year?.toString(), imageUrl = c.seriesPoster(it), modifier = m,
                                    played = it.userData?.played == true,
                                    badge = it.userData?.unplayed?.takeIf { n -> n > 0 }?.toString(),
                                ) { nav.navigate(Routes.detail(h.source.id, it.id)) }
                            }
                        }
                    }
                }
                val davs = sources.filter { !it.isJellyfin }
                if (davs.isNotEmpty()) {
                    item {
                        Box(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                            FoldHeader("网盘与文件共享", davs.size, davOpen) {
                                davOpen = !davOpen; homeUi.edit().putBoolean("home_dav_open", davOpen).apply()
                            }
                        }
                    }
                    if (davOpen) items(davs) { s ->
                        Surface(
                            color = Surface1, shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp).clip(RoundedCornerShape(10.dp)).clickable { nav.navigate(Routes.open(s)) },
                        ) {
                            Column(Modifier.padding(16.dp)) {
                                Text(s.name, fontWeight = FontWeight.SemiBold)
                                Text(when { s.isCloud -> s.typeLabel; s.isSmb -> "SMB · ${s.url}/${s.extra["share"] ?: ""}"; else -> s.url }, color = TextDim, fontSize = 12.sp, maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyHome(modifier: Modifier, onAddJf: () -> Unit, onAddDav: () -> Unit) {
    Column(modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("欢迎使用泛舟", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("添加一个媒体源，开始搭建你的海报墙", color = TextDim)
        Spacer(Modifier.height(28.dp))
        Button(onClick = onAddJf, modifier = Modifier.fillMaxWidth()) { Text("添加 Jellyfin / Emby 服务器") }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(onClick = onAddDav, modifier = Modifier.fillMaxWidth()) { Text("添加 SMB / WebDAV / 网盘") }
    }
}
