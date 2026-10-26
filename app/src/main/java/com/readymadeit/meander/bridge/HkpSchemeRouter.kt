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
    private val presetStore = PresetStore(context)
    private val homeScreenPins = HomeScreenPins(context)
    private val remoteName = context.getString(R.string.hkp_runtime_remote_name).ifBlank { "meander-android" }
    private val remoteRuntime = RemoteRuntimeBridge(remoteName)

    // Resolved per request: the embedded runtime's port is only known once the
    // foreground service has started it, so we never cache it at construction.
    private fun resolveRuntimeBaseUrl(): String? = runtimeHost.awaitRuntimeBaseUrl()

    fun handle(request: HkpRequest): HkpResponse {
        val method = request.method.uppercase()
        if (method == "OPTIONS") {
            return HkpResponse(204, headers = apiHeaders())
        }

        val uri = Uri.parse(request.url)
        return try {
            when (uri.host) {
                "boards" -> serveBoards(method, uri, request.body)
                "presets" -> servePresets(method, uri, request.body)
                "remotes" -> serveRemotes(method, uri, request.headers, request.body)
                "settings" -> serveSettings(method, request.body)
                "home-screen" -> serveHomeScreen(method, uri, request.body)
                else -> HkpResponse.json(404, jsonError("Unknown route: ${request.url}"))
            }.withApiHeaders()
        } catch (error: Exception) {
            HkpResponse.json(500, jsonError(error.message ?: "Request failed")).withApiHeaders()
        }
    }

    private fun serveBoards(method: String, uri: Uri, body: ByteArray): HkpResponse {
        val boardName = decode(normalizedPath(uri.path ?: ""))
        // "?meta=1" asks for the listing as objects carrying each board's
        // last-write time instead of bare names.
        val wantsMetadata = uri.getQueryParameter("meta")?.let { it != "0" } ?: false
        return when {
            method == "GET" && boardName.isEmpty() && wantsMetadata ->
                HkpResponse.json(
                    200,
                    JSONArray(
                        boardStore.listBoardEntries().map {
                            JSONObject(mapOf("name" to it.name, "modified" to it.modified))
                        },
                    ).toString(),
                )
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

    // The preset library: every file listed unparsed (the web app reads them
    // and skips what is not a preset), written and deleted by file name.
    private fun servePresets(method: String, uri: Uri, body: ByteArray): HkpResponse {
        val fileName = decode(normalizedPath(uri.path ?: ""))
        return when {
            method == "GET" && fileName.isEmpty() ->
                HkpResponse.json(
                    200,
                    JSONArray(
                        presetStore.listFiles().map {
                            JSONObject(mapOf("file" to it.file, "source" to it.source))
                        },
                    ).toString(),
                )
            method == "POST" && fileName.isNotEmpty() -> {
                if (body.isEmpty()) return HkpResponse.json(400, jsonError("Missing preset payload"))
                presetStore.write(fileName, body)
                HkpResponse.json(200, JSONObject(mapOf("file" to fileName)).toString())
            }
            method == "DELETE" && fileName.isNotEmpty() -> {
                presetStore.delete(fileName)
                HkpResponse.json(200, JSONObject(mapOf("file" to fileName)).toString())
            }
            else -> HkpResponse.json(405, jsonError("Unsupported presets request"))
        }
    }

    private fun serveRemotes(method: String, uri: Uri, headers: Map<String, String>, body: ByteArray): HkpResponse {
        val runtimeBaseUrl = resolveRuntimeBaseUrl()
            ?: return HkpResponse.json(503, jsonError("Runtime is still starting"))

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
        return remoteRuntime.handle(runtimeBaseUrl, method, subpath, uri.query, headers, body)
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

    // Boards pinned to the Home Screen (hkp://home-screen/…):
    //   GET    pins               the pinned list, newest first
    //   POST   pins               pin { kind, id, title } and ask the launcher
    //                             to place its icon ("requested"), or say the
    //                             launcher cannot ("manual")
    //   DELETE pins/<kind>/<id>   unpin
    //   POST   launch             take the board a shortcut asked to open, once
    private fun serveHomeScreen(method: String, uri: Uri, body: ByteArray): HkpResponse {
        val segments = uri.pathSegments
        fun pinsJson(pins: List<HomeScreenPin>) = JSONArray(pins.map { it.toJson() })

        return when {
            method == "GET" && segments == listOf("pins") ->
                HkpResponse.json(200, JSONObject(mapOf("pins" to pinsJson(homeScreenPins.list()))).toString())
            method == "POST" && segments == listOf("pins") -> {
                if (body.isEmpty()) return HkpResponse.json(400, jsonError("A pin needs a kind and an id"))
                val payload = JSONObject(String(body, Charsets.UTF_8))
                val id = payload.optString("id")
                val pin = HomeScreenPin(
                    kind = payload.optString("kind"),
                    id = id,
                    title = payload.optString("title").ifEmpty { id },
                )
                val pins = try {
                    homeScreenPins.add(pin)
                } catch (error: IllegalArgumentException) {
                    return HkpResponse.json(400, jsonError(error.message ?: "Invalid pin"))
                }
                val placement = if (homeScreenPins.requestOnHomeScreen(pin)) "requested" else "manual"
                HkpResponse.json(
                    200,
                    JSONObject(mapOf("pins" to pinsJson(pins), "homeScreen" to placement)).toString(),
                )
            }
            method == "DELETE" && segments.size == 3 && segments[0] == "pins" ->
                HkpResponse.json(
                    200,
                    JSONObject(mapOf("pins" to pinsJson(homeScreenPins.remove(segments[1], segments[2])))).toString(),
                )
            method == "POST" && segments == listOf("launch") -> {
                val target = LaunchInbox.take()?.let { (kind, id) -> JSONObject(mapOf("kind" to kind, "id" to id)) }
                HkpResponse.json(200, JSONObject().put("target", target ?: JSONObject.NULL).toString())
            }
            else -> HkpResponse.json(404, jsonError("Unknown home-screen request: $method ${uri.path}"))
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
