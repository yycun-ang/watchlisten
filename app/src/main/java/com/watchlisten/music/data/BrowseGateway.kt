package com.watchlisten.music.data

import com.watchlisten.music.model.*
import org.json.JSONObject
import java.security.MessageDigest

internal data class CatalogCall(val path: String, val router: String = "", val query: Map<String, String> = emptyMap(),
                                val body: JSONObject? = null, val post: Boolean = false,
                                val headers: Map<String, String> = emptyMap())

interface BrowseSource {
    suspend fun collections(feed: CollectionFeed, page: Int): CatalogPage<MusicCollection>
    suspend fun tracks(collection: MusicCollection, page: Int): CatalogPage<Track>
    suspend fun hotWords(): List<String>
}

class BrowseGateway(private val catalog: CatalogGateway, private val sessions: SessionStore) : BrowseSource {
    override suspend fun collections(feed: CollectionFeed, page: Int): CatalogPage<MusicCollection> {
        val identity = sessions.session.value
        val call = when (feed) {
            CollectionFeed.CHARTS -> CatalogCall("/ocean/v6/rank/list", query = mapOf("plat" to "2", "withsong" to "1", "parentid" to "0"))
            CollectionFeed.CLOUD -> {
                val user = requireSession(identity)
                CatalogCall("/v7/get_all_list", CLOUD_ROUTER, mapOf("plat" to "1"),
                    JSONObject().put("userid", user.userId.toLong()).put("token", user.token).put("total_ver", 979)
                        .put("type", 2).put("page", page).put("pagesize", PAGE_SIZE))
            }
            CollectionFeed.FEATURED -> featured(identity, page)
            CollectionFeed.PINNED -> return CatalogPage(emptyList(), 0, false)
        }
        val response = catalog.catalog(call, identity)
        return if (feed == CollectionFeed.CHARTS) BrowseParser.charts(response)
        else BrowseParser.playlists(response, page, PAGE_SIZE, if (feed == CollectionFeed.CLOUD) identity!!.userId else "")
    }

    override suspend fun tracks(collection: MusicCollection, page: Int): CatalogPage<Track> {
        val identity = if (collection.kind == CollectionKind.DAILY && collection.ownerId.isBlank()) null else sessions.session.value
        if (collection.privateContent && (requireSession(identity).userId != collection.ownerId))
            throw MusicFailure(MusicError.AuthenticationExpired)
        val call = when (collection.kind) {
            CollectionKind.CHART -> CatalogCall("/openapi/kmr/v2/rank/audio", body = JSONObject()
                .put("show_portrait_mv", 1).put("show_type_total", 1).put("filter_original_remarks", 1)
                .put("area_code", 1).put("pagesize", PAGE_SIZE).put("rank_cid", 0).put("type", 1)
                .put("page", page).put("rank_id", collection.id.toLong()), headers = mapOf("kg-tid" to "369"))
            CollectionKind.PLAYLIST -> CatalogCall("/pubsongs/v2/get_other_list_file_nofilt", query = mapOf(
                "area_code" to "1", "begin_idx" to "${(page.toLong() - 1) * PAGE_SIZE}", "plat" to "1", "type" to "1",
                "mode" to "1", "personal_switch" to "1", "extend_fields" to "abtags,hot_cmt,popularization",
                "pagesize" to "$PAGE_SIZE", "global_collection_id" to collection.id))
            CollectionKind.CLOUD -> CatalogCall("/v4/get_list_all_file_v3", CLOUD_ROUTER, body = JSONObject()
                .put("listid", collection.id.toLong()).put("userid", identity!!.userId.toLong()).put("type", collection.cloudType)
                .put("page", page).put("pagesize", PAGE_SIZE).put("area_code", 1).put("allplatform", 1)
                .put("show_cover", 1).put("token", identity.token))
            CollectionKind.DAILY -> CatalogCall("/everyday_song_recommend", "everydayrec.service.kugou.com",
                query = mapOf("platform" to "android"), post = true)
            CollectionKind.ALBUM -> CatalogCall("/v1/album_audio/lite", "openapi.kugou.com", body = JSONObject()
                .put("album_id", collection.id).put("is_buy", "").put("page", page).put("pagesize", PAGE_SIZE),
                headers = mapOf("kg-tid" to "255"))
        }
        return BrowseParser.tracks(catalog.catalog(call, identity), collection, page, PAGE_SIZE)
    }

    override suspend fun hotWords(): List<String> = BrowseParser.hotWords(catalog.catalog(
        CatalogCall("/api/v3/search/hot_tab", "msearch.kugou.com", mapOf("navid" to "1", "plat" to "2")), sessions.session.value))

    private fun featured(identity: SessionData?, page: Int): CatalogCall {
        val time = "${System.currentTimeMillis() / 1000}"
        val key = md5("3116LnT6xpN3khm36zse0QzvmgTZ3waWdRSA11436$time")
        return CatalogCall("/v2/special_recommend", "specialrec.service.kugou.com", body = JSONObject()
            .put("appid", 3116).put("mid", identity?.mid ?: sessions.installationMid).put("clientver", 11436)
            .put("platform", "android").put("clienttime", time).put("userid", identity?.userId?.toLong() ?: 0)
            .put("module_id", 1).put("page", page).put("pagesize", PAGE_SIZE).put("key", key)
            .put("special_recommend", JSONObject().put("withtag", 1).put("withsong", 1).put("sort", 1)
                .put("ugc", 1).put("is_selected", 0).put("withrecommend", 1).put("area_code", 1).put("categoryid", 0))
            .put("req_multi", 1).put("retrun_min", 5).put("return_special_falg", 1))
    }

    private fun requireSession(identity: SessionData?) = identity ?: throw MusicFailure(MusicError.AuthenticationExpired)
    private fun md5(text: String) = MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    companion object { const val PAGE_SIZE = 30; private const val CLOUD_ROUTER = "cloudlist.service.kugou.com" }
}
