package com.watchlisten.music.data

import com.watchlisten.music.model.*
import org.json.JSONArray
import org.json.JSONObject

/** 仅把已识别的官方响应字段映射为本应用模型，不从收费码猜测账号授权。 */
internal object BrowseParser {
    fun charts(response: JSONObject): CatalogPage<MusicCollection> {
        val data = data(response)
        val rows = array(data, "info", "list")
        val expanded = mutableListOf<JSONObject>()
        fun collectCharts(items: JSONArray, depth: Int) {
            objects(items).forEach { row ->
                expanded.add(row)
                if (depth < 3) row.optJSONArray("children")?.let { collectCharts(it, depth + 1) }
            }
        }
        collectCharts(rows, 0)
        val charts = expanded.mapNotNull { row ->
            val id = text(row, "rankid", "rank_id")
            val title = text(row, "rankname", "rank_name", "name")
            if (id.isBlank() || title.isBlank()) null else MusicCollection(id, title, CollectionKind.CHART,
                cover(row, "imgurl", "banner7url", "cover"), text(row, "update_frequency", "intro"),
                number(row, "song_count", "count"), description = text(row, "intro"))
        }.distinctBy { it.key }
        if (rows.length() > 0 && charts.isEmpty()) throw MusicFailure(MusicError.InvalidResponse)
        return CatalogPage(charts, charts.size, false, rawCount = rows.length())
    }

    fun playlists(response: JSONObject, page: Int, pageSize: Int, ownerId: String = ""): CatalogPage<MusicCollection> {
        val data = data(response)
        val rows = array(data, if (ownerId.isBlank()) "special_list" else "info", "lists", "list")
        val collections = objects(rows).mapNotNull { row ->
            val cloud = ownerId.isNotBlank()
            val id = if (cloud) text(row, "listid") else text(row, "global_collection_id", "list_create_gid")
            val title = text(row, "name", "specialname", "listname")
            if (id.isBlank() || title.isBlank()) null else MusicCollection(id, title,
                if (cloud) CollectionKind.CLOUD else CollectionKind.PLAYLIST,
                cover(row, "pic", "flexible_cover", "cover", "imgurl"),
                if (cloud) when {
                    text(row, "list_create_userid") == ownerId -> "我创建的"
                    title == "我喜欢" -> "云端喜欢"
                    else -> "已收藏的"
                } else text(row, "intro"), number(row, "count", "songcount", "song_count"), ownerId,
                text(row, "intro", "description"), if (cloud) row.optInt("type", 0) else 0)
        }.distinctBy { it.key }
        val total = number(data, "total", "total_count")
        if (rows.length() > 0 && collections.isEmpty()) throw MusicFailure(MusicError.InvalidResponse)
        return CatalogPage(collections, total, explicitNext(data) ?: hasNext(page, pageSize, rows.length(), total), rawCount = rows.length())
    }

    fun tracks(response: JSONObject, collection: MusicCollection, page: Int, pageSize: Int): CatalogPage<Track> {
        val data = data(response)
        val rows = when (collection.kind) {
            CollectionKind.CHART -> array(data, "songlist", "songs", "info")
            CollectionKind.DAILY -> array(data, "song_list", "songs")
            CollectionKind.CLOUD -> array(data, "fileinfo", "info", "songs", "list")
            else -> array(data, "songs", "info", "list")
        }
        val tracks = objects(rows).mapNotNull(::track).distinctBy { it.id }
        val listInfo = data.optJSONObject("list_info")
        if (rows.length() > 0 && tracks.isEmpty()) throw MusicFailure(MusicError.InvalidResponse)
        val total = number(data, "total", "total_count") ?: listInfo?.let { number(it, "count", "song_count") }
            ?: number(data, "count")
            ?: if (collection.kind == CollectionKind.DAILY) rows.length() else collection.count
        val description = listInfo?.let { text(it, "intro", "description") }.orEmpty().ifBlank {
            if (collection.kind == CollectionKind.DAILY) text(data, "creation_date").takeIf(String::isNotBlank)?.let { "服务端推荐日期：$it" }.orEmpty() else ""
        }
        val hasMore = collection.kind != CollectionKind.DAILY && (explicitNext(data) ?: hasNext(page, pageSize, rows.length(), total))
        return CatalogPage(tracks, total, hasMore, description, rows.length())
    }

