package com.watchlisten.music

import com.watchlisten.music.data.DiscoverySource
import com.watchlisten.music.domain.DiscoveryCoordinator
import com.watchlisten.music.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class DiscoveryCoordinatorTest {
    @Test fun 隐藏页只保存搜索意图进入前台才请求且再次激活不重复请求() = runBlocking {
        withController { controller, source, _ ->
            controller.search("  虚构歌手  ", DiscoveryKind.ARTISTS)
            controller.activate("catalog-search")
            assertTrue(source.searches.isEmpty())
            assertEquals("虚构歌手", controller.state.value.search.query)
            controller.visible(true)
            assertEquals(listOf(SearchCall("虚构歌手", DiscoveryKind.ARTISTS, 1)), source.searches)
            assertTrue(controller.state.value.search.loaded)
            controller.activate("catalog-search")
            assertEquals(1, source.searches.size)
            controller.visible(false)
            controller.search("新的专辑", DiscoveryKind.ALBUMS)
            assertEquals(1, source.searches.size)
            controller.visible(true)
            assertEquals(SearchCall("新的专辑", DiscoveryKind.ALBUMS, 1), source.searches.last())
        }
    }

    @Test fun 关键词限制长度且清空后不再沿用上次结果() = runBlocking {
        withController { controller, source, _ ->
            controller.visible(true); controller.activate("catalog-search")
            controller.search("  ${"曲".repeat(130)}  ", DiscoveryKind.ARTISTS)
            assertEquals("曲".repeat(100), source.searches.single().query)
            controller.search("   ", DiscoveryKind.ALBUMS)
            val state = controller.state.value.search
            assertEquals("", state.query)
            assertEquals(DiscoveryKind.ALBUMS, state.kind)
            assertFalse(state.loaded)
            assertFalse(state.loading)
            assertTrue(state.items.isEmpty())
            assertEquals(1, source.searches.size)
        }
    }

    @Test fun 分类切换重置分页且同分类追加按稳定键去重() = runBlocking {
        withController { controller, source, _ ->
            source.onSearch = { _, kind, page ->
                if (kind == DiscoveryKind.ALBUMS) CatalogPage(listOf(albumItem(9)), 1, false)
                else if (page == 1) artistPage(1, 2, hasMore = true)
                else artistPage(2, 3)
            }
            controller.visible(true); controller.activate("catalog-search")
            controller.search("测试", DiscoveryKind.ARTISTS)
            controller.search("测试", DiscoveryKind.ARTISTS, append = true)
            assertEquals(listOf("artist/1", "artist/2", "artist/3"), controller.state.value.search.items.map { it.key })
            assertEquals(2, controller.state.value.search.page)
            controller.search("测试", DiscoveryKind.ALBUMS)
            assertEquals(listOf(albumItem(9)), controller.state.value.search.items)
            assertEquals(1, controller.state.value.search.page)
            assertEquals(1, controller.state.value.search.total)
            assertFalse(controller.state.value.search.hasMore)
            assertEquals(listOf(1, 2, 1), source.searches.map { it.page })
        }
    }

    @Test fun 失败的下一页保留已加载内容且重试仍请求同一页() = runBlocking {
        withController { controller, source, _ ->
            var failNext = true
            source.onSearch = { _, _, page ->
                if (page == 2 && failNext) throw MusicFailure(MusicError.NetworkUnavailable)
                artistPage(page.toLong(), hasMore = page == 1)
            }
            controller.visible(true); controller.activate("catalog-search")
            controller.search("测试", DiscoveryKind.ARTISTS)
            controller.search("测试", append = true)
            assertEquals(1, controller.state.value.search.page)
            assertEquals(listOf("artist/1"), controller.state.value.search.items.map { it.key })
            assertEquals(MusicError.NetworkUnavailable, controller.state.value.search.error)
            assertTrue(controller.state.value.search.retryAppend)
            assertFalse(controller.state.value.search.loading)
            failNext = false
            controller.retrySearch()
            assertEquals(listOf(1, 2, 2), source.searches.map { it.page })
            assertEquals(listOf("artist/1", "artist/2"), controller.state.value.search.items.map { it.key })
            assertNull(controller.state.value.search.error)
            assertFalse(controller.state.value.search.retryAppend)
        }
    }

    @Test fun 服务端重复页不能被当作成功推进页数() = runBlocking {
        withController { controller, source, _ ->
            source.onSearch = { _, _, _ -> artistPage(1, hasMore = true) }
            controller.visible(true); controller.activate("catalog-search")
            controller.search("测试", DiscoveryKind.ARTISTS)
            controller.search("测试", append = true)
            val state = controller.state.value.search
            assertEquals(1, state.page)
            assertEquals(1, state.items.size)
            assertTrue(state.error is MusicError.Unknown)
            assertTrue(state.retryAppend)
            assertFalse(state.loading)
        }
    }

    @Test fun 空页却声称还有下一页会显示响应异常() = runBlocking {
        withController { controller, source, _ ->
            source.onSearch = { _, _, _ -> CatalogPage(emptyList(), 20, true, rawCount = 0) }
            controller.visible(true); controller.activate("catalog-search")
            controller.search("测试", DiscoveryKind.ARTISTS)
            assertEquals(MusicError.InvalidResponse, controller.state.value.search.error)
            assertEquals(0, controller.state.value.search.page)
            assertFalse(controller.state.value.search.loading)
        }
    }

    @Test fun 快速换词后不响应取消的旧成功结果也不能覆盖新结果() = runBlocking {
        withController { controller, source, _ ->
            val release = CompletableDeferred<Unit>()
            source.onSearch = { query, _, _ ->
                if (query == "旧查询") withContext(NonCancellable) { release.await(); artistPage(1) }
                else artistPage(2)
            }
            controller.visible(true); controller.activate("catalog-search")
            controller.search("旧查询", DiscoveryKind.ARTISTS)
            assertTrue(controller.state.value.search.loading)
            controller.search("新查询", DiscoveryKind.ARTISTS)
            release.complete(Unit); yield()
            assertEquals("新查询", controller.state.value.search.query)
            assertEquals(listOf("artist/2"), controller.state.value.search.items.map { it.key })
            assertNull(controller.state.value.search.error)
        }
    }

    @Test fun 快速换词后旧请求迟到的普通错误不能污染新搜索() = runBlocking {
        withController { controller, source, _ ->
            val release = CompletableDeferred<Unit>()
            source.onSearch = { query, _, _ ->
                if (query == "旧查询") withContext(NonCancellable) {
                    release.await()
                    throw MusicFailure(MusicError.NetworkUnavailable)
                } else artistPage(2)
            }
            controller.visible(true); controller.activate("catalog-search")
            controller.search("旧查询", DiscoveryKind.ARTISTS)
            controller.search("新查询", DiscoveryKind.ARTISTS)
            release.complete(Unit); yield()
            assertEquals("新查询", controller.state.value.search.query)
            assertNull(controller.state.value.search.error)
            assertEquals(listOf("artist/2"), controller.state.value.search.items.map { it.key })
            assertTrue(controller.state.value.search.loaded)
        }
    }

    @Test fun 离页立即停止加载且取消后的迟到错误不能改变缓存() = runBlocking {
        withController { controller, source, _ ->
            val release = CompletableDeferred<Unit>()
            source.onSearch = { _, _, _ -> withContext(NonCancellable) {
                release.await(); throw MusicFailure(MusicError.InvalidResponse)
            } }
            controller.visible(true); controller.activate("catalog-search")
            controller.search("测试", DiscoveryKind.ARTISTS)
            controller.deactivate("catalog-search")
            assertFalse(controller.state.value.search.loading)
            release.complete(Unit); yield()
            assertNull(controller.state.value.search.error)
            assertFalse(controller.state.value.search.loaded)
            controller.search("隐藏查询", DiscoveryKind.ALBUMS)
            assertEquals(1, source.searches.size)
        }
    }

    @Test fun 旧歌手资料歌曲和专辑的迟到错误不会污染新歌手或旧缓存() = runBlocking {
        withController { controller, source, _ ->
            val release = CompletableDeferred<Unit>()
            suspend fun waitForOldRequest(): Nothing = withContext(NonCancellable) {
                release.await(); throw MusicFailure(MusicError.NetworkUnavailable)
            }
            source.onArtist = { id -> if (id == 1L) waitForOldRequest() else MusicArtist(id, "新歌手") }
            source.onSongs = { id, _ -> if (id == 1L) waitForOldRequest() else trackPage(2) }
            source.onAlbums = { id, _ -> if (id == 1L) waitForOldRequest() else albumPage(2) }
            controller.visible(true); controller.activate("artist/1"); controller.loadArtistAlbums()
            controller.activate("artist/2")
            release.complete(Unit); yield()
            val old = controller.state.value.artists.getValue(1)
            assertFalse(old.profileLoading || old.songsLoading || old.albumsLoading)
            assertNull(old.profileError); assertNull(old.songsError); assertNull(old.albumsError)
            assertEquals(2L, controller.state.value.artist?.id)
            assertEquals(listOf(track(2)), controller.state.value.artist?.songs)
            assertNull(controller.state.value.artist?.songsError)
        }
    }

    @Test fun 同账号令牌变更和换账号均清空分类结果与歌手缓存但保留搜索意图() = runBlocking {
        withController { controller, _, sessions ->
            for (next in listOf(identity("1", "fixture-new"), identity("2", "fixture-other"))) {
                controller.visible(true); controller.activate("catalog-search")
                controller.search("保留关键词", DiscoveryKind.ARTISTS)
                controller.activate("artist/1")
                assertTrue(controller.state.value.artists.isNotEmpty())
                controller.visible(false)
                sessions.value = next
                yield()
                assertTrue(controller.state.value.search.items.isEmpty())
                assertFalse(controller.state.value.search.loaded)
                assertEquals("保留关键词", controller.state.value.search.query)
                assertEquals(DiscoveryKind.ARTISTS, controller.state.value.search.kind)
                assertTrue(controller.state.value.artists.isEmpty())
            }
        }
    }

    @Test fun 换号后不协作的旧歌手响应不能恢复旧账号缓存() = runBlocking {
        withController { controller, source, sessions ->
            val release = CompletableDeferred<Unit>()
            source.onArtist = { id -> withContext(NonCancellable) { release.await(); MusicArtist(id, "旧歌手") } }
            source.onSongs = { _, _ -> withContext(NonCancellable) { release.await(); trackPage(1) } }
            controller.visible(true); controller.activate("artist/1")
            controller.visible(false)
            sessions.value = identity("2", "fixture-next")
            release.complete(Unit); yield()
            assertTrue(controller.state.value.artists.isEmpty())
            assertNull(controller.state.value.activeArtistId)
        }
    }

    @Test fun 歌手资料错误不会阻断歌曲且专辑只在用户请求后加载() = runBlocking {
        withController { controller, source, _ ->
            source.onArtist = { MusicArtist(999, "不匹配的资料") }
            val preview = MusicArtist(1, "搜索页预览")
            val route = controller.openArtist(preview)
            controller.visible(true); controller.activate(route)
            assertEquals(MusicError.InvalidResponse, controller.state.value.artist?.profileError)
            assertEquals(preview, controller.state.value.artist?.profile)
            assertFalse(controller.state.value.artist!!.profileLoaded)
            assertEquals(listOf(track(1)), controller.state.value.artist?.songs)
            assertTrue(source.albumPages.isEmpty())
            controller.loadArtistAlbums()
            assertEquals(listOf(1L to 1), source.albumPages)
            assertTrue(controller.state.value.artist!!.albumsLoaded)
            controller.activate(route)
            assertEquals(1, source.albumPages.size)
        }
    }

    @Test fun 歌手歌曲下一页错误保留歌曲并支持原页重试() = runBlocking {
        withController { controller, source, _ ->
            var failNext = true
            source.onSongs = { _, page ->
                if (page == 2 && failNext) throw MusicFailure(MusicError.NetworkUnavailable)
                trackPage(page, hasMore = page == 1)
            }
            controller.visible(true); controller.activate("artist/1")
            controller.loadArtistSongs(append = true)
            assertEquals(listOf(track(1)), controller.state.value.artist?.songs)
            assertTrue(controller.state.value.artist!!.songsRetryAppend)
            assertEquals(1, controller.state.value.artist?.songsPage)
            failNext = false
            controller.loadArtistSongs(append = controller.state.value.artist!!.songsRetryAppend)
            assertEquals(listOf(1L to 1, 1L to 2, 1L to 2), source.songPages)
            assertEquals(listOf(track(1), track(2)), controller.state.value.artist?.songs)
            assertNull(controller.state.value.artist?.songsError)
            assertFalse(controller.state.value.artist!!.songsRetryAppend)
        }
    }

    @Test fun 专辑分页重试和歌手刷新会更新三种数据且保留按需加载约束() = runBlocking {
        withController { controller, source, _ ->
            var failNext = true
            var revision = 0
            source.onArtist = { MusicArtist(it, "歌手版本$revision") }
            source.onSongs = { _, _ -> trackPage(10 + revision) }
            source.onAlbums = { _, page ->
                if (page == 2 && failNext) throw MusicFailure(MusicError.NetworkUnavailable)
                albumPage(page + revision * 10, hasMore = page == 1)
            }
            controller.visible(true); controller.activate("artist/1")
            controller.refreshArtist()
            assertTrue(source.albumPages.isEmpty())
            controller.loadArtistAlbums(); controller.loadArtistAlbums(append = true)
            assertEquals(1, controller.state.value.artist?.albumsPage)
            assertTrue(controller.state.value.artist!!.albumsRetryAppend)
            failNext = false
            controller.loadArtistAlbums(append = controller.state.value.artist!!.albumsRetryAppend)
            assertEquals(listOf(1L to 1, 1L to 2, 1L to 2), source.albumPages)
            assertEquals(listOf(album(1), album(2)), controller.state.value.artist?.albums)
            revision = 1
            controller.refreshArtist()
            assertEquals("歌手版本1", controller.state.value.artist?.profile?.name)
            assertEquals(listOf(track(11)), controller.state.value.artist?.songs)
            assertEquals(listOf(album(11)), controller.state.value.artist?.albums)
            assertEquals(1, controller.state.value.artist?.albumsPage)
            assertNull(controller.state.value.artist?.albumsError)
        }
    }

    @Test fun 真正的请求超时应结束加载并显示可重试网络错误() = runBlocking {
        withController { controller, source, _ ->
            source.onSearch = { _, _, _ -> withTimeout(1) { awaitCancellation() } }
            controller.visible(true); controller.activate("catalog-search")
            controller.search("超时测试", DiscoveryKind.ARTISTS)
            val state = withTimeout(1_000) { controller.state.first { !it.search.loading } }.search
            assertEquals(MusicError.SlowNetwork, state.error)
            assertFalse(state.loaded)
            source.onSearch = { _, _, _ -> artistPage(3) }
            controller.retrySearch()
            assertEquals(listOf("artist/3"), controller.state.value.search.items.map { it.key })
            assertNull(controller.state.value.search.error)
        }
    }

    @Test fun 刷新歌手会重试用户已经打开但首次加载失败的专辑页() = runBlocking {
        withController { controller, source, _ ->
            var unavailable = true
            source.onAlbums = { _, _ ->
                if (unavailable) throw MusicFailure(MusicError.NetworkUnavailable)
                albumPage(8)
            }
            controller.visible(true); controller.activate("artist/1")
            controller.loadArtistAlbums()
            assertFalse(controller.state.value.artist!!.albumsLoaded)
            assertTrue(controller.state.value.artist!!.albumsRequested)
            assertEquals(MusicError.NetworkUnavailable, controller.state.value.artist?.albumsError)
            unavailable = false
            controller.refreshArtist()
            assertEquals(listOf(1L to 1, 1L to 1), source.albumPages)
            assertEquals(listOf(album(8)), controller.state.value.artist?.albums)
            assertTrue(controller.state.value.artist!!.albumsLoaded)
            assertNull(controller.state.value.artist?.albumsError)
        }
    }

    @Test fun 歌手路由只接受范围内正整数且非法路由不触发网络() = runBlocking {
        assertEquals(Long.MAX_VALUE, artistIdFromRoute("artist/${Long.MAX_VALUE}"))
        for (route in listOf("artist/0", "artist/-1", "artist/01", "artist/1/", "artist/../account",
            "artist/9223372036854775808", "artist/1?token=fixture", "Artist/1")) {
            assertNull(route, artistIdFromRoute(route))
            assertFalse(isArtistRoute(route))
        }
        withController { controller, source, _ ->
            controller.visible(true); controller.activate("artist/0")
            assertTrue(source.artistIds.isEmpty())
            assertTrue(source.songPages.isEmpty())
            assertTrue(source.albumPages.isEmpty())
        }
    }

    private suspend fun withController(block: suspend (DiscoveryCoordinator, FakeSource, MutableStateFlow<SessionData?>) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val source = FakeSource()
            val sessions = MutableStateFlow<SessionData?>(identity())
            block(DiscoveryCoordinator(source, sessions, scope), source, sessions)
        } finally { scope.cancel() }
    }

    private data class SearchCall(val query: String, val kind: DiscoveryKind, val page: Int)

    private class FakeSource : DiscoverySource {
        val searches = mutableListOf<SearchCall>()
        val artistIds = mutableListOf<Long>()
        val songPages = mutableListOf<Pair<Long, Int>>()
        val albumPages = mutableListOf<Pair<Long, Int>>()
        var onSearch: suspend (String, DiscoveryKind, Int) -> CatalogPage<DiscoveryItem> = { _, _, _ -> artistPage(1) }
        var onArtist: suspend (Long) -> MusicArtist = { MusicArtist(it, "虚构歌手$it") }
        var onSongs: suspend (Long, Int) -> CatalogPage<Track> = { _, _ -> trackPage(1) }
        var onAlbums: suspend (Long, Int) -> CatalogPage<MusicCollection> = { _, _ -> albumPage(1) }
        override suspend fun search(query: String, kind: DiscoveryKind, page: Int): CatalogPage<DiscoveryItem> {
            searches += SearchCall(query, kind, page)
            return onSearch(query, kind, page)
        }
        override suspend fun artist(id: Long): MusicArtist { artistIds += id; return onArtist(id) }
        override suspend fun artistSongs(id: Long, page: Int): CatalogPage<Track> {
            songPages += id to page; return onSongs(id, page)
        }
        override suspend fun artistAlbums(id: Long, page: Int): CatalogPage<MusicCollection> {
            albumPages += id to page; return onAlbums(id, page)
        }
    }

    companion object {
        private fun identity(id: String = "1", token: String = "fixture") = SessionData(token, id, "fixture", 1)
        private fun artistPage(vararg ids: Long, hasMore: Boolean = false): CatalogPage<DiscoveryItem> =
            CatalogPage(ids.map { DiscoveryItem.Artist(MusicArtist(it, "虚构歌手$it")) }, 10, hasMore)
        private fun track(id: Int) = Track("fixture-$id", "虚构歌曲$id", "虚构歌手")
        private fun trackPage(id: Int, hasMore: Boolean = false) = CatalogPage(listOf(track(id)), 10, hasMore)
        private fun album(id: Int) = MusicCollection("$id", "虚构专辑$id", CollectionKind.ALBUM)
        private fun albumPage(id: Int, hasMore: Boolean = false) = CatalogPage(listOf(album(id)), 10, hasMore)
        private fun albumItem(id: Int) = DiscoveryItem.Collection(album(id))
    }
}
