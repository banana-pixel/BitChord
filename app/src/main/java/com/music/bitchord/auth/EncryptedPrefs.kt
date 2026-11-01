package com.music.bitchord.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.music.bitchord.data.DebugLog as Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Encrypted preferences that repair themselves when their keyset stops
 * matching the device's Keystore.
 *
 * An [EncryptedSharedPreferences] file carries its Tink keyset wrapped by a
 * Keystore key, and the Keystore key never leaves the device. Anything that
 * restores the file without the key (auto-backup, a device transfer) leaves a
 * keyset nothing can unwrap: every open fails with keymint's
 * `VERIFICATION_FAILED`, measured on-device on every launch, and the old
 * fallback then kept credentials in plain prefs forever. The file's contents
 * are unreadable either way, so it is deleted and recreated, and whatever the
 * plain fallback collected meanwhile is moved back in.
 *
 * A Keystore that can't be used at all, which a handful of OEM builds ship,
 * still degrades to the plain file rather than failing launch.
 */
internal object EncryptedPrefs {

    private const val TAG = "BitChord"

    /** Tink's own entry inside the prefs file; its presence is what makes a failed open a stale keyset. */
    private const val KEYSET_ENTRY = "__androidx_security_crypto_encrypted_prefs_key_keyset__"

    /** One store per file per process; every failed open was a Keystore round trip. */
    private val opened = HashMap<String, SharedPreferences>()

    @Synchronized
    fun open(context: Context, name: String, plainName: String): SharedPreferences =
        opened.getOrPut(name) { resolve(context.applicationContext, name, plainName) }

    private fun resolve(context: Context, name: String, plainName: String): SharedPreferences {
        val decision = decide(
            name = name,
            attempt = { create(context, name) as SharedPreferences },
            // A keyset this device cannot unwrap is recoverable: the ciphertext
            // was unreadable anyway, so recreating it loses nothing. Only when
            // the file actually carries that keyset, though — an unavailable
            // Keystore has no keyset to be stale.
            staleKeyset = { e ->
                isUnverifiable(e) &&
                    context.getSharedPreferences(name, Context.MODE_PRIVATE).contains(KEYSET_ENTRY)
            },
            recreate = { context.deleteSharedPreferences(name) },
        )
        if (decision is Decision.UsePlain) {
            return context.getSharedPreferences(plainName, Context.MODE_PRIVATE)
        }
        val encrypted = (decision as Decision.UseEncrypted).store
        moveIn(context, plainName, encrypted, name)
        return encrypted
    }

    /**
     * Whether encrypted storage is currently unavailable and the plain fallback
     * is in use.
     *
     * Surfaced in Settings so a listener can see that credentials are being
     * written without encryption. The fallback still works — that is the point
     * of keeping it — but weakening credential storage must not happen silently.
     * Cleared as soon as a store opens successfully again.
     */
    private val _degraded = MutableStateFlow(false)
    val degraded: StateFlow<Boolean> = _degraded.asStateFlow()

    /**
     * Which stores are degraded, by name.
     *
     * Per store, not a single flag: auth and sources open independently, so a
     * later healthy store must not clear a warning the earlier one raised. The
     * public [degraded] is true while any name is in here.
     */
    private val degradedNames = mutableSetOf<String>()
    private val degradedLock = Any()

    /** The outcome of trying to open a store. */
    internal sealed interface Decision<out T> {
        data class UseEncrypted<T>(val store: T) : Decision<T>
        data class UsePlain(val reason: String) : Decision<Nothing>
    }

