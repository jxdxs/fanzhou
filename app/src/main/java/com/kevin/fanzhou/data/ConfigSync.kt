package com.kevin.fanzhou.data

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Base64
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Config backup: export/import a JSON file, and sync the same JSON to a WebDAV folder.
 * Synced: prefs "fanzhou" (sources, logins, preferences, cloud recents) and "positions" (resume points).
 * Not synced: device_id (each device must stay a separate Jellyfin client), TMDB cache, the sync settings themselves.
 * Security: Supports AES-256-GCM password-based encryption (PBKDF2) to protect cloud credentials.
 * Multi-user: Supports profile labels to distinguish backups belonging to different users or devices.
 */
object ConfigSync {
    private const val FORMAT = 1
    private val SYNCED = listOf("fanzhou", "positions")
    private val EXCLUDED_KEYS = setOf("device_id")
    const val FILE_NAME = "fanzhou-config.json"

    private lateinit var app: Context
    private lateinit var sync: SharedPreferences
    private val listeners = ArrayList<SharedPreferences.OnSharedPreferenceChangeListener>()
    @Volatile private var applying = false
    private val worker by lazy { Handler(HandlerThread("config-sync").apply { start() }.looper) }
    private val main = Handler(Looper.getMainLooper())
    private val uploadTask = Runnable { if (autoSync && configured) runCatching { upload() } }

    /** Called after a config was applied, so the UI can reload. */
    var onApplied: (() -> Unit)? = null

