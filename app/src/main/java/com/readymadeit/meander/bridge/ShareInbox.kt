package com.readymadeit.meander.bridge

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Items shared into Readymade from another app, kept until the page has them.
 *
 * Each is the envelope the iOS share extension writes (`SharedItem` in
 * meander-ios), so the web app has one shape for both shells:
 * `{ id, receivedAt, url?, title?, text?, boardName?, file? }`. Sharing opens
 * the app on Android, so no board is chosen here: `boardName` stays unset and
 * the in-app picker asks.
 *
 * One `<id>.json` per item and its bytes as `<id>.payload`, in the app's files:
 * a share that arrives before the page has loaded, or while the process is
 * recreated, is still there once it has. The page fetches the bytes from
 * hkp://share-files/<id> as it injects the share, and releases them after.
 */
class ShareInbox(context: Context) {
    private val dir = File(context.filesDir, "share-inbox")
    private val packageName = context.packageName
    private val packageManager = context.packageManager

    /** All pending items, oldest first. */
    fun pending(): List<JSONObject> =
        (dir.listFiles { file -> file.extension == "json" } ?: emptyArray())
            .mapNotNull { file -> runCatching { JSONObject(file.readText()) }.getOrNull() }
            .sortedBy { it.optDouble("receivedAt") }

    /** Removes an item once the page has it, so it is delivered exactly once. */
    fun remove(id: String) {
        if (isValidId(id)) File(dir, "$id.json").delete()
    }

    fun readPayload(id: String): ByteArray? =
        payloadFile(id)?.takeIf { it.exists() }?.readBytes()

    fun removePayload(id: String) {
        payloadFile(id)?.delete()
    }

    /**
     * Drops payloads nothing released (the in-app picker was cancelled, the
     * board is gone). Anything older than a week is safe to drop, as on iOS.
     */
    fun sweepStalePayloads(maxAgeMillis: Long = 7L * 24 * 3600 * 1000) {
        val cutoff = System.currentTimeMillis() - maxAgeMillis
        dir.listFiles { file -> file.extension == "payload" && file.lastModified() < cutoff }
            ?.forEach { it.delete() }
    }

    /**
     * Reads a share intent (ACTION_SEND or ACTION_SEND_MULTIPLE) into the
     * inbox. Each file becomes an item of its own, since an item carries one;
     * the text, link and subject go with the first. Blocking: the bytes are
     * copied here, so call it off the main thread.
     */
    fun receive(intent: Intent, resolver: ContentResolver): Received {
        val subject = intent.getCharSequenceExtra(Intent.EXTRA_SUBJECT)?.toString()?.takeIf { it.isNotBlank() }
        val shared = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        // A link arrives as text. Only a text that is nothing but a link is
        // taken as one; anything around it stays text, the link included.
        val url = shared?.takeIf { LINK.matches(it) }
        val text = shared?.takeIf { url == null }

        var stored = 0
        var tooLarge = 0
        val streams = streamsOf(intent)
        streams.forEachIndexed { index, uri ->
            val first = index == 0
            val id = newId()
            when (val payload = copyPayload(uri, id, resolver, fallbackType = intent.type)) {
                is Payload.Copied -> {
                    write(envelope(id, if (first) url else null, if (first) subject else null, if (first) text else null, payload.meta))
                    stored += 1
                }
                Payload.TooLarge -> tooLarge += 1
                Payload.Unreadable -> Unit
            }
        }
        if (streams.isEmpty() && (url != null || text != null)) {
            write(envelope(newId(), url, subject, text, null))
            stored += 1
        }
        return Received(stored, tooLarge)
    }

    data class Received(val stored: Int, val tooLarge: Int)

    private sealed interface Payload {
        data class Copied(val meta: JSONObject) : Payload
        data object TooLarge : Payload
        data object Unreadable : Payload
    }

