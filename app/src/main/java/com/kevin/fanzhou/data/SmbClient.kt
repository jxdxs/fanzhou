package com.kevin.fanzhou.data

import android.net.Uri
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.msfscc.fileinformation.FileStandardInformation
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.protocol.commons.EnumWithValue
import com.hierynomus.security.bc.BCSecurityProvider
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.EnumSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * SMB2/3 share browsing and reading via smbj.
 *
 * Files are addressed as `smb://<sourceId>/<path inside share>` (each segment Uri-encoded), so
 * credentials never appear in URIs and the player can look the source up again.
 */
class SmbClient(override val source: Source) : FileBrowser {
    val host get() = source.url.trim()
    val port get() = source.extra["port"]?.toIntOrNull() ?: 445
    val shareName get() = source.extra["share"]?.trim('/', '\\', ' ') ?: ""
    val domain get() = source.extra["domain"] ?: ""
    val guest get() = source.extra["guest"] == "1"

    override val rootUrl get() = "smb://${source.id}/"

    private var connection: Connection? = null
    private var session: Session? = null
    private var share: DiskShare? = null
    private val lock = Any()

    fun diskShare(): DiskShare = synchronized(lock) {
        share?.takeIf { it.isConnected }?.let { return it }
        runCatching { session?.close() }
        runCatching { connection?.close() }
        if (shareName.isEmpty()) throw IOException("请填写共享名")
        val conn = smbClient.connect(host, port)
        val auth = when {
            guest -> AuthenticationContext.guest()
            source.username.isBlank() -> AuthenticationContext.anonymous()
            else -> AuthenticationContext(source.username.trim(), source.password.toCharArray(), domain.ifBlank { null })
        }
        val sess = try { conn.authenticate(auth) } catch (e: Exception) {
            runCatching { conn.close() }
            throw IOException("SMB 认证失败：${e.message ?: e.javaClass.simpleName}", e)
        }
        val ds = try { sess.connectShare(shareName) as? DiskShare } catch (e: Exception) {
            runCatching { sess.close() }; runCatching { conn.close() }
            throw IOException("无法打开共享「$shareName」：${e.message ?: e.javaClass.simpleName}", e)
        } ?: throw IOException("「$shareName」不是文件共享")
        connection = conn; session = sess; share = ds
        ds
    }

    private fun <T> withShare(block: (DiskShare) -> T): T = try {
        block(diskShare())
    } catch (e: Exception) {
        // A stale connection (Wi-Fi switch, server restart): reconnect once.
        synchronized(lock) { runCatching { share?.close() }; share = null }
        block(diskShare())
    }

    override suspend fun list(url: String): List<DavEntry> = withContext(Dispatchers.IO) {
        val dir = pathOf(url)
        val items = withShare { it.list(dir.replace('/', '\\')) }
        items.filter { val n = it.fileName; n != "." && n != ".." && !n.startsWith(".") && !n.startsWith("@") && !n.startsWith("$") && n != "#recycle" }
            .filter { !EnumWithValue.EnumUtils.isSet(it.fileAttributes, FileAttributes.FILE_ATTRIBUTE_HIDDEN) }
            .map { info ->
                val isDir = EnumWithValue.EnumUtils.isSet(info.fileAttributes, FileAttributes.FILE_ATTRIBUTE_DIRECTORY)
                val childPath = if (dir.isEmpty()) info.fileName else "$dir/${info.fileName}"
                val ms = runCatching { info.lastWriteTime.toEpochMillis() }.getOrDefault(0L)
                DavEntry(
                    name = info.fileName,
                    href = uriFor(source.id, childPath) + if (isDir) "/" else "",
                    isDir = isDir,
                    size = info.endOfFile,
                    modified = null,
                    modifiedMs = ms,
                )
            }
            .sortedWith(compareBy<DavEntry>({ !it.isDir }, { WebDavClient.naturalKey(it.name) }))
    }

    suspend fun test() { list(rootUrl) }

    override suspend fun delete(e: DavEntry) = withContext(Dispatchers.IO) {
        val p = pathOf(e.href).replace('/', '\\')
        withShare { if (e.isDir) it.rmdir(p, true) else it.rm(p) }
    }

    override suspend fun rename(e: DavEntry, newName: String) = withContext(Dispatchers.IO) {
        val path = pathOf(e.href)
        val target = (path.substringBeforeLast('/', "").let { if (it.isEmpty()) newName else "$it/$newName" }).replace('/', '\\')
        withShare { s ->
            s.open(
                path.replace('/', '\\'), EnumSet.of(AccessMask.DELETE, AccessMask.GENERIC_READ), null,
                SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null,
            ).use { it.rename(target, false) }
        }
    }

    override fun folderName(url: String): String = pathOf(url).substringAfterLast('/').ifEmpty { shareName }

    override fun playItem(e: DavEntry, siblings: List<DavEntry>): PlayItem = PlayItem(
        url = e.href,
        title = e.name.substringBeforeLast('.'),
        startMs = Repo.store.position(e.href),
        sourceId = source.id,
        resumeKey = e.href,
        subtitles = WebDavClient.siblingSubtitles(e, siblings),
    )

    /** Opens a file for reading. Caller closes it. */
    fun open(path: String): SmbFile {
        val f = withShare {
            it.openFile(
                path.replace('/', '\\'), EnumSet.of(AccessMask.GENERIC_READ), null,
                SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null,
            )
        }
        val len = runCatching { f.getFileInformation(FileStandardInformation::class.java).endOfFile }.getOrDefault(-1L)
        return SmbFile(f, len)
    }

    class SmbFile(private val file: File, val length: Long) : AutoCloseable {
        /** Reads up to [len] bytes at [offset]; returns -1 at end of file. */
        fun read(offset: Long, buf: ByteArray, off: Int, len: Int): Int {
            if (length in 0..offset) return -1
            val n = file.read(buf, offset, off, len)
            return if (n <= 0) -1 else n
        }
        override fun close() { runCatching { file.close() } }
    }

    fun close() = synchronized(lock) {
        runCatching { share?.close() }; runCatching { session?.close() }; runCatching { connection?.close() }
        share = null; session = null; connection = null
    }

    companion object {
        val smbClient: SMBClient by lazy {
            SMBClient(
                SmbConfig.builder()
                    .withSecurityProvider(BCSecurityProvider())
                    // NTLM only: Kerberos/SPNEGO needs javax GSS classes that Android lacks.
                    .withAuthenticators(com.hierynomus.smbj.auth.NtlmAuthenticator.Factory())
                    .withTimeout(30, TimeUnit.SECONDS)
                    .withSoTimeout(30, TimeUnit.SECONDS)
                    .withReadBufferSize(1 shl 20)
                    .withMultiProtocolNegotiate(true)
                    .build()
            )
        }

        private val clients = ConcurrentHashMap<String, SmbClient>()
        fun get(sourceId: String): SmbClient? {
            val s = Repo.store.get(sourceId) ?: return null
            val c = clients[sourceId]
            if (c != null && c.source == s) return c
            c?.close()
            return SmbClient(s).also { clients[sourceId] = it }
        }

        fun uriFor(sourceId: String, path: String): String =
            "smb://$sourceId/" + path.split('/').filter { it.isNotEmpty() }.joinToString("/") { Uri.encode(it) }

        /** Path inside the share ("" for the share root), with '/' separators. */
        fun pathOf(url: String): String {
            val u = Uri.parse(url)
            return u.pathSegments.joinToString("/")
        }

        fun isSmb(url: String) = url.startsWith("smb://")
        fun sourceIdOf(url: String): String? = Uri.parse(url).host
    }
}
