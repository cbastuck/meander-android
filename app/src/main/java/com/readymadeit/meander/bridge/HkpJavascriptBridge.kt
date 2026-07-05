package com.readymadeit.meander.bridge

import android.util.Base64
import android.webkit.JavascriptInterface
import com.readymadeit.meander.auth.AuthSessionBridge
import com.readymadeit.meander.runtime.RuntimeHost
import org.json.JSONObject

class HkpJavascriptBridge(private val router: HkpSchemeRouter) {
    @JavascriptInterface
    fun fetch(requestJson: String): String {
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
            JSONObject(
                mapOf(
                    "status" to 500,
                    "mimeType" to "application/json",
                    "headers" to JSONObject(),
                    "bodyBase64" to Base64.encodeToString(
                        """{"error":"${escape(error.message ?: "Native bridge failed")}"}""".toByteArray(Charsets.UTF_8),
                        Base64.NO_WRAP,
                    ),
                ),
            ).toString()
        }
    }

    private fun escape(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
}

class RuntimeAuthJavascriptBridge(private val runtimeHost: RuntimeHost) {
    @JavascriptInterface
    fun postMessage(requestJson: String) {
        val body = JSONObject(requestJson)
        val email = body.optString("email", "")
        runtimeHost.setLoginUsers(if (email.isBlank()) emptyList() else listOf(email))
    }
}

class NativeAuthJavascriptBridge(private val authSessionBridge: AuthSessionBridge) {
    @JavascriptInterface
    fun postMessage(requestJson: String): String =
        authSessionBridge.login(requestJson)
}
