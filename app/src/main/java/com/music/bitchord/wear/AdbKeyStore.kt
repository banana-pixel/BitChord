package com.music.bitchord.wear

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyFactory
import java.security.KeyStore
import java.security.spec.PKCS8EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Seals something with a key the Android Keystore holds and the app never sees.
 *
 * The key material is generated inside the Keystore and cannot be exported; the
 * app can only ask it to encrypt or decrypt. [encrypt] and [decrypt] are the
 * only operations [AdbKeyStore] needs.
 */
internal interface KeyProtector {
    fun encrypt(plaintext: ByteArray): ByteArray
    fun decrypt(payload: ByteArray): ByteArray
}

/**
 * The stored ADB identity could not be read.
 *
 * Distinct from "there is no identity yet": that case generates a new keypair.
 * This one means a key exists on disk and cannot be recovered, so the caller
 * must tell the listener that the watch has to be paired again rather than
 * quietly replacing the identity the watch already trusts.
 */
internal class AdbIdentityUnavailableException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

/**
 * The ADB private key as it lives on disk.
 *
 * ## Why the key is not stored in the clear
 *
 * It is a 2048-bit RSA private key with a 30-year certificate, and it is not
 * scoped to this app: any watch that accepted it grants `shell` to whoever
 * holds it, indefinitely. It cannot be regenerated without every paired watch
 * rejecting the phone, so it has to persist — but nothing requires it to
 * persist as plaintext. It is therefore sealed with an AES-256-GCM key held by
 * the Android Keystore, which never leaves the device.
 *
 * The certificate is a public certificate and is stored in the clear beside it;
 * only the private key is sealed.
 *
 * ## File format
 *
 * ```
 * byte 0        VERSION (1)
 * byte 1..12    GCM IV
 * byte 13..     ciphertext
 * ```
 *
 * A file that does not start with [VERSION] is a pre-migration PKCS#8 key and
 * is migrated on first read (see [load]). Anything else — an empty file, a
 * truncated one, a sealed one whose Keystore key has gone — is a hard failure,
 * never a new identity.
 */
internal object AdbKeyStore {

    const val FILE_NAME = "adbkey.pk8"
    const val CERT_FILE_NAME = "adbkey.crt"

    /** Marks a sealed file. A PKCS#8 DER blob always starts with 0x30, so this cannot collide. */
    const val VERSION: Byte = 0x42

    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    /**
     * The key as stored, or null when no identity has been made yet.
     *
     * A legacy plaintext file is migrated here: it is read, sealed, and only
     * written back after the sealed bytes have been read back and compared, so
     * a crash or a failed verification leaves the original key in place and the
     * next attempt simply retries.
     *
     * @throws AdbIdentityUnavailableException when a key exists but cannot be
     *   read — the identity must not be replaced.
     */
    fun load(dir: File, protector: KeyProtector): ByteArray? {
        val file = File(dir, FILE_NAME)
        if (!file.exists()) return null

        val raw = file.readBytes()
        if (raw.isEmpty()) {
            throw AdbIdentityUnavailableException(
                "The saved ADB key is empty, so it cannot be used. Pair the watch again to make a new one.",
            )
        }

        if (raw[0] != VERSION) {
            // Legacy plaintext PKCS#8. Refuse to seal something that is not a
            // usable key: replacing a file we cannot parse would destroy an
            // identity that might still be recoverable by other means.
            if (runCatching { KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(raw)) }.isFailure) {
                throw AdbIdentityUnavailableException(
                    "The saved ADB key is not a readable RSA key. " +
                        "Pair the watch again to make a new one.",
                )
            }
            // Seal it in place, preserving the identity.
            return migrate(file, raw, protector)
        }

        if (raw.size <= 1 + IV_BYTES) {
            throw AdbIdentityUnavailableException(
                "The saved ADB key is incomplete. Pair the watch again to make a new one.",
            )
        }

        return try {
            protector.decrypt(raw.copyOfRange(1, raw.size))
        } catch (e: Exception) {
            throw AdbIdentityUnavailableException(
                "The saved ADB key can no longer be read on this device. " +
                    "Pair the watch again to make a new one.",
                e,
            )
        }
    }

    /**
     * Writes [pkcs8] sealed. Used for a brand-new identity, where there is
     * nothing to preserve, so a plain write is safe.
     */
    fun store(dir: File, pkcs8: ByteArray, protector: KeyProtector) {
        writeAtomically(File(dir, FILE_NAME), seal(pkcs8, protector))
    }

    /**
     * Seals a legacy key without ever putting the original at risk: the sealed
     * bytes are written to a sibling, read back, compared with the original,
     * and only then moved into place.
     */
    private fun migrate(file: File, legacy: ByteArray, protector: KeyProtector): ByteArray {
        val sealed = seal(legacy, protector)
        val temp = File(file.parentFile, "$FILE_NAME.new")
        try {
            temp.writeBytes(sealed)
            // Prove the bytes on disk decrypt back to the same key before the
            // original is replaced. A crash between the two leaves the legacy
            // file untouched and the next launch tries again.
            val written = temp.readBytes()
            if (written.size <= 1 + IV_BYTES) {
                throw AdbIdentityUnavailableException("Could not verify the sealed ADB key; the existing one was kept.")
            }
            val recovered = runCatching { protector.decrypt(written.copyOfRange(1, written.size)) }
                .getOrElse {
                    throw AdbIdentityUnavailableException(
                        "Could not verify the sealed ADB key; the existing one was kept.",
                        it,
                    )
                }
            if (!recovered.contentEquals(legacy)) {
                throw AdbIdentityUnavailableException("Could not verify the sealed ADB key; the existing one was kept.")
            }
            if (!temp.renameTo(file)) {
                throw AdbIdentityUnavailableException("Could not save the sealed ADB key; the existing one was kept.")
            }
        } finally {
            temp.delete()
        }
        return legacy
    }

    private fun seal(pkcs8: ByteArray, protector: KeyProtector): ByteArray {
        val payload = protector.encrypt(pkcs8)
        return ByteArray(1 + payload.size).also {
            it[0] = VERSION
            payload.copyInto(it, destinationOffset = 1)
        }
    }

    /** Write to a sibling and rename, so a half-written file is never the one in use. */
    private fun writeAtomically(target: File, bytes: ByteArray) {
        val temp = File(target.parentFile, "${target.name}.new")
        try {
            temp.writeBytes(bytes)
            if (!temp.renameTo(target)) {
                throw AdbIdentityUnavailableException("Could not save the ADB key.")
            }
        } finally {
            temp.delete()
        }
    }
}

/**
 * The real [KeyProtector]: AES-256-GCM with a key generated in, and never
 * exported from, the Android Keystore.
 *
 * A payload is `IV || ciphertext`, which is what [AdbKeyStore] stores after its
 * version byte. The IV is fresh per encryption, as GCM requires.
 */
internal class KeystoreKeyProtector(private val alias: String) : KeyProtector {

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (store.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    override fun encrypt(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val ciphertext = cipher.doFinal(plaintext)
        return cipher.iv + ciphertext
    }

    override fun decrypt(payload: ByteArray): ByteArray {
        require(payload.size > IV_BYTES) { "payload too short" }
        val iv = payload.copyOfRange(0, IV_BYTES)
        val ciphertext = payload.copyOfRange(IV_BYTES, payload.size)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
        }
        return cipher.doFinal(ciphertext)
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
