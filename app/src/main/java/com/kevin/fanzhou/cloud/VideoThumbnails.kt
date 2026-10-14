package com.kevin.fanzhou.cloud

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.LruCache
import com.kevin.fanzhou.player.LocalProxy
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Automatically extracts and caches video frame snapshots (thumbnails) for cloud video files
 * that lack provider-supplied cover images.
 * Optimized for high speed with parallel workers and fast initial sync frame decoding.
 */
object VideoThumbnails {

    private val failedKeys = ConcurrentHashMap.newKeySet<String>()
    private val inProgress = ConcurrentHashMap.newKeySet<String>()
    private val memoryCache = LruCache<String, File>(256)

    // 6 parallel background workers for rapid batch extraction without UI jank
    private val thumbDispatcher = Executors.newFixedThreadPool(6) { r ->
        Thread(r, "VideoThumbExtractor").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }.asCoroutineDispatcher()

    fun trimMemory(level: Int) {
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            memoryCache.evictAll()
            failedKeys.clear()
        } else if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
            memoryCache.trimToSize(memoryCache.size() / 2)
        }
    }

    fun cacheFile(context: Context, sourceId: String, fileId: String): File {
        val dir = File(context.cacheDir, "video_thumbs").apply { if (!exists()) mkdirs() }
        val hash = try {
            val md = MessageDigest.getInstance("MD5")
            md.digest("$sourceId:$fileId".toByteArray()).joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            "$sourceId-$fileId".replace(Regex("""[^\w-]"""), "_")
        }
        return File(dir, "$hash.jpg")
    }

    fun getCached(context: Context, sourceId: String, fileId: String): File? {
        val key = "$sourceId:$fileId"
        memoryCache.get(key)?.let { return it }
        val f = cacheFile(context, sourceId, fileId)
        return if (f.exists() && f.length() > 0) {
            memoryCache.put(key, f)
            f
        } else null
    }

    suspend fun loadOrExtract(context: Context, sourceId: String, file: CloudFile): File? {
        if (!file.isVideo) return null
        val key = "$sourceId:${file.id}"
        val cached = getCached(context, sourceId, file.id)
        if (cached != null) return cached
        if (failedKeys.contains(key)) return null
        if (!inProgress.add(key)) return null

        return try {
            withContext(thumbDispatcher) {
                withTimeoutOrNull(5_000L) {
                    extract(context, sourceId, file)
                }
            } ?: run {
                failedKeys.add(key)
                null
            }
        } catch (_: Exception) {
            failedKeys.add(key)
            null
        } finally {
            inProgress.remove(key)
        }
    }

    private suspend fun extract(context: Context, sourceId: String, file: CloudFile): File? {
        val resolved = try {
            CloudDrive.resolveCached(sourceId, file)
        } catch (_: Exception) {
            return null
        }

        val url = resolved.url
        if (url.isBlank()) return null

        val retriever = MediaMetadataRetriever()
        return try {
            val uri = Uri.parse(url)
            val scheme = uri.scheme?.lowercase()
            when {
                scheme == "smb" -> {
                    retriever.setDataSource(LocalProxy.httpUrl(url))
                }
                scheme == "content" -> {
                    retriever.setDataSource(context, uri)
                }
                scheme == "file" || url.startsWith("/") -> {
                    retriever.setDataSource(url)
                }
                else -> {
                    val reqHeaders = HashMap(resolved.headers)
                    if (!reqHeaders.keys.any { it.equals("User-Agent", true) }) {
                        reqHeaders["User-Agent"] = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
                    }
                    retriever.setDataSource(url, reqHeaders)
                }
            }

            // OPTION_CLOSEST_SYNC at 1s / 0s decodes the initial sync frame from the buffer,
            // avoiding extra network Range seek requests.
            val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                retriever.getScaledFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 320, 180)
                    ?: retriever.getScaledFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 320, 180)
                    ?: retriever.getScaledFrameAtTime(3_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 320, 180)
            } else {
                (retriever.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC))?.let { raw ->
                    val w = 320
                    val h = (w.toFloat() / raw.width * raw.height).toInt().coerceAtLeast(1)
                    val scaled = Bitmap.createScaledBitmap(raw, w, h, true)
                    if (scaled != raw) raw.recycle()
                    scaled
                }
            } ?: return null

            val dest = cacheFile(context, sourceId, file.id)
            val tmp = File(dest.parentFile, "${dest.name}.tmp")
            try {
                FileOutputStream(tmp).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
                }
                tmp.renameTo(dest)
                memoryCache.put("$sourceId:${file.id}", dest)
                dest
            } finally {
                bitmap.recycle()
                if (tmp.exists()) tmp.delete()
            }
        } catch (_: Throwable) {
            null
        } finally {
            try { retriever.release() } catch (_: Throwable) {}
        }
    }
}
