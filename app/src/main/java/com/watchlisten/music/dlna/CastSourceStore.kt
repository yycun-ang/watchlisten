package com.watchlisten.music.dlna

import android.net.Network
import android.net.Uri
import com.watchlisten.music.playback.CastStreamSource
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Signed phone URLs stay in memory; MediaSession only receives an opaque session token. */
class CastSourceStore {
    private val sources = ConcurrentHashMap<String, CastStreamSource>()
    @Volatile private var network: Network? = null

    fun bind(network: Network) { this.network = network }

    fun put(media: DlnaMedia, peer: String): String {
        RemoteUrlPolicy.validate(media.uri, peer)
        val route = network ?: throw IOException("投放网络已断开")
        val token = UUID.randomUUID().toString()
        sources[token] = CastStreamSource(media.uri, peer, route.socketFactory, route::getAllByName)
        return token
    }

    fun resolve(uri: Uri): CastStreamSource {
        if (uri.scheme != "watchlisten-cast" || uri.host != "media") throw IOException("无效的投放会话")
        return sources[uri.lastPathSegment] ?: throw IOException("投放会话已结束，请在手机重新选歌")
    }

    fun retain(tokens: Set<String>) { sources.keys.retainAll(tokens) }
    fun clear() { sources.clear() }
    fun unbind() { network = null; clear() }
}
