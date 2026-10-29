package com.watchlisten.music.data

import com.watchlisten.music.model.*
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

interface DiscoverySource {
    suspend fun search(query: String, kind: DiscoveryKind, page: Int): CatalogPage<DiscoveryItem>
    suspend fun artist(id: Long): MusicArtist
    suspend fun artistSongs(id: Long, page: Int): CatalogPage<Track>
    suspend fun artistAlbums(id: Long, page: Int): CatalogPage<MusicCollection>
}

internal interface DiscoveryTransport {
    suspend fun call(call: CatalogCall, identity: SessionData?): JSONObject
    suspend fun songs(query: String, page: Int): SearchPage
}

class DiscoveryGateway internal constructor(private val transport: DiscoveryTransport,
                                            private val sessions: StateFlow<SessionData?>) : DiscoverySource {
    constructor(catalog: CatalogGateway, sessions: SessionStore) : this(object : DiscoveryTransport {
        override suspend fun call(call: CatalogCall, identity: SessionData?) = catalog.catalog(call, identity)
        override suspend fun songs(query: String, page: Int) = catalog.search(query, page)
    }, sessions.session)

    override suspend fun search(query: String, kind: DiscoveryKind, page: Int): CatalogPage<DiscoveryItem> {
        val keyword = query.trim().take(100)
        require(keyword.isNotBlank() && page > 0)
        if (kind == DiscoveryKind.SONGS) {
            val result = transport.songs(keyword, page)
            return CatalogPage(result.tracks.map { DiscoveryItem.Song(it) }, result.total.coerceAtLeast(result.tracks.size),
                BrowseParser.hasNext(page, SONG_PAGE_SIZE, result.tracks.size, result.total), rawCount = result.tracks.size)
        }
        val endpoint = when (kind) { DiscoveryKind.ARTISTS -> "author"; DiscoveryKind.ALBUMS -> "album"; else -> "special" }
        val queryValues = linkedMapOf("keyword" to keyword, "page" to "$page", "pagesize" to "$PAGE_SIZE",
            "platform" to "AndroidFilter", "iscorrection" to "1", "nocollect" to "0", "albumhide" to "0")
        if (kind == DiscoveryKind.ALBUMS) queryValues.putAll(mapOf("category" to "1", "sorttype" to "0", "searchsong" to "0"))
        return DiscoveryParser.search(transport.call(CatalogCall("/v1/search/$endpoint", "complexsearch.kugou.com",
            query = queryValues, headers = mapOf("kg-tid" to "1")), sessions.value), kind, page)
    }

    override suspend fun artist(id: Long): MusicArtist {
        require(id > 0)
        return DiscoveryParser.artist(transport.call(CatalogCall("/kmr/v3/author", "openapi.kugou.com",
            body = JSONObject().put("author_id", id), headers = AUTHOR_HEADERS), sessions.value), id)
    }

    override suspend fun artistSongs(id: Long, page: Int): CatalogPage<Track> {
        require(id > 0 && page > 0)
        val response = transport.call(CatalogCall("/openapi/kmr/v2/audio_group/author", query = mapOf(
            "author_id" to "$id", "area_code" to "all", "sort" to "1", "page" to "$page", "pagesize" to "$PAGE_SIZE",
            "replace_api_version" to "1", "mvdata_need" to "1", "show_audio_honor" to "1", "show_audio_tag" to "1",
            "replace_need" to "1"), headers = AUTHOR_HEADERS), sessions.value)
        return DiscoveryParser.songs(response, page)
    }

    override suspend fun artistAlbums(id: Long, page: Int): CatalogPage<MusicCollection> {
        require(id > 0 && page > 0)
        val response = transport.call(CatalogCall("/kmr/v1/author/albums", "openapi.kugou.com",
            body = JSONObject().put("author_id", id).put("page", page).put("pagesize", PAGE_SIZE)
                .put("sort", 3).put("category", 1).put("area_code", "all"), headers = AUTHOR_HEADERS), sessions.value)
        return DiscoveryParser.albums(response, page)
    }

    companion object {
        const val PAGE_SIZE = 30
        private const val SONG_PAGE_SIZE = 20
        private val AUTHOR_HEADERS = mapOf("kg-tid" to "36")
    }
}

internal object DiscoveryParser {
    fun search(response: JSONObject, kind: DiscoveryKind, page: Int, pageSize: Int = DiscoveryGateway.PAGE_SIZE): CatalogPage<DiscoveryItem> {
        val data = response.optJSONObject("data") ?: invalid()
        val rows = data.optJSONArray("lists") ?: invalid()
        val items = objects(rows).mapNotNull { row -> when (kind) {
            DiscoveryKind.ARTISTS -> searchArtist(row)?.let { DiscoveryItem.Artist(it) }
            DiscoveryKind.ALBUMS -> album(row)?.let { DiscoveryItem.Collection(it) }
            DiscoveryKind.PLAYLISTS -> playlist(row)?.let { DiscoveryItem.Collection(it) }
            DiscoveryKind.SONGS -> null
        } }.distinctBy(DiscoveryItem::key)
        return catalogPage(items, rows.length(), number(data, "total"), page, pageSize)
    }

