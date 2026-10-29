package com.watchlisten.music.playback

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.watchlisten.music.data.CatalogGateway
import com.watchlisten.music.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class PlaybackSnapshot(
    val connected: Boolean = false, val queue: List<PlaybackEntry> = emptyList(), val index: Int = 0,
    val playing: Boolean = false, val buffering: Boolean = false, val positionMs: Long = 0,
    val durationMs: Long = 0, val repeat: Int = Player.REPEAT_MODE_OFF, val shuffle: Boolean = false,
    val error: MusicError? = null, val skipped: String? = null,
    val seekable: Boolean = false, val canNext: Boolean = false, val canPrevious: Boolean = false,
    val canUndoQueueEdit: Boolean = false
) { val current: PlaybackEntry? get() = queue.getOrNull(index) }

/**
 * 取源失败重试上限：同一实例只重新解析一次来源，其余直接跳过；
 * 连续跳过的次数达到队列长度或 [MAX_CONSECUTIVE_SKIPS] 时停止，避免无限取源。
 */
const val MAX_CONSECUTIVE_SKIPS = 8

data class CollectionQueueToken internal constructor(internal val generation: Long, internal val anchorId: String)

/** 纯逻辑的失败恢复决策，便于脱离播放器单独验证。 */
object PlaybackRecovery {
    enum class Decision { RefreshSource, SkipNext, GiveUp }

    fun decide(kind: MusicError, refreshUsed: Boolean, consecutiveSkips: Int,
               queueSize: Int, hasNext: Boolean): Decision {
        if (kind == MusicError.MobileDataBlocked || kind == MusicError.ConsentRequired) return Decision.GiveUp
        val permanent = kind == MusicError.TrackUnavailable || kind == MusicError.PermissionDenied ||
            kind == MusicError.InvalidResponse || kind == MusicError.AuthenticationExpired
        if (!permanent && !refreshUsed) return Decision.RefreshSource
        val limit = minOf(queueSize, MAX_CONSECUTIVE_SKIPS).coerceAtLeast(1)
        return if (consecutiveSkips >= limit || !hasNext) Decision.GiveUp else Decision.SkipNext
    }
}

