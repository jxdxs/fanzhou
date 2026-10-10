package com.kevin.fanzhou.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.kevin.fanzhou.Routes
import com.kevin.fanzhou.cloud.CloudDrive
import com.kevin.fanzhou.cloud.CloudFile
import com.kevin.fanzhou.cloud.QrLogin
import com.kevin.fanzhou.cloud.QrState
import com.kevin.fanzhou.data.Repo
import com.kevin.fanzhou.data.Source
import com.kevin.fanzhou.data.Tmdb
import com.kevin.fanzhou.player.PlayerCore
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudLoginScreen(nav: NavHostController, type: String, editId: String?) {
    if (type == Source.TYPE_GDRIVE) { GoogleLoginScreen(nav, editId); return }
    val existing = remember(editId) { editId?.let { Repo.store.get(it) } }
    val label = Source(id = "", type = type, name = "", url = "").typeLabel
    var qr by remember { mutableStateOf<Bitmap?>(null) }
    var status by remember { mutableStateOf("正在获取二维码…") }
    var attempt by remember { mutableIntStateOf(0) }
    var manual by remember { mutableStateOf(false) }
    var manualText by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val login = remember(attempt) { QrLogin.forType(type) }
    val context = LocalContext.current
    fun doSave() {
        val b = qr ?: return
        val ok = saveToGallery(context, b, "${label}登录二维码_${System.currentTimeMillis() / 1000}")
        android.widget.Toast.makeText(context, if (ok) "已保存到相册（图片/泛舟），可在${label} App 扫一扫里从相册选择" else "保存失败", android.widget.Toast.LENGTH_LONG).show()
    }
    val permLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) doSave() else android.widget.Toast.makeText(context, "需要存储权限才能保存到相册", android.widget.Toast.LENGTH_SHORT).show() }

    suspend fun finish(token: String, extra: Map<String, String>) {
        busy = true
        status = "登录成功，正在读取网盘…"
        try {
            var s = Source(
                id = existing?.id ?: UUID.randomUUID().toString(), type = type,
                name = existing?.name ?: label, url = "", token = token, extra = extra,
            )
            Repo.store.upsert(s)
            val d = CloudDrive.create(s)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
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

    LaunchedEffect(attempt) {
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

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("登录$label") },
            navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Bg),
        )
    }) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
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
                if (android.os.Build.VERSION.SDK_INT < 29 && androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                    permLauncher.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                else doSave()
            }) {
                Icon(androidx.compose.ui.res.painterResource(com.kevin.fanzhou.R.drawable.ic_save), null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("保存二维码到相册")
            }
            Spacer(Modifier.height(24.dp))
            TextButton(onClick = { manual = !manual }) { Text(if (type == Source.TYPE_ALIYUN) "手动填写 refresh_token" else "手动填写 Cookie") }
            if (manual) {
                OutlinedTextField(
                    manualText, { manualText = it }, modifier = Modifier.fillMaxWidth(), minLines = 3,
                    label = { Text(if (type == Source.TYPE_ALIYUN) "refresh_token" else "Cookie") },
                )
                Spacer(Modifier.height(8.dp))
                Button(enabled = manualText.isNotBlank() && !busy, onClick = { scope.launch { finish(manualText.trim(), emptyMap()) } }, modifier = Modifier.fillMaxWidth()) {
                    Text("保存")
                }
            }
        }
    }
}
