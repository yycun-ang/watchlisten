package com.watchlisten.music.data

import android.content.Context
import android.util.Base64
import com.watchlisten.music.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import watchmusic.lyrics.LyricsDocument
import watchmusic.lyrics.LyricsParser
import java.security.MessageDigest
import java.util.LinkedHashMap

class CatalogGateway(private val http: HttpTransport, private val sessions: SessionStore,
                     scope: CoroutineScope, private val library: LibraryStore, context: Context) {
    private val quality get() = library.quality.value
    private val applicationContext = context.applicationContext
    private val sourceRequests = SourceRequestGate(scope,
        allowRequest = { library.mobileDataAllowed.value || detectNetworkKind(applicationContext) != NetworkKind.MOBILE },
        fetch = ::resolveSource)
    val sourcePreviews = sourceRequests.previews
    val sourceAccess = sourceRequests.access
    val sourceDetails = sourceRequests.sources
    private val lyricCache = object : LinkedHashMap<String, LyricsDocument>(8, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, LyricsDocument>?) = size > 8
    }

    suspend fun search(keyword: String, page: Int = 1): SearchPage {
        val identity = sessions.session.value
        val params = base(identity).apply { putAll(mapOf("keyword" to keyword, "page" to "$page", "pagesize" to "20",
            "platform" to "AndroidFilter", "albumhide" to "0", "iscorrection" to "1", "nocollect" to "0")) }
        val response = signed("https://gateway.kugou.com/v3/search/song", params, "complexsearch.kugou.com", identity = identity)
        checkStatus(response, MusicError.Unknown("搜索失败，请稍后重试"), identity)
        val data = response.optJSONObject("data") ?: throw MusicFailure(MusicError.InvalidResponse)
        val rows = data.optJSONArray("lists") ?: throw MusicFailure(MusicError.InvalidResponse)
        val tracks = (0 until rows.length()).mapNotNull { rows.optJSONObject(it)?.let(::track) }
        return SearchPage(tracks, data.optInt("total", tracks.size))
    }

    private fun track(value: JSONObject): Track? {
        val hash = value.optString("FileHash").lowercase()
        if (!hash.matches(Regex("[a-f0-9]{32}"))) return null
        val filename = clean(value.optString("FileName"))
        return Track(hash, clean(value.optString("SongName", filename.substringAfter(" - "))),
            clean(value.optString("SingerName")), value.optLong("AlbumID"), value.optLong("MixSongID"),
            value.optLong("Duration") * 1000, value.optInt("PayType", -1),
            selectCoverUrl(value.optString("Image"), value.optString("AlbumPic")), searchRights(value))
    }

    suspend fun source(track: Track): PlayableSource = if (track.isLocal) localSource(track).also {
        sourceRequests.recordLocalSource(track, it)
    } else sourceRequests.source(track)

    private fun localSource(track: Track): PlayableSource {
        val directory = java.io.File(applicationContext.filesDir, "local-music").canonicalFile
        val file = java.io.File(track.localPath).canonicalFile
        if (!file.toPath().startsWith(directory.toPath()) || file == directory || !file.isFile)
            throw MusicFailure(MusicError.TrackUnavailable)
        return PlayableSource(file.toURI().toString(), file.extension.lowercase(), Long.MAX_VALUE,
            reportedDurationMs = track.durationMs, access = ListeningAccess.FULL,
            qualityLabel = "本地 ${file.extension.uppercase()}")
    }

    /** URL 缓存不等于音频缓存，媒体每次打开连接仍需执行流量策略。 */
    fun checkPlaybackNetwork() {
        if (!library.mobileDataAllowed.value && detectNetworkKind(applicationContext) == NetworkKind.MOBILE)
            throw MusicFailure(MusicError.MobileDataBlocked)
    }

    private suspend fun resolveSource(track: Track): PlayableSource {
        val preferred = quality
        return try { authorizedQualitySource(preferred) { tier, preview -> primarySource(track, tier, preview) } }
        catch (error: MusicFailure) {
            if (track.payType != 0 || track.rights != TrackRights.FREE || error.error in listOf(MusicError.PermissionDenied,
                    MusicError.AuthenticationExpired, MusicError.MobileDataBlocked, MusicError.ConsentRequired,
                    MusicError.VerificationRequired)) throw error
            checkPlaybackNetwork()
            publicSource(track)
        }
    }

    /** 由调用方持有预取生命周期，取消必须传递到正在进行的请求。 */
    suspend fun prefetch(track: Track) {
        try { source(track) }
        catch (error: CancellationException) { throw error }
        catch (_: Exception) { /* 下一首不可用时由播放恢复流程处理。 */ }
    }

    private suspend fun primarySource(track: Track, requestedQuality: Quality, previewRequest: Boolean = false): PlayableSource {
        val identity = sessions.session.value
        val params = base(identity).apply { putAll(mapOf("album_id" to "${track.albumId}", "area_code" to "1", "hash" to track.hash,
            "ssa_flag" to "is_fromtrack", "version" to "11436", "page_id" to "967177915", "quality" to requestedQuality.apiValue,
            "album_audio_id" to "${track.audioId}", "behavior" to "play", "pid" to "411", "cmd" to "26",
            "pidversion" to "3001", "IsFreePart" to if (previewRequest) "1" else "0", "ppage_id" to "356753938,823673182,967485191",
            "cdnBackup" to "1", "module" to "", "kcard" to "0")) }
        params["key"] = md5(track.hash + "185672dd44712f60bb1736df5a377e82" + "3116" + params.getValue("mid") + params.getValue("userid"))
        val response = signed("https://gateway.kugou.com/v5/url", params, "trackercdn.kugou.com", identity = identity)
        checkStatus(response, if (response.optInt("status") == 2) MusicError.PermissionDenied else MusicError.TrackUnavailable, identity)
        return playable(response, track, previewRequest)
    }

    private suspend fun publicSource(track: Track): PlayableSource {
        val url = "https://m.kugou.com/app/i/getSongInfo.php".toHttpUrl().newBuilder()
            .addQueryParameter("cmd", "playInfo").addQueryParameter("hash", track.hash).build()
        val response = http.json(Request.Builder().url(url).build())
        checkStatus(response, MusicError.TrackUnavailable)
        if (response.optInt("privilege", -1) != 0) throw MusicFailure(MusicError.PermissionDenied)
        return playable(response, track)
    }

    private fun playable(response: JSONObject, track: Track, previewRequest: Boolean = false): PlayableSource {
        val url = findSource(response)
        if (url.isEmpty()) throw MusicFailure(MusicError.TrackUnavailable)
        val parsed = url.toHttpUrl()
        if (!allowedMediaHost(parsed.host)) throw MusicFailure(MusicError.InvalidResponse)
        val data = response.optJSONObject("data") ?: response
        val duration = data.optLong("timelength", data.optLong("timeLength") * 1000)
        val access = sourceListeningAccess(data, track.durationMs, duration, previewRequest)
        val format = data.optString("extName", data.optString("extname", "mp3")).lowercase()
        val bitrate = sourceBitrateKbps(data.optLong("bitRate", data.optLong("bitrate", data.optLong("bit_rate"))))
        val label = listOf(format.uppercase(), bitrate?.let { "$it kbps" }).filterNotNull().joinToString(" · ")
        return PlayableSource(url, format, System.currentTimeMillis() + 120000,
            access == ListeningAccess.PREVIEW, duration, access, bitrate, label)
    }

    private fun findSource(value: JSONObject): String {
        for (name in listOf("url", "play_url", "backupUrl", "backup_url", "play_backup_url")) {
            val candidate = value.opt(name)
            if (candidate is String && candidate.startsWith("http")) return candidate
            if (candidate is JSONArray) for (index in 0 until candidate.length()) {
                val url = candidate.optString(index)
                if (url.startsWith("http")) return url
            }
        }
        return value.optJSONObject("data")?.let(::findSource).orEmpty()
    }

    suspend fun createQr(): LoginQr {
        val params = base().apply { putAll(mapOf("appid" to "1001", "type" to "1", "plat" to "4", "srcappid" to "2919",
            "qrcode_txt" to "https://h5.kugou.com/apps/loginQRCode/html/index.html?appid=3116&")) }
        val response = signed("https://login-user.kugou.com/v2/qrcode", params, web = true)
        checkStatus(response, MusicError.Unknown("暂时无法获取登录二维码，请重试"))
        val key = response.optJSONObject("data")?.optString("qrcode").orEmpty()
        if (key.isEmpty()) throw MusicFailure(MusicError.InvalidResponse)
        val url = "https://h5.kugou.com/apps/loginQRCode/html/index.html".toHttpUrl().newBuilder().addQueryParameter("qrcode", key).build()
        return LoginQr(key, url.toString(), android.os.SystemClock.elapsedRealtime())
    }

    suspend fun checkQr(qr: LoginQr): QrCheck {
        val params = base().apply { putAll(mapOf("plat" to "4", "appid" to "3116", "srcappid" to "2919", "qrcode" to qr.key)) }
        val response = signed("https://login-user.kugou.com/v2/get_userinfo_qrcode", params, web = true)
        checkStatus(response, MusicError.Unknown("暂时无法检查扫码结果，请重试"))
        val data = response.optJSONObject("data") ?: throw MusicFailure(MusicError.InvalidResponse)
        return AccountProtocol.qr(data, sessions.installationMid, System.currentTimeMillis())
    }

    suspend fun profile(identity: SessionData): ProfileResult {
        try {
            val time = System.currentTimeMillis() / 1000
            val encrypted = AccountProtocol.encryptPayload(JSONObject().put("token", identity.token).put("clienttime", time).toString())
            val body = JSONObject().put("visit_time", time).put("usertype", 1).put("p", encrypted)
                .put("userid", identity.userId.toLong()).toString()
            val response = signed("https://gateway.kugou.com/v3/get_my_info", base(identity).apply { put("plat", "1") },
                "usercenter.kugou.com", body = body, identity = identity)
            checkStatus(response, MusicError.Unknown("暂时无法加载账号资料"), identity)
            var warning: String? = null
            val vip = try {
                val result = signed("https://kugouvip.kugou.com/v1/get_union_vip", base(identity).apply {
                    put("busi_type", "concept"); put("opt_product_types", "dvip,qvip"); put("product_type", "svip")
                }, identity = identity)
                checkStatus(result, MusicError.Unknown("会员信息暂时不可用"), identity)
                result
            } catch (error: CancellationException) { throw error }
            catch (error: MusicFailure) {
                if (error.error == MusicError.AuthenticationExpired || error.error == MusicError.ConsentRequired) throw error
                warning = "头像资料已更新，会员信息暂时不可用，请重试"
                null
            }
            val profile = AccountProtocol.profile(response, vip, System.currentTimeMillis())
            return ProfileResult(profile, warning)
        } catch (error: MusicFailure) {
            if (error.error == MusicError.AuthenticationExpired && sessions.clearIfMatches(identity))
                sourceRequests.invalidate(cancelInFlight = false)
            throw error
        }
    }

    suspend fun lyrics(track: Track): LyricsDocument {
        synchronized(lyricCache) { lyricCache[track.id] }?.let { return it }
        val search = "https://lyrics.kugou.com/search".toHttpUrl().newBuilder()
            .addQueryParameter("ver", "1").addQueryParameter("man", "yes").addQueryParameter("client", "pc")
            .addQueryParameter("hash", track.hash).build()
        val candidates = http.json(Request.Builder().url(search).build()).optJSONArray("candidates")
            ?: throw MusicFailure(MusicError.LyricsUnavailable)
        val match = (0 until candidates.length()).mapNotNull { candidates.optJSONObject(it) }
            .firstOrNull { kotlin.math.abs(it.optLong("duration") - track.durationMs) <= 5000 }
            ?: throw MusicFailure(MusicError.LyricsUnavailable)
        val parser = LyricsParser()
        val document = try { parser.parseKrc(parser.decodeKrc(downloadLyric(match, "krc"))) }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                try { parser.parseLrc(String(downloadLyric(match, "lrc"), Charsets.UTF_8)) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (fallback: Exception) {
                    parser.parseText(String(downloadLyric(match, "lrc"), Charsets.UTF_8))
                }
            }
        synchronized(lyricCache) { lyricCache[track.id] = document }
        return document
    }

    private suspend fun downloadLyric(candidate: JSONObject, format: String): ByteArray {
        val url = "https://lyrics.kugou.com/download".toHttpUrl().newBuilder()
            .addQueryParameter("id", candidate.getString("id")).addQueryParameter("accesskey", candidate.getString("accesskey"))
            .addQueryParameter("client", "mobi").addQueryParameter("fmt", format).addQueryParameter("ver", "1").addQueryParameter("charset", "utf8").build()
        val value = http.json(Request.Builder().url(url).build()).optString("content")
        if (value.isEmpty()) throw MusicFailure(MusicError.LyricsUnavailable)
        return Base64.decode(value, Base64.DEFAULT)
    }

    private fun base(session: SessionData? = sessions.session.value): MutableMap<String, String> {
        return sortedMapOf("dfid" to "-", "mid" to (session?.mid ?: sessions.installationMid), "uuid" to "-",
            "appid" to "3116", "clientver" to "11436", "clienttime" to "${System.currentTimeMillis() / 1000}",
            "userid" to (session?.userId ?: "0")).apply { session?.let { put("token", it.token) } }
    }

    private suspend fun signed(endpoint: String, params: MutableMap<String, String>, router: String = "", web: Boolean = false,
                               body: String? = null, identity: SessionData? = null,
                               headers: Map<String, String> = emptyMap()): JSONObject {
        val signature = AccountProtocol.signature(params, body.orEmpty(), web)
        val url = endpoint.toHttpUrl().newBuilder().apply {
            params.forEach { (key, value) -> addQueryParameter(key, value) }
            addQueryParameter("signature", signature)
        }.build()
        val request = Request.Builder().url(url).header("User-Agent", "Android15-1070-11083-46-0-DiscoveryDRADProtocol-wifi")
            .header("mid", params.getValue("mid")).header("dfid", "-").header("clienttime", params.getValue("clienttime"))
        if (router.isNotEmpty()) request.header("x-router", router)
        if (router.startsWith("complexsearch")) request.header("kg-tid", "1")
        headers.forEach { (name, value) -> request.header(name, value) }
        if (body != null) request.post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
        return try { http.json(request.build()) }
        catch (error: MusicFailure) {
            if (error.error == MusicError.AuthenticationExpired && sessions.clearIfMatches(identity))
                sourceRequests.invalidate(cancelInFlight = false)
            throw error
        }
    }

    internal suspend fun catalog(call: CatalogCall, identity: SessionData?): JSONObject {
        val params = base(identity).apply { putAll(call.query) }
        val response = signed("https://gateway.kugou.com${call.path}", params, call.router,
            body = call.body?.toString() ?: if (call.post) "" else null,
            identity = identity, headers = call.headers)
        checkStatus(response, MusicError.Unknown("暂时无法加载内容，请重试"), identity)
        return response
    }

    private fun checkStatus(response: JSONObject, fallback: MusicError, identity: SessionData? = null) {
        if (response.optInt("status") == 1) return
        val code = response.optInt("error_code", response.optInt("errcode", -1))
        val error = catalogStatusError(code, fallback)
        if (error == MusicError.AuthenticationExpired && sessions.clearIfMatches(identity)) {
            sourceRequests.invalidate(cancelInFlight = false)
        }
        throw MusicFailure(error)
    }

    fun invalidate(track: Track? = null) { sourceRequests.invalidate(track?.id) }
    fun clearLyrics() { synchronized(lyricCache) { lyricCache.clear() } }
    private fun clean(text: String) = text.replace(Regex("<[^>]*>"), "").trim()
    private fun md5(text: String) = MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }

    companion object {
        fun allowedMediaHost(host: String) = listOf("kugou.com", "kgmusic.com", "kugou.net", "kgimg.com")
            .any { host == it || host.endsWith(".$it") }
    }
}

