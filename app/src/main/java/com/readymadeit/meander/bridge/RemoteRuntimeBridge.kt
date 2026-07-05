package com.readymadeit.meander.bridge

import java.net.HttpURLConnection
import java.net.URL

class RemoteRuntimeBridge(
    val remoteName: String,
) {
    fun handle(
        runtimeBaseUrl: String,
        method: String,
        path: String,
        query: String?,
        headers: Map<String, String>,
        body: ByteArray,
    ): HkpResponse {
        if (method == "GET" && path.trim('/') == "health") {
            return HkpResponse.json(
                200,
                """{"status":"ok","remote":"$remoteName","runtimeBaseURL":"$runtimeBaseUrl"}""",
            )
        }

        return try {
            proxy(runtimeBaseUrl, method, path, query, headers, body)
        } catch (error: Exception) {
            HkpResponse.json(502, """{"error":"Runtime request failed: ${escapeJson(error.message ?: "unknown")}"}""")
        }
    }

    private fun proxy(runtimeBaseUrl: String, method: String, path: String, query: String?, headers: Map<String, String>, body: ByteArray): HkpResponse {
        val target = runtimeUrl(runtimeBaseUrl, path, query)
        val connection = (URL(target).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("Accept", headers["Accept"] ?: "application/json")
            headers.forEach { (name, value) ->
                if (!name.equals("Host", ignoreCase = true) && !name.equals("Content-Length", ignoreCase = true)) {
                    setRequestProperty(name, value)
                }
            }
            if (body.isNotEmpty()) {
                doOutput = true
                setRequestProperty("Content-Type", headers["Content-Type"] ?: "application/json")
                outputStream.use { it.write(body) }
            }
        }

        var statusCode = 0
        var contentType = "application/json"
        val responseBody = try {
            statusCode = connection.responseCode
            contentType = connection.contentType?.substringBefore(";") ?: "application/json"
            val stream = if (statusCode >= 400) connection.errorStream else connection.inputStream
            stream?.use { it.readBytes() } ?: ByteArray(0)
        } finally {
            connection.disconnect()
        }

        return HkpResponse(
            statusCode = statusCode,
            mimeType = contentType,
            body = responseBody,
        )
    }

    private fun runtimeUrl(runtimeBaseUrl: String, path: String, query: String?): String {
        val base = runtimeBaseUrl.trimEnd('/')
        val cleanPath = path.trim('/')
        val suffix = if (cleanPath.isEmpty()) "" else "/$cleanPath"
        return buildString {
            append(base)
            append(suffix)
            if (!query.isNullOrBlank()) {
                append("?")
                append(query)
            }
        }
    }

    private fun String.trim(char: Char): String = trim { it == char }

    private fun escapeJson(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
}
