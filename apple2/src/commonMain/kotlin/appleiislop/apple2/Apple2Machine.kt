// SPDX-FileCopyrightText: 2026 The Beebslop contributors
// SPDX-FileCopyrightText: 2026 The AppleIIslop contributors
// SPDX-License-Identifier: BSD-3-Clause

package appleiislop.apple2

import appleiislop.cpu.Cpu6502

/**
 * One Apple II Plus, as just switched on. UI, file loading and real-time pacing
 * live outside.
 *
 * It has 48 KiB of RAM on the main board, Applesoft BASIC and the Autostart
 * monitor in ROM, a Language Card in slot 0 for 16 KiB more, and, given its
 * ROM, a Disk II interface in slot 6 with two drives. At power-on the Autostart
 * ROM looks for a disk interface from slot 7 down and boots from the first it
 * finds; with none, it starts BASIC.
 *
 * The cassette interface connects to a manually operated recorder.
 * No interrupts are wired: the 6502's IRQ and NMI only go to the
 * slots, and no card here uses them.
 */
class Apple2Machine(rom: ByteArray, characterRom: ByteArray, diskRom: ByteArray? = null) {
    val ram = ByteArray(0xC000)
    val clock = Apple2Clock()
    val keyboard = Keyboard()
    val speaker = Speaker()
    val gameIo = GameIo(clock)
    val video = Video(ram, characterRom)
    val cassette = Cassette()
    val switches = SoftSwitches(keyboard, speaker, video, gameIo, cassette)
    val languageCard = LanguageCard()
    val bus = Apple2MemoryBus(ram, rom, clock, switches, languageCard)
    val cpu = Cpu6502(bus)

    /** The Disk II interface in slot 6; present only with its ROM. */
    val disk: DiskII? = diskRom?.let { DiskII(it, clock) }

    init {
        bus.attach(DISK_SLOT, disk)
        clock.onTick = ::tick
        cpu.reset()
    }

    /**
     * Press RESET. It resets the CPU and the cards in the slots; RAM, the soft
     * switches and the Language Card stay as they are, and the monitor ROM puts
     * the display back in text mode.
     */
    fun reset() {
        disk?.reset()
        cpu.reset()
    }

    /** Execute one CPU instruction; return the elapsed machine ticks. */
    fun step(): Int {
        val before = clock.ticks
        cpu.step()
        return (clock.ticks - before).toInt()
    }

    /** Run until at least [count] ticks have elapsed; stops at an instruction boundary. */
    fun runForTicks(count: Long) {
        require(count >= 0)
        val start = clock.ticks
        while (clock.ticks - start < count) step()
    }

    fun runForSeconds(seconds: Double) = runForTicks((seconds * Apple2Clock.TICKS_PER_SECOND).toLong())

    /** Text page 1 as 24 lines of characters. */
    fun textLines(): List<String> = video.textLines()

    @Suppress("UNUSED_PARAMETER")
    private fun tick(ticks: Long) {
        video.tick()
        speaker.tick()
        cassette.tick()
    }

    companion object {
        /** The slot the Disk II interface usually goes in. */
        const val DISK_SLOT = 6
    }
}
