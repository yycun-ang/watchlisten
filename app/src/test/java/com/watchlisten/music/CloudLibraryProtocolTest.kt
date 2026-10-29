package com.watchlisten.music

import com.watchlisten.music.data.*
import com.watchlisten.music.model.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import javax.crypto.Cipher

class CloudLibraryProtocolTest {
    @Test fun 真实网关返回校验快照且每次写入只读取一次歌曲列表() = runBlocking {
        val transport = RecordingTransport()
        val gateway = CloudLibraryGateway(transport)
        val added = gateway.addVerified(identity, favorites, listOf(track))
        assertEquals(1, added.size)
        assertEquals(track.hash, added.single().track.hash)
        assertEquals(1, transport.calls.count { it.path == "/v4/get_list_all_file_v3" })
        val removed = gateway.removeVerified(identity, favorites, added)
        assertTrue(removed.isEmpty())
        assertEquals(2, transport.calls.count { it.path == "/v4/get_list_all_file_v3" })
        assertEquals(20, gateway.mutationBatchSize)
    }

    @Test fun 新建复用协调器已经读取的原列表并返回完整确认结果() = runBlocking {
        val transport = RecordingTransport()
        val rows = CloudLibraryGateway(transport).createVerified(identity, "跑步", listOf(favorites))
        assertTrue(rows.any { it.collection.id == "20" && it.collection.title == "跑步" })
        assertEquals(1, transport.calls.count { it.path == "/v7/get_all_list" })
    }
    @Test fun 同曲不同云条目标识都必须保留以便完整取消喜欢() {
        val rows = JSONArray()
        listOf(11, 12).forEach { id -> rows.put(JSONObject().put("hash", track.hash).put("filename", "歌手 - 测试歌曲")
            .put("fileid", id).put("mixsongid", 34)) }
        val response = JSONObject().put("data", JSONObject().put("total", 2).put("fileinfo", rows))
        assertEquals(listOf(11L, 12L), CloudLibraryProtocol.songs(response, favorites, 1).items.map { it.fileId })
    }
    private val identity = SessionData("fixture-token", "123", "fixture-mid", 1)
    private val collection = MusicCollection("7", "我喜欢", CollectionKind.CLOUD, ownerId = "123")
    private val favorites = CloudPlaylist(collection, creatorId = "123")
    private val track = Track("a".repeat(32), "测试歌曲", "歌手", 12, 34)

    @Test fun 添加与删除请求保留真实云歌单标识和账号() = runBlocking {
        val transport = RecordingTransport()
        val gateway = CloudLibraryGateway(transport)
        gateway.add(identity, favorites, listOf(track))
        val add = transport.calls.first()
        assertEquals("/cloudlist.service/v6/add_song", add.path)
        assertEquals(identity.token, add.query["token"])
        assertEquals(identity.userId, add.query["userid"])
        val body = add.body!!
        assertEquals(7L, body.getLong("listid"))
        assertEquals(123L, body.getLong("userid"))
        assertEquals("false;null", body.getString("scene"))
        val song = body.getJSONArray("data").getJSONObject(0)
        assertEquals(track.hash, song.getString("hash"))
        assertEquals(track.audioId, song.getLong("mixsongid"))
        assertEquals("歌手 - 测试歌曲", song.getString("name"))
        gateway.remove(identity, favorites, listOf(CloudSong(track, 9876)))
        val delete = transport.calls.first { it.path == "/v4/delete_songs" }
        assertEquals("/v4/delete_songs", delete.path)
        assertEquals("cloudlist.service.kugou.com", delete.router)
        assertEquals(9876L, delete.body!!.getJSONArray("data").getJSONObject(0).getLong("fileid"))
        assertFalse(delete.body!!.getJSONArray("data").getJSONObject(0).has("hash"))
    }

    @Test fun 删除缺少fileid时必须拒绝不能使用hash猜测() = runBlocking {
        val transport = RecordingTransport()
        val error = runCatching { CloudLibraryGateway(transport).remove(identity, favorites, listOf(CloudSong(track, 0))) }.exceptionOrNull()
        assertTrue(error is MusicFailure)
        assertTrue(transport.calls.isEmpty())
    }

