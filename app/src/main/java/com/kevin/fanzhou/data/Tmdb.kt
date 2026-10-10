package com.kevin.fanzhou.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import java.net.URLEncoder

/** Optional poster scraping for WebDAV files via TMDB. */
object Tmdb {
    private val json = Json { ignoreUnknownKeys = true }
    private val gate = Semaphore(4)

    data class Guess(val title: String, val year: String?)

    fun guess(fileName: String): Guess {
        var s = fileName.substringBeforeLast('.')
        s = s.replace(Regex("\\[[^\\]]*\\]|【[^】]*】"), " ")
        val year = Regex("(19|20)\\d{2}").find(s)?.value
        val cut = Regex("(?i)(\\b(19|20)\\d{2}\\b|S\\d{1,2}E\\d{1,3}|\\bS\\d{1,2}\\b|\\bE\\d{1,3}\\b|第.{1,3}[季集]|2160p|1080p|720p|480p|4k|uhd|bluray|blu-ray|web-?dl|webrip|hdtv|remux|x26[45]|h\\.?26[45]|hevc|avc|hdr|dv|aac|dts|atmos|10bit)").find(s)
        if (cut != null && cut.range.first > 0) s = s.substring(0, cut.range.first)
        s = s.replace(Regex("[._\\-]+"), " ").replace(Regex("\\s+"), " ").trim()
        return Guess(s.ifEmpty { fileName }, year)
    }

    suspend fun poster(fileName: String): String? {
        val key = Repo.store.tmdbKey
        if (key.isEmpty()) return null
        val g = guess(fileName)
        val cacheKey = g.title + "|" + (g.year ?: "")
        Repo.store.tmdbCached(cacheKey)?.let { return it.ifEmpty { null } }
        return gate.withPermit {
            withContext(Dispatchers.IO) {
                try {
                    val q = URLEncoder.encode(g.title, "UTF-8")
                    val isBearer = key.length > 60
                    var url = "https://api.themoviedb.org/3/search/multi?query=$q&language=zh-CN&include_adult=false"
                    if (!isBearer) url += "&api_key=$key"
                    val b = Request.Builder().url(url)
                    if (isBearer) b.header("Authorization", "Bearer $key")
                    val body = Repo.http.newCall(b.build()).execute().use { it.body?.string() ?: "" }
                    val results = json.parseToJsonElement(body).jsonObject["results"]?.jsonArray ?: return@withContext null
                    val pick = results.map { it.jsonObject }.filter { it["poster_path"]?.jsonPrimitive?.contentOrNull != null }
                        .sortedByDescending { o ->
                            val d = (o["release_date"] ?: o["first_air_date"])?.jsonPrimitive?.contentOrNull ?: ""
                            if (g.year != null && d.startsWith(g.year)) 1 else 0
                        }.firstOrNull()
                    val path = pick?.get("poster_path")?.jsonPrimitive?.contentOrNull
                    val result = path?.let { "https://image.tmdb.org/t/p/w342$it" } ?: ""
                    Repo.store.tmdbPut(cacheKey, result)
                    result.ifEmpty { null }
                } catch (e: Exception) { null }
            }
        }
    }
}
