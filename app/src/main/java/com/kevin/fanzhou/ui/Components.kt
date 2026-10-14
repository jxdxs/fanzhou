package com.kevin.fanzhou.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

@Composable
fun SectionHeader(title: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 18.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        if (action != null && onAction != null) TextButton(onClick = onAction) { Text(action) }
    }
}

@Composable
fun ImageBox(url: String?, label: String, modifier: Modifier, contentScale: ContentScale = ContentScale.Crop) {
    Box(modifier.background(Surface2), contentAlignment = Alignment.Center) {
        if (url == null) {
            Text(label.take(8), color = TextDim, fontSize = 13.sp, modifier = Modifier.padding(6.dp), maxLines = 3)
        } else {
            AsyncImage(model = url, contentDescription = label, contentScale = contentScale, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
fun PosterCard(
    title: String, subtitle: String?, imageUrl: String?, modifier: Modifier = Modifier,
    progress: Float = 0f, played: Boolean = false, badge: String? = null, onLongClick: (() -> Unit)? = null, onClick: () -> Unit,
) {
    Column(modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp))) {
            ImageBox(imageUrl, title, Modifier.fillMaxSize())
            if (played) CornerBadge("✓", Modifier.align(Alignment.TopEnd))
            else if (badge != null) CornerBadge(badge, Modifier.align(Alignment.TopEnd))
            if (progress > 0.01f) ProgressStrip(progress, Modifier.align(Alignment.BottomCenter))
        }
        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
        if (!subtitle.isNullOrEmpty()) Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 11.sp, color = TextDim)
    }
}

@Composable
fun LandscapeCard(
    title: String, subtitle: String?, imageUrl: String?, modifier: Modifier = Modifier,
    progress: Float = 0f, onClick: () -> Unit,
) {
    Column(modifier.clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp))) {
            ImageBox(imageUrl, title, Modifier.fillMaxSize())
            if (progress > 0.01f) ProgressStrip(progress, Modifier.align(Alignment.BottomCenter))
        }
        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
        if (!subtitle.isNullOrEmpty()) Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 11.sp, color = TextDim)
    }
}

@Composable
fun CornerBadge(text: String, modifier: Modifier) {
    Box(
        modifier.padding(5.dp).clip(RoundedCornerShape(10.dp)).background(Accent).padding(horizontal = 6.dp, vertical = 1.dp)
    ) { Text(text, fontSize = 10.sp, color = Color.White, fontWeight = FontWeight.Bold) }
}

@Composable
fun ProgressStrip(progress: Float, modifier: Modifier) {
    Box(modifier.fillMaxWidth().height(4.dp).background(Color(0x66000000))) {
        Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(Accent))
    }
}

@Composable
fun <T> PosterRow(items: List<T>, width: Int = 110, content: @Composable (T, Modifier) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(items) { content(it, Modifier.width(width.dp)) }
    }
}

@Composable
fun LoadingBox(modifier: Modifier = Modifier.fillMaxSize()) {
    Box(modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@Composable
fun ErrorBox(message: String, onRetry: (() -> Unit)? = null, modifier: Modifier = Modifier.fillMaxSize()) {
    Column(modifier.padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(message, color = TextDim)
        if (onRetry != null) {
            Spacer(Modifier.height(12.dp))
            Button(onClick = onRetry) { Text("重试") }
        }
    }
}

val ScrimBrush = Brush.verticalGradient(listOf(Color.Transparent, Bg))

fun ticksToText(ticks: Long?): String? {
    val t = ticks ?: return null
    val min = t / 10_000_000 / 60
    return if (min >= 60) "${min / 60}小时${min % 60}分" else "${min}分钟"
}

/** Round colored badge identifying a source type (same style for every source). */
@Composable
fun SourceBadge(type: String, size: Int = 40) {
    val (label, color) = when (type) {
        com.kevin.fanzhou.data.Source.TYPE_JELLYFIN -> "JF" to Color(0xFF7B5CD6)
        com.kevin.fanzhou.data.Source.TYPE_EMBY -> "EM" to Color(0xFF3AA757)
        com.kevin.fanzhou.data.Source.TYPE_FNOS -> "飞牛" to Color(0xFF1F6FEB)
        com.kevin.fanzhou.data.Source.TYPE_WEBDAV -> "DAV" to Color(0xFF2F80ED)
        com.kevin.fanzhou.data.Source.TYPE_SMB -> "SMB" to Color(0xFFE08A1E)
        com.kevin.fanzhou.data.Source.TYPE_115 -> "115" to Color(0xFF2777F8)
        com.kevin.fanzhou.data.Source.TYPE_QUARK -> "夸克" to Color(0xFF5B5FEF)
        com.kevin.fanzhou.data.Source.TYPE_ALIYUN -> "阿里" to Color(0xFFFF6A00)
        com.kevin.fanzhou.data.Source.TYPE_123 -> "123" to Color(0xFF007AFF)
        com.kevin.fanzhou.data.Source.TYPE_GDRIVE -> "G" to Color(0xFF1FA463)
        else -> "?" to Color.Gray
    }
    Box(
        Modifier.size(size.dp).clip(androidx.compose.foundation.shape.CircleShape).background(color),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = (if (label.length >= 3) size * 0.28f else size * 0.34f).sp, maxLines = 1)
    }
}
