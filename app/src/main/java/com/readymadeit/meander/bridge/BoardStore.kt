package com.readymadeit.meander.bridge

import android.content.Context
import java.io.File

class BoardStore(context: Context) {
    private val boardsDir = File(context.filesDir, "Boards")

    fun listBoards(): List<String> {
        ensureDirectory()
        return boardsDir
            .listFiles { file -> file.isFile && file.extension == "json" }
            ?.map { it.nameWithoutExtension }
            ?.sorted()
            ?: emptyList()
    }

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
