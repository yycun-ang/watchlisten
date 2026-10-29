package com.watchlisten.music

import com.watchlisten.music.data.*
import com.watchlisten.music.domain.*
import com.watchlisten.music.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

class CloudReliabilityV10Test {
    private val identity = SessionData("private-fixture-token", "123", "mid", 1)
    private val first = track(1)
    private val second = track(2)
    private fun track(id: Int) = Track(id.toString(16).padStart(32, '0'), "歌曲$id", "歌手", audioId = id.toLong())

    @Test fun 爱心写入只复用一次完整读回且再次操作复用系统歌单定位() = runBlocking {
        favoriteFixture { coordinator, source, _, _ ->
            coordinator.setLiked(first, true)
            assertEquals(1, source.listReads)
            assertEquals(2, source.songReads)
            assertEquals(1, source.addCalls)
            assertTrue(coordinator.state.value.isLiked(first))
            coordinator.setLiked(first, false)
            assertEquals(1, source.listReads)
            assertEquals(4, source.songReads)
            assertEquals(1, source.removeCalls)
        }
    }

    @Test fun 四十五首迁移按二十首分批且每批读回后逐首确认() = runBlocking {
        favoriteFixture { coordinator, source, _, _ ->
            source.batchSize = 20
            val tracks = (1..45).map(::track)
            val confirmed = mutableListOf<Track>()
            val result = coordinator.migrate(tracks) { confirmed += it }
            assertNull(result.error)
            assertEquals(tracks, confirmed)
            assertEquals(listOf(20, 20, 5), source.addedBatchSizes)
            assertEquals(1, source.listReads)
            assertEquals(6, source.songReads)
            assertEquals(45, coordinator.state.value.tracks.size)
        }
    }

    @Test fun 批量写入部分成功后仍按真实读回确认且重试不重复添加() = runBlocking {
        favoriteFixture { coordinator, source, _, _ ->
            source.batchSize = 20; source.partialAdd = true
            val confirmed = mutableListOf<Track>()
            val result = coordinator.migrate(listOf(first, second)) { confirmed += it }
            assertEquals(listOf(first), confirmed)
            assertEquals(setOf(first.id), result.confirmedIds)
            assertEquals(setOf(second.id), result.remainingIds)
            assertNotNull(result.error)
            source.partialAdd = false
            assertTrue(coordinator.migrate(listOf(first, second)).remainingIds.isEmpty())
            assertEquals(1, source.entries.count { sameKugouTrack(it.track, first) })
        }
    }

    @Test fun 撤销取消喜欢需要重新写云并确认且不会制造本机喜欢() = runBlocking {
        favoriteFixture { coordinator, source, _, _ ->
            source.entries += CloudSong(first, 10)
            coordinator.setLiked(first, false)
            assertTrue(coordinator.state.value.undoAvailable)
            assertFalse(coordinator.state.value.isLiked(first))
            coordinator.undo()
            assertTrue(coordinator.state.value.isLiked(first))
            assertEquals(1, source.addCalls)
            assertFalse(coordinator.state.value.undoAvailable)
        }
    }

    @Test fun 撤销前手机已经恢复时只确认而不再添加() = runBlocking {
        favoriteFixture { coordinator, source, _, _ ->
            source.entries += CloudSong(first, 10)
            coordinator.setLiked(first, false)
            source.entries += CloudSong(first, 11)
            coordinator.undo()
            assertEquals(0, source.addCalls)
            assertTrue(coordinator.state.value.isLiked(first))
        }
    }

    @Test fun 撤销过期或换token后不能继续写入() = runBlocking {
        favoriteFixture { coordinator, source, sessions, clock ->
            source.entries += CloudSong(first, 10)
            coordinator.setLiked(first, false)
            clock.now += 60_001
            coordinator.undo()
            assertEquals(0, source.addCalls)
            assertFalse(coordinator.state.value.undoAvailable)
            source.entries += CloudSong(first, 11)
            coordinator.setLiked(first, false)
            sessions.value = identity.copy(token = "new-token")
            coordinator.undo()
            assertEquals(0, source.addCalls)
            assertTrue(coordinator.state.value.tracks.isEmpty())
        }
    }

    @Test fun 撤销读回未确认时保留错误和有效期内重试入口() = runBlocking {
        favoriteFixture { coordinator, source, _, _ ->
            source.entries += CloudSong(first, 10)
            coordinator.setLiked(first, false)
            source.ignoreAdd = true
            coordinator.undo()
            assertFalse(coordinator.state.value.isLiked(first))
            assertTrue(coordinator.state.value.undoAvailable)
            assertNotNull(coordinator.state.value.error)
        }
    }

