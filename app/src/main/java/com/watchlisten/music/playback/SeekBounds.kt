package com.watchlisten.music.playback

/** 使用媒体实际时间线约束定位，目录里的全曲时长不能作为试听片段的边界。 */
internal data class SeekBounds(val entryId: String?, val durationMs: Long, val seekable: Boolean) {
    fun resolveTarget(requestedPositionMs: Long, expectedEntryId: String?): Long? {
        if (!seekable || durationMs <= 0 || entryId.isNullOrBlank()) return null
        if (expectedEntryId != entryId) return null
        return requestedPositionMs.coerceIn(0, durationMs)
    }
}
