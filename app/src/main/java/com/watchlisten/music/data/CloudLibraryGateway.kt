package com.watchlisten.music.data

import com.watchlisten.music.model.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

interface CloudLibrarySource {
    val mutationBatchSize: Int get() = 1
    suspend fun playlists(identity: SessionData, page: Int): CatalogPage<CloudPlaylist>
    suspend fun songs(identity: SessionData, playlist: CloudPlaylist, page: Int): CatalogPage<CloudSong>
    suspend fun add(identity: SessionData, playlist: CloudPlaylist, tracks: List<Track>)
    suspend fun remove(identity: SessionData, playlist: CloudPlaylist, songs: List<CloudSong>)
    suspend fun addVerified(identity: SessionData, playlist: CloudPlaylist, tracks: List<Track>): List<CloudSong> {
        add(identity, playlist, tracks)
        return confirmAdded(readSongs(identity, playlist), tracks)
    }
    suspend fun removeVerified(identity: SessionData, playlist: CloudPlaylist, songs: List<CloudSong>): List<CloudSong> {
        remove(identity, playlist, songs)
        return confirmRemoved(readSongs(identity, playlist), songs)
    }
}

interface CloudPlaylistSource : CloudLibrarySource {
    suspend fun createPlaylist(identity: SessionData, name: String)
    suspend fun renamePlaylist(identity: SessionData, playlist: CloudPlaylist, name: String)
    suspend fun deletePlaylist(identity: SessionData, playlist: CloudPlaylist)
    suspend fun createVerified(identity: SessionData, name: String, before: List<CloudPlaylist>): List<CloudPlaylist> {
        createPlaylist(identity, name)
        return readPlaylists(identity).also { rows ->
            if (rows.none { it.collection.title == name.trim() && it.canEdit && before.none { old -> old.collection.id == it.collection.id } }) unconfirmedCloudWrite()
        }
    }
    suspend fun renameVerified(identity: SessionData, playlist: CloudPlaylist, name: String, before: List<CloudPlaylist>): List<CloudPlaylist> {
        renamePlaylist(identity, playlist, name)
        return readPlaylists(identity).also { rows ->
            if (rows.none { it.collection.id == playlist.collection.id && it.collection.title == name.trim() }) unconfirmedCloudWrite()
        }
    }
    suspend fun deleteVerified(identity: SessionData, playlist: CloudPlaylist, before: List<CloudPlaylist>): List<CloudPlaylist> {
        deletePlaylist(identity, playlist)
        return readPlaylists(identity).also { rows ->
            if (rows.any { it.collection.id == playlist.collection.id }) unconfirmedCloudWrite()
        }
    }
}

internal interface CloudGatewayTransport {
    suspend fun signed(call: CatalogCall, identity: SessionData): JSONObject
    suspend fun encrypted(path: String, body: JSONObject, identity: SessionData): JSONObject
}

class CloudLibraryGateway internal constructor(private val transport: CloudGatewayTransport) : CloudPlaylistSource {
    override val mutationBatchSize: Int get() = 20
    constructor(catalog: CatalogGateway, http: HttpTransport, sessions: SessionStore) :
        this(ProductionCloudTransport(catalog, http, sessions))

    override suspend fun playlists(identity: SessionData, page: Int): CatalogPage<CloudPlaylist> {
        requireIdentity(identity)
        require(page > 0)
        val response = transport.signed(CatalogCall("/v7/get_all_list", ROUTER, mapOf("plat" to "1"),
            JSONObject().put("userid", CloudLibraryProtocol.userId(identity)).put("token", identity.token)
                .put("total_ver", 979).put("type", 2).put("page", page).put("pagesize", CloudLibraryProtocol.PAGE_SIZE)), identity)
        checkSuccess(response)
        return CloudLibraryProtocol.playlists(response, identity, page)
    }

    override suspend fun songs(identity: SessionData, playlist: CloudPlaylist, page: Int): CatalogPage<CloudSong> {
        requireOwner(identity, playlist)
        require(page > 0)
        val response = transport.signed(CatalogCall("/v4/get_list_all_file_v3", ROUTER, body = JSONObject()
            .put("listid", CloudLibraryProtocol.playlistId(playlist)).put("userid", CloudLibraryProtocol.userId(identity))
            .put("type", playlist.type).put("page", page).put("pagesize", CloudLibraryProtocol.PAGE_SIZE)
            .put("area_code", 1).put("allplatform", 1).put("show_cover", 1).put("token", identity.token)), identity)
        checkSuccess(response)
        return CloudLibraryProtocol.songs(response, playlist, page)
    }

