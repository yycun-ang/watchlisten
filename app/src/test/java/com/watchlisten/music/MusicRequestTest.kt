package com.watchlisten.music

import com.watchlisten.music.data.catalogStatusError
import com.watchlisten.music.domain.*
import com.watchlisten.music.model.*
import com.watchlisten.music.playback.PlaybackSnapshot
import org.junit.Assert.*
import org.junit.Test

class MusicRequestTest {
    private fun track(number: Int) = Track("hash$number", "歌曲$number", "歌手")

    @Test fun 准备中的新请求优先于旧播放器错误() {
        val request = PlaybackRequest.create(track(2), listOf(track(2)))
        val retry = MusicState(playRequest = request).retryRequest(PlaybackSnapshot(
            queue = listOf(PlaybackEntry(track(1))), error = MusicError.PlaybackFailure))!!
        assertEquals(track(2), retry.track)
        assertEquals(request.queue, retry.queue)
    }

    @Test fun 旧歌仍在播放时重试失败的新点歌及其队列() {
        val old = PlaybackEntry(track(1))
        val request = PlaybackRequest.create(track(3), listOf(track(2), track(3), track(4)))
            .copy(failed = MusicError.MobileDataBlocked)
        val retry = MusicState(playRequest = request).retryRequest(
            PlaybackSnapshot(queue = listOf(old), positionMs = 89000))!!
        assertEquals(track(3), retry.track)
        assertEquals(request.queue, retry.queue)
        assertEquals(1, retry.index)
        assertEquals(0, retry.positionMs)
        assertNull(retry.failed)
    }

    @Test fun 没有失败点歌时重试当前实例且保留进度() {
        val queue = listOf(PlaybackEntry(track(1)), PlaybackEntry(track(2)))
        val retry = MusicState().retryRequest(PlaybackSnapshot(queue = queue, index = 1, positionMs = 42000))!!
        assertSame(queue, retry.queue)
        assertEquals(track(2), retry.track)
        assertEquals(42000, retry.positionMs)
        assertNull(MusicState().retryRequest(PlaybackSnapshot()))
    }

    @Test fun 点播超过150项列表的末尾时保留完整队列和原始位置() {
        val tracks = (0..200).map(::track)
        val request = PlaybackRequest.create(tracks.last(), tracks)
        assertEquals(tracks, request.queue.map { it.track })
        assertEquals(tracks.lastIndex, request.index)
        assertEquals(tracks.last(), request.queue[request.index].track)
    }

    @Test fun 目标不在列表时补到首位且不删除原列表歌曲() {
        val tracks = (0..200).map(::track)
        val target = track(999)
        val request = PlaybackRequest.create(target, tracks)
        assertEquals(listOf(target) + tracks, request.queue.map { it.track })
        assertEquals(0, request.index)
        assertEquals(target, request.queue[request.index].track)
    }

    @Test fun 空列表点播只建立目标歌曲() {
        val target = track(7)
        val request = PlaybackRequest.create(target, emptyList())
        assertEquals(target, request.queue.single().track)
        assertEquals(0, request.index)
    }

    @Test fun 建队列保留重复歌曲及列表原顺序() {
        val tracks = listOf(track(1), track(2), track(1))
        val request = PlaybackRequest.create(track(2), tracks)
        assertEquals(tracks, request.queue.map { it.track })
        assertEquals(1, request.index)
        assertEquals(3, request.queue.map { it.instanceId }.distinct().size)
    }

    @Test fun 空页及重复页终止翻页且保留已有结果() {
        val current = MusicState(tracks = (1..20).map(::track), hasMore = true)
        val empty = current.withSearchPage(SearchPage(emptyList(), 100), 2, true)
        val repeated = current.withSearchPage(SearchPage(current.tracks, 100), 2, true)
        assertFalse(empty.hasMore)
        assertFalse(repeated.hasMore)
        assertEquals(current.tracks, empty.tracks)
        assertEquals(current.tracks, repeated.tracks)
    }

    @Test fun 达到服务端总量或分页范围终点时终止翻页() {
        assertFalse(MusicState().withSearchPage(SearchPage((1..20).map(::track), 20), 1, false).hasMore)
        val deduplicated = MusicState(tracks = (1..15).map(::track))
            .withSearchPage(SearchPage((10..25).map(::track), 40), 2, true)
        assertEquals((1..25).map(::track), deduplicated.tracks)
        assertFalse(deduplicated.hasMore)
    }

    @Test fun 超过150条搜索结果仍完整保留且能继续手动翻页() {
        val current = MusicState(query = "周杰伦", tracks = (1..140).map(::track), page = 7, hasMore = true)
        val appended = current.withSearchPage(SearchPage((141..160).map(::track), 1000), 8, true)
        assertEquals((1..160).map(::track), appended.tracks)
        assertEquals(8, appended.page)
        assertTrue(appended.hasMore)
        assertTrue(appended.canLoadNextPage("周杰伦"))
    }

    @Test fun 翻页只允许相同查询且没有正在搜索() {
        val current = MusicState(query = "周杰伦", hasMore = true)
        assertTrue(current.canLoadNextPage("周杰伦"))
        assertFalse(current.canLoadNextPage("陈奕迅"))
        assertFalse(current.copy(searching = true).canLoadNextPage("周杰伦"))
        assertFalse(current.copy(hasMore = false).canLoadNextPage("周杰伦"))
    }

    @Test fun 正常新页保持可继续翻页() {
        val page = MusicState().withSearchPage(SearchPage((1..20).map(::track), 80), 1, false)
        assertTrue(page.hasMore)
        assertEquals(20, page.tracks.size)
    }

    @Test fun 未知服务错误保留操作含义() {
        val search = MusicError.Unknown("搜索失败，请稍后重试")
        val login = MusicError.Unknown("暂时无法检查扫码结果，请重试")
        assertEquals(search, catalogStatusError(-1, search))
        assertEquals(login, catalogStatusError(-1, login))
        assertEquals(MusicError.AuthenticationExpired, catalogStatusError(20018, search))
    }
}
