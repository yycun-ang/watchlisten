package com.watchlisten.music.domain

import com.watchlisten.music.data.DiscoverySource
import com.watchlisten.music.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class DiscoverySearchState(
    val query: String = "", val kind: DiscoveryKind = DiscoveryKind.SONGS,
    val items: List<DiscoveryItem> = emptyList(), val total: Int? = null, val page: Int = 0,
    val loading: Boolean = false, val loaded: Boolean = false, val hasMore: Boolean = false,
    val error: MusicError? = null, val retryAppend: Boolean = false
)

data class ArtistLibraryState(
    val id: Long, val profile: MusicArtist? = null, val profileLoaded: Boolean = false,
    val profileLoading: Boolean = false, val profileError: MusicError? = null,
    val songs: List<Track> = emptyList(), val songsTotal: Int? = null, val songsPage: Int = 0,
    val songsLoaded: Boolean = false, val songsLoading: Boolean = false, val songsHasMore: Boolean = true,
    val songsError: MusicError? = null, val songsRetryAppend: Boolean = false,
    val albums: List<MusicCollection> = emptyList(), val albumsTotal: Int? = null, val albumsPage: Int = 0,
    val albumsLoaded: Boolean = false, val albumsLoading: Boolean = false, val albumsHasMore: Boolean = true,
    val albumsRequested: Boolean = false,
    val albumsError: MusicError? = null, val albumsRetryAppend: Boolean = false
)

data class DiscoveryState(val search: DiscoverySearchState = DiscoverySearchState(),
                          val artists: Map<Long, ArtistLibraryState> = emptyMap(), val activeArtistId: Long? = null) {
    val artist: ArtistLibraryState? get() = artists[activeArtistId]
}

