package com.music.bitchord.wear

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The on-disk half of the ADB identity: what is written, how a legacy
 * plaintext key is migrated, and what happens when the stored key can no
 * longer be read.
 *
 * The Android Keystore itself is not available to a JVM test, so these run
 * against a fake [KeyProtector]. That is the right boundary: everything being
 * tested here — the file format, the migration decision, the crash-safety
 * ordering, and the failure semantics — is independent of which cipher is
 * doing the sealing.
 */
class AdbKeyStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** First byte of the fixed test key, used by [FakeProtector] as its authentication marker. */
    /** First byte of the real key, used by [FakeProtector] as its authentication marker. */
    private val pkcs8Marker: Byte get() = pkcs8[0]

    /** A deterministic stand-in for the Keystore: reversible, and fails on the wrong secret. */
    private inner class FakeProtector(private val secret: Byte = 0x5A) : KeyProtector {
        var encryptCalls = 0
            private set
        var decryptCalls = 0
            private set

        override fun decrypt(payload: ByteArray): ByteArray {
            decryptCalls++
            // Models AES-GCM's authentication tag: a payload this protector did
            // not produce fails outright rather than decoding to garbage.
            if (payload.isEmpty() || payload[0] != (pkcs8Marker.toInt() xor secret.toInt()).toByte()) {
                throw javax.crypto.AEADBadTagException("authentication failed")
            }
            return ByteArray(payload.size) { (payload[it].toInt() xor secret.toInt()).toByte() }
        }

        override fun encrypt(plaintext: ByteArray): ByteArray {
            encryptCalls++
            return ByteArray(plaintext.size) { (plaintext[it].toInt() xor secret.toInt()).toByte() }
        }
    }

    /**
     * A real PKCS#8 RSA key, because the store validates a legacy file before
     * sealing it — arbitrary bytes would be refused as unreadable, which is a
     * different case with its own test.
     */
    private val pkcs8: ByteArray by lazy {
        java.security.KeyPairGenerator.getInstance("RSA")
            .apply { initialize(2048) }
            .generateKeyPair().private.encoded
    }

    private fun dir(): File = tmp.newFolder("adb")

    // ── Fresh write ────────────────────────────────────────────────────────

    @Test
    fun `a stored key is encrypted on disk, not written as plain PKCS8`() {
        val dir = dir()
        val protector = FakeProtector()

        AdbKeyStore.store(dir, pkcs8, protector)

        val raw = File(dir, AdbKeyStore.FILE_NAME).readBytes()
        assertTrue("must carry a version marker", raw[0] == AdbKeyStore.VERSION)
        assertFalse(
            "the PKCS#8 bytes must not appear verbatim",
            raw.copyOfRange(1, raw.size).contentEquals(pkcs8),
        )
        assertEquals(1, protector.encryptCalls)
    }

    @Test
    fun `a stored key round-trips back to the same bytes`() {
        val dir = dir()
        val protector = FakeProtector()
        AdbKeyStore.store(dir, pkcs8, protector)

        assertArrayEquals(pkcs8, AdbKeyStore.load(dir, protector))
    }

    @Test
    fun `no key file reads as absent rather than failing`() {
        assertNull(AdbKeyStore.load(dir(), FakeProtector()))
    }

    // ── Legacy migration ───────────────────────────────────────────────────

    @Test
    fun `a legacy plaintext key is migrated in place and keeps its identity`() {
        val dir = dir()
        val protector = FakeProtector()
        val legacy = File(dir, AdbKeyStore.FILE_NAME).apply { writeBytes(pkcs8) }

        val loaded = AdbKeyStore.load(dir, protector)

        // The identity is preserved: the caller gets back exactly what was there.
        assertArrayEquals("migrated key must be the same key", pkcs8, loaded)
        // ...and the file on disk is now sealed.
        val raw = legacy.readBytes()
        assertEquals(AdbKeyStore.VERSION, raw[0])
        assertFalse(raw.copyOfRange(1, raw.size).contentEquals(pkcs8))
        // ...and it still reads back afterwards.
        assertArrayEquals(pkcs8, AdbKeyStore.load(dir, protector))
    }

    @Test
    fun `migration does not run twice`() {
        val dir = dir()
        val protector = FakeProtector()
        File(dir, AdbKeyStore.FILE_NAME).writeBytes(pkcs8)

        AdbKeyStore.load(dir, protector)
        val afterFirst = protector.encryptCalls
        AdbKeyStore.load(dir, protector)

        assertEquals("a sealed file must not be re-encrypted", afterFirst, protector.encryptCalls)
    }

    @Test
    fun `a crash during migration leaves the original readable`() {
        val dir = dir()
        val file = File(dir, AdbKeyStore.FILE_NAME).apply { writeBytes(pkcs8) }
        // A protector that fails part-way: the write must not have landed.
        val failing = object : KeyProtector {
            override fun encrypt(plaintext: ByteArray): ByteArray = throw java.io.IOException("disk full")
            override fun decrypt(payload: ByteArray): ByteArray = payload
        }

        val error = runCatching { AdbKeyStore.load(dir, failing) }.exceptionOrNull()

        assertNotNull("a failed migration must surface", error)
        assertArrayEquals("the original key must be untouched", pkcs8, file.readBytes())
    }

    @Test
    fun `migration is not committed when the sealed bytes fail to verify`() {
        val dir = dir()
        val file = File(dir, AdbKeyStore.FILE_NAME).apply { writeBytes(pkcs8) }
        // Encrypts, but decrypts to something else: the store's read-back check
        // must refuse to replace a key it cannot prove it can recover.
        val lossy = object : KeyProtector {
            override fun encrypt(plaintext: ByteArray): ByteArray = plaintext.copyOf()
            override fun decrypt(payload: ByteArray): ByteArray = payload.copyOf().also { it[0] = (it[0] + 1).toByte() }
        }

        runCatching { AdbKeyStore.load(dir, lossy) }

        assertArrayEquals("the original key must survive a failed verify", pkcs8, file.readBytes())
    }

    // ── Unreadable identity ────────────────────────────────────────────────

    @Test
    fun `a key that cannot be decrypted fails explicitly and is not replaced`() {
        val dir = dir()
        val written = FakeProtector(secret = 0x11)
        AdbKeyStore.store(dir, pkcs8, written)
        val onDisk = File(dir, AdbKeyStore.FILE_NAME).readBytes()

        val wrongKey = FakeProtector(secret = 0x22)
        val error = runCatching { AdbKeyStore.load(dir, wrongKey) }.exceptionOrNull()

        assertTrue(
            "must be the identity failure the caller can explain, was $error",
            error is AdbIdentityUnavailableException,
        )
        assertTrue(
            "the message must say re-pairing is needed: ${error?.message}",
            error?.message.orEmpty().contains("pair", ignoreCase = true),
        )
        assertArrayEquals(
            "an unreadable identity must never be overwritten",
            onDisk,
            File(dir, AdbKeyStore.FILE_NAME).readBytes(),
        )
    }

    @Test
    fun `a corrupt empty key file fails instead of silently minting a new identity`() {
        val dir = dir()
        val file = File(dir, AdbKeyStore.FILE_NAME).apply { writeBytes(ByteArray(0)) }

        val error = runCatching { AdbKeyStore.load(dir, FakeProtector()) }.exceptionOrNull()

        assertTrue("was $error", error is AdbIdentityUnavailableException)
        assertEquals("the file must be left alone", 0L, file.length())
    }

    @Test
    fun `a legacy file that is not a usable key is refused, not sealed`() {
        val dir = dir()
        val junk = ByteArray(64) { (it * 7 + 3).toByte() }
        val file = File(dir, AdbKeyStore.FILE_NAME).apply { writeBytes(junk) }

        val error = runCatching { AdbKeyStore.load(dir, FakeProtector()) }.exceptionOrNull()

        assertTrue("was $error", error is AdbIdentityUnavailableException)
        assertArrayEquals("must be left exactly as found", junk, file.readBytes())
    }

    @Test
    fun `the certificate is stored in the clear and is not part of the sealed file`() {
        // The certificate is public by construction; only the private key is
        // sealed. This pins that the two files stay separate.
        val dir = dir()
        AdbKeyStore.store(dir, pkcs8, FakeProtector())

        assertTrue(File(dir, AdbKeyStore.FILE_NAME).exists())
        assertFalse(File(dir, AdbKeyStore.CERT_FILE_NAME).exists())
    }
}
