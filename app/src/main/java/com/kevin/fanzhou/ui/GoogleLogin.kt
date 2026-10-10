package com.kevin.fanzhou.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.kevin.fanzhou.cloud.CloudDrive
import com.kevin.fanzhou.cloud.GoogleDrive
import com.kevin.fanzhou.data.Repo
import com.kevin.fanzhou.data.Source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URLDecoder
import java.util.UUID

/**
 * Google Drive sign-in. Google blocks OAuth inside WebViews, so we open the system browser and catch
 * the redirect on a loopback port (the same flow rclone uses), or accept a pasted rclone token.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GoogleLoginScreen(nav: NavHostController, editId: String?) {
    val existing = remember(editId) { editId?.let { Repo.store.get(it) } }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var cid by remember { mutableStateOf(existing?.extra?.get("cid") ?: "") }
    var csec by remember { mutableStateOf(existing?.extra?.get("csec") ?: "") }
    var ownClient by remember { mutableStateOf(cid.isNotBlank()) }
    var manual by remember { mutableStateOf(false) }
    var manualText by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var server by remember { mutableStateOf<ServerSocket?>(null) }
    DisposableEffect(Unit) { onDispose { runCatching { server?.close() } } }

    fun clientId() = cid.trim().ifEmpty { GoogleDrive.DEFAULT_CLIENT_ID }
    fun clientSecret() = if (cid.isBlank()) GoogleDrive.DEFAULT_CLIENT_SECRET else csec.trim()

    suspend fun finish(refresh: String) {
        status = "登录成功，正在读取谷歌云盘…"
        try {
            val extra = if (cid.isNotBlank()) mapOf("cid" to cid.trim(), "csec" to csec.trim()) else emptyMap()
            val s = Source(
                id = existing?.id ?: UUID.randomUUID().toString(), type = Source.TYPE_GDRIVE,
                name = existing?.name ?: "谷歌云盘", url = "", token = refresh, extra = extra,
            )
            Repo.store.upsert(s)
            val d = CloudDrive.create(s)
            withContext(Dispatchers.IO) { d.prepare(); d.list(d.rootId) }
            Repo.store.upsert(d.source)
            nav.popBackStack()
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: Exception) { status = "登录失败：" + (e.message ?: e.javaClass.simpleName); busy = false }
    }

    fun browserLogin() {
        busy = true; status = "已打开浏览器，请登录 Google 并允许访问，完成后回到泛舟"
        val state = UUID.randomUUID().toString().take(12)
        val cId = clientId(); val cSec = clientSecret()
        scope.launch {
            try {
                val code = withContext(Dispatchers.IO) {
                    runCatching { server?.close() }
                    val ss = ServerSocket(GoogleDrive.REDIRECT_PORT, 4, InetAddress.getByName("127.0.0.1")).also { server = it }
                    ss.soTimeout = 300_000
                    ss.use {
                        while (true) {
                            val sock = ss.accept()
                            sock.use { c ->
                                val line = c.getInputStream().bufferedReader().readLine() ?: ""
                                val query = line.substringAfter("?", "").substringBefore(' ')
                                val params = query.split('&').mapNotNull { kv ->
                                    kv.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to URLDecoder.decode(it[1], "UTF-8") }
                                }.toMap()
                                val ok = params["state"] == state && params["code"] != null
                                val html = if (ok) "<h2>登录成功</h2><p>请回到泛舟。</p><p><a href=\"intent://#Intent;package=${context.packageName};end\">返回泛舟</a></p>"
                                    else "<h2>登录未完成</h2><p>${params["error"] ?: "请回到泛舟重试"}</p>"
                                val bytes = "<html><head><meta charset=utf-8><meta name=viewport content='width=device-width'></head><body style='font-family:sans-serif;padding:24px'>$html</body></html>".toByteArray()
                                c.getOutputStream().apply {
                                    write("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                                    write(bytes); flush()
                                }
                                if (ok) return@withContext params["code"]!!
                                if (params["error"] != null) throw java.io.IOException("Google 拒绝了授权：${params["error"]}")
                            }
                        }
                        @Suppress("UNREACHABLE_CODE") ""
                    }
                }
                status = "正在换取令牌…"
                val refresh = withContext(Dispatchers.IO) { GoogleDrive.exchangeCode(cId, cSec, code) }
                finish(refresh)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: java.net.SocketTimeoutException) { status = "等待超时，请重试"; busy = false
            } catch (e: Exception) { status = "登录失败：" + (e.message ?: e.javaClass.simpleName); busy = false }
        }
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(GoogleDrive.authUrl(cId, state))))
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("登录谷歌云盘") },
            navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Bg),
        )
    }) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("会在浏览器里打开 Google 登录页，允许访问云端硬盘后自动回到这里。手机需要能访问 Google。", color = TextDim, fontSize = 13.sp)
            Button(enabled = !busy, onClick = { browserLogin() }, modifier = Modifier.fillMaxWidth()) { Text("用浏览器登录 Google") }
            if (status.isNotEmpty()) Text(status, color = TextDim, fontSize = 13.sp)
            if (busy) OutlinedButton(onClick = { runCatching { server?.close() }; busy = false; status = "" }, modifier = Modifier.fillMaxWidth()) { Text("取消") }

            HorizontalDivider()
            TextButton(onClick = { manual = !manual }) { Text("手动填写 refresh_token / rclone token") }
            if (manual) {
                Text("可直接粘贴 rclone 配置里 drive 远程的 token 整段 JSON。若该远程配置了自己的 client_id，请同时在下面填 Client ID。", color = TextDim, fontSize = 12.sp)
                OutlinedTextField(manualText, { manualText = it }, modifier = Modifier.fillMaxWidth(), minLines = 3, label = { Text("token JSON 或 refresh_token") })
                Button(enabled = manualText.isNotBlank() && !busy, modifier = Modifier.fillMaxWidth(), onClick = {
                    busy = true
                    scope.launch {
                        try { finish(GoogleDrive.parseRefreshToken(manualText)) }
                        catch (e: Exception) { status = e.message ?: "格式不对"; busy = false }
                    }
                }) { Text("保存") }
            }

            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("使用自己的 OAuth Client ID", modifier = Modifier.weight(1f))
                Switch(ownClient, { ownClient = it; if (!it) { cid = ""; csec = "" } })
            }
            if (ownClient) {
                Text("默认用 rclone 的公共 Client，多人共用，偶尔会被限流。在 Google Cloud 控制台建「桌面应用」类型的 OAuth 客户端填在这里更稳。", color = TextDim, fontSize = 12.sp)
                OutlinedTextField(cid, { cid = it }, label = { Text("Client ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(csec, { csec = it }, label = { Text("Client Secret") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
