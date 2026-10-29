package com.watchlisten.music.data

import android.content.Context
import com.watchlisten.music.model.MusicCollection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

interface BrowseStorage {
    val pinned: StateFlow<List<MusicCollection>>
    fun remember(collection: MusicCollection)
    fun selection(route: String): MusicCollection?
    fun togglePin(collection: MusicCollection)
    fun clearForeignPrivate(ownerId: String?)
}

/** 只持久保存集合描述符，不保存云歌单歌曲、token 或账号响应。 */
class BrowseStore(context: Context) : BrowseStorage {
    private val preferences = context.getSharedPreferences("browse", Context.MODE_PRIVATE)
    private val selections = read("selections").toMutableMap()
    private val saved = read("pinned").toMutableMap()
    private val mutablePinned = MutableStateFlow(saved.values.toList())
    override val pinned = mutablePinned.asStateFlow()

    override fun remember(collection: MusicCollection) {
        selections[collection.route] = collection
        persist("selections", selections)
    }
    override fun selection(route: String): MusicCollection? = selections[route]
    override fun togglePin(collection: MusicCollection) {
        if (!collection.canPin) return
        if (saved.remove(collection.route) == null) saved[collection.route] = collection
        mutablePinned.value = saved.values.toList()
        persist("pinned", saved)
    }
    override fun clearForeignPrivate(ownerId: String?) {
        val changed = selections.entries.removeAll { it.value.privateContent && it.value.ownerId != ownerId }
        if (changed) persist("selections", selections)
    }
    private fun read(name: String): Map<String, MusicCollection> = runCatching {
        val value = JSONObject(preferences.getString(name, "{}") ?: "{}")
        value.keys().asSequence().mapNotNull { key -> value.optJSONObject(key)?.let(MusicCollection::fromJson)?.let { it.route to it } }
            .toMap(LinkedHashMap())
    }.getOrDefault(emptyMap())
    private fun persist(name: String, collections: Map<String, MusicCollection>) {
        val value = JSONObject().apply { collections.forEach { (route, collection) -> put(route, collection.json()) } }
        preferences.edit().putString(name, value.toString()).apply()
    }
}