    @Test fun 云歌单解析保留所属类型版本与歌曲fileid() {
        val response = JSONObject().put("status", 1).put("data", JSONObject().put("total_ver", 19)
            .put("total", 2).put("info", JSONArray().put(JSONObject().put("listid", 7).put("name", "我喜欢")
                .put("type", 0).put("list_create_userid", 123).put("list_ver", 5))
                .put(JSONObject().put("listid", 8).put("name", "他人的歌单").put("type", 1).put("list_create_userid", 456))))
        val parsed = CloudLibraryProtocol.playlists(response, identity, 1)
        assertEquals(2, parsed.items.size)
        assertTrue(parsed.items.first().isFavorites)
        assertEquals(19L, parsed.items.first().totalVersion)
        assertEquals(5L, parsed.items.first().listVersion)
        assertFalse(parsed.items.last().canEdit)
        val songs = JSONObject().put("data", JSONObject().put("total", 1).put("fileinfo", JSONArray()
            .put(JSONObject().put("hash", track.hash).put("filename", "歌手 - 测试歌曲").put("fileid", 678).put("mixsongid", 34))))
        val song = CloudLibraryProtocol.songs(songs, favorites, 1).items.single()
        assertEquals(678L, song.fileId)
        assertEquals(track.hash, song.track.hash)
    }

    @Test fun 收藏歌单读取必须携带type1() = runBlocking {
        val transport = RecordingTransport().apply {
            response = JSONObject().put("status", 1).put("data", JSONObject().put("fileinfo", JSONArray()).put("total", 0))
        }
        CloudLibraryGateway(transport).songs(identity, CloudPlaylist(collection.copy(title = "已收藏歌单"), type = 1), 1)
        assertEquals(1, transport.calls.single().body!!.getInt("type"))
    }

    @Test fun 换号与编辑他人歌单必须在请求前拒绝() = runBlocking {
        val transport = RecordingTransport()
        val gateway = CloudLibraryGateway(transport)
        assertTrue(runCatching { gateway.add(identity.copy(userId = "456"), favorites, listOf(track)) }.exceptionOrNull() is MusicFailure)
        assertTrue(runCatching { gateway.add(identity, favorites.copy(type = 1), listOf(track)) }.exceptionOrNull() is MusicFailure)
        assertTrue(transport.calls.isEmpty())
    }

    @Test fun 系统我喜欢不能重命名删除且不能用它创建重名列表() = runBlocking {
        val transport = RecordingTransport()
        val gateway = CloudLibraryGateway(transport)
        assertTrue(runCatching { gateway.renamePlaylist(identity, favorites, "新名称") }.exceptionOrNull() is MusicFailure)
        assertTrue(runCatching { gateway.deletePlaylist(identity, favorites) }.exceptionOrNull() is MusicFailure)
        assertTrue(runCatching { gateway.createPlaylist(identity, "我喜欢") }.exceptionOrNull() is MusicFailure)
        assertTrue(transport.calls.isEmpty())
        assertTrue(transport.encryptedPaths.isEmpty())
    }

    @Test fun 云歌单创建重命名和删除使用已核实端点() = runBlocking {
        val transport = RecordingTransport()
        val gateway = CloudLibraryGateway(transport)
        gateway.createPlaylist(identity, "  跑步音乐  ")
        val create = transport.calls.first { it.path == "/cloudlist.service/v5/add_list" }
        assertEquals("跑步音乐", create.body!!.getString("name"))
        val playlist = favorites.copy(collection = collection.copy(id = "20", title = "跑步音乐"), totalVersion = 22)
        gateway.renamePlaylist(identity, playlist, "夜跑")
        assertEquals("/v1/modify_list", transport.encryptedPaths.last())
        assertEquals(19L, transport.encryptedBodies.last().getLong("total_ver"))
        gateway.deletePlaylist(identity, playlist)
        assertEquals("/v2/delete_list", transport.encryptedPaths.last())
        assertEquals(1, transport.encryptedBodies.last().getInt("type"))
    }

    @Test fun 非成功状态与错误码不能当作云端成功() {
        assertTrue(runCatching { CloudLibraryGateway.checkSuccess(JSONObject("{\"status\":0,\"error_code\":0}")) }.exceptionOrNull() is MusicFailure)
        val error = runCatching { CloudLibraryGateway.checkSuccess(JSONObject("{\"status\":1,\"error_code\":20018}")) }.exceptionOrNull()
        assertEquals(MusicError.AuthenticationExpired, (error as MusicFailure).error)
    }

