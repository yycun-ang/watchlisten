package com.watchlisten.music.domain

import android.os.SystemClock
import com.watchlisten.music.data.*
import com.watchlisten.music.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.security.MessageDigest
import java.util.UUID

data class CloudPlaylistState(
    val playlists: List<CloudPlaylist> = emptyList(), val selected: CloudPlaylist? = null,
    val songs: List<CloudSong> = emptyList(), val addingTrack: Track? = null,
    val loading: Boolean = false, val error: MusicError? = null, val message: String? = null,
    val updatedAtMs: Long = 0, val pendingCreate: PendingCloudCreate? = null,
    val confirmationStatus: CloudConfirmationStatus = CloudConfirmationStatus.IDLE,
    val undoAvailable: Boolean = false, val undoLabel: String? = null, val undoExpiresAtMs: Long = 0
)

/** 当前账号云歌单的确认、恢复与短期撤销。 */
class CloudPlaylistCoordinator(
    private val gateway: CloudPlaylistSource,
    private val sessions: StateFlow<SessionData?>,
    private val scope: CoroutineScope,
    private val onCloudChanged: () -> Unit = {},
    private val creationStore: CloudCreationStore = MemoryCloudCreationStore(),
    private val wallClockMs: () -> Long = System::currentTimeMillis,
    private val monotonicMs: () -> Long = { SystemClock.elapsedRealtime() }
) {
    private val mutableState = MutableStateFlow(initialState())
    val state = mutableState.asStateFlow()
    private var job: Job? = null
    private var epoch = 0L
    private var undoRecord: Undo? = null
    private var undoExpiry: Job? = null
    private data class Undo(val identity: SessionData, val playlist: CloudPlaylist,
                            val tracks: List<Track>, val expiresAtMs: Long)

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            sessions.map { it?.let { s -> s.userId to s.token } }.distinctUntilChanged().drop(1).collect {
                epoch++; job?.cancel(); dismissUndo(); mutableState.value = initialState()
            }
        }
    }

    private fun initialState(adding: Track? = null): CloudPlaylistState {
        val pending = sessions.value?.userId?.let(creationStore::load)
        return CloudPlaylistState(addingTrack = adding, pendingCreate = pending,
            confirmationStatus = if (pending != null) CloudConfirmationStatus.AWAITING_CONFIRMATION else CloudConfirmationStatus.IDLE)
    }

    fun chooseTrack(track: Track) {
        if (track.origin != TrackOrigin.KUGOU) return
        epoch++; job?.cancel(); dismissUndo(); mutableState.value = initialState(track)
        refresh()
    }

    fun clearSelection() {
        epoch++; job?.cancel(); dismissUndo(); mutableState.value = initialState()
        refresh()
    }

    fun refresh() = perform { identity ->
        val playlists = allPlaylists(identity)
        val oldSelected = state.value.selected
        val selected = oldSelected?.let { old -> playlists.firstOrNull { it.collection.key == old.collection.key } }
        val songs = if (selected != null) allSongs(identity, selected) else emptyList()
        resolvePending(identity, playlists)
        ensureIdentity(identity)
        mutableState.update { it.copy(playlists = playlists, selected = selected, songs = songs,
            updatedAtMs = wallClockMs(), message = it.message ?: if (oldSelected != null && selected == null)
                "该歌单已在酷狗移除，列表已更新" else "云歌单已更新") }
    }

    /** 仅由可见页面生命周期调用，不启动后台轮询。 */
    fun refreshIfStale() {
        val now = wallClockMs()
        if (state.value.pendingCreate != null || state.value.updatedAtMs <= 0 ||
            now < state.value.updatedAtMs || now - state.value.updatedAtMs >= REFRESH_INTERVAL_MS) refresh()
    }

    fun open(playlist: CloudPlaylist) = perform { identity ->
        val songs = allSongs(identity, playlist)
        mutableState.update { it.copy(selected = playlist, songs = songs, updatedAtMs = wallClockMs()) }
    }

    fun create(name: String) {
        if (state.value.pendingCreate != null) { refresh(); return }
        val title = name.trim()
        if (title.isEmpty() || title.length > 40 || title.any(Char::isISOControl) || title == "我喜欢") {
            mutableState.update { it.copy(error = MusicError.Unknown("请输入 1–40 字的普通歌单名称")) }; return
        }
        perform { identity ->
            dismissUndo()
            val existing = creationStore.load(identity.userId)
            if (existing != null) {
                mutableState.update { it.copy(pendingCreate = existing) }
                resolvePending(identity, allPlaylists(identity))
                return@perform
            }
            val before = allPlaylists(identity)
            val pending = PendingCloudCreate(UUID.randomUUID().toString(), identity.userId,
                fingerprint(identity.token), title, before.mapTo(hashSetOf()) { it.collection.id }, wallClockMs())
            creationStore.save(pending)
            ensureIdentity(identity)
            mutableState.update { it.copy(pendingCreate = pending.copy(status = CloudConfirmationStatus.SUBMITTING),
                confirmationStatus = CloudConfirmationStatus.SUBMITTING) }
            try {
                val after = gateway.createVerified(identity, title, before)
                ensureIdentity(identity)
                resolvePending(identity, after)
                ensureIdentity(identity)
                mutableState.update { it.copy(playlists = after, updatedAtMs = wallClockMs()) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                ensureIdentity(identity)
                mutableState.update { it.copy(pendingCreate = it.pendingCreate?.copy(status = CloudConfirmationStatus.AWAITING_CONFIRMATION),
                    confirmationStatus = CloudConfirmationStatus.AWAITING_CONFIRMATION,
                    message = "创建结果待确认，刷新只会查询结果，不会重复创建") }
                throw error
            }
        }
    }

    /** 只清理本机待确认标识，不删除云端歌单；UI 应先说明可能已经创建。 */
    fun abandonPendingCreate() {
        if (job?.isActive == true) return
        val pending = state.value.pendingCreate ?: return
        val identity = sessions.value ?: return
        if (pending.userId != identity.userId) return
        try {
            creationStore.clear(identity.userId, pending.operationId)
            mutableState.update { it.copy(pendingCreate = null, confirmationStatus = CloudConfirmationStatus.IDLE,
                error = null, message = "已放弃待确认记录，请在手机酷狗核对是否已经创建") }
        } catch (error: Exception) { mutableState.update { it.copy(error = error.toMusicError()) } }
    }

    private suspend fun resolvePending(identity: SessionData, playlists: List<CloudPlaylist>) {
        ensureIdentity(identity)
        val pending = creationStore.load(identity.userId) ?: return
        val candidates = playlists.filter { it.canEdit && it.collection.title == pending.name && it.collection.id !in pending.beforeIds }
        if (candidates.size == 1) {
            creationStore.clear(identity.userId, pending.operationId)
            mutableState.update { it.copy(pendingCreate = null, error = null, confirmationStatus = CloudConfirmationStatus.CONFIRMED,
                message = "已确认酷狗歌单「${pending.name}」创建成功") }
            onCloudChanged()
        } else mutableState.update { it.copy(pendingCreate = pending,
            confirmationStatus = CloudConfirmationStatus.AWAITING_CONFIRMATION,
            message = if (candidates.isEmpty()) "尚未在酷狗读到「${pending.name}」，请稍后刷新，或在手机确认后放弃待确认"
                else "发现多个同名新歌单，请先在手机酷狗确认；本次不会重复提交") }
    }

    fun rename(playlist: CloudPlaylist, name: String) = perform { identity ->
        dismissUndo()
        val after = gateway.renameVerified(identity, playlist, name, allPlaylists(identity))
        ensureIdentity(identity)
        mutableState.update { it.copy(playlists = after, selected = after.firstOrNull { row -> row.collection.key == playlist.collection.key },
            updatedAtMs = wallClockMs(), confirmationStatus = CloudConfirmationStatus.CONFIRMED, message = "酷狗歌单已改名") }
        onCloudChanged()
    }

    fun delete(playlist: CloudPlaylist) = perform { identity ->
        dismissUndo()
        val after = gateway.deleteVerified(identity, playlist, allPlaylists(identity))
        ensureIdentity(identity)
        mutableState.update { it.copy(playlists = after, selected = null, songs = emptyList(), updatedAtMs = wallClockMs(),
            confirmationStatus = CloudConfirmationStatus.CONFIRMED, message = "酷狗歌单已删除") }
        onCloudChanged()
    }

    fun addTo(playlist: CloudPlaylist) {
        val track = state.value.addingTrack ?: return
        perform { identity ->
            dismissUndo()
            val existing = allSongs(identity, playlist)
            val songs = if (existing.any { sameKugouTrack(it.track, track) }) existing
                else gateway.addVerified(identity, playlist, listOf(track))
            publishSongs(identity, playlist, songs)
            ensureIdentity(identity)
            mutableState.update { it.copy(addingTrack = null, message = "已加入酷狗歌单") }
            onCloudChanged()
        }
    }

    fun remove(song: CloudSong) = removeSongs(listOf(song))
    fun removeSongs(songs: List<CloudSong>) {
        val playlist = state.value.selected ?: return
        if (songs.isEmpty()) return
        val selected = songs.distinctBy { it.fileId }
        if (selected.any { it.fileId <= 0 || it.track.origin != TrackOrigin.KUGOU ||
                    state.value.songs.none { known -> known.fileId == it.fileId && sameKugouTrack(known.track, it.track) } }) {
            mutableState.update { it.copy(error = MusicError.Unknown("所选歌曲已变化，请刷新歌单后重试")) }; return
        }
        perform { identity ->
            dismissUndo()
            val remaining = try { gateway.removeVerified(identity, playlist, selected) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                reconcilePartialRemoval(identity, playlist, selected)
                throw error
            }
            publishSongs(identity, playlist, remaining)
            ensureIdentity(identity)
            offerUndo(identity, playlist, selected.map { it.track })
            mutableState.update { it.copy(message = "已从酷狗歌单移除 ${selected.size} 首") }
            onCloudChanged()
        }
    }

    private suspend fun reconcilePartialRemoval(identity: SessionData, playlist: CloudPlaylist, selected: List<CloudSong>) {
        try {
            val remaining = allSongs(identity, playlist)
            publishSongs(identity, playlist, remaining)
            val removed = selected.filter { old -> remaining.none { it.fileId == old.fileId } }
            if (removed.isNotEmpty()) offerUndo(identity, playlist, removed.map { it.track })
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* 未读回时保留原快照，由原错误提示用户刷新。 */ }
    }

    fun undo() {
        val record = undoRecord ?: return
        if (job?.isActive == true) return
        if (!sameIdentity(record.identity) || monotonicMs() >= record.expiresAtMs) { dismissUndo(); return }
        perform { identity ->
            mutableState.update { it.copy(undoAvailable = false) }
            try {
                val existing = allSongs(identity, record.playlist)
                val missing = record.tracks.filter { track -> existing.none { sameKugouTrack(it.track, track) } }
                val restored = if (missing.isEmpty()) existing else gateway.addVerified(identity, record.playlist, missing)
                publishSongs(identity, record.playlist, restored)
                dismissUndo()
                mutableState.update { it.copy(message = "已恢复云歌单歌曲") }
                onCloudChanged()
            } catch (error: Exception) {
                if (undoRecord === record && sameIdentity(identity) && monotonicMs() < record.expiresAtMs)
                    mutableState.update { it.copy(undoAvailable = true) }
                throw error
            }
        }
    }

    private fun offerUndo(identity: SessionData, playlist: CloudPlaylist, tracks: List<Track>) {
        val record = Undo(identity, playlist, tracks.distinctBy { it.id }, monotonicMs() + UNDO_MS)
        undoRecord = record
        mutableState.update { it.copy(undoAvailable = true, undoLabel = "撤销移出歌单", undoExpiresAtMs = record.expiresAtMs) }
        undoExpiry?.cancel(); undoExpiry = scope.launch { delay(UNDO_MS); if (undoRecord === record) dismissUndo() }
    }

    fun dismissUndo() {
        undoExpiry?.cancel(); undoExpiry = null; undoRecord = null
        mutableState.update { it.copy(undoAvailable = false, undoLabel = null, undoExpiresAtMs = 0) }
    }
    fun clearMessage() { mutableState.update { it.copy(message = null) } }

    private suspend fun publishSongs(identity: SessionData, playlist: CloudPlaylist, songs: List<CloudSong>) {
        ensureIdentity(identity)
        if (songs.any { it.track.origin != TrackOrigin.KUGOU }) throw MusicFailure(MusicError.InvalidResponse)
        val unique = uniqueSongs(songs)
        val updated = playlist.copy(collection = playlist.collection.copy(count = unique.size))
        mutableState.update { it.copy(selected = updated, songs = unique,
            playlists = it.playlists.map { row -> if (row.collection.key == updated.collection.key) updated else row },
            updatedAtMs = wallClockMs(), confirmationStatus = CloudConfirmationStatus.CONFIRMED) }
    }

    private fun perform(action: suspend (SessionData) -> Unit) {
        val identity = sessions.value ?: run { mutableState.update { it.copy(error = MusicError.AuthenticationExpired) }; return }
        if (job?.isActive == true) return
        val expected = epoch
        mutableState.update { it.copy(loading = true, error = null, message = null) }
        job = scope.launch {
            try { ensureIdentity(identity); action(identity) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { if (expected == epoch && sameIdentity(identity)) mutableState.update {
                it.copy(error = error.toMusicError(), confirmationStatus = if (it.pendingCreate != null)
                    CloudConfirmationStatus.AWAITING_CONFIRMATION else it.confirmationStatus)
            } }
            finally { if (expected == epoch && sameIdentity(identity)) mutableState.update { it.copy(loading = false) } }
        }
    }

    private suspend fun allPlaylists(identity: SessionData): List<CloudPlaylist> =
        readCloudPages { page -> ensureIdentity(identity); gateway.playlists(identity, page).also { ensureIdentity(identity) } }
            .also { rows -> if (rows.any { it.collection.kind != CollectionKind.CLOUD || it.collection.ownerId != identity.userId }) throw MusicFailure(MusicError.InvalidResponse) }

    private suspend fun allSongs(identity: SessionData, playlist: CloudPlaylist): List<CloudSong> {
        if (playlist.collection.ownerId != identity.userId || playlist.collection.kind != CollectionKind.CLOUD)
            throw MusicFailure(MusicError.AuthenticationExpired)
        return readCloudPages { page -> ensureIdentity(identity); gateway.songs(identity, playlist, page).also { ensureIdentity(identity) } }
            .also { rows -> if (rows.any { it.track.origin != TrackOrigin.KUGOU }) throw MusicFailure(MusicError.InvalidResponse) }
            .let(::uniqueSongs)
    }

    private fun sameIdentity(identity: SessionData) = sessions.value?.let { it.userId == identity.userId && it.token == identity.token } == true
    private suspend fun ensureIdentity(identity: SessionData) {
        currentCoroutineContext().ensureActive()
        if (!sameIdentity(identity)) throw CancellationException("酷狗账号已变化")
    }
    private fun fingerprint(token: String) = MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun uniqueSongs(rows: List<CloudSong>) = rows.distinctBy { if (it.fileId > 0) "file:${it.fileId}" else "track:${it.track.id}" }
    companion object { private const val REFRESH_INTERVAL_MS = 60_000L; private const val UNDO_MS = 60_000L }
}
