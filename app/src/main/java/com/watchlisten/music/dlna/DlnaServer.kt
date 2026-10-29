package com.watchlisten.music.dlna

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.net.SocketFactory

/** A Wi-Fi scoped UPnP renderer transport. It never binds HTTP to a wildcard address. */
class DlnaServer(
    private val protocol: DlnaProtocol,
    private val allowRequest: () -> Boolean = { true },
    private val onError: (String) -> Unit = {},
    private val eventSocketFactory: SocketFactory = SocketFactory.getDefault(),
    private val bindDatagram: (DatagramSocket) -> Unit = {},
    private val onFatalError: (String) -> Unit = onError
) : Closeable {
    private val runtime = AtomicReference<Runtime?>()
    val port: Int get() = runtime.get()?.http?.localPort ?: 0

    @Synchronized
    fun start(address: Inet4Address, networkInterface: NetworkInterface, preferredPort: Int = 0): Int {
        check(runtime.get() == null) { "DLNA 服务已经启动" }
        require(networkInterface.isUp && !networkInterface.isLoopback)
        val prefix = networkInterface.interfaceAddresses.firstOrNull { it.address == address }
            ?.networkPrefixLength?.toInt() ?: throw IOException("未找到所选无线网络地址")
        val policy = DlnaPeerPolicy(address, prefix)
        require(policy.allows(address)) { "无线网络地址无效" }
        DlnaSsdp.uuid(protocol.identity)
        val http = bindDlnaPort(preferredPort) { requestedPort ->
            val candidate = ServerSocket()
            try {
                candidate.reuseAddress = true
                candidate.bind(InetSocketAddress(address, requestedPort), 16)
                candidate
            } catch (failure: Exception) {
                runCatching { candidate.close() }
                throw failure
            }
        }
        var multicast: MulticastSocket? = null
        try {
            multicast = MulticastSocket(null)
            multicast.apply {
                reuseAddress = true
                bindDatagram(this)
                bind(InetSocketAddress(DlnaSsdp.PORT))
                this.networkInterface = networkInterface
                timeToLive = 2
                soTimeout = 1000
                joinGroup(InetSocketAddress(DlnaSsdp.GROUP, DlnaSsdp.PORT), networkInterface)
            }
            val state = Runtime(address, networkInterface, policy, http, multicast)
            runtime.set(state)
            startLoops(state)
            return http.localPort
        } catch (failure: Exception) {
            runtime.getAndSet(null)?.let(::stop)
            runCatching { multicast?.close() }
            runCatching { http.close() }
            throw IOException("无法启动无线网络投放服务", failure)
        }
    }

    fun notifyState() {
        val state = runtime.get() ?: return
        if (!state.eventQueued.compareAndSet(false, true)) return
        try {
            state.scheduler.execute {
                state.eventQueued.set(false)
                notifySubscribers(state)
            }
        } catch (_: RejectedExecutionException) {
            state.eventQueued.set(false)
        }
    }

    @Synchronized
    override fun close() {
        runtime.getAndSet(null)?.let(::stop)
    }

    private fun startLoops(state: Runtime) {
        thread("dlna-http") { acceptRequests(state) }
        thread("dlna-ssdp") { receiveSearches(state) }
        state.scheduler.scheduleAtFixedRate({ advertise(state, true) }, 0, 30, TimeUnit.SECONDS)
        state.scheduler.scheduleAtFixedRate({ notifySubscribers(state) }, 1, 1, TimeUnit.SECONDS)
    }

    private fun stop(state: Runtime) {
        if (!state.closed.compareAndSet(false, true)) return
        advertise(state, false)
        state.subscriptions.clear()
        runCatching { state.multicast.leaveGroup(InetSocketAddress(DlnaSsdp.GROUP, DlnaSsdp.PORT), state.networkInterface) }
        runCatching { state.multicast.close() }
        runCatching { state.http.close() }
        state.sockets.toList().forEach { runCatching { it.close() } }
        state.scheduler.shutdownNow()
        state.httpWorkers.shutdownNow()
        state.eventWorkers.shutdownNow()
    }

    private fun permitted(state: Runtime): Boolean {
        if (state.closed.get()) return false
        val allowed = runCatching(allowRequest).getOrDefault(false)
        if (!allowed) state.subscriptions.clear()
        return allowed
    }

    private fun acceptRequests(state: Runtime) {
        val retry = DlnaListenerRetry()
        while (!state.closed.get()) {
            val socket = try { state.http.accept().also { retry.recovered() } }
            catch (_: IOException) {
                if (!retryListener(state, retry, state.http.isClosed, "投放控制监听不可用")) break
                continue
            }
            state.sockets.add(socket)
            try {
                state.httpWorkers.execute { serve(state, socket) }
            } catch (_: RejectedExecutionException) {
                state.sockets.remove(socket)
                runCatching { socket.close() }
            }
        }
    }

    private fun serve(state: Runtime, socket: Socket) {
        var timeout: ScheduledFuture<*>? = null
        try {
            timeout = state.scheduler.schedule({ runCatching { socket.close() } }, REQUEST_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
            run {
                socket.soTimeout = REQUEST_READ_TIMEOUT_MS
                if (!state.policy.allows(socket.inetAddress) || !permitted(state)) {
                    respond(socket, HttpResponse(403))
                    return
                }
                val input = BufferedInputStream(DeadlineInput(socket, REQUEST_READ_TIMEOUT_MS))
                val request = readDlnaRequest(input) {
                    socket.getOutputStream().apply { write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray(Charsets.US_ASCII)); flush() }
                }
                if (!permitted(state)) {
                    respond(socket, HttpResponse(403))
                    return
                }
                val response = route(state, request, socket.inetAddress.hostAddress.orEmpty())
                respond(socket, response, request.method == "HEAD")
                response.afterSend?.invoke()
            }
        } catch (failure: DlnaHttpFailure) {
            runCatching { respond(socket, HttpResponse(failure.status)) }
        } catch (_: SocketTimeoutException) {
            runCatching { respond(socket, HttpResponse(408)) }
        } catch (_: IOException) {
            // Disconnects are expected when controllers abandon a request or Wi-Fi changes.
        } catch (_: Exception) {
            report("投放控制请求未能完成")
        } finally {
            timeout?.cancel(false)
            state.sockets.remove(socket)
            runCatching { socket.close() }
        }
    }

    private fun route(state: Runtime, request: DlnaHttpRequest, peer: String): HttpResponse {
        val path = request.target.substringBefore('?')
        if (request.method == "GET" || request.method == "HEAD") return description(state, path)
        val service = DlnaService.entries.firstOrNull {
            path == "/${it.path}/control" || path == "/${it.path}/event"
        } ?: return HttpResponse(404)
        if (path.endsWith("/control")) {
            if (request.method != "POST") return HttpResponse(405, headers = mapOf("Allow" to "POST"))
            return control(service, request, peer)
        }
        if (request.method !in setOf("SUBSCRIBE", "UNSUBSCRIBE")) {
            return HttpResponse(405, headers = mapOf("Allow" to "SUBSCRIBE, UNSUBSCRIBE"))
        }
        return subscribe(state, service, request, peer)
    }

    private fun description(state: Runtime, path: String): HttpResponse {
        if (path == "/device.xml") return HttpResponse(200, protocol.description(state.baseUrl))
        val service = DlnaService.entries.firstOrNull { path == "/${it.path}.xml" } ?: return HttpResponse(404)
        return HttpResponse(200, protocol.serviceDescription(service))
    }

    private fun control(service: DlnaService, request: DlnaHttpRequest, peer: String): HttpResponse = try {
        val action = request.headers["soapaction"] ?: throw DlnaFault(401, "Invalid Action")
        HttpResponse(200, protocol.control(service, action, request.body, peer)).also { notifyState() }
    } catch (fault: DlnaFault) {
        HttpResponse(500, DlnaProtocol.fault(fault))
    } catch (_: Exception) {
        report("投放播放指令未能完成")
        HttpResponse(500, DlnaProtocol.fault(DlnaFault(501, "Action Failed")))
    }

    private fun subscribe(state: Runtime, service: DlnaService, request: DlnaHttpRequest, peer: String): HttpResponse {
        if (request.body.isNotEmpty()) return HttpResponse(400)
        return try {
            if (request.method == "UNSUBSCRIBE") {
                state.subscriptions.unsubscribe(service, peer, request.headers)
                HttpResponse(200)
            } else {
                val subscription = state.subscriptions.subscribe(service, peer, request.headers)
                HttpResponse(200, headers = mapOf("SID" to subscription.sid, "TIMEOUT" to "Second-${subscription.leaseSeconds}"),
                    afterSend = { subscription.responseSent.set(true); enqueueEvent(state, subscription) })
            }
        } catch (failure: DlnaHttpFailure) {
            HttpResponse(failure.status)
        }
    }

    private fun respond(socket: Socket, response: HttpResponse, head: Boolean = false) {
        val body = response.body.toByteArray(Charsets.UTF_8)
        val headers = buildString {
            append("HTTP/1.1 ${response.status} ${httpReason(response.status)}\r\n")
            append("Content-Length: ${body.size}\r\nContent-Type: text/xml; charset=\"utf-8\"\r\n")
            append("Connection: close\r\nServer: ${DlnaSsdp.server(protocol.identity)}\r\n")
            response.headers.forEach { (name, value) -> append("$name: $value\r\n") }
            append("\r\n")
        }
        socket.getOutputStream().apply {
            write(headers.toByteArray(Charsets.US_ASCII))
            if (!head) write(body)
            flush()
        }
    }

    private fun receiveSearches(state: Runtime) {
        val bytes = ByteArray(8193)
        val retry = DlnaListenerRetry()
        while (!state.closed.get()) {
            val packet = DatagramPacket(bytes, bytes.size)
            try {
                state.multicast.receive(packet)
                retry.recovered()
                if (packet.length > 8192 || !state.policy.allows(packet.address) || !permitted(state)) continue
                val search = DlnaSsdp.parseSearch(String(packet.data, 0, packet.length, Charsets.US_ASCII)) ?: continue
                val targets = DlnaSsdp.matchingTargets(search, protocol.identity)
                if (targets.isEmpty() || state.pendingSearches.incrementAndGet() > 32) {
                    if (targets.isNotEmpty()) state.pendingSearches.decrementAndGet()
                    continue
                }
                scheduleSearch(state, packet.address, packet.port, search, targets)
            } catch (_: SocketTimeoutException) {
                // Periodically return to the close flag.
                retry.recovered()
            } catch (_: IOException) {
                if (!retryListener(state, retry, state.multicast.isClosed, "局域网投放发现监听不可用")) break
            }
        }
    }

    private fun scheduleSearch(state: Runtime, peer: InetAddress, port: Int, search: DlnaSearch, targets: List<String>) {
        try {
            val delay = ThreadLocalRandom.current().nextLong(0, search.mxSeconds * 1000L)
            state.scheduler.schedule({
                try {
                    if (permitted(state)) targets.forEach { target ->
                        sendDatagram(state, DlnaSsdp.response(protocol.identity, state.location, target), peer, port)
                    }
                } finally { state.pendingSearches.decrementAndGet() }
            }, delay, TimeUnit.MILLISECONDS)
        } catch (_: RejectedExecutionException) { state.pendingSearches.decrementAndGet() }
    }

    private fun advertise(state: Runtime, alive: Boolean) {
        if (alive && !permitted(state)) return
        DlnaSsdp.targets(protocol.identity).forEach { target ->
            sendDatagram(state, DlnaSsdp.notification(protocol.identity, state.location, target, alive),
                InetAddress.getByName(DlnaSsdp.GROUP), DlnaSsdp.PORT)
        }
    }

    private fun sendDatagram(state: Runtime, message: String, peer: InetAddress, port: Int) {
        val bytes = message.toByteArray(Charsets.US_ASCII)
        runCatching { state.multicast.send(DatagramPacket(bytes, bytes.size, peer, port)) }
    }

    private fun notifySubscribers(state: Runtime) {
        if (!permitted(state)) return
        state.subscriptions.current().forEach { enqueueEvent(state, it) }
    }

    private fun enqueueEvent(state: Runtime, subscription: DlnaSubscription) {
        if (!permitted(state) || !subscription.responseSent.get() || !subscription.active.get() ||
            !subscription.retry.ready(System.nanoTime()) ||
            !subscription.pending.compareAndSet(false, true)) return
        try {
            state.eventWorkers.execute {
                try { deliverEvent(state, subscription) }
                finally { subscription.pending.set(false) }
            }
        } catch (_: RejectedExecutionException) { subscription.pending.set(false) }
    }

    private fun deliverEvent(state: Runtime, subscription: DlnaSubscription) {
        if (!permitted(state) || !state.subscriptions.contains(subscription)) return
        try {
            val snapshot = protocol.target.snapshot(subscription.peer)
            val body = protocol.event(subscription.service, snapshot)
            val now = System.nanoTime()
            if (body == subscription.lastBody && now - subscription.lastDeliveryNanos < TimeUnit.SECONDS.toNanos(25)) return
            if (!permitted(state) || !state.subscriptions.contains(subscription)) return
            sendEvent(state, subscription, body)
            subscription.lastBody = body
            subscription.lastDeliveryNanos = now
            subscription.sequence = if (subscription.sequence == 0xffff_ffffL) 1 else subscription.sequence + 1
            subscription.retry.recovered()
        } catch (_: DlnaFault) {
            state.subscriptions.remove(subscription)
        } catch (_: Exception) {
            subscription.retry.failed(System.nanoTime())
        }
    }

    private fun sendEvent(state: Runtime, subscription: DlnaSubscription, body: String) {
        val callback = subscription.callback
        val bytes = body.toByteArray(Charsets.UTF_8)
        val socket = eventSocketFactory.createSocket()
        state.sockets.add(socket)
        var timeout: ScheduledFuture<*>? = null
        try {
            timeout = state.scheduler.schedule({ runCatching { socket.close() } }, EVENT_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
            socket.use {
                socket.bind(InetSocketAddress(state.address, 0))
                socket.connect(InetSocketAddress(callback.address, callback.port), EVENT_TIMEOUT_MS)
                socket.soTimeout = EVENT_TIMEOUT_MS
                if (!permitted(state) || !state.subscriptions.contains(subscription)) return
                val headers = "NOTIFY ${callback.requestTarget} HTTP/1.1\r\n" +
                    "HOST: ${subscription.peer}:${callback.port}\r\nCONTENT-TYPE: text/xml; charset=\"utf-8\"\r\n" +
                    "NT: upnp:event\r\nNTS: upnp:propchange\r\nSID: ${subscription.sid}\r\n" +
                    "SEQ: ${subscription.sequence}\r\nCONTENT-LENGTH: ${bytes.size}\r\nCONNECTION: close\r\n\r\n"
                socket.getOutputStream().apply { write(headers.toByteArray(Charsets.US_ASCII)); write(bytes); flush() }
                val lines = readDlnaHeaders(BufferedInputStream(DeadlineInput(socket, EVENT_TIMEOUT_MS)))
                val status = lines.firstOrNull()?.split(' ')?.getOrNull(1)?.toIntOrNull() ?: throw IOException("通知响应无效")
                if (status !in 200..299) throw IOException("通知未被接受")
            }
        } finally {
            timeout?.cancel(false)
            state.sockets.remove(socket)
            runCatching { socket.close() }
        }
    }

    private fun report(message: String) { runCatching { onError(message) } }

    private fun retryListener(state: Runtime, retry: DlnaListenerRetry, socketClosed: Boolean, message: String): Boolean {
        if (state.closed.get()) return false
        val delay = if (socketClosed) null else retry.failed()
        if (delay == null) {
            if (state.fatalReported.compareAndSet(false, true)) runCatching { onFatalError(message) }
            return false
        }
        report("局域网短暂波动，正在重试")
        return try { Thread.sleep(delay); !state.closed.get() }
        catch (_: InterruptedException) { Thread.currentThread().interrupt(); false }
    }

    private class Runtime(
        val address: Inet4Address, val networkInterface: NetworkInterface, val policy: DlnaPeerPolicy,
        val http: ServerSocket, val multicast: MulticastSocket
    ) {
        val closed = AtomicBoolean(false)
        val fatalReported = AtomicBoolean(false)
        val eventQueued = AtomicBoolean(false)
        val pendingSearches = AtomicInteger(0)
        val sockets = ConcurrentHashMap.newKeySet<Socket>()
        val subscriptions = DlnaSubscriptions()
        val httpWorkers = workers(4, 16, "dlna-control")
        val eventWorkers = workers(2, 8, "dlna-event")
        val scheduler = ScheduledThreadPoolExecutor(2, daemonFactory("dlna-timer")).apply {
            removeOnCancelPolicy = true
            setExecuteExistingDelayedTasksAfterShutdownPolicy(false)
            setContinueExistingPeriodicTasksAfterShutdownPolicy(false)
        }
        val baseUrl = "http://${address.hostAddress}:${http.localPort}"
        val location = "$baseUrl/device.xml"
    }

    private data class HttpResponse(
        val status: Int, val body: String = "", val headers: Map<String, String> = emptyMap(),
        val afterSend: (() -> Unit)? = null
    )

    companion object {
        private const val REQUEST_TIMEOUT_MS = 10_000
        private const val REQUEST_READ_TIMEOUT_MS = 3000
        private const val EVENT_TIMEOUT_MS = 2500
        private fun thread(name: String, run: () -> Unit) = Thread(run, name).apply { isDaemon = true; start() }
        private fun daemonFactory(name: String): ThreadFactory {
            val number = AtomicInteger(0)
            return ThreadFactory { Thread(it, "$name-${number.incrementAndGet()}").apply { isDaemon = true } }
        }
        private fun workers(count: Int, queue: Int, name: String) = ThreadPoolExecutor(
            count, count, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(queue), daemonFactory(name), ThreadPoolExecutor.AbortPolicy()
        )
    }
}

private class DeadlineInput(private val socket: Socket, timeoutMs: Int) : InputStream() {
    private val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs.toLong())
    private val input = socket.getInputStream()
    private fun updateTimeout() {
        val remaining = deadline - System.nanoTime()
        if (remaining <= 0) throw SocketTimeoutException("请求超时")
        socket.soTimeout = TimeUnit.NANOSECONDS.toMillis(remaining).coerceAtLeast(1).toInt()
    }
    override fun read(): Int { updateTimeout(); return input.read() }
    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
        updateTimeout()
        return input.read(bytes, offset, length)
    }
}

