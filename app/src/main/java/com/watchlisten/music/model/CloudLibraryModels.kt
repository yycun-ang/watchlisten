package com.watchlisten.music.model

data class CloudPlaylist(
    val collection: MusicCollection,
    val type: Int = 0,
    val creatorId: String = "",
    val totalVersion: Long = 0,
    val listVersion: Long = 0
) {
    val isFavorites: Boolean get() = type == 0 && collection.title == "我喜欢" &&
        (creatorId.isBlank() || creatorId == "0" || creatorId == collection.ownerId)
    val canEdit: Boolean get() = type == 0 && (creatorId == collection.ownerId || isFavorites)
}

/** fileId 只属于此云歌单，不能用搜索歌曲 id/hash 代替它删除。 */
data class CloudSong(val track: Track, val fileId: Long)

enum class CloudConfirmationStatus { IDLE, SUBMITTING, AWAITING_CONFIRMATION, CONFIRMED }

data class PendingCloudCreate(
    val operationId: String, val userId: String, val identityFingerprint: String,
    val name: String, val beforeIds: Set<String>, val createdAtMs: Long,
    val status: CloudConfirmationStatus = CloudConfirmationStatus.AWAITING_CONFIRMATION
)

data class CloudFavoritesState(
    val userId: String? = null,
    val collection: MusicCollection? = null,
    val tracks: List<Track> = emptyList(),
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val pendingIds: Set<String> = emptySet(),
    val error: MusicError? = null,
    val message: String? = null,
    val updatedAtMs: Long = 0,
    val undoAvailable: Boolean = false, val undoLabel: String? = null, val undoExpiresAtMs: Long = 0,
    val confirmationStatus: CloudConfirmationStatus = CloudConfirmationStatus.IDLE
) {
    fun isLiked(track: Track): Boolean = tracks.any { sameKugouTrack(it, track) }
}

data class CloudMigrationResult(
    val confirmedIds: Set<String>, val remainingIds: Set<String>, val error: MusicError? = null
)

/** 云库和搜索可能返回不同音质 hash 或缺少 audioId；仅使用确定的同曲标识。 */
fun sameKugouTrack(first: Track, second: Track): Boolean {
    if (first.origin != TrackOrigin.KUGOU || second.origin != TrackOrigin.KUGOU) return false
    val sameHash = first.hash.matches(Regex("[a-fA-F0-9]{32}")) && first.hash.equals(second.hash, true)
    return sameHash || (first.audioId > 0 && first.audioId == second.audioId)
}
