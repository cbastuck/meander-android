package com.readymadeit.meander.bridge

import android.util.Base64
import android.webkit.JavascriptInterface
import com.readymadeit.meander.auth.AuthSessionBridge
import com.readymadeit.meander.runtime.RuntimeHost
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * What the app's own page presents with every call into the bridges below.
 *
 * `addJavascriptInterface` puts them into every frame, cross-origin ones
 * included, so having the objects proves nothing. The token is written only
 * into the top-level page the app serves (MainActivity.bridgeScript), inside
 * the bridge script's closure rather than on `window`, and a call without it
 * is refused — as the vault is kept out of frames.
 */
object BridgeToken {
    /** Drawn once per process, like the secrets token (SecretsPage). */
    val value: String = ByteArray(32).also { SecureRandom().nextBytes(it) }
        .joinToString("") { "%02x".format(it) }

    fun matches(presented: String?): Boolean =
        presented != null && MessageDigest.isEqual(presented.toByteArray(), value.toByteArray())
}

class HkpJavascriptBridge(private val router: HkpSchemeRouter) {
    @JavascriptInterface
    fun fetch(token: String?, requestJson: String): String {
        if (!BridgeToken.matches(token)) {
            return failure(403, "The native bridge serves the app's own page")
        }
        return try {
            val payload = JSONObject(requestJson)
            val headersJson = payload.optJSONObject("headers") ?: JSONObject()
            val headers = headersJson.keys().asSequence().associateWith { headersJson.optString(it) }
            val bodyBase64 = payload.optString("bodyBase64", "")
            val body = if (bodyBase64.isBlank()) ByteArray(0) else Base64.decode(bodyBase64, Base64.NO_WRAP)
            val response = router.handle(
                HkpRequest(
                    method = payload.optString("method", "GET"),
                    url = payload.getString("url"),
                    headers = headers,
                    body = body,
                ),
            )
            JSONObject(
                mapOf(
                    "status" to response.statusCode,
                    "mimeType" to response.mimeType,
                    "headers" to JSONObject(response.headers),
                    "bodyBase64" to Base64.encodeToString(response.body, Base64.NO_WRAP),
                ),
            ).toString()
        } catch (error: Exception) {
            failure(500, error.message ?: "Native bridge failed")
        }
    }

    private fun failure(status: Int, message: String): String =
        JSONObject(
            mapOf(
                "status" to status,
                "mimeType" to "application/json",
                "headers" to JSONObject(),
                "bodyBase64" to Base64.encodeToString(
                    """{"error":"${escape(message)}"}""".toByteArray(Charsets.UTF_8),
                    Base64.NO_WRAP,
                ),
            ),
        ).toString()

    private fun escape(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
}

class RuntimeAuthJavascriptBridge(private val runtimeHost: RuntimeHost) {
    @JavascriptInterface
    fun postMessage(token: String?, requestJson: String) {
        if (!BridgeToken.matches(token)) return
        val body = JSONObject(requestJson)
        val email = body.optString("email", "")
        runtimeHost.setLoginUsers(if (email.isBlank()) emptyList() else listOf(email))
    }
}

class NativeAuthJavascriptBridge(private val authSessionBridge: AuthSessionBridge) {
    @JavascriptInterface
    fun postMessage(token: String?, requestJson: String): String =
        if (BridgeToken.matches(token)) {
            authSessionBridge.login(requestJson)
        } else {
            JSONObject(mapOf("error" to "The native bridge serves the app's own page")).toString()
        }
}