    @Test fun 新建已提交后超时重复点击只查结果不再次创建() = runBlocking {
        playlistFixture { coordinator, source, _, store, _ ->
            source.commitThenTimeout = true
            coordinator.create("跑步")
            assertEquals(1, source.creates)
            assertNotNull(coordinator.state.value.pendingCreate)
            assertEquals(CloudConfirmationStatus.AWAITING_CONFIRMATION, coordinator.state.value.confirmationStatus)
            assertEquals(64, store.load("123")!!.identityFingerprint.length)
            assertNotEquals(identity.token, store.load("123")!!.identityFingerprint)
            coordinator.create("跑步")
            assertEquals(1, source.creates)
            assertNull(coordinator.state.value.pendingCreate)
            assertEquals(CloudConfirmationStatus.CONFIRMED, coordinator.state.value.confirmationStatus)
        }
    }

    @Test fun 未知创建跨协调器重建和重新授权后仍只确认() = runBlocking {
        val source = ReliabilityCloudSource(); source.commitThenTimeout = true
        val store = MemoryCloudCreationStore()
        val sessions = MutableStateFlow<SessionData?>(identity)
        val firstScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        CloudPlaylistCoordinator(source, sessions, firstScope, creationStore = store).create("保留")
        firstScope.cancel()
        sessions.value = identity.copy(token = "renewed-token")
        val secondScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val restored = CloudPlaylistCoordinator(source, sessions, secondScope, creationStore = store)
            assertEquals("保留", restored.state.value.pendingCreate?.name)
            restored.create("保留")
            assertEquals(1, source.creates)
            assertNull(restored.state.value.pendingCreate)
            assertNull(store.load("123"))
        } finally { secondScope.cancel() }
    }

    @Test fun 查不到未知创建时不能重发且明确放弃后才可新建() = runBlocking {
        playlistFixture { coordinator, source, _, _, _ ->
            source.failBeforeCreate = true
            coordinator.create("未找到")
            coordinator.create("换个名字")
            assertEquals(1, source.creates)
            assertEquals("未找到", coordinator.state.value.pendingCreate?.name)
            assertTrue(coordinator.state.value.message.orEmpty().contains("尚未"))
            coordinator.abandonPendingCreate()
            source.failBeforeCreate = false
            coordinator.create("新的")
            assertEquals(2, source.creates)
            assertNull(coordinator.state.value.pendingCreate)
        }
    }

    @Test fun 多个同名新歌单不得猜测待确认结果() = runBlocking {
        playlistFixture { coordinator, source, _, _, _ ->
            source.failBeforeCreate = true
            coordinator.create("同名")
            source.lists += source.playlist("20", "同名")
            source.lists += source.playlist("21", "同名")
            coordinator.refresh()
            assertNotNull(coordinator.state.value.pendingCreate)
            assertTrue(coordinator.state.value.message.orEmpty().contains("多个"))
            assertEquals(1, source.creates)
        }
    }

    @Test fun 待确认记录按账号隔离且旧账号迟到响应不能复活() = runBlocking {
        playlistFixture { coordinator, source, sessions, store, _ ->
            val release = CompletableDeferred<Unit>()
            source.afterCreate = { withContext(NonCancellable) { release.await() } }
            coordinator.create("旧账号")
            sessions.value = identity.copy(userId = "456", token = "other")
            assertNull(coordinator.state.value.pendingCreate)
            release.complete(Unit); yield()
            assertTrue(coordinator.state.value.playlists.isEmpty())
            assertNotNull(store.load("123"))
            assertNull(store.load("456"))
        }
    }

    @Test fun 当前歌单刷新会读取歌曲和同步时间且短期前台切换不重复刷新() = runBlocking {
        playlistFixture { coordinator, source, _, _, clock ->
            val list = source.lists.first()
            source.entries += CloudSong(first, 1)
            coordinator.open(list)
            assertEquals(listOf(first), coordinator.state.value.songs.map { it.track })
            val reads = source.songReads
            clock.now += 1000
            coordinator.refreshIfStale()
            assertEquals(reads, source.songReads)
            source.entries.clear(); source.entries += CloudSong(second, 2)
            clock.now += 60_000
            coordinator.refreshIfStale()
            assertEquals(listOf(second), coordinator.state.value.songs.map { it.track })
            assertEquals(clock.now, coordinator.state.value.updatedAtMs)
        }
    }

    @Test fun 手机已删除当前歌单时刷新清除旧歌曲() = runBlocking {
        playlistFixture { coordinator, source, _, _, _ ->
            coordinator.open(source.lists.first())
            source.lists.clear()
            coordinator.refresh()
            assertNull(coordinator.state.value.selected)
            assertTrue(coordinator.state.value.songs.isEmpty())
        }
    }

    @Test fun 多选移出后撤销恢复云端成员且换号撤销失效() = runBlocking {
        playlistFixture { coordinator, source, sessions, _, _ ->
            source.entries += listOf(CloudSong(first, 1), CloudSong(second, 2))
            coordinator.open(source.lists.first())
            coordinator.removeSongs(coordinator.state.value.songs)
            assertTrue(coordinator.state.value.undoAvailable)
            assertTrue(coordinator.state.value.songs.isEmpty())
            coordinator.undo()
            assertEquals(setOf(first.id, second.id), coordinator.state.value.songs.map { it.track.id }.toSet())
            assertFalse(coordinator.state.value.undoAvailable)
            coordinator.removeSongs(coordinator.state.value.songs)
            val writes = source.addCalls
            sessions.value = identity.copy(token = "new-token")
            coordinator.undo()
            assertEquals(writes, source.addCalls)
        }
    }

    @Test fun 批量移除部分生效时只为已确认移除的歌曲提供撤销() = runBlocking {
        playlistFixture { coordinator, source, _, _, _ ->
            source.entries += listOf(CloudSong(first, 1), CloudSong(second, 2))
            coordinator.open(source.lists.first())
            source.removeFirstOnly = true
            coordinator.removeSongs(coordinator.state.value.songs)
            assertNotNull(coordinator.state.value.error)
            assertEquals(listOf(second), coordinator.state.value.songs.map { it.track })
            assertTrue(coordinator.state.value.undoAvailable)
            coordinator.undo()
            assertEquals(setOf(first.id, second.id), source.entries.map { it.track.id }.toSet())
            assertEquals(1, source.entries.count { sameKugouTrack(it.track, second) })
        }
    }

    private suspend fun favoriteFixture(block: suspend (CloudFavoritesCoordinator, ReliabilityCloudSource, MutableStateFlow<SessionData?>, TestCloudClock) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val source = ReliabilityCloudSource(); val sessions = MutableStateFlow<SessionData?>(identity); val clock = TestCloudClock()
            block(CloudFavoritesCoordinator(source, sessions, scope, monotonicMs = { clock.now }), source, sessions, clock)
        } finally { scope.cancel() }
    }

    private suspend fun playlistFixture(block: suspend (CloudPlaylistCoordinator, ReliabilityCloudSource, MutableStateFlow<SessionData?>, MemoryCloudCreationStore, TestCloudClock) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val source = ReliabilityCloudSource(); val sessions = MutableStateFlow<SessionData?>(identity)
            val store = MemoryCloudCreationStore(); val clock = TestCloudClock()
            block(CloudPlaylistCoordinator(source, sessions, scope, creationStore = store,
                wallClockMs = { clock.now }, monotonicMs = { clock.now }), source, sessions, store, clock)
        } finally { scope.cancel() }
    }
}

