package com.watchlisten.music.domain

import android.os.SystemClock
import com.watchlisten.music.data.*
import com.watchlisten.music.model.*
import com.watchlisten.music.playback.PlaybackController
import com.watchlisten.music.playback.PlaybackSnapshot
import com.watchlisten.music.playback.PlaybackWatchdog
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import watchmusic.lyrics.LyricsDocument

data class MusicState(
    val query: String = "", val tracks: List<Track> = emptyList(), val total: Int = 0,
    val searching: Boolean = false, val searchError: MusicError? = null, val page: Int = 1,
    val hasMore: Boolean = false, val playRequest: PlaybackRequest? = null,
    val preparing: Track? = null, val error: MusicError? = null, val preview: Boolean = false, val previewKnown: Boolean = false,
    val lyrics: LyricsDocument? = null, val lyricsEntry: String? = null, val lyricsLoading: Boolean = false,
    val qr: LoginQr? = null, val loginMessage: String = "用手机酷狗扫码登录", val loginBusy: Boolean = false,
    val loginPhase: LoginPhase = LoginPhase.IDLE, val qrRemainingSeconds: Int = 0,
    val profileLoading: Boolean = false, val profileError: String? = null,
    val access: Map<String, ListeningAccess> = emptyMap(),
    val timerMinutes: Int = 0, val timerEndsAtMs: Long = 0,
    val timerDeadlineElapsedMs: Long = 0, val timerFadeOut: Boolean = false
)

