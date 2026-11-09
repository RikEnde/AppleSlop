// SPDX-FileCopyrightText: 2026 The AppleIIslop contributors
// SPDX-License-Identifier: BSD-3-Clause

package appleiislop.apple2

/**
 * A card in one of the expansion slots 1-7. Slot n has 16 I/O addresses at
 * `$C080 + n * 16`, and 256 bytes of ROM at `$Cn00`. No card here uses the
 * shared expansion ROM space at `$C800-$CFFF`.
 */
interface SlotCard {
    /** A read of I/O address 0-15. */
    fun readIo(offset: Int): Int

    /** A write to I/O address 0-15. */
    fun writeIo(offset: Int, value: Int)

    /** Read for debuggers and tests, without side effects. */
    fun peekIo(offset: Int): Int

    /** The card's ROM, 0-255. */
    fun readRom(offset: Int): Int

    /** The RESET line. */
    fun reset()
}
