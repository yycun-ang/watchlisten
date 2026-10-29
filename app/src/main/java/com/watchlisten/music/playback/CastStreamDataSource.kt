package com.watchlisten.music.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.watchlisten.music.dlna.RemoteUrlPolicy
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import java.nio.charset.StandardCharsets
import java.util.Locale
import javax.net.SocketFactory
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.SSLException

enum class CastStreamFailure(val retryable: Boolean, val message: String) {
    NETWORK(true, "投放网络暂时中断，正在尝试恢复"),
    TRUNCATED(true, "投放音频连接提前结束，正在尝试恢复"),
    HTTP_TEMPORARY(true, "投放来源暂时不可用，正在尝试恢复"),
    AUTHORIZATION(false, "投放地址授权已失效，请在手机重新选择歌曲"),
    UNAVAILABLE(false, "投放歌曲地址已失效，请在手机重新选择歌曲"),
    HTTP_PERMANENT(false, "投放来源拒绝了音频请求，请在手机重新投放"),
    POSITION_OUT_OF_RANGE(false, "投放来源不支持当前播放位置，请在手机重新投放"),
    INVALID_RESPONSE(false, "投放音频响应无效，请在手机重新选择歌曲"),
    ORIGIN_REJECTED(false, "投放媒体地址不在允许范围内"),
    SOURCE_UNAVAILABLE(false, "投放会话已结束，请在手机重新选择歌曲"),
    SECURE_CONNECTION(false, "无法验证投放来源的安全连接"),
    CANCELLED(false, "投放读取已取消")
}

/** Does not retain any raw URL, response body or original exception cause. */
class CastStreamException(val kind: CastStreamFailure, val httpStatus: Int? = null) : IOException(kind.message)

internal fun castHttpFailure(status: Int): CastStreamException = CastStreamException(when {
    status == 408 || status == 429 || status in 500..599 -> CastStreamFailure.HTTP_TEMPORARY
    status == 401 || status == 403 -> CastStreamFailure.AUTHORIZATION
    status == 404 || status == 410 -> CastStreamFailure.UNAVAILABLE
    status == 416 -> CastStreamFailure.POSITION_OUT_OF_RANGE
    else -> CastStreamFailure.HTTP_PERMANENT
}, status)

internal fun classifyCastFailure(error: Exception): CastStreamException = when (error) {
    is CastStreamException -> error
    is SSLException -> CastStreamException(
        if (hasTransientTlsCause(error)) CastStreamFailure.NETWORK else CastStreamFailure.SECURE_CONNECTION)
    is EOFException -> CastStreamException(CastStreamFailure.TRUNCATED)
    is InterruptedIOException -> CastStreamException(
        if (Thread.currentThread().isInterrupted) CastStreamFailure.CANCELLED else CastStreamFailure.NETWORK)
    is IOException -> CastStreamException(CastStreamFailure.NETWORK)
    else -> CastStreamException(CastStreamFailure.INVALID_RESPONSE)
}

private fun hasTransientTlsCause(error: SSLException): Boolean {
    var cause = error.cause
    repeat(8) {
        val current = cause ?: return false
        if (current is SocketTimeoutException || current is SocketException || current is EOFException ||
            current is UnknownHostException) return true
        cause = current.cause?.takeUnless { it === current }
    }
    return false
}

data class CastStreamSource(
    val url: String,
    val controllerIp: String,
    val socketFactory: SocketFactory = SocketFactory.getDefault(),
    val resolveHost: (String) -> Array<InetAddress> = { InetAddress.getAllByName(it) }
)

