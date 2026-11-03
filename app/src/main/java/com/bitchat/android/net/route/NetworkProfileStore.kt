package com.bitchat.android.net.route

import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class NetworkProfileStore(
    private val preferences: SharedPreferences,
    private val secrets: NetworkSecretStore,
) {
    private val gson = Gson()
    private val mutex = Mutex()
    private val mutableProfiles = MutableStateFlow(loadProfiles())
    val profiles = mutableProfiles.asStateFlow()
    private val mutableSelected = MutableStateFlow(preferences.getString("selected", null))
    val selectedId = mutableSelected.asStateFlow()
    val hasPersistedProfiles: Boolean
        get() = preferences.contains("profiles")

    init {
        check(
            mutableSelected.value == null || profiles.value.any { it.id == mutableSelected.value }
        ) {
            "Selected network profile is missing"
        }
    }

    private fun loadProfiles(): List<NetworkProfile> =
        try {
            val json = preferences.getString("profiles", null)
            if (json == null) emptyList()
            else {
                val type = object : TypeToken<List<NetworkProfile>>() {}.type
                val objects = com.google.gson.JsonParser.parseString(json).asJsonArray
                val result: List<NetworkProfile> = checkNotNull(gson.fromJson(json, type))
                check(result.size <= 32 && result.map { it.id }.distinct().size == result.size)
                var needsMigration = objects.any { obj ->
                    obj.asJsonObject.keySet().any { it.startsWith("cups") }
                }
                val removed = result.filter { it.route == NetworkRoute.CUPS }
                val migrated = result.mapIndexedNotNull { index, profile ->
                    if (profile.route == NetworkRoute.CUPS) {
                        needsMigration = true
                        secrets.removeProfile(profile.id)
                        return@mapIndexedNotNull null
                    }
                    if (
                        !objects[index].asJsonObject.has("deviceId") ||
                            profile.deviceId.isNullOrBlank()
                    ) {
                        needsMigration = true
                        profile.copy(deviceId = java.util.UUID.randomUUID().toString())
                    } else profile
                }
                migrated.forEach { it.validate() }
                if (needsMigration) {
                    val editor = preferences.edit().putString("profiles", gson.toJson(migrated))
                    if (removed.any { it.id == preferences.getString("selected", null) })
                        editor.remove("selected")
                    check(editor.commit())
                }
                migrated
            }
        } catch (e: Exception) {
            throw IllegalStateException("Stored network configuration is invalid", e)
        }

    suspend fun save(profile: NetworkProfile) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                profile.validate()
                val updated = profiles.value.filterNot { it.id == profile.id } + profile
                require(updated.size <= 32) { "Too many network profiles" }
                check(preferences.edit().putString("profiles", gson.toJson(updated)).commit())
                mutableProfiles.value = updated
            }
        }

    suspend fun select(id: String) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                require(profiles.value.any { it.id == id }) { "Network profile not found" }
                check(preferences.edit().putString("selected", id).commit())
                mutableSelected.value = id
            }
        }

    suspend fun remove(id: String) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                require(profiles.value.any { it.id == id }) { "Profile not found" }
                secrets.removeProfile(id)
                val updated = profiles.value.filterNot { it.id == id }
                val editor = preferences.edit().putString("profiles", gson.toJson(updated))
                if (selectedId.value == id) editor.remove("selected")
                check(editor.commit()) { "Could not remove network profile" }
                mutableProfiles.value = updated
                if (selectedId.value == id) mutableSelected.value = null
            }
        }

    suspend fun clear() =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                secrets.clear()
                check(preferences.edit().clear().commit())
                mutableProfiles.value = emptyList()
                mutableSelected.value = null
            }
        }
}
