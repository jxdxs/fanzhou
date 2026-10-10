package com.kevin.fanzhou.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.common.PlaybackException
import com.kevin.fanzhou.data.SmbClient
import java.io.IOException
import kotlin.math.min

/** Media3 DataSource reading `smb://<sourceId>/<path>` through smbj, with a 1 MB read-ahead buffer. */
@OptIn(UnstableApi::class)
class SmbDataSource : BaseDataSource(true) {
    private var file: SmbClient.SmbFile? = null
    private var uri: Uri? = null
    private var position = 0L
    private var bytesRemaining = 0L
    private val buf = ByteArray(BUFFER)
    private var bufStart = 0L
    private var bufLen = 0
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val sid = dataSpec.uri.host ?: throw IOException("无效的 SMB 地址")
        val client = SmbClient.get(sid) ?: throw IOException("SMB 媒体源不存在")
        val f = try { client.open(SmbClient.pathOf(dataSpec.uri.toString())) } catch (e: Exception) {
            throw DataSourceException(e, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        }
        file = f
        position = dataSpec.position
        if (f.length >= 0 && position > f.length) throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        bytesRemaining = when {
            dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
            f.length >= 0 -> f.length - position
            else -> C.LENGTH_UNSET.toLong()
        }
        bufLen = 0
        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val f = file ?: throw IOException("未打开")
        if (position < bufStart || position >= bufStart + bufLen) {
            bufStart = position
            bufLen = 0
            val want = if (bytesRemaining > 0) min(BUFFER.toLong(), bytesRemaining).toInt() else BUFFER
            while (bufLen < want) {
                val n = try { f.read(bufStart + bufLen, buf, bufLen, want - bufLen) } catch (e: Exception) {
                    throw DataSourceException(e, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
                }
                if (n < 0) break
                bufLen += n
            }
            if (bufLen == 0) return C.RESULT_END_OF_INPUT
        }
        val inBuf = (position - bufStart).toInt()
        var n = min(length, bufLen - inBuf)
        if (bytesRemaining > 0) n = min(n.toLong(), bytesRemaining).toInt()
        System.arraycopy(buf, inBuf, buffer, offset, n)
        position += n
        if (bytesRemaining > 0) bytesRemaining -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        file?.close()
        file = null
        uri = null
        if (opened) { opened = false; transferEnded() }
    }

    class Factory : DataSource.Factory {
        override fun createDataSource(): DataSource = SmbDataSource()
    }

    companion object { const val BUFFER = 1 shl 20 }
}

/** Sends smb:// to [SmbDataSource] and everything else to the upstream (http/content/file/cloud) source. */
@OptIn(UnstableApi::class)
class SchemeRoutingDataSource(private val smb: DataSource, private val other: DataSource) : DataSource {
    private var active: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        smb.addTransferListener(transferListener)
        other.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val ds = if (dataSpec.uri.scheme == "smb") smb else other
        active = ds
        return ds.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        active?.read(buffer, offset, length) ?: throw IOException("未打开")

    override fun getUri(): Uri? = active?.uri
    override fun getResponseHeaders(): Map<String, List<String>> = active?.responseHeaders ?: emptyMap()

    override fun close() {
        try { active?.close() } finally { active = null }
    }

    class Factory(private val upstream: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource = SchemeRoutingDataSource(SmbDataSource(), upstream.createDataSource())
    }
}