private class TestCloudClock(var now: Long = 1000)
private class ReliabilityCloudSource : CloudPlaylistSource {
    val lists = mutableListOf(playlist("7", "我喜欢"))
    val entries = mutableListOf<CloudSong>()
    var batchSize = 1
    override val mutationBatchSize: Int get() = batchSize
    var listReads = 0; var songReads = 0; var addCalls = 0; var removeCalls = 0; var creates = 0
    val addedBatchSizes = mutableListOf<Int>()
    var partialAdd = false; var ignoreAdd = false; var commitThenTimeout = false; var failBeforeCreate = false; var removeFirstOnly = false
    var afterCreate: suspend () -> Unit = {}
    private var nextFile = 100L
    fun playlist(id: String, name: String, owner: String = "123") = CloudPlaylist(MusicCollection(id, name,
        CollectionKind.CLOUD, ownerId = owner), creatorId = owner)
    override suspend fun playlists(identity: SessionData, page: Int): CatalogPage<CloudPlaylist> {
        listReads++
        val rows = lists.filter { it.collection.ownerId == identity.userId }
        return CatalogPage(rows, rows.size, false)
    }
    override suspend fun songs(identity: SessionData, playlist: CloudPlaylist, page: Int): CatalogPage<CloudSong> {
        songReads++
        val offset = (page - 1) * 30
        return CatalogPage(entries.drop(offset).take(30), entries.size, offset + 30 < entries.size)
    }
    override suspend fun add(identity: SessionData, playlist: CloudPlaylist, tracks: List<Track>) {
        addCalls++; addedBatchSizes += tracks.size
        if (ignoreAdd) return
        (if (partialAdd) tracks.take(1) else tracks).forEach { entries += CloudSong(it, nextFile++) }
        if (partialAdd) throw MusicFailure(MusicError.SlowNetwork)
    }
    override suspend fun remove(identity: SessionData, playlist: CloudPlaylist, songs: List<CloudSong>) {
        val chosen = if (removeFirstOnly) songs.take(1) else songs
        removeCalls++; entries.removeAll { row -> chosen.any { it.fileId == row.fileId } }
    }
    override suspend fun createPlaylist(identity: SessionData, name: String) {
        creates++
        if (failBeforeCreate) throw MusicFailure(MusicError.SlowNetwork)
        lists += playlist((20 + creates).toString(), name.trim(), identity.userId)
        afterCreate()
        if (commitThenTimeout) throw MusicFailure(MusicError.SlowNetwork)
    }
    override suspend fun renamePlaylist(identity: SessionData, playlist: CloudPlaylist, name: String) {
        val index = lists.indexOfFirst { it.collection.key == playlist.collection.key }
        lists[index] = playlist.copy(collection = playlist.collection.copy(title = name.trim()))
    }
    override suspend fun deletePlaylist(identity: SessionData, playlist: CloudPlaylist) { lists.removeAll { it.collection.key == playlist.collection.key } }
}
