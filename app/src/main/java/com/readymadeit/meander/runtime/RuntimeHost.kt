package com.readymadeit.meander.runtime

import android.content.Context

class RuntimeHost(private val context: Context) {
    private val prefs = context.getSharedPreferences("hkp-runtime", Context.MODE_PRIVATE)
    private var loginUsers: Set<String> = emptySet()

    val runtimeBaseUrl: String?
        get() = null

    val externalAccessEnabled: Boolean
        get() = prefs.getBoolean(KEY_EXTERNAL_ACCESS, true)

    val manualAllowedUsers: List<String>
        get() = prefs.getStringSet(KEY_MANUAL_USERS, emptySet())?.toList()?.sorted() ?: emptyList()

    fun setExternalAccessEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_EXTERNAL_ACCESS, enabled).apply()
    }

    fun setManualAllowedUsers(users: List<String>) {
        prefs.edit().putStringSet(KEY_MANUAL_USERS, users.filter { it.isNotBlank() }.toSet()).apply()
    }

    fun setLoginUsers(users: List<String>) {
        loginUsers = users.filter { it.isNotBlank() }.toSet()
    }

    fun effectiveAllowedUsers(): List<String> =
        (manualAllowedUsers.toSet() + loginUsers).sorted()

    companion object {
        private const val KEY_EXTERNAL_ACCESS = "hkpAllowExternalRuntimeAccess"
        private const val KEY_MANUAL_USERS = "hkpManualAllowedUsers"
    }
}