internal class DlnaHttpFailure(val status: Int) : IOException("HTTP 请求无效")

internal data class DlnaHttpRequest(
    val method: String, val target: String, val headers: Map<String, String>, val body: String
)

internal fun readDlnaHeaders(input: InputStream): List<String> {
    val lines = ArrayList<String>()
    val line = ByteArrayOutputStream()
    var total = 0
    var carriageReturn = false
    while (true) {
        val next = input.read()
        if (next < 0) throw DlnaHttpFailure(400)
        if (++total > 32 * 1024) throw DlnaHttpFailure(431)
        if (carriageReturn) {
            if (next != 10) throw DlnaHttpFailure(400)
            val value = line.toString("US-ASCII")
            if (value.isEmpty()) return lines
            lines.add(value)
            line.reset()
            carriageReturn = false
        } else if (next == 13) carriageReturn = true
        else {
            if (next == 10 || next > 126 || next < 32 && next != 9) throw DlnaHttpFailure(400)
            line.write(next)
        }
    }
}

internal fun readDlnaRequest(input: InputStream, onContinue: () -> Unit = {}): DlnaHttpRequest {
    val lines = readDlnaHeaders(input)
    val start = lines.firstOrNull()?.split(' ') ?: throw DlnaHttpFailure(400)
    if (start.size != 3 || start[0].isEmpty() || start[0].any { it !in 'A'..'Z' } ||
        start[1].length > 2048 || !start[1].startsWith('/') || start[1].startsWith("//") ||
        start[2] !in setOf("HTTP/1.0", "HTTP/1.1")) throw DlnaHttpFailure(400)
    val headers = LinkedHashMap<String, String>()
    for (line in lines.drop(1)) {
        val colon = line.indexOf(':')
        if (colon <= 0 || line.first().isWhitespace()) throw DlnaHttpFailure(400)
        val name = line.substring(0, colon)
        if (!name.all { it.isLetterOrDigit() || it in "!#$%&'*+-.^_`|~" }) throw DlnaHttpFailure(400)
        if (headers.put(name.lowercase(Locale.ROOT), line.substring(colon + 1).trim()) != null) throw DlnaHttpFailure(400)
    }
    if (start[2] == "HTTP/1.1" && headers["host"].isNullOrBlank()) throw DlnaHttpFailure(400)
    if (headers.containsKey("transfer-encoding")) throw DlnaHttpFailure(400)
    val rawLength = headers["content-length"]
    val length = if (rawLength == null) 0 else {
        if (rawLength.isEmpty() || rawLength.any { it !in '0'..'9' }) throw DlnaHttpFailure(400)
        rawLength.toLongOrNull() ?: throw DlnaHttpFailure(413)
    }
    if (length > 256 * 1024) throw DlnaHttpFailure(413)
    if (start[0] == "POST" && rawLength == null) throw DlnaHttpFailure(411)
    headers["expect"]?.let {
        if (!it.equals("100-continue", ignoreCase = true) || start[2] != "HTTP/1.1" || length == 0L) throw DlnaHttpFailure(417)
        onContinue()
    }
    val bytes = ByteArray(length.toInt())
    var offset = 0
    while (offset < bytes.size) {
        val read = input.read(bytes, offset, bytes.size - offset)
        if (read <= 0) throw DlnaHttpFailure(400)
        offset += read
    }
    val body = try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: Exception) { throw DlnaHttpFailure(400) }
    return DlnaHttpRequest(start[0], start[1], headers, body)
}