data class ProfileResult(val profile: UserProfile, val warning: String? = null)

/** 搜索收费码可能混合下载/音质权益，仅以明确的音乐包字段标 VIP。 */
internal fun searchRights(value: JSONObject): TrackRights {
    val trans = value.optJSONObject("TransParam") ?: value.optJSONObject("trans_param")
        ?: value.optString("TransParam").takeIf { it.startsWith("{") }?.let { runCatching { JSONObject(it) }.getOrNull() }
    if (trans?.optInt("all_quality_free") == 1 || trans?.optInt("free_limited") == 1) return TrackRights.LIMITED_FREE
    if (value.optInt("only_vip_playable") == 1 || trans?.optInt("only_vip_playable") == 1 ||
        trans?.optInt("musicpack_advance") == 1) return TrackRights.VIP
    if (trans?.has("musicpack_advance") == true && trans.optInt("musicpack_advance", -1) == 0) return TrackRights.FREE
    return if (value.optInt("PayType", -1) == 0) TrackRights.FREE else TrackRights.UNKNOWN
}

internal fun sourceListeningAccess(data: JSONObject, expectedMs: Long, actualMs: Long,
                                   previewRequest: Boolean): ListeningAccess {
    val flags = listOf("is_free_part", "IsFreePart", "isFreePart", "is_trial", "trial")
    if (previewRequest || flags.any { data.optString(it) in listOf("1", "true") }) return ListeningAccess.PREVIEW
    // 没有试听标记但实际音频明显短于曲目长度，也不能显示成完整曲目。
    if (expectedMs > 0 && actualMs > 0 && actualMs + 5000 < expectedMs) return ListeningAccess.PREVIEW
    if (flags.any { data.has(it) && data.optString(it) in listOf("0", "false") }) return ListeningAccess.FULL
    if (expectedMs > 0 && actualMs > 0 && kotlin.math.abs(expectedMs - actualMs) <= 5000) return ListeningAccess.FULL
    return ListeningAccess.UNKNOWN
}


internal fun catalogStatusError(code: Int, fallback: MusicError): MusicError = when (code) {
    20018, 20019 -> MusicError.AuthenticationExpired
    20028 -> MusicError.VerificationRequired
    20006 -> MusicError.InvalidResponse
    else -> fallback
}
