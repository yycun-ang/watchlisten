package com.watchlisten.music.dlna

import java.net.Inet4Address
import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DlnaSsdpTest {
    private val identity = DlnaIdentity("f5489e15-020c-4338-a26f-4995bec71a35", "腕上听")
    private fun ip(value: String) = InetAddress.getByName(value) as Inet4Address
    private fun search(target: String = "ssdp:all", mx: String = "2") =
        "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: $mx\r\nST: $target\r\n\r\n"

    @Test fun 同一子网的真实无线地址包括自身可以控制() {
        val policy = DlnaPeerPolicy(ip("192.168.31.42"), 24)
        assertTrue(policy.allows(ip("192.168.31.42")))
        assertTrue(policy.allows(ip("192.168.31.88")))
        assertFalse(policy.allows(ip("192.168.32.88")))
        assertFalse(policy.allows(ip("192.168.31.0")))
        assertFalse(policy.allows(ip("192.168.31.255")))
    }

    @Test fun 子网掩码不假设二十四位() {
        val policy = DlnaPeerPolicy(ip("192.168.30.42"), 23)
        assertTrue(policy.allows(ip("192.168.30.255")))
        assertTrue(policy.allows(ip("192.168.31.99")))
        assertFalse(policy.allows(ip("192.168.31.255")))
        assertFalse(policy.allows(ip("192.168.32.1")))
    }

    @Test fun 回环链路本地和组播不能作为控制端() {
        listOf("127.0.0.1", "169.254.3.8", "239.255.255.250", "0.0.0.0").forEach { peer ->
            assertFalse(DlnaPeerPolicy(ip("192.168.1.8"), 16).allows(ip(peer)))
        }
        assertFalse(DlnaPeerPolicy(ip("192.168.1.8"), 24).allows(InetAddress.getByName("::1")))
    }

    @Test fun 搜索全设备返回根设备标识设备类型和三个服务() {
        val parsed = requireNotNull(DlnaSsdp.parseSearch(search()))
        val targets = DlnaSsdp.matchingTargets(parsed, identity)
        assertEquals(6, targets.size)
        assertTrue(targets.contains("upnp:rootdevice"))
        assertTrue(targets.contains("uuid:${identity.uuid}"))
        assertTrue(targets.containsAll(DlnaService.entries.map { it.urn }))
    }

    @Test fun 按类型查找只返回匹配项且不响应陌生服务() {
        val target = DlnaService.TRANSPORT.urn
        assertEquals(listOf(target), DlnaSsdp.matchingTargets(DlnaSearch(target, 1), identity))
        assertTrue(DlnaSsdp.matchingTargets(DlnaSearch("urn:unknown", 1), identity).isEmpty())
    }

    @Test fun 搜索等待值最多三秒且非法值拒绝() {
        assertEquals(3, DlnaSsdp.parseSearch(search(mx = "999999"))?.mxSeconds)
        assertEquals(1, DlnaSsdp.parseSearch(search(mx = "0"))?.mxSeconds)
        listOf("-1", "NaN", "1.5").forEach { assertNull(DlnaSsdp.parseSearch(search(mx = it))) }
    }

    @Test fun 错误发现方法和重复头部不触发响应() {
        assertNull(DlnaSsdp.parseSearch(search().replace("M-SEARCH", "NOTIFY")))
        assertNull(DlnaSsdp.parseSearch(search().replace("ssdp:discover", "ssdp:alive")))
        assertNull(DlnaSsdp.parseSearch(search().replace("ST: ssdp:all", "ST: ssdp:all\r\nST: upnp:rootdevice")))
        assertNull(DlnaSsdp.parseSearch(search().replace("239.255.255.250", "192.168.1.1")))
        assertNull(DlnaSsdp.parseSearch(search().replace("\r\n", "\n")))
    }

    @Test fun 响应与宣告保持一致的唯一标识和有效期() {
        val location = "http://192.168.31.42:12345/device.xml"
        val uuid = "uuid:${identity.uuid}"
        val response = DlnaSsdp.response(identity, location, "upnp:rootdevice")
        assertTrue(response.startsWith("HTTP/1.1 200 OK\r\n"))
        assertTrue(response.contains("CACHE-CONTROL: max-age=120\r\n"))
        assertTrue(response.contains("USN: $uuid::upnp:rootdevice\r\n"))
        assertTrue(DlnaSsdp.response(identity, location, uuid).contains("USN: $uuid\r\n"))
        assertFalse(DlnaSsdp.response(identity, location, uuid).contains("$uuid::$uuid"))
        assertTrue(DlnaSsdp.notification(identity, location, uuid, true).contains("NTS: ssdp:alive\r\n"))
        assertTrue(DlnaSsdp.notification(identity, location, uuid, false).contains("NTS: ssdp:byebye\r\n"))
    }

    @Test fun 版本号不允许注入发现响应头部() {
        val server = DlnaSsdp.server(identity.copy(version = "0.8\r\nSecret: test"))
        assertFalse(server.contains('\r'))
        assertFalse(server.contains('\n'))
    }
}