/** Progressive audio only. The in-memory token is the sole URI exposed to Media3. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class CastStreamDataSource(
    private val resolve: (Uri) -> CastStreamSource,
    private val allowed: () -> Boolean
) : BaseDataSource(true) {
    private val lifecycle = Any()
    @Volatile private var cancelled = true
    @Volatile private var activeSocket: Socket? = null
    private var body: InputStream? = null
    private var originalUri: Uri? = null
    private var responseHeaders: Map<String, List<String>> = emptyMap()
    private var remaining = -1L
    private var transferActive = false

    override fun open(dataSpec: DataSpec): Long {
        synchronized(lifecycle) {
            check(cancelled && activeSocket == null && !transferActive) { "投放流已经打开" }
            cancelled = false
            originalUri = dataSpec.uri
        }
        transferInitializing(dataSpec)
        try {
            requireAllowed()
            if (dataSpec.httpMethod != DataSpec.HTTP_METHOD_GET || dataSpec.httpBody != null ||
                dataSpec.position < 0 || dataSpec.length < -1L) throw invalidResponse()
            val source = try { resolve(dataSpec.uri) } catch (_: Exception) {
                requireAllowed()
                throw CastStreamException(CastStreamFailure.SOURCE_UNAVAILABLE)
            }
            val response = openResponse(source, dataSpec.position, dataSpec.length)
            val framed = if (response.head.status == 416) ByteArrayInputStream(ByteArray(0)) else response.head.body(response.input)
            val skip = if (response.head.status == 200) dataSpec.position else 0L
            val available = response.head.audioLength(dataSpec.position)
            body = framed
            responseHeaders = response.head.publicHeaders()
            remaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) available else {
                if (available < 0) dataSpec.length else minOf(available, dataSpec.length)
            }
            synchronized(lifecycle) {
                requireAllowed()
                transferActive = true
                transferStarted(dataSpec)
            }
            skipFully(framed, skip)
            return remaining
        } catch (error: Exception) {
            val failure = classifyFailure(error)
            close()
            throw failure
        }
    }

    private fun openResponse(source: CastStreamSource, position: Long, length: Long): CastResponse {
        var uri = validateUrl(source.url, source.controllerIp)
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            requireAllowed()
            val socket = connect(uri, source)
            writeRequest(socket, uri, position, length)
            val guarded = object : FilterInputStream(socket.getInputStream()) {
                override fun read(): Int {
                    requireAllowed()
                    return super.read().also { requireAllowed() }
                }
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    requireAllowed()
                    return super.read(buffer, offset, length).also { requireAllowed() }
                }
            }
            val input = BufferedInputStream(guarded, 16 * 1024)
            val head = CastHttpHead.read(input)
            if (head.status in REDIRECT_STATUSES) {
                val location = head.single("location") ?: throw invalidResponse()
                closeConnection()
                if (redirectCount == MAX_REDIRECTS) throw invalidResponse()
                val target = try { uri.resolve(location) } catch (_: Exception) { throw invalidResponse() }
                uri = validateUrl(target.toASCIIString(), source.controllerIp)
            } else {
                if (head.status != 200 && head.status != 206 && head.status != 416) throw castHttpFailure(head.status)
                head.audioLength(position)
                return CastResponse(head, input)
            }
        }
        throw invalidResponse()
    }

    private fun connect(uri: URI, source: CastStreamSource): Socket {
        val addresses = if (uri.host == source.controllerIp) {
            arrayOf(RemoteUrlPolicy.controllerAddress(source.controllerIp))
        } else source.resolveHost(uri.host)
        if (addresses.isEmpty() || addresses.size > 32) throw invalidResponse()
        addresses.forEach { address ->
            try { RemoteUrlPolicy.validateAddress(uri, address, source.controllerIp) }
            catch (_: IOException) { throw CastStreamException(CastStreamFailure.ORIGIN_REJECTED) }
        }
        val port = if (uri.port != -1) uri.port else if (uri.scheme.equals("https", true)) 443 else 80
        var failure = CastStreamException(CastStreamFailure.NETWORK)
        for (address in addresses.sortedBy { it.address.size }) {
            requireAllowed()
            var socket: Socket? = null
            try {
                socket = source.socketFactory.createSocket()
                installSocket(socket)
                socket.soTimeout = READ_TIMEOUT_MS
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(address, port), CONNECT_TIMEOUT_MS)
                if (uri.scheme.equals("https", true)) {
                    val tls = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                        .createSocket(socket, uri.host, port, true) as SSLSocket
                    socket = tls
                    installSocket(tls)
                    tls.soTimeout = READ_TIMEOUT_MS
                    tls.sslParameters = tls.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                    tls.startHandshake()
                }
                requireAllowed()
                return socket
            } catch (error: Exception) {
                failure = classifyFailure(error)
                runCatching { socket?.close() }
                synchronized(lifecycle) { if (activeSocket === socket) activeSocket = null }
                requireAllowed()
            }
        }
        throw failure
    }

    private fun installSocket(socket: Socket) = synchronized(lifecycle) {
        if (cancelled) {
            runCatching { socket.close() }
            throw CastStreamException(CastStreamFailure.CANCELLED)
        }
        activeSocket = socket
    }

    private fun writeRequest(socket: Socket, uri: URI, position: Long, length: Long) {
        val target = uri.toASCIIString().let { URI(it) }
        val path = target.rawPath.takeUnless { it.isNullOrEmpty() } ?: "/"
        val query = target.rawQuery?.let { "?$it" }.orEmpty()
        val host = if (uri.port == -1) uri.host else "${uri.host}:${uri.port}"
        val request = buildString {
            append("GET $path$query HTTP/1.1\r\nHost: $host\r\n")
            append("User-Agent: WatchListen/0.8.0\r\nAccept: */*\r\n")
            append("Accept-Encoding: identity\r\nConnection: close\r\n")
            CastHttpHead.rangeHeader(position, length)?.let { append("Range: $it\r\n") }
            append("\r\n")
        }
        requireAllowed()
        socket.getOutputStream().apply {
            write(request.toByteArray(StandardCharsets.US_ASCII))
            flush()
        }
    }

    private fun skipFully(input: InputStream, count: Long) {
        var pending = count
        val buffer = ByteArray(8192)
        while (pending > 0) {
            requireAllowed()
            val read = input.read(buffer, 0, minOf(pending, buffer.size.toLong()).toInt())
            if (read < 0) throw truncatedResponse()
            if (read == 0) continue
            pending -= read
            bytesTransferred(read)
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (offset < 0 || length < 0 || offset > buffer.size - length) throw IndexOutOfBoundsException()
        if (length == 0) return 0
        try {
            requireAllowed()
            val input = body ?: throw CastStreamException(CastStreamFailure.SOURCE_UNAVAILABLE)
            if (remaining == 0L) return C.RESULT_END_OF_INPUT
            val requested = if (remaining < 0) length else minOf(length.toLong(), remaining).toInt()
            val count = input.read(buffer, offset, requested)
            requireAllowed()
            if (count < 0) {
                if (remaining > 0) throw truncatedResponse()
                return C.RESULT_END_OF_INPUT
            }
            if (remaining >= 0) remaining -= count
            if (count > 0) bytesTransferred(count)
            return count
        } catch (error: Exception) {
            val failure = classifyFailure(error)
            close()
            throw failure
        }
    }

    override fun getUri(): Uri? = originalUri

    override fun getResponseHeaders(): Map<String, List<String>> = responseHeaders

    override fun close() {
        val socket = synchronized(lifecycle) {
            cancelled = true
            activeSocket.also { activeSocket = null }
        }
        // Close the socket first: a blocked BufferedInputStream must not hold close() until timeout.
        runCatching { socket?.close() }
        runCatching { body?.close() }
        body = null
        originalUri = null
        responseHeaders = emptyMap()
        remaining = -1L
        synchronized(lifecycle) {
            if (transferActive) {
                transferActive = false
                transferEnded()
            }
        }
    }

    private fun closeConnection() {
        val socket = synchronized(lifecycle) { activeSocket.also { activeSocket = null } }
        runCatching { socket?.close() }
    }

    private fun requireAllowed() {
        if (cancelled || !allowed()) throw CastStreamException(CastStreamFailure.CANCELLED)
    }

    private fun validateUrl(url: String, peer: String): URI = try { RemoteUrlPolicy.validate(url, peer) }
        catch (_: IOException) { throw CastStreamException(CastStreamFailure.ORIGIN_REJECTED) }

    private fun classifyFailure(error: Exception): CastStreamException =
        if (cancelled || !allowed()) CastStreamException(CastStreamFailure.CANCELLED) else classifyCastFailure(error)

    private data class CastResponse(val head: CastHttpHead, val input: InputStream)

    private companion object {
        const val CONNECT_TIMEOUT_MS = 5000
        const val READ_TIMEOUT_MS = 10000
        const val MAX_REDIRECTS = 5
        val REDIRECT_STATUSES = setOf(301, 302, 303, 307, 308)
    }
}

