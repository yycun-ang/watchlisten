package com.watchlisten.music

import com.watchlisten.music.data.*
import com.watchlisten.music.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DiscoveryGatewayTest {
    private val identity = SessionData("fixture-token", "123", "fixture-mid", 1)

    @Test fun 歌曲分类复用既有搜索并按二十首分页() = runBlocking {
        val transport = RecordingTransport().apply {
            songsResult = SearchPage((1..20).map { Track("fixture-$it", "歌曲 $it", "虚构歌手") }, 40)
        }
        val gateway = DiscoveryGateway(transport, MutableStateFlow(identity))
        val first = gateway.search("  虚构音乐  ", DiscoveryKind.SONGS, 1)
        val second = gateway.search("歌".repeat(120), DiscoveryKind.SONGS, 2)
        assertEquals(listOf("虚构音乐" to 1, "歌".repeat(100) to 2), transport.songRequests)
        assertTrue(transport.calls.isEmpty())
        assertEquals(20, first.rawCount)
        assertEquals(40, first.total)
        assertEquals(transport.songsResult.tracks, first.items.map { (it as DiscoveryItem.Song).track })
        assertTrue(first.hasMore)
        assertFalse(second.hasMore)
    }

    @Test fun 分类搜索使用对应端点且每次读取当前身份() = runBlocking {
        val transport = RecordingTransport()
        val sessions = MutableStateFlow<SessionData?>(identity)
        val gateway = DiscoveryGateway(transport, sessions)
        val endpoints = mapOf(DiscoveryKind.ARTISTS to "author", DiscoveryKind.ALBUMS to "album",
            DiscoveryKind.PLAYLISTS to "special")
        endpoints.forEach { (kind, endpoint) ->
            gateway.search("  测试分类  ", kind, 3)
            val recorded = transport.calls.last()
            val call = recorded.first
            assertEquals("/v1/search/$endpoint", call.path)
            assertEquals("complexsearch.kugou.com", call.router)
            assertEquals("测试分类", call.query["keyword"])
            assertEquals("3", call.query["page"])
            assertEquals("30", call.query["pagesize"])
            assertEquals("AndroidFilter", call.query["platform"])
            assertEquals("1", call.headers["kg-tid"])
            assertEquals(identity, recorded.second)
            if (kind == DiscoveryKind.ALBUMS) {
                assertEquals("1", call.query["category"])
                assertEquals("0", call.query["sorttype"])
                assertEquals("0", call.query["searchsong"])
            } else assertFalse(call.query.containsKey("category"))
        }
        sessions.value = identity.copy(token = "fixture-refreshed-token")
        gateway.search("另一个词", DiscoveryKind.ARTISTS, 1)
        assertEquals(sessions.value, transport.calls.last().second)
        sessions.value = null
        gateway.search("公开结果", DiscoveryKind.ARTISTS, 1)
        assertNull(transport.calls.last().second)
    }

    @Test fun 歌手搜索清理标记升级头像并拒绝越界计数() {
        val response = searchResponse(JSONArray().put(JSONObject().put("AuthorId", "51")
            .put("AuthorName", "<em>虚构</em>&amp;乐队&nbsp;")
            .put("Avatar", "http://singerimg.kugou.com/upload/{size}/fixture.jpg")
            .put("AudioCount", 12).put("AlbumCount", "2147483648")))
        val artist = (DiscoveryParser.search(response, DiscoveryKind.ARTISTS, 1).items.single()
            as DiscoveryItem.Artist).artist
        assertEquals(51L, artist.id)
        assertEquals("虚构&乐队", artist.name)
        assertEquals("https://singerimg.kugou.com/upload/800/fixture.jpg", artist.avatar)
        assertEquals(12, artist.songCount)
        assertNull(artist.albumCount)
    }

    @Test fun 专辑去重和丢弃无效行后仍按原始行数判断后续页() {
        val album = JSONObject().put("album_id", 73).put("album_name", "<em>虚构专辑</em>")
            .put("author_name", "虚构歌手").put("publish_date", "2020-01-02").put("song_count", 8)
            .put("sizable_cover", "https://example.com/{size}/album.jpg")
        val response = searchResponse(JSONArray().put(album).put(album).put(JSONObject().put("album_id", -1)))
        val page = DiscoveryParser.search(response, DiscoveryKind.ALBUMS, 1, pageSize = 3)
        val collection = (page.items.single() as DiscoveryItem.Collection).collection
        assertEquals("73", collection.id)
        assertEquals(CollectionKind.ALBUM, collection.kind)
        assertEquals("虚构专辑", collection.title)
        assertEquals("虚构歌手 · 2020-01-02", collection.subtitle)
        assertEquals("https://example.com/800/album.jpg", collection.cover)
        assertEquals(8, collection.count)
        assertEquals(3, page.rawCount)
        assertTrue(page.hasMore)
    }

    @Test fun 歌单分类保留公共歌单标识与创建者() {
        val rows = JSONArray().put(JSONObject().put("global_collection_id", "fixture-playlist")
            .put("name", "虚构歌单").put("nickname", "虚构创建者").put("song_count", 9)
            .put("img", "https://example.com/{size}/playlist.jpg"))
        val page = DiscoveryParser.search(searchResponse(rows).apply { getJSONObject("data").put("total", 1) },
            DiscoveryKind.PLAYLISTS, 1)
        val collection = (page.items.single() as DiscoveryItem.Collection).collection
        assertEquals("fixture-playlist", collection.id)
        assertEquals(CollectionKind.PLAYLIST, collection.kind)
        assertEquals("虚构创建者", collection.subtitle)
        assertEquals(9, collection.count)
        assertFalse(collection.privateContent)
        assertFalse(page.hasMore)
    }

    @Test fun 分类响应缺少列表或全为不可识别行时不能当作空结果() {
        listOf(JSONObject(), JSONObject().put("data", JSONObject()),
            searchResponse(JSONArray().put(JSONObject().put("AuthorId", 0).put("AuthorName", "无效歌手"))),
            searchResponse(JSONArray().put("unexpected-row"))).forEach { response ->
            assertInvalid { DiscoveryParser.search(response, DiscoveryKind.ARTISTS, 1) }
        }
        val empty = DiscoveryParser.search(searchResponse(JSONArray()), DiscoveryKind.ARTISTS, 1)
        assertTrue(empty.items.isEmpty())
        assertFalse(empty.hasMore)
    }

    @Test fun 歌手详情请求绑定标识并解析简介与数量() = runBlocking {
        val transport = RecordingTransport().apply {
            response = JSONObject().put("data", JSONObject().put("author_id", 51).put("author_name", "虚构歌手")
                .put("sizable_avatar", "http://singerimg.kugou.com/upload/{size}/profile.jpg")
                .put("song_count", 31).put("album_count", 4).put("intro", "<p>虚构&nbsp;简介 &amp; 说明</p>"))
        }
        val artist = DiscoveryGateway(transport, MutableStateFlow(identity)).artist(51)
        val call = transport.calls.single().first
        assertEquals("/kmr/v3/author", call.path)
        assertEquals("openapi.kugou.com", call.router)
        assertEquals(51L, call.body!!.getLong("author_id"))
        assertEquals("36", call.headers["kg-tid"])
        assertEquals("虚构 简介 & 说明", artist.intro)
        assertEquals(31, artist.songCount)
        assertEquals(4, artist.albumCount)
        assertEquals("https://singerimg.kugou.com/upload/800/profile.jpg", artist.avatar)
    }

    @Test fun 歌手详情拒绝错人空名称和负数标识() {
        listOf(JSONObject().put("author_id", 52).put("author_name", "别的歌手"),
            JSONObject().put("author_id", 51).put("author_name", " "),
            JSONObject().put("author_id", -51).put("author_name", "无效歌手")).forEach { data ->
            assertInvalid { DiscoveryParser.artist(JSONObject().put("data", data), 51) }
        }
    }

    @Test fun 歌手歌曲请求正确分页且复用曲目解析与去重() = runBlocking {
        val row = JSONObject().put("audio_info", JSONObject().put("hash", "a".repeat(32)).put("duration", 213000))
            .put("songname", "虚构歌曲").put("author_name", "虚构歌手").put("mixsongid", 91)
        val transport = RecordingTransport().apply {
            response = JSONObject().put("data", JSONObject().put("total", 60)
                .put("songs", JSONArray().put(row).put(row).put(JSONObject().put("hash", "invalid"))))
        }
        val page = DiscoveryGateway(transport, MutableStateFlow(identity)).artistSongs(51, 1)
        val call = transport.calls.single().first
        assertEquals("/openapi/kmr/v2/audio_group/author", call.path)
        assertEquals("51", call.query["author_id"])
        assertEquals("1", call.query["page"])
        assertEquals("30", call.query["pagesize"])
        assertEquals("1", call.query["replace_api_version"])
        assertEquals("36", call.headers["kg-tid"])
        assertEquals(identity, transport.calls.single().second)
        assertEquals(213000L, page.items.single().durationMs)
        assertEquals(91L, page.items.single().audioId)
        assertEquals(3, page.rawCount)
        assertEquals(60, page.total)
        assertTrue(page.hasMore)
    }

    @Test fun 歌手专辑使用数组响应和请求页码() = runBlocking {
        val transport = RecordingTransport().apply {
            response = JSONObject().put("total", 31).put("data", JSONArray().put(JSONObject()
                .put("albumid", 73).put("albumname", "虚构专辑").put("singer", "虚构歌手")))
        }
        val page = DiscoveryGateway(transport, MutableStateFlow(identity)).artistAlbums(51, 2)
        val call = transport.calls.single().first
        assertEquals("/kmr/v1/author/albums", call.path)
        assertEquals("openapi.kugou.com", call.router)
        assertEquals(51L, call.body!!.getLong("author_id"))
        assertEquals(2, call.body!!.getInt("page"))
        assertEquals(30, call.body!!.getInt("pagesize"))
        assertEquals(3, call.body!!.getInt("sort"))
        assertEquals("36", call.headers["kg-tid"])
        assertEquals(CollectionKind.ALBUM, page.items.single().kind)
        assertEquals(31, page.total)
        assertFalse(page.hasMore)
    }

    @Test fun 歌手子列表错误结构或全无效行必须报错而真实空列表可完成() {
        assertInvalid { DiscoveryParser.songs(JSONObject().put("data", JSONArray()), 1) }
        assertInvalid { DiscoveryParser.songs(JSONObject().put("data", JSONObject().put("songs",
            JSONArray().put(JSONObject().put("hash", "invalid")))), 1) }
        assertInvalid { DiscoveryParser.albums(JSONObject().put("data", JSONObject()), 1) }
        assertInvalid { DiscoveryParser.albums(JSONObject().put("data", JSONArray().put(JSONObject().put("albumid", 0))), 1) }
        assertTrue(DiscoveryParser.songs(JSONObject().put("data", JSONObject().put("songs", JSONArray())), 1).items.isEmpty())
        assertFalse(DiscoveryParser.albums(JSONObject().put("data", JSONArray()), 1).hasMore)
    }

    @Test fun 非法关键词标识和页码在发送请求前拒绝() = runBlocking {
        val transport = RecordingTransport()
        val gateway = DiscoveryGateway(transport, MutableStateFlow(identity))
        val operations: List<suspend () -> Any> = listOf(
            { gateway.search("  ", DiscoveryKind.SONGS, 1) },
            { gateway.search("测试", DiscoveryKind.ARTISTS, 0) },
            { gateway.artist(0) }, { gateway.artistSongs(-1, 1) },
            { gateway.artistSongs(51, 0) }, { gateway.artistAlbums(0, 1) }, { gateway.artistAlbums(51, -1) })
        operations.forEach { operation -> assertTrue(runCatching { operation() }.exceptionOrNull() is IllegalArgumentException) }
        assertTrue(transport.calls.isEmpty())
        assertTrue(transport.songRequests.isEmpty())
    }

    private fun searchResponse(rows: JSONArray) = JSONObject().put("data", JSONObject().put("lists", rows))

    private fun assertInvalid(action: () -> Unit) {
        val error = runCatching(action).exceptionOrNull()
        assertTrue("应返回可识别的协议错误", error is MusicFailure)
        assertEquals(MusicError.InvalidResponse, (error as MusicFailure).error)
    }

    private class RecordingTransport : DiscoveryTransport {
        val calls = mutableListOf<Pair<CatalogCall, SessionData?>>()
        val songRequests = mutableListOf<Pair<String, Int>>()
        var response = JSONObject().put("data", JSONObject().put("lists", JSONArray()).put("total", 0))
        var songsResult = SearchPage(emptyList(), 0)
        override suspend fun call(call: CatalogCall, identity: SessionData?): JSONObject {
            calls += call to identity
            return response
        }
        override suspend fun songs(query: String, page: Int): SearchPage {
            songRequests += query to page
            return songsResult
        }
    }
}
