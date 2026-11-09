// SPDX-FileCopyrightText: 2026 The AppleIIslop contributors
// SPDX-License-Identifier: BSD-3-Clause

package appleiislop.apple2

/**
 * The keyboard, as the computer sees it: the encoder puts a 7-bit ASCII code in
 * a latch and sets the strobe. A program reads both at `$C000` (the strobe is
 * bit 7), and clears the strobe by touching `$C010`. A key pressed before the
 * last one was read replaces it; there is no buffer.
 *
 * The Apple II Plus keyboard makes upper case only. Which host key makes which
 * code is the front end's business; see [KeyboardText].
 */
class Keyboard {
    /** The last key's ASCII code, 0-127. */
    var latch: Int = 0
        private set

    /** Set by a key press, cleared when the program accesses `$C010`. */
    var strobe: Boolean = false
        private set

    /** A key goes down: [ascii] is latched and the strobe set. */
    fun press(ascii: Int) {
        latch = ascii and 0x7F
        strobe = true
    }

    /** What `$C000` reads. */
    fun read(): Int = latch or (if (strobe) 0x80 else 0)

    fun clearStrobe() {
        strobe = false
    }
}
