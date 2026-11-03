package com.bitchat.android.onboarding

import android.content.Context
import android.content.SharedPreferences

/** Local tool visibility only; this role never grants remote server privileges. */
enum class NetworkSetupRole {
    USER,
    ADMIN,
}

class NetworkSetupPreferences(private val preferences: SharedPreferences) {
    val region: String?
        get() = preferences.getString("manual_region", null)?.takeIf(::validRegion)

    val role: NetworkSetupRole
        get() = runCatching {
            NetworkSetupRole.valueOf(preferences.getString("role", "USER")!!)
        }.getOrDefault(NetworkSetupRole.USER)

    val completed: Boolean
        get() = preferences.getBoolean("complete", false) && region != null

    val phase: Int
        get() =
            if (region == null) 0
            else
                runCatching {
                        preferences.getInt("phase", if (completed) 2 else 1).coerceIn(0, 2)
                    }
                    .getOrDefault(1)

    val internetOnly: Boolean
        get() = preferences.getBoolean("internet_only", true)

    fun setRegion(value: String) {
        require(validRegion(value)) { "Invalid manual region" }
        val editor = preferences.edit().putString("manual_region", value)
        if (region == null || phase == 0) editor.putInt("phase", 1)
        check(editor.commit())
    }

    fun setPhase(value: Int) {
        require(value in 0..2 && (value == 0 || region != null))
        check(preferences.edit().putInt("phase", value).commit())
    }

    fun setRole(value: NetworkSetupRole) {
        check(preferences.edit().putString("role", value.name).commit())
    }

    fun setInternetOnly(value: Boolean) {
        check(preferences.edit().putBoolean("internet_only", value).commit())
    }

    fun complete() {
        check(region != null) { "Select a manual region first" }
        check(preferences.edit().putBoolean("complete", true).putInt("phase", 2).commit())
    }

    fun clear() {
        check(preferences.edit().clear().commit())
    }

    companion object {
        fun from(context: Context) =
            NetworkSetupPreferences(
                context.getSharedPreferences("network_setup", Context.MODE_PRIVATE)
            )

        private fun validRegion(value: String) =
            value.matches(Regex("[0123456789bcdefghjkmnpqrstuvwxyz]{1,8}"))
    }
}
