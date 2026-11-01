package com.music.bitchord.playback

import com.music.bitchord.playback.audio.AudioBlock
import com.music.bitchord.playback.audio.DspChain
import com.music.bitchord.playback.audio.PcmBoundary
import com.music.bitchord.playback.audio.PcmEncoding
import com.music.bitchord.playback.EqCurve
import com.music.bitchord.playback.EqLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * The listener's level stage: a plain output trim that must be invisible at
 * 100 %, exact when attenuating, silent at zero, and click-free while moving.
 *
 * The bit-exactness cases matter more than the gain ones. [DspChain] documents
 * that an idle chain leaves a block byte-identical, which is what lets the
 * 16- and 24-bit integer paths round-trip through float unchanged; a new stage
 * that multiplied by 1.0 would quietly break that, so "unity is a bypass" is
 * tested as a property of the samples rather than assumed.
 */
class VolumeProcessorTest {

    private val sampleRate = 44100
    private val channels = 2

    private fun block(frames: Int, fill: (Int) -> Float): AudioBlock {
        val block = AudioBlock(channelCount = channels, capacityFrames = frames)
        for (frame in 0 until frames) {
            for (channel in 0 until channels) {
                block.samples[frame * channels + channel] = fill(frame * channels + channel)
            }
        }
        block.reset(frames)
        return block
    }

    private fun sine(frames: Int, amplitude: Float = 0.9f, hz: Double = 440.0): AudioBlock {
        var index = 0
        return block(frames) {
            val sample = amplitude * sin(2.0 * PI * hz * (index / channels) / sampleRate).toFloat()
            index++
            sample
        }
    }

    private fun assertSamplesEqual(expected: FloatArray, actual: FloatArray, message: String = "") {
        for (i in expected.indices) {
            assertEquals("$message sample $i", expected[i], actual[i], 0f)
        }
    }

    // ── Unity is a true bypass ─────────────────────────────────────────────

    @Test
    fun `unity gain leaves every sample byte identical`() {
        val processor = VolumeProcessor()
        processor.configure(sampleRate, channels)

        val audio = sine(1024)
        val expected = audio.samples.copyOf(audio.sampleCount)

        processor.process(audio)

        assertSamplesEqual(expected, audio.samples)
        assertEquals(1f, processor.target)
    }

    @Test
    fun `unity gain after attenuating returns to an exact bypass`() {
        val processor = VolumeProcessor()
        processor.configure(sampleRate, channels)

        // Attenuate, then come back to 100 % and let the glide settle.
        processor.setGain(0.4f)
        repeat(60) { processor.process(sine(256)) }
        processor.setGain(1f)
        repeat(80) { processor.process(sine(256)) }

        val audio = sine(512)
        val expected = audio.samples.copyOf(audio.sampleCount)
        processor.process(audio)

        // Exact equality, not a tolerance: once parked the stage does not run.
        assertSamplesEqual(expected, audio.samples, "after returning to unity")
    }

