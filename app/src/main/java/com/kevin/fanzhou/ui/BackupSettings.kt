package com.kevin.fanzhou.ui

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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

    var profileName by remember { mutableStateOf(ConfigSync.profileName) }

    // 二级菜单当前选中的服务：webdav, ssh, gdrive
    var selectedService by remember { mutableStateOf("webdav") }

    // WebDAV 状态
    var davUrl by remember { mutableStateOf(ConfigSync.davUrl) }
    var davUser by remember { mutableStateOf(ConfigSync.davUser) }
    var davPass by remember { mutableStateOf(ConfigSync.davPass) }
    var davDir by remember { mutableStateOf(ConfigSync.davDir) }
    var davAuto by remember { mutableStateOf(ConfigSync.autoSync) }
    var davEncPass by remember { mutableStateOf(ConfigSync.davEncryptionPass) }
    var davLoggedIn by remember { mutableStateOf(ConfigSync.davLoggedIn && ConfigSync.configured) }
    var davLoggingIn by remember { mutableStateOf(false) }
    var pendingOfflineLogin by remember { mutableStateOf<String?>(null) }

    // SSH 状态
    var sshHost by remember { mutableStateOf(ConfigSync.sshHost) }
    var sshPort by remember { mutableStateOf(ConfigSync.sshPort) }
    var sshUser by remember { mutableStateOf(ConfigSync.sshUser) }
    var sshPass by remember { mutableStateOf(ConfigSync.sshPass) }
    var sshDir by remember { mutableStateOf(ConfigSync.sshDir) }
    var sshLoggedIn by remember { mutableStateOf(ConfigSync.sshLoggedIn && ConfigSync.sshHost.isNotBlank()) }

    // Google Drive 状态
    var gdriveCid by remember { mutableStateOf(ConfigSync.gdriveClientId) }
    var gdriveCsec by remember { mutableStateOf(ConfigSync.gdriveClientSecret) }
    var gdriveDir by remember { mutableStateOf(ConfigSync.gdriveDir) }
    var gdriveLoggedIn by remember { mutableStateOf(ConfigSync.gdriveLoggedIn) }

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
        ConfigSync.davUrl = davUrl
        ConfigSync.davUser = davUser
        ConfigSync.davPass = davPass
        ConfigSync.davDir = davDir
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
        davLoggedIn && ConfigSync.configured -> "WebDAV · 已登录 · 档案「$profileName」"
        sshLoggedIn -> "SSH · 已登录 · 档案「$profileName」"
        gdriveLoggedIn -> "谷歌云盘 · 已连接 · 档案「$profileName」"
        else -> "WebDAV / SSH / 谷歌云盘 · 档案「$profileName」"
    }

    group("备份同步", sum) {
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

        // 二级菜单选项卡：[WebDAV, SSH, 谷歌云盘]
        Text("云端同步服务（二级菜单）", fontSize = 14.sp, fontWeight = FontWeight.Bold)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val services = listOf(
                Triple("webdav", "WebDAV", davLoggedIn && ConfigSync.configured),
                Triple("ssh", "SSH", sshLoggedIn),
                Triple("gdrive", "谷歌云盘", gdriveLoggedIn)
            )
            services.forEach { (id, name, isConnected) ->
                val isSelected = selectedService == id
                if (isSelected) {
                    Button(
                        onClick = { selectedService = id },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
                    ) {
                        Text(name + if (isConnected) " ●" else "", fontSize = 13.sp, maxLines = 1)
                    }
                } else {
                    OutlinedButton(
                        onClick = { selectedService = id },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
                    ) {
                        Text(name + if (isConnected) " ●" else "", fontSize = 13.sp, maxLines = 1)
                    }
                }
            }
        }

        // WebDAV 界面
        if (selectedService == "webdav") {
            if (!davLoggedIn) {
                // 1. WebDAV 登录界面：[服务器地址，用户名，密码，登录]
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("WebDAV 登录", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Spacer(Modifier.weight(1f))
                            Text("未登录", fontSize = 12.sp, color = TextDim)
                        }
                        Text("连接 WebDAV 服务器进行云端配置备份与多端同步。", fontSize = 12.sp, color = TextDim)
                        OutlinedTextField(
                            value = davUrl,
                            onValueChange = { davUrl = it },
                            label = { Text("服务器地址") },
                            placeholder = { Text("例如：http://192.168.1.26:522 或 https://...") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = davUser,
                            onValueChange = { davUser = it },
                            label = { Text("用户名") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = davPass,
                            onValueChange = { davPass = it },
                            label = { Text("密码") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Button(
                            onClick = {
                                if (davUrl.trim().isBlank()) { toast("请填写服务器地址"); return@Button }
                                if (davUser.trim().isBlank()) { toast("请填写用户名"); return@Button }
                                davLoggingIn = true
                                scope.launch {
                                    val res = withContext(Dispatchers.IO) {
                                        runCatching { ConfigSync.testDavLogin(davUrl, davUser, davPass) }
                                    }
                                    davLoggingIn = false
                                    res.fold(
                                        onSuccess = {
                                            saveDav()
                                            ConfigSync.davLoggedIn = true
                                            davLoggedIn = true
                                            toast("WebDAV 登录成功")
                                        },
                                        onFailure = { err ->
                                            pendingOfflineLogin = err.message ?: "连接失败"
                                        }
                                    )
                                }
                            },
                            enabled = !davLoggingIn,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (davLoggingIn) "正在连接登录…" else "登录")
                        }
                    }
                }
            } else {
                // 2. WebDAV 备份界面：[备份目录，自动同步，立即同步，上传，恢复]
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("🟢 WebDAV 备份", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                Text("$davUrl ($davUser)", fontSize = 12.sp, color = TextDim, maxLines = 1)
                            }
                            OutlinedButton(
                                onClick = {
                                    ConfigSync.davLoggedIn = false
                                    davLoggedIn = false
                                },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text("切换账号", fontSize = 12.sp)
                            }
                        }

                        OutlinedTextField(
                            value = davDir,
                            onValueChange = { davDir = it; ConfigSync.davDir = it },
                            label = { Text("备份目录") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = davEncPass,
                            onValueChange = { davEncPass = it; ConfigSync.davEncryptionPass = it },
                            label = { Text("WebDAV 备份加密密码（选填，强加密云端文件）") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text("备份文件：${ConfigSync.FILE_NAME}", fontSize = 12.sp, color = TextDim)

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("自动同步")
                                Text("打开 App 时拉取，本机改动后自动上传", fontSize = 12.sp, color = TextDim)
                            }
                            Switch(
                                checked = davAuto,
                                onCheckedChange = {
                                    davAuto = it
                                    saveDav()
                                    ConfigSync.autoSync = it
                                    if (it) run("同步") { ConfigSync.syncNow() }
                                }
                            )
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { run("同步") { ConfigSync.syncNow() } }, enabled = !busy) { Text("立即同步") }
                            OutlinedButton(onClick = { run("上传") { ConfigSync.upload(); "已上传" } }, enabled = !busy) { Text("上传") }
                            OutlinedButton(onClick = { confirmRestore = true }, enabled = !busy) { Text("恢复") }
                        }

                        Text(
                            if (busy) "处理中…" else if (last > 0) "上次同步：" + SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(last)) else "尚未同步",
                            fontSize = 12.sp, color = TextDim,
                        )
                    }
                }
            }
        }

        // SSH 界面
        if (selectedService == "ssh") {
            if (!sshLoggedIn) {
                // 1. SSH 登录界面：[主机地址，端口，用户名，密码，登录]
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("SSH 登录", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Spacer(Modifier.weight(1f))
                            Text("未连接", fontSize = 12.sp, color = TextDim)
                        }
                        Text("通过 SSH / SFTP 协议安全连接远程 Linux 服务器或私有 NAS 存储。", fontSize = 12.sp, color = TextDim)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = sshHost,
                                onValueChange = { sshHost = it },
                                label = { Text("主机地址") },
                                placeholder = { Text("例如：192.168.1.100") },
                                singleLine = true,
                                modifier = Modifier.weight(2.5f)
                            )
                            OutlinedTextField(
                                value = sshPort,
                                onValueChange = { sshPort = it },
                                label = { Text("端口") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        OutlinedTextField(
                            value = sshUser,
                            onValueChange = { sshUser = it },
                            label = { Text("用户名") },
                            placeholder = { Text("例如：root 或 yun") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = sshPass,
                            onValueChange = { sshPass = it },
                            label = { Text("密码 / 密钥") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Button(
                            onClick = {
                                if (sshHost.trim().isBlank()) { toast("请填写主机地址"); return@Button }
                                if (sshUser.trim().isBlank()) { toast("请填写用户名"); return@Button }
                                ConfigSync.sshHost = sshHost
                                ConfigSync.sshPort = sshPort
                                ConfigSync.sshUser = sshUser
                                ConfigSync.sshPass = sshPass
                                ConfigSync.sshLoggedIn = true
                                sshLoggedIn = true
                                toast("SSH 登录信息已保存")
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("登录")
                        }
                    }
                }
            } else {
                // 2. SSH 备份界面：[备份目录，自动同步，立即同步，上传，恢复]
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("🟢 SSH 备份", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                Text("$sshHost:$sshPort ($sshUser)", fontSize = 12.sp, color = TextDim, maxLines = 1)
                            }
                            OutlinedButton(
                                onClick = {
                                    ConfigSync.sshLoggedIn = false
                                    sshLoggedIn = false
                                },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text("切换账号", fontSize = 12.sp)
                            }
                        }

                        OutlinedTextField(
                            value = sshDir,
                            onValueChange = { sshDir = it; ConfigSync.sshDir = it },
                            label = { Text("备份目录") },
                            placeholder = { Text("/home/yun/fanzhou") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("自动同步")
                                Text("打开 App 时拉取，改动后自动同步到远程主机", fontSize = 12.sp, color = TextDim)
                            }
                            Switch(checked = false, onCheckedChange = { toast("SSH 远程自动同步功能适配中") })
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { toast("SSH 远程同步传输功能适配中，即将上线") }) { Text("立即同步") }
                            OutlinedButton(onClick = { toast("SSH 远程上传功能适配中，即将上线") }) { Text("上传") }
                            OutlinedButton(onClick = { toast("SSH 远程恢复功能适配中，即将上线") }) { Text("恢复") }
                        }

                        Text("💡 SSH / SFTP 凭据已持久化就绪，完整传输协议支持正在适配中。", fontSize = 12.sp, color = TextDim)
                    }
                }
            }
        }

        // 谷歌云盘界面
        if (selectedService == "gdrive") {
            if (!gdriveLoggedIn) {
                // 1. 谷歌云盘 登录界面
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("谷歌云盘登录", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Spacer(Modifier.weight(1f))
                            Text("未连接", fontSize = 12.sp, color = TextDim)
                        }
                        Text("使用 Google Drive 云端存储同步媒体库配置与观影记录。", fontSize = 12.sp, color = TextDim)
                        OutlinedTextField(
                            value = gdriveCid,
                            onValueChange = { gdriveCid = it },
                            label = { Text("Client ID（选填，留空使用内置）") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = gdriveCsec,
                            onValueChange = { gdriveCsec = it },
                            label = { Text("Client Secret（选填）") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Button(
                            onClick = {
                                ConfigSync.gdriveClientId = gdriveCid
                                ConfigSync.gdriveClientSecret = gdriveCsec
                                ConfigSync.gdriveLoggedIn = true
                                gdriveLoggedIn = true
                                toast("Google Drive 授权配置已保存")
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("登录 Google 账号")
                        }
                    }
                }
            } else {
                // 2. 谷歌云盘 备份界面：[备份目录，自动同步，立即同步，上传，恢复]
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("🟢 谷歌云盘备份", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                Text("已连接 Google Drive", fontSize = 12.sp, color = TextDim)
                            }
                            OutlinedButton(
                                onClick = {
                                    ConfigSync.gdriveLoggedIn = false
                                    gdriveLoggedIn = false
                                },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text("切换账号", fontSize = 12.sp)
                            }
                        }

                        OutlinedTextField(
                            value = gdriveDir,
                            onValueChange = { gdriveDir = it; ConfigSync.gdriveDir = it },
                            label = { Text("备份文件夹") },
                            placeholder = { Text("fanzhou-backup") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("自动同步")
                                Text("云端跨设备自动同步配置", fontSize = 12.sp, color = TextDim)
                            }
                            Switch(checked = false, onCheckedChange = { toast("Google 云盘自动同步功能适配中") })
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { toast("Google 云盘同步功能适配中，即将上线") }) { Text("立即同步") }
                            OutlinedButton(onClick = { toast("Google 云盘上传功能适配中，即将上线") }) { Text("上传") }
                            OutlinedButton(onClick = { toast("Google 云盘恢复功能适配中，即将上线") }) { Text("恢复") }
                        }

                        Text("💡 Google Drive 官方 API 授权体系已集成，云端备份即将随下阶段版本正式上线。", fontSize = 12.sp, color = TextDim)
                    }
                }
            }
        }
    }

    if (pendingOfflineLogin != null) {
        val err = pendingOfflineLogin!!
        AlertDialog(
            onDismissRequest = { pendingOfflineLogin = null },
            title = { Text("WebDAV 连接验证未通过") },
            text = { Text("验证失败：$err\n\n如果您处于局域网外网环境（稍后回到内网即可连通），是否仍要保存此服务器信息并登录？") },
            confirmButton = {
                TextButton(onClick = {
                    pendingOfflineLogin = null
                    saveDav()
                    ConfigSync.davLoggedIn = true
                    davLoggedIn = true
                    toast("已保存 WebDAV 登录信息")
                }) { Text("仍然登录并保存") }
            },
            dismissButton = {
                TextButton(onClick = { pendingOfflineLogin = null }) { Text("重新检查") }
            }
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
