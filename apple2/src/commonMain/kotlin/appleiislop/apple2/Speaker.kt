// SPDX-FileCopyrightText: 2026 The Beebslop contributors
// SPDX-FileCopyrightText: 2026 The AppleIIslop contributors
// SPDX-License-Identifier: BSD-3-Clause

package appleiislop.apple2

/**
 * The speaker: one flip-flop, toggled by any access to `$C030-$C03F`. A program
 * makes a tone by toggling it in a timed loop, so pitch and volume come from
 * the CPU's timing alone.
 *
 * [startSampling] resamples the output for the host. Each sample is the mean
 * level over the ticks it covers, a simple anti-alias filter, followed by a
 * DC-blocking high-pass filter, like the capacitor in series with the speaker.
 * The resampling is Beebslop's, from its `Sn76489`.
 */
class Speaker {
    /** The flip-flop: false or true, for the cone in or out. */
    var state: Boolean = false
        private set

    /** Toggles since power-on. */
    var toggles: Long = 0
        private set

    fun toggle() {
        state = !state
        toggles++
    }

    private var sampleRate = 0
    private var samples = FloatArray(0)
    private var sampleCount = 0
    private var phase = 0
    private var levelSum = 0f
    private var levelTicks = 0
    private var lastInput = 0f
    private var lastOutput = 0f

    /** Samples available to [drainSamples]. */
    val bufferedSamples: Int get() = sampleCount

    /**
     * Start producing samples, about -0.5 to 0.5, at [rate] Hz. Up to [capacity]
     * samples are buffered; if the host does not drain them, newer ones are dropped.
     */
    fun startSampling(rate: Int, capacity: Int = rate / 5) {
        require(rate in 1..Apple2Clock.TICKS_PER_SECOND && capacity > 0)
        sampleRate = rate
        samples = FloatArray(capacity)
        sampleCount = 0
        phase = 0
        levelSum = 0f
        levelTicks = 0
        lastInput = level()
        lastOutput = 0f
    }

    /** Move buffered samples into [destination]; returns how many were copied. */
    fun drainSamples(destination: FloatArray): Int {
        val n = minOf(sampleCount, destination.size)
        samples.copyInto(destination, 0, 0, n)
        samples.copyInto(samples, 0, n, sampleCount)
        sampleCount -= n
        return n
    }

    /** One machine tick. */
    fun tick() {
        if (sampleRate == 0) return
        levelSum += level()
        levelTicks++
        phase += sampleRate
        if (phase < Apple2Clock.TICKS_PER_SECOND) return
        phase -= Apple2Clock.TICKS_PER_SECOND
        val input = levelSum / levelTicks
        lastOutput = input - lastInput + DC_BLOCK * lastOutput
        lastInput = input
        if (sampleCount < samples.size) samples[sampleCount++] = lastOutput
        levelSum = 0f
        levelTicks = 0
    }

    private fun level(): Float = if (state) LEVEL else 0f

    companion object {
        /** The height of the square wave, of a full scale of -1 to 1. */
        const val LEVEL = 0.5f

        /** Pole of the DC-blocking filter; about 40 Hz at 48 kHz. */
        private const val DC_BLOCK = 0.995f
    }
}