    @Test
    fun `idle chain is still bit exact through the 16-bit round trip`() {
        val chain = DspChain()
        chain.configure(sampleRate, channels)
        chain.transition.open()

        val frames = 256
        val pcm = ShortArray(frames * channels) { i -> ((i * 37) % 20000 - 10000).toShort() }
        val block = AudioBlock(channelCount = channels, capacityFrames = frames)
        PcmBoundary.decode(
            inputBuffer = java.nio.ByteBuffer.allocate(pcm.size * 2)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                .apply { pcm.forEach { putShort(it) } }
                .apply { flip() },
            encoding = PcmEncoding.PCM_16BIT,
            destinationBlock = block,
            maxFrames = frames,
        )
        chain.process(block)

        val out = java.nio.ByteBuffer.allocate(pcm.size * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        PcmBoundary.encode(block, PcmEncoding.PCM_16BIT, out)
        out.flip()
        for (i in pcm.indices) {
            assertEquals("pcm sample $i", pcm[i], out.short)
        }
    }

    // ── Attenuation ────────────────────────────────────────────────────────

    @Test
    fun `half gain halves the amplitude once the glide settles`() {
        val processor = VolumeProcessor()
        processor.configure(sampleRate, channels)
        processor.process(sine(64))
        processor.setGain(0.5f)

        // Well past the 40 ms glide at 44.1 kHz: the settle snap lands the gain
        // exactly on 0.5 after roughly 15k frames.
        repeat(40) { processor.process(sine(512)) }

        val audio = sine(512)
        val source = audio.samples.copyOf(audio.sampleCount)
        processor.process(audio)

        for (i in source.indices) {
            assertEquals("sample $i", source[i] * 0.5f, audio.samples[i], 1e-6f)
        }
    }

    @Test
    fun `gain applies to every channel equally`() {
        val processor = VolumeProcessor()
        processor.configure(sampleRate, channels)
        processor.process(sine(64))
        processor.setGain(0.25f)
        repeat(40) { processor.process(sine(512)) }

        val audio = block(64) { 0.8f }
        processor.process(audio)

        for (frame in 0 until 64) {
            assertEquals(0.2f, audio.samples[frame * 2], 1e-6f)
            assertEquals(0.2f, audio.samples[frame * 2 + 1], 1e-6f)
        }
    }

    @Test
    fun `mono blocks are scaled too`() {
        val processor = VolumeProcessor()
        processor.configure(sampleRate, 1)
        processor.process(AudioBlock(channelCount = 1, capacityFrames = 64).also { it.reset(64) })
        processor.setGain(0.5f)
        repeat(40) { processor.process(AudioBlock(channelCount = 1, capacityFrames = 512).also { it.reset(512) }) }

        val audio = AudioBlock(channelCount = 1, capacityFrames = 64)
        audio.samples.fill(0.5f)
        audio.reset(64)
        processor.process(audio)

        for (i in 0 until 64) assertEquals(0.25f, audio.samples[i], 1e-6f)
    }

    // ── Silence and mute ───────────────────────────────────────────────────

    @Test
    fun `zero gain produces silence`() {
        val processor = VolumeProcessor()
        processor.configure(sampleRate, channels)
        processor.process(sine(64))
        processor.setGain(0f)
        repeat(40) { processor.process(sine(512)) }

        val audio = sine(512)
        processor.process(audio)

        for (i in 0 until audio.sampleCount) {
            assertEquals("sample $i", 0f, audio.samples[i], 0f)
        }
    }

    @Test
    fun `mute is not sticky - the level can be restored`() {
        val processor = VolumeProcessor()
        processor.configure(sampleRate, channels)

        processor.process(sine(64))
        processor.setGain(0f)
        repeat(40) { processor.process(sine(512)) }
        processor.setGain(1f)
        repeat(80) { processor.process(sine(512)) }

        val audio = sine(512)
        val expected = audio.samples.copyOf(audio.sampleCount)
        processor.process(audio)
        assertSamplesEqual(expected, audio.samples, "after unmute")
    }

    // ── Ramping ────────────────────────────────────────────────────────────

    @Test
    fun `a step change is ramped rather than applied in one sample`() {
        val processor = VolumeProcessor()
        processor.configure(sampleRate, channels)
        // A block first: the level requested at configure time is applied
        // outright (no fade-in at track start), so the ramp only describes a
        // change made while playing.
        processor.process(block(64) { 1f })

        processor.setGain(0.5f)
        val audio = block(1024) { 1f }
        processor.process(audio)

        // The first frame has not jumped to the target, and the last has not
        // stayed at unity: the gain moved through the block.
        val first = audio.samples[0]
        val last = audio.samples[(1024 - 1) * channels]
        assertTrue("first frame should still be near unity, was $first", first > 0.9f)
        assertTrue("last frame should be below the first, was $last", last < first)
        assertTrue("last frame should not undershoot the target, was $last", last >= 0.5f)
    }

    @Test
    fun `the ramp is monotonic and never overshoots`() {
        val processor = VolumeProcessor()
        processor.configure(sampleRate, channels)
        processor.process(block(64) { 1f })

        processor.setGain(0.3f)
        val audio = block(2048) { 1f }
        processor.process(audio)

        var previous = Float.MAX_VALUE
        for (frame in 0 until 2048) {
            val gain = audio.samples[frame * channels]
            assertTrue("frame $frame rose: $gain > $previous", gain <= previous)
            assertTrue("frame $frame undershot the target: $gain", gain >= 0.3f)
            previous = gain
        }
    }

    @Test
    fun `the ramp reaches the target within the glide window`() {
        val processor = VolumeProcessor()
        processor.configure(sampleRate, channels)
        processor.process(block(64) { 1f })
        processor.setGain(0.5f)

        // Roughly twelve time constants, which is past the point where the
        // settle snap parks the stage exactly on the target.
        val frames = (sampleRate * 0.5).toInt()
        val audio = block(frames) { 1f }
        processor.process(audio)

        assertEquals(0.5f, audio.samples[(frames - 1) * channels], 1e-4f)
        assertEquals(0.5f, processor.target)
    }

    @Test
    fun `a ramp from silence rises without overshoot`() {
        val processor = VolumeProcessor()
        processor.configure(sampleRate, channels)
        processor.process(sine(64))
        processor.setGain(0f)
        repeat(40) { processor.process(sine(512)) }

        processor.setGain(1f)
        val audio = block(1024) { 1f }
        processor.process(audio)

        var previous = -1f
        for (frame in 0 until 1024) {
            val gain = audio.samples[frame * channels]
            assertTrue("frame $frame fell during a rise: $gain < $previous", gain >= previous)
            assertTrue("frame $frame overshot unity: $gain", gain <= 1f)
            previous = gain
        }
    }

    // ── Stability and state ────────────────────────────────────────────────

    @Test
    fun `gain is continuous across a block boundary`() {
        val processor = VolumeProcessor()
        processor.configure(sampleRate, channels)
        processor.process(block(64) { 1f })
        processor.setGain(0.2f)

        val first = block(64) { 1f }
        processor.process(first)
        val lastOfFirst = first.samples[(64 - 1) * channels]

        val second = block(64) { 1f }
        processor.process(second)
        val firstOfSecond = second.samples[0]

        // No jump back to unity at the boundary: the second block continues the
        // ramp from where the first left off.
        assertTrue("second block restarted the ramp: $firstOfSecond", firstOfSecond <= lastOfFirst)
        assertTrue(
            "continuity broke: $firstOfSecond vs $lastOfFirst",
            abs(firstOfSecond - lastOfFirst) < 0.01f,
        )
    }

    @Test
    fun `a flush jumps straight to the target`() {
        val processor = VolumeProcessor()
        processor.configure(sampleRate, channels)
        processor.process(block(64) { 1f })
        processor.setGain(0.4f)
        processor.process(block(64) { 1f })

        // A seek or stream restart: the output is discontinuous anyway.
        processor.flush()
        val audio = block(64) { 1f }
        processor.process(audio)

        assertEquals(0.4f, audio.samples[0], 1e-6f)
    }

    @Test
    fun `repeated processing stays stable and bounded`() {
        val processor = VolumeProcessor()
        processor.configure(sampleRate, channels)
        processor.process(block(64) { 1f })
        processor.setGain(0.6f)

        val audio = sine(512)
        repeat(200) { processor.process(audio) }
        audio.reset(512)
        processor.process(audio)

        for (i in 0 until audio.sampleCount) {
            assertTrue("sample $i is not finite", audio.samples[i].isFinite())
            assertTrue("sample $i left the signal range", abs(audio.samples[i]) <= 1f)
        }
    }

    @Test
    fun `out of range and non-finite requests are handled safely`() {
        val processor = VolumeProcessor()

        processor.setGain(2f)
        assertEquals(1f, processor.target)

        processor.setGain(-1f)
        assertEquals(0f, processor.target)

        processor.setGain(Float.NaN)
        assertEquals("NaN must not poison the target", 0f, processor.target)

        processor.setGain(Float.POSITIVE_INFINITY)
        assertEquals(0f, processor.target)

        processor.configure(sampleRate, channels)
        val audio = sine(128)
        processor.process(audio)
        for (i in 0 until audio.sampleCount) {
            assertTrue("sample $i is not finite", audio.samples[i].isFinite())
        }
    }

    @Test
    fun `zero length and unconfigured blocks are ignored`() {
        val processor = VolumeProcessor()

        val empty = AudioBlock(channelCount = channels, capacityFrames = 16)
        empty.reset(0)
        processor.process(empty)

        // Not configured: no sample rate, so the stage must not divide by it.
        val audio = block(16) { 1f }
        val expected = audio.samples.copyOf(audio.sampleCount)
        processor.process(audio)
        assertSamplesEqual(expected, audio.samples, "unconfigured")
    }

    @Test
    fun `reset restores the stage to the requested level`() {
        val processor = VolumeProcessor()
        processor.configure(sampleRate, channels)
        processor.process(block(64) { 1f })
        processor.setGain(0.3f)
        repeat(40) { processor.process(block(128) { 1f }) }

        processor.reset()
        processor.configure(sampleRate, channels)

        val audio = block(64) { 1f }
        processor.process(audio)
        assertEquals(0.3f, audio.samples[0], 1e-6f)
    }

    // ── Position in the chain ──────────────────────────────────────────────

    @Test
    fun `the level stage runs after loudness so it cannot drive the limiter`() {
        val chain = DspChain()
        chain.configure(sampleRate, channels)
        chain.transition.open()
        chain.process(block(64) { 0.95f })
        chain.volume.setGain(0.1f)

        // Let the level glide settle before measuring, so the assertion is
        // about the chain's ordering rather than about the ramp.
        repeat(40) { chain.process(block(512) { 0.95f }) }

        val audio = block(1024) { 0.95f }
        chain.process(audio)

        // A trim applied before the limiter would have been partly undone by it
        // and the result would sit above the target; applied after, the output
        // is the finished signal scaled by the requested level.
        val expected = 0.95f * 0.1f
        for (frame in 0 until 1024) {
            assertEquals("frame $frame", expected, audio.samples[frame * channels], 1e-6f)
        }
    }

    @Test
    fun `the level is a pure scalar on top of the other stages`() {
        // Two chains differing only in level, both with the equaliser shaping
        // the signal. If the trim interacted with any earlier stage — feeding
        // back into the limiter, say — the two outputs would not be in exact
        // proportion. They must be.
        fun run(level: Float): FloatArray {
            val chain = DspChain()
            chain.configure(sampleRate, channels)
            chain.transition.open()
            val gains = FloatArray(EqLayout.SLOTS)
            val qs = FloatArray(EqLayout.SLOTS) { 0.707f }
            gains[3] = 6f
            chain.equalizer.setTuning(enabled = true, EqCurve(gains, qs, preampDb = -3f), balance = 0f)
            chain.flush()
            chain.process(block(64) { 0.7f })
            chain.volume.setGain(level)
            repeat(40) { chain.process(block(512) { 0.7f }) }
            val audio = sine(512)
            chain.process(audio)
            return audio.samples.copyOf(audio.sampleCount)
        }

        val unity = run(1f)
        val half = run(0.5f)

        for (i in unity.indices) {
            assertEquals("sample $i", unity[i] * 0.5f, half[i], 1e-6f)
        }
        assertFalse("sanity: the equaliser must actually be shaping", unity.contentEquals(sine(512).samples))
    }
}
