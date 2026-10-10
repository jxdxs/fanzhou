package com.kevin.fanzhou.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavHostController
import com.kevin.fanzhou.Routes
import com.kevin.fanzhou.data.*
import com.kevin.fanzhou.player.PlayerCore
import kotlinx.coroutines.launch

@Composable
fun DetailScreen(nav: NavHostController, sid: String, itemId: String) {
    val client = remember(sid) { Repo.jellyfin(sid) } ?: return ErrorBox("媒体源不存在")
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var item by remember { mutableStateOf<JfItem?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var seasons by remember { mutableStateOf<List<JfItem>>(emptyList()) }
    var seasonIdx by remember { mutableIntStateOf(0) }
    var episodes by remember { mutableStateOf<List<JfItem>>(emptyList()) }
    var similar by remember { mutableStateOf<List<JfItem>>(emptyList()) }
    var tick by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current

    // Reload whenever we come back from the player so progress stays fresh.
    LaunchedEffect(itemId) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            try {
                val it = client.item(itemId)
                item = it
                if (it.type == "Series") {
                    val s = client.seasons(it.id)
                    if (seasons.isEmpty()) {
                        val firstUnwatched = s.indexOfFirst { se -> (se.userData?.unplayed ?: 0) > 0 }
                        seasonIdx = if (firstUnwatched >= 0) firstUnwatched else 0
                    }
                    seasons = s
                } else if (it.type == "Episode" && it.seriesId != null && it.seasonId != null) {
                    episodes = client.episodes(it.seriesId, it.seasonId)
                }
                if (similar.isEmpty()) similar = client.similar(it.seriesId ?: it.id)
                tick++
            } catch (e: Exception) { error = e.message }
        }
    }
    LaunchedEffect(seasons, seasonIdx, tick) {
        val it = item ?: return@LaunchedEffect
        if (it.type == "Series") seasons.getOrNull(seasonIdx)?.let { s -> episodes = runCatching { client.episodes(it.id, s.id) }.getOrDefault(emptyList()) }
    }

    val cur = item
    if (cur == null) {
        if (error != null) ErrorBox("加载失败：$error") else LoadingBox()
        return
    }

    fun playEpisodes(list: List<JfItem>, index: Int) =
        PlayerCore.start(context, list.mapIndexed { i, e -> client.playItem(e, start = i == index) }, index)

    LazyColumn(Modifier.fillMaxSize().background(Bg), contentPadding = PaddingValues(bottom = 32.dp)) {
        item {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 10f)) {
                ImageBox(client.backdrop(cur) ?: client.primaryImage(cur, 900), cur.name, Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(ScrimBrush))
                IconButton(
                    onClick = { nav.popBackStack() },
                    modifier = Modifier.statusBarsPadding().padding(8.dp).clip(RoundedCornerShape(50)).background(Color(0x66000000)),
                ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
            }
        }
        item {
            Row(Modifier.padding(horizontal = 16.dp).offset(y = (-60).dp)) {
                ImageBox(client.seriesPoster(cur), cur.name, Modifier.width(110.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp)))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f).padding(top = 48.dp)) {
                    if (cur.type == "Episode") Text(cur.seriesName ?: "", color = TextDim, fontSize = 13.sp)
                    Text(
                        if (cur.type == "Episode") "${cur.episodeLabel} ${cur.name}" else cur.name,
                        fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 3, overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                    val meta = listOfNotNull(
                        cur.year?.toString(), cur.rating?.let { "★ %.1f".format(it) }, cur.officialRating, ticksToText(cur.runTimeTicks),
                    ).joinToString("  ·  ")
                    Text(meta, color = TextDim, fontSize = 12.sp)
                    if (cur.genres.isNotEmpty()) Text(cur.genres.take(4).joinToString(" / "), color = TextDim, fontSize = 12.sp)
                }
            }
        }
        item {
            Row(Modifier.padding(horizontal = 16.dp).offset(y = (-44).dp), verticalAlignment = Alignment.CenterVertically) {
                val resumeMs = (cur.userData?.positionTicks ?: 0) / 10_000
                Button(
                    onClick = {
                        when (cur.type) {
                            "Series" -> {
                                val idx = episodes.indexOfFirst { it.userData?.played != true }.let { if (it < 0) 0 else it }
                                if (episodes.isNotEmpty()) playEpisodes(episodes, idx)
                            }
                            "Episode" -> {
                                val idx = episodes.indexOfFirst { it.id == cur.id }
                                if (idx >= 0) playEpisodes(episodes, idx) else PlayerCore.start(context, listOf(client.playItem(cur)), 0)
                            }
                            else -> PlayerCore.start(context, listOf(client.playItem(cur)), 0)
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.PlayArrow, null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (resumeMs > 30_000) "继续播放 ${fmtMs(resumeMs)}" else "播放")
                }
                if (cur.type != "Series" && resumeMs > 30_000) {
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { PlayerCore.start(context, listOf(client.playItem(cur, start = false)), 0) }) { Text("从头") }
                }
                IconButton(onClick = {
                    scope.launch { runCatching { client.setPlayed(cur.id, cur.userData?.played != true) }; item = runCatching { client.item(itemId) }.getOrDefault(cur) }
                }) { Icon(Icons.Filled.Check, "已看", tint = if (cur.userData?.played == true) Accent else TextDim) }
                IconButton(onClick = {
                    scope.launch { runCatching { client.setFavorite(cur.id, cur.userData?.favorite != true) }; item = runCatching { client.item(itemId) }.getOrDefault(cur) }
                }) {
                    Icon(if (cur.userData?.favorite == true) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, "收藏",
                        tint = if (cur.userData?.favorite == true) Color(0xFFFF5A7A) else TextDim)
                }
            }
        }
        if (!cur.overview.isNullOrBlank()) item {
            var expanded by remember { mutableStateOf(false) }
            Text(
                cur.overview, color = Color(0xFFCDD3DC), fontSize = 14.sp, lineHeight = 21.sp,
                maxLines = if (expanded) Int.MAX_VALUE else 4, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 16.dp).offset(y = (-32).dp).clickable { expanded = !expanded },
            )
        }
        cur.mediaSources.firstOrNull()?.let { ms ->
            item {
                val v = ms.streams.firstOrNull { it.type == "Video" }?.displayTitle
                val a = ms.streams.filter { it.type == "Audio" }.mapNotNull { it.displayTitle }.take(2)
                val s = ms.streams.count { it.type == "Subtitle" }
                val info = listOfNotNull(ms.container?.uppercase(), v, a.joinToString(" | ").ifEmpty { null }, if (s > 0) "字幕 $s" else null)
                Text(info.joinToString("  ·  "), color = TextDim, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 16.dp).offset(y = (-24).dp))
            }
        }
        if (cur.type == "Series" && seasons.isNotEmpty()) {
            item {
                ScrollableTabRow(selectedTabIndex = seasonIdx.coerceIn(0, seasons.size - 1), edgePadding = 16.dp, containerColor = Bg) {
                    seasons.forEachIndexed { i, s -> Tab(selected = i == seasonIdx, onClick = { seasonIdx = i }, text = { Text(s.name) }) }
                }
            }
        }
        if (episodes.isNotEmpty() && (cur.type == "Series" || cur.type == "Episode")) {
            if (cur.type == "Episode") item { SectionHeader("本季剧集") }
            itemsIndexed(episodes, key = { _, e -> e.id }) { i, e ->
                EpisodeRow(e, client, highlight = e.id == cur.id) { playEpisodes(episodes, i) }
            }
        }
        val cast = cur.people.filter { it.type == "Actor" }.take(15)
        if (cast.isNotEmpty()) {
            item { SectionHeader("演员") }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(cast) { p ->
                        Column(Modifier.width(80.dp)) {
                            Text(p.name, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(p.role ?: "", fontSize = 11.sp, color = TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
        if (similar.isNotEmpty()) {
            item { SectionHeader("相似推荐") }
            item {
                PosterRow(similar) { s, m ->
                    PosterCard(s.name, s.year?.toString(), client.primaryImage(s), m) { nav.navigate(Routes.detail(sid, s.id)) }
                }
            }
        }
    }
}

@Composable
private fun EpisodeRow(e: JfItem, client: JellyfinClient, highlight: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick)
            .background(if (highlight) Surface1 else Color.Transparent)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Box(Modifier.width(150.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(6.dp))) {
            ImageBox(client.thumb(e, 400), e.name, Modifier.fillMaxSize())
            if (e.userData?.played == true) CornerBadge("✓", Modifier.align(Alignment.TopEnd))
            if (e.progress > 0.01f) ProgressStrip(e.progress, Modifier.align(Alignment.BottomCenter))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("${e.indexNumber ?: ""}. ${e.name}", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            ticksToText(e.runTimeTicks)?.let { Text(it, fontSize = 11.sp, color = TextDim) }
            if (!e.overview.isNullOrBlank()) Text(e.overview, fontSize = 12.sp, color = TextDim, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

fun fmtMs(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%02d:%02d".format(m, sec)
}
