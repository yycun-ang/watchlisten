package com.watchlisten.music.dlna

import java.io.IOException
import java.net.BindException
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DlnaRecoveryTest {
    @Test fun 优先复用原端口而不是重新随机分配() {
        val attempts = mutableListOf<Int>()
        val selected = bindDlnaPort(43_215) { attempts += it; it }
        assertEquals(43_215, selected)
        assertEquals(listOf(43_215), attempts)
    }

    @Test fun 原端口占用时只回退一次随机端口() {
        val attempts = mutableListOf<Int>()
        val selected = bindDlnaPort(43_215) {
            attempts += it
            if (it != 0) throw BindException("occupied")
            51_234
        }
        assertEquals(51_234, selected)
        assertEquals(listOf(43_215, 0), attempts)
    }

    @Test fun 其他网络错误不能悄悄尝试别的端口() {
        val attempts = mutableListOf<Int>()
        try {
            bindDlnaPort(43_215) { attempts += it; throw IOException("network unavailable") }
            fail("网络错误应交给接收生命周期处理")
        } catch (_: IOException) { assertEquals(listOf(43_215), attempts) }
    }

    @Test fun 随机端口失败不进入反复重新绑定() {
        var attempts = 0
        try {
            bindDlnaPort(0) { attempts++; throw BindException("unavailable") }
            fail("随机端口失败应直接返回")
        } catch (_: BindException) { assertEquals(1, attempts) }
    }

    @Test fun 非法和特权端口在绑定前拒绝() {
        listOf(-1, 1, 1023, 65536).forEach { port ->
            var attempted = false
            try {
                bindDlnaPort(port) { attempted = true; it }
                fail("应拒绝无效端口")
            } catch (_: IllegalArgumentException) { assertFalse(attempted) }
        }
    }

    @Test fun 监听短暂异常有递增退避且达到上限后结束() {
        val retry = DlnaListenerRetry()
        assertEquals(listOf(200L, 400L, 800L, 1600L, 2000L), List(5) { retry.failed() })
        assertNull(retry.failed())
        assertNull(retry.failed())
    }

    @Test fun 监听恢复成功后下一次异常重新从短等待开始() {
        val retry = DlnaListenerRetry()
        retry.failed(); retry.failed(); retry.failed()
        retry.recovered()
        assertEquals(200L, retry.failed())
    }

    @Test fun 事件重试初始立即可用且延时最多三十秒() {
        val retry = DlnaEventRetry()
        assertTrue(retry.ready(-1_000))
        var now = 0L
        listOf(1L, 2L, 4L, 8L, 16L, 30L, 30L, 30L).forEach { seconds ->
            retry.failed(now)
            val expected = now + TimeUnit.SECONDS.toNanos(seconds)
            assertFalse(retry.ready(expected - 1))
            assertTrue(retry.ready(expected))
            now = expected
        }
        assertTrue(retry.hasFailures)
    }

    @Test fun 事件成功后取消退避让下一次状态及时发送() {
        val retry = DlnaEventRetry()
        retry.failed(0)
        retry.failed(1)
        retry.recovered()
        assertFalse(retry.hasFailures)
        assertTrue(retry.ready(2))
        retry.failed(2)
        assertTrue(retry.ready(2 + TimeUnit.SECONDS.toNanos(1)))
    }

    @Test fun 单调时钟接近上限也不会溢出导致疯狂重试() {
        val retry = DlnaEventRetry()
        retry.failed(Long.MAX_VALUE - 10)
        assertFalse(retry.ready(Long.MAX_VALUE - 1))
        assertTrue(retry.ready(Long.MAX_VALUE))
    }

    @Test fun 事件连续失败仍可在租期内续订原SID() {
        var now = 1000L
        val registry = DlnaSubscriptions { now }
        val subscription = registry.subscribe(DlnaService.TRANSPORT, PEER, initial())
        repeat(10) { subscription.retry.failed(TimeUnit.MILLISECONDS.toNanos(now)); now += 1000 }
        assertTrue(registry.contains(subscription))
        val renewed = registry.subscribe(DlnaService.TRANSPORT, PEER, mapOf("sid" to subscription.sid, "timeout" to "Second-60"))
        assertEquals(subscription.sid, renewed.sid)
        assertEquals(now + 60_000L, renewed.expiresAtMs)
        now += 60_000
        assertTrue(registry.current().isEmpty())
    }

    @Test fun 手机新建订阅可以替换自己已失败的旧回调() {
        val registry = DlnaSubscriptions { 1000L }
        val failed = registry.subscribe(DlnaService.TRANSPORT, PEER, initial())
        val otherService = registry.subscribe(DlnaService.RENDERING, PEER, initial())
        val otherPhone = registry.subscribe(DlnaService.TRANSPORT, "192.168.31.9", initial("192.168.31.9"))
        failed.retry.failed(0)
        otherService.retry.failed(0)
        otherPhone.retry.failed(0)
        val replacement = registry.subscribe(DlnaService.TRANSPORT, PEER, initial(port = 4322))
        assertFalse(failed.active.get())
        assertFalse(registry.contains(failed))
        assertTrue(registry.contains(replacement))
        assertTrue(registry.contains(otherService))
        assertTrue(registry.contains(otherPhone))
        assertEquals(3, registry.current().size)
    }

    @Test fun 新订阅不会删掉同手机的健康订阅() {
        val registry = DlnaSubscriptions { 1000L }
        val healthy = registry.subscribe(DlnaService.TRANSPORT, PEER, initial())
        registry.subscribe(DlnaService.TRANSPORT, PEER, initial(port = 4322))
        assertTrue(registry.contains(healthy))
        assertEquals(2, registry.current().size)
    }

    private fun initial(peer: String = PEER, port: Int = 4321) = mapOf(
        "nt" to "upnp:event", "callback" to "<http://$peer:$port/event>", "timeout" to "Second-1800"
    )

    companion object { private const val PEER = "192.168.31.8" }
}
