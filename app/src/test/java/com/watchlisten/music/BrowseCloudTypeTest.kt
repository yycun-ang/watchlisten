package com.watchlisten.music

import com.watchlisten.music.data.BrowseParser
import com.watchlisten.music.model.CollectionKind
import com.watchlisten.music.model.MusicCollection
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BrowseCloudTypeTest {
    @Test fun 已收藏云歌单保存再恢复保留type1及账号信息() {
        val collection = MusicCollection("71", "虚构收藏歌单", CollectionKind.CLOUD,
            cover = "https://example.com/fixture.jpg", subtitle = "已收藏的", count = 12,
            ownerId = "123", description = "虚构描述", cloudType = 1)
        val saved = collection.json()
        val restored = MusicCollection.fromJson(JSONObject(saved.toString()))
        assertEquals(1, saved.getInt("cloudType"))
        assertEquals(collection, restored)
        assertEquals(collection.route, restored!!.route)
        assertTrue(restored.privateContent)
        assertFalse(restored.canPin)
    }

    @Test fun 旧缓存没有cloudType时仍按原创建歌单类型恢复() {
        val legacy = JSONObject().put("id", "72").put("title", "虚构旧歌单").put("kind", "CLOUD")
            .put("owner", "123")
        val restored = MusicCollection.fromJson(legacy)!!
        assertEquals(0, restored.cloudType)
        assertEquals("123", restored.ownerId)
        assertEquals(0, MusicCollection.fromJson(restored.json())!!.cloudType)
    }

    @Test fun 云列表解析区分收藏类型与创建类型并可持久化() {
        val rows = JSONArray().put(JSONObject().put("listid", 71).put("name", "虚构收藏歌单")
            .put("type", 1).put("list_create_userid", 456))
            .put(JSONObject().put("listid", 72).put("name", "虚构创建歌单")
                .put("type", 0).put("list_create_userid", 123))
            .put(JSONObject().put("listid", 73).put("name", "虚构旧接口歌单"))
        val page = BrowseParser.playlists(JSONObject().put("data", JSONObject().put("info", rows).put("total", 3)),
            page = 1, pageSize = 30, ownerId = "123")
        assertEquals(listOf(1, 0, 0), page.items.map { it.cloudType })
        assertTrue(page.items.all { it.kind == CollectionKind.CLOUD && it.ownerId == "123" })
        assertEquals("已收藏的", page.items[0].subtitle)
        assertEquals("我创建的", page.items[1].subtitle)
        assertEquals(1, MusicCollection.fromJson(page.items.first().json())!!.cloudType)
        assertFalse(page.hasMore)
    }

    @Test fun 公共歌单响应中的type不能污染云端类型() {
        val rows = JSONArray().put(JSONObject().put("global_collection_id", "fixture-public")
            .put("name", "虚构公共歌单").put("type", 1))
        val response = JSONObject().put("data", JSONObject().put("special_list", rows).put("total", 1))
        val collection = BrowseParser.playlists(response, 1, 30).items.single()
        assertEquals(CollectionKind.PLAYLIST, collection.kind)
        assertEquals(0, collection.cloudType)
        assertEquals("", collection.ownerId)
        assertFalse(collection.privateContent)
    }
}
