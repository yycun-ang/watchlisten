package com.watchlisten.music

import com.watchlisten.music.data.*
import com.watchlisten.music.model.Track
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

class LocalTransferTest {
    private fun parse(text: String) = LocalTransferProtocol.read(ByteArrayInputStream(text.toByteArray(Charsets.US_ASCII)))
    private fun request(headers: String = "Content-Length: 3\r\n", target: String = "/upload?name=music.flac") =
        parse("POST $target HTTP/1.1\r\nX-Watch-Code: 12345678\r\n$headers\r\n")

    @Test fun 正确验证码和编码文件名可以解析() {
        val call = request(target = "/upload?name=%E5%A4%9C%E6%9B%B2.flac")
        assertTrue(LocalTransferProtocol.authenticated(call, "12345678"))
        assertFalse(LocalTransferProtocol.authenticated(call, "87654321"))
        assertEquals("夜曲.flac", LocalTransferProtocol.upload(call).name)
    }

    @Test fun 重复长度及超长请求头被拒绝() {
        assertThrows(TransferHttpError::class.java) { request("Content-Length: 1\r\nContent-Length: 3\r\n") }
        assertThrows(TransferHttpError::class.java) { request("X-Large: ${"a".repeat(17000)}\r\n") }
    }

    @Test fun 分块未知长度和目录穿越不能写入() {
        for (headers in listOf("Transfer-Encoding: chunked\r\nContent-Length: 3\r\n", "", "Content-Length: -1\r\n", "Content-Length: 0\r\n")) {
            assertThrows(TransferHttpError::class.java) { LocalTransferProtocol.upload(request(headers)) }
        }
        for (name in listOf("..%2Foutside.mp3", "%5Csecret.mp3", "%00bad.mp3", "a.mp3&extra=1")) {
            assertThrows(TransferHttpError::class.java) { LocalTransferProtocol.upload(request(target = "/upload?name=$name")) }
        }
    }

    @Test fun 提前断开不将半个文件当成功且不会越过长度() {
        val broken = TransferBody(ByteArrayInputStream(byteArrayOf(1, 2)), 3) {}
        assertThrows(IOException::class.java) { broken.readBytes() }
        val bounded = TransferBody(ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)), 3) {}
        assertArrayEquals(byteArrayOf(1, 2, 3), bounded.readBytes())
        assertEquals(3L, bounded.received)
    }

    @Test fun 错误验证码无需发送文件体即可返回且不调用导入器() = runBlocking {
        val imports = AtomicInteger()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val server = LocalTransferServer(InetAddress.getLoopbackAddress(), "12345678", scope,
            import = { _, _ -> imports.incrementAndGet(); Track("test", "test", "test") },
            progress = { _, _, _ -> }, completed = {}, failed = {}, fatal = {}, authLimit = {}, hasSpace = { true })
        try {
            val port = server.start()
            val response = withContext(Dispatchers.IO) {
                Socket(InetAddress.getLoopbackAddress(), port).use { socket ->
                    socket.soTimeout = 3000
                    socket.getOutputStream().write("POST /upload?name=test.wav HTTP/1.1\r\nX-Watch-Code: 00000000\r\nContent-Length: 1000000\r\n\r\n".toByteArray())
                    socket.getInputStream().bufferedReader().readText()
                }
            }
            assertTrue(response.startsWith("HTTP/1.1 401"))
            assertEquals(0, imports.get())
        } finally { server.close(); scope.cancel() }
    }

    @Test fun 真实套接字上传必须完整导入并返回确认() = runBlocking {
        var bytes = byteArrayOf()
        var name = ""
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val server = LocalTransferServer(InetAddress.getLoopbackAddress(), "12345678", scope,
            import = { input, display -> bytes = input.readBytes(); name = display; Track("test", "收到的音乐", "") },
            progress = { _, _, _ -> }, completed = {}, failed = {}, fatal = {}, authLimit = {}, hasSpace = { true })
        try {
            val port = server.start()
            val response = withContext(Dispatchers.IO) {
                Socket(InetAddress.getLoopbackAddress(), port).use { socket ->
                    socket.soTimeout = 3000
                    socket.getOutputStream().write("POST /upload?name=music.flac HTTP/1.1\r\nX-Watch-Code: 12345678\r\nContent-Length: 3\r\n\r\nabc".toByteArray())
                    socket.getInputStream().bufferedReader().readText()
                }
            }
            assertTrue(response.startsWith("HTTP/1.1 200"))
            assertTrue(response.contains("收到的音乐"))
            assertArrayEquals("abc".toByteArray(), bytes)
            assertEquals("music.flac", name)
        } finally { server.close(); scope.cancel() }
    }
}
