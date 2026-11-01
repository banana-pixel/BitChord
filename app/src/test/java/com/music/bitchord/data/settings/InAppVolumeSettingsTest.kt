package com.music.bitchord.data.settings

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Persistence and mute semantics for the in-app level.
 *
 * The rules worth pinning are the ones a naive implementation gets wrong: that
 * muting does not overwrite the listener's level (so unmuting restores it), that
 * the range is enforced on the way in as well as on the way out, and that a
 * non-finite value is refused rather than written.
 */
class InAppVolumeSettingsTest {

    private val prefs = FakePrefs()

    // ── Defaults and persistence ───────────────────────────────────────────

    @Test
    fun `a fresh install is at unity and unmuted`() {
        assertEquals(1f, readInAppVolume(prefs))
        assertFalse(readInAppVolumeMuted(prefs))
        assertEquals(1f, effectiveInAppGain(readInAppVolume(prefs), readInAppVolumeMuted(prefs)))
    }

    @Test
    fun `the level survives a round trip through the preference file`() {
        assertEquals(0.42f, writeInAppVolume(prefs, 0.42f))
        assertEquals(0.42f, readInAppVolume(prefs))
    }

    @Test
    fun `the mute flag survives a round trip`() {
        writeInAppVolumeMuted(prefs, true)
        assertTrue(readInAppVolumeMuted(prefs))
        writeInAppVolumeMuted(prefs, false)
        assertFalse(readInAppVolumeMuted(prefs))
    }

    @Test
    fun `the stored keys are the ones the app reads`() {
        writeInAppVolume(prefs, 0.7f)
        writeInAppVolumeMuted(prefs, true)
        assertEquals(0.7f, prefs.getFloat("in_app_volume", 1f))
        assertTrue(prefs.getBoolean("in_app_volume_muted", false))
    }

    // ── Range enforcement ──────────────────────────────────────────────────

    @Test
    fun `writes are clamped to the supported range`() {
        assertEquals(1f, writeInAppVolume(prefs, 1.5f))
        assertEquals(1f, readInAppVolume(prefs))

        assertEquals(0f, writeInAppVolume(prefs, -0.2f))
        assertEquals(0f, readInAppVolume(prefs))
    }

    @Test
    fun `a corrupt stored value is clamped on read`() {
        prefs.edit().putFloat("in_app_volume", 3f).apply()
        assertEquals(1f, readInAppVolume(prefs))

        prefs.edit().putFloat("in_app_volume", -1f).apply()
        assertEquals(0f, readInAppVolume(prefs))
    }

    @Test
    fun `a corrupt non-finite stored value falls back to unity`() {
        // Only a hand-edited or corrupted file can hold this: the writer refuses
        // NaN. But the read path claims to clamp, and coerceIn does not clamp
        // NaN — it passes it straight through, which would put a NaN in the
        // slider. Prove the guard rather than assume it.
        prefs.edit().putFloat("in_app_volume", Float.NaN).apply()
        assertEquals(1f, readInAppVolume(prefs))

        prefs.edit().putFloat("in_app_volume", Float.POSITIVE_INFINITY).apply()
        assertEquals(1f, readInAppVolume(prefs))
    }

    @Test
    fun `a non-finite value is refused instead of written`() {
        writeInAppVolume(prefs, 0.5f)

        assertNull(writeInAppVolume(prefs, Float.NaN))
        assertNull(writeInAppVolume(prefs, Float.POSITIVE_INFINITY))
        assertNull(writeInAppVolume(prefs, Float.NEGATIVE_INFINITY))

        // The previous level is untouched: a rejected write must not silence
        // playback or reset the listener's choice.
        assertEquals(0.5f, readInAppVolume(prefs))
    }

    // ── Mute and restore ───────────────────────────────────────────────────

    @Test
    fun `muting silences the chain without losing the level`() {
        writeInAppVolume(prefs, 0.35f)
        writeInAppVolumeMuted(prefs, true)

        assertEquals("the stored level must survive a mute", 0.35f, readInAppVolume(prefs))
        assertEquals(0f, effectiveInAppGain(readInAppVolume(prefs), readInAppVolumeMuted(prefs)))
    }

    @Test
    fun `unmuting restores the level the listener chose`() {
        writeInAppVolume(prefs, 0.35f)
        writeInAppVolumeMuted(prefs, true)
        writeInAppVolumeMuted(prefs, false)

        assertEquals(0.35f, effectiveInAppGain(readInAppVolume(prefs), readInAppVolumeMuted(prefs)))
    }

    @Test
    fun `changing the level while muted keeps the chain silent`() {
        writeInAppVolumeMuted(prefs, true)
        writeInAppVolume(prefs, 0.8f)

        assertEquals(0.8f, readInAppVolume(prefs))
        assertEquals(0f, effectiveInAppGain(readInAppVolume(prefs), readInAppVolumeMuted(prefs)))

        writeInAppVolumeMuted(prefs, false)
        assertEquals(0.8f, effectiveInAppGain(readInAppVolume(prefs), readInAppVolumeMuted(prefs)))
    }

    @Test
    fun `a level of zero and a mute are the same gain but different states`() {
        writeInAppVolume(prefs, 0f)
        assertEquals(0f, effectiveInAppGain(readInAppVolume(prefs), muted = false))

        writeInAppVolume(prefs, 0.6f)
        writeInAppVolumeMuted(prefs, true)
        assertEquals(0f, effectiveInAppGain(readInAppVolume(prefs), muted = true))

        // The difference matters: unmuting after a zero level stays silent,
        // unmuting after a mute returns to 0.6.
        writeInAppVolumeMuted(prefs, false)
        assertEquals(0.6f, effectiveInAppGain(readInAppVolume(prefs), muted = false))
    }

    @Test
    fun `the effective gain is clamped even for an out-of-range level`() {
        assertEquals(1f, effectiveInAppGain(2f, muted = false))
        assertEquals(0f, effectiveInAppGain(-2f, muted = false))
    }

    // ── Fake ───────────────────────────────────────────────────────────────

    private class FakePrefs : SharedPreferences {
        private val data = mutableMapOf<String, Any?>()

        override fun getAll(): Map<String, *> = HashMap(data)
        override fun getString(key: String?, defValue: String?): String? = data[key] as? String ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: Set<String>?): Set<String>? =
            data[key] as? Set<String> ?: defValues
        override fun getInt(key: String?, defValue: Int): Int = (data[key] as? Number)?.toInt() ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = (data[key] as? Number)?.toLong() ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = (data[key] as? Number)?.toFloat() ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = data[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = data.containsKey(key)
        override fun edit(): SharedPreferences.Editor = Editor(data)
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        private class Editor(private val data: MutableMap<String, Any?>) : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()
            private val removals = mutableSetOf<String>()

            override fun putString(key: String, value: String?) = apply { pending[key] = value }
            override fun putStringSet(key: String, values: Set<String>?) = apply { pending[key] = values }
            override fun putInt(key: String, value: Int) = apply { pending[key] = value }
            override fun putLong(key: String, value: Long) = apply { pending[key] = value }
            override fun putFloat(key: String, value: Float) = apply { pending[key] = value }
            override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }
            override fun remove(key: String) = apply { removals += key }
            override fun clear() = apply { removals += data.keys }
            override fun commit(): Boolean { apply(); return true }
            override fun apply() {
                removals.forEach { data.remove(it) }
                data.putAll(pending)
                pending.clear()
                removals.clear()
            }
        }
    }
}
