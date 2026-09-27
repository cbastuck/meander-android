package com.readymadeit.meander.bridge

import android.content.Context
import java.io.File

/**
 * The preset library: preset files — blocks among them — in files/Presets, one
 * per file as the web app names them, or as whoever put them there did.
 */
class PresetStore(context: Context) {
    private val presetsDir = File(context.filesDir, "Presets")

    data class PresetFile(val file: String, val source: String)

    fun listFiles(): List<PresetFile> {
        ensureDirectory()
        return presetsDir
            .listFiles { file -> file.isFile && file.extension == "json" && !file.name.startsWith(".") }
            ?.sortedBy { it.name }
            ?.map { PresetFile(it.name, it.readText(Charsets.UTF_8)) }
            ?: emptyList()
    }

    fun write(name: String, data: ByteArray) {
        ensureDirectory()
        presetFile(validate(name)).writeBytes(data)
    }

    fun delete(name: String) {
        val file = presetFile(validate(name))
        if (file.exists()) {
            file.delete()
        }
    }

    private fun ensureDirectory() {
        if (!presetsDir.exists()) {
            presetsDir.mkdirs()
        }
    }

    private fun presetFile(name: String): File = File(presetsDir, name)

    private fun validate(name: String): String {
        require(name.endsWith(".json") && !name.startsWith(".")) { "Invalid preset file name" }
        require(!name.contains("/") && !name.contains("..")) { "Forbidden path" }
        return name
    }
}
