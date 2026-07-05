package com.readymadeit.meander.bridge

import android.content.Context
import android.net.Uri
import com.readymadeit.meander.R
import com.readymadeit.meander.runtime.RuntimeHost
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder

class HkpSchemeRouter(
    private val context: Context,
    private val runtimeHost: RuntimeHost,
) {
    private val boardStore = BoardStore(context)
    private val remoteName = context.getString(R.string.hkp_runtime_remote_name).ifBlank { "meander-android" }
    private val runtimeBaseUrl = runtimeHost.runtimeBaseUrl
        ?: context.getString(R.string.hkp_runtime_base_url).ifBlank { "http://127.0.0.1:5556" }
    private val remoteRuntime = RemoteRuntimeBridge(remoteName, runtimeBaseUrl)

    fun handle(request: HkpRequest): HkpResponse {
        val method = request.method.uppercase()
        if (method == "OPTIONS") {
            return HkpResponse(204, headers = apiHeaders())
        }

        val uri = Uri.parse(request.url)
        return try {
            when (uri.host) {
                "boards" -> serveBoards(method, uri, request.body)
                "remotes" -> serveRemotes(method, uri, request.headers, request.body)
                "settings" -> serveSettings(method, request.body)
                else -> HkpResponse.json(404, jsonError("Unknown route: ${request.url}"))
            }.withApiHeaders()
        } catch (error: Exception) {
            HkpResponse.json(500, jsonError(error.message ?: "Request failed")).withApiHeaders()
        }
    }

    private fun serveBoards(method: String, uri: Uri, body: ByteArray): HkpResponse {
        val boardName = decode(normalizedPath(uri.path ?: ""))
        return when {
            method == "GET" && boardName.isEmpty() ->
                HkpResponse.json(200, JSONArray(boardStore.listBoards()).toString())
            method == "GET" ->
                HkpResponse(200, "application/json", body = boardStore.loadBoard(boardName))
            method == "POST" && boardName.isNotEmpty() -> {
                if (body.isEmpty()) return HkpResponse.json(400, jsonError("Missing board payload"))
                boardStore.saveBoard(boardName, body)
                HkpResponse.json(200, JSONObject(mapOf("saved" to boardName)).toString())
            }
            method == "DELETE" && boardName.isNotEmpty() -> {
                boardStore.deleteBoard(boardName)
                HkpResponse.json(200, JSONObject(mapOf("deleted" to boardName)).toString())
            }
            else -> HkpResponse.json(405, jsonError("Unsupported boards request"))
        }
    }

    private fun serveRemotes(method: String, uri: Uri, headers: Map<String, String>, body: ByteArray): HkpResponse {
        val path = normalizedPath(uri.path ?: "")
        if (path.isEmpty()) {
            val remotes = JSONArray().put(
                JSONObject(
                    mapOf(
                        "url" to "hkp://remotes/$remoteName",
                        "port" to (Uri.parse(runtimeBaseUrl).port.takeIf { it > 0 } ?: 80),
                        "name" to remoteName,
                    ),
                ),
            )
            return HkpResponse.json(200, remotes.toString())
        }

        val segments = path.split("/")
        if (segments.firstOrNull() != remoteName) {
            return HkpResponse.json(404, jsonError("Unknown remote"))
        }
        val subpath = segments.drop(1).joinToString("/")
        return remoteRuntime.handle(method, subpath, uri.query, headers, body)
    }

    private fun serveSettings(method: String, body: ByteArray): HkpResponse {
        fun current(): JSONObject = JSONObject(
            mapOf(
                "allowExternalRuntimeAccess" to runtimeHost.externalAccessEnabled,
                "allowedUsers" to JSONArray(runtimeHost.manualAllowedUsers),
            ),
        )

        return when (method) {
            "GET" -> HkpResponse.json(200, current().toString())
            "POST" -> {
                if (body.isNotEmpty()) {
                    val payload = JSONObject(String(body, Charsets.UTF_8))
                    if (payload.has("allowExternalRuntimeAccess")) {
                        runtimeHost.setExternalAccessEnabled(payload.getBoolean("allowExternalRuntimeAccess"))
                    }
                    if (payload.has("allowedUsers")) {
                        val users = payload.getJSONArray("allowedUsers")
                        runtimeHost.setManualAllowedUsers((0 until users.length()).map { users.getString(it) })
                    }
                }
                HkpResponse.json(200, current().toString())
            }
            else -> HkpResponse.json(405, jsonError("Unsupported settings request"))
        }
    }

    private fun HkpResponse.withApiHeaders(): HkpResponse = copy(headers = apiHeaders() + headers)

    private fun apiHeaders(): Map<String, String> = mapOf(
        "Access-Control-Allow-Origin" to "*",
        "Access-Control-Allow-Methods" to "GET, POST, DELETE, OPTIONS",
        "Access-Control-Allow-Headers" to "Content-Type, Authorization",
    )

    private fun normalizedPath(path: String): String = path.trim('/')

    private fun decode(value: String): String = URLDecoder.decode(value, Charsets.UTF_8.name())

    private fun jsonError(message: String): String =
        JSONObject(mapOf("error" to message)).toString()

    private fun String.trim(char: Char): String = trim { it == char }
}
