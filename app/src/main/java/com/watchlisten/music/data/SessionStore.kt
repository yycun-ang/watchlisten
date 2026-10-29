package com.watchlisten.music.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.watchlisten.music.model.SessionData
import com.watchlisten.music.model.UserProfile
import com.watchlisten.music.model.MusicError
import com.watchlisten.music.model.MusicFailure
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SessionStore(context: Context) {
    private val lock = Any()
    private var identityRevision = 0L
    val revision: Long get() = synchronized(lock) { identityRevision }
    private val preferences = context.getSharedPreferences("secure_session", Context.MODE_PRIVATE)
    val installationMid: String = preferences.getString("mid", null)
        ?: UUID.randomUUID().toString().replace("-", "").also { preferences.edit().putString("mid", it).apply() }
    private val mutableSession = MutableStateFlow(restore())
    val session = mutableSession.asStateFlow()

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("watchlisten-session", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("watchlisten-session", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    fun save(session: SessionData) = synchronized(lock) {
        persist(session)
        identityRevision++
        mutableSession.value = session
    }

    /** 扫码开始后若发生退出或换号，迟到授权不得复活旧身份。 */
    fun saveIfRevisionMatches(session: SessionData, expectedRevision: Long): Boolean = synchronized(lock) {
        if (identityRevision != expectedRevision) return false
        persist(session)
        identityRevision++
        mutableSession.value = session
        true
    }

    fun updateProfile(expected: SessionData, profile: UserProfile): Boolean = synchronized(lock) {
        val current = mutableSession.value ?: return false
        if (!sameIdentity(current, expected)) return false
        val updated = current.copy(profile = profile)
        persist(updated)
        mutableSession.value = updated
        true
    }

    fun clearIfMatches(expected: SessionData?): Boolean = synchronized(lock) {
        if (expected == null || !sameIdentity(mutableSession.value, expected)) return false
        clear()
        true
    }

    private fun sameIdentity(first: SessionData?, second: SessionData): Boolean =
        first != null && first.userId == second.userId && first.token == second.token

    private fun persist(session: SessionData) {
        val value = JSONObject().put("token", session.token).put("userid", session.userId)
            .put("mid", session.mid).put("validated", session.validatedAtMs)
            .apply { session.profile?.let { put("profile", it.json()) } }.toString().toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted = JSONObject().put("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .put("value", Base64.encodeToString(cipher.doFinal(value), Base64.NO_WRAP)).toString()
        if (!preferences.edit().putString("encrypted", encrypted).commit())
            throw MusicFailure(MusicError.Unknown("账号资料保存失败，请重试"))
    }

    private fun restore(): SessionData? {
        val text = preferences.getString("encrypted", null) ?: return null
        return runCatching {
            val data = JSONObject(text)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(data.getString("iv"), Base64.NO_WRAP)))
            }
            val value = JSONObject(String(cipher.doFinal(Base64.decode(data.getString("value"), Base64.NO_WRAP)), Charsets.UTF_8))
            SessionData(value.getString("token"), value.getString("userid"), value.getString("mid"), value.getLong("validated"),
                value.optJSONObject("profile")?.let(UserProfile::fromJson))
        }.getOrElse { preferences.edit().remove("encrypted").apply(); null }
    }

    fun clear() = synchronized(lock) {
        if (!preferences.edit().remove("encrypted").commit())
            throw MusicFailure(MusicError.Unknown("退出登录未能保存，请重试"))
        identityRevision++
        mutableSession.value = null
    }
}
