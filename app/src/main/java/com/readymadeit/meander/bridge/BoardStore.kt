package com.readymadeit.meander.bridge

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * A board's name as it names the board's file — the desktop's rule
 * (`meander/backend/boardName.h`), so every host names a board the same way:
 * ASCII letters, digits, `-`, `_` and spaces as they are, every other UTF-8
 * byte as `%XX`. No separator and no dot survives, so any name is one path
 * component.
 */
object BoardFileName {
    const val EXTENSION = "hkpp"

    fun encode(name: String): String {
        val out = StringBuilder()
        for (byte in name.toByteArray(Charsets.UTF_8)) {
            val c = byte.toInt() and 0xFF
            val kept = c in 0x30..0x39 || c in 0x41..0x5A || c in 0x61..0x7A ||
                c == 0x2D || c == 0x5F || c == 0x20
            if (kept) out.append(c.toChar()) else out.append("%%%02X".format(c))
        }
        return out.toString()
    }

    fun decode(storageName: String): String {
        val bytes = storageName.toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream()
        var i = 0
        while (i < bytes.size) {
            val hex = if (bytes[i] == '%'.code.toByte() && i + 2 < bytes.size) {
                String(bytes, i + 1, 2, Charsets.US_ASCII).toIntOrNull(16)
            } else {
                null
            }
            if (hex != null) {
                out.write(hex)
                i += 3
            } else {
                out.write(bytes[i].toInt())
                i += 1
            }
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    /** The desktop's rule for which names are accepted: non-empty, no control characters. */
    fun isValid(name: String): Boolean =
        name.isNotEmpty() && name.none { it.code < 0x20 || it.code == 0x7F }
}

/**
 * The saved boards: `<encoded name>.hkpp` in files/Boards. Boards written
 * before this version are `<name>.json`, named as typed; they are renamed the
 * first time the store is used.
 */
class BoardStore(context: Context) {
    private val boardsDir = File(context.filesDir, "Boards")

    /** A saved board with its last-write time as ISO 8601 UTC. */
    data class BoardEntry(val name: String, val modified: String)

    init {
        renameLegacyBoards()
    }

    fun listBoards(): List<String> = listBoardEntries().map { it.name }

    fun listBoardEntries(): List<BoardEntry> {
        ensureDirectory()
        return boardsDir
            .listFiles { file -> file.isFile && file.extension == BoardFileName.EXTENSION }
            ?.map { BoardEntry(BoardFileName.decode(it.nameWithoutExtension), isoTime(it.lastModified())) }
            ?.sortedBy { it.name }
            ?: emptyList()
    }

    fun loadBoard(name: String): ByteArray {
        val file = boardFile(validate(name))
        if (!file.exists()) {
            throw NoSuchElementException("No board called $name")
        }
        return file.readBytes()
    }

    fun saveBoard(name: String, data: ByteArray) {
        ensureDirectory()
        boardFile(validate(name)).writeBytes(data)
    }

    fun deleteBoard(name: String) {
        val file = boardFile(validate(name))
        if (file.exists()) {
            file.delete()
        }
    }

    private fun renameLegacyBoards() {
        val legacy = boardsDir.listFiles { file -> file.isFile && file.extension == "json" } ?: return
        for (file in legacy) {
            var name = file.nameWithoutExtension
            if (!BoardFileName.isValid(name)) continue
            val base = name
            var counter = 2
            while (boardFile(name).exists()) {
                name = "$base (${counter++})"
            }
            if (file.renameTo(boardFile(name)).not()) {
                file.copyTo(boardFile(name))
                file.delete()
            }
        }
    }

    private fun ensureDirectory() {
        if (!boardsDir.exists()) {
            boardsDir.mkdirs()
        }
    }

    private fun boardFile(name: String): File =
        File(boardsDir, "${BoardFileName.encode(name)}.${BoardFileName.EXTENSION}")

    private fun validate(name: String): String {
        require(BoardFileName.isValid(name)) { "Invalid board name" }
        return name
    }

    private fun isoTime(epochMillis: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(epochMillis))
}

/**
 * A board's history: the snapshots taken as it was edited, newest first, in
 * files/History. The desktop's rules (`handlePushBoardSnapshot` in
 * `meander/backend/schemeHandler.cpp`): at most 50 entries, and consecutive
 * "config" entries replace each other.
 */
class BoardHistoryStore(context: Context) {
    private val directory = File(context.filesDir, "History")

    private fun file(name: String) = File(directory, "${BoardFileName.encode(name)}.history")

    fun summaries(): JSONArray {
        val files = directory.listFiles { file -> file.isFile && file.extension == "history" } ?: return JSONArray()
        return JSONArray(
            files.map { file ->
                JSONObject().apply {
                    put("name", BoardFileName.decode(file.nameWithoutExtension))
                    entries(file).optJSONObject(0)?.optString("timestamp")?.takeIf { it.isNotEmpty() }
                        ?.let { put("latestTimestamp", it) }
                }
            },
        )
    }

    fun load(name: String): String = if (file(name).exists()) file(name).readText() else "[]"

    fun push(name: String, body: ByteArray) {
        val entry = JSONObject(String(body, Charsets.UTF_8))
        // The registry is the runtimes' to say again on load; keeping it would
        // multiply every snapshot by every service's description.
        entry.optJSONObject("snapshot")?.remove("registry")

        val history = entries(file(name))
        val isConfig = { e: JSONObject? -> e?.optString("label") == "config" }
        val next = JSONArray()
        next.put(entry)
        val keepFrom = if (isConfig(entry) && isConfig(history.optJSONObject(0))) 1 else 0
        for (i in keepFrom until history.length()) {
            if (next.length() >= MAX_DEPTH) break
            next.put(history.get(i))
        }

        directory.mkdirs()
        file(name).writeText(next.toString())
    }

    fun clear(name: String) {
        file(name).delete()
    }

    private fun entries(file: File): JSONArray =
        try {
            if (file.exists()) JSONArray(file.readText()) else JSONArray()
        } catch (error: Exception) {
            JSONArray()
        }

    companion object {
        const val MAX_DEPTH = 50
    }
}
