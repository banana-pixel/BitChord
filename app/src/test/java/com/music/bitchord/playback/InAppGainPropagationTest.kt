package com.music.bitchord.playback

import com.music.bitchord.playback.audio.AudioBlock
import com.music.bitchord.playback.audio.DspChain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How the listener's level reaches the two players.
 *
 * A crossfade runs two sinks at once and the idle one is the next transition's
 * *incoming* player, so both have to carry the level — and the service replaces
 * both players when the output route changes, which is the case that would
 * silently reset the level to 100 % if the processors were built per player.
 */
class InAppGainPropagationTest {

    private val sampleRate = 44100
    private val channels = 2

    /**
     * The gain the processor applies once its glide has settled, measured on a
     * fresh unity block. Each pass refills the buffer: processing the same
     * samples twice would multiply the gain in twice and measure its square.
     */
    private fun settledGain(processor: VolumeProcessor): Float {
        val audio = AudioBlock(channelCount = channels, capacityFrames = 512)
        processor.configure(sampleRate, channels)
        fun pass() {
            audio.samples.fill(1f)
            audio.reset(512)
            processor.process(audio)
        }
        repeat(40) { pass() }
        pass()
        return audio.samples[0]
    }

    @Test
    fun `both players receive the same level`() {
        val active = VolumeProcessor()
        val spare = VolumeProcessor()

        applyInAppGain(0.4f, active, spare)

        assertEquals(0.4f, active.target)
        assertEquals(0.4f, spare.target)
        assertEquals(settledGain(active), settledGain(spare), 0f)
    }

    @Test
    fun `a level change while a blend is running applies to both sides`() {
        // Mid-crossfade both sinks are audible, so a level applied to only the
        // active one would tilt the blend.
        val active = VolumeProcessor()
        val spare = VolumeProcessor()

        applyInAppGain(1f, active, spare)
        applyInAppGain(0.25f, active, spare)

        assertEquals(settledGain(active), settledGain(spare), 0f)
        assertEquals(0.25f, settledGain(spare), 1e-6f)
    }

    @Test
    fun `replaced players inherit the level already in force`() {
        // What rebuildPlayersForOutput does: the service keeps the processors,
        // the players around them are new. A fresh sink must pick up the level
        // from the processor rather than from a default of unity.
        val keptActive = VolumeProcessor()
        val keptSpare = VolumeProcessor()
        applyInAppGain(0.3f, keptActive, keptSpare)

        val rebuiltChain = DspChain(volume = keptActive)
        rebuiltChain.configure(sampleRate, channels)
        rebuiltChain.transition.open()

        val audio = AudioBlock(channelCount = channels, capacityFrames = 512)
        fun pass() {
            audio.samples.fill(1f)
            audio.reset(512)
            rebuiltChain.process(audio)
        }
        repeat(40) { pass() }
        pass()

        assertEquals(0.3f, audio.samples[0], 1e-6f)
    }

    @Test
    fun `muting reaches both players and unmuting restores both`() {
        val active = VolumeProcessor()
        val spare = VolumeProcessor()

        applyInAppGain(0.6f, active, spare)
        applyInAppGain(0f, active, spare)
        assertEquals(0f, settledGain(active), 0f)
        assertEquals(0f, settledGain(spare), 0f)

        applyInAppGain(0.6f, active, spare)
        assertEquals(0.6f, settledGain(active), 1e-6f)
        assertEquals(0.6f, settledGain(spare), 1e-6f)
    }

    @Test
    fun `a new chain starts at unity until the level is applied`() {
        // Documents the ordering the service relies on: the level is pushed
        // before the players are built, so this window never opens in practice.
        val chain = DspChain()
        chain.configure(sampleRate, channels)
        chain.transition.open()

        val audio = AudioBlock(channelCount = channels, capacityFrames = 64)
        audio.samples.fill(1f)
        audio.reset(64)
        chain.process(audio)

        assertEquals(1f, audio.samples[0], 0f)
    }

    @Test
    fun `changing the level does not disturb the stream state`() {
        // The stage has no effect on the rest of the chain's state: a level
        // change between blocks leaves the equaliser's filter memory and the
        // block itself untouched apart from the scalar.
        val chain = DspChain()
        chain.configure(sampleRate, channels)
        chain.transition.open()

        fun block(): AudioBlock {
            val audio = AudioBlock(channelCount = channels, capacityFrames = 128)
            for (i in 0 until 128) {
                audio.samples[i * 2] = 0.5f
                audio.samples[i * 2 + 1] = -0.5f
            }
            audio.reset(128)
            return audio
        }

        chain.process(block())
        chain.volume.setGain(0.5f)
        val audio = block()
        chain.process(audio)

        // No flush happened, so the ramp is mid-flight rather than snapped; what
        // matters is that it moved toward the target and stayed proportional
        // across channels.
        assertTrue("gain should have started moving", audio.samples[0] < 0.5f)
        assertEquals(-audio.samples[0], audio.samples[1], 0f)
        assertNotEquals(0f, audio.samples[0])
    }
}
