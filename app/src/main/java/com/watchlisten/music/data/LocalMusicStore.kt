package com.watchlisten.music.data

import android.content.Context
import android.content.SharedPreferences
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import com.watchlisten.music.model.Track
import com.watchlisten.music.model.TrackOrigin
import com.watchlisten.music.model.TrackRights
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

data class LocalFileDetails(val audioBytes: Long, val format: String, val coverBytes: Long = 0,
                            val lyricsBytes: Long = 0) {
    val totalBytes: Long get() = audioBytes + coverBytes + lyricsBytes
    val hasLyrics: Boolean get() = lyricsBytes > 0
}

data class LocalMusicState(val tracks: List<Track> = emptyList(), val importing: Boolean = false,
                           val message: String? = null, val error: String? = null,
                           val files: Map<String, LocalFileDetails> = emptyMap(),
                           val lyricImporting: Boolean = false, val exporting: Boolean = false) {
    val totalBytes: Long get() = files.values.sumOf(LocalFileDetails::totalBytes)
    val detailsReady: Boolean get() = tracks.all { it.id in files }
    val lyricTrackIds: Set<String> get() = files.filterValues(LocalFileDetails::hasLyrics).keys
}

/** 用户导入的文件复制到私有目录，独立于酷狗云收藏。 */
class LocalMusicStore(private val context: Context, private val scope: CoroutineScope) {
    private val preferences = context.getSharedPreferences("local_music", Context.MODE_PRIVATE)
    val directory = File(context.filesDir, "local-music").apply { mkdirs() }
    private val covers = File(context.filesDir, "local-covers").apply { mkdirs() }
    val lyrics = LocalLyricsRepository(File(context.filesDir, "local-lyrics"))
    private val mutex = Mutex()
    private val imports = AtomicInteger()
    private val mutableState = MutableStateFlow(LocalMusicState(read()))
    val state = mutableState.asStateFlow()
    private val pendingActions = PendingLocalFileActions(context.getSharedPreferences("local_file_actions", Context.MODE_PRIVATE))
    val pendingLyricsTrack: Track? get() = pendingActions.target(LocalFileAction.LYRICS, ::pendingTrack)
    val pendingExportTrack: Track? get() = pendingActions.target(LocalFileAction.EXPORT, ::pendingTrack)

    init {
        if (!pendingActions.clearInvalid(::pendingTrack)) {
            mutableState.update { it.copy(error = "文件操作未能恢复，请重新选择歌曲") }
        }
        scope.launch(Dispatchers.IO) {
            mutex.withLock {
                val files = state.value.tracks.associate { it.id to details(it) }
                mutableState.update { it.copy(files = files) }
            }
        }
    }

    fun importUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        scope.launch {
            imports.incrementAndGet()
            mutableState.update { it.copy(importing = true, error = null, message = null) }
            var succeeded = 0
            var failures = 0
            try {
                for (uri in uris) {
                    try {
                        val name = withContext(Dispatchers.IO) {
                            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                                ?.use { if (it.moveToFirst()) it.getString(0) else null }
                        } ?: "导入音乐"
                        withContext(Dispatchers.IO) {
                            context.contentResolver.openInputStream(uri)?.use { importStream(it, name) }
                                ?: throw java.io.IOException("文件无法读取")
                        }
                        succeeded++
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { failures++ }
                }
                mutableState.update { it.copy(message = "已导入 $succeeded 首",
                    error = if (failures > 0) "$failures 个文件导入失败，请检查格式与剩余空间" else null) }
            } finally {
                val remaining = imports.decrementAndGet()
                mutableState.update { it.copy(importing = remaining > 0) }
            }
        }
    }