private fun httpReason(status: Int): String = when (status) {
    200 -> "OK"
    400 -> "Bad Request"
    403 -> "Forbidden"
    404 -> "Not Found"
    405 -> "Method Not Allowed"
    408 -> "Request Timeout"
    411 -> "Length Required"
    412 -> "Precondition Failed"
    413 -> "Content Too Large"
    417 -> "Expectation Failed"
    431 -> "Request Header Fields Too Large"
    503 -> "Service Unavailable"
    else -> "Internal Server Error"
}

internal data class DlnaCallback(val address: Inet4Address, val port: Int, val requestTarget: String)

internal fun parseDlnaCallback(value: String, peer: String): DlnaCallback {
    if (value.length > 4096 || !value.startsWith('<') || !value.endsWith('>') ||
        value.substring(1, value.length - 1).any { it == '<' || it == '>' || it.isWhitespace() || it.code < 32 }) throw DlnaHttpFailure(412)
    val uri = runCatching { URI(value.substring(1, value.length - 1)) }.getOrNull() ?: throw DlnaHttpFailure(412)
    if (!uri.scheme.equals("http", ignoreCase = true) || uri.host != peer || uri.userInfo != null || uri.fragment != null ||
        uri.port == 0 || uri.port > 65535 || uri.port < -1) throw DlnaHttpFailure(412)
    if (uri.rawAuthority != peer && uri.rawAuthority != "$peer:${uri.port}") throw DlnaHttpFailure(412)
    // Matching the literal source address prevents DNS rebinding and arbitrary callback requests.
    if (!peer.matches(Regex("[0-9]{1,3}(\\.[0-9]{1,3}){3}"))) throw DlnaHttpFailure(412)
    val address = InetAddress.getByName(peer) as? Inet4Address ?: throw DlnaHttpFailure(412)
    if (address.hostAddress != peer) throw DlnaHttpFailure(412)
    val path = uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/"
    val requestTarget = path + (uri.rawQuery?.let { "?$it" } ?: "")
    return DlnaCallback(address, if (uri.port == -1) 80 else uri.port, URI(requestTarget).toASCIIString())
}

