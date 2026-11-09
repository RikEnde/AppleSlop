// SPDX-FileCopyrightText: 2026 The Beebslop contributors
// SPDX-FileCopyrightText: 2026 The AppleIIslop contributors
// SPDX-License-Identifier: BSD-3-Clause

package appleiislop.desktop

import appleiislop.apple2.Apple2Clock
import appleiislop.apple2.Apple2Machine
import appleiislop.apple2.Tape
import appleiislop.apple2.Disk
import appleiislop.apple2.KeyboardText
import appleiislop.apple2.Typist
import appleiislop.apple2.Video
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue

/** Adapted from Beebslop: one emulation thread, queued commands, locked frame publication,
 * audio-clock pacing, and automatic disk persistence. */
class Emulator(
    private val createMachine: () -> Apple2Machine,
    private val soundEnabled: Boolean,
    private val listener: Listener,
) {
    interface Listener {
        /** Called on the emulation thread after each completed field. */
        fun frameReady(frameCount: Long)

        /** Called on the emulation thread when the machine stops on an error. */
        fun stopped(error: Throwable)

        /** Called on the emulation thread when the disk drives start or stop. */
        fun diskMotorChanged(on: Boolean) {}

        /** Called when a changed disk couldn't be written back to [file]; it stays in, unsaved. */
        fun diskSaveFailed(drive: Int, file: File, error: IOException) {}
    }

    /** A disk in a drive, and the file it is saved to, if any. */
    private class Slot(val disk: Disk, var file: File?, var saved: Long)

    private val commands = ConcurrentLinkedQueue<(Apple2Machine) -> Unit>()
    private val frameLock = Any()
    private val published = IntArray(Video.WIDTH * Video.HEIGHT)
    private val typist = Typist()
    private var machine = createMachine()
    private var thread: Thread? = null
    private val slots = arrayOfNulls<Slot>(2)
    private var diskMotor = false

    @Volatile private var running = false
    @Volatile var paused = false
    @Volatile var volume = 1f
    @Volatile var frameCount = 0L
        private set

    fun start() {
        check(thread == null)
        running = true
        thread = Thread(::run, "apple2-emulation").apply {
            isDaemon = true
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    fun stop() {
        running = false
        thread?.join()
        thread = null
    }

    /** Run [command] on the emulation thread before the next field. */
    fun post(command: (Apple2Machine) -> Unit) { commands += command }

    fun press(code: Int) = post { it.keyboard.press(code) }
    fun releaseAll() = post { it.gameIo.buttons.fill(false) }
    fun pressBreak() = post { typist.cancel(); it.reset(); paused = false }

    /** Type [text]; characters the Apple II Plus cannot type are skipped and returned. */
    fun type(text: String, delayFrames: Int = 0): Set<Char> {
        val skipped = text.filter { it != '\t' && it != '\r' && !KeyboardText.canType(it) }.toSet()
        post { typist.enqueue(text, delayFrames) }
        return skipped
    }

    fun cancelTyping() = post { typist.cancel() }

    fun insertTape(tape: Tape?) = post { it.cassette.insert(tape) }
    fun playTape() = post { it.cassette.play() }
    fun recordTape() = post { it.cassette.record() }
    fun stopTape() = post { it.cassette.stop() }
    fun rewindTape() = post { it.cassette.rewind() }
    fun exportTape(receive: (ByteArray?) -> Unit) = post {
        it.cassette.stop()
        receive(it.cassette.tape?.let(TapeFile::encode))
    }
    @Volatile var tapeStatus = ""
        private set


    /** Whether the machine has a disk interface (the Disk II ROM was found). */
    val hasDisks: Boolean get() = machine.disk != null

    /** Put [disk] in drive 0 or 1; if [file] is given, changes are saved to it. */
    fun insertDisk(drive: Int, disk: Disk, file: File?) = post {
        if (!saveDisk(drive)) return@post
        slots[drive] = Slot(disk, file, disk.changes)
        it.disk?.drives?.get(drive)?.disk = disk
    }

    fun ejectDisk(drive: Int) = post {
        if (!saveDisk(drive)) return@post
        slots[drive] = null
        it.disk?.drives?.get(drive)?.disk = null
    }

    fun setWriteProtected(drive: Int, on: Boolean) = post { slots[drive]?.disk?.writeProtected = on }

    /**
     * Write the disk in [drive] to [file], and save later changes there too.
     * [done] gets null, or the error, on the emulation thread.
     */
    fun saveDiskAs(drive: Int, file: File, done: (IOException?) -> Unit) = post {
        val slot = slots[drive]
        if (slot == null) {
            done(IOException("There is no disk in drive $drive."))
            return@post
        }
        try {
            DiskFile.save(slot.disk, file)
            slot.file = file
            slot.saved = slot.disk.changes
            done(null)
        } catch (e: IOException) {
            done(e)
        }
    }

    /** Switch off and on with fresh RAM and chips; disks stay inserted. */
    fun powerCycle() = post {
        typist.cancel()
        val tape = machine.cassette.tape
        val monochrome = machine.video.monochrome
        machine = createMachine().also { fresh ->
            fresh.cassette.insert(tape)
            fresh.video.monochrome = monochrome
            for (drive in slots.indices) fresh.disk?.drives?.get(drive)?.disk = slots[drive]?.disk
        }
        paused = false
    }

    /** Writes a changed disk back to its file. */
    private fun saveDisk(drive: Int): Boolean {
        val slot = slots[drive] ?: return true
        val file = slot.file ?: return true
        if (slot.disk.changes == slot.saved) return true
        slot.saved = slot.disk.changes
        try {
            DiskFile.save(slot.disk, file)
        } catch (e: IOException) {
            // Don't keep failing: the disk stays in, and Save Disk As can still save it.
            slot.file = null
            listener.diskSaveFailed(drive, file, e)
            return false
        }
        return true
    }

    fun copyFrame(destination: IntArray) {
        synchronized(frameLock) { published.copyInto(destination) }
    }

    private fun run() {
        val audio = if (soundEnabled) AudioOutput.open(SAMPLE_RATE, SAMPLE_RATE / 15) else null
        try {
            runWith(audio)
        } finally {
            for (drive in slots.indices) saveDisk(drive)
            audio?.close()
        }
    }

    private fun runWith(audio: AudioOutput?) {
        val samples = FloatArray(SAMPLE_RATE / 5)
        var soundMachine: Apple2Machine? = null
        var paceStart = System.nanoTime()
        var paceTicks = 0L
        while (running) {
            while (true) commands.poll()?.invoke(machine) ?: break
            if (audio != null && machine !== soundMachine) {
                machine.speaker.startSampling(SAMPLE_RATE, samples.size)
                soundMachine = machine
            }
            if (paused) {
                Thread.sleep(10)
                paceStart = System.nanoTime()
                paceTicks = 0
                continue
            }
            val elapsed = try {
                runField()
            } catch (e: Exception) {
                paused = true
                listener.stopped(e)
                continue
            }
            if (audio != null) {
                audio.write(samples, soundMachine!!.speaker.drainSamples(samples), volume)
                continue
            }
            paceTicks += elapsed
            val ahead = paceStart + paceTicks * NANOS_PER_TICK - System.nanoTime()
            if (ahead > 0) {
                Thread.sleep(ahead / 1_000_000, (ahead % 1_000_000).toInt())
            } else if (ahead < -100_000_000) {
                // Fell far behind (host stall): don't try to catch up.
                paceStart = System.nanoTime()
                paceTicks = 0
            }
        }
    }

    /** Runs one frame and publishes the picture; returns the elapsed machine ticks. */
    private fun runField(): Long {
        val machine = machine
        typist.onFrame(machine)
        val elapsed = runFrame(machine)
        synchronized(frameLock) { machine.video.pixels.copyInto(published) }
        tapeStatus = if (machine.cassette.tape == null) "" else
            "${machine.cassette.mode} ${machine.cassette.seconds.toInt()}s"
        listener.frameReady(++frameCount)
        val disks = machine.disk?.spinning == true
        if (disks != diskMotor) {
            diskMotor = disks
            listener.diskMotorChanged(disks)
            // Save when the motor stops.
            if (!disks) for (drive in slots.indices) saveDisk(drive)
        }
        if (machine.cpu.halted) {
            // Like the real machine, a jammed CPU leaves the display running and
            // only RESET recovers it. Pause so the user knows why it froze.
            paused = true
            listener.stopped(IllegalStateException("The 6502 executed a JAM opcode and halted."))
        }
        return elapsed
    }

    companion object {
        const val SAMPLE_RATE = 48_000
        private const val NANOS_PER_TICK = 1_000_000_000L / Apple2Clock.TICKS_PER_SECOND

        fun runFrame(machine: Apple2Machine): Long {
            val start = machine.clock.ticks
            machine.runForTicks(Apple2Clock.TICKS_PER_FRAME.toLong())
            return machine.clock.ticks - start
        }
    }
}
