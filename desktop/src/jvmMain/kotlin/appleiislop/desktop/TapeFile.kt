// SPDX-FileCopyrightText: 2026 The AppleIIslop contributors
// SPDX-License-Identifier: BSD-3-Clause
package appleiislop.desktop

import appleiislop.apple2.Tape
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Uncompressed PCM WAV: 8/16 bit, mono/stereo. Stereo input is mixed to mono. */
object TapeFile {
    fun load(file: File): Tape = decode(file.readBytes())

    fun decode(bytes: ByteArray): Tape {
        fun invalid(): Nothing = throw IOException("Expected an uncompressed 8/16-bit mono/stereo PCM WAV tape")
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        fun tag(offset: Int) = String(bytes, offset, 4, Charsets.US_ASCII)
        if (bytes.size < 12 || tag(0) != "RIFF" || tag(8) != "WAVE") invalid()
        val end = 8L + (b.getInt(4).toLong() and 0xFFFFFFFFL)
        if (end > bytes.size || end < 12) invalid()
        var channels = 0; var rate = 0; var bits = 0; var block = 0
        var start = -1; var length = 0; var offset = 12
        while (offset.toLong() + 8 <= end) {
            val n = b.getInt(offset + 4).toLong() and 0xFFFFFFFFL
            val next = offset + 8L + n + (n and 1)
            if (next > end || n > Int.MAX_VALUE) invalid()
            when (tag(offset)) {
                "fmt " -> {
                    if (n < 16 || b.getShort(offset + 8).toInt() != 1) invalid()
                    channels = b.getShort(offset + 10).toInt()
                    rate = b.getInt(offset + 12)
                    block = b.getShort(offset + 20).toInt()
                    bits = b.getShort(offset + 22).toInt()
                }
                "data" -> { start = offset + 8; length = n.toInt() }
            }
            offset = next.toInt()
        }
        if (channels !in 1..2 || bits !in listOf(8,16) || rate !in 8_000..192_000 ||
            block != channels * bits / 8 || start < 0 || length % block != 0) invalid()
        val samples = ShortArray(length / block) { i ->
            var sum = 0
            for (channel in 0 until channels) {
                val at = start + i * block + channel * bits / 8
                sum += if (bits == 8) ((bytes[at].toInt() and 255) - 128) * 256 else b.getShort(at).toInt()
            }
            (sum / channels).toShort()
        }
        return Tape(rate, samples)
    }

    fun encode(tape: Tape): ByteArray {
        val samples = tape.samples()
        val b = ByteBuffer.allocate(44 + samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        fun tag(s: String) { b.put(s.toByteArray(Charsets.US_ASCII)) }
        tag("RIFF"); b.putInt(36 + samples.size * 2); tag("WAVE"); tag("fmt "); b.putInt(16)
        b.putShort(1); b.putShort(1); b.putInt(tape.sampleRate); b.putInt(tape.sampleRate * 2)
        b.putShort(2); b.putShort(16); tag("data"); b.putInt(samples.size * 2)
        samples.forEach { b.putShort(it) }
        return b.array()
    }

    /** Snapshot bytes are made on the emulation thread, then saved by the UI. */
    fun save(bytes: ByteArray, file: File) {
        val target = file.absoluteFile.toPath()
        Files.createDirectories(target.parent)
        val temporary = Files.createTempFile(target.parent, ".apple-tape-", ".tmp")
        try {
            Files.write(temporary, bytes)
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally { Files.deleteIfExists(temporary) }
    }
}