/** Routes only private cast-token URIs to the restricted socket transport. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class OriginRoutingDataSource(private val normal: DataSource, private val cast: DataSource) : DataSource {
    private var active: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        normal.addTransferListener(transferListener)
        cast.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        check(active == null) { "媒体流已经打开" }
        val selected = if (dataSpec.uri.scheme.equals("watchlisten-cast", true)) cast else normal
        active = selected
        return try { selected.open(dataSpec) } catch (error: Exception) {
            runCatching { selected.close() }
            active = null
            throw error
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        (active ?: throw IOException("媒体流尚未打开")).read(buffer, offset, length)

    override fun getUri(): Uri? = active?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = active?.responseHeaders ?: emptyMap()

    override fun close() {
        val selected = active
        active = null
        selected?.close()
    }
}

/** Bounded HTTP framing, kept independent of Android for deterministic malformed-response tests. */
internal data class CastHttpHead(val status: Int, val headers: Map<String, List<String>>) {
    fun single(name: String): String? {
        val values = headers[name] ?: return null
        if (values.size != 1) throw invalidResponse()
        return values.single()
    }

    fun audioLength(position: Long): Long {
        if (status == 416) {
            val range = single("content-range") ?: throw invalidResponse()
            val total = UNSATISFIED_RANGE.matchEntire(range)?.groupValues?.get(1)?.let(::positiveLong)
                ?: throw invalidResponse()
            if (position != total) throw castHttpFailure(416)
            return 0L
        }
        val length = contentLength()
        if (status == 200) {
            if (length >= 0 && position > length) throw invalidResponse()
            return if (length < 0) -1L else length - position
        }
        if (status != 206) throw invalidResponse()
        val value = single("content-range") ?: throw invalidResponse()
        val match = CONTENT_RANGE.matchEntire(value) ?: throw invalidResponse()
        val start = positiveLong(match.groupValues[1])
        val end = positiveLong(match.groupValues[2])
        val total = match.groupValues[3].takeUnless { it == "*" }?.let(::positiveLong)
        if (start != position || end < start || total != null && end >= total) throw invalidResponse()
        val rangeLength = try { Math.addExact(Math.subtractExact(end, start), 1L) } catch (_: ArithmeticException) { throw invalidResponse() }
        if (length >= 0 && length != rangeLength) throw invalidResponse()
        return rangeLength
    }

