// SPDX-License-Identifier: BSD-3-Clause
package appleiislop.desktop

import appleiislop.apple2.*
import java.io.IOException
import java.nio.file.Files
import kotlin.test.*

class TapeTest {
    private fun frames(m: Apple2Machine, n: Int, text: String = "") {
        val typist = Typist(); typist.enqueue(text)
        repeat(n) { typist.onFrame(m); Emulator.runFrame(m) }
        assertFalse(typist.busy, "Unconsumed typing: ${m.textLines()}")
    }

    @Test fun realRomSaveWavLoadAndRun() {
        val roms = Roms.load(null, false)
        val writer = roms.newMachine()
        frames(writer, 120)
        frames(writer, 120, "10 PRINT 31415\n")
        writer.cassette.insert(Tape())
        writer.cassette.record()
        frames(writer, 2100, "SAVE\n")
        writer.cassette.stop()
        val tape = writer.cassette.tape!!
        assertTrue(tape.samples().any { it > 0 })
        assertTrue(tape.samples().any { it < 0 })
        val file = Files.createTempFile("apple-tape", ".wav").toFile()
        try {
            TapeFile.save(TapeFile.encode(tape), file)
            // Exercises the actual CLI/headless setup, not a direct memory injection.
            val loaded = runHeadless(roms, Options.parse(arrayOf("--no-disk", "--tape", file.path,
                "--type", "LOAD\\nRUN\\n", "--frames", "2400")))
            assertTrue(loaded.textLines().any { it.trim() == "31415" }, loaded.textLines().joinToString("\n"))
            assertFalse(loaded.cpu.halted)
        } finally { file.delete() }
    }

    @Test fun stereoEightBitAndPaddedUnknownChunks() {
        val raw = byteArrayOf(0, 0, 128.toByte(), 128.toByte(), 255.toByte(), 255.toByte())
        val out = java.io.ByteArrayOutputStream()
        val format = javax.sound.sampled.AudioFormat(22_050f, 8, 2, false, false)
        javax.sound.sampled.AudioInputStream(java.io.ByteArrayInputStream(raw), format, 3).use {
            javax.sound.sampled.AudioSystem.write(it, javax.sound.sampled.AudioFileFormat.Type.WAVE, out)
        }
        val wav = out.toByteArray()
        // Insert an odd-length ancillary chunk, including its RIFF pad byte.
        val padded = wav.copyOfRange(0, 12) + byteArrayOf(74,85,78,75,3,0,0,0,1,2,3,0) + wav.copyOfRange(12, wav.size)
        java.nio.ByteBuffer.wrap(padded).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(4, padded.size - 8)
        val tape = TapeFile.decode(padded)
        assertEquals(22_050, tape.sampleRate)
        assertContentEquals(shortArrayOf(-32768, 0, 32512), tape.samples())
    }

    @Test fun pcmWavRoundtripAndMalformedInputs() {
        val original = shortArrayOf(-32768,-123,0,123,32767)
        val wav = TapeFile.encode(Tape(48_000, original))
        val decoded = TapeFile.decode(wav)
        assertEquals(48_000, decoded.sampleRate)
        assertContentEquals(original, decoded.samples())
        for (n in listOf(0, 11, 20, wav.size - 1)) {
            assertFailsWith<IOException> { TapeFile.decode(wav.copyOf(n)) }
        }
        val compressed = wav.copyOf(); compressed[20] = 3
        assertFailsWith<IOException> { TapeFile.decode(compressed) }
    }
}
