package com.watchlisten.music.data

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Locale

internal class TransferHttpError(val status: Int, override val message: String) : IOException(message)

internal data class TransferHttpRequest(
    val method: String, val target: String, val headers: Map<String, String>
)

internal data class TransferUpload(val name: String, val length: Long)
internal data class TransferResumeCreate(val name: String, val total: Long, val clientKey: String?)

/** 只解析有界的 HTTP 头；调用方通过身份验证后才可读取文件内容。 */
internal object LocalTransferProtocol {
    const val MAX_HEADER_BYTES = 16 * 1024
    const val MAX_FILE_BYTES = 2L * 1024 * 1024 * 1024
    const val CODE_HEADER = "x-watch-code"
    private const val MAX_HEADERS = 64

    fun read(input: InputStream): TransferHttpRequest {
        var remaining = MAX_HEADER_BYTES
        fun line(): String {
            val result = ByteArrayOutputStream()
            var previous = -1
            while (remaining-- > 0) {
                val value = input.read()
                if (value < 0) throw TransferHttpError(400, "请求不完整")
                if (previous == 13) {
                    if (value != 10) throw TransferHttpError(400, "请求格式不正确")
                    return result.toString(Charsets.US_ASCII.name())
                }
                if (value == 13) { previous = value; continue }
                if (value != 9 && value !in 32..126) throw TransferHttpError(400, "请求头不合法")
                result.write(value)
            }
            throw TransferHttpError(431, "请求头过长")
        }
        val start = line().split(' ')
        if (start.size != 3 || start[2] !in setOf("HTTP/1.0", "HTTP/1.1") ||
            !start[1].startsWith('/') || start[1].startsWith("//") || start[1].length > 4096)
            throw TransferHttpError(400, "请求格式不正确")
        val headers = linkedMapOf<String, String>()
        while (true) {
            val raw = line()
            if (raw.isEmpty()) break
            val separator = raw.indexOf(':')
            if (separator <= 0 || raw.first().isWhitespace() || headers.size >= MAX_HEADERS)
                throw TransferHttpError(400, "请求头不合法")
            val key = raw.substring(0, separator).lowercase(Locale.ROOT)
            if (!key.matches(Regex("[a-z0-9!#$%&'*+.^_`|~-]+")) || key in headers)
                throw TransferHttpError(400, "请求头重复或不合法")
            headers[key] = raw.substring(separator + 1).trim()
        }
        return TransferHttpRequest(start[0], start[1], headers)
    }

    fun authenticated(request: TransferHttpRequest, expectedCode: String): Boolean {
        val supplied = request.headers[CODE_HEADER].orEmpty()
        if (supplied.length != 8 || expectedCode.length != 8) return false
        return MessageDigest.isEqual(supplied.toByteArray(Charsets.US_ASCII),
            expectedCode.toByteArray(Charsets.US_ASCII))
    }

    fun upload(request: TransferHttpRequest): TransferUpload {
        if (request.method != "POST") throw TransferHttpError(405, "请使用上传页面选择文件")
        val uri = runCatching { URI(request.target) }.getOrNull()
            ?: throw TransferHttpError(400, "上传地址不合法")
        if (uri.rawPath != "/upload" || uri.rawFragment != null) throw TransferHttpError(404, "页面不存在")
        if ("transfer-encoding" in request.headers) throw TransferHttpError(400, "请使用支持完整文件上传的浏览器")
        val rawLength = request.headers["content-length"] ?: throw TransferHttpError(411, "缺少文件长度")
        val length = rawLength.takeIf { it.matches(Regex("[0-9]{1,12}")) }?.toLongOrNull()
            ?: throw TransferHttpError(400, "文件长度不合法")
        if (length <= 0 || length > MAX_FILE_BYTES) throw TransferHttpError(413, "文件为空或超过 2 GB")
        val parameters = uri.rawQuery.orEmpty().split('&').filter(String::isNotEmpty)
        if (parameters.size != 1 || !parameters.single().startsWith("name=")) throw TransferHttpError(400, "缺少文件名")
        val name = runCatching { URLDecoder.decode(parameters.single().substring(5), "UTF-8") }.getOrNull()
            ?: throw TransferHttpError(400, "文件名不合法")
        validName(name)
        return TransferUpload(name, length)
    }