    private fun envelope(id: String, url: String?, title: String?, text: String?, file: JSONObject?): JSONObject =
        JSONObject().apply {
            put("id", id)
            put("receivedAt", System.currentTimeMillis() / 1000.0)
            url?.let { put("url", it) }
            title?.let { put("title", it) }
            text?.let { put("text", it) }
            file?.let { put("file", it) }
        }

    private fun write(item: JSONObject) {
        dir.mkdirs()
        val id = item.getString("id")
        // Written aside and renamed, so pending() never reads half an item.
        val partial = File(dir, "$id.json.partial")
        partial.writeText(item.toString())
        partial.renameTo(File(dir, "$id.json"))
    }

    /**
     * Copies a shared stream to `<id>.payload` with the file's metadata.
     * Copied rather than kept as a URI: the permission to read it ends with
     * the activity that received the share.
     */
    private fun copyPayload(uri: Uri, id: String, resolver: ContentResolver, fallbackType: String?): Payload {
        dir.mkdirs()
        val target = File(dir, "$id.payload")
        val size = runCatching {
            resolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_PAYLOAD_BYTES) return@use -1L
                        output.write(buffer, 0, read)
                    }
                    total
                }
            }
        }.getOrNull()
        if (size == null || size < 0) {
            target.delete()
            return if (size == null) Payload.Unreadable else Payload.TooLarge
        }
        val mimeType = resolver.getType(uri)
            ?: fallbackType?.takeUnless { it.contains('*') }
            ?: "application/octet-stream"
        return Payload.Copied(
            JSONObject().apply {
                put("name", displayName(uri, resolver) ?: uri.lastPathSegment ?: "shared-file")
                put("mimeType", mimeType)
                put("size", size)
            },
        )
    }

    private fun displayName(uri: Uri, resolver: ContentResolver): String? =
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()?.takeIf { it.isNotBlank() }

    /**
     * The shared files: EXTRA_STREAM, or the clip data some apps use alone.
     * Only those another app hands over (isForeign); the rest are dropped.
     */
    private fun streamsOf(intent: Intent): List<Uri> = sharedUris(intent).filter(::isForeign)

    /**
     * Whether `uri` is something another app holds and granted: a content URI
     * of a provider that is not this app's. Any app can send a share, and
     * what is shared is read with this app's own rights, so a file URI or one
     * of this app's providers would have it copy its private files — the
     * board library, the history, the grants — into a board that may send
     * them anywhere.
     */
    @Suppress("DEPRECATION")
    private fun isForeign(uri: Uri): Boolean {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return false
        val authority = uri.authority ?: return false
        return packageManager.resolveContentProvider(authority, 0)?.packageName != packageName
    }

    @Suppress("DEPRECATION")
    private fun sharedUris(intent: Intent): List<Uri> {
        val extra: List<Uri> = when (intent.action) {
            Intent.ACTION_SEND_MULTIPLE ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
                }.orEmpty()
            else ->
                listOfNotNull(
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                    } else {
                        intent.getParcelableExtra(Intent.EXTRA_STREAM)
                    },
                )
        }
        if (extra.isNotEmpty()) return extra
        val clip = intent.clipData ?: return emptyList()
        return (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
    }

    private fun payloadFile(id: String): File? = if (isValidId(id)) File(dir, "$id.payload") else null

    private fun newId(): String = UUID.randomUUID().toString().uppercase()

    companion object {
        /**
         * Below the iOS shell's 100 MB: on Android the page receives the bytes
         * through the JavaScript bridge as base64 inside a JSON string, which
         * holds several copies of a file in memory at once.
         */
        const val MAX_PAYLOAD_BYTES = 25L * 1024 * 1024

        private val ID = Regex("^[A-Za-z0-9-]+$")
        private val LINK = Regex("^https?://\\S+$", RegexOption.IGNORE_CASE)

        fun isValidId(id: String): Boolean = ID.matches(id)

        fun isShare(intent: Intent?): Boolean =
            intent?.action == Intent.ACTION_SEND || intent?.action == Intent.ACTION_SEND_MULTIPLE
    }
}
