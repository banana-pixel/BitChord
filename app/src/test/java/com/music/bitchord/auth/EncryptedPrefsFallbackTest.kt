package com.music.bitchord.auth

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fallback rules for encrypted preferences.
 *
 * Two behaviours matter and they are opposite: a stale keyset is *recoverable*
 * (the ciphertext was unreadable anyway, so recreate and carry on), while any
 * other failure must not quietly downgrade credential storage to plaintext
 * without saying so. And an `Error` — an OOM during Tink setup, say — must not
 * be swallowed at all.
 *
 * [EncryptedPrefs.decide] is the decision `resolve` makes, with store creation
 * injected so each failure mode can be reproduced without a Keystore.
 */
class EncryptedPrefsFallbackTest {

    @After
    fun tearDown() {
        EncryptedPrefs.resetDegradedForTest()
    }

    private fun decide(
        attempt: () -> String,
        stale: Boolean = false,
        recreated: () -> Unit = {},
    ) = EncryptedPrefs.decide(
        name = "bitchord_auth",
        attempt = attempt,
        staleKeyset = { stale },
        recreate = recreated,
    )

    // ── Recovery that must be preserved ────────────────────────────────────

    @Test
    fun `a working keystore uses the encrypted store`() {
        val decision = decide({ "store" })

        assertTrue(decision is EncryptedPrefs.Decision.UseEncrypted)
        assertEquals("store", (decision as EncryptedPrefs.Decision.UseEncrypted).store)
        assertFalse(EncryptedPrefs.degraded.value)
    }

    @Test
    fun `a stale keyset is recreated and retried, not downgraded`() {
        var calls = 0
        var recreated = false

        val decision = decide(
            attempt = {
                calls++
                if (calls == 1) throw IllegalStateException("stale") else "store"
            },
            stale = true,
            recreated = { recreated = true },
        )

        assertTrue("must end up encrypted", decision is EncryptedPrefs.Decision.UseEncrypted)
        assertTrue("must recreate the keyset first", recreated)
        assertEquals("must retry once", 2, calls)
        assertFalse("a recovered store is not degraded", EncryptedPrefs.degraded.value)
    }

    @Test
    fun `a stale keyset that fails again falls back and says so`() {
        val decision = decide(attempt = { throw IllegalStateException("still broken") }, stale = true)

        assertTrue(decision is EncryptedPrefs.Decision.UsePlain)
        assertTrue("the fallback must be observable", EncryptedPrefs.degraded.value)
    }

    // ── The fallback must be visible ───────────────────────────────────────

    @Test
    fun `an unusable keystore falls back to plaintext and marks storage degraded`() {
        val decision = decide({ throw IllegalStateException("no keystore") })

        assertTrue(decision is EncryptedPrefs.Decision.UsePlain)
        assertTrue(EncryptedPrefs.degraded.value)
    }

    @Test
    fun `the degraded state clears once a later open succeeds`() {
        decide({ throw IllegalStateException("no keystore") })
        assertTrue(EncryptedPrefs.degraded.value)

        decide({ "store" })
        assertFalse("a healed store must clear the warning", EncryptedPrefs.degraded.value)
    }

    @Test
    fun `one healthy store does not hide another store's fallback`() {
        // auth and sources open independently. If the sources store opened
        // cleanly after auth had degraded, a single shared flag would clear the
        // warning while credentials were still being written in the clear.
        EncryptedPrefs.decide(
            name = "bitchord_auth",
            attempt = { throw IllegalStateException("no keystore") },
            staleKeyset = { false },
            recreate = {},
        )
        assertTrue(EncryptedPrefs.degraded.value)

        EncryptedPrefs.decide(
            name = "bitchord_sources",
            attempt = { "store" },
            staleKeyset = { false },
            recreate = {},
        )

        assertTrue("auth is still degraded, so the warning must stay", EncryptedPrefs.degraded.value)
    }

    @Test
    fun `the warning clears once every degraded store has recovered`() {
        for (name in listOf("bitchord_auth", "bitchord_sources")) {
            EncryptedPrefs.decide(name, { throw IllegalStateException("no keystore") }, { false }, {})
        }
        assertTrue(EncryptedPrefs.degraded.value)

        EncryptedPrefs.decide("bitchord_auth", { "store" }, { false }, {})
        assertTrue("sources is still degraded", EncryptedPrefs.degraded.value)

        EncryptedPrefs.decide("bitchord_sources", { "store" }, { false }, {})
        assertFalse("all recovered", EncryptedPrefs.degraded.value)
    }

    @Test
    fun `degraded is a flow the settings screen can observe`() {
        assertNotNull(EncryptedPrefs.degraded)
        assertFalse(EncryptedPrefs.degraded.value)
    }

    // ── Errors must not be swallowed ───────────────────────────────────────

    @Test
    fun `an Error propagates instead of silently downgrading storage`() {
        val thrown = OutOfMemoryError("tink could not allocate")

        val error = runCatching { decide({ throw thrown }) }.exceptionOrNull()

        assertTrue("an Error must not become a fallback, was $error", error is OutOfMemoryError)
        assertFalse("a failed open must not be reported as degraded", EncryptedPrefs.degraded.value)
    }

    @Test
    fun `a LinkageError also propagates`() {
        val error = runCatching { decide({ throw NoClassDefFoundError("tink") }) }.exceptionOrNull()

        assertTrue("was $error", error is NoClassDefFoundError)
    }

    @Test
    fun `an Error during the recreate retry also propagates`() {
        var calls = 0
        val error = runCatching {
            decide(
                attempt = {
                    calls++
                    if (calls == 1) throw IllegalStateException("stale") else throw StackOverflowError("boom")
                },
                stale = true,
            )
        }.exceptionOrNull()

        assertTrue("was $error", error is StackOverflowError)
    }

    // ── What the fallback must not do ──────────────────────────────────────

    @Test
    fun `a plain fallback never claims to have an encrypted store`() {
        val decision = decide({ throw IllegalStateException("no keystore") })

        assertTrue(decision is EncryptedPrefs.Decision.UsePlain)
        assertFalse(decision is EncryptedPrefs.Decision.UseEncrypted)
    }
}
