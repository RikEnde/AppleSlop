// SPDX-FileCopyrightText: 2026 The AppleIIslop contributors
// SPDX-License-Identifier: BSD-3-Clause

package appleiislop.apple2

/**
 * The game I/O socket: three push buttons and four paddles.
 *
 * A paddle is a variable resistor that charges a capacitor in a 558 timer.
 * Touching `$C070` starts all four timers; bit 7 of `$C064-$C067` then reads 1
 * until that paddle's timer runs out, after about 11 cycles for each of the
 * 0-255 steps the monitor's PREAD routine counts. With no paddles plugged in,
 * every paddle here rests in the middle.
 */
class GameIo(private val clock: Apple2Clock) {
    /** Push buttons 0-2, at `$C061-$C063`. */
    val buttons = BooleanArray(3)

    /** Paddle positions 0-255, at `$C064-$C067`. */
    val paddles = IntArray(4) { CENTRE }

    private var triggered = Long.MIN_VALUE / 2

    /** An access to `$C070`: start the timers. */
    fun trigger() {
        triggered = clock.ticks
    }

    /** True while paddle [index]'s timer is still running. */
    fun timing(index: Int): Boolean = clock.ticks - triggered < paddles[index].toLong() * TICKS_PER_STEP

    companion object {
        const val CENTRE = 127

        /** PREAD's loop takes 11 cycles a count. */
        const val TICKS_PER_STEP = 11
    }
}
