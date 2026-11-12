// SPDX-FileCopyrightText: 2026 The AppleIIslop contributors
// SPDX-License-Identifier: BSD-3-Clause

package appleiislop.apple2

/**
 * The video generator. It free-runs: 262 lines of 65 CPU cycles, of which 192
 * lines of 40 cycles are displayed. In each displayed cycle it fetches one byte
 * of RAM, chosen by the soft switches as they are at that moment, so a program
 * that flips a switch in the middle of a frame changes the picture from there.
 * Each line is drawn once its last byte has been fetched.
 *
 * The picture is [WIDTH] by [HEIGHT] pixels, RGB as `0xRRGGBB`, with the 560 by
 * 384 display at ([LEFT], [TOP]) and a black border round it. A fetched byte
 * covers 14 pixels across, and each scan line is drawn twice.
 *
 * - **Text.** The byte's character comes from the character generator ROM: 8
 *   rows of 7 dots. Codes `$00-$3F` are inverse and `$40-$7F` flash.
 * - **Lo-res.** The byte is two blocks, the low nibble above the high one, each
 *   in one of 16 colours.
 * - **Hi-res.** Seven dots, bit 0 first. The colours are an artefact of NTSC: a
 *   lone dot is violet in an even column and green in an odd one, or blue and
 *   orange if the byte's bit 7 is set, which delays its dots by half a dot.
 *   Dots side by side are white. The picture is drawn that way, by rule, with
 *   no blur: a lit dot beside another is white, a lone dot takes its column's
 *   colour, and a dark dot between two lit ones is filled with their colour.
 *
 * [monochrome] shows the dots as a monochrome monitor would, without colour.
 */
class Video(private val ram: ByteArray, characterRom: ByteArray) {
    private val font = characterRom.copyOf()

    /** `$C050/$C051`: graphics or text. */
    var text = true

    /** `$C052/$C053`: with graphics, four lines of text at the bottom. */
    var mixed = false

    /** `$C054/$C055`: the second text or hi-res page. */
    var page2 = false

    /** `$C056/$C057`: lo-res or hi-res graphics. */
    var hires = false

    /** Annunciators 0-3, `$C058-$C05F`. They go to the game I/O socket, and nothing here uses them. */
    val annunciators = BooleanArray(4)

    /** Show dots in one colour, as a monochrome monitor does. */
    var monochrome = false

    /** The picture, updated as it is drawn. */
    val pixels = IntArray(WIDTH * HEIGHT)

    /** Frames completed: one each time the last displayed line has been drawn. */
    var frames: Long = 0
        private set

    /** The byte the video generator read last. Unused I/O addresses read it: the "floating bus". */
    var busByte = 0
        private set

    /** True during the 70 lines of vertical blanking. */
    val verticalBlank: Boolean get() = line >= DISPLAY_LINES

    private var cycle = 0
    private var line = 0
    private var flashOn = false

    private val kinds = IntArray(COLUMNS)
    private val bytes = IntArray(COLUMNS)

    /** Hi-res dots of one line, and the delay of the byte each came from. */
    private val dots = BooleanArray(COLUMNS * 7)
    private val delayed = BooleanArray(COLUMNS * 7)
    private val rowPixels = IntArray(DISPLAY_WIDTH)

    init {
        require(font.size == 0x800) { "The character generator ROM must be 2 KiB" }
    }

    /** One CPU cycle. */
    fun tick() {
        if (line < DISPLAY_LINES && cycle >= BLANK_CYCLES) fetch(cycle - BLANK_CYCLES)
        if (++cycle < Apple2Clock.TICKS_PER_LINE) return
        cycle = 0
        if (line < DISPLAY_LINES) draw()
        if (++line == Apple2Clock.LINES_PER_FRAME) line = 0
        if (line == DISPLAY_LINES) {
            frames++
            if (frames % FLASH_FRAMES == 0L) flashOn = !flashOn
        }
    }

    private fun fetch(column: Int) {
        val asText = text || (mixed && line >= MIXED_LINE)
        if (asText || !hires) {
            val code = ram[textAddress(line shr 3, page2) + column].toInt() and 0xFF
            busByte = code
            if (asText) {
                var pattern = font[code * 8 + (line and 7)].toInt() and 0x7F
                if (code < 0x40 || (code < 0x80 && flashOn)) pattern = pattern xor 0x7F
                kinds[column] = TEXT
                bytes[column] = pattern
            } else {
                kinds[column] = LORES
                bytes[column] = if (line and 4 == 0) code and 0x0F else code shr 4
            }
        } else {
            val data = ram[hiresAddress(line, page2) + column].toInt() and 0xFF
            busByte = data
            kinds[column] = HIRES
            bytes[column] = data
        }
    }

