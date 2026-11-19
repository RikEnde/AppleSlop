// SPDX-FileCopyrightText: 2026 The AppleIIslop contributors
// SPDX-License-Identifier: BSD-3-Clause

package appleiislop.desktop

import appleiislop.cpu.Debugger
import appleiislop.debugger.DebugSession
import appleiislop.debugger.DebugSnapshot
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.*

class DebuggerIntegrationTest {
    /** INX / JSR $D010 / JMP $D000, with INY / RTS at $D010. */
    private fun roms(): Roms {
        val main = ByteArray(0x3000) { 0xEA.toByte() }
        listOf(0xE8, 0x20, 0x10, 0xD0, 0x4C, 0x00, 0xD0).forEachIndexed { i, b -> main[i] = b.toByte() }
        main[0x10] = 0xC8.toByte()
        main[0x11] = 0x60
        main[0x2FFC] = 0
        main[0x2FFD] = 0xD0.toByte()
        return Roms(main, ByteArray(0x800), null)
    }

    @Test fun headlessBreakpointsStopBeforeExecutingAndRespectTheFrameLimit() {
        val options = Options.parse(arrayOf("--no-disk", "--frames", "1", "--break", "D010", "--break", "0xD004"))
        assertEquals(listOf(0xD010, 0xD004), options.breakpoints)
        val run = runHeadless(roms(), options)
        assertEquals(0xD010, run.breakpoint)
        assertEquals(0xD010, run.machine.cpu.programCounter)
        assertEquals(0, run.machine.cpu.y)
        val initial = runHeadless(roms(), options.copy(breakpoints = listOf(0xD000)))
        assertEquals(0xD000, initial.breakpoint)
        assertEquals(0, initial.machine.cpu.x)
        val missed = runHeadless(roms(), options.copy(breakpoints = listOf(0x1234)))
        assertNull(missed.breakpoint)
        val reference = roms().newMachine()
        Emulator.runFrame(reference)
        assertEquals(reference.clock.ticks, missed.machine.clock.ticks)
        assertEquals(reference.cpu.state(), missed.machine.cpu.state())
        for (value in listOf("-1", "10000", "nope")) {
            assertFailsWith<IllegalArgumentException> { Options.parse(arrayOf("--break", value)) }
        }
        assertFailsWith<IllegalArgumentException> { Options.parse(arrayOf("--break")) }
    }

    @Test fun realRomBootStopsAtMonitorOutput() {
        val run = runHeadless(Roms.load(null, false), Options(diskInterface = false, breakpoints = listOf(0xFDED)))
        assertEquals(0xFDED, run.breakpoint)
        assertEquals(0xFDED, run.machine.cpu.programCounter)
        assertFalse(run.machine.cpu.halted)
    }

    @Test fun steppingPreservesTheRestOfTheFrame() {
        val machine = roms().newMachine()
        val reference = roms().newMachine()
        val frame = Frame(machine.clock.ticks)
        val debugger = Debugger(machine.bus::peek)
        repeat(20) {
            debugger.stepInto()
            assertFalse(Emulator.runFrame(machine, frame,
                { debugger.shouldStop(machine.cpu) }, { debugger.stepped(machine.cpu) }))
            reference.step()
            assertEquals(reference.cpu.state(), machine.cpu.state())
            assertEquals(reference.clock.ticks, machine.clock.ticks)
        }
        assertTrue(Emulator.runFrame(machine, frame))
        val uninterrupted = roms().newMachine()
        Emulator.runFrame(uninterrupted)
        assertEquals(uninterrupted.clock.ticks, machine.clock.ticks)
        assertEquals(uninterrupted.cpu.state(), machine.cpu.state())
        assertContentEquals(uninterrupted.video.pixels, machine.video.pixels)
    }

    @Test fun snapshotReadsTheSelectedBankWithoutTouchingHardware() {
        val machine = Roms.load(null).newMachine()
        machine.keyboard.press(65)
        machine.languageCard.access(0x0B, false)
        machine.languageCard.access(0x0B, false)
        machine.languageCard.write(0xD000, 0x42)
        machine.video.text = false
        val ticks = machine.clock.ticks
        val session = DebugSession(machine.bus::peek)
        var snapshot: DebugSnapshot? = null
        session.target({ it() }, { machine.cpu }, {}).watch { snapshot = it }
        session.tick(machine.cpu, true, 0)
        assertEquals(0x42, assertNotNull(snapshot).peek(0xD000))
        assertEquals(0xC1, snapshot!!.peek(0xC000))
        assertTrue(machine.keyboard.strobe)
        assertEquals(0L, machine.speaker.toggles)
        assertFalse(machine.cassette.output)
        assertFalse(machine.video.text)
        assertFalse(machine.video.page2)
        assertFalse(machine.video.hires)
        assertTrue(machine.languageCard.readRam)
        assertTrue(machine.languageCard.writeEnabled)
        assertEquals(1, machine.languageCard.bank)
        assertFalse(machine.disk!!.motorOn)
        assertEquals(0, machine.disk!!.selected)
        assertEquals(ticks, machine.clock.ticks)
    }

    @Test fun workerCommandsStepAndRetainBreakpointsAcrossPowerCycles() {
        val snapshots = LinkedBlockingQueue<DebugSnapshot>()
        val errors = LinkedBlockingQueue<Throwable>()
        val emulator = Emulator(roms()::newMachine, false, object : Emulator.Listener {
            override fun frameReady(frameCount: Long) {}
            override fun stopped(error: Throwable) { errors.add(error) }
        })
        fun stoppedAt(pc: Int): DebugSnapshot {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (true) {
                val snapshot = snapshots.poll(maxOf(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)
                    ?: fail("No paused snapshot at ${pc.toString(16)}; errors: $errors")
                if (snapshot.paused && snapshot.state.programCounter == pc) return snapshot
            }
        }
        emulator.paused = true
        emulator.debugTarget.watch { snapshots.add(it) }
        emulator.setBreakpoint(0xD001, true)
        emulator.start()
        try {
            stoppedAt(0xD000)
            emulator.debugTarget.debugContinue()
            val first = stoppedAt(0xD001)
            assertEquals(1, first.state.x)
            assertEquals(0L, first.frame)
            emulator.debugTarget.debugStepOver()
            val over = stoppedAt(0xD004)
            assertEquals(1, over.state.y)
            assertEquals(first.state.stackPointer, over.state.stackPointer)
            emulator.debugTarget.debugStepInto()
            stoppedAt(0xD000)
            emulator.setBreakpoint(0xD001, false)
            emulator.debugTarget.debugRunTo(0xD010)
            stoppedAt(0xD010)
            emulator.debugTarget.debugStepOut()
            assertEquals(2, stoppedAt(0xD004).state.y)
            emulator.setBreakpoint(0xD001, true)
            emulator.powerCycle()
            val fresh = stoppedAt(0xD001)
            assertEquals(1, fresh.state.x)
            assertEquals(0, fresh.state.y)
            assertEquals(first.cycles, fresh.cycles)
            assertEquals(listOf(0xD001), fresh.breakpoints)
            assertTrue(errors.isEmpty())
        } finally {
            emulator.stop()
        }
    }
}
