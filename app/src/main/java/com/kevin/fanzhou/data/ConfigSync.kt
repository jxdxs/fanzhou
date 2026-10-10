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

/**
 * Config backup: export/import a JSON file, and sync the same JSON to a WebDAV folder.
 * Synced: prefs "fanzhou" (sources, logins, preferences, cloud recents) and "positions" (resume points).
 * Not synced: device_id (each device must stay a separate Jellyfin client), TMDB cache, the sync settings themselves.
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
        get() = sync.getString("url", "https://dav.yinmaolee.fnos.net") ?: ""
        set(v) { sync.edit().putString("url", v.trim().trimEnd('/')).apply() }
    var davUser: String
        get() = sync.getString("user", "yun") ?: "yun"
        set(v) { sync.edit().putString("user", v.trim()).apply() }
    var davPass: String
        get() = sync.getString("pass", "") ?: ""
        set(v) { sync.edit().putString("pass", v).apply() }
    var davDir: String
        get() = sync.getString("dir", "/app/jellyfin-lyy") ?: "/app/jellyfin-lyy"
        set(v) { sync.edit().putString("dir", "/" + v.trim().trim('/')).apply() }
    var autoSync: Boolean
        get() = sync.getBoolean("auto", false)
        set(v) { sync.edit().putBoolean("auto", v).apply() }
    val lastSync: Long get() = sync.getLong("last_sync", 0)
    val configured: Boolean get() = davUrl.isNotBlank() && davUser.isNotBlank()
    private val localModified: Long get() = sync.getLong("local_modified", 0)

    // ---- snapshot ----
    fun export(): String {
        val root = buildJsonObject {
            put("app", "fanzhou")
            put("format", FORMAT)
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
    fun import(text: String) {
        val o = parse(text)
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
        } finally { applying = false }
        main.post { Repo.store.reloadSources(); onApplied?.invoke() }
    }

    // ---- WebDAV ----
    private fun fileUrl(): String = davUrl.trimEnd('/') + davDir.trimEnd('/') + "/" + FILE_NAME
    private fun auth(): String = "Basic " + Base64.encodeToString("$davUser:$davPass".toByteArray(), Base64.NO_WRAP)

    /** Uploads the local config. Blocking; call off the main thread. */
    fun upload() {
        val body = export().toRequestBody("application/json; charset=utf-8".toMediaType())
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
        import(text)
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
        val remoteModified = parse(text)["modified"]?.jsonPrimitive?.longOrNull ?: 0
        return when {
            remoteModified > localModified -> {
                import(text)
                sync.edit().putLong("last_sync", System.currentTimeMillis()).putLong("remote_seen", remoteModified).apply()
                "已从服务器更新配置"
            }
            localModified > remoteModified -> { upload(); "已上传本机配置" }
            else -> { sync.edit().putLong("last_sync", System.currentTimeMillis()).apply(); "已是最新" }
        }
    }
}
