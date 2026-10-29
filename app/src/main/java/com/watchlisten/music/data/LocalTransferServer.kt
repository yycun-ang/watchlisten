package com.watchlisten.music.data

import com.watchlisten.music.model.Track
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.Closeable
import java.io.InputStream
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.UUID

/** 绑定指定 Wi-Fi 地址的临时文件接收器，不提供磁盘文件读取和目录列表。 */
internal class LocalTransferServer(
    private val address: InetAddress,
    private val code: String,
    parentScope: CoroutineScope,
    private val import: suspend (InputStream, String) -> Track,
    private val progress: (String, Long, Long) -> Unit,
    private val completed: (Track) -> Unit,
    private val failed: (String) -> Unit,
    private val fatal: (String) -> Unit,
    private val authLimit: () -> Unit,
    private val hasSpace: (Long) -> Boolean,
    resumeDirectory: File? = null,
    sessionDurationMs: Long = ResumeUploadStore.SESSION_MS,
    nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val paused: (String) -> Unit = {}
) : Closeable {
    private val job = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + job + Dispatchers.IO)
    private val listener = ServerSocket()
    private val clients = Collections.synchronizedSet(mutableSetOf<Socket>())
    private val slots = Semaphore(4)
    private val uploading = AtomicBoolean(false)
    private val rejectedCodes = AtomicInteger()
    private val resumable = ResumeUploadStore(resumeDirectory ?: File(System.getProperty("java.io.tmpdir"),
        "watchlisten-transfer-${UUID.randomUUID()}"), nowMs, nowMs() + sessionDurationMs.coerceIn(0, ResumeUploadStore.SESSION_MS))

    fun start(): Int {
        listener.reuseAddress = true
        listener.bind(InetSocketAddress(address, 0), 4)
        scope.launch {
            try {
                while (isActive) {
                    val socket = listener.accept()
                    if (!slots.tryAcquire()) {
                        socket.use { respond(it, 503, "连接较多，请稍后重试") }
                        continue
                    }
                    clients += socket
                    launch {
                        try { socket.use { handle(it) } }
                        catch (_: Exception) { }
                        finally { clients -= socket; slots.release() }
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (isActive) fatal("传歌连接已关闭，请重新开启") }
        }
        return listener.localPort
    }

    private suspend fun handle(socket: Socket) {
        socket.soTimeout = 15_000
        val input = socket.getInputStream()
        try {
            val request = LocalTransferProtocol.read(input)
            if (request.method == "GET" && request.target == "/") {
                respond(socket, 200, TRANSFER_PAGE, "text/html; charset=utf-8")
                return
            }
            if (!request.target.startsWith("/upload?") && !request.target.startsWith("/uploads"))
                throw TransferHttpError(404, "页面不存在")
            // 身份验证在读取 body、分配文件和调用导入器之前完成。
            if (!LocalTransferProtocol.authenticated(request, code)) {
                respond(socket, 401, "验证码不正确，请查看手表上的 8 位验证码")
                if (rejectedCodes.incrementAndGet() >= 20) authLimit()
                return
            }
            if (request.target.startsWith("/upload?")) legacyUpload(socket, input, request)
            else resumedUpload(socket, input, request)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: TransferHttpError) { respond(socket, error.status, error.message) }
        catch (_: Exception) {
            failed("文件导入失败，请检查音频格式、网络和剩余空间")
            runCatching { respond(socket, 400, "文件导入失败，请检查音频格式、网络和剩余空间") }
        }
    }

    private suspend fun legacyUpload(socket: Socket, input: InputStream, request: TransferHttpRequest) {
        val upload = LocalTransferProtocol.upload(request)
        if (!hasSpace(upload.length)) throw TransferHttpError(507, "手表剩余空间不足")
        exclusively {
            socket.soTimeout = 30_000
            progress(upload.name, 0, upload.length)
            val body = TransferBody(input, upload.length, throttledProgress(upload.name, upload.length))
            val track = import(body, upload.name)
            currentCoroutineContext().ensureActive()
            if (body.received != upload.length) throw TransferHttpError(400, "文件未完整接收")
            completed(track)
            respondJson(socket, JSONObject().put("ok", true).put("title", track.title),
                "音频已导入，手机可重试确认结果")
        }
    }

    private suspend fun resumedUpload(socket: Socket, input: InputStream, request: TransferHttpRequest) {
        if (request.target.startsWith("/uploads?")) {
            val create = LocalTransferProtocol.resumeCreate(request)
            val state = resumable.start(create.name, create.total, create.clientKey)
            if (state.completed == null && !hasSpace(state.total * 2 - state.offset)) {
                if (state.offset == 0L) resumable.cancel(state.id)
                throw TransferHttpError(507, "手表剩余空间不足，请整理离线音乐后重试")
            }
            respondState(socket, state)
            return
        }
        val (id, completing) = LocalTransferProtocol.resumeTarget(request)
        when {
            request.method == "GET" && !completing -> {
                LocalTransferProtocol.emptyBody(request); LocalTransferProtocol.noQuery(request)
                respondState(socket, resumable.state(id))
            }
            request.method == "DELETE" && !completing -> {
                LocalTransferProtocol.emptyBody(request); LocalTransferProtocol.noQuery(request)
                resumable.cancel(id)
                paused("已移除传歌任务")
                respondJson(socket, JSONObject().put("ok", true))
            }
            request.method == "PUT" && !completing -> exclusively {
                val (offset, length) = LocalTransferProtocol.chunk(request)
                val state = resumable.state(id)
                if (!hasSpace(length)) throw TransferHttpError(507, "手表剩余空间不足")
                socket.soTimeout = 30_000
                progress(state.name, state.offset, state.total)
                try {
                    val updated = resumable.append(id, offset, length, input, throttledProgress(state.name, state.total))
                    respondState(socket, updated, "完整分块已保留，手机可继续当前任务")
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: TransferHttpError) { throw error }
                catch (_: Exception) {
                    val confirmed = runCatching { resumable.state(id).offset }.getOrDefault(state.offset)
                    progress(state.name, confirmed, state.total)
                    failed("连接中断，已保留完整分块，可在手机继续传输")
                    throw TransferHttpError(409, "分块未传完整，已保留此前进度，请重试任务")
                }
            }
            request.method == "POST" && completing -> {
                LocalTransferProtocol.emptyBody(request); LocalTransferProtocol.noQuery(request)
                val state = resumable.state(id)
                if (state.completed != null) { respondState(socket, state); return }
                exclusively {
                    val fresh = resumable.state(id)
                    if (fresh.completed != null) { respondState(socket, fresh); return@exclusively }
                    if (!hasSpace(fresh.total)) throw TransferHttpError(507, "文件已传完，但空间不足以导入，请整理离线音乐后重试")
                    val file = resumable.fileForCompletion(id)
                    progress(fresh.name, fresh.total, fresh.total)
                    val track = file.inputStream().use { import(it, fresh.name) }
                    currentCoroutineContext().ensureActive()
                    val result = resumable.complete(id, track)
                    completed(track)
                    respondState(socket, result, "音频已导入，手机可重试确认结果")
                }
            }
            else -> throw TransferHttpError(405, "上传请求方式不正确")
        }
    }

    private suspend fun exclusively(block: suspend () -> Unit) {
        if (!uploading.compareAndSet(false, true)) throw TransferHttpError(409, "已有文件正在传输，请稍后重试")
        try { block() } finally { uploading.set(false) }
    }

    private fun throttledProgress(name: String, total: Long): (Long) -> Unit {
        var reportedAt = 0L
        return { bytes ->
            val now = System.nanoTime()
            if (now - reportedAt >= 100_000_000L || bytes == total) {
                progress(name, bytes, total)
                reportedAt = now
            }
        }
    }

    private fun respondState(socket: Socket, state: ResumeUploadState, acknowledgmentMessage: String? = null) = respondJson(socket, JSONObject()
        .put("ok", true).put("id", state.id).put("name", state.name).put("total", state.total)
        .put("offset", state.offset).put("completed", state.completed != null)
        .apply { state.completed?.let { put("title", it.title) } }, acknowledgmentMessage)
    private fun respondJson(socket: Socket, value: JSONObject, acknowledgmentMessage: String? = null) {
        try { respond(socket, 200, value.toString(), "application/json; charset=utf-8") }
        catch (_: java.io.IOException) {
            // 写回响应失败不代表已确认的分块或导入被撤销，手机可查询同一任务。
            acknowledgmentMessage?.let(paused)
        }
    }

    private fun respond(socket: Socket, status: Int, body: String, type: String = "text/plain; charset=utf-8") {
        val content = body.toByteArray(Charsets.UTF_8)
        val reason = when (status) {
            200 -> "OK"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 404 -> "Not Found"
            405 -> "Method Not Allowed"; 409 -> "Conflict"; 410 -> "Gone"; 411 -> "Length Required"
            413 -> "Content Too Large"; 431 -> "Request Header Fields Too Large"
            503 -> "Service Unavailable"; 507 -> "Insufficient Storage"; else -> "Error"
        }
        val headers = "HTTP/1.1 $status $reason\r\nContent-Type: $type\r\n" +
            "Content-Length: ${content.size}\r\nConnection: close\r\nCache-Control: no-store\r\n" +
            "X-Content-Type-Options: nosniff\r\nReferrer-Policy: no-referrer\r\n" +
            "Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; connect-src 'self'; base-uri 'none'; frame-ancestors 'none'\r\n\r\n"
        socket.getOutputStream().apply { write(headers.toByteArray(Charsets.US_ASCII)); write(content); flush() }
    }

    override fun close() {
        runCatching { listener.close() }
        synchronized(clients) { clients.toList() }.forEach { runCatching { it.close() } }
        job.cancel()
        resumable.close()
    }
}
