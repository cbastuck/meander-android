package com.readymadeit.meander.bridge

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The values behind the aliases a board refers to (`{{secret.<alias>}}`).
 *
 * The phone's counterpart of the desktop's `~/.hkp/vault.json`
 * (`meander/backend/vault.h`), with the same entry: a value and the hosts it
 * may be sent to, `{ "value": "…", "audience": ["imap.gmail.com"] }`. An empty
 * audience is unconstrained.
 *
 * The Android Keystore holds keys, not data, so each entry is encrypted with an
 * AES key that lives there and never leaves it, and the ciphertext is kept in
 * the app's private preferences. A backup carries the ciphertext but not the
 * key, so an entry restored onto another device reads as absent: secrets stay
 * on the device that was given them, as each provisioner keeps its own vault
 * (plans/TODO-SECRETS.md, Prio B).
 */
class DeviceVault(context: Context) {
    data class Entry(val value: String, val audience: List<String>)

    private val prefs = context.getSharedPreferences("secrets", Context.MODE_PRIVATE)

    /** The names held, without the values. */
    @Synchronized
    fun aliases(): List<String> = all().keys.sorted()

    @Synchronized
    fun entry(alias: String): Entry? {
        val sealed = prefs.getString(alias, null) ?: return null
        return try {
            val entry = JSONObject(open(sealed))
            val audience = entry.optJSONArray("audience") ?: JSONArray()
            Entry(
                value = entry.getString("value"),
                audience = (0 until audience.length()).map { audience.optString(it) }.filter { it.isNotEmpty() },
            )
        } catch (error: Exception) {
            // Sealed with a key this device does not have (restored from a
            // backup), or damaged. Either way there is no value to give.
            null
        }
    }

    /** Every entry that can be read, for injecting into the app's page. */
    @Synchronized
    fun all(): Map<String, Entry> =
        prefs.all.keys.mapNotNull { alias -> entry(alias)?.let { alias to it } }.toMap()

    /** Sets the value, leaving the audience the entry already had. */
    @Synchronized
    fun setValue(alias: String, value: String) {
        write(alias, Entry(value, entry(alias)?.audience ?: emptyList()))
    }

    /**
     * Sets where an entry may be sent, leaving its value alone. False when
     * there is no such entry: an audience constrains a value, and with none
     * there is nothing to constrain.
     */
    @Synchronized
    fun setAudience(alias: String, audience: List<String>): Boolean {
        val held = entry(alias) ?: return false
        write(alias, Entry(held.value, audience.filter { it.isNotEmpty() }))
        return true
    }

    @Synchronized
    fun delete(alias: String) {
        prefs.edit().remove(alias).commit()
    }

    private fun write(alias: String, entry: Entry) {
        val plain = JSONObject(mapOf("value" to entry.value, "audience" to JSONArray(entry.audience))).toString()
        check(prefs.edit().putString(alias, seal(plain)).commit()) { "The secret could not be written" }
    }

    private fun seal(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val sealed = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(sealed, Base64.NO_WRAP)
    }

    private fun open(sealed: String): String {
        val bytes = Base64.decode(sealed, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES))
        return String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8)
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        private const val KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "readymade-secrets"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
    }
}

/**
 * Which board may hand which secrets to which runtime: the remembered answers
 * to the consent prompt, keyed as `hkp-frontend/src/core/secretConsent.ts`
 * says. The desktop keeps them in `~/.hkp/grants.json`
 * (`meander/backend/grants.h`); here they are a file in the app's private
 * storage. Grants hold no secrets, they name them, so they are not sealed.
 */
class SecretGrants(context: Context) {
    private val file = File(context.filesDir, "grants.json")

    @Synchronized
    fun all(): Map<String, List<String>> {
        val grants = try {
            if (file.exists()) JSONObject(file.readText()) else JSONObject()
        } catch (error: Exception) {
            JSONObject()
        }
        return grants.keys().asSequence().mapNotNull { key ->
            val aliases = grants.optJSONArray(key) ?: return@mapNotNull null
            val named = (0 until aliases.length()).map { aliases.optString(it) }.filter { it.isNotEmpty() }
            if (named.isEmpty()) null else key to named
        }.toMap()
    }

    /**
     * Adds aliases to a grant, leaving the rest of it alone: a board that comes
     * to need one more secret is asked about that one, and the answer must not
     * drop what was already agreed.
     */
    @Synchronized
    fun grant(key: String, aliases: List<String>) {
        val grants = all().toMutableMap()
        val merged = ((grants[key] ?: emptyList()) + aliases.filter { it.isNotEmpty() }).toSortedSet()
        if (merged.isEmpty()) return
        grants[key] = merged.toList()
        write(grants)
    }

    /** Forgets one grant entirely, so the next release asks again. */
    @Synchronized
    fun revoke(key: String) {
        val grants = all().toMutableMap()
        if (grants.remove(key) != null) write(grants)
    }

    private fun write(grants: Map<String, List<String>>) {
        val json = JSONObject()
        grants.forEach { (key, aliases) -> json.put(key, JSONArray(aliases)) }
        val partial = File(file.parentFile, "${file.name}.partial")
        partial.writeText(json.toString())
        check(partial.renameTo(file)) { "The grant could not be written" }
    }
}

/**
 * What the app's page is told about secrets, and how a request to
 * `hkp://secrets` proves it came from that page.
 *
 * The values and grants go into the app's own `index.html` as it is served, so
 * the page has them before any board loads (provisioning starts as soon as one
 * does) and every load reads them afresh. No other page is served that way.
 *
 * The route answers only requests carrying `token`, handed out the same way.
 * Every other `hkp://` route answers any caller; this one must not, because
 * the bridge object is given to every frame in the page — a service's own UI,
 * an embedded site — and none of those may list, replace or constrain what is
 * held.
 */
object SecretsPage {
    const val TOKEN_HEADER = "X-Hkp-Secrets-Token"

    /** Drawn once per process: anything that can read it can already read
     *  every value, because both are given to the same page. */
    val token: String = ByteArray(32).also { SecureRandom().nextBytes(it) }
        .joinToString("") { "%02x".format(it) }

    fun script(vault: DeviceVault, grants: SecretGrants): String {
        val values = JSONObject()
        vault.all().forEach { (alias, entry) ->
            values.put(alias, JSONObject(mapOf("value" to entry.value, "audience" to JSONArray(entry.audience))))
        }
        val granted = JSONObject()
        grants.all().forEach { (key, aliases) -> granted.put(key, JSONArray(aliases)) }
        return """
            window.__HKP_VAULT__ = ${inline(values.toString())};
            window.__HKP_GRANTS__ = ${inline(granted.toString())};
            window.__HKP_SECRETS_TOKEN__ = ${inline(JSONObject.quote(token))};
        """.trimIndent()
    }

    /** JSON as it may stand inside an HTML `<script>`: a value holding
     *  `</script>` must not end the element it is written into. */
    private fun inline(json: String): String = json.replace("</", "<\\/")
}
