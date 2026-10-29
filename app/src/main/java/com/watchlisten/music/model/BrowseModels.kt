package com.watchlisten.music.model

import org.json.JSONObject
import java.util.Base64

enum class CollectionKind { CHART, PLAYLIST, CLOUD, DAILY, ALBUM }

data class MusicCollection(
    val id: String, val title: String, val kind: CollectionKind,
    val cover: String = "", val subtitle: String = "", val count: Int? = null,
    val ownerId: String = "", val description: String = "", val cloudType: Int = 0
) {
    val privateContent: Boolean get() = kind == CollectionKind.CLOUD || (kind == CollectionKind.DAILY && ownerId.isNotBlank())
    val canPin: Boolean get() = !privateContent && kind != CollectionKind.DAILY
    val key: String get() = "${kind.name}:$ownerId:$id"
    val route: String get() = "collection/" + Base64.getUrlEncoder().withoutPadding().encodeToString(key.toByteArray(Charsets.UTF_8))
    fun json() = JSONObject().put("id", id).put("title", title).put("kind", kind.name)
        .put("cover", cover).put("subtitle", subtitle).put("owner", ownerId).put("description", description)
        .put("cloudType", cloudType)
        .apply { count?.let { put("count", it) } }
    companion object {
        fun fromJson(value: JSONObject): MusicCollection? = runCatching {
            MusicCollection(value.getString("id"), value.getString("title"), CollectionKind.valueOf(value.getString("kind")),
                value.optString("cover"), value.optString("subtitle"),
                if (value.has("count")) value.optInt("count").coerceAtLeast(0) else null,
                value.optString("owner"), value.optString("description"), value.optInt("cloudType", 0))
        }.getOrNull()?.takeIf { it.id.isNotBlank() && it.title.isNotBlank() }
    }
}

enum class CollectionFeed(val route: String, val title: String) {
    CHARTS("rankings", "音乐排行榜"), FEATURED("featured", "精选歌单"), CLOUD("cloud", "酷狗云歌单"), PINNED("pinned", "本机收藏歌单");
    companion object { fun ofRoute(route: String) = entries.firstOrNull { it.route == route } }
}

data class CatalogPage<T>(val items: List<T>, val total: Int?, val hasMore: Boolean,
                          val description: String = "", val rawCount: Int = items.size)

fun isCollectionRoute(route: String): Boolean = route.startsWith("collection/") &&
    route.length in 12..1024 && route.substringAfter('/').all { it.isLetterOrDigit() || it == '-' || it == '_' }
