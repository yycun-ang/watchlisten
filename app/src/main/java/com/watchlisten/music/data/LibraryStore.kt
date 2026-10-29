package com.watchlisten.music.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.watchlisten.music.model.PlaybackEntry
import com.watchlisten.music.model.Track
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray

private const val MAX_SEARCHES = 12

/** 当前传输类型，用于流量策略判断。蓝牙 tethering 与 Wi-Fi 同属不计费网络。 */
enum class NetworkKind { NONE, WIFI, MOBILE, BLUETOOTH }

fun detectNetworkKind(context: Context): NetworkKind {
    val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return NetworkKind.NONE
    val network = manager.activeNetwork ?: return NetworkKind.NONE
    val capabilities = manager.getNetworkCapabilities(network) ?: return NetworkKind.NONE
    return when {
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> NetworkKind.BLUETOOTH
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkKind.WIFI
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkKind.MOBILE
        else -> NetworkKind.NONE
    }
}

/** 取源质量档位。名称是请求意图，不是对实际码率的承诺。 */
enum class Quality(val key: String, val label: String, val apiValue: String, val note: String) {
    THROTTLED("throttled", "节流", "128", "优先节省流量，按歌曲授权播放"),
    STANDARD("standard", "标准", "320", "优先高码率，按歌曲授权播放"),
    HIGH("high", "无损优先", "flac", "优先已授权无损，不可用时自动选择可播音质");

    companion object {
        fun fromKey(value: String?) = entries.firstOrNull { it.key == value } ?: HIGH
    }
}

/** 最近搜索合并规则：去重（忽略大小写）、最近在前、长度裁剪。 */
fun mergeSearch(current: List<String>, query: String): List<String> {
    val keyword = query.trim().take(100)
    if (keyword.isEmpty()) return current
    return (listOf(keyword) + current.filterNot { it.equals(keyword, ignoreCase = true) }).take(MAX_SEARCHES)
}

class LibraryStore(context: Context) {
    private val preferences = context.getSharedPreferences("library", Context.MODE_PRIVATE)
    init {
        // 页面由大单项列表改为任务布局，旧像素偏移不再指向同一位置。
        if (preferences.getInt("ui_layout_version", 0) < 4) {
            preferences.edit().apply {
                preferences.all.keys.filter { it.startsWith("scroll_") || it.startsWith("offset_") }
                    .forEach { remove(it) }
                putInt("ui_layout_version", 4)
            }.apply()
        }
    }
    private val mutableFavorites = MutableStateFlow(readTracks("favorites"))
    private val mutableHistory = MutableStateFlow(readTracks("history"))
    val favorites = mutableFavorites.asStateFlow()
    val history = mutableHistory.asStateFlow()
    val reduceMotion = MutableStateFlow(preferences.getBoolean("reduce_motion", false))
    val lyricOffset = MutableStateFlow(preferences.getInt("lyric_offset", 0))
    /** 播放质量偏好；实际码率仍以服务端授权的来源为准。 */
    val quality = MutableStateFlow(Quality.fromKey(preferences.getString("quality", null)))
    /** 是否允许在移动网络下取源。关闭时蜂窝网络只提示，不自动播放。 */
    val mobileDataAllowed = MutableStateFlow(preferences.getBoolean("mobile_data", false))
    private val mutableSearches = MutableStateFlow(readSearches())
    val searches = mutableSearches.asStateFlow()

    fun toggleFavorite(track: Track) {
        if (track.isRemote) return
        val list = mutableFavorites.value
        mutableFavorites.value = if (list.any { it.id == track.id }) list.filterNot { it.id == track.id }
            else listOf(track) + list
        writeTracks("favorites", mutableFavorites.value)
    }

    fun favoriteAll(tracks: List<Track>) {
        mutableFavorites.value = (tracks.filterNot { it.isRemote } + mutableFavorites.value).distinctBy { it.id }
        writeTracks("favorites", mutableFavorites.value)
    }

    /** 仅在云端确认迁移成功后移除旧收藏，迁移中断可以继续。 */
    fun removeLegacyFavorite(trackId: String) {
        val remaining = mutableFavorites.value.filterNot { it.id == trackId }
        if (remaining.size == mutableFavorites.value.size) return
        mutableFavorites.value = remaining
        writeTracks("favorites", remaining)
    }

    fun remember(track: Track) {
        if (track.isRemote) return
        mutableHistory.value = (listOf(track) + mutableHistory.value.filterNot { it.id == track.id }).take(100)
        writeTracks("history", mutableHistory.value)
    }

