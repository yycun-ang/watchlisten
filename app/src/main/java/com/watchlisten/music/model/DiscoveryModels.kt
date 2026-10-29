package com.watchlisten.music.model

enum class DiscoveryKind(val label: String) { SONGS("歌曲"), ARTISTS("歌手"), ALBUMS("专辑"), PLAYLISTS("歌单") }

data class MusicArtist(val id: Long, val name: String, val avatar: String = "", val songCount: Int? = null,
                       val albumCount: Int? = null, val intro: String = "") {
    val route: String get() = "artist/$id"
}

sealed interface DiscoveryItem {
    val key: String
    data class Song(val track: Track) : DiscoveryItem { override val key: String get() = "song:${track.id}" }
    data class Artist(val artist: MusicArtist) : DiscoveryItem { override val key: String get() = artist.route }
    data class Collection(val collection: MusicCollection) : DiscoveryItem { override val key: String get() = collection.key }
}

fun artistIdFromRoute(route: String): Long? = route.takeIf { it.matches(Regex("artist/[1-9][0-9]{0,18}")) }
    ?.substringAfter('/')?.toLongOrNull()?.takeIf { it > 0 }
fun isArtistRoute(route: String): Boolean = artistIdFromRoute(route) != null
