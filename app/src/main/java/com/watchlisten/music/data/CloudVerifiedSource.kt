package com.watchlisten.music.data

import com.watchlisten.music.model.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** 完整读回只由一层执行，返回快照供协调器直接复用。 */
internal suspend fun <T> readCloudPages(request: suspend (Int) -> CatalogPage<T>): List<T> {
    val rows = mutableListOf<T>()
    val seen = hashSetOf<List<T>>()
    var page = 1
    while (true) {
        val result = request(page++)
        currentCoroutineContext().ensureActive()
        if (result.items.isNotEmpty() && !seen.add(result.items)) throw MusicFailure(MusicError.InvalidResponse)
        rows += result.items
        if (!result.hasMore) return rows
        if (result.rawCount == 0 || result.items.isEmpty()) throw MusicFailure(MusicError.InvalidResponse)
    }
}

internal suspend fun CloudLibrarySource.readSongs(identity: SessionData, playlist: CloudPlaylist): List<CloudSong> =
    readCloudPages { songs(identity, playlist, it) }.also { rows ->
        if (rows.any { it.track.origin != TrackOrigin.KUGOU }) throw MusicFailure(MusicError.InvalidResponse)
    }

internal suspend fun CloudLibrarySource.readPlaylists(identity: SessionData): List<CloudPlaylist> =
    readCloudPages { playlists(identity, it) }.also { rows ->
        if (rows.any { it.collection.kind != CollectionKind.CLOUD || it.collection.ownerId != identity.userId })
            throw MusicFailure(MusicError.InvalidResponse)
    }

internal fun unconfirmedCloudWrite(): Nothing =
    throw MusicFailure(MusicError.Unknown("酷狗尚未同步这次操作，请刷新确认"))

internal fun confirmAdded(rows: List<CloudSong>, tracks: List<Track>): List<CloudSong> = rows.also {
    if (tracks.any { requested -> rows.none { sameKugouTrack(it.track, requested) } }) unconfirmedCloudWrite()
}

internal fun confirmRemoved(rows: List<CloudSong>, songs: List<CloudSong>): List<CloudSong> = rows.also {
    if (rows.any { row -> songs.any { it.fileId == row.fileId } }) unconfirmedCloudWrite()
}