/** 公开内容仍随账号授权隔离；离页停止请求，保留已加载列表与分页位置。 */
class DiscoveryCoordinator(private val source: DiscoverySource, private val sessions: StateFlow<SessionData?>,
                            private val scope: CoroutineScope) {
    private val mutableState = MutableStateFlow(DiscoveryState())
    val state = mutableState.asStateFlow()
    private var visible = false
    private var route: String? = null
    private var generation = 0L
    private var searchJob: Job? = null
    private var profileJob: Job? = null
    private var songsJob: Job? = null
    private var albumsJob: Job? = null

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            sessions.map { it?.let { account -> account.userId to account.token } }.distinctUntilChanged().drop(1).collect {
                generation++; cancelRequests()
                val previous = state.value.search
                mutableState.value = DiscoveryState(search = DiscoverySearchState(query = previous.query, kind = previous.kind))
                if (visible) route?.let(::activate)
            }
        }
    }

    fun visible(value: Boolean) {
        visible = value
        if (!value) cancelRequests() else route?.let(::activate)
    }

    fun activate(next: String) {
        if (next != route) cancelRequests()
        route = next
        val id = artistIdFromRoute(next)
        mutableState.update { it.copy(activeArtistId = id) }
        if (!visible) return
        if (next == "catalog-search") {
            val currentSearch = state.value.search
            if (currentSearch.query.isNotBlank() && !currentSearch.loaded && !currentSearch.loading)
                search(currentSearch.query, currentSearch.kind)
        } else if (id != null) {
            if (state.value.artists[id] == null) updateArtist(id) { it }
            val artist = state.value.artists.getValue(id)
            if (!artist.profileLoaded && !artist.profileLoading) loadProfile(id)
            if (!artist.songsLoaded && !artist.songsLoading) loadArtistSongs()
            if (artist.albumsRequested && !artist.albumsLoaded && !artist.albumsLoading) loadArtistAlbums()
        }
    }

    fun deactivate(page: String) {
        if (route == page) { route = null; cancelRequests() }
    }

    fun search(query: String, kind: DiscoveryKind = state.value.search.kind, append: Boolean = false) {
        val keyword = query.trim().take(100)
        val before = state.value.search
        if (append && (route != "catalog-search" || before.loading || !before.hasMore ||
                    keyword != before.query || kind != before.kind)) return
        searchJob?.cancel()
        if (keyword.isEmpty()) {
            mutableState.update { it.copy(search = DiscoverySearchState(kind = kind)) }
            return
        }
        val nextPage = if (append) before.page + 1 else 1
        val expected = generation
        val identity = identity()
        mutableState.update { it.copy(search = before.copy(query = keyword, kind = kind,
            items = if (append) before.items else emptyList(), total = if (append) before.total else null,
            page = if (append) before.page else 0, loaded = append && before.loaded,
            loading = visible && route == "catalog-search", error = null, retryAppend = append,
            hasMore = append && before.hasMore)) }
        if (!visible || route != "catalog-search") return
        searchJob = scope.launch {
            try {
                val result = source.search(keyword, kind, nextPage)
                ensureActive()
                if (!current(expected, identity)) return@launch
                val items = mergePage(if (append) before.items else emptyList(), result, append, DiscoveryItem::key)
                mutableState.update { it.copy(search = DiscoverySearchState(keyword, kind, items, result.total,
                    nextPage, loaded = true, hasMore = result.hasMore)) }
            } catch (error: TimeoutCancellationException) { searchFailure(expected, identity, MusicError.SlowNetwork, append) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { searchFailure(expected, identity, error.toMusicError(), append) }
        }
    }

    fun retrySearch() { state.value.search.let { search(it.query, it.kind, it.retryAppend) } }

    fun openArtist(id: Long): String {
        require(id > 0)
        return "artist/$id"
    }

    fun openArtist(artist: MusicArtist): String {
        require(artist.id > 0)
        if (state.value.artists[artist.id] == null) updateArtist(artist.id) { it.copy(profile = artist) }
        return artist.route
    }

    fun refreshArtist() {
        val id = artistIdFromRoute(route.orEmpty()) ?: return
        if (!visible) return
        loadProfile(id)
        loadArtistSongs()
        if (state.value.artists[id]?.albumsRequested == true) loadArtistAlbums()
    }

    fun loadArtistSongs(append: Boolean = false) {
        val id = artistIdFromRoute(route.orEmpty()) ?: return
        if (!visible) return
        val previous = state.value.artists[id] ?: ArtistLibraryState(id)
        if (previous.songsLoading || (append && !previous.songsHasMore)) return
        songsJob?.cancel()
        val expected = generation
        val identity = identity()
        updateArtist(id) { it.copy(songsLoading = true, songsError = null, songsRetryAppend = append) }
        songsJob = scope.launch {
            try {
                val page = source.artistSongs(id, if (append) previous.songsPage + 1 else 1)
                ensureActive()
                if (!current(expected, identity)) return@launch
                val songs = mergePage(if (append) previous.songs else emptyList(), page, append, Track::id)
                updateArtist(id) { it.copy(songs = songs, songsTotal = page.total,
                    songsPage = if (append) previous.songsPage + 1 else 1, songsLoading = false, songsLoaded = true,
                    songsHasMore = page.hasMore, songsError = null, songsRetryAppend = false) }
            } catch (error: TimeoutCancellationException) { artistFailure(id, expected, identity, MusicError.SlowNetwork, false) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { artistFailure(id, expected, identity, error.toMusicError(), false) }
        }
    }

    fun loadArtistAlbums(append: Boolean = false) {
        val id = artistIdFromRoute(route.orEmpty()) ?: return
        if (!visible) return
        val previous = state.value.artists[id] ?: ArtistLibraryState(id)
        if (previous.albumsLoading || (append && !previous.albumsHasMore)) return
        albumsJob?.cancel()
        val expected = generation
        val identity = identity()
        updateArtist(id) { it.copy(albumsLoading = true, albumsError = null, albumsRetryAppend = append, albumsRequested = true) }
        albumsJob = scope.launch {
            try {
                val page = source.artistAlbums(id, if (append) previous.albumsPage + 1 else 1)
                ensureActive()
                if (!current(expected, identity)) return@launch
                val albums = mergePage(if (append) previous.albums else emptyList(), page, append, MusicCollection::key)
                updateArtist(id) { it.copy(albums = albums, albumsTotal = page.total,
                    albumsPage = if (append) previous.albumsPage + 1 else 1, albumsLoading = false, albumsLoaded = true,
                    albumsHasMore = page.hasMore, albumsError = null, albumsRetryAppend = false) }
            } catch (error: TimeoutCancellationException) { artistFailure(id, expected, identity, MusicError.SlowNetwork, true) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { artistFailure(id, expected, identity, error.toMusicError(), true) }
        }
    }

    private fun loadProfile(id: Long) {
        profileJob?.cancel()
        val expected = generation
        val identity = identity()
        updateArtist(id) { it.copy(profileLoading = true, profileError = null) }
        profileJob = scope.launch {
            try {
                val profile = source.artist(id)
                ensureActive()
                if (profile.id != id) throw MusicFailure(MusicError.InvalidResponse)
                if (current(expected, identity)) updateArtist(id) { it.copy(profile = profile, profileLoading = false, profileLoaded = true) }
            } catch (error: TimeoutCancellationException) { profileFailure(id, expected, identity, MusicError.SlowNetwork) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { profileFailure(id, expected, identity, error.toMusicError()) }
        }
    }

    private suspend fun searchFailure(expected: Long, identity: Pair<String, String>?, error: MusicError, append: Boolean) {
        currentCoroutineContext().ensureActive()
        if (current(expected, identity)) mutableState.update { it.copy(search = it.search.copy(loading = false, error = error, retryAppend = append)) }
    }
    private suspend fun profileFailure(id: Long, expected: Long, identity: Pair<String, String>?, error: MusicError) {
        currentCoroutineContext().ensureActive()
        if (current(expected, identity)) updateArtist(id) { it.copy(profileLoading = false, profileError = error) }
    }
    private suspend fun artistFailure(id: Long, expected: Long, identity: Pair<String, String>?, error: MusicError, albums: Boolean) {
        currentCoroutineContext().ensureActive()
        if (current(expected, identity)) updateArtist(id) { if (albums) it.copy(albumsLoading = false, albumsError = error)
            else it.copy(songsLoading = false, songsError = error) }
    }
    private fun updateArtist(id: Long, change: (ArtistLibraryState) -> ArtistLibraryState) = mutableState.update {
        it.copy(artists = it.artists + (id to change(it.artists[id] ?: ArtistLibraryState(id))))
    }
    private fun identity() = sessions.value?.let { it.userId to it.token }
    private fun current(expected: Long, expectedIdentity: Pair<String, String>?) = expected == generation && expectedIdentity == identity()
    private fun cancelRequests() {
        searchJob?.cancel(); profileJob?.cancel(); songsJob?.cancel(); albumsJob?.cancel()
        searchJob = null; profileJob = null; songsJob = null; albumsJob = null
        mutableState.update { it.copy(search = it.search.copy(loading = false), artists = it.artists.mapValues { (_, item) ->
            item.copy(profileLoading = false, songsLoading = false, albumsLoading = false)
        }) }
    }
}

private fun <T> mergePage(previous: List<T>, incoming: CatalogPage<T>, append: Boolean, key: (T) -> String): List<T> {
    if (incoming.hasMore && (incoming.items.isEmpty() || incoming.rawCount == 0)) throw MusicFailure(MusicError.InvalidResponse)
    val merged = (previous + incoming.items).distinctBy(key)
    if (append && merged.size == previous.size && (incoming.rawCount > 0 || incoming.hasMore))
        throw MusicFailure(MusicError.Unknown("分页没有新增内容，请刷新后重试"))
    return merged
}
