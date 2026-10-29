package com.watchlisten.music.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 只保存明确同意的说明版本；安装升级不会被当作用户同意。 */
class ConsentStore(context: Context) {
    private val preferences = context.getSharedPreferences("usage_consent", Context.MODE_PRIVATE)
    private val mutableAccepted = MutableStateFlow(
        preferences.getInt("version", 0) == DOCUMENT_VERSION && preferences.getLong("accepted_at", 0) > 0
    )
    val accepted = mutableAccepted.asStateFlow()

    @Synchronized
    fun accept(): Boolean {
        val saved = preferences.edit().putInt("version", DOCUMENT_VERSION)
            .putLong("accepted_at", System.currentTimeMillis()).commit()
        if (saved) mutableAccepted.value = true
        return saved
    }

    @Synchronized
    fun revoke(): Boolean {
        val saved = preferences.edit().remove("version").remove("accepted_at").commit()
        if (saved) mutableAccepted.value = false
        return saved
    }

    companion object {
        const val DOCUMENT_VERSION = 5
        const val DOCUMENT_DATE = "2026-10-29"
    }
}
