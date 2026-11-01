package com.music.bitchord.playback

import com.music.bitchord.playback.audio.AudioBlock
import kotlin.math.abs
import kotlin.math.exp

/**
 * The listener's own output level, as a gain inside the DSP chain.
 *
 * ## Why here and not `ExoPlayer.volume`
 *
 * `ExoPlayer.volume` is already spoken for: [CrossfadeController] writes it on
 * every transition, every sleep fade and every clickless speed change, and
 * [com.music.bitchord.playback.PlaybackService] writes it during a version
 * swap. A second writer on that field would be overwritten mid-blend, and the
 * in-app level would jump back to unity at every handoff. This stage instead
 * scales the Float32 samples inside the sink, so it is independent of both the
 * system stream volume and the crossfade's own gain automation.
 *
 * ## Why last
 *
 * It sits after [LoudnessProcessor], so the limiter still sees the signal every
 * other stage has finished shaping, and the level is a plain trim on the
 * result. Attenuating after limiting cannot clip, and turning the level down
 * does not make the limiter work harder to compensate — the two controls stay
 * independent, which is the whole point of having both.
 *
 * ## Unity is a bypass, not a multiply
 *
 * At 100 % the stage returns before it reads a sample. That is what keeps the
 * chain bit-exact (see [DspChain]) and what lets [PcmBoundary]'s power-of-two
 * scaling round-trip 16- and 24-bit integers unchanged. Once a ramp reaches its
 * target the gain is snapped to it exactly, so returning to 100 % parks the
 * stage rather than leaving it multiplying by 0.9999.
 *
 * ## Gliding
 *
 * A slider is a stream of small jumps; applying them as steps zippers. The
 * gain therefore glides per frame with the same 40 ms time constant
 * [LoudnessProcessor] uses, which is slow enough to be inaudible as a step and
 * fast enough to feel immediate.
 *
 * [setGain] takes a linear amplitude in 0..1 — 0 is silence, 1 is unity. Any
 * perceptual mapping from a percentage belongs to the caller, not here.
 */
class VolumeProcessor {

    /**
     * Where the gain is heading, linear amplitude 0..1. Readable for tests and readouts.
     *
     * Volatile because it is written from the service (a settings collector) and
     * read on the audio thread each block — the same reason
     * [SpatialAudioProcessor.enabled] is. Everything else here is audio-thread
     * state and needs no such care.
     */
    @Volatile
    var target: Float = 1f
        private set

    /** The gain applied to the last block, kept so a ramp can resume mid-way. */
    private var current = 1f

    private var sampleRate = 0
    private var channelCount = 0
    private var glideCoef = 1f

    /** Apply [target] outright on the next block instead of gliding to it. */
    private var snap = true

    /**
     * Sets the level, 0..1. Out-of-range values are clamped; a non-finite one is
     * ignored rather than allowed to poison the audio path with NaN.
     */
    fun setGain(gain: Float) {
        if (!gain.isFinite()) return
        target = gain.coerceIn(0f, 1f)
    }

    fun configure(sampleRate: Int, channelCount: Int) {
        this.sampleRate = sampleRate
        this.channelCount = channelCount
        glideCoef = if (sampleRate <= 0) {
            1f
        } else {
            (1.0 - exp(-1.0 / (GLIDE_SECONDS * sampleRate))).toFloat()
        }
        snap = true
    }

    /**
     * A seek or a stream restart. The output is discontinuous across it anyway,
     * so the gain jumps to its target rather than gliding in from wherever the
     * previous stream left it.
     */
    fun flush() {
        snap = true
    }

    fun reset() {
        sampleRate = 0
        channelCount = 0
        current = target
        snap = true
    }

    fun process(block: AudioBlock) {
        val frames = block.frameCount
        val channels = channelCount
        if (frames == 0 || channels < 1 || sampleRate <= 0) return

        val wanted = target
        if (snap) {
            current = wanted
            snap = false
        }
        // Parked at unity: return before touching a sample, so the block leaves
        // this stage byte-identical to how it arrived.
        if (wanted == 1f && current == 1f) return

        val samples = block.samples
        var gain = current
        var index = 0
        for (frame in 0 until frames) {
            gain += (wanted - gain) * glideCoef
            for (channel in 0 until channels) {
                samples[index + channel] *= gain
            }
            index += channels
        }
        // Land exactly on the target once the glide is within a hair of it, so
        // the next block can take the bypass again instead of multiplying by
        // 0.9999 for the rest of the session.
        if (abs(wanted - gain) < SETTLED) gain = wanted
        current = gain
    }

    companion object {
        /** Time constant for level changes: long enough not to zipper, short enough to feel direct. */
        private const val GLIDE_SECONDS = 0.04

        /** Close enough to the target to call it arrived; about 0.0009 dB at unity. */
        private const val SETTLED = 1e-4f
    }
}
