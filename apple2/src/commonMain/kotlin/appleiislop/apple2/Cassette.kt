// SPDX-FileCopyrightText: 2026 The AppleIIslop contributors
// SPDX-License-Identifier: BSD-3-Clause
package appleiislop.apple2

/** PCM tape medium. Platform file formats live in the desktop module. */
class Tape(val sampleRate: Int = 44_100, samples: ShortArray = ShortArray(0)) {
    init { require(sampleRate in 8_000..192_000) }
    private var data = samples.copyOf(maxOf(samples.size, 1024))
    var size = samples.size
        private set
    fun sample(index: Int): Short = if (index in 0 until size) data[index] else 0
    fun samples(): ShortArray = data.copyOf(size)
    internal fun write(index: Int, value: Short) {
        if (index >= data.size) data = data.copyOf(maxOf(index + 1, data.size * 2))
        data[index] = value
        size = maxOf(size, index + 1)
    }
}

/** External recorder: transport is manual, with no motor control from the Apple II.
 * C020 toggles the output flip-flop; C060 reads the inverted input comparator.
 * Sampling follows machine time, so pausing emulation also pauses the recorder.
 */
class Cassette {
    enum class Mode { STOPPED, PLAYING, RECORDING }
    var tape: Tape? = null
        private set
    var mode = Mode.STOPPED
        private set
    var position = 0
        private set
    private var phase = 0
    var output = false
        private set
    val input: Boolean get() = mode != Mode.PLAYING || (tape?.sample(position)?.toInt() ?: 0) <= 0
    val seconds: Double get() = position.toDouble() / (tape?.sampleRate ?: 44_100)

    fun insert(medium: Tape?) { stop(); tape = medium; position = 0; phase = 0 }
    fun play() { if (position < (tape?.size ?: 0)) mode = Mode.PLAYING }
    fun record() { if (tape != null) mode = Mode.RECORDING }
    fun stop() { mode = Mode.STOPPED }
    fun rewind() { stop(); position = 0; phase = 0 }
    fun toggleOutput() { output = !output }
    fun tick() {
        if (mode == Mode.STOPPED) return
        val medium = tape ?: return
        phase += medium.sampleRate
        if (phase < Apple2Clock.TICKS_PER_SECOND) return
        phase -= Apple2Clock.TICKS_PER_SECOND
        if (mode == Mode.RECORDING) medium.write(position, (if (output) 24576 else -24576).toShort())
        position++
        if (mode == Mode.PLAYING && position >= medium.size) stop()
    }
}
