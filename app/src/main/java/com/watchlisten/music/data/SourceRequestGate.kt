package com.watchlisten.music.data

import com.watchlisten.music.model.MusicError
import com.watchlisten.music.model.MusicFailure
import com.watchlisten.music.model.PlayableSource
import com.watchlisten.music.model.Track
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 同一首歌共享取源；最后一个等待者离开或缓存失效时取消网络工作。 */
internal class SourceRequestGate(
    private val scope: CoroutineScope,
    private val allowRequest: () -> Boolean,
    private val fetch: suspend (Track) -> PlayableSource,
    private val nowMs: () -> Long = System::currentTimeMillis
) {
    private class Flight {
        lateinit var result: Deferred<PlayableSource>
        var waiters = 0
    }

    private val lock = Any()
    private val cache = mutableMapOf<String, PlayableSource>()
    private val flights = mutableMapOf<String, Flight>()
    private val mutablePreviews = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val previews = mutablePreviews.asStateFlow()
    private val mutableAccess = MutableStateFlow<Map<String, com.watchlisten.music.model.ListeningAccess>>(emptyMap())
    val access = mutableAccess.asStateFlow()
    private val mutableSources = MutableStateFlow<Map<String, PlayableSource>>(emptyMap())
    val sources = mutableSources.asStateFlow()

    fun recordLocalSource(track: Track, source: PlayableSource) = synchronized(lock) {
        mutableSources.value = mutableSources.value + (track.id to source)
        mutableAccess.value = mutableAccess.value + (track.id to source.access)
    }

    suspend fun source(track: Track): PlayableSource {
        currentCoroutineContext().ensureActive()
        val flight = synchronized(lock) {
            cache[track.id]?.takeIf { it.expiresAtMs > nowMs() }?.let { return it }
            val pending = flights[track.id] ?: start(track)
            pending.waiters++
            pending
        }
        return try { flight.result.await() }
        finally { release(track.id, flight) }
    }

    private fun start(track: Track): Flight {
        if (!allowRequest()) throw MusicFailure(MusicError.MobileDataBlocked)
        cache.remove(track.id)
        mutableAccess.value = mutableAccess.value - track.id
        mutablePreviews.value = mutablePreviews.value - track.id
        mutableSources.value = mutableSources.value - track.id
        val flight = Flight()
        flight.result = scope.async(start = CoroutineStart.LAZY) { resolve(track, flight) }
        flights[track.id] = flight
        return flight
    }

    private suspend fun resolve(track: Track, flight: Flight): PlayableSource = try {
        val source = fetch(track)
        currentCoroutineContext().ensureActive()
        synchronized(lock) {
            if (flights[track.id] === flight) {
                cache[track.id] = source
                mutablePreviews.value = mutablePreviews.value + (track.id to source.preview)
                mutableAccess.value = mutableAccess.value + (track.id to source.access)
                mutableSources.value = mutableSources.value + (track.id to source)
            }
        }
        source
    } catch (error: MusicFailure) {
        if (error.error == MusicError.PermissionDenied) synchronized(lock) {
            if (flights[track.id] === flight) mutableAccess.value = mutableAccess.value +
                (track.id to com.watchlisten.music.model.ListeningAccess.DENIED)
        }
        throw error
    } finally {
        synchronized(lock) {
            if (flights[track.id] === flight) flights.remove(track.id)
        }
    }

    private fun release(id: String, flight: Flight) {
        val cancel = synchronized(lock) {
            flight.waiters--
            if (flight.waiters == 0 && !flight.result.isCompleted) {
                if (flights[id] === flight) flights.remove(id)
                true
            } else false
        }
        if (cancel) flight.result.cancel()
    }

    fun invalidate(id: String? = null, cancelInFlight: Boolean = true) {
        val pending = synchronized(lock) {
            if (id == null) {
                cache.clear()
                mutablePreviews.value = emptyMap()
                mutableAccess.value = emptyMap()
                mutableSources.value = emptyMap()
                flights.values.toList().also { flights.clear() }
            } else {
                cache.remove(id)
                mutablePreviews.value = mutablePreviews.value - id
                mutableAccess.value = mutableAccess.value - id
                mutableSources.value = mutableSources.value - id
                listOfNotNull(flights.remove(id))
            }
        }
        if (cancelInFlight) pending.forEach { it.result.cancel() }
    }
}