    /**
     * Decides which store [name] resolves to, opening it through [attempt].
     *
     * A failure [staleKeyset] recognises is retried once after [recreate],
     * because that case is recoverable. Any other [Exception] keeps working
     * through the plain fallback and raises [degraded].
     *
     * Errors are deliberately not caught. An `OutOfMemoryError` during Tink
     * setup is not a reason to start writing credentials in the clear, and
     * swallowing it would hide a real fault behind a silent downgrade.
     */
    internal fun <T> decide(
        name: String,
        attempt: () -> T,
        staleKeyset: (Throwable) -> Boolean,
        recreate: () -> Unit,
    ): Decision<T> {
        try {
            val store = attempt()
            markHealthy(name)
            return Decision.UseEncrypted(store)
        } catch (e: Exception) {
            if (!staleKeyset(e)) {
                Log.w(TAG, "EncryptedSharedPreferences unavailable for $name: ${describe(e)}")
                return degrade(name, describe(e))
            }
            Log.w(TAG, "$name's keyset no longer matches this device's Keystore (${describe(e)}); recreating it")
        }

        // Recoverable: drop the unreadable keyset and make a new one.
        recreate()
        return try {
            val store = attempt()
            markHealthy(name)
            Decision.UseEncrypted(store)
        } catch (retry: Exception) {
            Log.w(TAG, "$name could not be recreated: ${describe(retry)}")
            degrade(name, describe(retry))
        }
    }

    private fun degrade(name: String, reason: String): Decision<Nothing> {
        Log.w(TAG, "$name is being stored unencrypted: $reason")
        synchronized(degradedLock) {
            degradedNames += name
            _degraded.value = true
        }
        return Decision.UsePlain(reason)
    }

    private fun markHealthy(name: String) {
        synchronized(degradedLock) {
            if (degradedNames.remove(name)) _degraded.value = degradedNames.isNotEmpty()
        }
    }

    /** Test hook: forget every degraded store. */
    internal fun resetDegradedForTest() {
        synchronized(degradedLock) {
            degradedNames.clear()
            _degraded.value = false
        }
    }

    private fun create(context: Context, name: String): SharedPreferences =
        EncryptedSharedPreferences.create(
            context,
            name,
            MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )

    /**
     * Moves what an earlier fallback wrote into the encrypted store, then
     * deletes the plain file.
     *
     * Plain values win over encrypted ones: while the fallback was in use it
     * was the only store being written. The plain file is deleted only after
     * every value reads back from the encrypted store, so a failure part-way
     * leaves it as the source of truth and the next launch tries again.
     */
    private fun moveIn(context: Context, plainName: String, encrypted: SharedPreferences, name: String) {
        val entries = context.getSharedPreferences(plainName, Context.MODE_PRIVATE).all
        if (entries.isEmpty()) return
        val edit = encrypted.edit()
        for ((key, value) in entries) {
            when (value) {
                is String -> edit.putString(key, value)
                is Boolean -> edit.putBoolean(key, value)
                is Int -> edit.putInt(key, value)
                is Long -> edit.putLong(key, value)
                is Float -> edit.putFloat(key, value)
                is Set<*> -> edit.putStringSet(key, value.filterIsInstance<String>().toSet())
            }
        }
        if (!edit.commit()) {
            Log.w(TAG, "could not write $plainName into $name; keeping the plain copy")
            return
        }
        val stored = encrypted.all
        val unmatched = entries.keys.filter { stored[it] != entries[it] }
        if (unmatched.isNotEmpty()) {
            Log.w(TAG, "${unmatched.size} of ${entries.size} entries from $plainName did not read back; keeping the plain copy")
            return
        }
        context.deleteSharedPreferences(plainName)
        Log.d(TAG, "moved ${entries.size} entries from $plainName into $name")
    }

    /**
     * Whether [e] says the stored keyset failed verification, which no retry
     * can change, as opposed to a Keystore that is merely unavailable.
     */
    private fun isUnverifiable(e: Throwable): Boolean = causes(e).any {
        it is javax.crypto.AEADBadTagException ||
            it.javaClass.name.endsWith("InvalidProtocolBufferException") ||
            // android.security.KeyStoreException is API 33+; matched by name so older devices never load it.
            (it.javaClass.name == "android.security.KeyStoreException" &&
                it.message.orEmpty().contains("VERIFICATION_FAILED", ignoreCase = true))
    }

    private fun describe(e: Throwable): String =
        causes(e).joinToString(" <- ") { "${it.javaClass.name}: ${it.message}" }

    private fun causes(e: Throwable): List<Throwable> =
        generateSequence(e) { it.cause?.takeIf { cause -> cause !== it } }.take(8).toList()
}
