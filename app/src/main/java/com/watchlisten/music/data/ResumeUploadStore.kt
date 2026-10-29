package com.watchlisten.music.data

import com.watchlisten.music.model.Track
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.UUID

internal data class ResumeUploadState(
    val id: String, val name: String, val total: Long, val offset: Long,
    val completed: Track? = null
)

/** 每个接收会话独占的临时文件；只公开完整确认过的块，不保留半块。 */
internal class ResumeUploadStore(
    private val directory: File,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val expiresAtMs: Long = nowMs() + SESSION_MS
) : Closeable {
    private data class Entry(val id: String, val name: String, val total: Long, val file: File,
                             val clientKey: String?, var offset: Long = 0, var completed: Track? = null)
    private val entries = linkedMapOf<String, Entry>()
    private var closed = false

    init { if (!directory.mkdirs() && !directory.isDirectory) throw java.io.IOException("无法创建传歌临时目录") }

    @Synchronized fun start(name: String, total: Long, clientKey: String? = null): ResumeUploadState {
        active()
        LocalTransferProtocol.validName(name)
        if (total <= 0 || total > LocalTransferProtocol.MAX_FILE_BYTES) throw TransferHttpError(413, "文件为空或超过 2 GB")
        if (clientKey != null && !clientKey.matches(Regex("[a-f0-9]{32}"))) throw TransferHttpError(400, "上传任务标识不合法")
        entries.values.firstOrNull { clientKey != null && it.clientKey == clientKey }?.let {
            if (it.name != name || it.total != total) throw TransferHttpError(409, "上传任务与文件不一致")
            return snapshot(it)
        }
        val pending = entries.values.filter { it.completed == null }
        if (pending.size >= MAX_PENDING || pending.sumOf { it.total } > LocalTransferProtocol.MAX_FILE_BYTES - total)
            throw TransferHttpError(409, "待传文件较多，请先完成或移除当前任务")
        while (entries.size >= MAX_ENTRIES) {
            val old = entries.values.firstOrNull { it.completed != null } ?: break
            entries.remove(old.id)
        }
        val id = UUID.randomUUID().toString().replace("-", "")
        val file = File(directory, "$id.part")
        if (!file.createNewFile()) throw java.io.IOException("无法创建上传文件")
        return Entry(id, name, total, file, clientKey).also { entries[id] = it }.let(::snapshot)
    }

    @Synchronized fun state(id: String): ResumeUploadState { active(); return snapshot(entry(id)) }

    @Synchronized fun append(id: String, offset: Long, length: Long, input: InputStream,
                              progress: (Long) -> Unit = {}): ResumeUploadState {
        active()
        val item = entry(id)
        if (item.completed != null) throw TransferHttpError(409, "文件已经导入")
        if (offset != item.offset) throw TransferHttpError(409, "上传位置已变化，请查询进度后继续")
        if (length <= 0 || length > MAX_CHUNK_BYTES || length > item.total - item.offset)
            throw TransferHttpError(413, "上传分块大小不合法")
        val confirmed = item.offset
        try { RandomAccessFile(item.file, "rw").use { output ->
            if (output.length() != confirmed) throw TransferHttpError(409, "临时文件状态已变化，请重新选择文件")
            try {
                output.seek(confirmed)
                val body = TransferBody(input, length) { progress(confirmed + it) }
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    active()
                    val count = body.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
                output.fd.sync()
                item.offset = confirmed + length
            } catch (error: Exception) {
                output.setLength(confirmed)
                output.fd.sync()
                throw error
            }
        } } finally { if (closed) { item.file.delete(); directory.delete() } }
        return snapshot(item)
    }

    @Synchronized fun fileForCompletion(id: String): File {
        active()
        val item = entry(id)
        if (item.completed != null) throw TransferHttpError(409, "文件已经导入")
        if (item.offset != item.total || item.file.length() != item.total)
            throw TransferHttpError(409, "文件尚未传输完整")
        return item.file
    }

    @Synchronized fun complete(id: String, track: Track): ResumeUploadState {
        active()
        val item = entry(id)
        item.completed?.let { return snapshot(item) }
        fileForCompletion(id)
        item.completed = track
        item.file.delete()
        return snapshot(item)
    }

    @Synchronized fun cancel(id: String) {
        active()
        val item = entry(id)
        if (item.file.exists() && !item.file.delete()) throw java.io.IOException("暂存文件暂时无法移除")
        entries.remove(id)
    }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        entries.values.forEach { it.file.delete() }
        entries.clear()
        directory.delete() // 不递归删除，目录里若有不属于本实例的文件则保留。
    }

    private fun active() {
        if (closed || nowMs() >= expiresAtMs) {
            close()
            throw TransferHttpError(410, "传歌会话已结束，请在手表重新开启")
        }
    }
    private fun entry(id: String): Entry {
        if (!id.matches(Regex("[a-f0-9]{32}"))) throw TransferHttpError(404, "上传任务不存在")
        return entries[id] ?: throw TransferHttpError(404, "上传任务不存在或已过期")
    }
    private fun snapshot(item: Entry) = ResumeUploadState(item.id, item.name, item.total, item.offset, item.completed)

    companion object {
        const val MAX_CHUNK_BYTES = 512L * 1024
        const val SESSION_MS = 15 * 60_000L
        private const val MAX_PENDING = 8
        private const val MAX_ENTRIES = 128
    }
}
