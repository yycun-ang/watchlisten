package com.watchlisten.music.domain

import com.watchlisten.music.data.*
import com.watchlisten.music.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.time.LocalDate
import java.time.ZoneId

data class CollectionListing(
    val items: List<MusicCollection> = emptyList(), val total: Int? = null, val page: Int = 0,
    val hasMore: Boolean = true, val loading: Boolean = false, val loaded: Boolean = false, val error: MusicError? = null,
    val retryAppend: Boolean = false
)
data class TrackListing(
    val collection: MusicCollection, val tracks: List<Track> = emptyList(), val total: Int? = collection.count,
    val page: Int = 0, val hasMore: Boolean = true, val loading: Boolean = false,
    val loadingAll: Boolean = false, val loaded: Boolean = false, val error: MusicError? = null,
    val description: String = collection.description, val message: String? = null, val retryAppend: Boolean = false
)
data class BrowseState(
    val feeds: Map<CollectionFeed, CollectionListing> = emptyMap(),
    val details: Map<String, TrackListing> = emptyMap(),
    val hotWords: List<String> = emptyList(), val hotLoading: Boolean = false, val hotError: MusicError? = null
)
enum class CollectionAction { PLAY, QUEUE, FAVORITE }
fun interface PagedCollectionPlayback { suspend fun append(tracks: List<Track>): Boolean }

