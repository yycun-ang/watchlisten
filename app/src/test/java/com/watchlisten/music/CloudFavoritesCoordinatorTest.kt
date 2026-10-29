package com.watchlisten.music

import com.watchlisten.music.data.CloudLibrarySource
import com.watchlisten.music.domain.CloudFavoritesCoordinator
import com.watchlisten.music.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

class CloudFavoritesCoordinatorTest {
    @Test fun 同曲重复条目取消喜欢时移除全部fileid且界面只显示一首() = runBlocking {
        withFixture { coordinator, source, _ ->
            source.entries += listOf(CloudSong(first, 11), CloudSong(first, 12))
            coordinator.refresh()
            assertEquals(1, coordinator.state.value.tracks.size)
            coordinator.setLiked(first, false)
            assertEquals(listOf(11L, 12L), source.deletedIds)
            assertTrue(coordinator.state.value.tracks.isEmpty())
            assertNull(coordinator.state.value.error)
        }
    }
    private val identity = SessionData("fixture-token", "123", "fixture-mid", 1)
    private val first = Track("a".repeat(32), "甲", "歌手", audioId = 1)
    private val second = Track("b".repeat(32), "乙", "歌手", audioId = 2)

    @Test fun 未登录不读取不写入也不会生成本机喜欢() = runBlocking {
        withFixture(null) { coordinator, source, _ ->
            coordinator.visible(true); coordinator.toggle(first)
            assertEquals(0, source.reads)
            assertEquals(0, source.writes)
            assertTrue(coordinator.state.value.tracks.isEmpty())
            assertEquals(MusicError.AuthenticationExpired, coordinator.state.value.error)
        }
    }

    @Test fun 喜欢取消喜欢都等云端读回确认且删除使用fileid() = runBlocking {
        withFixture { coordinator, source, _ ->
            coordinator.toggle(first)
            assertTrue(coordinator.state.value.isLiked(first))
            assertEquals(1, source.writes)
            coordinator.toggle(first.copy(audioId = 0))
            assertFalse(coordinator.state.value.isLiked(first))
            assertEquals(2, source.writes)
            assertTrue(source.deletedIds.single() > 0)
        }
    }

    @Test fun 网络写失败不改变云收藏且提供错误() = runBlocking {
        withFixture { coordinator, source, _ ->
            coordinator.refresh()
            source.failWrite = true
            coordinator.toggle(first)
            assertTrue(coordinator.state.value.tracks.isEmpty())
            assertEquals(MusicError.NetworkUnavailable, coordinator.state.value.error)
            assertTrue(coordinator.state.value.pendingIds.isEmpty())
        }
    }

    @Test fun 服务声称成功但读回没有歌曲时不得显示收藏成功() = runBlocking {
        withFixture { coordinator, source, _ ->
            source.ignoreWrite = true
            coordinator.toggle(first)
            assertFalse(coordinator.state.value.isLiked(first))
            assertNotNull(coordinator.state.value.error)
            assertNull(coordinator.state.value.message)
        }
    }

    @Test fun 读回确认之前保持pending且不乐观填充歌曲() = runBlocking {
        withFixture { coordinator, source, _ ->
            val release = CompletableDeferred<Unit>()
            source.afterWrite = { release.await() }
            coordinator.toggle(first)
            assertTrue(first.id in coordinator.state.value.pendingIds)
            assertFalse(coordinator.state.value.isLiked(first))
            release.complete(Unit); yield()
            assertTrue(coordinator.state.value.isLiked(first))
            assertTrue(coordinator.state.value.pendingIds.isEmpty())
        }
    }

    @Test fun 换账号或同账号换token立即清空并拒绝迟到结果() = runBlocking {
        withFixture { coordinator, source, sessions ->
            source.entries += CloudSong(first, 9)
            coordinator.refresh()
            assertEquals(1, coordinator.state.value.tracks.size)
            val release = CompletableDeferred<Unit>()
            source.beforeRead = { withContext(NonCancellable) { release.await() } }
            coordinator.refresh()
            sessions.value = identity.copy(token = "new-token")
            assertTrue(coordinator.state.value.tracks.isEmpty())
            release.complete(Unit); yield()
            assertTrue(coordinator.state.value.tracks.isEmpty())
            assertFalse(coordinator.state.value.loaded)
        }
    }