    fun forgetImportedTrack(trackId: String) {
        mutableHistory.value = mutableHistory.value.filterNot { it.isLocal && it.id == trackId }
        writeTracks("history", mutableHistory.value)
        val entries = restoredQueue()
        val current = entries.getOrNull(restoredIndex(entries))?.instanceId
        val remaining = entries.filterNot { it.track.isLocal && it.track.id == trackId }
        val selected = current?.takeIf { id -> remaining.any { it.instanceId == id } }
        saveQueue(remaining, selected ?: remaining.firstOrNull()?.instanceId,
            if (selected != null) restoredPosition(entries) else 0)
    }

    private fun readTracks(name: String): List<Track> = runCatching {
        val array = JSONArray(preferences.getString(name, "[]"))
        (0 until array.length()).map { Track.fromJson(array.getJSONObject(it)) }.filterNot { it.isRemote }
    }.getOrDefault(emptyList())

    private fun writeTracks(name: String, tracks: List<Track>) {
        preferences.edit().putString(name, JSONArray().apply { tracks.forEach { put(it.json()) } }.toString()).apply()
    }

    fun saveQueue(entries: List<PlaybackEntry>, currentId: String?, positionMs: Long) {
        if (entries.any { it.track.isRemote }) return
        val array = JSONArray().apply { entries.forEach { put(it.json()) } }
        preferences.edit().putString("queue", array.toString()).putString("current", currentId)
            .putLong("position", positionMs.coerceAtLeast(0)).apply()
    }

    fun savePosition(currentId: String?, positionMs: Long) {
        preferences.edit().putString("current", currentId).putLong("position", positionMs.coerceAtLeast(0)).apply()
    }

    fun restoredQueue(): List<PlaybackEntry> = runCatching {
        val array = JSONArray(preferences.getString("queue", "[]"))
        (0 until array.length()).map { index -> array.getJSONObject(index).let {
            PlaybackEntry(Track.fromJson(it.getJSONObject("track")), it.getString("instance"))
        } }.filterNot { it.track.isRemote }
    }.getOrDefault(emptyList())

    fun restoredIndex(entries: List<PlaybackEntry>) = entries.indexOfFirst { it.instanceId == preferences.getString("current", "") }.coerceAtLeast(0)
    fun restoredPosition(entries: List<PlaybackEntry> = restoredQueue()): Long {
        val currentId = preferences.getString("current", null)
        if (entries.none { it.instanceId == currentId }) return 0
        return preferences.getLong("position", 0).coerceAtLeast(0)
    }

    fun rememberSearch(query: String) {
        val merged = mergeSearch(mutableSearches.value, query)
        if (merged == mutableSearches.value) return
        mutableSearches.value = merged
        writeSearches()
    }

    fun clearSearches() { mutableSearches.value = emptyList(); writeSearches() }

    private fun readSearches(): List<String> = runCatching {
        val array = JSONArray(preferences.getString("searches", "[]"))
        (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }
    }.getOrDefault(emptyList())

    private fun writeSearches() {
        preferences.edit().putString("searches", JSONArray().apply { mutableSearches.value.forEach { put(it) } }.toString()).apply()
    }

    /** 手表内存紧张时后台页面可能被回收，按页保存滚动位置以便返回时复原。 */
    fun saveScroll(route: String, index: Int, offset: Int) {
        preferences.edit().putInt("scroll_$route", index).putInt("offset_$route", offset).apply()
    }

    fun scrollFor(route: String): Pair<Int, Int> =
        preferences.getInt("scroll_$route", 0) to preferences.getInt("offset_$route", 0)

    fun setReduceMotion(value: Boolean) { reduceMotion.value = value; preferences.edit().putBoolean("reduce_motion", value).apply() }
    fun setQuality(value: Quality) { quality.value = value; preferences.edit().putString("quality", value.key).apply() }
    fun setMobileDataAllowed(value: Boolean) { mobileDataAllowed.value = value; preferences.edit().putBoolean("mobile_data", value).apply() }
    fun savedRepeatMode(): Int = preferences.getInt("repeat_mode", 0).takeIf { it in 0..2 } ?: 0
    fun savedShuffle(): Boolean = preferences.getBoolean("shuffle_mode", false)
    fun savePlaybackMode(repeat: Int, shuffle: Boolean) {
        preferences.edit().putInt("repeat_mode", repeat.coerceIn(0, 2)).putBoolean("shuffle_mode", shuffle).apply()
    }
    fun shiftLyrics(delta: Int) {
        lyricOffset.value = (lyricOffset.value + delta).coerceIn(-10000, 10000)
        preferences.edit().putInt("lyric_offset", lyricOffset.value).apply()
    }
}
