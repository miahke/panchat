package com.bitchat.android.ui.network

import android.content.Context
import com.bitchat.android.net.importprofile.*
import com.bitchat.android.net.route.*
import com.bitchat.android.onboarding.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface NetworkSettingsOperations {
    val initialRole: NetworkSetupRole

    suspend fun importSource(source: String): List<ImportedConnection>

    suspend fun readSecrets(profile: NetworkProfile): Map<String, String>

    suspend fun saveAndConnect(profile: NetworkProfile, secrets: Map<String, String>)

    suspend fun connectSaved(id: String)

    suspend fun removeProfile(id: String)

    suspend fun disconnect()

    suspend fun generateHash(): String


    suspend fun logout()

    suspend fun saveRole(role: NetworkSetupRole)

    fun randomPassword(): String
}

class RuntimeNetworkSettingsOperations(context: Context) : NetworkSettingsOperations {
    private val preferences = NetworkSetupPreferences.from(context.applicationContext)
    override val initialRole
        get() = preferences.role

    override suspend fun importSource(source: String) =
        withContext(Dispatchers.IO) {
            ConnectionImport.parse(QwdttSubscriptionLoader().readSource(source))
        }

    override suspend fun readSecrets(profile: NetworkProfile) = NetworkRuntime.readSecrets(profile)

    override suspend fun saveAndConnect(profile: NetworkProfile, secrets: Map<String, String>) {
        NetworkRuntime.saveAndConnect(profile, secrets)
    }

    override suspend fun connectSaved(id: String) = NetworkRuntime.connectSaved(id)

    override suspend fun removeProfile(id: String) = NetworkRuntime.removeProfile(id)

    override suspend fun disconnect() = NetworkRuntime.disconnect()

    override suspend fun generateHash() = NetworkRuntime.vkSession.generateCallHash()


    override suspend fun logout() {
        NetworkRuntime.vkSession.logout()
        com.bitchat.android.net.vk.VkAuthActivity.clearTransientStorage()
    }

    override suspend fun saveRole(role: NetworkSetupRole) =
        withContext(Dispatchers.IO) { preferences.setRole(role) }

    override fun randomPassword() = NetworkRuntime.randomPassword()
}