    @Test fun 本机迁移逐首确认失败保留余下且重试不重复写已确认歌曲() = runBlocking {
        withFixture { coordinator, source, _ ->
            source.rejectHash = second.hash
            val confirmed = mutableListOf<Track>()
            val result = coordinator.migrate(listOf(first, second)) { confirmed += it }
            assertEquals(setOf(first.id), result.confirmedIds)
            assertEquals(setOf(second.id), result.remainingIds)
            assertEquals(listOf(first), confirmed)
            assertNotNull(result.error)
            source.rejectHash = null
            val retry = coordinator.migrate(listOf(first, second))
            assertTrue(retry.remainingIds.isEmpty())
            assertEquals(1, source.addedTracks.count { it.id == first.id })
        }
    }

    @Test fun 迁移取消前已确认的回调不会丢失() = runBlocking {
        withFixture { coordinator, source, _ ->
            val release = CompletableDeferred<Unit>()
            source.beforeAdd = { if (it.hash == second.hash) release.await() }
            val confirmed = mutableListOf<Track>()
            val job = launch(Dispatchers.Unconfined) { coordinator.migrate(listOf(first, second)) { confirmed += it } }
            assertEquals(listOf(first), confirmed)
            job.cancelAndJoin()
            assertEquals(listOf(first), confirmed)
            assertTrue(coordinator.state.value.pendingIds.isEmpty())
        }
    }

    @Test fun 云收藏超过首屏会完整分页且重复页明确失败() = runBlocking {
        withFixture { coordinator, source, _ ->
            source.entries += (1..201).map { CloudSong(first.copy(hash = it.toString(16).padStart(32, '0'), audioId = it.toLong()), it.toLong()) }
            coordinator.refresh()
            assertEquals(201, coordinator.state.value.tracks.size)
            source.repeatFirstPage = true
            coordinator.refresh()
            assertNotNull(coordinator.state.value.error)
            assertEquals(201, coordinator.state.value.tracks.size)
        }
    }

    @Test fun 手机已收藏的歌曲在迁移时直接读回确认不重复新增() = runBlocking {
        withFixture { coordinator, source, _ ->
            source.entries += CloudSong(first.copy(hash = "c".repeat(32)), 9)
            val result = coordinator.migrate(listOf(first))
            assertEquals(setOf(first.id), result.confirmedIds)
            assertEquals(0, source.writes)
        }
    }

    private suspend fun withFixture(account: SessionData? = identity,
                                    block: suspend (CloudFavoritesCoordinator, MemoryCloud, MutableStateFlow<SessionData?>) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val source = MemoryCloud()
            val sessions = MutableStateFlow(account)
            block(CloudFavoritesCoordinator(source, sessions, scope), source, sessions)
        } finally { scope.cancel() }
    }
}

private class MemoryCloud : CloudLibrarySource {
    val entries = mutableListOf<CloudSong>()
    val deletedIds = mutableListOf<Long>()
    val addedTracks = mutableListOf<Track>()
    var reads = 0
    var writes = 0
    var failWrite = false
    var ignoreWrite = false
    var repeatFirstPage = false
    var rejectHash: String? = null
    var beforeRead: suspend () -> Unit = {}
    var beforeAdd: suspend (Track) -> Unit = {}
    var afterWrite: suspend () -> Unit = {}
    override suspend fun playlists(identity: SessionData, page: Int): CatalogPage<CloudPlaylist> {
        reads++
        beforeRead()
        return CatalogPage(listOf(CloudPlaylist(MusicCollection("7", "我喜欢", CollectionKind.CLOUD,
            ownerId = identity.userId), creatorId = identity.userId)), 1, false)
    }
    override suspend fun songs(identity: SessionData, playlist: CloudPlaylist, page: Int): CatalogPage<CloudSong> {
        reads++
        if (writes > 0) afterWrite()
        val start = if (repeatFirstPage) 0 else (page - 1) * 30
        return CatalogPage(entries.drop(start).take(30), entries.size, start + 30 < entries.size)
    }
    override suspend fun add(identity: SessionData, playlist: CloudPlaylist, tracks: List<Track>) {
        tracks.forEach { beforeAdd(it) }
        writes++
        if (failWrite || tracks.any { it.hash == rejectHash }) throw MusicFailure(MusicError.NetworkUnavailable)
        if (!ignoreWrite) tracks.forEach { entries += CloudSong(it, (entries.size + 100).toLong()); addedTracks += it }
    }
    override suspend fun remove(identity: SessionData, playlist: CloudPlaylist, songs: List<CloudSong>) {
        writes++
        if (failWrite) throw MusicFailure(MusicError.NetworkUnavailable)
        if (!ignoreWrite) { deletedIds += songs.map { it.fileId }; entries.removeAll(songs.toSet()) }
    }
}
