package com.watchlisten.music

import com.watchlisten.music.data.CloudPlaylistSource
import com.watchlisten.music.domain.CloudPlaylistCoordinator
import com.watchlisten.music.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

class CloudPlaylistCoordinatorTest {
    private val identity = SessionData("fixture", "123", "mid", 1)
    private val track = Track("a".repeat(32), "曲目", "歌手", audioId = 1)
    private val playlist = CloudPlaylist(MusicCollection("7", "歌单", CollectionKind.CLOUD, ownerId = "123"), creatorId = "123")

    @Test fun 换账号与同账号换token后迟到列表不得复活() = runBlocking {
        fixture { coordinator, source, sessions ->
            val release = CompletableDeferred<Unit>()
            source.beforeLists = { withContext(NonCancellable) { release.await() } }
            coordinator.refresh()
            sessions.value = identity.copy(token = "new-token")
            release.complete(Unit); yield()
            assertTrue(coordinator.state.value.playlists.isEmpty())
            assertFalse(coordinator.state.value.loading)
        }
    }

    @Test fun 清除选择后旧请求不能覆盖新的选歌上下文() = runBlocking {
        fixture { coordinator, source, _ ->
            val release = CompletableDeferred<Unit>()
            var requests = 0
            source.beforeLists = { if (++requests == 1) withContext(NonCancellable) { release.await() } }
            coordinator.refresh()
            coordinator.chooseTrack(track)
            assertEquals(track, coordinator.state.value.addingTrack)
            release.complete(Unit); yield()
            assertEquals(track, coordinator.state.value.addingTrack)
            assertEquals(1, coordinator.state.value.playlists.size)
        }
    }

    @Test fun 多页失败不能凭空视为删除已确认() = runBlocking {
        fixture { coordinator, source, _ ->
            source.failAfterDelete = true
            coordinator.delete(playlist)
            assertNotNull(coordinator.state.value.error)
            assertNull(coordinator.state.value.message)
        }
    }

    @Test fun 缺少fileid的只读歌曲也需全部保留() = runBlocking {
        fixture { coordinator, source, _ ->
            source.entries = listOf(CloudSong(track, 0), CloudSong(track.copy(hash = "b".repeat(32), audioId = 2), 0))
            coordinator.open(playlist)
            assertEquals(2, coordinator.state.value.songs.size)
            coordinator.removeSongs(coordinator.state.value.songs)
            assertEquals(0, source.removals)
            assertNotNull(coordinator.state.value.error)
        }
    }

    @Test fun 多选移除必须确认每个fileid都消失() = runBlocking {
        fixture { coordinator, source, _ ->
            source.entries = listOf(CloudSong(track, 1), CloudSong(track.copy(hash = "b".repeat(32), audioId = 2), 2))
            coordinator.open(playlist)
            val selected = coordinator.state.value.songs
            source.onlyRemoveFirst = true
            coordinator.removeSongs(selected)
            assertNotNull(coordinator.state.value.error)
            assertNull(coordinator.state.value.message)
            source.onlyRemoveFirst = false
            coordinator.open(playlist)
            coordinator.removeSongs(coordinator.state.value.songs)
            assertTrue(coordinator.state.value.songs.isEmpty())
            assertNotNull(coordinator.state.value.message)
        }
    }

    @Test fun 本地文件不能被选为云添加且云源不能混入本地曲目() = runBlocking {
        fixture { coordinator, source, _ ->
            coordinator.chooseTrack(track.copy(origin = TrackOrigin.LOCAL, localPath = "/private/file.mp3"))
            assertNull(coordinator.state.value.addingTrack)
            source.entries = listOf(CloudSong(track.copy(origin = TrackOrigin.LOCAL), 1))
            coordinator.open(playlist)
            assertTrue(coordinator.state.value.songs.isEmpty())
            assertNotNull(coordinator.state.value.error)
        }
    }

    @Test fun 重复分页明确报错且保留上次有效列表() = runBlocking {
        fixture { coordinator, source, _ ->
            coordinator.refresh()
            source.repeatLists = true
            coordinator.refresh()
            assertEquals(1, coordinator.state.value.playlists.size)
            assertEquals(MusicError.InvalidResponse, coordinator.state.value.error)
        }
    }

    private suspend fun fixture(block: suspend (CloudPlaylistCoordinator, PlaylistMemorySource, MutableStateFlow<SessionData?>) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val source = PlaylistMemorySource(playlist)
            val sessions = MutableStateFlow<SessionData?>(identity)
            block(CloudPlaylistCoordinator(source, sessions, scope), source, sessions)
        } finally { scope.cancel() }
    }
}

private class PlaylistMemorySource(private val playlist: CloudPlaylist) : CloudPlaylistSource {
    var entries = emptyList<CloudSong>()
    var beforeLists: suspend () -> Unit = {}
    var failAfterDelete = false
    var deleted = false
    var repeatLists = false
    var onlyRemoveFirst = false
    var removals = 0
    override suspend fun playlists(identity: SessionData, page: Int): CatalogPage<CloudPlaylist> {
        beforeLists()
        if (deleted && failAfterDelete && page == 2) throw MusicFailure(MusicError.NetworkUnavailable)
        return CatalogPage(listOf(playlist), if (repeatLists) null else 1, repeatLists || (deleted && failAfterDelete))
    }
    override suspend fun songs(identity: SessionData, playlist: CloudPlaylist, page: Int) = CatalogPage(entries, entries.size, false)
    override suspend fun add(identity: SessionData, playlist: CloudPlaylist, tracks: List<Track>) {
        entries = entries + tracks.map { CloudSong(it, (entries.size + 1).toLong()) }
    }
    override suspend fun remove(identity: SessionData, playlist: CloudPlaylist, songs: List<CloudSong>) {
        removals++
        val selected = if (onlyRemoveFirst) songs.take(1) else songs
        entries = entries.filterNot { row -> selected.any { it.fileId == row.fileId } }
    }
    override suspend fun createPlaylist(identity: SessionData, name: String) = Unit
    override suspend fun renamePlaylist(identity: SessionData, playlist: CloudPlaylist, name: String) = Unit
    override suspend fun deletePlaylist(identity: SessionData, playlist: CloudPlaylist) { deleted = true }
}