internal class DlnaSubscription(
    val sid: String, val service: DlnaService, val peer: String, val callback: DlnaCallback,
    @Volatile var leaseSeconds: Int, @Volatile var expiresAtMs: Long
) {
    val active = AtomicBoolean(true)
    val responseSent = AtomicBoolean(false)
    val pending = AtomicBoolean(false)
    var sequence = 0L
    val retry = DlnaEventRetry()
    var lastBody: String? = null
    var lastDeliveryNanos = 0L
}

internal class DlnaSubscriptions(private val nowMs: () -> Long = { TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) }) {
    private val subscriptions = LinkedHashMap<String, DlnaSubscription>()

    @Synchronized
    fun subscribe(service: DlnaService, peer: String, headers: Map<String, String>): DlnaSubscription {
        expire()
        val lease = leaseSeconds(headers["timeout"])
        val sid = headers["sid"]
        if (sid != null) {
            if (headers.containsKey("callback") || headers.containsKey("nt")) throw DlnaHttpFailure(400)
            val subscription = matching(sid, service, peer)
            subscription.leaseSeconds = lease
            subscription.expiresAtMs = nowMs() + lease * 1000L
            return subscription
        }
        if (!headers["nt"].equals("upnp:event", ignoreCase = true)) throw DlnaHttpFailure(412)
        val callback = parseDlnaCallback(headers["callback"] ?: throw DlnaHttpFailure(412), peer)
        // A phone reconnecting to a new callback must not fill the bounded registry with dead SIDs.
        subscriptions.values.filter { it.peer == peer && it.service == service && it.retry.hasFailures }.forEach(::remove)
        if (subscriptions.size >= 8) throw DlnaHttpFailure(503)
        val subscription = DlnaSubscription("uuid:${UUID.randomUUID()}", service, peer, callback, lease, nowMs() + lease * 1000L)
        subscriptions[subscription.sid] = subscription
        return subscription
    }