    override suspend fun add(identity: SessionData, playlist: CloudPlaylist, tracks: List<Track>) { addVerified(identity, playlist, tracks) }

    override suspend fun addVerified(identity: SessionData, playlist: CloudPlaylist, tracks: List<Track>): List<CloudSong> {
        requireEditable(identity, playlist)
        if (tracks.isEmpty()) return allSongs(identity, playlist)
        val query = writeQuery(identity)
        checkSuccess(transport.signed(CatalogCall("/cloudlist.service/v6/add_song", query = query,
            body = CloudLibraryProtocol.addition(identity, playlist, tracks)), identity))
        return confirmAdded(allSongs(identity, playlist), tracks)
    }

    override suspend fun remove(identity: SessionData, playlist: CloudPlaylist, songs: List<CloudSong>) { removeVerified(identity, playlist, songs) }

    override suspend fun removeVerified(identity: SessionData, playlist: CloudPlaylist, songs: List<CloudSong>): List<CloudSong> {
        requireEditable(identity, playlist)
        if (songs.isEmpty()) return allSongs(identity, playlist)
        checkSuccess(transport.signed(CatalogCall("/v4/delete_songs", ROUTER,
            body = CloudLibraryProtocol.deletion(identity, playlist, songs)), identity))
        return confirmRemoved(allSongs(identity, playlist), songs)
    }

    override suspend fun createPlaylist(identity: SessionData, name: String) {
        requireIdentity(identity)
        validName(name)
        createVerified(identity, name, allPlaylists(identity))
    }

    override suspend fun createVerified(identity: SessionData, name: String, before: List<CloudPlaylist>): List<CloudPlaylist> {
        requireIdentity(identity)
        val title = validName(name)
        val previous = before.mapTo(hashSetOf()) { it.collection.id }
        checkSuccess(transport.signed(CatalogCall("/cloudlist.service/v5/add_list", query = writeQuery(identity),
            body = JSONObject().put("userid", CloudLibraryProtocol.userId(identity)).put("token", identity.token)
                .put("total_ver", 0).put("name", title).put("type", 0).put("source", 0).put("is_pri", 0)
                .put("list_create_userid", CloudLibraryProtocol.userId(identity)).put("list_create_listid", 0)
                .put("list_create_gid", "").put("from_shupinmv", 0)), identity))
        return allPlaylists(identity).also { rows ->
            if (rows.none { it.collection.id !in previous && it.collection.title == title && it.canEdit }) unconfirmed()
        }
    }

    override suspend fun renamePlaylist(identity: SessionData, playlist: CloudPlaylist, name: String) {
        requireEditable(identity, playlist)
        requireOrdinaryPlaylist(playlist)
        validName(name)
        renameVerified(identity, playlist, name, allPlaylists(identity))
    }

    override suspend fun renameVerified(identity: SessionData, playlist: CloudPlaylist, name: String, before: List<CloudPlaylist>): List<CloudPlaylist> {
        requireEditable(identity, playlist)
        requireOrdinaryPlaylist(playlist)
        val title = validName(name)
        val current = before.firstOrNull { it.collection.id == playlist.collection.id }
            ?: throw MusicFailure(MusicError.Unknown("云歌单已不存在，请刷新"))
        requireEditable(identity, current)
        requireOrdinaryPlaylist(current)
        checkSuccess(transport.encrypted("/v1/modify_list", JSONObject().put("total_ver", current.totalVersion)
            .put("listid", CloudLibraryProtocol.playlistId(playlist)).put("type", 0).put("name", title), identity))
        return allPlaylists(identity).also { rows ->
            if (rows.none { it.collection.id == playlist.collection.id && it.collection.title == title }) unconfirmed()
        }
    }

    override suspend fun deletePlaylist(identity: SessionData, playlist: CloudPlaylist) {
        requireOwner(identity, playlist)
        requireOrdinaryPlaylist(playlist)
        deleteVerified(identity, playlist, allPlaylists(identity))
    }

    override suspend fun deleteVerified(identity: SessionData, playlist: CloudPlaylist, before: List<CloudPlaylist>): List<CloudPlaylist> {
        requireOwner(identity, playlist)
        requireOrdinaryPlaylist(playlist)
        val current = before.firstOrNull { it.collection.id == playlist.collection.id } ?: return before
        requireOrdinaryPlaylist(current)
        if (current.type == 0 && !current.canEdit) throw MusicFailure(MusicError.Unknown("只能管理自己创建的云歌单"))
        checkSuccess(transport.encrypted("/v2/delete_list", JSONObject().put("listid", CloudLibraryProtocol.playlistId(playlist))
            .put("total_ver", current.totalVersion).put("type", 1), identity))
        return allPlaylists(identity).also { rows -> if (rows.any { it.collection.id == playlist.collection.id }) unconfirmed() }
    }

