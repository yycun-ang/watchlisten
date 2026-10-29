package com.watchlisten.music.domain

import android.os.SystemClock
import com.watchlisten.music.data.*
import com.watchlisten.music.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 收藏只发布酷狗读回的快照，写入后的完整结果直接复用。 */
class CloudFavoritesCoordinator(
    private val source: CloudLibrarySource,
    private val sessions: StateFlow<SessionData?>,
    private val scope: CoroutineScope,
    private val monotonicMs: () -> Long = { SystemClock.elapsedRealtime() }
) {
    private val mutableState = MutableStateFlow(CloudFavoritesState(userId = sessions.value?.userId))
    val state = mutableState.asStateFlow()
    private val mutex = Mutex()
    private var epoch = 0L
    private var active = false
    private var refreshJob: Job? = null
    private val mutations = mutableSetOf<Job>()
    private var snapshot: Snapshot? = null
    private var undoRecord: Undo? = null
    private var undoExpiry: Job? = null
    private data class Snapshot(val playlist: CloudPlaylist, val songs: List<CloudSong>)
    private data class Undo(val identity: SessionData, val epoch: Long, val playlist: CloudPlaylist,
                            val tracks: List<Track>, val expiresAtMs: Long)

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            sessions.map { it?.let { account -> account.userId to account.token } }.distinctUntilChanged()
                .drop(1).collect {
                    epoch++; refreshJob?.cancel(); mutations.toList().forEach(Job::cancel)
                    mutations.clear(); snapshot = null; dismissUndo()
                    mutableState.value = CloudFavoritesState(userId = sessions.value?.userId)
                    if (active && sessions.value != null) refresh()
                }
        }
    }

    fun visible(value: Boolean) {
        active = value
        if (!value) { refreshJob?.cancel(); return }
        if (sessions.value != null && (!state.value.loaded ||
                System.currentTimeMillis() - state.value.updatedAtMs >= REFRESH_INTERVAL_MS)) refresh()
    }

    fun refresh() {
        val identity = identityOrReport() ?: return
        if (refreshJob?.isActive == true) return
        val expected = epoch
        refreshJob = scope.launch {
            try {
                mutex.withLock {
                    current(identity, expected)
                    mutableState.update { it.copy(loading = true, error = null) }
                    load(identity, expected, refreshLists = true)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { report(identity, expected, error) }
            finally { if (matches(identity, expected)) mutableState.update { it.copy(loading = false) } }
        }
    }

    fun toggle(track: Track) = mutate(track, state.value.takeIf { it.loaded }?.let { !it.isLiked(track) })
    fun setLiked(track: Track, liked: Boolean) = mutate(track, liked)

    private fun mutate(track: Track, desired: Boolean?) {
        val identity = identityOrReport() ?: return
        if (!canFavorite(track) || track.id in state.value.pendingIds) return
        val expected = epoch
        mutableState.update { it.copy(pendingIds = it.pendingIds + track.id, error = null, message = null) }
        launchMutation {
            try {
                mutex.withLock {
                    dismissUndo()
                    val fresh = load(identity, expected)
                    val liked = desired ?: fresh.songs.none { sameKugouTrack(it.track, track) }
                    val removed = fresh.songs.filter { sameKugouTrack(it.track, track) }
                    ensureLiked(identity, expected, track, liked, fresh)
                    current(identity, expected)
                    if (!liked && removed.isNotEmpty()) offerUndo(identity, expected, fresh.playlist, removed.map { it.track })
                    mutableState.update { it.copy(message = if (liked) "已加入酷狗我喜欢" else "已从酷狗我喜欢移除") }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { report(identity, expected, error) }
            finally { if (matches(identity, expected)) mutableState.update { it.copy(pendingIds = it.pendingIds - track.id) } }
        }
    }

    fun addAll(tracks: List<Track>) {
        if (identityOrReport() != null) launchMutation { migrate(tracks) }
    }

    /** 按来源支持的批量大小提交；只对实际读回的歌曲逐首确认迁移。 */
    suspend fun migrate(tracks: List<Track>, onConfirmed: (Track) -> Unit = {}): CloudMigrationResult {
        val original = tracks.distinctBy { it.id }
        val allIds = original.mapTo(linkedSetOf()) { it.id }
        val confirmed = linkedSetOf<String>()
        val identity = identityOrReport() ?: return CloudMigrationResult(emptySet(), allIds, MusicError.AuthenticationExpired)
        val expected = epoch
        mutableState.update { it.copy(pendingIds = it.pendingIds + allIds, error = null, message = null) }
        try {
            mutex.withLock {
                dismissUndo()
                var fresh = load(identity, expected)
                for (batch in original.chunked(source.mutationBatchSize.coerceIn(1, 50))) {
                    current(identity, expected)
                    if (batch.any { !canFavorite(it) }) throw MusicFailure(MusicError.Unknown("部分歌曲不能加入酷狗云歌单"))
                    val missing = batch.filter { track -> fresh.songs.none { sameKugouTrack(it.track, track) } }
                    var writeFailure: Exception? = null
                    if (missing.isNotEmpty()) {
                        mutableState.update { it.copy(confirmationStatus = CloudConfirmationStatus.SUBMITTING) }
                        fresh = try {
                            publishSnapshot(identity, expected, Snapshot(fresh.playlist, source.addVerified(identity, fresh.playlist, missing)))
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (error: Exception) {
                            writeFailure = error
                            loadSongs(identity, expected, fresh.playlist)
                        }
                    }
                    for (track in batch) {
                        current(identity, expected)
                        if (fresh.songs.any { sameKugouTrack(it.track, track) }) {
                            onConfirmed(track); confirmed += track.id
                            mutableState.update { it.copy(message = "已确认 ${confirmed.size}/${original.size} 首云端喜欢") }
                        }
                    }
                    if (batch.any { it.id !in confirmed }) throw writeFailure ?: MusicFailure(MusicError.Unknown("部分歌曲尚未在酷狗确认，请刷新重试"))
                }
                mutableState.update { it.copy(message = "已同步 ${confirmed.size} 首到酷狗我喜欢") }
            }
            return CloudMigrationResult(confirmed, allIds - confirmed)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            report(identity, expected, error)
            return CloudMigrationResult(confirmed, allIds - confirmed, error.toMusicError())
        } finally { if (matches(identity, expected)) mutableState.update { it.copy(pendingIds = it.pendingIds - allIds) } }
    }

    fun undo() {
        val record = undoRecord ?: return
        if (!matches(record.identity, record.epoch) || monotonicMs() >= record.expiresAtMs) { dismissUndo(); return }
        val ids = record.tracks.mapTo(hashSetOf()) { it.id }
        mutableState.update { it.copy(undoAvailable = false, pendingIds = it.pendingIds + ids, error = null, message = null) }
        launchMutation {
            try {
                mutex.withLock {
                    current(record.identity, record.epoch)
                    val fresh = loadSongs(record.identity, record.epoch, record.playlist)
                    val missing = record.tracks.filter { track -> fresh.songs.none { sameKugouTrack(it.track, track) } }
                    if (missing.isNotEmpty()) publishSnapshot(record.identity, record.epoch,
                        Snapshot(record.playlist, source.addVerified(record.identity, record.playlist, missing)))
                    dismissUndo()
                    mutableState.update { it.copy(message = "已恢复酷狗喜欢") }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                report(record.identity, record.epoch, error)
                if (undoRecord === record && matches(record.identity, record.epoch) && monotonicMs() < record.expiresAtMs)
                    mutableState.update { it.copy(undoAvailable = true) }
            } finally { if (matches(record.identity, record.epoch)) mutableState.update { it.copy(pendingIds = it.pendingIds - ids) } }
        }
    }

    fun dismissUndo() {
        undoExpiry?.cancel(); undoExpiry = null; undoRecord = null
        mutableState.update { it.copy(undoAvailable = false, undoLabel = null, undoExpiresAtMs = 0) }
    }
    fun clearMessage() { mutableState.update { it.copy(message = null) } }

    private fun offerUndo(identity: SessionData, expected: Long, playlist: CloudPlaylist, tracks: List<Track>) {
        val record = Undo(identity, expected, playlist, tracks.distinctBy { it.id }, monotonicMs() + UNDO_MS)
        undoRecord = record
        mutableState.update { it.copy(undoAvailable = true, undoLabel = "撤销取消喜欢", undoExpiresAtMs = record.expiresAtMs) }
        undoExpiry?.cancel()
        undoExpiry = scope.launch { delay(UNDO_MS); if (undoRecord === record) dismissUndo() }
    }

    private suspend fun ensureLiked(identity: SessionData, expected: Long, track: Track, desired: Boolean, fresh: Snapshot): Snapshot {
        current(identity, expected)
        val matching = fresh.songs.filter { sameKugouTrack(it.track, track) }
        if (matching.isNotEmpty() == desired) return fresh
        mutableState.update { it.copy(confirmationStatus = CloudConfirmationStatus.SUBMITTING) }
        val songs = if (desired) source.addVerified(identity, fresh.playlist, listOf(track))
            else source.removeVerified(identity, fresh.playlist, matching)
        return publishSnapshot(identity, expected, Snapshot(fresh.playlist, songs))
    }

    private suspend fun load(identity: SessionData, expected: Long, refreshLists: Boolean = false): Snapshot {
        current(identity, expected)
        val cached = snapshot?.playlist.takeUnless { refreshLists }
        val playlist = cached ?: source.readPlaylists(identity).filter { it.isFavorites }.let { favorites ->
            current(identity, expected)
            if (favorites.size != 1) throw MusicFailure(MusicError.Unknown("未能确定酷狗“我喜欢”歌单，请先在手机酷狗检查后刷新"))
            favorites.single()
        }
        return loadSongs(identity, expected, playlist)
    }

    private suspend fun loadSongs(identity: SessionData, expected: Long, playlist: CloudPlaylist): Snapshot =
        publishSnapshot(identity, expected, Snapshot(playlist, source.readSongs(identity, playlist)))

    private suspend fun publishSnapshot(identity: SessionData, expected: Long, fresh: Snapshot): Snapshot {
        current(identity, expected)
        if (fresh.playlist.collection.ownerId != identity.userId || fresh.songs.any { it.track.origin != TrackOrigin.KUGOU })
            throw MusicFailure(MusicError.InvalidResponse)
        snapshot = fresh
        mutableState.update { it.copy(userId = identity.userId, collection = fresh.playlist.collection,
            tracks = fresh.songs.map { it.track }.distinctBy { it.id }, loaded = true, error = null,
            updatedAtMs = System.currentTimeMillis(), confirmationStatus = CloudConfirmationStatus.CONFIRMED) }
        return fresh
    }

    private fun identityOrReport(): SessionData? = sessions.value.also {
        if (it == null) mutableState.update { before -> before.copy(error = MusicError.AuthenticationExpired, message = "登录酷狗后即可使用云端喜欢") }
    }
    private fun canFavorite(track: Track) = track.origin == TrackOrigin.KUGOU && track.hash.matches(Regex("[a-fA-F0-9]{32}"))
    private fun matches(identity: SessionData, expected: Long) = epoch == expected && sessions.value?.let { it.userId == identity.userId && it.token == identity.token } == true
    private suspend fun current(identity: SessionData, expected: Long) {
        currentCoroutineContext().ensureActive()
        if (!matches(identity, expected)) throw CancellationException("酷狗账号已变化")
    }
    private fun report(identity: SessionData, expected: Long, error: Exception) {
        if (matches(identity, expected)) mutableState.update { it.copy(error = error.toMusicError(), message = null,
            confirmationStatus = if (it.confirmationStatus == CloudConfirmationStatus.SUBMITTING)
                CloudConfirmationStatus.AWAITING_CONFIRMATION else it.confirmationStatus) }
    }
    private fun launchMutation(block: suspend () -> Unit) {
        val job = scope.launch(start = CoroutineStart.LAZY) { block() }
        mutations += job; job.invokeOnCompletion { mutations -= job }; job.start()
    }
    companion object { private const val REFRESH_INTERVAL_MS = 60_000L; private const val UNDO_MS = 60_000L }
}