    suspend fun importStream(input: InputStream, displayName: String): Track = mutex.withLock {
        withContext(Dispatchers.IO) {
            val temporary = File(directory, ".${UUID.randomUUID()}.part")
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                temporary.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                    }
                    output.fd.sync()
                }
                val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
                mutableState.value.tracks.firstOrNull { it.hash.equals("local-$hash", true) && validTrack(it) }
                    ?.let { return@withContext it }
                val metadata = metadata(temporary, displayName, hash)
                val fileId = "$hash-${UUID.randomUUID()}"
                val target = File(directory, fileId + "." + localAudioExtension(displayName))
                val coverFile = File(covers, "$fileId.image")
                LocalImportFiles(temporary, target, coverFile).use { files ->
                    files.promoteAudio()
                    metadata.picture?.let(files::storeCover)
                    val track = metadata.track.copy(localPath = target.absolutePath,
                        cover = if (metadata.picture != null) coverFile.toURI().toString() else "")
                    val next = listOf(track) + mutableState.value.tracks.filterNot { it.hash.equals(track.hash, true) }
                    val saved = preferences.edit().putString("tracks", JSONArray().apply { next.forEach { put(it.json()) } }.toString()).commit()
                    if (!saved) throw java.io.IOException("无法保存音乐库")
                    files.commit()
                    mutableState.update { old -> old.copy(tracks = next,
                        files = old.files + (track.id to details(track)), message = "已导入 ${track.title}", error = null) }
                    track
                }
            } finally { temporary.delete() }
        }
    }

    fun remove(track: Track) = removeAll(listOf(track))

    fun removeAll(tracks: Collection<Track>, onRemoved: (Set<String>) -> Unit = {}): Job {
        val ids = tracks.filter(Track::isLocal).mapTo(hashSetOf(), Track::id)
        return scope.launch(Dispatchers.IO) {
            if (ids.isEmpty()) return@launch
            try {
                mutex.withLock {
                    val stored = mutableState.value.tracks.filter { it.id in ids }
                    if (stored.isEmpty()) return@withLock
                    val next = mutableState.value.tracks.filterNot { it.id in ids }
                    val confirmedIds = stored.mapTo(hashSetOf(), Track::id)
                    val removed = completeConfirmedLocalRemoval(persist = {
                        preferences.edit().putString("tracks", JSONArray().apply { next.forEach { put(it.json()) } }.toString()).commit()
                    }, onConfirmed = {
                        mutableState.update { it.copy(tracks = next, files = it.files - ids,
                            message = "已移除 ${stored.size} 个导入文件", error = null) }
                        withContext(Dispatchers.Main.immediate) { onRemoved(confirmedIds) }
                    }, cleanup = {
                        var cleanupFailed = false
                        stored.forEach { track ->
                            val audio = File(track.localPath).takeIf { isPrivateFile(it, directory) }
                            if (audio?.exists() == true && !audio.delete()) cleanupFailed = true
                            val cover = privateCover(track.cover)
                            if (cover?.exists() == true && !cover.delete()) cleanupFailed = true
                            try { lyrics.remove(track) }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { cleanupFailed = true }
                        }
                        if (!pendingActions.clearInvalid(::pendingTrack)) cleanupFailed = true
                        if (cleanupFailed) mutableState.update { it.copy(error = "音乐库已移除，部分文件未能清理") }
                    })
                    if (!removed) mutableState.update { it.copy(error = "移除未能保存，请重试") }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableState.update { it.copy(error = "移除文件失败，请重试") } }
        }
    }

    fun prepareLyricsImport(track: Track): Boolean = prepare(track, LocalFileAction.LYRICS)
    fun prepareExport(track: Track): Boolean = prepare(track, LocalFileAction.EXPORT)
    fun cancelLyricsImport() { clearPending(LocalFileAction.LYRICS) }
    fun cancelExport() { clearPending(LocalFileAction.EXPORT) }
    fun clearMessage() { mutableState.update { it.copy(message = null) } }

    fun removeLyrics(track: Track): Job = scope.launch(Dispatchers.IO) {
            try {
                mutex.withLock {
                    val stored = state.value.tracks.firstOrNull { it.id == track.id && it.isLocal }
                        ?: throw java.io.IOException("歌曲已移除")
                    lyrics.remove(stored)
                    mutableState.update { it.copy(files = it.files + (stored.id to details(stored)),
                        message = "已移除关联歌词", error = null) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableState.update { it.copy(error = "移除歌词失败，请重试") } }
    }

    private fun pendingTrack(id: String): Track? = state.value.tracks.firstOrNull { it.id == id && validTrack(it) }

    private fun prepare(track: Track, action: LocalFileAction): Boolean {
        val stored = pendingTrack(track.id)
        if (stored == null) {
            mutableState.update { it.copy(error = "歌曲已移除，请重新选择") }
            return false
        }
        if (!pendingActions.save(action, stored.id)) {
            mutableState.update { it.copy(error = "文件操作未能保存，请检查存储后重试") }
            return false
        }
        mutableState.update { it.copy(error = null) }
        return true
    }

    private fun clearPending(action: LocalFileAction): Boolean {
        val cleared = pendingActions.clear(action)
        if (!cleared) mutableState.update { it.copy(error = "文件操作未能清理，请检查存储后重试") }
        return cleared
    }

    fun importLyrics(uri: Uri): Job {
        val selected = pendingLyricsTrack
        val consumed = clearPending(LocalFileAction.LYRICS)
        return scope.launch {
            mutableState.update { it.copy(lyricImporting = true, message = null, error = null) }
            try {
                if (!consumed) throw LocalLyricsFailure("文件操作未能清理，请检查存储后重试")
                if (selected == null) throw LocalLyricsFailure("请先选择要关联歌词的音乐")
                withContext(Dispatchers.IO) {
                    mutex.withLock {
                        val stored = state.value.tracks.firstOrNull { it.id == selected.id && validTrack(it) }
                            ?: throw LocalLyricsFailure("歌曲已移除，请重新选择")
                        val document = context.contentResolver.openInputStream(uri)?.use { lyrics.import(stored, it) }
                            ?: throw LocalLyricsFailure("歌词文件无法读取")
                        mutableState.update { it.copy(files = it.files + (stored.id to details(stored)),
                            message = if (document.format == watchmusic.lyrics.LyricsDocument.Format.TEXT)
                                "已关联纯文本歌词，可在歌词页浏览" else "已关联 LRC 歌词，可随播放同步") }
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { mutableState.update { it.copy(error =
                if (error is LocalLyricsFailure) error.message else "歌词导入失败，请检查文件与存储后重试") } }
            finally { mutableState.update { it.copy(lyricImporting = false) } }
        }
    }

    fun exportFileName(track: Track): String = safeLocalExportName(track.title, File(track.localPath).extension)
    fun exportMimeType(track: Track): String = localAudioMime(File(track.localPath).extension)

    fun exportTo(track: Track?, uri: Uri): Job {
        val consumed = clearPending(LocalFileAction.EXPORT)
        return scope.launch {
            mutableState.update { it.copy(exporting = true, message = null, error = null) }
            try {
                if (!consumed) throw java.io.IOException("文件操作未能清理")
                if (track == null) throw java.io.IOException("歌曲已移除，请重新选择")
                if (uri.scheme != "content") throw java.io.IOException("请选择系统文件保存位置")
                withContext(Dispatchers.IO) {
                    mutex.withLock {
                        val stored = state.value.tracks.firstOrNull { it.id == track.id && validTrack(it) }
                            ?: throw java.io.IOException("歌曲已移除，请重新选择")
                        context.contentResolver.openOutputStream(uri, "wt")?.use { output ->
                            File(stored.localPath).inputStream().use { input -> copyLocalAudio(input, output) }
                            output.flush()
                        } ?: throw java.io.IOException("保存位置无法写入")
                        mutableState.update { it.copy(message = "已导出原始音频文件") }
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableState.update { it.copy(error = "导出失败，请检查保存位置与空间后重试") } }
            finally { mutableState.update { it.copy(exporting = false) } }
        }
    }

    private fun details(track: Track) = LocalFileDetails(File(track.localPath).length(),
        File(track.localPath).extension.lowercase(), privateCover(track.cover)?.length() ?: 0,
        lyrics.sizeBytes(track))

    private fun metadata(file: File, name: String, hash: String): ImportedMetadata {
        val reader = MediaMetadataRetriever()
        try {
            reader.setDataSource(file.absolutePath)
            if (reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) != "yes")
                throw java.io.IOException("文件不包含音频")
            val duration = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
            if (duration <= 0) throw java.io.IOException("不是可识别的音频文件")
            val title = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.takeIf(String::isNotBlank)
                ?: name.substringBeforeLast('.').take(150).ifBlank { "导入音乐" }
            val artist = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.take(150).orEmpty()
            val picture = reader.embeddedPicture?.takeIf { it.size <= 16 * 1024 * 1024 }
            return ImportedMetadata(Track("local-$hash", title, artist, durationMs = duration,
                rights = TrackRights.FREE, origin = TrackOrigin.LOCAL), picture)
        } finally { reader.release() }
    }

    private fun read(): List<Track> = runCatching {
        val array = JSONArray(preferences.getString("tracks", "[]"))
        (0 until array.length()).mapNotNull { index -> runCatching { Track.fromJson(array.getJSONObject(index)) }.getOrNull() }
            .filter(::validTrack).distinctBy { it.hash.lowercase() }
            .map { it.copy(cover = privateCover(it.cover)?.takeIf(File::isFile)?.toURI()?.toString().orEmpty()) }
    }.getOrDefault(emptyList())

    private fun validTrack(track: Track): Boolean = track.isLocal && track.hash.matches(Regex("local-[a-fA-F0-9]{64}")) &&
        File(track.localPath).let { isPrivateFile(it, directory) && it.isFile && it.canRead() }
    private fun privateCover(value: String): File? = runCatching {
        val uri = java.net.URI(value)
        if (uri.scheme != "file") null else File(uri).takeIf { isPrivateFile(it, covers) }
    }.getOrNull()
    private data class ImportedMetadata(val track: Track, val picture: ByteArray?)
}

/** 导入提交失败时同时回收音频、封面和临时文件，已存在的文件不覆盖也不回收。 */
internal class LocalImportFiles(private val temporary: File, private val target: File,
                                private val cover: File) : java.io.Closeable {
    private var audioCreated = false
    private var coverCreated = false
    private var committed = false

    fun promoteAudio() {
        if (target.exists() || !temporary.renameTo(target)) throw java.io.IOException("无法保存文件")
        audioCreated = true
    }
    fun storeCover(bytes: ByteArray) {
        if (!cover.createNewFile()) throw java.io.IOException("无法保存封面")
        coverCreated = true
        cover.outputStream().use { output -> output.write(bytes); output.fd.sync() }
    }
    fun commit() { committed = true }
    override fun close() {
        temporary.delete()
        if (!committed) {
            if (audioCreated) target.delete()
            if (coverCreated) cover.delete()
        }
    }
}

internal fun isPrivateFile(file: File, directory: File): Boolean =
    runCatching { file.canonicalFile.parentFile == directory.canonicalFile }.getOrDefault(false)

internal fun localAudioExtension(name: String): String = name.substringAfterLast('.', "audio").lowercase()
    .takeIf { it in setOf("mp3", "flac", "m4a", "aac", "ogg", "opus", "wav", "wma", "aiff", "alac") } ?: "audio"

internal enum class LocalFileAction(val key: String) { LYRICS("lyrics_track_id"), EXPORT("export_track_id") }

/** 系统文件选择器可跨进程恢复；只保存歌曲 ID，结果返回时重新验证本机文件。 */
internal class PendingLocalFileActions(private val preferences: SharedPreferences) {
    @Synchronized fun target(action: LocalFileAction, resolve: (String) -> Track?): Track? =
        preferences.getString(action.key, null)?.let(resolve)

    @Synchronized fun save(action: LocalFileAction, trackId: String): Boolean {
        val saved = runCatching { preferences.edit().putString(action.key, trackId).commit() }.getOrDefault(false)
        if (!saved) clear(action)
        return saved
    }

    @Synchronized fun clear(action: LocalFileAction): Boolean =
        runCatching { preferences.edit().remove(action.key).commit() }.getOrDefault(false)

    @Synchronized fun clearInvalid(resolve: (String) -> Track?): Boolean {
        var cleared = true
        LocalFileAction.entries.forEach { action ->
            val id = preferences.getString(action.key, null)
            if (id != null && resolve(id) == null && !clear(action)) cleared = false
        }
        return cleared
    }
}

/** 保存失败不产生副作用；保存成功后的播放器通知和物理清理作为一个收尾阶段完成。 */
internal suspend fun completeConfirmedLocalRemoval(persist: () -> Boolean, onConfirmed: suspend () -> Unit,
                                                   cleanup: suspend () -> Unit): Boolean {
    if (!persist()) return false
    withContext(NonCancellable) {
        onConfirmed()
        cleanup()
    }
    return true
}
