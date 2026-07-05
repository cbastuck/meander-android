package com.readymadeit.meander.auth

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AuthSessionBridge(
    private val activity: Activity,
    private val callbackScheme: String,
) {
    private val lock = Any()
    private var pending: PendingLogin? = null

    fun login(requestJson: String): String {
        val body = JSONObject(requestJson)
        val domain = body.getString("domain")
        val clientId = body.getString("clientId")
        val scheme = body.optString("scheme", callbackScheme).ifBlank { callbackScheme }

        synchronized(lock) {
            if (pending != null) {
                return JSONObject(mapOf("error" to "A login is already in progress")).toString()
            }
        }

        val verifier = randomUrlSafe(32)
        val challenge = s256Challenge(verifier)
        val state = randomUrlSafe(16)
        val redirectUri = "$scheme://auth/callback"
        val latch = CountDownLatch(1)
        val login = PendingLogin(state, latch, scheme)

        synchronized(lock) {
            pending = login
        }

        val authorizeUrl = Uri.Builder()
            .scheme("https")
            .authority(domain)
            .path("/authorize")
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("redirect_uri", redirectUri)
            .appendQueryParameter("scope", "openid profile email")
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("state", state)
            .build()

        Handler(Looper.getMainLooper()).post {
            try {
                activity.startActivity(Intent(Intent.ACTION_VIEW, authorizeUrl))
            } catch (_: Exception) {
                login.latch.countDown()
            }
        }

        val completed = latch.await(180, TimeUnit.SECONDS)
        val redirect = synchronized(lock) {
            val result = pending?.redirect
            pending = null
            result
        }

        if (!completed || redirect == null) {
            return JSONObject(mapOf("cancelled" to true)).toString()
        }
        if (redirect.getQueryParameter("error") != null) {
            return JSONObject(
                mapOf("error" to (redirect.getQueryParameter("error_description") ?: redirect.getQueryParameter("error"))),
            ).toString()
        }
        if (redirect.getQueryParameter("state") != state) {
            return JSONObject(mapOf("error" to "Login redirect did not include a valid state")).toString()
        }
        val code = redirect.getQueryParameter("code")
            ?: return JSONObject(mapOf("error" to "Login redirect did not include a code")).toString()

        return exchangeCode(domain, clientId, code, verifier, redirectUri)
    }

    fun handleRedirect(uri: Uri): Boolean {
        val login = synchronized(lock) { pending } ?: return false
        val expectedScheme = synchronized(lock) { pending?.callbackScheme } ?: callbackScheme
        if (uri.scheme == expectedScheme && uri.host == "auth" && uri.path == "/callback") {
            login.redirect = uri
            login.latch.countDown()
            return true
        }
        return false
    }

    private fun exchangeCode(domain: String, clientId: String, code: String, verifier: String, redirectUri: String): String {
        return try {
            val form = listOf(
                "grant_type" to "authorization_code",
                "client_id" to clientId,
                "code" to code,
                "code_verifier" to verifier,
                "redirect_uri" to redirectUri,
            ).joinToString("&") { (key, value) ->
                "${urlEncode(key)}=${urlEncode(value)}"
            }.toByteArray(Charsets.UTF_8)

            val connection = (URL("https://$domain/oauth/token").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 15_000
                doOutput = true
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                outputStream.use { it.write(form) }
            }
            val status = connection.responseCode
            val response = try {
                val stream = if (status >= 400) connection.errorStream else connection.inputStream
                stream?.use { String(it.readBytes(), Charsets.UTF_8) } ?: ""
            } finally {
                connection.disconnect()
            }
            if (status != 200) {
                JSONObject(mapOf("error" to "Token exchange failed ($status)")).toString()
            } else {
                val idToken = JSONObject(response).optString("id_token", "")
                JSONObject(mapOf("idToken" to idToken)).toString()
            }
        } catch (error: Exception) {
            JSONObject(mapOf("error" to "Token exchange failed: ${error.message ?: "unknown"}")).toString()
        }
    }

    private data class PendingLogin(
        val state: String,
        val latch: CountDownLatch,
        val callbackScheme: String = "",
        var redirect: Uri? = null,
    )

    private fun randomUrlSafe(byteCount: Int): String {
        val bytes = ByteArray(byteCount)
        SecureRandom().nextBytes(bytes)
        return base64Url(bytes)
    }

    private fun s256Challenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.UTF_8))
        return base64Url(digest)
    }

    private fun base64Url(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    private fun urlEncode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())
}
