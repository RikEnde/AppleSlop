// SPDX-FileCopyrightText: 2026 The AppleIIslop contributors
// SPDX-License-Identifier: BSD-3-Clause

package appleiislop.apple2

import appleiislop.cpu.MemoryBus

/**
 * Apple II Plus address decoding (*Apple II Reference Manual*, chapter 4):
 *
 * | Address | Contents |
 * |---|---|
 * | `$0000-$BFFF` | RAM, 48 KiB |
 * | `$C000-$C07F` | Built-in I/O: the [SoftSwitches] |
 * | `$C080-$C08F` | Slot 0 I/O: the [LanguageCard] |
 * | `$C090-$C0FF` | Slot 1-7 I/O, 16 addresses each |
 * | `$C100-$C7FF` | Slot 1-7 ROM, 256 bytes each |
 * | `$C800-$CFFF` | Expansion ROM, shared by the slots; unused here |
 * | `$D000-$FFFF` | ROM: Applesoft BASIC and the Autostart monitor, or the Language Card's RAM |
 *
 * Each bus call advances [clock] by one cycle and then performs the access.
 * Where nothing answers, a read gets the byte the video generator last put on
 * the data bus, the "floating bus", which some programs use to follow the
 * display.
 */
class Apple2MemoryBus(
    val ram: ByteArray,
    rom: ByteArray,
    val clock: Apple2Clock,
    private val switches: SoftSwitches,
    val languageCard: LanguageCard?,
) : MemoryBus {
    private val rom = rom.copyOf()
    private val slots = arrayOfNulls<SlotCard>(8)

    init {
        require(ram.size == 0xC000) { "RAM must be 48 KiB" }
        require(rom.size == 0x3000) { "The ROM must be 12 KiB" }
    }

    /** Put a card in slot 1-7. Slot 0 is the Language Card's. */
    fun attach(slot: Int, card: SlotCard?) {
        require(slot in 1..7) { "Cards go in slots 1-7" }
        slots[slot] = card
    }

    fun card(slot: Int): SlotCard? = slots[slot]

    override fun read(address: Int): Int {
        val location = address and 0xFFFF
        clock.cycle()
        return readMapped(location, sideEffects = true)
    }

    override fun write(address: Int, value: Int) {
        val location = address and 0xFFFF
        val data = value and 0xFF
        clock.cycle()
        when (location) {
            in 0x0000..0xBFFF -> ram[location] = data.toByte()
            in 0xC000..0xC07F -> switches.write(location and 0x7F, data)
            in 0xC080..0xC08F -> languageCard?.access(location and 0x0F, isWrite = true)
            in 0xC090..0xC0FF -> slots[(location shr 4) and 7]?.writeIo(location and 0x0F, data)
            in 0xD000..0xFFFF -> languageCard?.write(location, data)
            // Slot ROMs, and the empty expansion ROM space, ignore writes.
        }
    }

    /** Debugger read without changing time or device state. */
    fun peek(address: Int): Int = readMapped(address and 0xFFFF, sideEffects = false)

    private fun readMapped(address: Int, sideEffects: Boolean): Int = when (address) {
        in 0x0000..0xBFFF -> ram[address].toInt() and 0xFF
        in 0xC000..0xC07F -> if (sideEffects) switches.read(address and 0x7F) else switches.peek(address and 0x7F)
        in 0xC080..0xC08F -> {
            if (sideEffects) languageCard?.access(address and 0x0F, isWrite = false)
            switches.floatingBus()
        }
        in 0xC090..0xC0FF -> {
            val card = slots[(address shr 4) and 7]
            when {
                card == null -> switches.floatingBus()
                sideEffects -> card.readIo(address and 0x0F)
                else -> card.peekIo(address and 0x0F)
            }
        }
        in 0xC100..0xC7FF -> slots[(address shr 8) and 7]?.readRom(address and 0xFF) ?: switches.floatingBus()
        in 0xC800..0xCFFF -> switches.floatingBus()
        else -> if (languageCard?.readRam == true) languageCard.read(address) else rom[address - 0xD000].toInt() and 0xFF
    }
}