class BrowseCoordinator(
    private val source: BrowseSource, private val storage: BrowseStorage,
    private val sessions: StateFlow<SessionData?>, private val scope: CoroutineScope,
    private val startPagedPlayback: (suspend (List<Track>) -> PagedCollectionPlayback)? = null,
    private val execute: suspend (List<Track>, CollectionAction) -> Unit
) {
    private val mutableState = MutableStateFlow(BrowseState())
    val state = mutableState.asStateFlow()
    val pinned = storage.pinned
    private var activeRoute: String? = null
    private var visible = false
    private var requestJob: Job? = null
    private var hotJob: Job? = null
    private var collectionJob: Job? = null
    private var collectionRoute: String? = null
    private data class InterruptedCollection(val route: String, val playback: PagedCollectionPlayback, var submitted: Int)
    private var interruptedCollection: InterruptedCollection? = null
    private var collectionGeneration = 0L
    private var identityGeneration = 0L

    init {
        storage.clearForeignPrivate(sessions.value?.userId)
        scope.launch {
            sessions.map { it?.let { user -> user.userId to user.token } }.distinctUntilChanged().drop(1).collect {
                identityGeneration++
                cancelCollection()
                cancelRequests()
                storage.clearForeignPrivate(sessions.value?.userId)
                mutableState.update { current -> current.copy(
                    feeds = current.feeds.filterKeys { it == CollectionFeed.PINNED },
                    details = current.details.filterValues { !it.collection.privateContent },
                    hotWords = emptyList(), hotError = null) }
                if (visible) activeRoute?.let(::activate)
            }
        }
        scope.launch {
            storage.pinned.collect { items -> mutableState.update { current -> current.copy(feeds = current.feeds +
                (CollectionFeed.PINNED to CollectionListing(items, items.size, page = 1, hasMore = false, loaded = true))) } }
        }
    }

    fun visible(value: Boolean) {
        visible = value
        if (!value) cancelRequests() else activeRoute?.let(::activate)
    }
    fun activate(route: String) {
        if (activeRoute != route) cancelRequests()
        activeRoute = route
        if (!visible) return
        val feed = CollectionFeed.ofRoute(route)
        when {
            feed != null -> if (state.value.feeds[feed]?.loaded != true) loadFeed(feed)
            isCollectionRoute(route) -> if (state.value.details[route]?.loaded != true) loadTracks(route)
            route == "search" || route == "hot-search" -> loadHotWords()
        }
    }
    fun deactivate(route: String) {
        if (activeRoute != route) return
        activeRoute = null
        cancelRequests()
    }
    fun open(collection: MusicCollection): String {
        storage.remember(collection)
        return collection.route
    }
    fun daily(): String {
        val owner = sessions.value?.userId.orEmpty()
        val day = LocalDate.now(ZoneId.of("Asia/Shanghai"))
        return open(MusicCollection("daily-$day", if (owner.isEmpty()) "今日精选" else "每日推荐",
            CollectionKind.DAILY, ownerId = owner, subtitle = if (owner.isEmpty()) "游客推荐" else "酷狗为你推荐"))
    }
    fun togglePin(collection: MusicCollection) = storage.togglePin(collection)
    fun invalidateCloudLibrary() {
        mutableState.update { current -> current.copy(
            feeds = current.feeds - CollectionFeed.CLOUD,
            details = current.details.filterValues { it.collection.kind != CollectionKind.CLOUD }) }
        if (visible) activeRoute?.let(::activate)
    }
    fun detail(route: String): TrackListing? = state.value.details[route]
        ?: storage.selection(route)?.takeIf { !it.privateContent || it.ownerId == sessions.value?.userId }?.let(::TrackListing)

    fun loadFeed(feed: CollectionFeed, append: Boolean = false) {
        if (!visible || feed == CollectionFeed.PINNED) return
        val old = state.value.feeds[feed] ?: CollectionListing()
        if (old.loading || (append && !old.hasMore)) return
        requestJob?.cancel()
        val page = if (append) old.page + 1 else 1
        val generation = identityGeneration
        val identity = identity()
        updateFeed(feed) { it.copy(loading = true, error = null, retryAppend = append) }
        requestJob = scope.launch {
            try {
                val result = source.collections(feed, page)
                ensureActive()
                if (!current(generation, identity)) return@launch
                val previous = if (append) old.items else emptyList()
                val items = mergeCatalogItems(previous, result.items) { it.key }
                requireProgress(previous.size, items.size, result.rawCount, result.hasMore, append)
                updateFeed(feed) { CollectionListing(items, result.total, page, result.hasMore, loaded = true) }
            } catch (timeout: TimeoutCancellationException) {
                if (current(generation, identity)) updateFeed(feed) { it.copy(loading = false, error = MusicError.SlowNetwork) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { if (current(generation, identity)) updateFeed(feed) { it.copy(loading = false, error = error.toMusicError()) } }
        }
    }

    fun loadTracks(route: String, append: Boolean = false) {
        if (!visible) return
        val old = detail(route) ?: return
        if (old.loading || old.loadingAll || (append && !old.hasMore)) return
        val interrupted = interruptedCollection?.takeIf { it.route == route }
        if (append && old.error != null && interrupted != null) {
            playPaged(route, old, {}, interrupted)
            return
        }
        if (!append && interrupted != null) cancelCollection()
        requestJob?.cancel()
        val generation = identityGeneration
        val identity = identity()
        updateDetail(route, old.copy(loading = true, error = null, message = null, retryAppend = append))
        requestJob = scope.launch {
            try {
                val result = source.tracks(old.collection, if (append) old.page + 1 else 1)
                ensureActive()
                if (current(generation, identity)) updateDetail(route, old.withPage(result, append))
            } catch (timeout: TimeoutCancellationException) {
                if (current(generation, identity)) updateDetail(route, old.copy(error = MusicError.SlowNetwork, retryAppend = append))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { if (current(generation, identity)) updateDetail(route, old.copy(error = error.toMusicError(), retryAppend = append)) }
        }
    }

    fun performAll(route: String, action: CollectionAction, onDone: () -> Unit = {}) {
        if (!visible || activeRoute != route) return
        val original = detail(route) ?: return
        if (original.loadingAll) return
        if (action == CollectionAction.PLAY && startPagedPlayback != null) {
            playPaged(route, original, onDone)
            return
        }
        cancelCollection()
        requestJob?.cancel()
        val generation = identityGeneration
        val identity = identity()
        updateDetail(route, original.copy(loading = false, loadingAll = true, error = null, message = null))
        requestJob = scope.launch {
            var listing = original.copy(loading = false, loadingAll = true)
            try {
                while (!listing.loaded || listing.hasMore) {
                    val result = source.tracks(listing.collection, if (listing.loaded) listing.page + 1 else 1)
                    ensureActive()
                    if (!current(generation, identity)) return@launch
                    listing = listing.withPage(result, listing.loaded).copy(loadingAll = true)
                    updateDetail(route, listing)
                }
                if (listing.tracks.isEmpty()) throw MusicFailure(MusicError.Unknown("这个集合暂时没有可播放歌曲"))
                ensureActive()
                if (!current(generation, identity) || activeRoute != route) return@launch
                execute(listing.tracks, action)
                ensureActive()
                val message = when (action) {
                    CollectionAction.PLAY -> "已准备 ${listing.tracks.size} 首歌曲"
                    CollectionAction.QUEUE -> "已加入 ${listing.tracks.size} 首到播放队列"
                    CollectionAction.FAVORITE -> "已同步 ${listing.tracks.size} 首到酷狗喜欢"
                }
                updateDetail(route, listing.copy(loadingAll = false, message = message))
                onDone()
            } catch (timeout: TimeoutCancellationException) {
                if (current(generation, identity)) updateDetail(route, listing.copy(loadingAll = false, error = MusicError.SlowNetwork, retryAppend = listing.loaded && listing.hasMore))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (current(generation, identity)) updateDetail(route, listing.copy(loadingAll = false, error = error.toMusicError(), retryAppend = listing.loaded && listing.hasMore))
            }
        }
    }
    fun cancelAllLoading() {
        cancelCollection()
        requestJob?.cancel()
        requestJob = null
        clearLoadingFlags()
    }

    private fun playPaged(route: String, original: TrackListing, onStarted: () -> Unit,
                          resume: InterruptedCollection? = null) {
        requestJob?.cancel()
        if (resume == null) cancelCollection() else {
            collectionGeneration++
            collectionJob?.cancel()
            collectionJob = null
        }
        val operation = collectionGeneration
        val generation = identityGeneration
        val identity = identity()
        collectionRoute = route
        updateDetail(route, original.copy(loading = false, loadingAll = true, error = null, message = null))
        collectionJob = scope.launch {
            var listing = original.copy(loading = false, loadingAll = true, error = null)
            var playback: PagedCollectionPlayback? = resume?.playback
            var submitted = resume?.submitted ?: 0
            try {
                suspend fun submit(): Boolean {
                    if (listing.tracks.isEmpty()) return true
                    val existing = playback
                    if (existing == null) {
                        playback = startPagedPlayback!!.invoke(listing.tracks)
                        ensureActive()
                        submitted = listing.tracks.size
                        interruptedCollection = InterruptedCollection(route, playback!!, submitted)
                        updateDetail(route, listing.copy(message = "已开始播放，正在补齐歌单…"))
                        onStarted()
                    } else {
                        if (!existing.append(listing.tracks.drop(submitted))) return false
                        submitted = listing.tracks.size
                        interruptedCollection?.takeIf { it.route == route }?.submitted = submitted
                    }
                    return true
                }
                if (!submit()) {
                    interruptedCollection = null
                    updateDetail(route, listing.copy(loadingAll = false, error = null,
                        message = "已切换队列，停止补齐歌单"))
                    return@launch
                }
                while (!listing.loaded || listing.hasMore) {
                    val result = source.tracks(listing.collection, if (listing.loaded) listing.page + 1 else 1)
                    ensureActive()
                    if (!current(generation, identity)) return@launch
                    listing = listing.withPage(result, listing.loaded).copy(loadingAll = true)
                    updateDetail(route, listing.copy(message = if (submitted > 0) "正在补齐歌单，已有 ${listing.tracks.size} 首" else null))
                    if (!submit()) {
                        interruptedCollection = null
                        updateDetail(route, listing.copy(loadingAll = false, message = "已切换队列，停止补齐歌单"))
                        return@launch
                    }
                }
                if (listing.tracks.isEmpty()) throw MusicFailure(MusicError.Unknown("这个集合暂时没有可播放歌曲"))
                interruptedCollection = null
                updateDetail(route, listing.copy(loadingAll = false, message = "已准备 ${listing.tracks.size} 首歌曲"))
            } catch (timeout: TimeoutCancellationException) {
                if (current(generation, identity) && operation == collectionGeneration) updateDetail(route, listing.copy(
                    loadingAll = false, error = MusicError.SlowNetwork, retryAppend = listing.loaded && listing.hasMore,
                    message = if (submitted > 0) "已开始播放，剩余歌曲可重试补齐" else null))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (current(generation, identity) && operation == collectionGeneration && isActive) updateDetail(route, listing.copy(loadingAll = false,
                    error = error.toMusicError(), retryAppend = listing.loaded && listing.hasMore,
                    message = if (submitted > 0) "已开始播放，剩余歌曲可重试补齐" else null))
            } finally {
                if (operation == collectionGeneration) collectionRoute = null
            }
        }
    }

    private fun cancelCollection() {
        interruptedCollection = null
        collectionGeneration++
        collectionJob?.cancel()
        collectionJob = null
        collectionRoute = null
        mutableState.update { current -> current.copy(details = current.details.mapValues { it.value.copy(loadingAll = false) }) }
    }
    fun loadHotWords(force: Boolean = false) {
        if (!visible || state.value.hotLoading || (!force && state.value.hotWords.isNotEmpty())) return
        hotJob?.cancel()
        mutableState.update { it.copy(hotLoading = true, hotError = null) }
        val generation = identityGeneration
        val identity = identity()
        hotJob = scope.launch {
            try {
                val words = source.hotWords()
                ensureActive()
                if (current(generation, identity)) mutableState.update { it.copy(hotWords = words, hotLoading = false) }
            } catch (timeout: TimeoutCancellationException) {
                if (current(generation, identity)) mutableState.update { it.copy(hotLoading = false, hotError = MusicError.SlowNetwork) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { if (current(generation, identity)) mutableState.update { it.copy(hotLoading = false, hotError = error.toMusicError()) } }
        }
    }

    private fun cancelRequests() {
        requestJob?.cancel(); hotJob?.cancel()
        requestJob = null; hotJob = null
        clearLoadingFlags()
    }
    private fun identity(): Pair<String, String>? = sessions.value?.let { it.userId to it.token }
    private fun current(generation: Long, expected: Pair<String, String>?) = generation == identityGeneration && expected == identity()
    private fun clearLoadingFlags() = mutableState.update { current -> current.copy(
        feeds = current.feeds.mapValues { it.value.copy(loading = false) },
        details = current.details.mapValues { (route, listing) -> listing.copy(loading = false,
            loadingAll = listing.loadingAll && route == collectionRoute) }, hotLoading = false) }
    private fun updateFeed(feed: CollectionFeed, change: (CollectionListing) -> CollectionListing) = mutableState.update {
        it.copy(feeds = it.feeds + (feed to change(it.feeds[feed] ?: CollectionListing()))) }
    private fun updateDetail(route: String, detail: TrackListing) = mutableState.update { it.copy(details = it.details + (route to detail)) }
}

internal fun <T> mergeCatalogItems(previous: List<T>, incoming: List<T>, key: (T) -> String): List<T> =
    (previous + incoming).distinctBy(key)

private fun requireProgress(before: Int, after: Int, rawCount: Int, hasMore: Boolean, append: Boolean) {
    if (append && before == after && (rawCount > 0 || hasMore))
        throw MusicFailure(MusicError.Unknown("服务返回的分页没有新增内容，请刷新后重试"))
}

internal fun TrackListing.withPage(result: CatalogPage<Track>, append: Boolean): TrackListing {
    val previous = if (append) tracks else emptyList()
    val merged = mergeCatalogItems(previous, result.items) { it.id }
    requireProgress(previous.size, merged.size, result.rawCount, result.hasMore, append)
    return copy(tracks = merged, total = result.total, page = if (append) page + 1 else 1,
        hasMore = result.hasMore, loading = false, loaded = true, error = null, retryAppend = false,
        description = result.description.ifBlank { description })
}
