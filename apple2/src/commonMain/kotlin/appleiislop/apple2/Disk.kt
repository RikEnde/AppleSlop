// SPDX-FileCopyrightText: 2026 The Beebslop contributors
// SPDX-FileCopyrightText: 2026 The AppleIIslop contributors
// SPDX-License-Identifier: BSD-3-Clause

package appleiislop.apple2

/**
 * A 5.25-inch floppy disk as the Disk II sees it: 35 tracks, each a ring of
 * "nibbles", the bytes with their top bit set that the drive's electronics
 * shift in from the disk. This is the level the controller works at, so a disk
 * that DOS has formatted itself, or one with unusual address marks, behaves as
 * it would in a real drive. Flux, and the extra bits of self-sync bytes, are
 * not modelled.
 *
 * Disk images are usually sector dumps: 35 tracks of 16 sectors of 256 bytes,
 * in the order DOS 3.3 numbers the sectors (`.dsk`, `.do`) or the order ProDOS
 * numbers its blocks (`.po`). [parse] turns one into tracks, giving each sector
 * an address field and a data field in "6 and 2" encoding, as DOS 3.3's INIT
 * writes them (*Beneath Apple DOS*, chapter 3). [toImage] reads the sectors
 * back out of the nibbles. A `.nib` image holds the nibbles themselves, 6656 a
 * track, for [parseNibbles] and [toNibbles].
 */
class Disk private constructor(private val tracks: Array<ByteArray>, private val sectors: ByteArray?) {
    /** Set by the user; the drive's sensor reports it and writes do nothing. */
    var writeProtected: Boolean = false

    /** Counts written nibbles, so the host can tell when to save the image. */
    var changes: Long = 0
        private set

    /** The nibbles of track 0-34, in the order they pass the head. */
    fun track(track: Int): ByteArray = tracks[track]

    internal fun write(track: Int, position: Int, nibble: Int) {
        tracks[track][position] = nibble.toByte()
        changes++
    }

    /**
     * The disk as a sector image in [order]. A sector that can't be found on its
     * track, or doesn't decode, keeps the contents the disk was made with, or
     * zeros; [unreadableSectors] counts them.
     */
    fun toImage(order: SectorOrder): ByteArray {
        val image = ByteArray(IMAGE_SIZE)
        for (track in 0 until TRACKS) {
            val found = decodeTrack(tracks[track], track)
            for (physical in 0 until SECTORS) {
                val target = (track * SECTORS + order.logical[physical]) * SECTOR_SIZE
                val data = found[physical]
                if (data != null) {
                    data.copyInto(image, target)
                } else {
                    sectors?.copyInto(image, target, sectorIndex(track, physical), sectorIndex(track, physical) + SECTOR_SIZE)
                }
            }
        }
        return image
    }

    /** How many of the 560 sectors [toImage] can't read from the tracks. */
    fun unreadableSectors(): Int = (0 until TRACKS).sumOf { track -> decodeTrack(tracks[track], track).count { it == null } }

    /** The disk as a `.nib` image: each track's nibbles, cut or padded with sync bytes to 6656. */
    fun toNibbles(): ByteArray {
        val image = ByteArray(TRACKS * NIB_TRACK_SIZE) { SYNC }
        for (track in 0 until TRACKS) {
            tracks[track].copyInto(image, track * NIB_TRACK_SIZE, 0, minOf(tracks[track].size, NIB_TRACK_SIZE))
        }
        return image
    }

    /** The order of the sectors in a sector image. */
    enum class SectorOrder(internal val logical: IntArray) {
        /** `.dsk` and `.do`: by DOS 3.3 sector number. */
        DOS(intArrayOf(0, 7, 14, 6, 13, 5, 12, 4, 11, 3, 10, 2, 9, 1, 8, 15)),

        /** `.po`: by ProDOS block, two sectors to a block. */
        PRODOS(intArrayOf(0, 8, 1, 9, 2, 10, 3, 11, 4, 12, 5, 13, 6, 14, 7, 15)),
    }

