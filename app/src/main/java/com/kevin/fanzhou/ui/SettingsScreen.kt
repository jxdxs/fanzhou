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
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val store = Repo.store
    val context = LocalContext.current
    var tmdb by remember { mutableStateOf(store.tmdbKey) }
    var assrtT by remember { mutableStateOf(store.assrtToken) }
    var subdlK by remember { mutableStateOf(store.subdlKey) }
    var osK by remember { mutableStateOf(store.osApiKey) }
    var subTesting by remember { mutableStateOf(false) }
    var subTest by remember { mutableStateOf("") }
    val subScope = rememberCoroutineScope()
    var speed by remember { mutableFloatStateOf(store.defaultSpeed) }
    var step by remember { mutableIntStateOf(store.seekStepSec) }
    var introSkip by remember { mutableIntStateOf(store.introSkipSec) }
    var outroSkip by remember { mutableIntStateOf(store.outroSkipSec) }
    var subScale by remember { mutableFloatStateOf(store.subtitleScale) }
    var subColor by remember { mutableStateOf(store.subtitleColor) }
    var subStyle by remember { mutableStateOf(store.subtitleStyle) }
    var subMargin by remember { mutableIntStateOf(store.subtitleBottomMargin) }
    var voiceEnhance by remember { mutableStateOf(store.voiceEnhance) }
    var volumeBoost by remember { mutableFloatStateOf(store.volumeBoost) }
    var autoNext by remember { mutableStateOf(store.autoPlayNext) }
    var bufferMb by remember { mutableIntStateOf(store.bufferSizeMb) }
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
            val introText = if (introSkip == 0) "片头关" else "片头 ${introSkip} 秒"
            val outroText = if (outroSkip == 0) "片尾关" else "片尾 ${outroSkip} 秒"
            Group("播放", "缓存 ${bufferMb}MB · ${"%.2f".format(speed).trimEnd('0').trimEnd('.')}× · 快进 ${step} 秒 · $introText · $outroText" + if (autoNext) " · 自动下一集" else "") {
                Text("默认倍速：${"%.2f".format(speed).trimEnd('0').trimEnd('.')}×", fontSize = 14.sp)
                Slider(speed, { speed = (Math.round(it * 4) / 4f); store.defaultSpeed = speed }, valueRange = 0.5f..3f, steps = 9)
                Text("双击快进/快退：${step} 秒", fontSize = 14.sp)
                Slider(step.toFloat(), { step = it.toInt(); store.seekStepSec = step }, valueRange = 5f..60f, steps = 10)
                Text("跳过片头：${if (introSkip == 0) "关闭" else "${introSkip} 秒"}", fontSize = 14.sp)
                Slider(introSkip.toFloat(), { introSkip = (Math.round(it / 5) * 5).toInt(); store.introSkipSec = introSkip }, valueRange = 0f..180f, steps = 35)
                Text("跳过片尾：${if (outroSkip == 0) "关闭" else "${outroSkip} 秒"}", fontSize = 14.sp)
                Slider(outroSkip.toFloat(), { outroSkip = (Math.round(it / 5) * 5).toInt(); store.outroSkipSec = outroSkip }, valueRange = 0f..300f, steps = 59)
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Text("播放缓存加载大小：${bufferMb} MB", fontSize = 14.sp)
                Text("增大缓存可抵抗弱网抖动与云盘限速波动；推荐 64MB ~ 128MB。若设备内存紧张，系统将自适应限制安全缓冲，防止内存溢出退出", fontSize = 12.sp, color = TextDim)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(32 to "32M", 64 to "64M", 128 to "128M", 256 to "256M", 512 to "512M").forEach { (mb, name) ->
                        FilterChip(selected = bufferMb == mb, onClick = { bufferMb = mb; store.bufferSizeMb = mb }, label = { Text(name) })
                    }
                }
                Slider(bufferMb.toFloat(), { bufferMb = (Math.round(it / 32) * 32).toInt().coerceIn(32, 512); store.bufferSizeMb = bufferMb }, valueRange = 32f..512f, steps = 14)
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Text("字幕大小：${"%.2f".format(subScale).trimEnd('0').trimEnd('.')}×", fontSize = 14.sp)
                Slider(subScale, { subScale = (Math.round(it * 20) / 20f); store.subtitleScale = subScale }, valueRange = 0.8f..2.2f, steps = 13)
                Text("字幕颜色：${when(subColor) { "yellow" -> "电影黄"; "cyan" -> "清爽青"; else -> "经典白" }}", fontSize = 14.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("white" to "经典白", "yellow" to "电影黄", "cyan" to "清爽青").forEach { (k, name) ->
                        FilterChip(selected = subColor == k, onClick = { subColor = k; store.subtitleColor = k }, label = { Text(name) })
                    }
                }
                Text("字幕特效：${when(subStyle) { "shadow" -> "立体投影"; "box" -> "半透明底色"; else -> "经典描边" }}", fontSize = 14.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("outline" to "经典描边", "shadow" to "立体投影", "box" to "半透明底色").forEach { (k, name) ->
                        FilterChip(selected = subStyle == k, onClick = { subStyle = k; store.subtitleStyle = k }, label = { Text(name) })
                    }
                }
                Text("字幕底部边距：${subMargin}dp", fontSize = 14.sp)
                Slider(subMargin.toFloat(), { subMargin = it.toInt(); store.subtitleBottomMargin = subMargin }, valueRange = 10f..60f, steps = 10)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("夜间人声对白增强", fontSize = 14.sp)
                        Text("压缩音效动态，抑制背景爆破轰鸣，突显人声", fontSize = 12.sp, color = TextDim)
                    }
                    Switch(voiceEnhance, { voiceEnhance = it; store.voiceEnhance = it })
                }
                Text("音量软放大（超出最大音量增益）：${(volumeBoost * 100).toInt()}%", fontSize = 14.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1.0f to "100%(标准)", 1.25f to "125%", 1.5f to "150%", 2.0f to "200%").forEach { (v, name) ->
                        FilterChip(selected = kotlin.math.abs(volumeBoost - v) < 0.05f, onClick = { volumeBoost = v; store.volumeBoost = v }, label = { Text(name) })
                    }
                }
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
            Group("在线字幕", listOf(store.assrtToken, store.subdlKey, store.osApiKey).count { it.isNotBlank() }.let { if (it == 0) "未设置" else "已接入 $it 家" }) {
                Text("播放没有字幕的资源时，可在字幕菜单里点「在线搜索字幕」。支持配置以下字幕源（按需填入任意一家即可）：", fontSize = 12.sp, color = TextDim)
                OutlinedTextField(assrtT, { assrtT = it }, label = { Text("射手网(伪) assrt Token（推荐，中文字幕最全）") }, placeholder = { Text("可在 assrt.net 免费注册获取") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(subdlK, { subdlK = it }, label = { Text("SubDL API Key（多语言字幕）") }, placeholder = { Text("可在 subdl.com 获取") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(osK, { osK = it }, label = { Text("OpenSubtitles API Key（全球字幕库）") }, placeholder = { Text("可在 opensubtitles.com 获取") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("说明：\n• 射手网(伪)：国内中文字幕最全面，个人使用无严格按天限制。\n• SubDL：支持电影与剧集，每日提供免费配额。\n• OpenSubtitles：支持全球多语言，每日免费提供下载额度。\n• 下载成功的字幕将缓存在本机，下次播放同一视频将自动加载。", fontSize = 12.sp, color = TextDim)
                Row {
                    Button(onClick = { store.assrtToken = assrtT; store.subdlKey = subdlK; store.osApiKey = osK; Toast.makeText(context, "已保存", Toast.LENGTH_SHORT).show() }) { Text("保存") }
                    Spacer(Modifier.width(10.dp))
                    OutlinedButton(enabled = !subTesting, onClick = {
                        // Test what is currently typed, even before 保存.
                        store.assrtToken = assrtT; store.subdlKey = subdlK; store.osApiKey = osK
                        subTesting = true; subTest = ""
                        subScope.launch {
                            val lines = runCatching { com.kevin.fanzhou.subtitle.OnlineSubtitles.testAll() }
                                .getOrElse { listOf("测试失败：${it.message ?: "网络错误"}") }
                            subTest = lines.joinToString("\n")
                            subTesting = false
                        }
                    }) { Text(if (subTesting) "测试中…" else "测试连接") }
                }
                if (subTest.isNotEmpty()) Text(subTest, fontSize = 12.sp, color = TextDim, lineHeight = 18.sp)
            }
            BackupSettings { t, sum, c -> Group(t, sum, c) }
            Group("手势说明", "单击、双击、滑动、长按") {
                Text("单击：显示/隐藏控制栏\n双击左/右侧：快退/快进\n双击中间：暂停/播放\n左右滑动：拖动进度\n左侧上下滑：亮度　右侧上下滑：音量\n长按：2 倍速快进，松手恢复", fontSize = 13.sp, color = TextDim, lineHeight = 20.sp)
            }
            Text("泛舟 " + (try { context.packageManager.getPackageInfo(context.packageName, 0).versionName } catch (e: Exception) { null } ?: ""), color = TextDim, fontSize = 12.sp)
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