    fun artist(response: JSONObject, expectedId: Long): MusicArtist {
        val data = response.optJSONObject("data") ?: invalid()
        val id = positiveId(data, "author_id") ?: invalid()
        val name = text(data, "author_name").takeIf(String::isNotBlank) ?: invalid()
        if (id != expectedId) invalid()
        return MusicArtist(id, name, avatar(text(data, "sizable_avatar")), number(data, "song_count"),
            number(data, "album_count"), text(data, "intro"))
    }

    fun songs(response: JSONObject, page: Int, pageSize: Int = DiscoveryGateway.PAGE_SIZE): CatalogPage<Track> {
        val data = response.optJSONObject("data") ?: invalid()
        val rows = data.optJSONArray("songs") ?: invalid()
        return catalogPage(objects(rows).mapNotNull(BrowseParser::track).distinctBy(Track::id), rows.length(),
            number(data, "total"), page, pageSize)
    }

    fun albums(response: JSONObject, page: Int, pageSize: Int = DiscoveryGateway.PAGE_SIZE): CatalogPage<MusicCollection> {
        val rows = response.optJSONArray("data") ?: invalid()
        return catalogPage(objects(rows).mapNotNull(::album).distinctBy(MusicCollection::key), rows.length(),
            number(response, "total"), page, pageSize)
    }

    private fun searchArtist(row: JSONObject): MusicArtist? {
        val id = positiveId(row, "AuthorId") ?: return null
        val name = text(row, "AuthorName").takeIf(String::isNotBlank) ?: return null
        return MusicArtist(id, name, avatar(text(row, "Avatar")), number(row, "AudioCount"), number(row, "AlbumCount"))
    }

    private fun album(row: JSONObject): MusicCollection? {
        val id = positiveId(row, "albumid", "album_id") ?: return null
        val name = text(row, "albumname", "album_name").takeIf(String::isNotBlank) ?: return null
        return MusicCollection("$id", name, CollectionKind.ALBUM,
            selectCoverUrl(text(row, "sizable_cover", "img", "cover")),
            listOf(text(row, "singer", "author_name"), text(row, "publish_time", "publish_date"))
                .filter(String::isNotBlank).joinToString(" · "), number(row, "songcount", "song_count"))
    }

    private fun playlist(row: JSONObject): MusicCollection? {
        val id = text(row, "gid", "global_collection_id").takeIf(String::isNotBlank) ?: return null
        val name = text(row, "specialname", "name").takeIf(String::isNotBlank) ?: return null
        return MusicCollection(id, name, CollectionKind.PLAYLIST,
            selectCoverUrl(text(row, "img", "sizable_cover")), text(row, "nickname"), number(row, "song_count"))
    }

    private fun <T> catalogPage(items: List<T>, rawCount: Int, total: Int?, page: Int, pageSize: Int): CatalogPage<T> {
        if (rawCount > 0 && items.isEmpty()) invalid()
        return CatalogPage(items, total, BrowseParser.hasNext(page, pageSize, rawCount, total), rawCount = rawCount)
    }

    private fun avatar(value: String): String = selectCoverUrl(value.replace(
        Regex("^http://singerimg\\.kugou\\.com/", RegexOption.IGNORE_CASE), "https://singerimg.kugou.com/"))
    private fun objects(rows: JSONArray) = (0 until rows.length()).mapNotNull(rows::optJSONObject)
    private fun text(value: JSONObject, vararg keys: String): String = keys.firstNotNullOfOrNull { key ->
        value.optString(key).takeUnless { it.isBlank() || it == "null" }
    }.orEmpty().replace(Regex("<[^>]*>"), "").replace("&amp;", "&").replace("&nbsp;", " ").trim()
    private fun number(value: JSONObject, vararg keys: String): Int? = keys.firstNotNullOfOrNull { key ->
        value.optString(key).toLongOrNull()?.takeIf { it in 0..Int.MAX_VALUE.toLong() }?.toInt()
    }
    private fun positiveId(value: JSONObject, vararg keys: String): Long? = keys.firstNotNullOfOrNull { key ->
        value.optString(key).toLongOrNull()?.takeIf { it > 0 }
    }
    private fun invalid(): Nothing = throw MusicFailure(MusicError.InvalidResponse)
}