class MusicCoordinator(
    private val gateway: CatalogGateway, private val sessions: SessionStore, private val library: LibraryStore,
    val playback: PlaybackController, private val scope: CoroutineScope,
    private val context: android.content.Context,
    private val localLyrics: LocalLyricsRepository? = null
) {
    private val mutableState = MutableStateFlow(MusicState())
    val state = mutableState.asStateFlow()
    private val visible = MutableStateFlow(false)
    private val mutableNetwork = MutableStateFlow(detectNetworkKind(context))
    /** 当前传输类型；流量策略与界面提示都读这里。 */
    val network = mutableNetwork.asStateFlow()
    private var searchJob: Job? = null
    private var playJob: Job? = null
    private var loginJob: Job? = null
    private var profileJob: Job? = null
    private var loginGeneration = 0L
    private var timerJob: Job? = null
    private var accountVisible = false
    private var networkJob: Job? = null
    private val timerStore = SleepTimerStore(context)

    /** 播放超时自愈：先重试取源，仍失败才重启应用。 */
    val watchdog = PlaybackWatchdog(context,
        onRetrySource = {
            withContext(Dispatchers.Main.immediate) {
                val request = state.value.playRequest?.copy(failed = null)
                    ?: state.value.retryRequest(playback.state.value)
                if (request != null) {
                    playJob?.cancel()
                    gateway.invalidate(request.track)
                    startPlayback(request)
                }
                // 重试结果交给正常播放流程展示，策略限制不应导致进程重启。
                true
            }
        },
        onRestartApp = {
            val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            if (intent != null) {
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                Runtime.getRuntime().exit(0)
            }
        })

    init {
        timerStore.restore()?.let(::resumeTimer)
        scope.launch {
            gateway.sourceAccess.collect { access -> mutableState.update { it.copy(access = access) } }
        }
        scope.launch {
            sessions.session.map { it?.let { session -> session.userId to session.token } }
                .distinctUntilChanged().drop(1).collect { identity ->
                    gateway.invalidate()
                    profileJob?.cancel()
                    profileJob = null
                    mutableState.update { it.copy(profileLoading = false, profileError = null) }
                    if (identity == null) {
                        if (accountVisible) startLogin(forceNew = true)
                    } else if (visible.value || accountVisible) refreshProfileIfNeeded()
                }
        }
        scope.launch {
            combine(playback.state.map { it.current }.distinctUntilChanged(), visible,
                localLyrics?.revision ?: flowOf(0L)) { entry, active, revision ->
                Triple(entry, active, if (entry?.track?.isLocal == true) revision else 0L)
            }.distinctUntilChanged()
                .collectLatest { (entry, active, _) ->
                    if (!active || entry == null) return@collectLatest
                    if (entry.track.isRemote) {
                        mutableState.update { it.copy(lyrics = null, lyricsEntry = entry.instanceId, lyricsLoading = false) }
                        return@collectLatest
                    }
                    mutableState.update { it.copy(lyrics = null, lyricsEntry = entry.instanceId, lyricsLoading = true) }
                    try {
                        val document = if (entry.track.isLocal) localLyrics?.load(entry.track) else gateway.lyrics(entry.track)
                        mutableState.update { it.copy(lyrics = document, lyricsLoading = false) }
                    } catch (error: CancellationException) { throw error }
                    catch (error: Exception) { mutableState.update { it.copy(lyrics = null, lyricsLoading = false) }
                    }
                }
        }
        scope.launch {
            combine(playback.state.map { it.current?.track?.id }.distinctUntilChanged(),
                gateway.sourceAccess) { id, access -> access[id] }
                .distinctUntilChanged()
                .collect { preview -> mutableState.update {
                    it.copy(preview = preview == ListeningAccess.PREVIEW,
                        previewKnown = preview != null && preview != ListeningAccess.UNKNOWN)
                } }
        }
        // 旧歌曲继续播放时，不能覆盖刚点播的新歌曲的失败原因。
        scope.launch {
            playback.state.map { it.current?.instanceId to it.error }
                .distinctUntilChanged()
                .filter { (id, error) -> id != null && error == null }
                .collect { mutableState.update { if (it.playRequest == null) it.copy(error = null) else it } }
        }
        // 自愈计时：开始准备播放时 arm；准备结束（无论成功失败）即 cancel。
        // 失败路径由 PlaybackController 的失败恢复负责重试或跳过，这里只兜住卡死。
        scope.launch {
            mutableState.map { it.preparing?.id }
                .distinctUntilChanged()
                .collect { preparing -> if (preparing != null) watchdog.arm() else watchdog.cancel() }
        }
        // 切歌后补预取新的后续歌曲，避免在播放线程上等取源。
        scope.launch {
            combine(playback.state.map { snapshot -> snapshot.index to snapshot.queue }, visible) { queue, active -> queue to active }
                .distinctUntilChanged()
                .collectLatest { (position, active) ->
                    val (index, queue) = position
                    if (!active || queue.isEmpty() || index < 0) return@collectLatest
                    queue.drop(index + 1).filterNot { it.track.isRemote || it.track.isLocal }.take(PREFETCH_COUNT).forEach { gateway.prefetch(it.track) }
                }
        }
        // 音质偏好变化后丢弃已缓存来源，让下一次播放按新偏好重新取源。
        scope.launch {
            library.quality.drop(1).collect { invalidateSourcesAndResume() }
        }
    }

    fun visible(value: Boolean) {
        visible.value = value
        if (!value) {
            searchJob?.cancel(); loginJob?.cancel(); profileJob?.cancel(); networkJob?.cancel()
            networkJob = null
            mutableState.update { it.copy(searching = false, loginBusy = false, profileLoading = false) }
        } else {
            watchNetwork()
            refreshProfileIfNeeded()
        }
    }

    /** 只在界面可见时低频检查传输类型，息屏与后台不做轮询。 */
    private fun watchNetwork() {
        if (networkJob?.isActive == true) return
        networkJob = scope.launch {
            mutableNetwork.value = detectNetworkKind(context)
            while (isActive) {
                delay(NETWORK_POLL_MS)
                val kind = detectNetworkKind(context)
                if (kind != mutableNetwork.value) mutableNetwork.value = kind
            }
        }
    }

    fun search(query: String, nextPage: Boolean = false) {
        val keyword = query.trim().take(100)
        if (keyword.isEmpty()) return
        val before = state.value
        if (nextPage && !before.canLoadNextPage(keyword)) return
        searchJob?.cancel()
        val page = if (nextPage) before.page + 1 else 1
        mutableState.update { it.copy(query = keyword, searching = true, searchError = null,
            tracks = if (nextPage) it.tracks else emptyList(),
            page = if (nextPage) it.page else 1, total = if (nextPage) it.total else 0,
            hasMore = if (nextPage) it.hasMore else false) }
        searchJob = scope.launch {
            try {
                val found = gateway.search(keyword, page)
                ensureActive()
                if (page == 1) library.rememberSearch(keyword)
                mutableState.update { it.withSearchPage(found, page, nextPage) }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { mutableState.update { it.copy(searching = false, searchError = error.toMusicError()) } }
        }
    }

    fun play(track: Track, tracks: List<Track> = listOf(track)) {
        if (track.isRemote) return
        startPlayback(PlaybackRequest.create(track, tracks))
    }

    /** 整个集合先交给媒体会话，首曲不可用时仍可按已有恢复规则跳过，不丢掉后续队列。 */
    fun playCollection(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        startPlayback(PlaybackRequest.collection(tracks))
    }

    suspend fun startPagedCollection(tracks: List<Track>): PagedCollectionPlayback {
        playJob?.cancel()
        mutableState.update { it.copy(playRequest = null, preparing = null, error = null) }
        val token = playback.beginCollection(tracks)
        return PagedCollectionPlayback { batch -> playback.appendCollection(token, batch) }
    }

    fun retryPlayback() {
        if (playback.state.value.current?.track?.isRemote == true) { playback.retryRemote(); return }
        val request = state.value.retryRequest(playback.state.value) ?: return
        gateway.invalidate(request.track)
        startPlayback(request)
    }

    private fun startPlayback(request: PlaybackRequest) {
        playJob?.cancel()
        mutableState.update { it.copy(playRequest = request, preparing = request.track, error = null) }
        playJob = scope.launch {
            try {
                if (request.resolveBeforeLoad) gateway.source(request.track)
                ensureActive()
                playback.load(request.queue, request.index, request.positionMs)
                finishRequest(request)
            } catch (error: TimeoutCancellationException) {
                failRequest(request, MusicError.SlowNetwork)
            } catch (error: CancellationException) {
                finishRequest(request)
                throw error
            } catch (error: Exception) {
                failRequest(request, error.toMusicError())
            }
        }
    }

    /** 投放使用同一播放器，先取消尚未提交的普通点播请求。 */
    fun beginRemotePlayback() {
        playJob?.cancel()
        mutableState.update { it.copy(playRequest = null, preparing = null, error = null) }
    }

    private fun invalidateSourcesAndResume() {
        val pending = state.value.playRequest?.takeIf { it.failed == null }
        gateway.invalidate()
        // 新身份避免旧任务的取消回调清掉使用新音质或新会话的请求。
        if (pending != null) startPlayback(pending.copy())
    }

    private fun finishRequest(request: PlaybackRequest) {
        mutableState.update { if (it.playRequest === request)
            it.copy(playRequest = null, preparing = null, error = null) else it }
    }

    private fun failRequest(request: PlaybackRequest, error: MusicError) {
        mutableState.update { if (it.playRequest === request)
            it.copy(playRequest = request.copy(failed = error), preparing = null, error = error) else it }
    }

    fun accountActive(value: Boolean) {
        accountVisible = value
        if (!value) {
            loginGeneration++; loginJob?.cancel()
            mutableState.update { it.copy(loginBusy = false) }
        } else if (sessions.session.value == null) startLogin()
        else refreshProfile()
    }

    fun startLogin(forceNew: Boolean = false) {
        if (!accountVisible || sessions.session.value != null) return
        loginJob?.cancel()
        val generation = ++loginGeneration
        val revision = sessions.revision
        mutableState.update { it.copy(qr = if (forceNew) null else it.qr, loginBusy = true,
            loginPhase = LoginPhase.LOADING, loginMessage = "正在获取二维码…", qrRemainingSeconds = 0) }
        loginJob = scope.launch {
            try {
                var previous = state.value.qr?.takeUnless { forceNew }
                var earlyExpiries = 0
                while (accountVisible && generation == loginGeneration) {
                    val qr = previous?.takeIf { qrSecondsRemaining(it) > 10 } ?: run {
                        mutableState.update { it.copy(qr = null, loginBusy = true,
                            loginPhase = LoginPhase.LOADING, loginMessage = "正在获取二维码…") }
                        gateway.createQr()
                    }
                    previous = null
                    ensureActive()
                    mutableState.update { it.copy(qr = qr, loginBusy = false, loginPhase = LoginPhase.WAITING,
                        qrRemainingSeconds = qrSecondsRemaining(qr), loginMessage = "用手机酷狗扫一扫，并确认授权") }
                    var nextPollAt = SystemClock.elapsedRealtime() + 3000
                    while (accountVisible && qrSecondsRemaining(qr) > 0) {
                        delay(1000)
                        mutableState.update { it.copy(qrRemainingSeconds = qrSecondsRemaining(qr)) }
                        if (SystemClock.elapsedRealtime() < nextPollAt) continue
                        val result = gateway.checkQr(qr)
                        ensureActive()
                        if (generation != loginGeneration) return@launch
                        nextPollAt = SystemClock.elapsedRealtime() + 3000
                        if (result.phase == LoginPhase.EXPIRED) break
                        val session = result.session
                        if (result.phase == LoginPhase.CONFIRMED && session != null) {
                            val saved = withContext(Dispatchers.IO) { sessions.saveIfRevisionMatches(session, revision) }
                            if (!saved) return@launch
                            invalidateSourcesAndResume()
                            mutableState.update { it.copy(qr = null, loginBusy = false, loginPhase = LoginPhase.CONFIRMED,
                                qrRemainingSeconds = 0, loginMessage = "登录成功，正在同步资料") }
                            refreshProfile()
                            return@launch
                        }
                        mutableState.update { it.copy(loginPhase = result.phase,
                            loginMessage = if (result.phase == LoginPhase.SCANNED) "已扫码，请在手机上确认登录" else "用手机酷狗扫一扫，并确认授权") }
                    }
                    if (qrSecondsRemaining(qr) > 120) earlyExpiries++ else earlyExpiries = 0
                    if (earlyExpiries >= 2) throw MusicFailure(MusicError.Unknown("二维码暂时不可用，请点按刷新"))
                    mutableState.update { it.copy(qr = null, loginPhase = LoginPhase.EXPIRED,
                        qrRemainingSeconds = 0, loginMessage = "二维码已过期，正在自动更新…") }
                    delay(1500)
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { mutableState.update { it.copy(qr = null, qrRemainingSeconds = 0,
                loginBusy = false, loginPhase = LoginPhase.ERROR, loginMessage = error.toMusicError().message()) } }
        }
    }

    /** 首页和个人中心共享资料；只在前台进入或身份变化时补取缺失／过期资料。 */
    private fun refreshProfileIfNeeded() {
        val identity = sessions.session.value ?: return
        val updated = identity.profile?.updatedAtMs ?: 0L
        val now = System.currentTimeMillis()
        if (updated <= 0 || updated > now || now - updated >= PROFILE_REFRESH_INTERVAL_MS) refreshProfile()
    }

    fun refreshProfile() {
        val identity = sessions.session.value ?: return
        if (profileJob?.isActive == true) return
        mutableState.update { it.copy(profileLoading = true, profileError = null) }
        profileJob = scope.launch {
            try {
                val result = gateway.profile(identity)
                ensureActive()
                val saved = withContext(Dispatchers.IO) { sessions.updateProfile(identity, result.profile) }
                if (saved) mutableState.update { it.copy(profileLoading = false, profileError = result.warning) }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                val current = sessions.session.value
                if (current?.userId == identity.userId && current.token == identity.token)
                    mutableState.update { it.copy(profileLoading = false, profileError = error.toMusicError().message()) }
            }
        }
    }

    fun logout(): Boolean {
        loginGeneration++; loginJob?.cancel(); profileJob?.cancel(); playJob?.cancel(); searchJob?.cancel()
        profileJob = null
        playback.stop(); gateway.invalidate()
        mutableState.update { it.copy(qr = null, loginMessage = "已退出登录", loginPhase = LoginPhase.IDLE,
            qrRemainingSeconds = 0, loginBusy = false, profileLoading = false, profileError = null,
            searching = false, error = null, preparing = null, playRequest = null) }
        return try { sessions.clear(); true }
        catch (error: Exception) {
            mutableState.update { it.copy(profileError = "退出登录未能保存，请重试",
                loginMessage = "退出登录未能保存，请重试") }
            false
        }
    }

    fun sleepAfter(minutes: Int, fadeOut: Boolean = state.value.timerFadeOut) {
        timerJob?.cancel()
        playback.setSleepVolumeMultiplier(1f)
        val value = if (minutes == SLEEP_AFTER_TRACK) minutes else minutes.coerceIn(0, 180)
        resumeTimer(timerStore.save(value, fadeOut))
    }

    fun setTimerFadeOut(enabled: Boolean) {
        timerStore.setFade(enabled)
        mutableState.update { it.copy(timerFadeOut = enabled) }
        if (!enabled) playback.setSleepVolumeMultiplier(1f)
    }

    private fun resumeTimer(spec: SleepTimerSpec) {
        mutableState.update { it.copy(timerMinutes = spec.minutes, timerEndsAtMs = spec.deadlineWallMs,
            timerDeadlineElapsedMs = spec.deadlineElapsedMs, timerFadeOut = spec.fadeOut) }
        if (spec.minutes <= 0) return
        timerJob = scope.launch {
            while (isActive) {
                val remaining = spec.deadlineElapsedMs - SystemClock.elapsedRealtime()
                if (remaining <= 0) break
                val fade = state.value.timerFadeOut && remaining < 30_000
                playback.setSleepVolumeMultiplier(if (fade) remaining / 30_000f else 1f)
                delay(minOf(remaining, 1000))
            }
            playback.pause()
            finishTimer()
        }
    }

    private fun finishTimer() {
        timerStore.clear()
        playback.setSleepVolumeMultiplier(1f)
        mutableState.update { it.copy(timerMinutes = 0, timerEndsAtMs = 0, timerDeadlineElapsedMs = 0) }
    }

    /** 本曲结束定时在曲目切换时由服务回调触发，避免高频轮询。 */
    fun sleepTimerExpired() {
        if (state.value.timerMinutes != SLEEP_AFTER_TRACK) return
        timerJob?.cancel()
        playback.pause()
        finishTimer()
    }

    fun clearTimer() { sleepAfter(0) }

    fun stop() {
        playJob?.cancel()
        playback.stop()
        mutableState.update { it.copy(preparing = null, playRequest = null, error = null) }
    }
    fun clearError() { mutableState.update { it.copy(error = null) } }
}

fun Throwable.toMusicError(): MusicError = (this as? MusicFailure)?.error ?: when (this) {
    is TimeoutCancellationException -> MusicError.SlowNetwork
    else -> MusicError.Unknown()
}

const val SLEEP_AFTER_TRACK = -1

private const val PROFILE_REFRESH_INTERVAL_MS = 5 * 60 * 1000L

/** 预取后续歌曲数量：足以覆盖跳过与切歌，又不至于在手表上并发过多请求。 */
private const val PREFETCH_COUNT = 2

/** 传输类型检查间隔；只在界面可见时运行。 */
private const val NETWORK_POLL_MS = 5000L

private fun qrSecondsRemaining(qr: LoginQr): Int =
    ((180000 - (SystemClock.elapsedRealtime() - qr.createdAtMs)).coerceAtLeast(0) / 1000).toInt()


/** 保留用户点歌的队列与实例，失败后重试仍指向该请求。 */
data class PlaybackRequest(
    val track: Track, val queue: List<PlaybackEntry>, val index: Int,
    val failed: MusicError? = null, val positionMs: Long = 0, val resolveBeforeLoad: Boolean = true
) {
    companion object {
        fun create(track: Track, tracks: List<Track>): PlaybackRequest {
            val selected = if (tracks.any { it.id == track.id }) tracks else listOf(track) + tracks
            val queue = selected.map { PlaybackEntry(it) }
            return PlaybackRequest(track, queue, queue.indexOfFirst { it.track.id == track.id })
        }
        fun collection(tracks: List<Track>): PlaybackRequest = create(tracks.first(), tracks).copy(resolveBeforeLoad = false)
    }
}

internal fun MusicState.retryRequest(snapshot: PlaybackSnapshot): PlaybackRequest? {
    playRequest?.let { return it.copy(failed = null) }
    val current = snapshot.current ?: return null
    return PlaybackRequest(current.track, snapshot.queue, snapshot.index, positionMs = snapshot.positionMs)
}

internal fun MusicState.canLoadNextPage(keyword: String): Boolean =
    keyword == query && hasMore && !searching

internal fun MusicState.withSearchPage(found: SearchPage, page: Int, append: Boolean): MusicState {
    val previous = if (append) tracks else emptyList()
    val merged = (previous + found.tracks).distinctBy { it.id }
    val hasNewTracks = merged.size > previous.size
    return copy(tracks = merged, total = found.total.coerceAtLeast(0), page = page, searching = false,
        hasMore = found.tracks.isNotEmpty() && hasNewTracks &&
            merged.size < found.total && page.toLong() * SEARCH_PAGE_SIZE < found.total)
}

private const val SEARCH_PAGE_SIZE = 20