    fun track(row: JSONObject): Track? {
        val audio = row.optJSONObject("audio_info") ?: row
        val deprecated = row.optJSONObject("deprecated") ?: JSONObject()
        val base = row.optJSONObject("base") ?: row
        val trans = row.optJSONObject("trans_param") ?: row.optJSONObject("TransParam") ?: audio.optJSONObject("trans_param")
            ?: row.optJSONObject("copyright")?.optJSONObject("trans_param_departed")
        val hash = text(audio, "hash", "filehash", "FileHash").ifBlank { text(deprecated, "hash") }.lowercase()
        if (!hash.matches(Regex("[a-f0-9]{32}"))) return null
        val filename = text(row, "filename", "FileName", "name")
        val title = text(row, "ori_audio_name", "songname", "audio_name", "SongName")
            .ifBlank { text(base, "audio_name", "songname") }.ifBlank { filename.substringAfter(" - ") }
        if (title.isBlank()) return null
        val artist = text(row, "author_name", "singername", "SingerName", "singer")
            .ifBlank { text(base, "author_name") }
            .ifBlank { row.optJSONArray("authors")?.let { authors -> objects(authors).map {
                text(it.optJSONObject("base") ?: it, "author_name", "name")
            }.filter(String::isNotBlank).joinToString("、") }.orEmpty() }
            .ifBlank { if (filename.contains(" - ")) filename.substringBefore(" - ") else "" }
        val album = row.optJSONObject("albuminfo") ?: row.optJSONObject("album_info")
        val albumId = long(row, "album_id", "albumid", "AlbumID").takeIf { it > 0 } ?: album?.let { long(it, "album_id", "id") } ?: 0
        val audioId = long(row, "mixsongid", "album_audio_id", "MixSongID").takeIf { it > 0 }
            ?: long(audio, "mixsongid", "album_audio_id").takeIf { it > 0 } ?: long(base, "album_audio_id")
        val ms = long(audio, "timelength", "timelen").takeIf { it > 0 }
            ?: long(row, "timelength", "timelen").takeIf { it > 0 }
            ?: long(deprecated, "duration").takeIf { it > 0 }
            ?: if (row.has("audio_info")) long(audio, "duration") else 0
        // 已核对官方响应：嵌套 audio_info.duration / deprecated.duration 为毫秒，推荐 time_length 为秒。
        val durationMs = if (ms > 0) ms else long(row, "time_length").takeIf { it > 0 }?.times(1000)
            ?: (long(audio, "Duration", "duration") * 1000)
        val payType = number(row, "PayType", "pay_type") ?: -1
        val normalizedRights = JSONObject().put("PayType", payType)
            .apply { trans?.let { put("TransParam", it) }; if (row.has("only_vip_playable")) put("only_vip_playable", row.opt("only_vip_playable")) }
        return Track(hash, clean(title), clean(artist), albumId, audioId, durationMs, payType,
            selectCoverUrl(cover(row, "cover", "sizable_cover", "imgurl", "Image"),
                album?.let { cover(it, "sizable_cover", "cover") }.orEmpty(), trans?.let { cover(it, "union_cover") }.orEmpty()),
            searchRights(normalizedRights))
    }

    fun hotWords(response: JSONObject): List<String> {
        val data = data(response)
        val rows = array(data, "list", "info", "hotword", "hotwords")
        val words = mutableListOf<String>()
        fun read(rows: JSONArray, depth: Int) {
            for (index in 0 until rows.length()) when (val row = rows.opt(index)) {
                is String -> if (row.isNotBlank()) words.add(row)
                is JSONObject -> {
                    text(row, "keyword", "searchword", "word").takeIf { it.isNotBlank() }?.let { words.add(it) }
                    if (depth < 3) for (key in listOf("keywords", "list", "hotword")) row.optJSONArray(key)?.let { read(it, depth + 1) }
                }
            }
        }
        read(rows, 0)
        if (rows.length() > 0 && words.isEmpty()) throw MusicFailure(MusicError.InvalidResponse)
        return words.map(::clean).filter(String::isNotBlank).distinct()
    }

    private fun data(response: JSONObject): JSONObject = response.optJSONObject("data")
        ?: throw MusicFailure(MusicError.InvalidResponse)
    private fun array(data: JSONObject, vararg names: String): JSONArray = names.firstNotNullOfOrNull { name ->
        data.optJSONArray(name) ?: data.optJSONObject(name)?.optJSONArray("data")
    } ?: throw MusicFailure(MusicError.InvalidResponse)
    private fun objects(rows: JSONArray) = (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }
    internal fun hasNext(page: Int, pageSize: Int, rawCount: Int, total: Int?): Boolean = rawCount > 0 &&
        if (total != null) page.toLong() * pageSize < total else rawCount >= pageSize
    private fun explicitNext(data: JSONObject): Boolean? = listOf("has_next", "has_more", "hasmore").firstNotNullOfOrNull {
        when (data.optString(it)) { "1", "true" -> true; "0", "false" -> false; else -> null }
    }
    private fun text(row: JSONObject, vararg names: String) = names.firstNotNullOfOrNull { row.optString(it).takeUnless { value -> value.isBlank() || value == "null" } }.orEmpty()
    private fun number(row: JSONObject, vararg names: String): Int? = names.firstNotNullOfOrNull {
        if (row.has(it)) row.optString(it).toLongOrNull()?.takeIf { n -> n >= 0 && n <= Int.MAX_VALUE }?.toInt() else null
    }
    private fun long(row: JSONObject, vararg names: String): Long = names.firstNotNullOfOrNull {
        row.optString(it).toLongOrNull()?.takeIf { value -> value > 0 }
    } ?: 0
    private fun cover(row: JSONObject, vararg keys: String) = selectCoverUrl(text(row, *keys))
    private fun clean(value: String) = value.replace(Regex("<[^>]*>"), "").trim()
}
