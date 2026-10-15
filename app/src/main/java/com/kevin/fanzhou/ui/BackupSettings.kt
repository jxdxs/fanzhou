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
    var profileName by remember { mutableStateOf(ConfigSync.profileName) }
    var davEncPass by remember { mutableStateOf(ConfigSync.davEncryptionPass) }
    var busy by remember { mutableStateOf(false) }
    var last by remember { mutableLongStateOf(ConfigSync.lastSync) }

    var showExportDialog by remember { mutableStateOf(false) }
    var exportPassword by remember { mutableStateOf("") }

    var pendingImportText by remember { mutableStateOf<String?>(null) }
    var importInfo by remember { mutableStateOf<ConfigSync.BackupInfo?>(null) }
    var importPassword by remember { mutableStateOf("") }

    var confirmRestore by remember { mutableStateOf(false) }
    fun toast(s: String) = Toast.makeText(context, s, Toast.LENGTH_SHORT).show()

    DisposableEffect(Unit) {
        ConfigSync.onApplied = { (context as? Activity)?.recreate() }
        onDispose { ConfigSync.onApplied = null }
    }

    fun saveDav() {
        ConfigSync.davUrl = url
        ConfigSync.davUser = user
        ConfigSync.davPass = pass
        ConfigSync.davDir = dir
        ConfigSync.profileName = profileName
        ConfigSync.davEncryptionPass = davEncPass
    }

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
        val pwd = exportPassword.trim().ifEmpty { null }
        val prof = profileName.trim().ifEmpty { "默认" }
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)!!.use {
                        it.write(ConfigSync.export(password = pwd, profile = prof).toByteArray())
                    }
                }.isSuccess
            }
            toast(if (ok) (if (pwd != null) "已加密导出" else "已导出") else "导出失败")
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() } }.getOrNull()
            }
            if (text == null) {
                toast("读取文件失败")
            } else {
                runCatching {
                    val info = ConfigSync.inspect(text)
                    importInfo = info
                    pendingImportText = text
                    importPassword = ""
                }.onFailure {
                    toast("不是有效的泛舟配置文件：${it.message}")
                }
            }
        }
    }

    val sum = when {
        auto -> "WebDAV 自动同步已开 · 档案「$profileName」"
        last > 0 -> "上次同步 " + SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(last)) + " · 档案「$profileName」"
        else -> "导出、导入、WebDAV · 档案「$profileName」"
    }
    group("配置备份与同步", sum) {
        Text("包含媒体源、网盘登录、播放进度和各项偏好。支持设置加密密码防止凭据泄漏；支持设定用户档案标识，区分不同使用者的备份。", fontSize = 12.sp, color = TextDim)

        OutlinedTextField(
            value = profileName,
            onValueChange = { profileName = it; ConfigSync.profileName = it },
            label = { Text("当前用户档案 / 身份标识") },
            placeholder = { Text("例如：个人、客厅电视、张三") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = {
                exportPassword = ""
                showExportDialog = true
            }) { Text("导出到文件") }

            OutlinedButton(onClick = {
                importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream", "*/*"))
            }) { Text("从文件导入") }
        }

        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        Text("WebDAV 云端同步", fontSize = 14.sp)
        OutlinedTextField(url, { url = it }, label = { Text("服务器地址") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(user, { user = it }, label = { Text("用户名") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(pass, { pass = it }, label = { Text("密码") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(dir, { dir = it }, label = { Text("备份目录") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            value = davEncPass,
            onValueChange = { davEncPass = it; ConfigSync.davEncryptionPass = it },
            label = { Text("WebDAV 备份加密密码（选填，强加密云端文件）") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )
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

    if (showExportDialog) {
        AlertDialog(
            onDismissRequest = { showExportDialog = false },
            title = { Text("导出配置备份") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("档案标识：【${profileName.trim().ifEmpty { "默认" }}】", fontSize = 14.sp)
                    Text("备份包含云盘登录凭据、媒体源与播放历史。强烈建议设置加密密码（AES-256 加密），保护账号隐私安全；若留空则为明文备份。", fontSize = 12.sp, color = TextDim)
                    OutlinedTextField(
                        value = exportPassword,
                        onValueChange = { exportPassword = it },
                        label = { Text("加密密码（推荐填写）") },
                        placeholder = { Text("留空则不加密") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showExportDialog = false
                    val day = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
                    val safeProfile = profileName.trim().ifEmpty { "默认" }
                    val encSuffix = if (exportPassword.isNotBlank()) "-enc" else ""
                    exportLauncher.launch("fanzhou-$safeProfile-$day$encSuffix.json")
                }) { Text("确定导出") }
            },
            dismissButton = { TextButton(onClick = { showExportDialog = false }) { Text("取消") } }
        )
    }

    pendingImportText?.let { text ->
        val info = importInfo
        val isEnc = info?.isEncrypted == true
        AlertDialog(
            onDismissRequest = { pendingImportText = null; importInfo = null },
            title = { Text(if (isEnc) "导入加密配置" else "导入备份配置") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("档案标识：【${info?.profile ?: "默认"}】", fontSize = 14.sp)
                    Text("来源设备：${info?.device ?: "未知"}", fontSize = 13.sp, color = TextDim)
                    if ((info?.exportedAt ?: 0L) > 0) {
                        Text("备份时间：" + SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(info!!.exportedAt)), fontSize = 13.sp, color = TextDim)
                    }
                    if (isEnc) {
                        Text("此备份已被 AES-256 密码加密保护。请输入密码以解密：", fontSize = 13.sp)
                        OutlinedTextField(
                            value = importPassword,
                            onValueChange = { importPassword = it },
                            label = { Text("解密密码") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        Text("此备份为明文配置。将用文件里的配置替换本机的媒体源、登录和偏好。本机已有的播放进度会保留。", fontSize = 12.sp, color = TextDim)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    runCatching {
                        ConfigSync.import(text, importPassword.takeIf { it.isNotBlank() })
                    }.onSuccess {
                        toast("已导入档案「${info?.profile ?: "默认"}」")
                        pendingImportText = null
                        importInfo = null
                    }.onFailure {
                        toast("导入失败：${it.message}")
                    }
                }) { Text(if (isEnc) "解密并导入" else "导入") }
            },
            dismissButton = { TextButton(onClick = { pendingImportText = null; importInfo = null }) { Text("取消") } }
        )
    }

    if (confirmRestore) {
        AlertDialog(
            onDismissRequest = { confirmRestore = false },
            title = { Text("从 WebDAV 恢复？") },
            text = { Text("将用服务器上的备份替换本机的媒体源、登录和偏好。本机已有的播放进度会保留。若服务器备份已加密，将自动使用上方填写的 WebDAV 加密密码解密。") },
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