    fun body(input: InputStream): InputStream {
        val length = contentLength()
        val encoding = single("content-encoding")
        if (encoding != null && !encoding.equals("identity", true)) throw invalidResponse()
        val transfer = single("transfer-encoding")
        if (transfer != null) {
            if (!transfer.equals("chunked", true) || length >= 0) throw invalidResponse()
            return CastChunkedInputStream(input)
        }
        return if (length >= 0) CastLengthInputStream(input, length) else input
    }

    // Media3's extractor inference looks up the exact "Content-Type" key.
    fun publicHeaders(): Map<String, List<String>> = headers.entries.mapNotNull { (name, values) ->
        PUBLIC_HEADERS[name]?.let { it to values }
    }.toMap()

    private fun contentLength(): Long = single("content-length")?.let(::positiveLong) ?: -1L

    companion object {
        private val CONTENT_RANGE = Regex("bytes ([0-9]+)-([0-9]+)/(\\*|[0-9]+)", RegexOption.IGNORE_CASE)
        private val UNSATISFIED_RANGE = Regex("bytes \\*/([0-9]+)", RegexOption.IGNORE_CASE)
        private val STATUS = Regex("HTTP/1\\.[01] ([1-5][0-9]{2})(?: [\\x20-\\x7e]*)?")
        private val HEADER_NAME = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
        private val PUBLIC_HEADERS = mapOf(
            "content-type" to "Content-Type", "content-length" to "Content-Length",
            "content-range" to "Content-Range", "accept-ranges" to "Accept-Ranges"
        )

        fun read(input: InputStream): CastHttpHead {
            val lines = CastHttpLines(input, 32 * 1024)
            repeat(4) {
                val statusLine = lines.readLine()
                val status = STATUS.matchEntire(statusLine)?.groupValues?.get(1)?.toInt() ?: throw invalidResponse()
                val headers = linkedMapOf<String, MutableList<String>>()
                while (true) {
                    val line = lines.readLine()
                    if (line.isEmpty()) break
                    val colon = line.indexOf(':')
                    if (colon <= 0 || !HEADER_NAME.matches(line.substring(0, colon))) throw invalidResponse()
                    val value = line.substring(colon + 1).trim(' ', '\t')
                    if (value.any { (it < ' ' && it != '\t') || it == '\u007f' }) throw invalidResponse()
                    headers.getOrPut(line.substring(0, colon).lowercase(Locale.US)) { mutableListOf() }.add(value)
                }
                if (status >= 200) return CastHttpHead(status, headers.mapValues { it.value.toList() })
                if (status != 100 && status != 102 && status != 103) throw invalidResponse()
            }
            throw invalidResponse()
        }

        fun rangeHeader(position: Long, length: Long): String? {
            if (position < 0 || length < -1L || length == 0L) throw invalidResponse()
            if (length < 0) return if (position == 0L) null else "bytes=$position-"
            val end = try { Math.addExact(position, length - 1) } catch (_: ArithmeticException) { throw invalidResponse() }
            return "bytes=$position-$end"
        }
    }
}

