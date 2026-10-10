package com.kevin.fanzhou.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.animation.animateContentSize
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.widget.Toast
import com.kevin.fanzhou.data.Repo

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val store = Repo.store
    val context = LocalContext.current
    var tmdb by remember { mutableStateOf(store.tmdbKey) }
    var speed by remember { mutableFloatStateOf(store.defaultSpeed) }
    var step by remember { mutableIntStateOf(store.seekStepSec) }
    var autoNext by remember { mutableStateOf(store.autoPlayNext) }
    var gridMin by remember { mutableIntStateOf(store.gridColumnsMin) }
    var core by remember { mutableStateOf(store.playerCore) }
    var hwdec by remember { mutableStateOf(store.mpvHwdec) }
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    @Composable fun Group(title: String, summary: String = "", content: @Composable ColumnScope.() -> Unit) =
        FoldGroup(title, summary, open == title, { open = if (open == title) null else title }, content)
    val coreName = when (core) { com.kevin.fanzhou.data.CORE_EXO -> "ExoPlayer"; com.kevin.fanzhou.data.CORE_MPV -> "MPV"; else -> "自动" }

    Scaffold(topBar = { TopAppBar(title = { Text("设置") }, colors = TopAppBarDefaults.topAppBarColors(containerColor = Bg)) }) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Group("播放内核", coreName + if (hwdec) " · 硬解" else " · 软解") {
                Text("ExoPlayer 省电、启动快，已内置 FFmpeg 音频解码（DTS、TrueHD、AC3/EAC3 等）；MPV 兼容性最强，可播放 ISO、RMVB、WMV 等格式。播放时也可以点右上角的切换图标临时换内核。", fontSize = 12.sp, color = TextDim)
                listOf(
                    com.kevin.fanzhou.data.CORE_AUTO to "自动（默认 ExoPlayer，无法播放时自动切换 MPV）",
                    com.kevin.fanzhou.data.CORE_EXO to "ExoPlayer",
                    com.kevin.fanzhou.data.CORE_MPV to "MPV",
                ).forEach { (k, label) ->
                    Row(
                        Modifier.fillMaxWidth().clickable { core = k; store.playerCore = k },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = core == k, onClick = { core = k; store.playerCore = k })
                        Text(label, fontSize = 14.sp)
                    }
                }
                if (!com.kevin.fanzhou.player.PlayerCore.mpvAvailable) Text("此设备无法加载 MPV 内核，将始终使用 ExoPlayer。", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("MPV 硬件解码")
                        Text("关闭后使用软件解码，兼容性更好但更耗电", fontSize = 12.sp, color = TextDim)
                    }
                    Switch(hwdec, { hwdec = it; store.mpvHwdec = it })
                }
            }
            Group("播放", "${"%.2f".format(speed).trimEnd('0').trimEnd('.')}× · 快进 ${step} 秒" + if (autoNext) " · 自动下一集" else "") {
                Text("默认倍速：${"%.2f".format(speed).trimEnd('0').trimEnd('.')}×", fontSize = 14.sp)
                Slider(speed, { speed = (Math.round(it * 4) / 4f); store.defaultSpeed = speed }, valueRange = 0.5f..3f, steps = 9)
                Text("双击快进/快退：${step} 秒", fontSize = 14.sp)
                Slider(step.toFloat(), { step = it.toInt(); store.seekStepSec = step }, valueRange = 5f..60f, steps = 10)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("自动播放下一集", modifier = Modifier.weight(1f))
                    Switch(autoNext, { autoNext = it; store.autoPlayNext = it })
                }
            }
            Group("海报墙", "海报 ${gridMin}dp") {
                Text("海报大小：${gridMin}dp", fontSize = 14.sp)
                Slider(gridMin.toFloat(), { gridMin = it.toInt(); store.gridColumnsMin = gridMin }, valueRange = 90f..180f)
            }
            Group("TMDB 刮削", if (store.tmdbKey.isBlank()) "未设置" else "已设置") {
                Text("填入 TMDB API Key（v3）或读访问令牌，WebDAV、SMB 和网盘文件夹就会按文件名自动匹配海报。Jellyfin / Emby 自带海报，无需填写。", fontSize = 12.sp, color = TextDim)
                OutlinedTextField(tmdb, { tmdb = it }, label = { Text("TMDB API Key") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row {
                    Button(onClick = { store.tmdbKey = tmdb; Toast.makeText(context, "已保存", Toast.LENGTH_SHORT).show() }) { Text("保存") }
                    Spacer(Modifier.width(10.dp))
                    OutlinedButton(onClick = { store.clearTmdbCache(); Toast.makeText(context, "已清除海报缓存", Toast.LENGTH_SHORT).show() }) { Text("清除匹配缓存") }
                }
            }
            BackupSettings { t, sum, c -> Group(t, sum, c) }
            Group("手势说明", "单击、双击、滑动、长按") {
                Text("单击：显示/隐藏控制栏\n双击左/右侧：快退/快进\n双击中间：暂停/播放\n左右滑动：拖动进度\n左侧上下滑：亮度　右侧上下滑：音量\n长按：2 倍速快进，松手恢复", fontSize = 13.sp, color = TextDim, lineHeight = 20.sp)
            }
            Text("泛舟 2.3.4", color = TextDim, fontSize = 12.sp)
        }
    }
}

@Composable
private fun FoldGroup(title: String, summary: String, expanded: Boolean, onToggle: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Surface(color = Surface1, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.fillMaxWidth().animateContentSize()) {
            Row(Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, fontWeight = FontWeight.Bold)
                    if (summary.isNotBlank() && !expanded) Text(summary, fontSize = 12.sp, color = TextDim, maxLines = 1)
                }
                Text(if (expanded) "⌃" else "⌄", color = TextDim, fontSize = 18.sp, modifier = Modifier.rotate(0f))
            }
            if (expanded) Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
        }
    }
}
