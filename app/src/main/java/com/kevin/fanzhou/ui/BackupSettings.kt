package com.kevin.fanzhou.ui

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kevin.fanzhou.data.ConfigSync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun BackupSettings(group: @Composable (String, String, @Composable ColumnScope.() -> Unit) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf(ConfigSync.davUrl) }
    var user by remember { mutableStateOf(ConfigSync.davUser) }
    var pass by remember { mutableStateOf(ConfigSync.davPass) }
    var dir by remember { mutableStateOf(ConfigSync.davDir) }
    var auto by remember { mutableStateOf(ConfigSync.autoSync) }
    var busy by remember { mutableStateOf(false) }
    var last by remember { mutableLongStateOf(ConfigSync.lastSync) }
    var pendingImport by remember { mutableStateOf<String?>(null) }
    var confirmRestore by remember { mutableStateOf(false) }
    fun toast(s: String) = Toast.makeText(context, s, Toast.LENGTH_SHORT).show()

    DisposableEffect(Unit) {
        ConfigSync.onApplied = { (context as? Activity)?.recreate() }
        onDispose { ConfigSync.onApplied = null }
    }

    fun saveDav() { ConfigSync.davUrl = url; ConfigSync.davUser = user; ConfigSync.davPass = pass; ConfigSync.davDir = dir }
    fun run(label: String, block: () -> String) {
        if (busy) return
        saveDav(); busy = true
        scope.launch {
            val msg = withContext(Dispatchers.IO) { runCatching(block).fold({ it }, { "${label}失败：${it.message}" }) }
            busy = false; last = ConfigSync.lastSync; toast(msg)
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openOutputStream(uri)!!.use { it.write(ConfigSync.export().toByteArray()) } }.isSuccess
            }
            toast(if (ok) "已导出" else "导出失败")
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() } }.getOrNull()
            }
            if (text == null) toast("读取文件失败") else pendingImport = text
        }
    }

    val sum = when {
        auto -> "WebDAV 自动同步已开"
        last > 0 -> "上次同步 " + SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(last))
        else -> "导出、导入、WebDAV"
    }
    group("配置备份与同步", sum) {
        Text("包含媒体源、网盘登录、播放进度和各项偏好。文件里有登录凭据，请勿发给他人。", fontSize = 12.sp, color = TextDim)
        Row {
            OutlinedButton(onClick = {
                val day = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
                exportLauncher.launch("fanzhou-config-$day.json")
            }) { Text("导出到文件") }
            Spacer(Modifier.width(10.dp))
            OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream", "*/*")) }) { Text("从文件导入") }
        }
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        Text("WebDAV 同步", fontSize = 14.sp)
        OutlinedTextField(url, { url = it }, label = { Text("服务器地址") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(user, { user = it }, label = { Text("用户名") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(pass, { pass = it }, label = { Text("密码") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(dir, { dir = it }, label = { Text("备份目录") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Text("文件名：${ConfigSync.FILE_NAME}", fontSize = 12.sp, color = TextDim)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("自动同步")
                Text("打开 App 时拉取较新的配置，本机改动后自动上传", fontSize = 12.sp, color = TextDim)
            }
            Switch(auto, { auto = it; saveDav(); ConfigSync.autoSync = it; if (it) run("同步") { ConfigSync.syncNow() } })
        }
        Row {
            Button(onClick = { run("同步") { ConfigSync.syncNow() } }, enabled = !busy) { Text("立即同步") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { run("上传") { ConfigSync.upload(); "已上传" } }, enabled = !busy) { Text("上传") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { confirmRestore = true }, enabled = !busy) { Text("恢复") }
        }
        Text(
            if (busy) "处理中…" else if (last > 0) "上次同步：" + SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(last)) else "尚未同步",
            fontSize = 12.sp, color = TextDim,
        )
    }

    pendingImport?.let { text ->
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            title = { Text("导入配置？") },
            text = { Text("将用文件里的配置替换本机的媒体源、登录和偏好。本机已有的播放进度会保留。") },
            confirmButton = {
                TextButton(onClick = {
                    pendingImport = null
                    runCatching { ConfigSync.import(text) }.onSuccess { toast("已导入") }.onFailure { toast("导入失败：${it.message}") }
                }) { Text("导入") }
            },
            dismissButton = { TextButton(onClick = { pendingImport = null }) { Text("取消") } },
        )
    }
    if (confirmRestore) {
        AlertDialog(
            onDismissRequest = { confirmRestore = false },
            title = { Text("从 WebDAV 恢复？") },
            text = { Text("将用服务器上的备份替换本机的媒体源、登录和偏好。本机已有的播放进度会保留。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRestore = false
                    run("恢复") { if (ConfigSync.restore()) "已恢复" else "服务器上还没有备份" }
                }) { Text("恢复") }
            },
            dismissButton = { TextButton(onClick = { confirmRestore = false }) { Text("取消") } },
        )
    }
}
