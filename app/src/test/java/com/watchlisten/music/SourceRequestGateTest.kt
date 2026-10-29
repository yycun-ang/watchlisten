package com.watchlisten.music

import com.watchlisten.music.data.SourceRequestGate
import com.watchlisten.music.model.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class SourceRequestGateTest {
    @Test fun 新授权开始时清除过期的完整播放标记() = runBlocking {
        supervisorScope {
            var time = 0L
            var calls = 0
            val release = CompletableDeferred<Unit>()
            val gate = SourceRequestGate(this, { true }, {
                if (++calls > 1) release.await()
                PlayableSource("https://test.kugou.com/audio", "mp3", time + 1000,
                    access = ListeningAccess.FULL)
            }, { time })
            gate.source(track)
            assertEquals(ListeningAccess.FULL, gate.access.value[track.id])
            time = 1001
            val renewed = async(start = CoroutineStart.UNDISPATCHED) { gate.source(track) }
            yield()
            assertNull(gate.access.value[track.id])
            release.complete(Unit)
            renewed.await()
            assertEquals(ListeningAccess.FULL, gate.access.value[track.id])
            gate.invalidate()
            assertTrue(gate.access.value.isEmpty())
        }
    }

    @Test fun 无权限结果会展示但不阻止再次正式请求() = runBlocking {
        supervisorScope {
            var calls = 0
            val gate = SourceRequestGate(this, { true }, {
                if (++calls == 1) throw MusicFailure(MusicError.PermissionDenied)
                PlayableSource("https://test.kugou.com/audio", "mp3", 1000, preview = true)
            }, { 0 })
            runCatching { gate.source(track) }
            assertEquals(ListeningAccess.DENIED, gate.access.value[track.id])
            assertEquals(ListeningAccess.PREVIEW, gate.source(track).access)
            assertEquals(2, calls)
        }
    }
    private val track = Track("test", "测试歌曲", "歌手")
    private fun source(preview: Boolean = false) = PlayableSource("https://test.kugou.com/song", "mp3", 1000, preview)

    @Test fun 失败请求不会污染下一次重试() = runBlocking {
        supervisorScope {
            var requests = 0
            val gate = SourceRequestGate(this, { true }, {
                if (++requests == 1) throw MusicFailure(MusicError.NetworkUnavailable)
                source()
            }, { 0 })
            try {
                gate.source(track)
                fail("第一次取源应失败")
            } catch (error: MusicFailure) {
                assertEquals(MusicError.NetworkUnavailable, error.error)
            }
            assertEquals(source(), gate.source(track))
            assertEquals(2, requests)
        }
    }

    @Test fun 同一音源的并发请求只执行一次且缓存预览属性() = runBlocking {
        supervisorScope {
            val release = CompletableDeferred<Unit>()
            var requests = 0
            val gate = SourceRequestGate(this, { true }, { requests++; release.await(); source(true) }, { 0 })
            val first = async(start = CoroutineStart.UNDISPATCHED) { gate.source(track) }
            val second = async(start = CoroutineStart.UNDISPATCHED) { gate.source(track) }
            yield()
            assertEquals(1, requests)
            release.complete(Unit)
            assertEquals(first.await(), second.await())
            assertTrue(gate.source(track).preview)
            assertEquals(true, gate.previews.value[track.id])
            assertEquals(1, requests)
        }
    }

    @Test fun 一个等待者取消不影响另一个播放请求() = runBlocking {
        supervisorScope {
            val release = CompletableDeferred<Unit>()
            var requests = 0
            val gate = SourceRequestGate(this, { true }, { requests++; release.await(); source() }, { 0 })
            val prefetch = async(start = CoroutineStart.UNDISPATCHED) { gate.source(track) }
            val playback = async(start = CoroutineStart.UNDISPATCHED) { gate.source(track) }
            prefetch.cancelAndJoin()
            release.complete(Unit)
            assertEquals(source(), playback.await())
            assertEquals(1, requests)
        }
    }

    @Test fun 所有等待者取消会中止取源且不会写回缓存() = runBlocking {
        supervisorScope {
            val started = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            val gate = SourceRequestGate(this, { true }, {
                started.complete(Unit)
                try { awaitCancellation() }
                finally { cancelled.complete(Unit) }
            }, { 0 })
            val request = async { gate.source(track) }
            withTimeout(2000) { started.await() }
            request.cancelAndJoin()
            withTimeout(2000) { cancelled.await() }
            assertTrue(gate.previews.value.isEmpty())
        }
    }

    @Test fun 失效时迟到的响应不能覆盖新的缓存() = runBlocking {
        supervisorScope {
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var requests = 0
            val gate = SourceRequestGate(this, { true }, {
                if (++requests == 1) {
                    started.complete(Unit)
                    withContext(NonCancellable) { release.await() }
                    source(true)
                } else source(false)
            }, { 0 })
            val stale = async { gate.source(track) }
            withTimeout(2000) { started.await() }
            gate.invalidate(track.id)
            assertFalse(gate.source(track).preview)
            release.complete(Unit)
            stale.join()
            assertTrue(stale.isCancelled)
            assertFalse(gate.source(track).preview)
            assertEquals(false, gate.previews.value[track.id])
            assertEquals(2, requests)
        }
    }

    @Test fun 移动网络受限时可读有效缓存但不发新请求() = runBlocking {
        supervisorScope {
            var allowed = true
            var now = 0L
            var requests = 0
            val gate = SourceRequestGate(this, { allowed }, { requests++; source() }, { now })
            gate.source(track)
            allowed = false
            assertEquals(source(), gate.source(track))
            now = 1000
            try {
                gate.source(track)
                fail("过期缓存不能绕过移动网络限制")
            } catch (error: MusicFailure) { assertEquals(MusicError.MobileDataBlocked, error.error) }
            assertEquals(1, requests)
        }
    }
}
