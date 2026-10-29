package com.watchlisten.music.playback

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.watchlisten.music.model.PlaybackEntry
import com.watchlisten.music.model.Track
import org.json.JSONObject

fun PlaybackEntry.mediaItem(): MediaItem {
    val text = track.json().toString()
    val uri = if (track.isLocal) Uri.fromFile(java.io.File(track.localPath))
    else if (track.isRemote && castToken != null)
        Uri.Builder().scheme("watchlisten-cast").authority("media").appendPath(castToken).build()
    else Uri.Builder().scheme("watchlisten").authority("track").appendPath(instanceId).appendQueryParameter("track", text).build()
    val metadata = MediaMetadata.Builder().setTitle(track.title).setArtist(track.artist)
        .setIsPlayable(true).setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
        .setExtras(Bundle().apply { putString("track", text); castToken?.let { putString("cast_token", it) } })
    if (track.cover.startsWith("https://")) metadata.setArtworkUri(Uri.parse(track.cover))
    return MediaItem.Builder().setMediaId(instanceId).setUri(uri).setMediaMetadata(metadata.build()).build()
}

fun MediaItem.entry(): PlaybackEntry? = runCatching {
    val text = mediaMetadata.extras?.getString("track") ?: return null
    PlaybackEntry(Track.fromJson(JSONObject(text)), mediaId, mediaMetadata.extras?.getString("cast_token"))
}.getOrNull()