    @Test fun AES与独立Python加密向量一致且支持明文错误响应() {
        val plain = "{\"listid\":7,\"name\":\"fixture\"}"
        val cipher = CloudLibraryProtocol.crypt(Cipher.ENCRYPT_MODE, plain.toByteArray(), "Abc123")
        assertEquals("bba622db35836411aa9c0c511ddfef506972f0b7f9c36b73b6ba4dfabcf0eac0",
            cipher.joinToString("") { "%02x".format(it.toInt() and 255) })
        assertEquals(7, CloudLibraryProtocol.decrypt(cipher, "Abc123").getInt("listid"))
        assertEquals(0, CloudLibraryProtocol.decrypt("{\"status\":0}".toByteArray(), "Abc123").getInt("status"))
    }

    @Test fun 删除歌单与重命名采用不同的已核实加密封装() {
        val body = JSONObject().put("listid", 7)
        val modern = CloudLibraryProtocol.encrypted(body, identity, 100, "Abc123")
        assertFalse(modern.params.containsKey("signature"))
        val legacy = CloudLibraryProtocol.encrypted(body, identity, 100, "Abc123", legacyDelete = true)
        val posted = legacy.body.toString(Charsets.UTF_8)
        assertEquals(AccountProtocol.signature(legacy.params - "signature", posted), legacy.params["signature"])
        assertEquals(identity.token, legacy.params["token"])
        assertArrayEquals(modern.body, java.util.Base64.getDecoder().decode(posted))
    }

    @Test fun 云端未读回新歌单时不能报告创建成功() = runBlocking {
        val transport = RecordingTransport().apply { ignoreMutations = true }
        assertTrue(runCatching { CloudLibraryGateway(transport).createPlaylist(identity, "未生效") }.exceptionOrNull() is MusicFailure)
    }

    @Test fun 同曲识别兼容缺audioId与不同音质且不误认投放() {
        assertTrue(sameKugouTrack(track, track.copy(audioId = 0)))
        assertTrue(sameKugouTrack(track, track.copy(hash = "b".repeat(32))))
        assertFalse(sameKugouTrack(track, track.copy(hash = "b".repeat(32), audioId = 99)))
        assertFalse(sameKugouTrack(track, track.copy(origin = TrackOrigin.DLNA)))
    }

    @Test fun 系统歌单允许无个人创建者但不把他人同名歌单当喜欢() {
        assertTrue(favorites.copy(creatorId = "0").isFavorites)
        assertTrue(favorites.copy(creatorId = "").isFavorites)
        assertFalse(favorites.copy(creatorId = "456").isFavorites)
        assertFalse(favorites.copy(type = 1).isFavorites)
    }
}

private class RecordingTransport : CloudGatewayTransport {
    val calls = mutableListOf<CatalogCall>()
    val encryptedPaths = mutableListOf<String>()
    val encryptedBodies = mutableListOf<JSONObject>()
    var response: JSONObject? = null
    var ignoreMutations = false
    private val lists = mutableListOf(JSONObject().put("listid", 7).put("name", "我喜欢")
        .put("type", 0).put("list_create_userid", 123))
    private val songs = mutableListOf<JSONObject>()
    override suspend fun signed(call: CatalogCall, identity: SessionData): JSONObject {
        calls += call
        response?.let { return it }
        if (call.path == "/v7/get_all_list") return JSONObject().put("status", 1).put("data",
            JSONObject().put("total_ver", 19).put("total", lists.size).put("info", JSONArray(lists)))
        if (call.path == "/v4/get_list_all_file_v3") return JSONObject().put("status", 1).put("data",
            JSONObject().put("total", songs.size).put("fileinfo", JSONArray(songs)))
        if (!ignoreMutations) when (call.path) {
            "/cloudlist.service/v6/add_song" -> {
                val row = JSONObject(call.body!!.getJSONArray("data").getJSONObject(0).toString())
                row.put("filename", row.getString("name")).put("fileid", 9876)
                songs += row
            }
            "/v4/delete_songs" -> songs.clear()
            "/cloudlist.service/v5/add_list" -> lists += JSONObject().put("listid", 20)
                .put("name", call.body!!.getString("name")).put("type", 0).put("list_create_userid", 123)
        }
        return JSONObject().put("status", 1)
    }
    override suspend fun encrypted(path: String, body: JSONObject, identity: SessionData): JSONObject {
        encryptedPaths += path; encryptedBodies += body
        if (!ignoreMutations) when (path) {
            "/v1/modify_list" -> lists.first { it.getLong("listid") == body.getLong("listid") }.put("name", body.getString("name"))
            "/v2/delete_list" -> lists.removeAll { it.getLong("listid") == body.getLong("listid") }
        }
        return response ?: JSONObject().put("status", 1)
    }
}