    private fun draw() {
        for (column in 0 until COLUMNS) {
            val hiresColumn = kinds[column] == HIRES
            for (bit in 0 until 7) {
                dots[column * 7 + bit] = hiresColumn && bytes[column] and (1 shl bit) != 0
                delayed[column * 7 + bit] = hiresColumn && bytes[column] and 0x80 != 0
            }
        }
        rowPixels.fill(BLACK)
        val lit = if (monochrome) PHOSPHOR else WHITE
        for (column in 0 until COLUMNS) {
            val x = column * 14
            val value = bytes[column]
            when (kinds[column]) {
                TEXT -> for (dot in 0 until 7) {
                    if (value and (0x40 shr dot) != 0) {
                        rowPixels[x + dot * 2] = lit
                        rowPixels[x + dot * 2 + 1] = lit
                    }
                }
                LORES -> for (i in 0 until 14) {
                    // On a monochrome monitor the colour shows as its four-dot pattern.
                    rowPixels[x + i] = when {
                        !monochrome -> PALETTE[value]
                        value and (1 shl ((x + i) and 3)) != 0 -> PHOSPHOR
                        else -> BLACK
                    }
                }
                else -> for (bit in 0 until 7) drawHiresDot(column * 7 + bit)
            }
        }
        val first = (TOP + line * 2) * WIDTH + LEFT
        rowPixels.copyInto(pixels, first)
        rowPixels.copyInto(pixels, first + WIDTH)
    }

    /** Dot [index] of 280 across the line; a delayed dot is drawn half a dot to the right. */
    private fun drawHiresDot(index: Int) {
        val colour = hiresColour(index)
        val shift = if (delayed[index]) 1 else 0
        val x = index * 2 + shift
        // A delayed dot leaves a gap behind it, which the dot before it runs into.
        if (shift == 1 && index % 7 == 0 && index > 0 && kinds[index / 7 - 1] == HIRES) rowPixels[x - 1] = hiresColour(index - 1)
        rowPixels[x] = colour
        if (x + 1 < DISPLAY_WIDTH) rowPixels[x + 1] = colour
    }

    private fun hiresColour(index: Int): Int {
        val left = index > 0 && dots[index - 1]
        val right = index < dots.size - 1 && dots[index + 1]
        if (monochrome) return if (dots[index]) PHOSPHOR else BLACK
        return when {
            dots[index] && (left || right) -> WHITE
            dots[index] -> artefact(index, delayed[index])
            // A dark dot between two lit ones is too narrow to show: the colour runs through.
            left && right -> artefact(index - 1, delayed[index])
            else -> BLACK
        }
    }

    private fun artefact(index: Int, delay: Boolean): Int = when {
        index and 1 == 0 -> if (delay) BLUE else VIOLET
        else -> if (delay) ORANGE else GREEN
    }

    /** Text page 1 as 24 lines of characters, without their inverse and flash attributes. */
    fun textLines(secondPage: Boolean = false): List<String> = (0 until 24).map { row ->
        val base = textAddress(row, secondPage)
        (0 until COLUMNS).map { column -> character(ram[base + column].toInt() and 0xFF) }.joinToString("").trimEnd()
    }

    companion object {
        const val WIDTH = 640
        const val HEIGHT = 480
        const val DISPLAY_WIDTH = 560
        const val DISPLAY_HEIGHT = 384
        const val LEFT = (WIDTH - DISPLAY_WIDTH) / 2
        const val TOP = (HEIGHT - DISPLAY_HEIGHT) / 2

        const val COLUMNS = 40
        const val DISPLAY_LINES = 192

        /** Where the text of mixed mode starts. */
        const val MIXED_LINE = 160

        /** Cycles of horizontal blanking at the start of each line, before the 40 displayed. */
        const val BLANK_CYCLES = Apple2Clock.TICKS_PER_LINE - COLUMNS

        /** Flashing characters change about every quarter of a second. */
        const val FLASH_FRAMES = 16

        private const val TEXT = 0
        private const val LORES = 1
        private const val HIRES = 2

        const val BLACK = 0x000000
        const val WHITE = 0xFFFFFF

        /** A green monitor's phosphor, for [monochrome]. */
        const val PHOSPHOR = 0x33FF66

        /** The 16 lo-res colours, by COLOR= number. */
        val PALETTE = intArrayOf(
            0x000000, 0xDD0033, 0x000099, 0xDD22DD, 0x007722, 0x555555, 0x2222FF, 0x66AAFF,
            0x885500, 0xFF6600, 0xAAAAAA, 0xFF9988, 0x11DD00, 0xFFFF00, 0x44FF99, 0xFFFFFF,
        )

        /** The hi-res colours are four of the lo-res ones. */
        val VIOLET = PALETTE[3]
        val GREEN = PALETTE[12]
        val BLUE = PALETTE[6]
        val ORANGE = PALETTE[9]

        /** The address of text or lo-res row 0-23. */
        fun textAddress(row: Int, page2: Boolean = false): Int =
            (if (page2) 0x0800 else 0x0400) + ((row and 7) shl 7) + (row shr 3) * 40

        /** The address of hi-res line 0-191. */
        fun hiresAddress(line: Int, page2: Boolean = false): Int =
            (if (page2) 0x4000 else 0x2000) + ((line and 7) shl 10) + (((line shr 3) and 7) shl 7) + (line shr 6) * 40

        /** The ASCII character a screen code shows, whether normal, inverse or flashing. */
        fun character(code: Int): Char {
            val ascii = if (code < 0x80) code and 0x3F else code and 0x7F
            return (if (ascii < 0x20) ascii + 0x40 else ascii).toChar()
        }
    }
}
