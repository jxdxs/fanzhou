package com.kevin.fanzhou.data

import android.content.Context
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

object Repo {
    lateinit var store: Store
        private set
    lateinit var http: OkHttpClient
        private set

    fun init(context: Context) {
        store = Store(context)
        FolderStatCache.init(context)
        // Home servers often use self-signed certificates, so trust them.
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(trustAll), SecureRandom()) }
        http = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .sslSocketFactory(ssl.socketFactory, trustAll)
            .hostnameVerifier { _, _ -> true }
            .followRedirects(true)
            // 飞牛影视 ignores api_key in the URL on media/image endpoints: attach the
            // token as headers for requests to a 飞牛影视 source (covers Coil image
            // loads and Exo streams; MPV gets headers via PlayItem.headers).
            .addInterceptor { chain ->
                val req = chain.request()
                val hdrs = fnosAuthHeaders(req.url.host, req.url.port)
                if (hdrs.isEmpty()) chain.proceed(req)
                else chain.proceed(req.newBuilder().apply { hdrs.forEach { (k, v) -> header(k, v) } }.build())
            }
            .build()
    }

    /** Auth headers when [host]:[port] belongs to a 飞牛影视 source, else empty. */
    private fun fnosAuthHeaders(host: String, port: Int): Map<String, String> {
        if (!::store.isInitialized) return emptyMap()
        val src = store.sources.value.firstOrNull { s ->
            s.isFnos && !s.token.isNullOrEmpty() && runCatching {
                val u = s.url.toHttpUrl()
                u.host == host && u.port == port
            }.getOrDefault(false)
        } ?: return emptyMap()
        return JellyfinClient(src).authHeaders()
    }

    private val jfClients = HashMap<String, JellyfinClient>()
    fun jellyfin(source: Source): JellyfinClient = synchronized(jfClients) {
        val c = jfClients[source.id]
        if (c != null && c.source == source) c else JellyfinClient(source).also { jfClients[source.id] = it }
    }
    fun jellyfin(sourceId: String): JellyfinClient? = store.get(sourceId)?.let { jellyfin(it) }

    fun webdav(source: Source) = WebDavClient(source)

    /** Folder browser for WebDAV and SMB sources. */
    fun browser(source: Source): FileBrowser =
        if (source.isSmb) SmbClient.get(source.id) ?: SmbClient(source) else WebDavClient(source)
}
