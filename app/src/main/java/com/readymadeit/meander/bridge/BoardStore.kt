package com.readymadeit.meander.bridge

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class BoardStore(context: Context) {
    private val boardsDir = File(context.filesDir, "Boards")

    /** A saved board with its last-write time as ISO 8601 UTC. */
    data class BoardEntry(val name: String, val modified: String)

    fun listBoards(): List<String> = listBoardEntries().map { it.name }

    fun listBoardEntries(): List<BoardEntry> {
        ensureDirectory()
        return boardsDir
            .listFiles { file -> file.isFile && file.extension == "json" }
            ?.map { BoardEntry(it.nameWithoutExtension, isoTime(it.lastModified())) }
            ?.sortedBy { it.name }
            ?: emptyList()
    }

    private fun isoTime(epochMillis: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(epochMillis))

    fun loadBoard(name: String): ByteArray =
        boardFile(validate(name)).readBytes()

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

    private fun ensureDirectory() {
        if (!boardsDir.exists()) {
            boardsDir.mkdirs()
        }
    }

    private fun boardFile(name: String): File = File(boardsDir, "$name.json")

    private fun validate(name: String): String {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "Invalid board name" }
        require(!trimmed.contains("/") && !trimmed.contains("..")) { "Forbidden path" }
        return trimmed
    }
}
