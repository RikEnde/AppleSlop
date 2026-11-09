// SPDX-FileCopyrightText: 2026 The AppleIIslop contributors
// SPDX-License-Identifier: BSD-3-Clause

package appleiislop.apple2

/**
 * The built-in I/O at `$C000-$C07F`. Most of it is "soft switches": an address
 * does its job when it is touched, by a read or a write alike, and the data is
 * beside the point.
 *
 * | Address | Read | Any access |
 * |---|---|---|
 * | `$C000-$C00F` | Keyboard data, strobe in bit 7 | |
 * | `$C010-$C01F` | | Clear the keyboard strobe |
 * | `$C020-$C02F` | | Toggle the cassette output |
 * | `$C030-$C03F` | | Toggle the speaker |
 * | `$C040-$C04F` | | Game I/O strobe (not connected) |
 * | `$C050`, `$C051` | | Graphics, text |
 * | `$C052`, `$C053` | | Full screen, mixed with four lines of text |
 * | `$C054`, `$C055` | | Page 1, page 2 |
 * | `$C056`, `$C057` | | Lo-res, hi-res |
 * | `$C058-$C05F` | | Annunciators 0-3: even off, odd on |
 * | `$C060`, `$C068` | Cassette input in bit 7 | |
 * | `$C061-$C063` | Push buttons 0-2 in bit 7 | |
 * | `$C064-$C067` | Paddle timers 0-3 in bit 7 | |
 * | `$C070-$C07F` | | Start the paddle timers |
 *
 * `$C060-$C067` repeat at `$C068-$C06F`. Bits the hardware doesn't drive, and
 * whole addresses that only switch, read as the [floatingBus]. This is the
 * table in the *Apple II Reference Manual*, chapter 4.
 */
class SoftSwitches(
    private val keyboard: Keyboard,
    private val speaker: Speaker,
    private val video: Video,
    private val gameIo: GameIo,
    private val cassette: Cassette = Cassette(),
) {
    /** What an address that nothing drives reads: the video generator's last byte. */
    fun floatingBus(): Int = video.busByte

    /** A read of `$C000 + offset`. */
    fun read(offset: Int): Int {
        touch(offset)
        return peek(offset)
    }

    /** A write to `$C000 + offset`. */
    fun write(offset: Int, value: Int) = touch(offset)

    /** What a read would return, without switching anything. */
    fun peek(offset: Int): Int = when (offset shr 4) {
        0x0 -> keyboard.read()
        0x6 -> {
            val high = when (offset and 0x07) {
                0 -> cassette.input
                in 1..3 -> gameIo.buttons[(offset and 0x07) - 1]
                else -> gameIo.timing((offset and 0x07) - 4)
            }
            (floatingBus() and 0x7F) or (if (high) 0x80 else 0)
        }
        else -> floatingBus()
    }

    private fun touch(offset: Int) {
        when (offset shr 4) {
            0x1 -> keyboard.clearStrobe()
            0x2 -> cassette.toggleOutput()
            0x3 -> speaker.toggle()
            0x5 -> when (offset and 0x0F) {
                0x0 -> video.text = false
                0x1 -> video.text = true
                0x2 -> video.mixed = false
                0x3 -> video.mixed = true
                0x4 -> video.page2 = false
                0x5 -> video.page2 = true
                0x6 -> video.hires = false
                0x7 -> video.hires = true
                else -> video.annunciators[(offset shr 1) and 3] = offset and 1 != 0
            }
            0x7 -> gameIo.trigger()
        }
    }
}
