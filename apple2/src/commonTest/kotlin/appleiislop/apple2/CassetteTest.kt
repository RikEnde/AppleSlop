// SPDX-License-Identifier: BSD-3-Clause
package appleiislop.apple2

import kotlin.test.*

class CassetteTest {
    @Test fun transportAndOverwrite() {
        val recorder = Cassette()
        val tape = Tape(8_000, shortArrayOf(100, -100, 100))
        recorder.insert(tape)
        assertTrue(recorder.input)
        recorder.play()
        assertFalse(recorder.input)
        repeat(128) { recorder.tick() }
        assertTrue(recorder.input)
        recorder.stop()
        val position = recorder.position
        repeat(1000) { recorder.tick() }
        assertEquals(position, recorder.position)
        recorder.rewind(); recorder.record()
        repeat(128) { recorder.tick() }
        recorder.stop()
        assertTrue(tape.sample(0) < 0)
        assertEquals((-100).toShort(), tape.sample(1)) // unwritten tail survives
        recorder.rewind(); recorder.play()
        repeat(1000) { recorder.tick() }
        assertEquals(Cassette.Mode.STOPPED, recorder.mode)
        assertEquals(tape.size, recorder.position)
        recorder.insert(null); recorder.record()
        assertEquals(Cassette.Mode.STOPPED, recorder.mode)
    }

    @Test fun cassetteIoAliasesAndPeekHaveCorrectSideEffects() {
        val rom = ByteArray(0x3000)
        val m = Apple2Machine(rom, ByteArray(0x800))
        val initial = m.cassette.output
        m.bus.peek(0xC020)
        assertEquals(initial, m.cassette.output)
        m.bus.read(0xC020)
        assertEquals(!initial, m.cassette.output)
        m.bus.write(0xC02F, 0)
        assertEquals(initial, m.cassette.output)
        m.cassette.insert(Tape(samples = shortArrayOf(100,100)))
        m.cassette.play()
        assertEquals(0, m.bus.read(0xC060) and 0x80)
        assertEquals(0, m.bus.read(0xC068) and 0x80)
        m.cassette.stop()
        assertEquals(0x80, m.bus.read(0xC060) and 0x80)
    }
}