class PlaybackController(private val context: Context, private val scope: CoroutineScope,
                         private val gateway: CatalogGateway) {
    private var controller: MediaController? = null
    private var connecting = false
    private val mutableState = MutableStateFlow(PlaybackSnapshot())
    val state = mutableState.asStateFlow()
    private var ticker: Job? = null
    private var refreshUsed = false
    private var consecutiveSkips = 0
    private var queueGeneration = 0L
    private var replacementGeneration = 0L
    private var cachedQueue: List<PlaybackEntry> = emptyList()
    private var cachedQueueRemote = false
    private var queueDirty = true
    private var queueUndo: QueueUndo? = null
    private var sleepVolumeMultiplier = 1f
    private data class QueueUndo(val items: List<MediaItem>, val currentId: String?, val positionMs: Long,
                                 val playWhenReady: Boolean, val generation: Long)
    internal var beforeLocalQueueEdit: (() -> Unit)? = null

    private fun prepareLocalQueue() {
        if (controller?.currentMediaItem?.entry()?.track?.isRemote == true) beforeLocalQueueEdit?.invoke()
    }

    fun connect() {
        if (controller != null || connecting) return
        connecting = true
        val future = MediaController.Builder(context, SessionToken(context, ComponentName(context, MusicService::class.java)))
            .setListener(object : MediaController.Listener {
                override fun onDisconnected(controller: MediaController) {
                    this@PlaybackController.controller = null
                    connecting = false
                    mutableState.update { it.copy(connected = false) }
                }
            }).buildAsync()
        future.addListener({
            connecting = false
            runCatching { future.get() }.onSuccess {
                controller = it
                it.volume = sleepVolumeMultiplier
                queueDirty = true
                it.addListener(listener)
                publish()
            }.onFailure { mutableState.update { it.copy(error = MusicError.PlaybackFailure) } }
        }, ContextCompat.getMainExecutor(context))
    }

    private val listener = object : Player.Listener {
        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
            if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) queueDirty = true
        }
        override fun onEvents(player: Player, events: Player.Events) {
            publish()
        }
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) {
                consecutiveSkips = 0
                refreshUsed = false
                mutableState.update { it.copy(error = null) }
            }
            publish()
        }
        override fun onPlayerError(error: PlaybackException) {
            val player = controller
            val entry = player?.currentMediaItem?.entry()
            if (player == null || entry == null) {
                mutableState.update { it.copy(error = error.musicError(), buffering = false) }
            } else recover(player, entry, if (entry.track.isRemote) error.remoteMusicError() else error.musicError())
            publish()
        }
    }

    /**
     * 来源可能过期时只重新解析一次并回到原进度；确认不可用则跳到队列下一首。
     * 连续失败达上限后停止播放并保留原因，不再循环取源。
     */
    private fun recover(player: MediaController, entry: PlaybackEntry, kind: MusicError) {
        if (entry.track.isRemote) {
            player.pause()
            mutableState.update { it.copy(error = kind, buffering = false) }
            return
        }
        val decision = PlaybackRecovery.decide(kind, refreshUsed, consecutiveSkips + 1,
            player.mediaItemCount, player.hasNextMediaItem())
        if (decision == PlaybackRecovery.Decision.RefreshSource) {
            refreshUsed = true
            val resume = player.currentPosition.coerceAtLeast(0)
            gateway.invalidate(entry.track)
            mutableState.update { it.copy(error = null, skipped = null, buffering = true) }
            player.seekTo(resume)
            player.prepare()
            player.play()
            return
        }
        consecutiveSkips++
        if (decision == PlaybackRecovery.Decision.GiveUp) {
            player.pause()
            mutableState.update { it.copy(error = kind, buffering = false, skipped = entry.track.title) }
            return
        }
        mutableState.update { it.copy(error = null, skipped = entry.track.title, buffering = true) }
        player.seekToNextMediaItem()
        player.prepare()
        player.play()
    }

    suspend fun ready(): MediaController {
        connect()
        withTimeout(7000) { state.first { it.connected } }
        return controller ?: throw MusicFailure(MusicError.PlaybackFailure)
    }

    suspend fun load(entries: List<PlaybackEntry>, index: Int, startPositionMs: Long = 0) {
        val generation = ++queueGeneration
        replacementGeneration++
        queueUndo = null
        val player = ready()
        currentCoroutineContext().ensureActive()
        if (generation != queueGeneration) throw CancellationException("Queue replaced")
        prepareLocalQueue()
        player.apply {
            setMediaItems(entries.map { it.mediaItem() }, index, startPositionMs.coerceAtLeast(0))
            prepare()
            play()
        }
        resetRecovery()
        mutableState.update { it.copy(error = null, skipped = null) }
        queueDirty = true
        publish()
    }

    suspend fun beginCollection(tracks: List<Track>): CollectionQueueToken {
        require(tracks.isNotEmpty())
        val entries = tracks.map { PlaybackEntry(it) }
        load(entries, 0)
        return CollectionQueueToken(queueGeneration, entries.first().instanceId)
    }

    suspend fun appendCollection(token: CollectionQueueToken, tracks: List<Track>): Boolean {
        currentCoroutineContext().ensureActive()
        val player = controller ?: return false
        if (token.generation != queueGeneration ||
            (0 until player.mediaItemCount).none { player.getMediaItemAt(it).mediaId == token.anchorId }) return false
        if (tracks.isNotEmpty()) {
            queueUndo = null
            player.addMediaItems(tracks.map { PlaybackEntry(it).mediaItem() })
            queueDirty = true
            publish()
        }
        return true
    }

    private fun resetRecovery() {
        refreshUsed = false
        consecutiveSkips = 0
    }

    fun visible(visible: Boolean) {
        ticker?.cancel()
        if (!visible) return
        connect()
        ticker = scope.launch { while (isActive) { publish(); delay(500) } }
    }

    private fun publish() {
        val player = controller ?: return
        if (queueDirty || cachedQueue.size != player.mediaItemCount) {
            cachedQueue = (0 until player.mediaItemCount).mapNotNull { player.getMediaItemAt(it).entry() }
            cachedQueueRemote = cachedQueue.any { it.track.isRemote }
            queueDirty = false
        }
        val queue = cachedQueue
        if (cachedQueueRemote) queueUndo = null
        mutableState.value = PlaybackSnapshot(true, queue, player.currentMediaItemIndex.coerceAtLeast(0),
            player.isPlaying, player.playbackState == Player.STATE_BUFFERING,
            player.currentPosition.coerceAtLeast(0), player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0,
            player.repeatMode, player.shuffleModeEnabled, player.playerError?.let {
                if (queue.getOrNull(player.currentMediaItemIndex)?.track?.isRemote == true)
                    it.remoteMusicError() else it.musicError()
            },
            mutableState.value.skipped,
            player.isCurrentMediaItemSeekable && player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM),
            player.hasNextMediaItem() && player.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM),
            (player.currentPosition > 3000 && player.isCurrentMediaItemSeekable &&
                player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)) ||
                (player.hasPreviousMediaItem() && player.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)),
            queueUndo?.generation == queueGeneration)
    }

    fun positionNow() = controller?.currentPosition?.coerceAtLeast(0) ?: state.value.positionMs
    fun forgetLocalTrack(trackId: String) {
        val player = controller ?: return
        val indexes = (0 until player.mediaItemCount).filter {
            player.getMediaItemAt(it).entry()?.track?.let { track -> track.isLocal && track.id == trackId } == true
        }
        if (indexes.isEmpty()) return
        queueGeneration++; replacementGeneration++; queueUndo = null
        indexes.asReversed().forEach(player::removeMediaItem)
        queueDirty = true
        publish()
    }
    fun setSleepVolumeMultiplier(value: Float) {
        sleepVolumeMultiplier = value.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 1f
        controller?.volume = sleepVolumeMultiplier
    }
    fun retryRemote() { controller?.let { if (it.currentMediaItem?.entry()?.track?.isRemote == true) { it.prepare(); it.play() } }; publish() }
    fun toggle() { controller?.let { if (it.playWhenReady) it.pause() else { if (it.playbackState == Player.STATE_IDLE) it.prepare(); it.play() } } }
    fun pause() { controller?.pause() }
    fun stop() { queueGeneration++; replacementGeneration++; queueUndo = null; controller?.run { stop(); clearMediaItems() }; queueDirty = true; resetRecovery(); publish() }
    fun next() { controller?.let { if (it.hasNextMediaItem() && it.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)) it.seekToNextMediaItem() }; publish() }
    fun previous() { controller?.let {
        if (it.currentPosition > 3000 && it.isCurrentMediaItemSeekable && it.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)) it.seekTo(0)
        else if (it.hasPreviousMediaItem() && it.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)) it.seekToPreviousMediaItem()
    }; publish() }
    fun seek(position: Long, expectedEntryId: String? = state.value.current?.instanceId) {
        val player = controller ?: return
        val target = SeekBounds(player.currentMediaItem?.mediaId, player.duration,
            player.isCurrentMediaItemSeekable && player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM))
            .resolveTarget(position, expectedEntryId) ?: return
        player.seekTo(target)
        publish()
    }
    fun select(index: Int) { queueUndo = null; controller?.run { if (index in 0 until mediaItemCount) { seekToDefaultPosition(index); prepare(); play() } }; resetRecovery(); publish() }
    fun add(track: Track, next: Boolean = false, onComplete: (MusicError?) -> Unit = {}) {
        queueGeneration++
        val generation = replacementGeneration
        queueUndo = null
        scope.launch {
            try {
                val player = ready()
                ensureActive()
                if (generation != replacementGeneration) {
                    onComplete(MusicError.Unknown("操作已停止，请重试"))
                    return@launch
                }
                prepareLocalQueue()
                val item = PlaybackEntry(track).mediaItem()
                if (next && player.mediaItemCount > 0) player.addMediaItem(player.currentMediaItemIndex + 1, item)
                else player.addMediaItem(item)
                queueDirty = true
                publish()
                onComplete(null)
            } catch (timeout: TimeoutCancellationException) {
                if (generation == replacementGeneration) mutableState.update { it.copy(error = MusicError.SlowNetwork) }
                onComplete(MusicError.SlowNetwork)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (generation == replacementGeneration) mutableState.update { it.copy(error = error.musicError()) }
                onComplete(error.musicError())
            }
        }
    }
    suspend fun addAll(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        queueGeneration++
        val generation = replacementGeneration
        queueUndo = null
        val player = ready()
        currentCoroutineContext().ensureActive()
        if (generation != replacementGeneration) throw MusicFailure(MusicError.Unknown("操作已停止，请重试"))
        prepareLocalQueue()
        player.addMediaItems(tracks.map { PlaybackEntry(it).mediaItem() })
        queueDirty = true
        publish()
    }
    fun remove(index: Int) {
        val player = controller ?: return
        if (index !in 0 until player.mediaItemCount || player.currentMediaItem?.entry()?.track?.isRemote == true) return
        rememberQueueEdit(player)
        player.removeMediaItem(index)
        queueDirty = true
        publish()
    }
    fun move(index: Int, destination: Int) { controller?.let {
        if (index in 0 until it.mediaItemCount && destination in 0 until it.mediaItemCount && index != destination) {
            queueGeneration++; queueUndo = null; it.moveMediaItem(index, destination); queueDirty = true
        }
    }; publish() }
    fun clearUpcoming() { controller?.let { player ->
        val index = player.currentMediaItemIndex
        if (index !in 0 until player.mediaItemCount || player.mediaItemCount <= 1 || player.currentMediaItem?.entry()?.track?.isRemote == true) return@let
        rememberQueueEdit(player)
        if (index + 1 < player.mediaItemCount) player.removeMediaItems(index + 1, player.mediaItemCount)
        if (index > 0) player.removeMediaItems(0, index)
        queueDirty = true
    }; publish() }

    private fun rememberQueueEdit(player: MediaController) {
        queueUndo = QueueUndo((0 until player.mediaItemCount).map(player::getMediaItemAt),
            player.currentMediaItem?.mediaId, player.currentPosition.coerceAtLeast(0), player.playWhenReady, ++queueGeneration)
    }

    fun undoLastQueueEdit() {
        val player = controller ?: return
        val undo = queueUndo?.takeIf { it.generation == queueGeneration } ?: return
        queueUndo = null
        val remainingIds = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }
        val remainingSet = remainingIds.toSet()
        if (undo.items.filter { it.mediaId in remainingSet }.map { it.mediaId } != remainingIds) {
            publish()
            return
        }
        val activeId = player.currentMediaItem?.mediaId
        var index = 0
        while (index < undo.items.size) {
            if (index < player.mediaItemCount && player.getMediaItemAt(index).mediaId == undo.items[index].mediaId) {
                index++
                continue
            }
            val nextPresent = if (index < player.mediaItemCount) player.getMediaItemAt(index).mediaId else null
            var end = index + 1
            while (end < undo.items.size && undo.items[end].mediaId != nextPresent) end++
            player.addMediaItems(index, undo.items.subList(index, end))
            index = end
        }
        if (activeId != undo.currentId) {
            val originalIndex = undo.items.indexOfFirst { it.mediaId == undo.currentId }
            if (originalIndex >= 0) {
                player.seekTo(originalIndex, undo.positionMs)
                player.prepare()
                player.playWhenReady = undo.playWhenReady
            }
        }
        queueDirty = true
        publish()
    }
    fun cycleMode() { controller?.let { player ->
        val next = nextPlaybackMode(player.repeatMode, player.shuffleModeEnabled)
        player.repeatMode = next.first
        player.shuffleModeEnabled = next.second
    }; publish() }
    fun clearSkipped() { mutableState.update { it.copy(skipped = null) } }
}

internal fun nextPlaybackMode(repeat: Int, shuffle: Boolean): Pair<Int, Boolean> = when {
    shuffle -> Player.REPEAT_MODE_OFF to false
    repeat == Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_ALL to true
    repeat == Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE to false
    else -> Player.REPEAT_MODE_ALL to false
}

fun Throwable.musicError(): MusicError {
    var current: Throwable? = this
    repeat(8) {
        if (current is MusicFailure) return (current as MusicFailure).error
        current = current?.cause
    }
    return MusicError.PlaybackFailure
}

internal fun Throwable.remoteMusicError(): MusicError {
    var failure: Throwable? = this
    repeat(16) {
        val current = failure ?: return@repeat
        if (current is CastStreamException) return MusicError.Unknown(
            if (current.kind.retryable) "网络恢复失败，请检查 Wi-Fi 后重新投放" else current.kind.message)
        failure = current.cause?.takeUnless { it === current }
    }
    return MusicError.Unknown("投放播放失败，请在手机重新选择歌曲")
}
