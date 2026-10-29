package com.watchlisten.music

import com.watchlisten.music.data.*
import com.watchlisten.music.model.Track
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

class ResumableTransferServerTest {
    @Test fun 真实套接字中断续传完整导入且完成响应可幂等重试() = runBlocking {
        fixture { server, port, directory, imports, completions, received ->
            val created = exchange(port, "POST", "/uploads?name=music.flac&size=6&key=${"a".repeat(32)}")
            assertEquals(200, created.status)
            val id = JSONObject(created.body).getString("id")
            assertEquals(id, JSONObject(exchange(port, "POST", "/uploads?name=music.flac&size=6&key=${"a".repeat(32)}").body).getString("id"))
            assertEquals(200, exchange(port, "PUT", "/uploads/$id?offset=0", "abc".toByteArray()).status)
            assertEquals(409, exchange(port, "POST", "/uploads/$id/complete").status)
            assertEquals(0, imports.get())
            val broken = exchange(port, "PUT", "/uploads/$id?offset=3", "d".toByteArray(), announcedLength = 3)
            assertEquals(409, broken.status)
            assertEquals(3, JSONObject(exchange(port, "GET", "/uploads/$id").body).getInt("offset"))
            assertEquals(200, exchange(port, "PUT", "/uploads/$id?offset=3", "def".toByteArray()).status)
            assertEquals(200, exchange(port, "POST", "/uploads/$id/complete").status)
            assertEquals(200, exchange(port, "POST", "/uploads/$id/complete").status)
            assertTrue(JSONObject(exchange(port, "GET", "/uploads/$id").body).getBoolean("completed"))
            assertEquals("abcdef", received.toString())
            assertEquals(1, imports.get()); assertEquals(1, completions.get())
            assertTrue(directory.listFiles().isNullOrEmpty())
        }
    }

    @Test fun 错误验证码不能查询或续传已有任务且不读取文件体() = runBlocking {
        fixture { _, port, _, imports, _, _ ->
            val id = JSONObject(exchange(port, "POST", "/uploads?name=a.wav&size=3").body).getString("id")
            assertEquals(401, exchange(port, "GET", "/uploads/$id", code = "87654321").status)
            assertEquals(401, exchange(port, "PUT", "/uploads/$id?offset=0", code = "87654321", announcedLength = 100000).status)
            assertEquals(0, JSONObject(exchange(port, "GET", "/uploads/$id").body).getInt("offset"))
            assertEquals(0, imports.get())
        }
    }

    @Test fun 非法分块和路径不会提交也不会调用导入器() = runBlocking {
        fixture { _, port, _, imports, _, _ ->
            val id = JSONObject(exchange(port, "POST", "/uploads?name=a.wav&size=3").body).getString("id")
            assertEquals(409, exchange(port, "PUT", "/uploads/$id?offset=1", "abc".toByteArray()).status)
            assertEquals(413, exchange(port, "PUT", "/uploads/$id?offset=0", announcedLength = ResumeUploadStore.MAX_CHUNK_BYTES + 1).status)
            assertEquals(404, exchange(port, "GET", "/uploads/..%2Fprivate").status)
            assertEquals(400, exchange(port, "POST", "/uploads?name=..%2Fprivate&size=3").status)
            assertEquals(0, JSONObject(exchange(port, "GET", "/uploads/$id").body).getInt("offset"))
            assertEquals(0, imports.get())
            assertEquals(200, exchange(port, "DELETE", "/uploads/$id").status)
            assertEquals(404, exchange(port, "GET", "/uploads/$id").status)
        }
    }

    @Test fun 过期会话清理暂存且不允许继续提交() = runBlocking {
        var now = 0L
        fixture({ now }, 10) { _, port, directory, imports, _, _ ->
            val id = JSONObject(exchange(port, "POST", "/uploads?name=a.wav&size=3").body).getString("id")
            exchange(port, "PUT", "/uploads/$id?offset=0", "a".toByteArray())
            assertEquals(1, directory.listFiles()!!.size)
            now = 10
            assertEquals(410, exchange(port, "GET", "/uploads/$id").status)
            assertTrue(directory.listFiles().isNullOrEmpty())
            assertEquals(0, imports.get())
        }
    }

    private data class Response(val status: Int, val body: String)
    private suspend fun exchange(port: Int, method: String, target: String, body: ByteArray = byteArrayOf(),
                                 code: String = "12345678", announcedLength: Long = body.size.toLong()): Response = withContext(Dispatchers.IO) {
        Socket(InetAddress.getLoopbackAddress(), port).use { socket ->
            socket.soTimeout = 5000
            val header = "$method $target HTTP/1.1\r\nHost: localhost\r\nX-Watch-Code: $code\r\nContent-Length: $announcedLength\r\n\r\n"
            socket.getOutputStream().apply { write(header.toByteArray()); write(body); flush() }
            socket.shutdownOutput()
            val text = socket.getInputStream().bufferedReader().readText()
            Response(text.substringBefore("\r\n").split(' ')[1].toInt(), text.substringAfter("\r\n\r\n"))
        }
    }

    private suspend fun fixture(nowMs: () -> Long = { System.nanoTime() / 1_000_000 }, duration: Long = ResumeUploadStore.SESSION_MS,
                                block: suspend (LocalTransferServer, Int, File, AtomicInteger, AtomicInteger, StringBuilder) -> Unit) {
        val directory = Files.createTempDirectory("watch-resume-http-").toFile()
        val imports = AtomicInteger(); val completions = AtomicInteger(); val received = StringBuilder()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val server = LocalTransferServer(InetAddress.getLoopbackAddress(), "12345678", scope,
            import = { input, _ -> imports.incrementAndGet(); received.append(input.readBytes().toString(Charsets.UTF_8)); Track("fixture", "已导入", "") },
            progress = { _, _, _ -> }, completed = { completions.incrementAndGet() }, failed = {}, fatal = {}, authLimit = {},
            hasSpace = { true }, resumeDirectory = directory, sessionDurationMs = duration, nowMs = nowMs)
        try { block(server, server.start(), directory, imports, completions, received) }
        finally { server.close(); scope.cancel(); directory.walkBottomUp().forEach(File::delete) }
    }
}
