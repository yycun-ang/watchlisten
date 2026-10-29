package com.watchlisten.music

import android.app.Application
import android.content.Context
import com.watchlisten.music.data.*
import com.watchlisten.music.domain.MusicCoordinator
import com.watchlisten.music.domain.BrowseCoordinator
import com.watchlisten.music.domain.CollectionAction
import com.watchlisten.music.domain.CloudFavoritesCoordinator
import com.watchlisten.music.domain.CloudPlaylistCoordinator
import com.watchlisten.music.domain.DiscoveryCoordinator
import com.watchlisten.music.playback.PlaybackController
import com.watchlisten.music.dlna.DlnaController
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import com.watchlisten.music.ui.nav.PlaybackSource
import com.watchlisten.music.model.CloudConfirmationStatus

class WatchApplication : Application() {
    val graph by lazy { AppGraph(this) }
}

class AppGraph(application: Context, catalogSource: BrowseSource? = null, cloudSource: CloudPlaylistSource? = null,
               discoverySource: DiscoverySource? = null) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val sessions = SessionStore(application)
    val library = LibraryStore(application)
    val consent = ConsentStore(application)
    val http = HttpTransport { consent.accepted.value }
    val diagnostics = DiagnosticsStore(application) { consent.accepted.value }
    val gateway = CatalogGateway(http, sessions, scope, library, application)
    val discovery = DiscoveryCoordinator(discoverySource ?: DiscoveryGateway(gateway, sessions), sessions.session, scope)
    val playback = PlaybackController(application, scope, gateway)
    val localMusic = LocalMusicStore(application, scope)
    val music = MusicCoordinator(gateway, sessions, library, playback, scope, application, localLyrics = localMusic.lyrics)
    val cloudLibrary: CloudPlaylistSource = cloudSource ?: CloudLibraryGateway(gateway, http, sessions)
    val favorites = CloudFavoritesCoordinator(cloudLibrary, sessions.session, scope)
    val cloudManager = CloudPlaylistCoordinator(cloudLibrary, sessions.session, scope, onCloudChanged = {
        favorites.refresh()
        browse.invalidateCloudLibrary()
    }, creationStore = CloudCreationPreferences(application))
    val transfer = LocalTransferController(application, localMusic, scope)
    val playbackSource = MutableStateFlow<PlaybackSource?>(null)
    init {
        scope.launch {
            sessions.session.map { it?.let { s -> s.userId to s.token } }.distinctUntilChanged().drop(1).collect {
                playbackSource.value = null
            }
        }
    }
    val dlna = DlnaController(application, this)
    init { playback.beforeLocalQueueEdit = dlna::disconnect }
    val browseStore = BrowseStore(application)
    val browse = BrowseCoordinator(catalogSource ?: BrowseGateway(gateway, sessions), browseStore, sessions.session, scope,
        startPagedPlayback = music::startPagedCollection) { tracks, action ->
        when (action) {
            CollectionAction.PLAY -> music.playCollection(tracks)
            CollectionAction.QUEUE -> playback.addAll(tracks)
            CollectionAction.FAVORITE -> {
                val result = favorites.migrate(tracks)
                result.error?.let { throw com.watchlisten.music.model.MusicFailure(it) }
            }
        }
    }
    init {
        scope.launch {
            consent.accepted.collectLatest { accepted ->
                if (!accepted) return@collectLatest
                coroutineScope {
                    launch { music.network.collect { network -> diagnostics.record(DiagnosticArea.NETWORK, when (network) {
                        NetworkKind.WIFI -> DiagnosticCode.WIFI
                        NetworkKind.MOBILE -> DiagnosticCode.MOBILE
                        NetworkKind.BLUETOOTH -> DiagnosticCode.BLUETOOTH
                        NetworkKind.NONE -> DiagnosticCode.OFFLINE
                    }) } }
                    launch { playback.state.map { snapshot -> when {
                        snapshot.error != null -> diagnosticError(snapshot.error)
                        !snapshot.connected -> DiagnosticCode.DISCONNECTED
                        snapshot.buffering -> DiagnosticCode.BUFFERING
                        snapshot.playing -> DiagnosticCode.PLAYING
                        snapshot.current == null -> DiagnosticCode.STOPPED
                        else -> DiagnosticCode.PAUSED
                    } }.distinctUntilChanged().collect { diagnostics.record(DiagnosticArea.PLAYBACK, it) } }
                    launch { favorites.state.map { diagnosticCloudCode(it.error, it.confirmationStatus) }
                        .distinctUntilChanged().filter { it != DiagnosticCode.NONE }.collect { diagnostics.record(DiagnosticArea.CLOUD, it) } }
                    launch { cloudManager.state.map { diagnosticCloudCode(it.error, it.confirmationStatus) }
                        .distinctUntilChanged().filter { it != DiagnosticCode.NONE }.collect { diagnostics.record(DiagnosticArea.CLOUD, it) } }
                    launch { transfer.state.map { when {
                        it.error != null -> DiagnosticCode.NETWORK_ERROR
                        it.enabled -> DiagnosticCode.STARTED
                        else -> DiagnosticCode.STOPPED
                    } }.distinctUntilChanged().collect { diagnostics.record(DiagnosticArea.TRANSFER, it) } }
                    launch { dlna.state.map { when {
                        it.peer != null -> DiagnosticCode.CONNECTED
                        it.error != null -> DiagnosticCode.NETWORK_ERROR
                        it.ready -> DiagnosticCode.STARTED
                        else -> DiagnosticCode.STOPPED
                    } }.distinctUntilChanged().collect { diagnostics.record(DiagnosticArea.CAST, it) } }
                    awaitCancellation()
                }
            }
        }
    }

    fun diagnosticReport(): String {
        val player = playback.state.value
        val cast = dlna.state.value
        return diagnostics.export(BuildConfig.VERSION_NAME, android.os.Build.VERSION.SDK_INT, android.os.Build.MODEL,
            music.network.value, player.connected, player.playing, player.buffering, player.queue.size,
            cast.enabled, cast.peer != null, transfer.state.value.enabled)
    }
    private var visibleWindows = 0
    fun enter() {
        if (!consent.accepted.value) return
        visibleWindows++
        music.visible(true)
        playback.visible(true)
        browse.visible(true)
        discovery.visible(true)
        favorites.visible(true)
    }
    fun leave() {
        visibleWindows = (visibleWindows - 1).coerceAtLeast(0)
        if (visibleWindows == 0) { music.visible(false); playback.visible(false); browse.visible(false); favorites.visible(false); discovery.visible(false) }
    }

    fun revokeConsent(): Boolean {
        if (!consent.revoke()) return false
        dlna.disable()
        transfer.stop()
        http.cancelAll()
        diagnostics.clear()
        browse.visible(false)
        discovery.visible(false)
        // 即使凭据写盘失败，也不能重新开放已经撤回的联网权限。
        return runCatching { music.logout() }.getOrDefault(false)
    }
}

private fun diagnosticCloudCode(error: com.watchlisten.music.model.MusicError?, status: CloudConfirmationStatus): DiagnosticCode =
    if (error != null) diagnosticError(error) else when (status) {
        CloudConfirmationStatus.AWAITING_CONFIRMATION -> DiagnosticCode.AWAITING_CONFIRMATION
        CloudConfirmationStatus.CONFIRMED -> DiagnosticCode.CONFIRMED
        else -> DiagnosticCode.NONE
    }