    @Synchronized
    fun unsubscribe(service: DlnaService, peer: String, headers: Map<String, String>) {
        expire()
        if (headers.containsKey("callback") || headers.containsKey("nt")) throw DlnaHttpFailure(400)
        remove(matching(headers["sid"] ?: throw DlnaHttpFailure(412), service, peer))
    }

    @Synchronized fun current(): List<DlnaSubscription> { expire(); return subscriptions.values.toList() }
    @Synchronized fun contains(subscription: DlnaSubscription): Boolean {
        expire()
        return subscription.active.get() && subscriptions[subscription.sid] === subscription
    }
    @Synchronized fun remove(subscription: DlnaSubscription) {
        subscription.active.set(false)
        subscriptions.remove(subscription.sid)
    }
    @Synchronized fun clear() {
        subscriptions.values.forEach { it.active.set(false) }
        subscriptions.clear()
    }

    private fun matching(sid: String, service: DlnaService, peer: String): DlnaSubscription =
        subscriptions[sid]?.takeIf { it.peer == peer && it.service == service } ?: throw DlnaHttpFailure(412)

    private fun expire() {
        val now = nowMs()
        subscriptions.values.removeAll {
            if (it.expiresAtMs > now) false else { it.active.set(false); true }
        }
    }

    private fun leaseSeconds(timeout: String?): Int {
        if (timeout == null || timeout.equals("Second-infinite", ignoreCase = true)) return 1800
        if (!timeout.startsWith("Second-", ignoreCase = true)) throw DlnaHttpFailure(400)
        val seconds = timeout.substring(7).toLongOrNull()?.takeIf { it > 0 } ?: throw DlnaHttpFailure(400)
        return seconds.coerceAtMost(1800).toInt()
    }
}
