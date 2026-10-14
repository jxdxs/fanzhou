package com.kevin.fanzhou.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.navigation.NavHostController
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.kevin.fanzhou.R
import com.kevin.fanzhou.cloud.CloudDrive
import com.kevin.fanzhou.cloud.Drive123
import com.kevin.fanzhou.cloud.QrLogin
import com.kevin.fanzhou.cloud.QrState
import com.kevin.fanzhou.data.Repo
import com.kevin.fanzhou.data.Source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONTokener
import java.util.UUID

fun qrBitmap(content: String, size: Int = 640): Bitmap {
    val m = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
    val px = IntArray(size * size) { i -> if (m[i % size, i / size]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
    return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
}

/** Saves a bitmap to Pictures/泛舟 so it can be scanned from the drive app's album picker. */
fun saveToGallery(context: android.content.Context, bmp: Bitmap, name: String): Boolean = try {
    if (android.os.Build.VERSION.SDK_INT >= 29) {
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "$name.png")
            put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/泛舟")
            put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
        }
        val r = context.contentResolver
        val uri = r.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("insert failed")
        r.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        values.clear(); values.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
        r.update(uri, values, null, null)
        true
    } else {
        @Suppress("DEPRECATION")
        val dir = java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_PICTURES), "泛舟").apply { mkdirs() }
        val f = java.io.File(dir, "$name.png")
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        android.media.MediaScannerConnection.scanFile(context, arrayOf(f.absolutePath), arrayOf("image/png"), null)
        true
    }
} catch (_: Exception) { false }

fun extract115Cookies(cookieStr: String): String? {
    val map = mutableMapOf<String, String>()
    cookieStr.split(';').forEach { part ->
        val kv = part.trim().split('=', limit = 2)
        if (kv.size == 2) {
            val k = kv[0].trim()
            val v = kv[1].trim()
            if (k.isNotEmpty() && v.isNotEmpty()) {
                map[k] = v
            }
        }
    }
    val uid = map["UID"] ?: return null
    val cid = map["CID"] ?: return null
    val seid = map["SEID"] ?: return null
    val kid = map["KID"]
    return buildString {
        append("UID=").append(uid)
        append("; CID=").append(cid)
        append("; SEID=").append(seid)
        if (!kid.isNullOrBlank()) {
            append("; KID=").append(kid)
        }
    }
}

fun parseAliyunToken(raw: String?): Pair<String, Map<String, String>>? {
    if (raw.isNullOrBlank() || raw == "null") return null
    val unquoted = if (raw.startsWith("\"") && raw.endsWith("\"") && raw.length > 2) {
        try {
            JSONTokener(raw).nextValue().toString()
        } catch (_: Exception) {
            raw.substring(1, raw.length - 1).replace("\\\"", "\"").replace("\\\\", "\\")
        }
    } else raw
    return try {
        val json = JSONObject(unquoted)
        val refresh = json.optString("refresh_token").takeIf { it.isNotEmpty() }
            ?: run {
                var found: String? = null
                val it = json.keys()
                while (it.hasNext()) {
                    val k = it.next()
                    val child = json.optJSONObject(k)
                    if (child != null) {
                        val r = child.optString("refresh_token")
                        if (r.isNotEmpty()) { found = r; break }
                    }
                }
                found
            } ?: Regex("\"refresh_token\"\\s*:\\s*\"([^\"]+)\"").find(unquoted)?.groupValues?.get(1)
            ?: return null

        val userId = json.optString("user_id").takeIf { it.isNotEmpty() }
            ?: json.optString("userId").takeIf { it.isNotEmpty() }
            ?: Regex("\"user_id\"\\s*:\\s*\"([^\"]+)\"").find(unquoted)?.groupValues?.get(1)
            ?: ""
        val access = json.optString("access_token").takeIf { it.isNotEmpty() }
            ?: json.optString("accessToken").takeIf { it.isNotEmpty() }
            ?: Regex("\"access_token\"\\s*:\\s*\"([^\"]+)\"").find(unquoted)?.groupValues?.get(1)
            ?: ""
        val driveId = json.optString("default_drive_id").takeIf { it.isNotEmpty() }
            ?: Regex("\"default_drive_id\"\\s*:\\s*\"([^\"]+)\"").find(unquoted)?.groupValues?.get(1)
        val extra = mutableMapOf<String, String>()
        if (userId.isNotEmpty()) extra["user_id"] = userId
        if (access.isNotEmpty()) extra["access"] = access
        if (!driveId.isNullOrEmpty()) extra["default_drive"] = driveId
        refresh to extra
    } catch (_: Exception) {
        null
    }
}

