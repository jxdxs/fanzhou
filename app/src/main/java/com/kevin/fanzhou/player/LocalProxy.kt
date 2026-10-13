package com.kevin.fanzhou.player

import android.net.Uri
import com.kevin.fanzhou.data.SmbClient
import java.io.BufferedOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.Executors

/**
 * Tiny localhost HTTP server so players that only speak HTTP (libmpv) can stream from SMB.
 * URL shape: http://127.0.0.1:<port>/smb/<sourceId>/<encoded path>. Supports HEAD and Range.
 */
object LocalProxy {
    @Volatile private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "fanzhou-proxy").apply { isDaemon = true } }

    @Synchronized
    private fun ensureStarted(): Int {
        server?.takeIf { !it.isClosed }?.let { return it.localPort }
        val s = ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"))
        server = s
        pool.execute {
            while (!s.isClosed) {
                val c = try { s.accept() } catch (_: Exception) { break }
                pool.execute { handle(c) }
            }
        }
        return s.localPort
    }

    /** Maps a smb:// uri to a local http url; other urls are returned unchanged. */
    fun httpUrl(url: String): String {
        if (!SmbClient.isSmb(url)) return url
        val u = Uri.parse(url)
        val port = ensureStarted()
        val path = u.pathSegments.joinToString("/") { Uri.encode(it) }
        return "http://127.0.0.1:$port/smb/${u.host}/$path"
    }

    private fun readLine(ins: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = ins.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
            if (sb.length > 16384) return null
        }
    }

    private fun handle(sock: Socket) {
        sock.use { s ->
            try {
                s.soTimeout = 60_000
                val ins = java.io.BufferedInputStream(s.getInputStream(), 8192)
                val out = BufferedOutputStream(s.getOutputStream(), 64 * 1024)
                val requestLine = readLine(ins) ?: return
                val headers = HashMap<String, String>()
                while (true) {
                    val l = readLine(ins) ?: break
                    if (l.isEmpty()) break
                    val i = l.indexOf(':')
                    if (i > 0) headers[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
                }
                val parts = requestLine.split(' ')
                if (parts.size < 2) return
                val method = parts[0]
                // Request path is already percent-encoded: /smb/<sid>/<seg>/<seg>
                val segs = parts[1].substringBefore('?').split('/').filter { it.isNotEmpty() }
                if (segs.size < 3 || segs[0] != "smb") { status(out, 404, "Not Found"); return }
                val sid = segs[1]
                val path = segs.drop(2).joinToString("/") { Uri.decode(it) }
                val client = SmbClient.get(sid) ?: run { status(out, 404, "Not Found"); return }
                val file = try { client.open(path) } catch (e: Exception) { status(out, 502, "Bad Gateway"); return }
                file.use { f ->
                    val total = f.length
                    var start = 0L
                    var end = if (total > 0) total - 1 else Long.MAX_VALUE
                    val range = headers["range"]
                    val partial = range != null && range.startsWith("bytes=") && total > 0
                    if (partial) {
                        val spec = range!!.removePrefix("bytes=").substringBefore(',')
                        val a = spec.substringBefore('-').trim()
                        val b = spec.substringAfter('-', "").trim()
                        if (a.isEmpty() && b.isNotEmpty()) { start = (total - b.toLong()).coerceAtLeast(0) }
                        else {
                            start = a.toLongOrNull() ?: 0
                            if (b.isNotEmpty()) end = minOf(b.toLong(), total - 1)
                        }
                        if (start >= total) {
                            out.write("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */$total\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                            out.flush(); return
                        }
                    }
                    val len = if (total > 0) end - start + 1 else -1
                    val sb = StringBuilder()
                    sb.append(if (partial) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
                    sb.append("Content-Type: ").append(mimeOf(path)).append("\r\n")
                    sb.append("Accept-Ranges: bytes\r\n")
                    if (len >= 0) sb.append("Content-Length: ").append(len).append("\r\n")
                    if (partial) sb.append("Content-Range: bytes ").append(start).append('-').append(end).append('/').append(total).append("\r\n")
                    sb.append("Connection: close\r\n\r\n")
                    out.write(sb.toString().toByteArray())
                    if (method == "HEAD") { out.flush(); return }
                    val buf = ByteArray(SmbDataSource.BUFFER)
                    var pos = start
                    while (len < 0 || pos <= end) {
                        val want = if (len < 0) buf.size else minOf(buf.size.toLong(), end - pos + 1).toInt()
                        val n = f.read(pos, buf, 0, want)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        pos += n
                    }
                    out.flush()
                }
            } catch (_: SocketException) {
                // Player closed the connection (seek); normal.
            } catch (_: Exception) { }
        }
    }

    private fun status(out: BufferedOutputStream, code: Int, msg: String) {
        out.write("HTTP/1.1 $code $msg\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
        out.flush()
    }

    private fun mimeOf(path: String) = when (path.substringAfterLast('.').lowercase()) {
        "mp4", "m4v" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "avi" -> "video/x-msvideo"
        "ts", "m2ts" -> "video/mp2t"
        "srt" -> "application/x-subrip"
        "ass", "ssa" -> "text/x-ssa"
        "vtt" -> "text/vtt"
        else -> "application/octet-stream"
    }
}
