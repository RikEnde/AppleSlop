// SPDX-FileCopyrightText: 2026 The AppleIIslop contributors
// SPDX-License-Identifier: BSD-3-Clause

package appleiislop.apple2

/**
 * The Disk II interface card and its two drives.
 *
 * The card has almost no logic of its own: sixteen addresses that switch things,
 * a shift register, and a small state machine in a PROM. The program does the
 * rest, in loops timed to the disk.
 *
 * | Address | Switch |
 * |---|---|
 * | `0-7` | Stepper motor phases 0-3: even off, odd on |
 * | `8`, `9` | Drive motor off, on |
 * | `A`, `B` | Select drive 1, drive 2 |
 * | `C`, `D` | Q6 low, high |
 * | `E`, `F` | Q7 low, high |
 *
 * Q7 low reads: with Q6 low the data register fills from the disk, and with Q6
 * high it holds the write-protect sensor in bit 7. Q7 high writes: a write to
 * an address with Q6 high loads the data register, which is then shifted out.
 * Reading any even address returns the data register.
 *
 * The disk is modelled a nibble at a time, not a bit at a time:
 *
 * - It turns at 300 rpm, and a nibble passes the head every 32 cycles (8 bits
 *   of 4 µs). The head's place on the track comes from the clock, so a program
 *   has to keep up with the disk as on the real machine.
 * - A nibble is ready, with bit 7 set, for the first [VALID_TICKS] cycles of its
 *   32; the rest of the time the register is still shifting, and reads with
 *   bit 7 clear. A loop that polls every 7 or 8 cycles sees each nibble once.
 * - Each write lands in the nibble under the head, and the next begins there,
 *   so the 40-cycle self-sync bytes that DOS writes take one place each.
 * - The motor runs on for a second after it is switched off.
 * - The head moves half a track when the phase next to the one holding it is
 *   switched on. Data is only read from whole tracks.
 *
 * (*Understanding the Apple II*, chapter 9; *Beneath Apple DOS*, chapter 6.)
 */
class DiskII(rom: ByteArray, private val clock: Apple2Clock) : SlotCard {
    private val rom = rom.copyOf()

    /** A drive: the disk in it and where its head is. */
    class Drive {
        var disk: Disk? = null

        /** The head's position in half tracks, 0-69. */
        var halfTrack: Int = 0
            internal set

        val track: Int get() = minOf(halfTrack / 2, Disk.TRACKS - 1)
    }

    val drives = arrayOf(Drive(), Drive())

    /** 0 or 1, for drive 1 or drive 2. */
    var selected: Int = 0
        private set

    var motorOn: Boolean = false
        private set

    private var motorOffTick = Long.MIN_VALUE / 2
    private var q6 = false
    private var q7 = false
    private var register = 0

    /** Where the disk was at [anchorTick]: a nibble's index on the track. */
    private var anchorPosition = 0
    private var anchorTick = 0L

    init {
        require(rom.size == 256) { "The Disk II ROM must be 256 bytes" }
    }

    /** True while a disk is turning: the motor is on, or was until less than a second ago. */
    val spinning: Boolean get() = motorOn || clock.ticks - motorOffTick < SPIN_DOWN_TICKS

    override fun readRom(offset: Int): Int = rom[offset and 0xFF].toInt() and 0xFF

    override fun reset() {
        switchMotor(false)
        selected = 0
        q6 = false
        q7 = false
    }

    override fun readIo(offset: Int): Int {
        switch(offset)
        if (offset and 1 != 0) return 0
        return when {
            q7 -> 0
            q6 -> if (drives[selected].disk?.writeProtected == true) 0x80 else 0
            else -> readNibble()
        }
    }

    override fun writeIo(offset: Int, value: Int) {
        switch(offset)
        if (q7 && q6 && offset and 1 != 0) writeNibble(value and 0xFF)
    }

    override fun peekIo(offset: Int): Int = register

    private fun switch(offset: Int) {
        when (offset and 0x0F) {
            in 0..7 -> if (offset and 1 != 0) step((offset shr 1) and 3)
            0x8 -> switchMotor(false)
            0x9 -> switchMotor(true)
            0xA -> selected = 0
            0xB -> selected = 1
            0xC -> q6 = false
            0xD -> q6 = true
            0xE -> q7 = false
            0xF -> q7 = true
        }
    }

    private fun switchMotor(on: Boolean) {
        if (motorOn && !on) motorOffTick = clock.ticks
        motorOn = on
    }

    /** Phase [phase] is switched on: the head moves if that is the phase next to it. */
    private fun step(phase: Int) {
        if (!spinning) return
        val drive = drives[selected]
        val before = drive.track
        val direction = when (phase) {
            (drive.halfTrack + 1) and 3 -> 1
            (drive.halfTrack + 3) and 3 -> -1
            else -> return
        }
        drive.halfTrack = (drive.halfTrack + direction).coerceIn(0, MAX_HALF_TRACK)
        val disk = drive.disk ?: return
        if (drive.track == before) return
        // The same angle on the new track.
        val from = disk.track(before).size
        val to = disk.track(drive.track).size
        if (from > 0) anchorPosition = (position(from).toLong() * to / from).toInt()
        anchorTick = clock.ticks
    }

    /** The index of the nibble under the head on a track of [length] nibbles. */
    private fun position(length: Int): Int = ((anchorPosition + (clock.ticks - anchorTick) / NIBBLE_TICKS) % length).toInt()

    private fun readNibble(): Int {
        val drive = drives[selected]
        val disk = drive.disk
        if (disk == null || !spinning) return 0
        val nibbles = disk.track(drive.track)
        if (nibbles.isEmpty()) return 0
        register = nibbles[position(nibbles.size)].toInt() and 0xFF
        val age = (clock.ticks - anchorTick) % NIBBLE_TICKS
        return if (age < VALID_TICKS) register else register and 0x7F
    }

    private fun writeNibble(value: Int) {
        register = value
        val drive = drives[selected]
        val disk = drive.disk
        if (disk == null || !spinning || disk.writeProtected) return
        val length = disk.track(drive.track).size
        if (length == 0) return
        val position = position(length)
        disk.write(drive.track, position, value)
        // The next nibble starts when this one is done, however long the program takes over it.
        anchorPosition = position
        anchorTick = clock.ticks
    }

    companion object {
        /** Eight bit cells of four cycles. */
        const val NIBBLE_TICKS = 32

        /** How long a nibble stays in the data register before the next one starts to shift in. */
        const val VALID_TICKS = 8

        const val SPIN_DOWN_TICKS = Apple2Clock.TICKS_PER_SECOND.toLong()

        /** The stepper can go a little past track 34. */
        const val MAX_HALF_TRACK = 69
    }
}