fun parse123Token(raw: String?): String? {
    if (raw.isNullOrBlank() || raw == "null") return null
    val unquoted = if (raw.startsWith("\"") && raw.endsWith("\"") && raw.length > 2) {
        try {
            JSONTokener(raw).nextValue().toString()
        } catch (_: Exception) {
            raw.substring(1, raw.length - 1).replace("\\\"", "\"").replace("\\\\", "\\")
        }
    } else raw
    return try {
        if (unquoted.startsWith("{")) {
            val json = JSONObject(unquoted)
            var tok = json.optString("token").takeIf { it.isNotEmpty() }
                ?: json.optString("Token").takeIf { it.isNotEmpty() }
                ?: json.optString("passport_token").takeIf { it.isNotEmpty() }
            if (tok.isNullOrEmpty()) {
                val dataObj = json.optJSONObject("data")
                tok = dataObj?.optString("token")?.takeIf { it.isNotEmpty() }
                    ?: dataObj?.optString("Token")?.takeIf { it.isNotEmpty() }
                    ?: dataObj?.optString("passport_token")?.takeIf { it.isNotEmpty() }
            }
            if (tok.isNullOrEmpty()) {
                tok = Regex("\"(?:token|Token|passport_token)\"\\s*:\\s*\"([^\"]+)\"").find(unquoted)?.groupValues?.get(1)
            }
            tok?.let { if (it.startsWith("Bearer ", ignoreCase = true)) it else "Bearer $it" }
        } else if (unquoted.isNotBlank()) {
            val t = unquoted.trim()
            if (t.startsWith("Bearer ", ignoreCase = true)) t else "Bearer $t"
        } else null
    } catch (_: Exception) {
        null
    }
}

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudWebLoginDialog(
    type: String,
    initialAccount: String = "",
    initialPassword: String = "",
    onDismiss: () -> Unit,
    onSuccess: (token: String, extra: Map<String, String>) -> Unit,
) {
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var progress by remember { mutableIntStateOf(0) }
    var finishedLogin by remember { mutableStateOf(false) }

    fun checkCredentials(wv: WebView?, currentUrl: String? = null) {
        if (finishedLogin || wv == null) return
        when (type) {
            Source.TYPE_115 -> {
                val c1 = CookieManager.getInstance().getCookie("https://115.com") ?: ""
                val c2 = CookieManager.getInstance().getCookie("https://passport.115.com") ?: ""
                val c3 = CookieManager.getInstance().getCookie("https://webapi.115.com") ?: ""
                val all = "$c1; $c2; $c3"
                if (all.contains("UID=") && all.contains("CID=") && all.contains("SEID=")) {
                    val extracted = extract115Cookies(all)
                    if (extracted != null) {
                        finishedLogin = true
                        onSuccess(extracted, emptyMap())
                    }
                }
            }
            Source.TYPE_ALIYUN -> {
                val js = """
                    (function() {
                        try {
                            var t = localStorage.getItem('token');
                            if (t && t.indexOf('refresh_token') !== -1) return t;
                            for (var i = 0; i < localStorage.length; i++) {
                                var k = localStorage.key(i);
                                var val = localStorage.getItem(k);
                                if (val && val.indexOf('refresh_token') !== -1) return val;
                            }
                        } catch(e) {}
                        return null;
                    })()
                """.trimIndent()
                wv.evaluateJavascript(js) { raw ->
                    if (finishedLogin) return@evaluateJavascript
                    val parsed = parseAliyunToken(raw)
                    if (parsed != null) {
                        finishedLogin = true
                        onSuccess(parsed.first, parsed.second)
                    }
                }
            }
            Source.TYPE_123 -> {
                val c = CookieManager.getInstance().getCookie("https://www.123pan.com") ?: ""
                val tokenFromCookie = c.split(';').map { it.trim() }
                    .firstOrNull { it.startsWith("token=") || it.startsWith("passport_token=") }
                    ?.substringAfter('=')
                if (!tokenFromCookie.isNullOrEmpty() && tokenFromCookie.length > 20) {
                    val tok = if (tokenFromCookie.startsWith("Bearer ", ignoreCase = true)) tokenFromCookie else "Bearer $tokenFromCookie"
                    finishedLogin = true
                    onSuccess(tok, emptyMap())
                    return
                }

                val js = """
                    (function() {
                        try {
                            var t = localStorage.getItem('token') || localStorage.getItem('passport_token');
                            if (t) return t;
                            for (var i = 0; i < localStorage.length; i++) {
                                var k = localStorage.key(i);
                                var val = localStorage.getItem(k);
                                if (val && (k.toLowerCase().indexOf('token') !== -1 || val.indexOf('Bearer') !== -1)) return val;
                            }
                        } catch(e) {}
                        return null;
                    })()
                """.trimIndent()
                wv.evaluateJavascript(js) { raw ->
                    if (finishedLogin) return@evaluateJavascript
                    val token = parse123Token(raw)
                    if (!token.isNullOrEmpty()) {
                        finishedLogin = true
                        onSuccess(token, emptyMap())
                    }
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        while (!finishedLogin) {
            delay(1000)
            webViewRef?.let { checkCredentials(it) }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            runCatching {
                webViewRef?.stopLoading()
                webViewRef?.destroy()
                webViewRef = null
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        BackHandler(enabled = true) {
            if (webViewRef?.canGoBack() == true) {
                webViewRef?.goBack()
            } else {
                onDismiss()
            }
        }

        Surface(modifier = Modifier.fillMaxSize(), color = Bg) {
            Column(modifier = Modifier.fillMaxSize()) {
                TopAppBar(
                    title = {
                        Text(
                            when (type) {
                                Source.TYPE_115 -> "115 账号密码登录"
                                Source.TYPE_ALIYUN -> "阿里云盘账号密码登录"
                                Source.TYPE_123 -> "123云盘网页登录"
                                else -> "网页登录"
                            },
                            fontSize = 17.sp
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Filled.Close, "关闭")
                        }
                    },
                    actions = {
                        IconButton(onClick = { webViewRef?.reload() }) {
                            Icon(Icons.Filled.Refresh, "刷新")
                        }
                        TextButton(onClick = { webViewRef?.let { checkCredentials(it) } }) {
                            Text("检测登录")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Bg)
                )

                if (progress in 1..99) {
                    LinearProgressIndicator(
                        progress = { progress / 100f },
                        modifier = Modifier.fillMaxWidth().height(2.dp),
                        color = Accent
                    )
                }

                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            webViewRef = this
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                            settings.apply {
                                javaScriptEnabled = true
                                domStorageEnabled = true
                                databaseEnabled = true
                                useWideViewPort = true
                                loadWithOverviewMode = true
                                cacheMode = WebSettings.LOAD_DEFAULT
                                val rawUa = userAgentString
                                userAgentString = rawUa.replace("; wv", "").replace("Version/4.0 ", "")
                            }
                            CookieManager.getInstance().let { cm ->
                                cm.setAcceptCookie(true)
                                cm.setAcceptThirdPartyCookies(this@apply, true)
                            }
                            webChromeClient = object : WebChromeClient() {
                                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                    progress = newProgress
                                }
                            }
                            webViewClient = object : WebViewClient() {
                                override fun onPageFinished(view: WebView?, url: String?) {
                                    super.onPageFinished(view, url)
                                    if (initialAccount.isNotBlank() || initialPassword.isNotBlank()) {
                                        val fillJs = """
                                            (function() {
                                                var user = ${JSONObject.quote(initialAccount)};
                                                var pwd = ${JSONObject.quote(initialPassword)};
                                                function tryFill() {
                                                    var u = document.querySelector('input[type="text"], input[type="tel"], input[name*="user"], input[name*="account"], input[placeholder*="手机"], input[placeholder*="账号"]');
                                                    var p = document.querySelector('input[type="password"]');
                                                    if (u && user && !u.value) {
                                                        u.value = user;
                                                        u.dispatchEvent(new Event('input', { bubbles: true }));
                                                        u.dispatchEvent(new Event('change', { bubbles: true }));
                                                    }
                                                    if (p && pwd && !p.value) {
                                                        p.value = pwd;
                                                        p.dispatchEvent(new Event('input', { bubbles: true }));
                                                        p.dispatchEvent(new Event('change', { bubbles: true }));
                                                    }
                                                }
                                                tryFill();
                                                setTimeout(tryFill, 600);
                                                setTimeout(tryFill, 1500);
                                            })()
                                        """.trimIndent()
                                        view?.evaluateJavascript(fillJs, null)
                                    }
                                    checkCredentials(view, url)
                                }
                            }
                            val startUrl = when (type) {
                                Source.TYPE_115 -> "https://passport.115.com/?ct=login&ac=index&mode=1"
                                Source.TYPE_ALIYUN -> "https://www.alipan.com/login"
                                Source.TYPE_123 -> "https://www.123pan.com/login"
                                else -> "https://www.alipan.com/login"
                            }
                            loadUrl(startUrl)
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudLoginScreen(nav: NavHostController, type: String, editId: String?) {
    if (type == Source.TYPE_GDRIVE) { GoogleLoginScreen(nav, editId); return }
    val existing = remember(editId) { editId?.let { Repo.store.get(it) } }
    val label = Source(id = "", type = type, name = "", url = "").typeLabel
    var qr by remember { mutableStateOf<Bitmap?>(null) }
    var status by remember { mutableStateOf("正在获取二维码…") }
    var attempt by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }

    val supportsQr = type == Source.TYPE_115 || type == Source.TYPE_ALIYUN || type == Source.TYPE_QUARK
    val tabs = when (type) {
        Source.TYPE_123 -> listOf("账号密码登录", "网页登录", "手动填 Token")
        Source.TYPE_115 -> listOf("扫码登录", "账号密码登录", "手动填 Cookie")
        Source.TYPE_ALIYUN -> listOf("扫码登录", "账号密码登录", "手动填 Token")
        else -> listOf("扫码登录", "手动填写")
    }
    var selectedTab by remember { mutableIntStateOf(0) }

    // Form inputs
    var accountInput by remember { mutableStateOf(existing?.username ?: "") }
    var passwordInput by remember { mutableStateOf(existing?.password ?: "") }
    var showPassword by remember { mutableStateOf(false) }
    var manualText by remember { mutableStateOf(existing?.token ?: "") }
    var showWebLogin by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val login = remember(attempt) { if (supportsQr) QrLogin.forType(type) else null }
    val context = LocalContext.current

    fun doSave() {
        val b = qr ?: return
        val ok = saveToGallery(context, b, "${label}登录二维码_${System.currentTimeMillis() / 1000}")
        android.widget.Toast.makeText(context, if (ok) "已保存到相册（图片/泛舟），可在${label} App 扫一扫里从相册选择" else "保存失败", android.widget.Toast.LENGTH_LONG).show()
    }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) doSave() else android.widget.Toast.makeText(context, "需要存储权限才能保存到相册", android.widget.Toast.LENGTH_SHORT).show() }

    suspend fun finish(token: String, extra: Map<String, String>) {
        busy = true
        status = "登录成功，正在读取网盘…"
        try {
            var s = Source(
                id = existing?.id ?: UUID.randomUUID().toString(), type = type,
                name = existing?.name ?: label, url = "", token = token, extra = extra,
                username = accountInput.takeIf { it.isNotBlank() } ?: existing?.username ?: "",
                password = passwordInput.takeIf { it.isNotBlank() } ?: existing?.password ?: "",
            )
            Repo.store.upsert(s)
            val d = CloudDrive.create(s)
            withContext(Dispatchers.IO) {
                s = d.prepare()
                d.list(d.rootId) // verify the credential works
            }
            Repo.store.upsert(d.source)
            nav.popBackStack()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            status = "登录失败：" + (e.message ?: e.javaClass.simpleName)
            busy = false
        }
    }

    LaunchedEffect(attempt, supportsQr) {
        if (!supportsQr || login == null) return@LaunchedEffect
        try {
            qr = qrBitmap(login.start())
            status = login.appHint
            val started = System.currentTimeMillis()
            while (true) {
                when (val st = login.poll()) {
                    QrState.Waiting -> {}
                    QrState.Scanned -> status = "已扫码，请在手机上确认登录"
                    QrState.Expired -> { status = "二维码已过期，点击刷新"; qr = null; return@LaunchedEffect }
                    is QrState.Failed -> { status = st.msg; return@LaunchedEffect }
                    is QrState.Done -> { finish(st.token, st.extra); return@LaunchedEffect }
                }
                if (System.currentTimeMillis() - started > 280_000) { status = "二维码已过期，点击刷新"; qr = null; return@LaunchedEffect }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            status = "获取二维码失败：" + (e.message ?: e.javaClass.simpleName)
        }
    }

    if (showWebLogin) {
        CloudWebLoginDialog(
            type = type,
            initialAccount = accountInput,
            initialPassword = passwordInput,
            onDismiss = { showWebLogin = false },
            onSuccess = { tok, ext ->
                showWebLogin = false
                android.widget.Toast.makeText(context, "网页登录成功，正在连接网盘…", android.widget.Toast.LENGTH_SHORT).show()
                scope.launch { finish(tok, ext) }
            }
        )
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("登录$label") },
            navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Bg),
        )
    }) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize(),
        ) {
            if (tabs.size > 1) {
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = Bg,
                    contentColor = Accent,
                ) {
                    tabs.forEachIndexed { idx, title ->
                        Tab(
                            selected = selectedTab == idx,
                            onClick = { selectedTab = idx },
                            text = { Text(title, fontWeight = if (selectedTab == idx) FontWeight.Bold else FontWeight.Normal, fontSize = 14.sp) }
                        )
                    }
                }
            }

            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val currentTabTitle = tabs.getOrNull(selectedTab) ?: ""

                when {
                    currentTabTitle == "扫码登录" -> {
                        Box(
                            Modifier.size(240.dp).clip(RoundedCornerShape(12.dp)).background(Color.White).clickable { if (qr == null && !busy) attempt++ },
                            contentAlignment = Alignment.Center,
                        ) {
                            val b = qr
                            if (b != null) Image(b.asImageBitmap(), "二维码", Modifier.fillMaxSize().padding(10.dp))
                            else if (busy || status.startsWith("正在")) CircularProgressIndicator()
                            else Text("点击刷新", color = Color.Black)
                        }
                        Spacer(Modifier.height(16.dp))
                        Text(status, color = TextDim, fontSize = 14.sp)
                        Spacer(Modifier.height(8.dp))
                        Text("可以用另一台手机扫码；如果只有这一台，先把二维码保存到相册，再在网盘 App 的扫一扫里从相册选择。", color = TextDim, fontSize = 12.sp)
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(enabled = qr != null, onClick = {
                            if (android.os.Build.VERSION.SDK_INT < 29 && ContextCompat.checkSelfPermission(context, android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                                permLauncher.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                            else doSave()
                        }) {
                            Icon(painterResource(R.drawable.ic_save), null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("保存二维码到相册")
                        }
                    }

                    currentTabTitle == "账号密码登录" && type == Source.TYPE_123 -> {
                        Text("输入 123云盘 账号及密码直接登录", color = TextDim, fontSize = 14.sp)
                        Spacer(Modifier.height(16.dp))
                        OutlinedTextField(
                            value = accountInput,
                            onValueChange = { accountInput = it },
                            label = { Text("手机号 / 账号") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = passwordInput,
                            onValueChange = { passwordInput = it },
                            label = { Text("密码") },
                            singleLine = true,
                            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                TextButton(onClick = { showPassword = !showPassword }) {
                                    Text(if (showPassword) "隐藏" else "显示", fontSize = 12.sp, color = Accent)
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(20.dp))
                        Button(
                            enabled = accountInput.isNotBlank() && passwordInput.isNotBlank() && !busy,
                            onClick = {
                                scope.launch {
                                    busy = true
                                    status = "正在登录123云盘…"
                                    try {
                                        val token = Drive123.login(accountInput, passwordInput)
                                        finish(token, emptyMap())
                                    } catch (e: Exception) {
                                        status = "登录失败：" + (e.message ?: "账号或密码错误")
                                        busy = false
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth().height(48.dp)
                        ) {
                            if (busy) {
                                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(8.dp))
                            }
                            Text("登录", fontSize = 15.sp)
                        }
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = { showWebLogin = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("使用网页登录 (如遇验证码)")
                        }
                        if (status.isNotBlank()) {
                            Spacer(Modifier.height(12.dp))
                            Text(status, color = if (status.contains("失败")) MaterialTheme.colorScheme.error else TextDim, fontSize = 13.sp)
                        }
                    }

                    currentTabTitle == "账号密码登录" -> {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = Surface1,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    Icons.Filled.Lock,
                                    contentDescription = null,
                                    tint = Accent,
                                    modifier = Modifier.size(44.dp)
                                )
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    if (type == Source.TYPE_115) "115 官方安全登录" else "阿里云盘官方安全登录",
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    if (type == Source.TYPE_115)
                                        "采用官方安全网页登录，支持手机号/115账号+密码或短信登录，支持滑块验证码。\n登录完成后将自动提取凭证并保存。"
                                    else
                                        "采用官方安全网页登录，支持手机号/账号+密码或短信验证码登录，支持人机滑块验证。\n登录完成后将自动提取 Token 并保存。",
                                    fontSize = 13.sp,
                                    color = TextDim,
                                    lineHeight = 18.sp,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(Modifier.height(16.dp))
                                OutlinedTextField(
                                    value = accountInput,
                                    onValueChange = { accountInput = it },
                                    label = { Text("账号 / 手机号 (选填，自动填入网页)") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(Modifier.height(10.dp))
                                OutlinedTextField(
                                    value = passwordInput,
                                    onValueChange = { passwordInput = it },
                                    label = { Text("密码 (选填，自动填入网页)") },
                                    singleLine = true,
                                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                                    trailingIcon = {
                                        TextButton(onClick = { showPassword = !showPassword }) {
                                            Text(if (showPassword) "隐藏" else "显示", fontSize = 12.sp, color = Accent)
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(Modifier.height(18.dp))
                                Button(
                                    onClick = { showWebLogin = true },
                                    modifier = Modifier.fillMaxWidth().height(48.dp)
                                ) {
                                    Text(if (type == Source.TYPE_115) "打开 115 账号密码登录" else "打开阿里云盘账号密码登录", fontSize = 15.sp)
                                }
                            }
                        }
                    }

                    currentTabTitle == "网页登录" -> {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = Surface1,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Filled.Lock, contentDescription = null, tint = Accent, modifier = Modifier.size(44.dp))
                                Spacer(Modifier.height(12.dp))
                                Text("123云盘网页登录", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.height(8.dp))
                                Text("在官方网页中登录，登录成功后泛舟将自动提取凭据完成连接。", fontSize = 13.sp, color = TextDim, textAlign = TextAlign.Center)
                                Spacer(Modifier.height(20.dp))
                                Button(
                                    onClick = { showWebLogin = true },
                                    modifier = Modifier.fillMaxWidth().height(48.dp)
                                ) {
                                    Text("打开 123云盘网页登录", fontSize = 15.sp)
                                }
                            }
                        }
                    }

                    else -> {
                        // Manual input tab
                        val manualLabel = when (type) {
                            Source.TYPE_ALIYUN -> "refresh_token"
                            Source.TYPE_115 -> "Cookie"
                            Source.TYPE_123 -> "Token (Bearer xxx)"
                            else -> "Token / Cookie"
                        }
                        Text("手动填入凭据连接网盘", color = TextDim, fontSize = 14.sp)
                        Spacer(Modifier.height(16.dp))
                        OutlinedTextField(
                            manualText, { manualText = it },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 4,
                            label = { Text(manualLabel) },
                        )
                        Spacer(Modifier.height(16.dp))
                        Button(
                            enabled = manualText.isNotBlank() && !busy,
                            onClick = { scope.launch { finish(manualText.trim(), emptyMap()) } },
                            modifier = Modifier.fillMaxWidth().height(48.dp)
                        ) {
                            if (busy) {
                                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(8.dp))
                            }
                            Text("保存并连接", fontSize = 15.sp)
                        }
                        if (status.isNotBlank()) {
                            Spacer(Modifier.height(12.dp))
                            Text(status, color = if (status.contains("失败")) MaterialTheme.colorScheme.error else TextDim, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}