    fun init(context: Context) {
        app = context.applicationContext
        sync = app.getSharedPreferences("sync", Context.MODE_PRIVATE)
        for (name in SYNCED) {
            val l = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (applying || key in EXCLUDED_KEYS) return@OnSharedPreferenceChangeListener
                sync.edit().putLong("local_modified", System.currentTimeMillis()).apply()
                if (autoSync && configured) { worker.removeCallbacks(uploadTask); worker.postDelayed(uploadTask, 15_000) }
            }
            listeners += l
            app.getSharedPreferences(name, Context.MODE_PRIVATE).registerOnSharedPreferenceChangeListener(l)
        }
        if (autoSync && configured) worker.post { runCatching { syncNow() } }
    }

    // ---- settings ----
    var davUrl: String
        get() = sync.getString("url", "") ?: ""
        set(v) { sync.edit().putString("url", v.trim().trimEnd('/')).apply() }
    var davUser: String
        get() = sync.getString("user", "") ?: ""
        set(v) { sync.edit().putString("user", v.trim()).apply() }
    var davPass: String
        get() = sync.getString("pass", "") ?: ""
        set(v) { sync.edit().putString("pass", v).apply() }
    var davDir: String
        get() = sync.getString("dir", "/fanzhou") ?: "/fanzhou"
        set(v) { sync.edit().putString("dir", "/" + v.trim().trim('/')).apply() }
    var autoSync: Boolean
        get() = sync.getBoolean("auto", false)
        set(v) { sync.edit().putBoolean("auto", v).apply() }
    val lastSync: Long get() = sync.getLong("last_sync", 0)
    val configured: Boolean get() = davUrl.isNotBlank() && davUser.isNotBlank()
    private val localModified: Long get() = sync.getLong("local_modified", 0)

    /** 用户档案 / 身份标识：用于区分不同使用者的备份（如“个人”、“客厅电视”、“张三”） */
    var profileName: String
        get() = sync.getString("profile_name", "默认") ?: "默认"
        set(v) { sync.edit().putString("profile_name", v.trim()).apply() }

    /** WebDAV 备份加密密码（选填，设置后自动对 WebDAV 备份进行 AES-256 加密） */
    var davEncryptionPass: String
        get() = sync.getString("dav_enc_pass", "") ?: ""
        set(v) { sync.edit().putString("dav_enc_pass", v).apply() }

    var davLoggedIn: Boolean
        get() = sync.getBoolean("dav_logged_in", configured)
        set(v) { sync.edit().putBoolean("dav_logged_in", v).apply() }

    // SSH settings
    var sshHost: String
        get() = sync.getString("ssh_host", "") ?: ""
        set(v) { sync.edit().putString("ssh_host", v.trim()).apply() }
    var sshPort: String
        get() = sync.getString("ssh_port", "22") ?: "22"
        set(v) { sync.edit().putString("ssh_port", v.trim()).apply() }
    var sshUser: String
        get() = sync.getString("ssh_user", "") ?: ""
        set(v) { sync.edit().putString("ssh_user", v.trim()).apply() }
    var sshPass: String
        get() = sync.getString("ssh_pass", "") ?: ""
        set(v) { sync.edit().putString("ssh_pass", v).apply() }
    var sshDir: String
        get() = sync.getString("ssh_dir", "/fanzhou") ?: "/fanzhou"
        set(v) { sync.edit().putString("ssh_dir", "/" + v.trim().trim('/')).apply() }
    var sshLoggedIn: Boolean
        get() = sync.getBoolean("ssh_logged_in", false)
        set(v) { sync.edit().putBoolean("ssh_logged_in", v).apply() }

    // Google Drive settings
    var gdriveClientId: String
        get() = sync.getString("gdrive_client_id", "") ?: ""
        set(v) { sync.edit().putString("gdrive_client_id", v.trim()).apply() }
    var gdriveClientSecret: String
        get() = sync.getString("gdrive_client_secret", "") ?: ""
        set(v) { sync.edit().putString("gdrive_client_secret", v.trim()).apply() }
    var gdriveDir: String
        get() = sync.getString("gdrive_dir", "fanzhou-backup") ?: "fanzhou-backup"
        set(v) { sync.edit().putString("gdrive_dir", v.trim()).apply() }
    var gdriveLoggedIn: Boolean
        get() = sync.getBoolean("gdrive_logged_in", false)
        set(v) { sync.edit().putBoolean("gdrive_logged_in", v).apply() }

    // ---- encryption & inspection ----
    data class BackupInfo(
        val isEncrypted: Boolean,
        val profile: String,
        val device: String,
        val exportedAt: Long,
        val modified: Long
    )

    fun inspect(text: String): BackupInfo {
        val o = Json.parseToJsonElement(text).jsonObject
        require(o["app"]?.jsonPrimitive?.contentOrNull == "fanzhou") { "不是泛舟的配置文件" }
        val isEncrypted = o["encrypted"]?.jsonPrimitive?.booleanOrNull ?: false
        val profile = o["profile"]?.jsonPrimitive?.contentOrNull?.ifBlank { "默认" } ?: "默认"
        val device = o["device"]?.jsonPrimitive?.contentOrNull?.ifBlank { "未知设备" } ?: "未知设备"
        val exportedAt = o["exportedAt"]?.jsonPrimitive?.longOrNull ?: 0L
        val modified = o["modified"]?.jsonPrimitive?.longOrNull ?: 0L
        return BackupInfo(isEncrypted, profile, device, exportedAt, modified)
    }

    fun encrypt(plainText: String, pass: String, profile: String = profileName): String {
        val random = SecureRandom()
        val salt = ByteArray(16).also { random.nextBytes(it) }
        val iv = ByteArray(12).also { random.nextBytes(it) }
        val keySpec = PBEKeySpec(pass.toCharArray(), salt, 65536, 256)
        val keyBytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(keySpec).encoded
        val secretKey = SecretKeySpec(keyBytes, "AES")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
        val ciphertext = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))

        return buildJsonObject {
            put("app", "fanzhou")
            put("format", FORMAT)
            put("encrypted", true)
            put("cipher", "AES-256-GCM")
            put("kdf", "PBKDF2WithHmacSHA256")
            put("iterations", 65536)
            put("profile", profile)
            put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            put("exportedAt", System.currentTimeMillis())
            put("modified", localModified.takeIf { it > 0 } ?: System.currentTimeMillis())
            put("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            put("iv", Base64.encodeToString(iv, Base64.NO_WRAP))
            put("ciphertext", Base64.encodeToString(ciphertext, Base64.NO_WRAP))
        }.toString()
    }

    fun decrypt(encryptedJson: String, pass: String): String {
        val o = Json.parseToJsonElement(encryptedJson).jsonObject
        require(o["app"]?.jsonPrimitive?.contentOrNull == "fanzhou") { "不是泛舟的配置文件" }
        require(o["encrypted"]?.jsonPrimitive?.booleanOrNull == true) { "此配置文件未加密" }
        val salt = Base64.decode(o["salt"]!!.jsonPrimitive.content, Base64.NO_WRAP)
        val iv = Base64.decode(o["iv"]!!.jsonPrimitive.content, Base64.NO_WRAP)
        val ciphertext = Base64.decode(o["ciphertext"]!!.jsonPrimitive.content, Base64.NO_WRAP)
        val iterations = o["iterations"]?.jsonPrimitive?.intOrNull ?: 65536

        val keySpec = PBEKeySpec(pass.toCharArray(), salt, iterations, 256)
        val keyBytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(keySpec).encoded
        val secretKey = SecretKeySpec(keyBytes, "AES")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
        return try {
            cipher.doFinal(ciphertext).decodeToString()
        } catch (_: Exception) {
            throw IllegalArgumentException("解密失败：密码错误或备份文件已损坏")
        }
    }

    // ---- snapshot ----
    fun export(password: String? = null, profile: String = profileName): String {
        val plain = exportPlain(profile)
        return if (!password.isNullOrBlank()) {
            encrypt(plain, password, profile)
        } else {
            plain
        }
    }

    private fun exportPlain(profile: String = profileName): String {
        val root = buildJsonObject {
            put("app", "fanzhou")
            put("format", FORMAT)
            put("encrypted", false)
            put("profile", profile)
            put("modified", localModified.takeIf { it > 0 } ?: System.currentTimeMillis())
            put("exportedAt", System.currentTimeMillis())
            put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            putJsonObject("prefs") {
                for (name in SYNCED) {
                    putJsonObject(name) {
                        for ((k, v) in app.getSharedPreferences(name, Context.MODE_PRIVATE).all) {
                            if (k in EXCLUDED_KEYS || v == null) continue
                            put(k, buildJsonObject {
                                when (v) {
                                    is String -> { put("t", "s"); put("v", v) }
                                    is Int -> { put("t", "i"); put("v", v) }
                                    is Long -> { put("t", "l"); put("v", v) }
                                    is Float -> { put("t", "f"); put("v", v) }
                                    is Boolean -> { put("t", "b"); put("v", v) }
                                    is Set<*> -> { put("t", "ss"); put("v", JsonArray(v.map { JsonPrimitive(it.toString()) })) }
                                    else -> { put("t", "s"); put("v", v.toString()) }
                                }
                            })
                        }
                    }
                }
            }
        }
        return root.toString()
    }

    private fun parse(text: String): JsonObject {
        val o = Json.parseToJsonElement(text).jsonObject
        require(o["app"]?.jsonPrimitive?.contentOrNull == "fanzhou") { "不是泛舟的配置文件" }
        return o
    }

    /** Replaces local config with [text]. Resume points from this device that the file lacks are kept. */
    fun import(text: String, password: String? = null) {
        val info = inspect(text)
        val plain = if (info.isEncrypted) {
            require(!password.isNullOrBlank()) { "请输入解密密码" }
            decrypt(text, password)
        } else {
            text
        }
        val o = parse(plain)
        val prefs = o["prefs"]!!.jsonObject
        applying = true
        try {
            for (name in SYNCED) {
                val src = prefs[name]?.jsonObject ?: continue
                val sp = app.getSharedPreferences(name, Context.MODE_PRIVATE)
                val keep = sp.all.filterKeys { it in EXCLUDED_KEYS || name == "positions" }
                val e = sp.edit().clear()
                for ((k, v) in keep) if (v is Long) e.putLong(k, v) else if (v is String) e.putString(k, v)
                for ((k, el) in src) {
                    if (k in EXCLUDED_KEYS) continue
                    val f = el.jsonObject; val p = f["v"]
                    when (f["t"]?.jsonPrimitive?.content) {
                        "s" -> e.putString(k, p!!.jsonPrimitive.content)
                        "i" -> e.putInt(k, p!!.jsonPrimitive.int)
                        "l" -> e.putLong(k, p!!.jsonPrimitive.long)
                        "f" -> e.putFloat(k, p!!.jsonPrimitive.float)
                        "b" -> e.putBoolean(k, p!!.jsonPrimitive.boolean)
                        "ss" -> e.putStringSet(k, p!!.jsonArray.map { it.jsonPrimitive.content }.toSet())
                    }
                }
                e.commit()
            }
            val m = o["modified"]?.jsonPrimitive?.longOrNull ?: System.currentTimeMillis()
            sync.edit().putLong("local_modified", m).commit()
            if (info.profile.isNotBlank() && info.profile != "默认") {
                profileName = info.profile
            }
        } finally { applying = false }
        main.post { Repo.store.reloadSources(); onApplied?.invoke() }
    }

    // ---- WebDAV ----
    private fun fileUrl(): String = davUrl.trimEnd('/') + davDir.trimEnd('/') + "/" + FILE_NAME
    private fun auth(): String = "Basic " + Base64.encodeToString("$davUser:$davPass".toByteArray(), Base64.NO_WRAP)

    /** Tests WebDAV server connectivity and credentials. */
    fun testDavLogin(url: String, user: String, pass: String): String {
        val u = url.trim().trimEnd('/')
        require(u.isNotBlank()) { "服务器地址不能为空" }
        require(user.trim().isNotBlank()) { "用户名不能为空" }
        val target = "$u/"
        val authHeader = "Basic " + Base64.encodeToString("${user.trim()}:$pass".toByteArray(), Base64.NO_WRAP)
        val req = Request.Builder()
            .url(target)
            .header("Authorization", authHeader)
            .method("PROPFIND", "<?xml version=\"1.0\" encoding=\"utf-8\"?><D:propfind xmlns:D=\"DAV:\"><D:prop><D:resourcetype/></D:prop></D:propfind>".toRequestBody("application/xml".toMediaType()))
            .header("Depth", "0")
            .build()
        Repo.http.newCall(req).execute().use { resp ->
            if (resp.code == 401) throw IllegalArgumentException("WebDAV 用户名或密码错误")
            if (resp.code == 405) {
                val opt = Request.Builder().url(target).header("Authorization", authHeader).build()
                Repo.http.newCall(opt).execute().use { o ->
                    if (o.code == 401) throw IllegalArgumentException("WebDAV 用户名或密码错误")
                }
            } else if (!resp.isSuccessful && resp.code != 404 && resp.code != 207) {
                throw IllegalArgumentException("服务器响应异常（HTTP ${resp.code}）")
            }
        }
        return "连接成功"
    }

    /** Uploads the local config. Blocking; call off the main thread. */
    fun upload() {
        val content = export(password = davEncryptionPass.takeIf { it.isNotBlank() }, profile = profileName)
        val body = content.toRequestBody("application/json; charset=utf-8".toMediaType())
        var code = put(body)
        if (code == 404 || code == 409) {
            Repo.http.newCall(Request.Builder().url(davUrl.trimEnd('/') + davDir.trimEnd('/') + "/").header("Authorization", auth())
                .method("MKCOL", null).build()).execute().close()
            code = put(body)
        }
        check(code in 200..299) { if (code == 401) "WebDAV 用户名或密码错误" else "上传失败（HTTP $code）" }
        sync.edit().putLong("last_sync", System.currentTimeMillis()).putLong("remote_seen", localModified).apply()
    }

    private fun put(body: okhttp3.RequestBody): Int =
        Repo.http.newCall(Request.Builder().url(fileUrl()).header("Authorization", auth()).put(body).build()).execute().use { it.code }

    /** Downloads the remote config, or null when there is none yet. Blocking. */
    fun download(): String? =
        Repo.http.newCall(Request.Builder().url(fileUrl()).header("Authorization", auth()).build()).execute().use { r ->
            when {
                r.code == 404 -> null
                r.code == 401 -> error("WebDAV 用户名或密码错误")
                !r.isSuccessful -> error("下载失败（HTTP ${r.code}）")
                else -> r.body!!.string()
            }
        }

    /** Restores from WebDAV. Blocking. Returns false when the server has no backup yet. */
    fun restore(): Boolean {
        val text = download() ?: return false
        val info = inspect(text)
        if (info.isEncrypted && davEncryptionPass.isBlank()) {
            error("WebDAV 备份已加密，请先填写 WebDAV 加密密码")
        }
        import(text, davEncryptionPass.takeIf { it.isNotBlank() })
        sync.edit().putLong("last_sync", System.currentTimeMillis()).putLong("remote_seen", localModified).apply()
        return true
    }

    /**
     * Two-way sync, newest wins: pull when the server copy is newer than this device's last change,
     * push when this device changed since. Returns a short status line. Blocking.
     */
    fun syncNow(): String {
        val text = download()
        if (text == null) { upload(); return "已上传（服务器上还没有备份）" }
        val info = inspect(text)
        if (info.isEncrypted && davEncryptionPass.isBlank()) {
            error("WebDAV 备份已加密，请先填写 WebDAV 加密密码")
        }
        val remoteModified = info.modified
        return when {
            remoteModified > localModified -> {
                import(text, davEncryptionPass.takeIf { it.isNotBlank() })
                sync.edit().putLong("last_sync", System.currentTimeMillis()).putLong("remote_seen", remoteModified).apply()
                "已从服务器更新配置"
            }
            localModified > remoteModified -> { upload(); "已上传本机配置" }
            else -> { sync.edit().putLong("last_sync", System.currentTimeMillis()).apply(); "已是最新" }
        }
    }
}
