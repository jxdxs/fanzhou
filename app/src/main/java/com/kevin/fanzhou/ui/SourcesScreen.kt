package com.kevin.fanzhou.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.kevin.fanzhou.Routes
import com.kevin.fanzhou.data.*
import kotlinx.coroutines.launch
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourcesScreen(nav: NavHostController) {
    val sources by Repo.store.sources.collectAsState()
    var confirmDelete by remember { mutableStateOf<Source?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val ui = remember { context.getSharedPreferences("ui", android.content.Context.MODE_PRIVATE) }
    // Fold state per group, remembered across launches. Groups start open; "add" starts folded once any source exists.
    val open = remember { mutableStateMapOf<String, Boolean>().apply { listOf("server", "lan", "cloud").forEach { put(it, !ui.getBoolean("fold_$it", false)) } } }
    var addOpen by remember { mutableStateOf(!ui.getBoolean("fold_add", sources.isNotEmpty())) }
    val groups = listOf(
        Triple("server", "媒体服务器", sources.filter { it.isJellyfin }),
        Triple("lan", "局域网与网络存储", sources.filter { !it.isJellyfin && !it.isCloud }),
        Triple("cloud", "网盘", sources.filter { it.isCloud }),
    )
    Scaffold(topBar = { TopAppBar(title = { Text("媒体源") }, colors = TopAppBarDefaults.topAppBarColors(containerColor = Bg)) }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            groups.forEach { (key, label, list) ->
                if (list.isEmpty()) return@forEach
                item(key = "h_$key") {
                    FoldHeader(label, list.size, open[key] != false) { open[key] = open[key] == false; ui.edit().putBoolean("fold_$key", open[key] == false).apply() }
                }
                if (open[key] != false) items(list, key = { it.id }) { s ->
                    Surface(color = Surface1, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().clickable {
                        nav.navigate(Routes.open(s))
                    }) {
                        Row(Modifier.padding(start = 12.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            SourceBadge(s.type)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(s.name, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                val where = when {
                                    s.isSmb -> "smb://${s.url}/${s.extra["share"] ?: ""}"
                                    else -> s.url
                                }
                                Text(s.typeLabel + if (where.isNotEmpty()) " · $where" else "", fontSize = 12.sp, color = TextDim, maxLines = 1)
                            }
                            IconButton(onClick = { nav.navigate(Routes.editSource(s.type, s.id)) }) { Icon(Icons.Filled.Edit, if (s.isCloud) "重新登录" else "编辑") }
                            IconButton(onClick = { confirmDelete = s }) { Icon(Icons.Filled.Delete, "删除") }
                        }
                    }
                }
            }
            item(key = "h_add") {
                Spacer(Modifier.height(4.dp))
                FoldHeader("添加媒体源", null, addOpen) { addOpen = !addOpen; ui.edit().putBoolean("fold_add", !addOpen).apply() }
            }
            if (addOpen) item(key = "add") {
                Column {
                    Text("媒体服务器", color = TextDim, fontSize = 13.sp)
                    Spacer(Modifier.height(8.dp))
                    AddRow(listOf(Source.TYPE_JELLYFIN, Source.TYPE_EMBY, Source.TYPE_FNOS)) { nav.navigate(Routes.editSource(it)) }
                    Spacer(Modifier.height(16.dp))
                    Text("局域网与网络存储", color = TextDim, fontSize = 13.sp)
                    Spacer(Modifier.height(8.dp))
                    AddRow(listOf(Source.TYPE_SMB, Source.TYPE_WEBDAV)) { nav.navigate(Routes.editSource(it)) }
                    Spacer(Modifier.height(16.dp))
                    Text("网盘", color = TextDim, fontSize = 13.sp)
                    Spacer(Modifier.height(8.dp))
                    AddRow(listOf(Source.TYPE_115, Source.TYPE_QUARK, Source.TYPE_ALIYUN)) { nav.navigate(Routes.cloudLogin(it)) }
                    Spacer(Modifier.height(10.dp))
                    AddRow(listOf(Source.TYPE_123, Source.TYPE_GDRIVE)) { nav.navigate(Routes.cloudLogin(it)) }
                }
            }
        }
    }
    confirmDelete?.let { s ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("删除 ${s.name}？") },
            confirmButton = { TextButton(onClick = { Repo.store.remove(s.id); confirmDelete = null }) { Text("删除") } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun AddRow(types: List<String>, onClick: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        types.forEach { t ->
            Surface(
                color = Surface1, shape = RoundedCornerShape(12.dp),
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable { onClick(t) },
            ) {
                Column(Modifier.padding(vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    SourceBadge(t)
                    Spacer(Modifier.height(8.dp))
                    Text(Source(id = "", type = t, name = "", url = "").typeLabel, fontSize = 13.sp)
                }
            }
        }
        repeat(3 - types.size) { Spacer(Modifier.weight(1f)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditSourceScreen(nav: NavHostController, initialType: String, editId: String?) {
    val existing = remember(editId) { editId?.let { Repo.store.get(it) } }
    var type by remember { mutableStateOf(existing?.type ?: initialType) }
    val isServer = type == Source.TYPE_JELLYFIN || type == Source.TYPE_EMBY || type == Source.TYPE_FNOS
    val isSmb = type == Source.TYPE_SMB
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var url by remember { mutableStateOf(existing?.url ?: "") }
    var user by remember { mutableStateOf(existing?.username ?: "") }
    var pass by remember { mutableStateOf(existing?.password ?: "") }
    var port by remember { mutableStateOf(existing?.extra?.get("port") ?: "445") }
    var share by remember { mutableStateOf(existing?.extra?.get("share") ?: "") }
    var domain by remember { mutableStateOf(existing?.extra?.get("domain") ?: "") }
    var guest by remember { mutableStateOf(existing?.extra?.get("guest") == "1") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var discovered by remember { mutableStateOf<List<String>?>(null) }
    var scanning by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(when (type) { Source.TYPE_SMB -> "SMB 局域网共享"; Source.TYPE_WEBDAV -> "WebDAV"; Source.TYPE_EMBY -> "Emby"; Source.TYPE_FNOS -> "飞牛影视"; else -> "Jellyfin" }) },
            navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Bg),
        )
    }) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (isServer) {
                Text("服务器类型", fontSize = 13.sp, color = TextDim)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf(Source.TYPE_JELLYFIN to "Jellyfin", Source.TYPE_EMBY to "Emby", Source.TYPE_FNOS to "飞牛影视").forEachIndexed { i, (t, l) ->
                        SegmentedButton(selected = type == t, onClick = { type = t }, shape = SegmentedButtonDefaults.itemShape(i, 3)) { Text(l) }
                    }
                }
            }
            OutlinedTextField(name, { name = it }, label = { Text("名称（可选）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                url, { url = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                label = { Text(when { isServer -> "服务器地址"; isSmb -> "主机 / IP"; else -> "WebDAV 地址" }) },
                placeholder = { Text(when { type == Source.TYPE_FNOS -> "http://192.168.1.10:5666"; isServer -> "http://192.168.1.10:8096"; isSmb -> "192.168.1.10"; else -> "https://example.com:5005/dav/" }) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            if (isSmb) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(port, { port = it.filter(Char::isDigit) }, label = { Text("端口") }, singleLine = true, modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    OutlinedTextField(share, { share = it }, label = { Text("共享名") }, placeholder = { Text("例如 Movies") }, singleLine = true, modifier = Modifier.weight(2f))
                }
                Text("共享名即 \\\\主机\\共享名 中的文件夹名，可在 NAS 或电脑的共享设置里看到。", fontSize = 12.sp, color = TextDim)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("匿名 / 访客登录", modifier = Modifier.weight(1f))
                    Switch(guest, { guest = it })
                }
            }
            if (!(isSmb && guest)) {
                OutlinedTextField(user, { user = it }, label = { Text("用户名") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (type == Source.TYPE_FNOS) Text("填飞牛影视的账号密码（影视里的用户，不是 NAS 系统账号）。地址填 NAS 的 IP:5666，或远程访问域名。", fontSize = 12.sp, color = TextDim)
                OutlinedTextField(
                    pass, { pass = it }, label = { Text("密码") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
            }
            if (isSmb && !guest) {
                OutlinedTextField(domain, { domain = it }, label = { Text("域（可选）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
            Button(
                enabled = !busy && url.isNotBlank() && (!isServer || user.isNotBlank()) && (!isSmb || share.isNotBlank()),
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    busy = true; error = null
                    scope.launch {
                        try {
                            val id = existing?.id ?: UUID.randomUUID().toString()
                            var s: Source
                            if (isSmb) {
                                val host = url.trim().removePrefix("smb://").trim('/', '\\', ' ').substringBefore('/')
                                s = Source(
                                    id = id, type = type, name = name.ifBlank { "$host/${share.trim('/', '\\')}" }, url = host,
                                    username = if (guest) "" else user.trim(), password = if (guest) "" else pass,
                                    extra = mapOf("port" to port.ifBlank { "445" }, "share" to share.trim('/', '\\', ' '), "domain" to domain.trim(), "guest" to if (guest) "1" else "0"),
                                )
                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { SmbClient(s).also { it.test(); it.close() } }
                            } else {
                                val normalized = JellyfinClient.normalizeUrl(url)
                                s = Source(
                                    id = id, type = type,
                                    name = name.ifBlank { java.net.URI(normalized).host ?: normalized },
                                    url = if (isServer) normalized else normalized + "/",
                                    username = user.trim(), password = pass,
                                )
                                if (isServer) s = JellyfinClient(s).login() else WebDavClient(s).test()
                            }
                            Repo.store.upsert(s)
                            nav.popBackStack()
                        } catch (e: Exception) {
                            error = "连接失败：${e.message ?: e.javaClass.simpleName}"
                        } finally { busy = false }
                    }
                },
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("连接并保存")
            }
            if (isSmb) {
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("可用的 SMB 服务器", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    TextButton(enabled = !scanning, onClick = {
                        scanning = true
                        scope.launch { discovered = SmbDiscovery.scan(); scanning = false }
                    }) { Text(if (scanning) "扫描中…" else "扫描局域网") }
                }
                if (scanning) LinearProgressIndicator(Modifier.fillMaxWidth())
                discovered?.let { list ->
                    if (list.isEmpty()) Text("没有发现开放 445 端口的设备", fontSize = 13.sp, color = TextDim)
                    list.forEach { h ->
                        Surface(color = Surface1, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth().clickable { url = h.substringBefore(' ') }) {
                            Text(h, modifier = Modifier.padding(14.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun FoldHeader(title: String, count: Int?, expanded: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = onToggle).padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        if (count != null) Text("  $count", color = TextDim, fontSize = 13.sp)
        Spacer(Modifier.weight(1f))
        Icon(if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, if (expanded) "收起" else "展开", tint = TextDim)
    }
}