internal class CastLengthInputStream(private val input: InputStream, private var remaining: Long) : InputStream() {
    override fun read(): Int {
        val buffer = ByteArray(1)
        return if (read(buffer, 0, 1) < 0) -1 else buffer[0].toInt() and 255
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        checkBuffer(buffer, offset, length)
        if (length == 0) return 0
        if (remaining == 0L) return -1
        val count = input.read(buffer, offset, minOf(length.toLong(), remaining).toInt())
        if (count < 0) throw truncatedResponse()
        remaining -= count
        return count
    }

    override fun close() = input.close()
}

internal class CastChunkedInputStream(private val input: InputStream) : InputStream() {
    private var remaining = 0L
    private var needsTerminator = false
    private var ended = false

    override fun read(): Int {
        val buffer = ByteArray(1)
        return if (read(buffer, 0, 1) < 0) -1 else buffer[0].toInt() and 255
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        checkBuffer(buffer, offset, length)
        if (length == 0) return 0
        if (ended) return -1
        if (remaining == 0L) nextChunk()
        if (ended) return -1
        val count = input.read(buffer, offset, minOf(length.toLong(), remaining).toInt())
        if (count < 0) throw truncatedResponse()
        remaining -= count
        return count
    }

    private fun nextChunk() {
        if (needsTerminator) {
            val first = input.read()
            val second = input.read()
            if (first < 0 || second < 0) throw truncatedResponse()
            if (first != '\r'.code || second != '\n'.code) throw invalidResponse()
        }
        val size = CastHttpLines(input, 8192).readLine().substringBefore(';')
        if (size.isEmpty() || size.length > 16 || size.any { it !in '0'..'9' && it !in 'a'..'f' && it !in 'A'..'F' }) throw invalidResponse()
        remaining = size.toLongOrNull(16) ?: throw invalidResponse()
        needsTerminator = true
        if (remaining != 0L) return
        val trailers = CastHttpLines(input, 32 * 1024)
        while (trailers.readLine().isNotEmpty()) { /* Trailers do not affect the validated media framing. */ }
        ended = true
    }

    override fun close() = input.close()
}

internal class CastHttpLines(private val input: InputStream, private val maximum: Int) {
    private var consumed = 0

    fun readLine(): String {
        val line = StringBuilder()
        while (true) {
            val value = nextByte()
            if (value == '\r'.code) {
                if (nextByte() != '\n'.code) throw invalidResponse()
                return line.toString()
            }
            if (value == '\n'.code || value == 0) throw invalidResponse()
            line.append(value.toChar())
        }
    }

    private fun nextByte(): Int {
        if (++consumed > maximum) throw invalidResponse()
        return input.read().takeIf { it >= 0 } ?: throw truncatedResponse()
    }
}

private fun positiveLong(value: String): Long {
    if (value.isEmpty() || value.any { it !in '0'..'9' }) throw invalidResponse()
    return value.toLongOrNull() ?: throw invalidResponse()
}

private fun checkBuffer(buffer: ByteArray, offset: Int, length: Int) {
    if (offset < 0 || length < 0 || offset > buffer.size - length) throw IndexOutOfBoundsException()
}

private fun invalidResponse() = CastStreamException(CastStreamFailure.INVALID_RESPONSE)
private fun truncatedResponse() = CastStreamException(CastStreamFailure.TRUNCATED)
