package com.kevin.fanzhou.cloud

import com.kevin.fanzhou.data.DavEntry
import com.kevin.fanzhou.data.Repo
import com.kevin.fanzhou.data.Source
import com.kevin.fanzhou.data.WebDavClient

/**
 * WebDAV 以网盘形式接入：复用网盘浏览页（缩略图、续播进度、最近播放、收藏、搜索、排序、
 * 列表/网格/海报视图、面包屑、文件夹信息等），体验与 115 等网盘一致。
 * 文件 id = 资源的绝对 href；根目录 id = 源地址。
 */
class WebDavDrive(source: Source) : CloudDrive(source) {
    private val client = WebDavClient(source)
    override val rootId: String get() = client.rootUrl
    override val thumbHeaders: Map<String, String> get() = client.headers

    override suspend fun list(dirId: String): List<CloudFile> {
        val entries = client.list(dirId.ifEmpty { client.rootUrl })
        val store = Repo.store
        return entries.map { e ->
            if (e.isVideo) migratePosition(store, e.href)
            CloudFile(
                id = e.href,
                name = e.name,
                isDir = e.isDir,
                size = e.size,
                modified = e.modifiedMs,
            )
        }
    }

    /** 旧版 WebDAV 页以 href 作为续播 key；首次以网盘方式浏览时迁移到网盘 key，进度不丢。 */
    private fun migratePosition(store: com.kevin.fanzhou.data.Store, href: String) {
        val newKey = "cloud:${source.id}:$href"
        if (store.position(newKey) > 0) return
        val old = store.position(href)
        if (old > 0) store.savePosition(newKey, old)
    }

    override suspend fun resolve(file: CloudFile): Resolved {
        return Resolved(file.id, client.headers, null)
    }

    private fun entry(f: CloudFile) = DavEntry(f.name, f.id, f.isDir, f.size, null, f.modified)

    override suspend fun delete(file: CloudFile) = client.delete(entry(file))
    override suspend fun rename(file: CloudFile, newName: String) = client.rename(entry(file), newName)
}
