package com.readymadeit.meander.runtime

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log

/**
 * Owns the embedded hkp-rt runtime for the Android app — the analogue of iOS's
 * HKPRuntimeHost. The native server itself is a single process-wide instance
 * (see hkp_runtime_jni.cpp), so this wrapper is safe to construct more than once;
 * every instance reflects the same global runtime via [nativePort].
 *
 * It also acts as the multicast-lock controller for LAN discovery: the native
 * DiscoveryManager upcalls [acquireMulticastLock] / [releaseMulticastLock]
 * around each discover window (Android drops multicast without a held lock).
 */
class RuntimeHost(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("hkp-runtime", Context.MODE_PRIVATE)
    private var loginUsers: Set<String> = emptySet()

    private val wifiManager =
        appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private var multicastLock: WifiManager.MulticastLock? = null

    /** http://127.0.0.1:<port> of the embedded runtime, or null when not running. */
    val runtimeBaseUrl: String?
        get() {
            val port = nativePort()
            return if (port > 0) "http://127.0.0.1:$port" else null
        }

    val isRunning: Boolean
        get() = nativePort() > 0

    val externalAccessEnabled: Boolean
        get() = prefs.getBoolean(KEY_EXTERNAL_ACCESS, true)

    val manualAllowedUsers: List<String>
        get() = prefs.getStringSet(KEY_MANUAL_USERS, emptySet())?.toList()?.sorted() ?: emptyList()

    /**
     * Starts the runtime if not already running (idempotent). Binds 0.0.0.0 when
     * external access is enabled, else loopback only. Returns true on success.
     */
    fun start(): Boolean {
        val bind = if (externalAccessEnabled) "0.0.0.0" else "127.0.0.1"
        val port = nativeStart(0, allowedOrigins(), deviceName(), bind, this)
        if (port < 0) {
            Log.e(TAG, "runtime failed to start")
            return false
        }
        // Seed the allow-list (login user arrives later via setLoginUsers).
        applyAllowedUsers()
        Log.i(TAG, "runtime running on port $port (bind $bind)")
        return true
    }

    fun stop() {
        nativeStop()
    }

    /**
     * Blocks up to [timeoutMs] for the runtime port to become available, then
     * returns its base URL (or null on timeout). Used by the request router to
     * absorb the brief startup race between the foreground service starting the
     * runtime and the first hkp:// request from the web app.
     */
    fun awaitRuntimeBaseUrl(timeoutMs: Long = 4000): String? {
        val deadline = System.currentTimeMillis() + timeoutMs
        var url = runtimeBaseUrl
        while (url == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
            url = runtimeBaseUrl
        }
        return url
    }

    fun setExternalAccessEnabled(enabled: Boolean) {
        // Bind address is chosen at start(); a change applies on next runtime start.
        prefs.edit().putBoolean(KEY_EXTERNAL_ACCESS, enabled).apply()
    }

    fun setManualAllowedUsers(users: List<String>) {
        prefs.edit().putStringSet(KEY_MANUAL_USERS, users.filter { it.isNotBlank() }.toSet()).apply()
        applyAllowedUsers()
    }

    fun setLoginUsers(users: List<String>) {
        loginUsers = users.filter { it.isNotBlank() }.toSet()
        applyAllowedUsers()
    }

    fun effectiveAllowedUsers(): List<String> =
        (manualAllowedUsers.toSet() + loginUsers).sorted()

    /** Pushes the effective allow-list (manual ∪ login) to the running server. */
    private fun applyAllowedUsers() {
        nativeSetAllowedUsers(effectiveAllowedUsers().toTypedArray())
    }

    private fun allowedOrigins(): String = "https://$BUNDLED_APP_HOST"

    private fun deviceName(): String {
        val model = Build.MODEL?.trim().orEmpty()
        return model.ifEmpty { "Android" }
    }

    // ── Multicast-lock controller (called from the native discover thread) ──────
    // A non-reference-counted lock held for the duration of a discover window.

    @Suppress("unused") // invoked via JNI
    fun acquireMulticastLock() {
        synchronized(this) {
            val lock = multicastLock ?: wifiManager?.createMulticastLock("hkp-discovery")?.also {
                it.setReferenceCounted(false)
                multicastLock = it
            }
            lock?.let { if (!it.isHeld) it.acquire() }
        }
    }

    @Suppress("unused") // invoked via JNI
    fun releaseMulticastLock() {
        synchronized(this) {
            multicastLock?.let { if (it.isHeld) it.release() }
        }
    }

    // ── JNI ─────────────────────────────────────────────────────────────────────
    private external fun nativeStart(
        port: Int,
        allowedOrigins: String,
        deviceName: String,
        bindAddress: String,
        multicastController: Any,
    ): Int

    private external fun nativeStop()
    private external fun nativePort(): Int
    private external fun nativeSetAllowedUsers(emails: Array<String>)

    companion object {
        private const val TAG = "RuntimeHost"
        private const val KEY_EXTERNAL_ACCESS = "hkpAllowExternalRuntimeAccess"
        private const val KEY_MANUAL_USERS = "hkpManualAllowedUsers"

        // Must match MainActivity.BUNDLED_APP_HOST (the web app's served origin).
        private const val BUNDLED_APP_HOST = "appassets.androidplatform.net"

        init {
            System.loadLibrary("hkprt_jni")
        }
    }
}
