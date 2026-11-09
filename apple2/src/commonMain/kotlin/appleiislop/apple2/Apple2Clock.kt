// SPDX-FileCopyrightText: 2026 The Beebslop contributors
// SPDX-FileCopyrightText: 2026 The AppleIIslop contributors
// SPDX-License-Identifier: BSD-3-Clause

package appleiislop.apple2

/**
 * Machine time in CPU cycles.
 *
 * The Apple II divides its 14.31818 MHz crystal by 14 for the 6502, and
 * stretches the last cycle of each scan line by two crystal periods to keep the
 * colour subcarrier in step. A line is therefore 912 crystal periods and 65 CPU
 * cycles, which makes 1,020,484 cycles a second on average. Here every cycle is
 * one tick of equal length; the stretched cycle is not modelled.
 *
 * Video and CPU share RAM on alternate clock phases, so the display never holds
 * the CPU up, and every bus cycle is one tick.
 */
class Apple2Clock {
    var ticks: Long = 0
        private set

    /** Called after each tick with the new time; the machine clocks its hardware here. */
    var onTick: ((Long) -> Unit)? = null

    /** Advance time without the CPU (also useful in tests). */
    fun advance(count: Int) {
        require(count >= 0)
        val listener = onTick
        if (listener == null) {
            ticks += count
            return
        }
        repeat(count) {
            ticks++
            listener(ticks)
        }
    }

    /** One bus cycle. */
    internal fun cycle() = advance(1)

    companion object {
        /** 14,318,181.8 Hz x 65 / 912. */
        const val TICKS_PER_SECOND = 1_020_484

        /** CPU cycles in a scan line, and scan lines in an NTSC frame. */
        const val TICKS_PER_LINE = 65
        const val LINES_PER_FRAME = 262
        const val TICKS_PER_FRAME = TICKS_PER_LINE * LINES_PER_FRAME
    }
}