    companion object {
        const val TRACKS = 35
        const val SECTORS = 16
        const val SECTOR_SIZE = 256
        const val IMAGE_SIZE = TRACKS * SECTORS * SECTOR_SIZE

        /** Nibbles in a track of a `.nib` image. */
        const val NIB_TRACK_SIZE = 6656

        /** The volume number DOS 3.3 gives a disk unless told otherwise. */
        const val DEFAULT_VOLUME = 254

        internal const val SYNC: Byte = 0xFF.toByte()

        /** Sync bytes before the first sector, between a sector's address and data, and after its data. */
        private const val GAP1 = 48
        private const val GAP2 = 6
        private const val GAP3 = 27

        /** The data field's nibbles: 342 of data and a checksum. */
        private const val DATA_NIBBLES = 343

        /** The 64 nibbles that stand for six bits: no more than one pair of 0 bits, and at least one pair of 1s. */
        private val ENCODE = intArrayOf(
            0x96, 0x97, 0x9A, 0x9B, 0x9D, 0x9E, 0x9F, 0xA6, 0xA7, 0xAB, 0xAC, 0xAD, 0xAE, 0xAF, 0xB2, 0xB3,
            0xB4, 0xB5, 0xB6, 0xB7, 0xB9, 0xBA, 0xBB, 0xBC, 0xBD, 0xBE, 0xBF, 0xCB, 0xCD, 0xCE, 0xCF, 0xD3,
            0xD6, 0xD7, 0xD9, 0xDA, 0xDB, 0xDC, 0xDD, 0xDE, 0xDF, 0xE5, 0xE6, 0xE7, 0xE9, 0xEA, 0xEB, 0xEC,
            0xED, 0xEE, 0xEF, 0xF2, 0xF3, 0xF4, 0xF5, 0xF6, 0xF7, 0xF9, 0xFA, 0xFB, 0xFC, 0xFD, 0xFE, 0xFF,
        )
        private val DECODE = IntArray(256) { -1 }.also { table -> ENCODE.forEachIndexed { value, nibble -> table[nibble] = value } }

        private fun sectorIndex(track: Int, physical: Int) = (track * SECTORS + physical) * SECTOR_SIZE

        /**
         * Reads a sector image of 143,360 bytes.
         * @throws IllegalArgumentException if it is any other size.
         */
        fun parse(image: ByteArray, order: SectorOrder, volume: Int = DEFAULT_VOLUME): Disk {
            require(image.size == IMAGE_SIZE) { "A disk image must be $IMAGE_SIZE bytes, not ${image.size}" }
            // Kept in the order the sectors lie on the track.
            val physical = ByteArray(IMAGE_SIZE)
            for (track in 0 until TRACKS) for (sector in 0 until SECTORS) {
                val source = (track * SECTORS + order.logical[sector]) * SECTOR_SIZE
                image.copyInto(physical, sectorIndex(track, sector), source, source + SECTOR_SIZE)
            }
            val tracks = Array(TRACKS) { track -> encodeTrack(physical, track, volume) }
            return Disk(tracks, physical)
        }

        /**
         * Reads a `.nib` image: 35 tracks of 6656 nibbles.
         * @throws IllegalArgumentException if it is any other size.
         */
        fun parseNibbles(image: ByteArray): Disk {
            require(image.size == TRACKS * NIB_TRACK_SIZE) { "A .nib image must be ${TRACKS * NIB_TRACK_SIZE} bytes, not ${image.size}" }
            return Disk(Array(TRACKS) { image.copyOfRange(it * NIB_TRACK_SIZE, (it + 1) * NIB_TRACK_SIZE) }, null)
        }

        /**
         * A disk with every sector laid out and full of zeros. It has no DOS on it
         * and no catalogue: `INIT HELLO` in DOS 3.3 makes it a disk that boots.
         */
        fun blank(): Disk = parse(ByteArray(IMAGE_SIZE), SectorOrder.DOS)

        private fun encodeTrack(physical: ByteArray, track: Int, volume: Int): ByteArray {
            val out = ArrayList<Byte>(NIB_TRACK_SIZE)
            fun put(vararg values: Int) = values.forEach { out += it.toByte() }
            fun sync(count: Int) = repeat(count) { out += SYNC }
            fun fourAndFour(value: Int) = put((value shr 1) or 0xAA, value or 0xAA)
            sync(GAP1)
            for (sector in 0 until SECTORS) {
                put(0xD5, 0xAA, 0x96)
                fourAndFour(volume)
                fourAndFour(track)
                fourAndFour(sector)
                fourAndFour(volume xor track xor sector)
                put(0xDE, 0xAA, 0xEB)
                sync(GAP2)
                put(0xD5, 0xAA, 0xAD)
                encodeData(physical, sectorIndex(track, sector)).forEach { put(it) }
                put(0xDE, 0xAA, 0xEB)
                sync(GAP3)
            }
            return out.toByteArray()
        }

        /**
         * 256 bytes as 343 nibbles. The low two bits of each byte, swapped, are
         * gathered three bytes to a six-bit value; those 86 go first, then the
         * 256 high six bits. Each value is exclusive-ORed with the one before
         * it, and the last one follows as the checksum.
         */
        private fun encodeData(source: ByteArray, start: Int): IntArray {
            fun byte(i: Int) = source[start + i].toInt() and 0xFF
            fun swapped(i: Int) = if (i < SECTOR_SIZE) ((byte(i) and 1) shl 1) or ((byte(i) and 2) shr 1) else 0
            val values = IntArray(342)
            for (i in 0 until 86) values[i] = swapped(i) or (swapped(i + 86) shl 2) or (swapped(i + 172) shl 4)
            for (i in 0 until SECTOR_SIZE) values[86 + i] = byte(i) shr 2
            val nibbles = IntArray(DATA_NIBBLES)
            var previous = 0
            for (i in values.indices) {
                nibbles[i] = ENCODE[values[i] xor previous]
                previous = values[i]
            }
            nibbles[342] = ENCODE[previous]
            return nibbles
        }

        /** The data of each physical sector found on a track with a good checksum, or null. */
        private fun decodeTrack(nibbles: ByteArray, track: Int): Array<ByteArray?> {
            val found = arrayOfNulls<ByteArray>(SECTORS)
            val length = nibbles.size
            if (length == 0) return found
            fun at(i: Int) = nibbles[i % length].toInt() and 0xFF
            fun fourAndFour(i: Int) = ((at(i) shl 1) or 1) and at(i + 1)
            var i = 0
            // Indexes wrap, so a sector that straddles the end of the array is read whole.
            while (i < length) {
                if (at(i) != 0xD5 || at(i + 1) != 0xAA || at(i + 2) != 0x96) {
                    i++
                    continue
                }
                val sector = fourAndFour(i + 7)
                val sameTrack = fourAndFour(i + 5) == track
                i += 11
                if (!sameTrack || sector !in 0 until SECTORS) continue
                // The data field follows within a few sync bytes, or not at all.
                var data = -1
                for (gap in 0 until 40) {
                    if (at(i + gap) == 0xD5 && at(i + gap + 1) == 0xAA) {
                        if (at(i + gap + 2) == 0xAD) data = i + gap + 3
                        break
                    }
                }
                if (data < 0) continue
                decodeData(::at, data)?.let { found[sector] = it }
                i = data + DATA_NIBBLES
            }
            return found
        }

        private fun decodeData(at: (Int) -> Int, start: Int): ByteArray? {
            val values = IntArray(342)
            var previous = 0
            for (i in 0 until 342) {
                val decoded = DECODE[at(start + i)]
                if (decoded < 0) return null
                previous = previous xor decoded
                values[i] = previous
            }
            if (DECODE[at(start + 342)] != previous) return null
            return ByteArray(SECTOR_SIZE) { i ->
                val pair = (values[i % 86] shr (2 * (i / 86))) and 3
                ((values[86 + i] shl 2) or ((pair and 1) shl 1) or (pair shr 1)).toByte()
            }
        }
    }
}
