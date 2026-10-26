package com.readymadeit.meander.bridge

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.net.Uri
import com.readymadeit.meander.MainActivity
import com.readymadeit.meander.R
import org.json.JSONArray
import org.json.JSONObject

/**
 * A board pinned to the Home Screen: a saved board by its name, a demo by its
 * slug — the two kinds of board whose identity outlives a session. `title` is
 * what the launcher shows under the icon.
 */
data class HomeScreenPin(val kind: String, val id: String, val title: String) {
    val shortcutId: String get() = "$kind:$id"

    fun toJson(): JSONObject = JSONObject(mapOf("kind" to kind, "id" to id, "title" to title))
}

/**
 * The pinned boards, newest first, and the launcher shortcuts made of them.
 *
 * Android, unlike iOS, lets an app ask for an icon on the Home Screen
 * (`requestPinShortcut`): the launcher asks the person once and places it. The
 * pinned list also feeds the dynamic shortcuts — the menu on a long press of
 * the Readymade icon. Each shortcut opens `readymade://board?…` in MainActivity.
 */
class HomeScreenPins(private val context: Context) {
    private val prefs = context.getSharedPreferences("home-screen", Context.MODE_PRIVATE)
    private val shortcuts: ShortcutManager? = context.getSystemService(ShortcutManager::class.java)

    fun list(): List<HomeScreenPin> {
        val array = JSONArray(prefs.getString(KEY_PINS, "[]") ?: "[]")
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val kind = item.optString("kind")
            val id = item.optString("id")
            if (kind in KINDS && id.isNotEmpty()) {
                HomeScreenPin(kind, id, item.optString("title").ifEmpty { id })
            } else {
                null
            }
        }
    }

    /** Pins `pin` (or renames it when it is pinned already) at the front. */
    fun add(pin: HomeScreenPin): List<HomeScreenPin> {
        require(pin.kind in KINDS && pin.id.isNotEmpty()) { "Only a saved board or a demo can be pinned" }
        val pins = listOf(pin) + list().filterNot { it.kind == pin.kind && it.id == pin.id }
        write(pins)
        return pins
    }

    fun remove(kind: String, id: String): List<HomeScreenPin> {
        val pins = list().filterNot { it.kind == kind && it.id == id }
        write(pins)
        // An icon already on the Home Screen cannot be taken off by the app,
        // only disabled: the launcher greys it out and says why when tapped.
        shortcuts?.disableShortcuts(listOf("$kind:$id"), context.getString(R.string.home_screen_unpinned))
        return pins
    }

    /**
     * Asks the launcher to place `pin` on the Home Screen. False where the
     * launcher cannot (a launcher without pinned shortcuts): the board is still
     * in the long-press menu.
     */
    fun requestOnHomeScreen(pin: HomeScreenPin): Boolean {
        val manager = shortcuts ?: return false
        if (!manager.isRequestPinShortcutSupported) {
            return false
        }
        // A board pinned, unpinned and pinned again has a disabled icon left.
        manager.enableShortcuts(listOf(pin.shortcutId))
        return manager.requestPinShortcut(shortcutFor(pin), null)
    }

    private fun write(pins: List<HomeScreenPin>) {
        prefs.edit().putString(KEY_PINS, JSONArray(pins.map { it.toJson() }).toString()).apply()
        updateDynamicShortcuts(pins)
    }

    private fun updateDynamicShortcuts(pins: List<HomeScreenPin>) {
        val manager = shortcuts ?: return
        val shown = pins.take(manager.maxShortcutCountPerActivity.coerceAtMost(4))
        manager.dynamicShortcuts = shown.map(::shortcutFor)
        // Keeps an icon already on the Home Screen named as its board is.
        manager.updateShortcuts(pins.map(::shortcutFor))
    }

    private fun shortcutFor(pin: HomeScreenPin): ShortcutInfo =
        ShortcutInfo.Builder(context, pin.shortcutId)
            .setShortLabel(pin.title.take(SHORT_LABEL_LENGTH))
            .setLongLabel(pin.title)
            .setIcon(Icon.createWithResource(context, R.drawable.ic_shortcut_board))
            .setIntent(
                Intent(Intent.ACTION_VIEW, launchUri(pin.kind, pin.id))
                    .setClass(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
            )
            .build()

    companion object {
        private const val KEY_PINS = "pins"
        private const val SHORT_LABEL_LENGTH = 25
        val KINDS = setOf("saved", "demo")

        /** What opens a board: readymade://board?saved=<name> or ?demo=<slug>. */
        fun launchUri(kind: String, id: String): Uri =
            Uri.Builder().scheme("readymade").authority("board").appendQueryParameter(kind, id).build()

        /** The board a launch link names, or null for any other link. */
        fun target(uri: Uri): Pair<String, String>? {
            if (uri.scheme?.lowercase() != "readymade" || uri.host != "board") {
                return null
            }
            return KINDS.firstNotNullOfOrNull { kind ->
                uri.getQueryParameter(kind)?.takeIf { it.isNotEmpty() }?.let { kind to it }
            }
        }
    }
}

/**
 * The board a shortcut asked to open, kept until the web app takes it
 * (hkp://home-screen/launch). Taking rather than delivering is what makes a
 * cold start the same as a warm one: the intent arrives before the page
 * exists, and the page asks for it as it mounts.
 */
object LaunchInbox {
    @Volatile
    private var pending: Pair<String, String>? = null

    fun offer(kind: String, id: String) {
        pending = kind to id
    }

    @Synchronized
    fun take(): Pair<String, String>? {
        val target = pending
        pending = null
        return target
    }
}
