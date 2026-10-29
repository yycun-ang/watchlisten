package com.watchlisten.music.data

import com.watchlisten.music.model.*
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** MakcRe/KuGouMusicApi MIT 协议参考，固定来源见 research/cloud-library-v9.md。 */
internal object CloudLibraryProtocol {
    private const val APP_KEY = "LnT6xpN3khm36zse0QzvmgTZ3waWdRSA"
    private const val PUBLIC_KEY = "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDECi0Np2UR87scwrvTr72L6oO01rBbbBPriSDFPxr3Z5syug0O24QyQO8bg27+0+4kBzTBTBOZ/WWU0WryL1JSXRTXLgFVxtzIY41Pe7lPOgsfTCn5kZcvKhYKJesKnnJDNr5/abvTGf+rHG3YRwsCHcQ08/q6ifSioBszvb3QiwIDAQAB"

    fun playlists(response: JSONObject, identity: SessionData, page: Int): CatalogPage<CloudPlaylist> {
        val parsed = BrowseParser.playlists(response, page, PAGE_SIZE, identity.userId)
        val data = response.getJSONObject("data")
        val rows = data.optJSONArray("info") ?: data.optJSONArray("lists") ?: data.optJSONArray("list")
            ?: throw MusicFailure(MusicError.InvalidResponse)
        val version = data.optLong("total_ver")
        val result = parsed.items.map { collection ->
            val row = (0 until rows.length()).mapNotNull(rows::optJSONObject)
                .firstOrNull { it.optString("listid") == collection.id }
                ?: throw MusicFailure(MusicError.InvalidResponse)
            CloudPlaylist(collection, row.optInt("type", 0), row.optString("list_create_userid")
                .takeUnless { it == "null" }.orEmpty(), version, row.optLong("list_ver"))
        }
        return CatalogPage(result, parsed.total, parsed.hasMore, rawCount = parsed.rawCount)
    }

    fun songs(response: JSONObject, playlist: CloudPlaylist, page: Int): CatalogPage<CloudSong> {
        val data = response.optJSONObject("data") ?: throw MusicFailure(MusicError.InvalidResponse)
        val rows = data.optJSONArray("fileinfo") ?: data.optJSONArray("info") ?: data.optJSONArray("songs")
            ?: data.optJSONArray("list") ?: throw MusicFailure(MusicError.InvalidResponse)
        val parsed = BrowseParser.tracks(response, playlist.collection, page, PAGE_SIZE)
        val songs = (0 until rows.length()).mapNotNull { index ->
            val row = rows.optJSONObject(index) ?: return@mapNotNull null
            BrowseParser.track(row)?.let { CloudSong(it, row.optLong("fileid")) }
        }
        return CatalogPage(songs, parsed.total, parsed.hasMore, parsed.description, parsed.rawCount)
    }

    fun addition(identity: SessionData, playlist: CloudPlaylist, tracks: List<Track>): JSONObject = JSONObject()
        .put("userid", userId(identity)).put("token", identity.token).put("listid", playlistId(playlist))
        .put("list_ver", 0).put("type", 0).put("slow_upload", 1).put("scene", "false;null")
        .put("data", JSONArray().apply { tracks.forEach { track ->
            if (track.origin != TrackOrigin.KUGOU || !track.hash.matches(Regex("[a-fA-F0-9]{32}")))
                throw MusicFailure(MusicError.Unknown("此歌曲不能加入酷狗云歌单"))
            put(JSONObject().put("number", 1).put("name", listOf(track.artist, track.title)
                .filter(String::isNotBlank).joinToString(" - ")).put("hash", track.hash)
                .put("size", 0).put("sort", 0).put("timelen", 0).put("bitrate", 0)
                .put("album_id", track.albumId).put("mixsongid", track.audioId))
        } })

