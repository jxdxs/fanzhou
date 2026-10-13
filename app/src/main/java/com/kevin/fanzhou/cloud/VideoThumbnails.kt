package com.kevin.fanzhou.cloud

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import com.kevin.fanzhou.player.LocalProxy
import kotlinx.coroutines.Dispatchers
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
 */
object VideoThumbnails {

    private val failedKeys = ConcurrentHashMap.newKeySet<String>()
    private val inProgress = ConcurrentHashMap.newKeySet<String>()

    // Bounded to 2 background threads to avoid stressing network and device resources
    private val thumbDispatcher = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "VideoThumbExtractor").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }.asCoroutineDispatcher()

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
        val f = cacheFile(context, sourceId, fileId)
        return if (f.exists() && f.length() > 0) f else null
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
                withTimeoutOrNull(8_000L) {
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
                resolved.headers.isNotEmpty() -> {
                    retriever.setDataSource(url, resolved.headers)
                }
                else -> {
                    retriever.setDataSource(url)
                }
            }

            // Prefer frame at 5 seconds, fallback to 1s, then 0s
            val targetUs = 5_000_000L
            val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                retriever.getScaledFrameAtTime(targetUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 360, 202)
                    ?: retriever.getScaledFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 360, 202)
                    ?: retriever.getScaledFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 360, 202)
            } else {
                retriever.getFrameAtTime(targetUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { raw ->
                    val w = 360
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
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                }
                tmp.renameTo(dest)
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
