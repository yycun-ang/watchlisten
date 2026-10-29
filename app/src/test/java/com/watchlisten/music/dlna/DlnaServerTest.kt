package com.watchlisten.music.dlna

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DlnaServerTest {
    private val peer = "192.168.31.8"
    private val service = DlnaService.TRANSPORT
    private fun request(headers: String, body: String = "", method: String = "POST") =
        "$method /avtransport/control HTTP/1.1\r\nHost: 192.168.31.42:12345\r\n$headers\r\n$body"
    private fun read(value: String, onContinue: () -> Unit = {}) =
        readDlnaRequest(ByteArrayInputStream(value.toByteArray(Charsets.UTF_8)), onContinue)
    private fun initial(timeout: String = "Second-1800") = mapOf(
        "nt" to "upnp:event", "callback" to "<http://$peer:4321/event?id=1>", "timeout" to timeout
    )
    private fun fails(status: Int, action: () -> Unit) {
        try { action(); fail("请求应该被拒绝") } catch (failure: DlnaHttpFailure) { assertEquals(status, failure.status) }
    }

    @Test fun 解析固定长度的中文SOAP正文() {
        val body = "<title>手机投放</title>"
        val result = read(request("Content-Length: ${body.toByteArray().size}\r\nSOAPAction: \"urn:schemas-upnp-org:service:AVTransport:1#Play\"\r\n", body))
        assertEquals(body, result.body)
        assertEquals("POST", result.method)
        assertTrue(result.headers.containsKey("soapaction"))
    }

    @Test fun HEAD描述请求不需要正文长度() {
        val result = read("HEAD /device.xml HTTP/1.1\r\nHost: 192.168.31.42\r\n\r\n")
        assertEquals("HEAD", result.method)
        assertEquals("", result.body)
    }

    @Test fun 重复长度和分块编码不能混淆请求边界() {
        fails(400) { read(request("Content-Length: 0\r\ncontent-length: 1\r\n")) }
        fails(400) { read(request("Content-Length: 0\r\nTransfer-Encoding: chunked\r\n")) }
        fails(400) { read(request("Content-Length: -1\r\n")) }
        fails(400) { read(request("Content-Length: +1\r\n", "x")) }
    }

    @Test fun 正文和头部都有严格上限() {
        fails(413) { read(request("Content-Length: 262145\r\n")) }
        fails(431) { read(request("X-Long: ${"a".repeat(33000)}\r\nContent-Length: 0\r\n")) }
        fails(411) { read(request("")) }
    }

    @Test fun 长度不足与非法UTF8正文会被拒绝() {
        fails(400) { read(request("Content-Length: 10\r\n", "abc")) }
        val bytes = request("Content-Length: 2\r\n").toByteArray() + byteArrayOf(0xc3.toByte(), 0x28)
        fails(400) { readDlnaRequest(ByteArrayInputStream(bytes)) }
    }

    @Test fun 请求行和头部格式必须明确() {
        fails(400) { read("GET http://example.com/device.xml HTTP/1.1\r\nHost: example.com\r\n\r\n") }
        fails(400) { read("GET /device.xml HTTP/1.1\r\n\r\n") }
        fails(400) { read("GET /device.xml HTTP/1.1\nHost: test\n\n") }
        fails(400) { read(request(" Folded: value\r\nContent-Length: 0\r\n")) }
    }

    @Test fun 只在验证正文上限后发送继续响应() {
        var continued = false
        val result = read(request("Content-Length: 3\r\nExpect: 100-continue\r\n", "abc")) { continued = true }
        assertTrue(continued)
        assertEquals("abc", result.body)
        continued = false
        fails(413) { read(request("Content-Length: 262145\r\nExpect: 100-continue\r\n")) { continued = true } }
        assertFalse(continued)
        fails(417) { read(request("Content-Length: 1\r\nExpect: unsupported\r\n", "a")) }
    }

    @Test fun 事件回调保留合法路径查询且只使用真实来源IP() {
        val callback = parseDlnaCallback("<http://$peer:4321/event?id=1>", peer)
        assertEquals(peer, callback.address.hostAddress)
        assertEquals(4321, callback.port)
        assertEquals("/event?id=1", callback.requestTarget)
        assertEquals(80, parseDlnaCallback("<http://$peer>", peer).port)
    }

    @Test fun 回调不能越权访问其他IP域名或userinfo() {
        listOf(
            "<http://192.168.31.9:4321/event>", "<http://example.com/event>",
            "<http://user@$peer/event>", "<https://$peer/event>",
            "<http://$peer:0/event>", "<http://$peer:65536/event>", "<http://$peer:/event>",
            "<http://$peer/event#fragment>", "<http://$peer/event> <http://$peer/other>",
            "<http://$peer/event\r\nInjected: true>"
        ).forEach { callback -> fails(412) { parseDlnaCallback(callback, peer) } }
    }

    @Test fun 初始订阅从序号零开始且租期上限为半小时() {
        val registry = DlnaSubscriptions { 1000L }
        val subscription = registry.subscribe(service, peer, initial("Second-999999"))
        assertEquals(0L, subscription.sequence)
        assertFalse(subscription.responseSent.get())
        assertEquals(1800, subscription.leaseSeconds)
        assertEquals(1_801_000L, subscription.expiresAtMs)
        assertEquals(1800, registry.subscribe(service, peer, initial("Second-infinite")).leaseSeconds)
    }

    @Test fun 续租与退订必须匹配SID手机和服务() {
        val registry = DlnaSubscriptions { 1000L }
        val subscription = registry.subscribe(service, peer, initial())
        val renewal = mapOf("sid" to subscription.sid, "timeout" to "Second-5")
        fails(412) { registry.subscribe(service, "192.168.31.9", renewal) }
        fails(412) { registry.subscribe(DlnaService.RENDERING, peer, renewal) }
        fails(400) { registry.subscribe(service, peer, renewal + ("callback" to "<http://$peer/event>")) }
        assertSame(subscription, registry.subscribe(service, peer, renewal))
        assertEquals(6000L, subscription.expiresAtMs)
        fails(412) { registry.unsubscribe(service, "192.168.31.9", mapOf("sid" to subscription.sid)) }
        registry.unsubscribe(service, peer, mapOf("sid" to subscription.sid))
        assertFalse(subscription.active.get())
        assertTrue(registry.current().isEmpty())
    }

    @Test fun 过期订阅不可续租并释放名额() {
        var now = 1000L
        val registry = DlnaSubscriptions { now }
        val subscription = registry.subscribe(service, peer, initial("Second-1"))
        now = 2000L
        fails(412) { registry.subscribe(service, peer, mapOf("sid" to subscription.sid)) }
        assertFalse(subscription.active.get())
        assertTrue(registry.current().isEmpty())
    }

    @Test fun 最多保留八个事件订阅且关闭可整体撤销() {
        val registry = DlnaSubscriptions { 1000L }
        val subscriptions = (1..8).map { registry.subscribe(service, peer, initial()) }
        fails(503) { registry.subscribe(service, peer, initial()) }
        registry.clear()
        assertTrue(subscriptions.all { !it.active.get() })
        assertTrue(registry.current().isEmpty())
        registry.subscribe(service, peer, initial())
    }

    @Test fun 非法租期和缺少事件头部不能创建订阅() {
        val registry = DlnaSubscriptions { 1000L }
        listOf("Second-0", "Second--1", "Second-abc", "random").forEach { timeout ->
            fails(400) { registry.subscribe(service, peer, initial(timeout)) }
        }
        fails(412) { registry.subscribe(service, peer, initial() - "nt") }
        fails(412) { registry.subscribe(service, peer, initial() - "callback") }
    }
}