    private suspend fun allPlaylists(identity: SessionData): List<CloudPlaylist> = readPlaylists(identity)
    private suspend fun allSongs(identity: SessionData, playlist: CloudPlaylist): List<CloudSong> = readSongs(identity, playlist)
    private fun unconfirmed(): Nothing = throw MusicFailure(MusicError.Unknown("酷狗尚未同步这次操作，请稍后刷新确认"))

    private fun requireIdentity(identity: SessionData) {
        CloudLibraryProtocol.userId(identity)
        if (identity.token.isBlank()) throw MusicFailure(MusicError.AuthenticationExpired)
    }
    private fun requireOwner(identity: SessionData, playlist: CloudPlaylist) {
        requireIdentity(identity)
        if (playlist.collection.kind != CollectionKind.CLOUD || playlist.collection.ownerId != identity.userId)
            throw MusicFailure(MusicError.AuthenticationExpired)
        CloudLibraryProtocol.playlistId(playlist)
    }
    private fun requireEditable(identity: SessionData, playlist: CloudPlaylist) {
        requireOwner(identity, playlist)
        if (!playlist.canEdit) throw MusicFailure(MusicError.Unknown("只能编辑自己创建的云歌单"))
    }
    private fun requireOrdinaryPlaylist(playlist: CloudPlaylist) {
        if (playlist.isFavorites) throw MusicFailure(MusicError.Unknown("“我喜欢”是酷狗系统歌单，不能重命名或删除"))
    }
    private fun validName(value: String): String {
        val name = value.trim()
        if (name.isBlank() || name.length > 40 || name.any(Char::isISOControl) || name == "我喜欢")
            throw MusicFailure(MusicError.Unknown("请输入 1–40 字的歌单名称，不能使用系统歌单名称"))
        return name
    }
    private fun writeQuery(identity: SessionData) = mapOf("last_time" to "${System.currentTimeMillis() / 1000}",
        "last_area" to "gztx", "userid" to identity.userId, "token" to identity.token)

    companion object {
        private const val ROUTER = "cloudlist.service.kugou.com"
        internal fun checkSuccess(response: JSONObject) {
            val code = response.optInt("error_code", response.optInt("errcode", 0))
            if (response.optInt("status", -1) == 1 && code == 0) return
            throw MusicFailure(catalogStatusError(code, MusicError.Unknown("酷狗云端未确认操作，请刷新后重试")))
        }
    }
}

private class ProductionCloudTransport(private val catalog: CatalogGateway, private val http: HttpTransport,
                                       private val sessions: SessionStore) : CloudGatewayTransport {
    override suspend fun signed(call: CatalogCall, identity: SessionData): JSONObject {
        ensureCurrent(identity)
        try {
            return catalog.catalog(call, identity).also(CloudLibraryGateway::checkSuccess)
        } catch (error: MusicFailure) {
            if (error.error == MusicError.AuthenticationExpired) sessions.clearIfMatches(identity)
            throw error
        }
    }

    override suspend fun encrypted(path: String, body: JSONObject, identity: SessionData): JSONObject {
        ensureCurrent(identity)
        val payload = CloudLibraryProtocol.encrypted(body, identity, System.currentTimeMillis() / 1000,
            legacyDelete = path == "/v2/delete_list")
        val url = "https://gateway.kugou.com$path".toHttpUrl().newBuilder().apply {
            payload.params.forEach { (name, value) -> addQueryParameter(name, value) }
        }.build()
        val request = Request.Builder().url(url).header("x-router", "cloudlist.service.kugou.com")
            .header("mid", identity.mid).header("dfid", "-").header("clienttime", payload.params.getValue("clienttime"))
            .header("kg-rc", "1").header("kg-thash", "5d816a0").header("kg-rec", "1")
            .header("kg-rf", "B9EDA08A64250DEFFBCADDEE00F8F25F")
            .post(payload.body.toRequestBody("application/json;charset=utf-8".toMediaType())).build()
        try {
            val response = CloudLibraryProtocol.decrypt(http.bytes(request), payload.sessionKey)
            CloudLibraryGateway.checkSuccess(response)
            return response
        } catch (error: MusicFailure) {
            if (error.error == MusicError.AuthenticationExpired) sessions.clearIfMatches(identity)
            throw error
        }
    }

    private fun ensureCurrent(identity: SessionData) {
        val current = sessions.session.value
        if (current?.userId != identity.userId || current.token != identity.token)
            throw MusicFailure(MusicError.AuthenticationExpired)
    }
}
