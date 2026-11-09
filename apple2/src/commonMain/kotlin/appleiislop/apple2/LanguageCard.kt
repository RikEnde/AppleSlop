// SPDX-FileCopyrightText: 2026 The AppleIIslop contributors
// SPDX-License-Identifier: BSD-3-Clause

package appleiislop.apple2

/**
 * The Language Card in slot 0: 16 KiB of RAM that can take the place of the
 * ROM at `$D000-$FFFF`, making a 64 KiB machine. `$D000-$DFFF` has two banks,
 * because the 16 KiB has to fit in 12 KiB of addresses.
 *
 * It is switched by accesses to `$C080-$C08F`:
 *
 * | Bits 1-0 | Reading gives | Writing |
 * |---|---|---|
 * | 00 | RAM | protected |
 * | 01 | ROM | enabled, after two reads in a row |
 * | 10 | ROM | protected |
 * | 11 | RAM | enabled, after two reads in a row |
 *
 * Bit 3 clear selects bank 2 of `$D000`, set bank 1. A write access to an odd
 * address does not count as one of the two reads. The card powers on reading
 * ROM, write-enabled, with bank 2, and RESET does not change it.
 * (*Understanding the Apple II*, chapter 5.)
 */
class LanguageCard {
    /** `$D000-$DFFF` bank 1, then bank 2, then `$E000-$FFFF`. */
    val ram = ByteArray(0x4000)

    var readRam = false
        private set
    var writeEnabled = true
        private set

    /** 1 or 2. */
    var bank = 2
        private set

    private var preWrite = false

    /** An access to `$C080 + offset`. */
    fun access(offset: Int, isWrite: Boolean) {
        bank = if (offset and 0x08 != 0) 1 else 2
        readRam = (offset and 0x03) == 0 || (offset and 0x03) == 3
        if (offset and 0x01 != 0) {
            if (isWrite) {
                preWrite = false
            } else {
                if (preWrite) writeEnabled = true
                preWrite = true
            }
        } else {
            preWrite = false
            writeEnabled = false
        }
    }

    fun read(address: Int): Int = ram[index(address)].toInt() and 0xFF

    fun write(address: Int, value: Int) {
        if (writeEnabled) ram[index(address)] = value.toByte()
    }

    private fun index(address: Int): Int = when {
        address >= 0xE000 -> address - 0xC000
        bank == 1 -> address - 0xD000
        else -> address - 0xC000
    }
}