    fun validName(name: String) {
        if (name.isBlank() || name.length > 180 || name in setOf(".", "..") ||
            name.any { it == '/' || it == '\\' || it.code < 32 || it.code == 127 })
            throw TransferHttpError(400, "文件名不合法")
    }

    fun resumeCreate(request: TransferHttpRequest): TransferResumeCreate {
        if (request.method != "POST") throw TransferHttpError(405, "上传请求方式不正确")
        emptyBody(request)
        val uri = targetUri(request)
        if (uri.rawPath != "/uploads") throw TransferHttpError(404, "上传地址不存在")
        val parameters = query(uri, setOf("name", "size", "key"))
        val name = parameters["name"] ?: throw TransferHttpError(400, "缺少文件名")
        validName(name)
        val total = decimal(parameters["size"] ?: "")
        if (total <= 0 || total > MAX_FILE_BYTES) throw TransferHttpError(413, "文件为空或超过 2 GB")
        val key = parameters["key"]
        if (key != null && !key.matches(Regex("[a-f0-9]{32}"))) throw TransferHttpError(400, "上传任务标识不合法")
        return TransferResumeCreate(name, total, key)
    }

    fun resumeTarget(request: TransferHttpRequest): Pair<String, Boolean> {
        val uri = targetUri(request)
        val match = Regex("/uploads/([a-f0-9]{32})(/complete)?").matchEntire(uri.rawPath)
            ?: throw TransferHttpError(404, "上传任务不存在")
        return match.groupValues[1] to (match.groupValues[2].isNotEmpty())
    }

    fun chunk(request: TransferHttpRequest): Pair<Long, Long> {
        if (request.method != "PUT") throw TransferHttpError(405, "上传请求方式不正确")
        if ("transfer-encoding" in request.headers) throw TransferHttpError(400, "分块必须提供明确长度")
        val offset = decimal(query(targetUri(request), setOf("offset"))["offset"] ?: "")
        val length = decimal(request.headers["content-length"] ?: throw TransferHttpError(411, "缺少分块长度"))
        if (length <= 0 || length > ResumeUploadStore.MAX_CHUNK_BYTES) throw TransferHttpError(413, "分块大小不合法")
        return offset to length
    }

    fun emptyBody(request: TransferHttpRequest) {
        if ("transfer-encoding" in request.headers || request.headers["content-length"]?.let { it != "0" } == true)
            throw TransferHttpError(400, "此请求不接受文件内容")
    }

    fun noQuery(request: TransferHttpRequest) {
        if (targetUri(request).rawQuery != null) throw TransferHttpError(400, "请求地址不合法")
    }

    private fun targetUri(request: TransferHttpRequest): URI {
        val uri = runCatching { URI(request.target) }.getOrNull()
            ?: throw TransferHttpError(400, "请求地址不合法")
        if (uri.rawFragment != null) throw TransferHttpError(400, "请求地址不合法")
        return uri
    }
    private fun decimal(value: String): Long = value.takeIf { it.matches(Regex("[0-9]{1,12}")) }?.toLongOrNull()
        ?: throw TransferHttpError(400, "文件大小或位置不合法")
    private fun query(uri: URI, allowed: Set<String>): Map<String, String> {
        val values = linkedMapOf<String, String>()
        for (part in uri.rawQuery.orEmpty().split('&').filter(String::isNotEmpty)) {
            val key = part.substringBefore('=')
            if ('=' !in part || key !in allowed || key in values) throw TransferHttpError(400, "请求参数不合法")
            val value = runCatching { URLDecoder.decode(part.substringAfter('='), "UTF-8") }.getOrNull()
                ?: throw TransferHttpError(400, "请求参数不合法")
            values[key] = value
        }
        return values
    }
}

/** 精确读到 Content-Length，提前断开会报错，绝不将半个文件当作完整导入。 */
internal class TransferBody(
    private val input: InputStream, val length: Long, private val progress: (Long) -> Unit
) : InputStream() {
    var received = 0L
        private set

    override fun read(): Int {
        if (received == length) return -1
        val value = input.read()
        if (value < 0) throw IOException("文件传输中断")
        received++
        progress(received)
        return value
    }

    override fun read(buffer: ByteArray, offset: Int, count: Int): Int {
        if (count == 0) return 0
        if (received == length) return -1
        val actual = input.read(buffer, offset, minOf(count.toLong(), length - received).toInt())
        if (actual < 0) throw IOException("文件传输中断")
        received += actual
        progress(received)
        return actual
    }
}
