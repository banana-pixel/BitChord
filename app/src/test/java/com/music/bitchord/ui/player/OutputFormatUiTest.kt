package com.music.bitchord.ui.player

import com.music.bitchord.playback.AudioOutputStatus
import com.music.bitchord.playback.audio.bitperfect.BitPerfectOutput
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The in-app volume is a gain inside the DSP chain, so its row must only offer
 * itself on a stream that reaches that chain. These cover both ways the chain
 * drops out: a bitstream that is never decoded to PCM, and the bit-perfect
 * output that hands samples straight to the DAC.
 */
class OutputFormatUiTest {

    @Test
    fun dspAvailableOnAnOrdinaryPcmStream() {
        val ui = AudioOutputStatus.Snapshot().toOutputFormat(BitPerfectOutput.Status())

        assertTrue(ui.dspAvailable)
    }

    @Test
    fun dspUnavailableOnAPassthroughBitstream() {
        val ui = AudioOutputStatus.Snapshot(dspAvailable = false)
            .toOutputFormat(BitPerfectOutput.Status())

        assertFalse(ui.dspAvailable)
    }

    @Test
    fun dspUnavailableWhileBitPerfectCarriesTheTrack() {
        val ui = AudioOutputStatus.Snapshot().toOutputFormat(
            BitPerfectOutput.Status(
                active = true,
                sampleRateHz = 44_100,
                outputBits = 24,
                exact = true,
            ),
        )

        assertFalse(ui.dspAvailable)
    }

    /**
     * The exclusive output can refuse a track and hand it back to the ordinary
     * sink within the same session; the row must come back with it.
     */
    @Test
    fun dspAvailableAgainAfterBitPerfectFallsBack() {
        val ui = AudioOutputStatus.Snapshot().toOutputFormat(
            BitPerfectOutput.Status(active = false, fallbackReason = "no device"),
        )

        assertTrue(ui.dspAvailable)
    }
}