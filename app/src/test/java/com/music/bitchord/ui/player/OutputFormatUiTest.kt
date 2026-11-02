package com.music.bitchord.ui.player

import com.music.bitchord.playback.AudioOutputStatus
import com.music.bitchord.playback.audio.bitperfect.BitPerfectOutput
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The in-app volume is a gain inside the DSP chain, so its row must only offer
 * itself while that gain is the control. These cover both the value itself and
 * the flow that carries it to the sheet: enabling bit-perfect changes readiness
 * long before any transport opens, and the row has to follow that too.
 */
class OutputFormatUiTest {

    @Test
    fun dspAvailableOnAnOrdinaryPcmStream() {
        val ui = AudioOutputStatus.Snapshot().toOutputFormat(
            BitPerfectOutput.Status(),
            bitPerfectReady = false,
        )

        assertTrue(ui.dspAvailable)
    }

    @Test
    fun dspUnavailableOnAPassthroughBitstream() {
        val ui = AudioOutputStatus.Snapshot(dspAvailable = false).toOutputFormat(
            BitPerfectOutput.Status(),
            bitPerfectReady = false,
        )

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
            bitPerfectReady = false,
        )

        assertFalse(ui.dspAvailable)
    }

    /**
     * The mode is on and its device is attached, but no transport is open yet
     * — paused playback, or a lossy track. The rest of the settings screen
     * already treats this as "bit-perfect governs this output", and the volume
     * row has to agree, or it looks live in exactly the state it is not.
     */
    @Test
    fun dspUnavailableWhileBitPerfectIsReady() {
        val ui = AudioOutputStatus.Snapshot().toOutputFormat(
            BitPerfectOutput.Status(),
            bitPerfectReady = true,
        )

        assertFalse(ui.dspAvailable)
    }

    @Test
    fun dspAvailableAgainAfterBitPerfectFallsBack() {
        val ui = AudioOutputStatus.Snapshot().toOutputFormat(
            BitPerfectOutput.Status(active = false, fallbackReason = "no device"),
            bitPerfectReady = false,
        )

        assertTrue(ui.dspAvailable)
    }

    /**
     * The transition the sheet actually observes: readiness and the open
     * transport each have to move the published value on their own, without a
     * format change to drag it along.
     */
    @Test
    fun availabilityFollowsTheExclusiveOutputsState() = runTest {
        val status = MutableStateFlow(AudioOutputStatus.Snapshot())
        val bitPerfect = MutableStateFlow(BitPerfectOutput.Status())
        val ready = MutableStateFlow(false)
        val format = outputFormatFlow(status, bitPerfect, ready)
            .stateIn(
                backgroundScope,
                SharingStarted.Eagerly,
                status.value.toOutputFormat(bitPerfect.value, ready.value),
            )
        runCurrent()

        assertTrue(format.value.dspAvailable)

        // Mode enabled with a DAC attached, before the first buffer.
        ready.value = true
        runCurrent()
        assertFalse(format.value.dspAvailable)

        // The transport opens for the playing track.
        bitPerfect.value = BitPerfectOutput.Status(
            active = true,
            sampleRateHz = 96_000,
            outputBits = 24,
            exact = true,
        )
        runCurrent()
        assertFalse(format.value.dspAvailable)

        // Mode turned off: normal output, and the gain works again.
        bitPerfect.value = BitPerfectOutput.Status()
        ready.value = false
        runCurrent()
        assertTrue(format.value.dspAvailable)

        // A passthrough bitstream disables it without any bit-perfect state.
        status.value = AudioOutputStatus.Snapshot(dspAvailable = false)
        runCurrent()
        assertFalse(format.value.dspAvailable)

        // Back to an ordinary PCM stream.
        status.value = AudioOutputStatus.Snapshot()
        runCurrent()
        assertTrue(format.value.dspAvailable)
    }
}