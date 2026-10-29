package com.watchlisten.music

import androidx.media3.common.Player
import com.watchlisten.music.data.*
import com.watchlisten.music.domain.*
import com.watchlisten.music.model.*
import com.watchlisten.music.playback.naturalTrackTransition
import com.watchlisten.music.playback.nextPlaybackMode
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

class BrowseCoordinatorTest {
    @Test fun 分页失败后重试补齐不重启当前歌曲() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            var failed = true
            var starts = 0
            val playing = mutableListOf<Track>()
            val controller = BrowseCoordinator(FakeSource { _, page ->
                if (page == 2 && failed) throw MusicFailure(MusicError.NetworkUnavailable)
                CatalogPage(tracks((page - 1) * 30, 30), 60, page == 1)
            }, MemoryStorage(), MutableStateFlow(null), scope, startPagedPlayback = { first ->
                starts++; playing += first
                PagedCollectionPlayback { playing += it; true }
            }) { _, _ -> }
            val route = controller.open(collection)
            controller.visible(true); controller.activate(route)
            controller.performAll(route, CollectionAction.PLAY)
            assertEquals(30, playing.size)
            failed = false
            controller.loadTracks(route, append = true)
            assertEquals(1, starts)
            assertEquals(tracks(0, 60), playing)
            assertNull(controller.detail(route)!!.error)
            assertFalse(controller.detail(route)!!.loadingAll)
        } finally { scope.cancel() }
    }

    @Test fun 失败后换队列再重试不能恢复旧队列() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            var accepts = true
            var requests = 0
            var starts = 0
            val controller = BrowseCoordinator(FakeSource { _, page ->
                requests++
                if (page == 2) throw MusicFailure(MusicError.NetworkUnavailable)
                CatalogPage(tracks(0, 30), 60, true)
            }, MemoryStorage(), MutableStateFlow(null), scope, startPagedPlayback = {
                starts++; PagedCollectionPlayback { accepts }
            }) { _, _ -> }
            val route = controller.open(collection)
            controller.visible(true); controller.activate(route)
            controller.performAll(route, CollectionAction.PLAY)
            accepts = false
            controller.loadTracks(route, append = true)
            assertEquals(1, starts)
            assertEquals(2, requests)
            assertEquals("已切换队列，停止补齐歌单", controller.detail(route)!!.message)
            assertFalse(controller.detail(route)!!.loadingAll)
        } finally { scope.cancel() }
    }
    @Test fun 首批立即播放并在离开歌单后补齐完整二百零一首() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val release = CompletableDeferred<Unit>()
            val playing = mutableListOf<Track>()
            val requests = mutableListOf<Int>()
            var started = 0
            val controller = BrowseCoordinator(FakeSource { _, page ->
                requests += page
                if (page == 2) release.await()
                val offset = (page - 1) * 30
                CatalogPage(tracks(offset, minOf(30, 201 - offset)), 201, offset + 30 < 201)
            }, MemoryStorage(), MutableStateFlow(null), scope, startPagedPlayback = { first ->
                playing.clear(); playing += first
                PagedCollectionPlayback { batch -> playing += batch; true }
            }) { _, _ -> fail("渐进播放不走完整列表阻塞路径") }
            val route = controller.open(collection)
            controller.visible(true); controller.activate(route)
            controller.performAll(route, CollectionAction.PLAY) { started++; controller.deactivate(route) }
            assertEquals(1, started)
            assertEquals(30, playing.size)
            assertEquals(listOf(1, 2), requests)
            assertTrue(controller.detail(route)!!.loadingAll)
            release.complete(Unit); yield()
            assertEquals(tracks(0, 201), playing)
            assertFalse(controller.detail(route)!!.loadingAll)
            assertFalse(controller.detail(route)!!.hasMore)
            assertEquals(1, started)
        } finally { scope.cancel() }
    }

    @Test fun 补齐失败不打断已开始的音乐并可从原页重试() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            var fail = true
            var begins = 0
            val playing = mutableListOf<Track>()
            val controller = BrowseCoordinator(FakeSource { _, page ->
                if (page == 2 && fail) throw MusicFailure(MusicError.NetworkUnavailable)
                CatalogPage(tracks((page - 1) * 30, 30), 60, page == 1)
            }, MemoryStorage(), MutableStateFlow(null), scope, startPagedPlayback = { first ->
                begins++; playing.clear(); playing += first
                PagedCollectionPlayback { batch -> playing += batch; true }
            }) { _, _ -> }
            val route = controller.open(collection)
            controller.visible(true); controller.activate(route)
            controller.performAll(route, CollectionAction.PLAY)
            assertEquals(30, playing.size)
            assertEquals(MusicError.NetworkUnavailable, controller.detail(route)!!.error)
            assertTrue(controller.detail(route)!!.retryAppend)
            assertFalse(controller.detail(route)!!.loadingAll)
            fail = false
            controller.performAll(route, CollectionAction.PLAY)
            assertEquals(2, begins)
            assertEquals(tracks(0, 60), playing)
            assertNull(controller.detail(route)!!.error)
        } finally { scope.cancel() }
    }

    @Test fun 换队列后迟到的歌单页不能污染新播放队列() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val release = CompletableDeferred<Unit>()
            var acceptsOriginalQueue = true
            val queue = mutableListOf<Track>()
            val controller = BrowseCoordinator(FakeSource { _, page ->
                if (page == 2) release.await()
                CatalogPage(tracks((page - 1) * 30, 30), 60, page == 1)
            }, MemoryStorage(), MutableStateFlow(null), scope, startPagedPlayback = { first ->
                queue += first
                PagedCollectionPlayback { batch -> if (acceptsOriginalQueue) { queue += batch; true } else false }
            }) { _, _ -> }
            val route = controller.open(collection)
            controller.visible(true); controller.activate(route)
            controller.performAll(route, CollectionAction.PLAY)
            acceptsOriginalQueue = false
            queue.clear(); queue += tracks(900, 1)
            release.complete(Unit); yield()
            assertEquals(tracks(900, 1), queue)
            assertEquals("已切换队列，停止补齐歌单", controller.detail(route)!!.message)
            assertFalse(controller.detail(route)!!.loadingAll)
        } finally { scope.cancel() }
    }

    @Test fun 重复点击不会重复开播或重复追加歌曲() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val release = CompletableDeferred<Unit>()
            var begins = 0
            var appended = 0
            val controller = BrowseCoordinator(FakeSource { _, page ->
                if (page == 2) release.await()
                CatalogPage(tracks((page - 1) * 30, 30), 60, page == 1)
            }, MemoryStorage(), MutableStateFlow(null), scope, startPagedPlayback = {
                begins++; PagedCollectionPlayback { appended += it.size; true }
            }) { _, _ -> }
            val route = controller.open(collection)
            controller.visible(true); controller.activate(route)
            controller.performAll(route, CollectionAction.PLAY)
            controller.performAll(route, CollectionAction.PLAY)
            assertEquals(1, begins)
            release.complete(Unit); yield()
            assertEquals(30, appended)
            assertEquals(1, begins)
        } finally { scope.cancel() }
    }

    @Test fun 取消补齐后非协作迟到响应也不能追加() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val release = CompletableDeferred<Unit>()
            var appended = 0
            val controller = BrowseCoordinator(FakeSource { _, page ->
                if (page == 2) withContext(NonCancellable) { release.await() }
                CatalogPage(tracks((page - 1) * 30, 30), 60, page == 1)
            }, MemoryStorage(), MutableStateFlow(null), scope, startPagedPlayback = {
                PagedCollectionPlayback { appended += it.size; true }
            }) { _, _ -> }
            val route = controller.open(collection)
            controller.visible(true); controller.activate(route)
            controller.performAll(route, CollectionAction.PLAY)
            controller.cancelAllLoading()
            release.complete(Unit); yield()
            assertEquals(0, appended)
            assertFalse(controller.detail(route)!!.loadingAll)
            assertEquals(30, controller.detail(route)!!.tracks.size)
        } finally { scope.cancel() }
    }

    @Test fun 首批连接超时会结束加载并保留重试入口() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val controller = BrowseCoordinator(FakeSource { _, _ -> CatalogPage(tracks(0, 1), 1, false) },
                MemoryStorage(), MutableStateFlow(null), scope, startPagedPlayback = {
                    withTimeout(1) { awaitCancellation() }
                }) { _, _ -> }
            val route = controller.open(collection)
            controller.visible(true); controller.activate(route)
            controller.performAll(route, CollectionAction.PLAY)
            delay(30)
            assertFalse(controller.detail(route)!!.loadingAll)
            assertEquals(MusicError.SlowNetwork, controller.detail(route)!!.error)
        } finally { scope.cancel() }
    }

    @Test fun 播放器连接超时应结束加载并允许重试() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val controller = BrowseCoordinator(FakeSource { _, _ -> CatalogPage(tracks(0, 1), 1, false) },
                MemoryStorage(), MutableStateFlow(null), scope) { _, _ -> withTimeout(1) { awaitCancellation() } }
            val route = controller.open(collection)
            controller.visible(true); controller.activate(route)
            controller.performAll(route, CollectionAction.QUEUE)
            delay(30)
            assertFalse(controller.detail(route)!!.loadingAll)
            assertEquals(MusicError.SlowNetwork, controller.detail(route)!!.error)
            assertEquals(1, controller.detail(route)!!.tracks.size)
        } finally { scope.cancel() }
    }
    @Test fun 顺序列表循环单曲循环随机四种模式完整轮转() {
        var mode = Player.REPEAT_MODE_OFF to false
        mode = nextPlaybackMode(mode.first, mode.second)
        assertEquals(Player.REPEAT_MODE_ALL to false, mode)
        mode = nextPlaybackMode(mode.first, mode.second)
        assertEquals(Player.REPEAT_MODE_ONE to false, mode)
        mode = nextPlaybackMode(mode.first, mode.second)
        assertEquals(Player.REPEAT_MODE_ALL to true, mode)
        assertEquals(Player.REPEAT_MODE_OFF to false, nextPlaybackMode(mode.first, mode.second))
    }
    @Test fun 集合播放保留完整队列而不是被首曲预检阻挡() {
        val items = tracks(0, 201)
        val request = PlaybackRequest.collection(items)
        assertEquals(201, request.queue.size)
        assertFalse(request.resolveBeforeLoad)
        assertTrue(PlaybackRequest.create(items.first(), items).resolveBeforeLoad)
        assertEquals(items.map { it.id }, request.queue.map { it.track.id })
    }
    private val collection = MusicCollection("test-list", "测试歌单", CollectionKind.PLAYLIST)
    private fun tracks(start: Int, count: Int) = (start until start + count).map { Track("hash-$it", "歌曲 $it", "歌手") }
    private fun identity(id: String = "1", token: String = "fixture") = SessionData(token, id, "fixture", 1)

    @Test fun 集合路由按种类和账号区分且可以安全保存到栈() {
        assertTrue(isCollectionRoute(collection.route))
        assertNotEquals(collection.route, collection.copy(kind = CollectionKind.ALBUM).route)
        assertNotEquals(collection.copy(ownerId = "1").route, collection.copy(ownerId = "2").route)
        assertFalse(isCollectionRoute("collection/../../account"))
        assertFalse(isCollectionRoute("collection/"))
    }

    @Test fun 全部播放先读完实际分页再提交完整队列() = runBlocking {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val requested = mutableListOf<Int>()
            val submitted = mutableListOf<List<Track>>()
            val source = FakeSource { _, page ->
                requested += page
                CatalogPage(tracks((page - 1) * 30, 30), 90, page < 3)
            }
            val controller = BrowseCoordinator(source, MemoryStorage(), MutableStateFlow(null), testScope) { items, _ -> submitted += items }
            val route = controller.open(collection)
            controller.visible(true); controller.activate(route)
            assertEquals(30, controller.detail(route)!!.tracks.size)
            controller.performAll(route, CollectionAction.PLAY)
            assertEquals(listOf(1, 2, 3), requested)
            assertEquals(90, submitted.single().size)
            assertEquals(90, controller.detail(route)!!.tracks.size)
            assertFalse(controller.detail(route)!!.hasMore)
        } finally { testScope.cancel() }
    }

    @Test fun 未知总数也按明确下一页一直获取而不截断首屏() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            var submitted = 0
            val controller = BrowseCoordinator(FakeSource { _, page -> CatalogPage(tracks(page * 8, 8), null, page < 4) },
                MemoryStorage(), MutableStateFlow(null), scope) { songs, _ -> submitted = songs.size }
            val route = controller.open(collection)
            controller.visible(true); controller.activate(route)
            controller.performAll(route, CollectionAction.QUEUE)
            assertEquals(32, submitted)
        } finally { scope.cancel() }
    }

    @Test fun 分页失败保留内容且不得提前执行全部播放() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            var failNext = true
            var submissions = 0
            val controller = BrowseCoordinator(FakeSource { _, page ->
                if (page == 2 && failNext) throw MusicFailure(MusicError.NetworkUnavailable)
                CatalogPage(tracks(page * 30, 30), 60, page == 1)
            }, MemoryStorage(), MutableStateFlow(null), scope) { _, _ -> submissions++ }
            val route = controller.open(collection)
            controller.visible(true); controller.activate(route)
            controller.performAll(route, CollectionAction.PLAY)
            assertEquals(0, submissions)
            assertEquals(30, controller.detail(route)!!.tracks.size)
            assertEquals(MusicError.NetworkUnavailable, controller.detail(route)!!.error)
            assertTrue(controller.detail(route)!!.retryAppend)
            failNext = false
            controller.performAll(route, CollectionAction.PLAY)
            assertEquals(1, submissions)
            assertEquals(60, controller.detail(route)!!.tracks.size)
        } finally { scope.cancel() }
    }

    @Test fun 重复分页会停止并明确报错而不是无限获取或伪称完成() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            var submissions = 0
            val controller = BrowseCoordinator(FakeSource { _, _ -> CatalogPage(tracks(0, 30), null, true) },
                MemoryStorage(), MutableStateFlow(null), scope) { _, _ -> submissions++ }
            val route = controller.open(collection)
            controller.visible(true); controller.activate(route)
            controller.performAll(route, CollectionAction.PLAY)
            assertEquals(0, submissions)
            assertNotNull(controller.detail(route)!!.error)
            assertFalse(controller.detail(route)!!.loadingAll)
            assertEquals(30, controller.detail(route)!!.tracks.size)
        } finally { scope.cancel() }
    }

    @Test fun 离页取消完整加载且迟到结果不能加入队列() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val release = CompletableDeferred<Unit>()
            var submissions = 0
            val controller = BrowseCoordinator(FakeSource { _, page ->
                if (page == 2) withContext(NonCancellable) { release.await() }
                CatalogPage(tracks(page * 30, 30), 60, page == 1)
            }, MemoryStorage(), MutableStateFlow(null), scope) { _, _ -> submissions++ }
            val route = controller.open(collection)
            controller.visible(true); controller.activate(route)
            controller.performAll(route, CollectionAction.QUEUE)
            assertTrue(controller.detail(route)!!.loadingAll)
            controller.deactivate(route)
            release.complete(Unit); yield()
            assertEquals(0, submissions)
            assertFalse(controller.detail(route)!!.loadingAll)
            assertEquals(30, controller.detail(route)!!.tracks.size)
        } finally { scope.cancel() }
    }

    @Test fun 空页仍宣称有下一页时停止批量加载并保留已有歌曲() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            var requests = 0
            var submissions = 0
            val controller = BrowseCoordinator(FakeSource { _, page ->
                requests++
                CatalogPage(if (page == 1) tracks(0, 30) else emptyList(), null, true)
            }, MemoryStorage(), MutableStateFlow(null), scope) { _, _ -> submissions++ }
            val route = controller.open(collection)
            controller.visible(true); controller.activate(route)
            controller.performAll(route, CollectionAction.PLAY)
            assertEquals(2, requests)
            assertEquals(0, submissions)
            assertEquals(30, controller.detail(route)!!.tracks.size)
            assertNotNull(controller.detail(route)!!.error)
            assertFalse(controller.detail(route)!!.loadingAll)
        } finally { scope.cancel() }
    }

    @Test fun 换号立即清掉私人云内容并拒绝旧响应() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val account = MutableStateFlow<SessionData?>(identity())
            val storage = MemoryStorage()
            val release = CompletableDeferred<Unit>()
            val controller = BrowseCoordinator(FakeSource { _, _ ->
                withContext(NonCancellable) { release.await() }
                CatalogPage(tracks(0, 30), 30, false)
            }, storage, account, scope) { _, _ -> fail("旧账号不得提交播放") }
            val route = controller.open(collection.copy(kind = CollectionKind.CLOUD, ownerId = "1"))
            controller.visible(true); controller.activate(route)
            account.value = identity("2", "new-fixture")
            release.complete(Unit); yield()
            assertNull(controller.detail(route))
            assertNull(storage.selection(route))
        } finally { scope.cancel() }
    }

    @Test fun 同账号重新授权也不能沿用旧私人列表() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val account = MutableStateFlow<SessionData?>(identity())
            var requests = 0
            val controller = BrowseCoordinator(FakeSource { _, _ -> requests++; CatalogPage(tracks(requests, 1), 1, false) },
                MemoryStorage(), account, scope) { _, _ -> }
            val route = controller.open(collection.copy(kind = CollectionKind.CLOUD, ownerId = "1"))
            controller.visible(true); controller.activate(route)
            account.value = identity(token = "new-token")
            assertEquals(2, requests)
            assertEquals("hash-2", controller.detail(route)!!.tracks.single().hash)
        } finally { scope.cancel() }
    }

    @Test fun 刷新失败的重试不应误跳到下一页() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            var fail = false
            val pages = mutableListOf<Int>()
            val controller = BrowseCoordinator(FakeSource { _, page ->
                pages += page
                if (fail) throw MusicFailure(MusicError.SlowNetwork)
                CatalogPage(tracks(0, 1), 1, false)
            }, MemoryStorage(), MutableStateFlow(null), scope) { _, _ -> }
            val route = controller.open(collection)
            controller.visible(true); controller.activate(route)
            fail = true; controller.loadTracks(route)
            assertFalse(controller.detail(route)!!.retryAppend)
            fail = false; controller.loadTracks(route, controller.detail(route)!!.retryAppend)
            assertEquals(listOf(1, 1, 1), pages)
        } finally { scope.cancel() }
    }

    @Test fun 退出前台后不主动请求列表和热搜() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val source = FakeSource { _, _ -> fail("隐藏页面不应请求"); CatalogPage(emptyList(), 0, false) }
            val controller = BrowseCoordinator(source, MemoryStorage(), MutableStateFlow(null), scope) { _, _ -> }
            val route = controller.open(collection)
            controller.activate(route); controller.loadTracks(route); controller.loadHotWords()
            assertEquals(0, source.hotRequests)
            val daily = controller.detail(controller.daily())!!.collection
            assertTrue(daily.ownerId.isEmpty())
            assertFalse(daily.privateContent)
            assertFalse(daily.canPin)
        } finally { scope.cancel() }
    }

    @Test fun 本曲结束定时覆盖自动切歌和单曲循环但排除手动选择() {
        assertTrue(naturalTrackTransition(Player.MEDIA_ITEM_TRANSITION_REASON_AUTO))
        assertTrue(naturalTrackTransition(Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT))
        assertFalse(naturalTrackTransition(Player.MEDIA_ITEM_TRANSITION_REASON_SEEK))
        assertFalse(naturalTrackTransition(Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED))
    }
}

private class FakeSource(private val page: suspend (MusicCollection, Int) -> CatalogPage<Track>) : BrowseSource {
    var hotRequests = 0
    override suspend fun tracks(collection: MusicCollection, page: Int) = this.page(collection, page)
    override suspend fun collections(feed: CollectionFeed, page: Int) = CatalogPage<MusicCollection>(emptyList(), 0, false)
    override suspend fun hotWords(): List<String> { hotRequests++; return listOf("热搜样本") }
}
private class MemoryStorage : BrowseStorage {
    private val selections = linkedMapOf<String, MusicCollection>()
    override val pinned = MutableStateFlow<List<MusicCollection>>(emptyList())
    override fun remember(collection: MusicCollection) { selections[collection.route] = collection }
    override fun selection(route: String) = selections[route]
    override fun togglePin(collection: MusicCollection) { pinned.value = listOf(collection) }
    override fun clearForeignPrivate(ownerId: String?) {
        selections.entries.removeAll { it.value.privateContent && it.value.ownerId != ownerId }
    }
}