    fun deletion(identity: SessionData, playlist: CloudPlaylist, songs: List<CloudSong>): JSONObject = JSONObject()
        .put("listid", playlistId(playlist)).put("userid", userId(identity)).put("token", identity.token)
        .put("type", 0).put("list_ver", 0).put("data", JSONArray().apply { songs.forEach { song ->
            if (song.fileId <= 0) throw MusicFailure(MusicError.Unknown("云端没有返回歌曲标识，请刷新后重试"))
            put(JSONObject().put("fileid", song.fileId))
        } })

    fun userId(identity: SessionData): Long = identity.userId.toLongOrNull()?.takeIf { it > 0 }
        ?: throw MusicFailure(MusicError.AuthenticationExpired)
    fun playlistId(playlist: CloudPlaylist): Long = playlist.collection.id.toLongOrNull()?.takeIf { it > 0 }
        ?: throw MusicFailure(MusicError.InvalidResponse)

    data class EncryptedPayload(val body: ByteArray, val params: Map<String, String>, val sessionKey: String)

    fun encrypted(body: JSONObject, identity: SessionData, nowSeconds: Long,
                  sessionKey: String = randomSessionKey(), legacyDelete: Boolean = false): EncryptedPayload {
        val portrait = JSONObject().put("aes", sessionKey).put("uid", userId(identity)).put("token", identity.token)
        val key = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(PUBLIC_KEY)))
        val rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding").apply { init(Cipher.ENCRYPT_MODE, key) }
        val bytes = portrait.toString().toByteArray(Charsets.UTF_8)
        if (bytes.size > 117) throw MusicFailure(MusicError.InvalidResponse)
        val p = rsa.doFinal(bytes).joinToString("") { "%02X".format(it.toInt() and 255) }
        val encrypted = crypt(Cipher.ENCRYPT_MODE, body.toString().toByteArray(Charsets.UTF_8), sessionKey)
        val params = linkedMapOf("appid" to "3116", "clientver" to "11436", "mid" to identity.mid,
            "clienttime" to "$nowSeconds", "key" to md5("3116${APP_KEY}11436$nowSeconds"), "dfid" to "-", "p" to p)
        if (!legacyDelete) return EncryptedPayload(encrypted, params, sessionKey)
        val encoded = Base64.getEncoder().encodeToString(encrypted)
        params.putAll(mapOf("last_time" to "$nowSeconds", "last_area" to "gztx", "uuid" to "-",
            "userid" to identity.userId, "token" to identity.token))
        params["signature"] = AccountProtocol.signature(params, encoded)
        return EncryptedPayload(encoded.toByteArray(Charsets.UTF_8), params, sessionKey)
    }

    fun decrypt(bytes: ByteArray, sessionKey: String): JSONObject {
        val plain = bytes.toString(Charsets.UTF_8).trimStart()
        if (plain.startsWith("{")) return parse(plain)
        return try { parse(crypt(Cipher.DECRYPT_MODE, bytes, sessionKey).toString(Charsets.UTF_8)) }
        catch (error: MusicFailure) { throw error }
        catch (_: Exception) { throw MusicFailure(MusicError.InvalidResponse) }
    }

    internal fun crypt(mode: Int, bytes: ByteArray, sessionKey: String): ByteArray {
        val hash = md5(sessionKey)
        return Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
            init(mode, SecretKeySpec(hash.take(16).toByteArray(Charsets.UTF_8), "AES"),
                IvParameterSpec(hash.takeLast(16).toByteArray(Charsets.UTF_8)))
        }.doFinal(bytes)
    }

    private fun parse(value: String): JSONObject = try { JSONObject(value) }
        catch (_: Exception) { throw MusicFailure(MusicError.InvalidResponse) }
    private fun md5(value: String) = MessageDigest.getInstance("MD5").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun randomSessionKey(): String {
        val random = SecureRandom()
        val alphabet = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        return (0 until 6).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
    }

    const val PAGE_SIZE = 30
}
