// SPDX-FileCopyrightText: 2026 The AppleIIslop contributors
// SPDX-License-Identifier: BSD-3-Clause

package appleiislop.desktop

import appleiislop.apple2.Apple2Machine
import appleiislop.apple2.Typist
import appleiislop.apple2.Video
import appleiislop.cpu.Disassembler
import java.awt.GraphicsEnvironment
import java.awt.image.BufferedImage
import java.io.File
import java.io.IOException
import javax.imageio.ImageIO
import javax.swing.JOptionPane
import javax.swing.SwingUtilities
import kotlin.system.exitProcess

/** Frames to wait after power-on before typing: the monitor takes under a second to start BASIC. */
const val BOOT_FRAMES = 60

/** How long a headless run waits, past its frames, for media to finish or text to be typed: two minutes. */
const val HEADLESS_LIMIT_FRAMES = 2 * 60 * 60

const val USAGE = """Usage: appleIIslop [options]
  --roms DIR          Directory with apple2plus.rom, video.rom and disk2.rom (default: built in)
  --scale 1|2         Window size; 1 is 640 x 480 (default: 1)
  --screenshot FILE   Run without a window, save a PNG of the picture, then exit
  --disassemble A-B   Run without a window, then print the machine code from hex address
                      A to B, as in --disassemble FA62-FA80; with --screenshot, the
                      picture comes from the same run
  --origin ADDR       Number the listing from hex ADDR, for code that is copied there to run
  --frames N          60ths of a second to run before the screenshot or listing (default: 120);
                      the run goes on while disk/tape playback or queued typing is active
  --tape FILE         Insert and play a PCM WAV cassette (use --no-disk for BASIC)
  --disk FILE         Put a disk image (.dsk, .do, .po or .nib) in drive 1; it boots at power-on
  --disk2 FILE        Put a disk image in drive 2
  --no-disk           Leave out the Disk II interface, so the machine starts in BASIC
  --type TEXT         Type TEXT once the machine has started; \n is RETURN
  --mono              Show the picture as a green monochrome monitor does
  --no-sound          Run without sound
  --log-keys          Print each host key event and the Apple II key code it maps to"""

data class Options(
    /** Null means the ROMs built into the jar. */
    val romDirectory: File? = null,
    val scale: Int = 1,
    val screenshot: File? = null,
    /** Frames to run before the screenshot or the listing. */
    val screenshotFrames: Int = 120,
    /** Addresses to disassemble after running headless, or null. */
    val disassemble: IntRange? = null,
    /** Where the code in [disassemble] runs from, if it is copied there first; null for where it is. */
    val origin: Int? = null,
    val logKeys: Boolean = false,
    val sound: Boolean = true,
    val disks: List<File?> = listOf(null, null),
    val diskInterface: Boolean = true,
    val typeText: String? = null,
    val monochrome: Boolean = false,
    val tape: File? = null,
) {
    companion object {
        fun parse(args: Array<String>): Options {
            var options = Options()
            var i = 0
            fun value(): String = args.getOrNull(++i) ?: throw IllegalArgumentException("${args[i - 1]} needs a value")
            while (i < args.size) {
                options = when (val arg = args[i]) {
                    "--roms" -> options.copy(romDirectory = File(value()))
                    "--scale" -> options.copy(scale = value().toIntOrNull()?.takeIf { it in 1..2 }
                        ?: throw IllegalArgumentException("--scale must be 1 or 2"))
                    "--screenshot" -> options.copy(screenshot = File(value()))
                    "--frames" -> options.copy(screenshotFrames = value().toIntOrNull()?.takeIf { it > 0 }
                        ?: throw IllegalArgumentException("--frames must be a positive number"))
                    "--log-keys" -> options.copy(logKeys = true)
                    "--no-sound" -> options.copy(sound = false)
                    "--mono" -> options.copy(monochrome = true)
                    "--tape" -> options.copy(tape = File(value()))
                    "--disk" -> options.copy(disks = listOf(File(value()), options.disks[1]))
                    "--disk2" -> options.copy(disks = listOf(options.disks[0], File(value())))
                    "--no-disk" -> options.copy(diskInterface = false)
                    "--type" -> options.copy(typeText = value().replace("\\n", "\n"))
                    "--disassemble" -> options.copy(disassemble = parseRange(value()))
                    "--origin" -> options.copy(origin = address(value())
                        ?: throw IllegalArgumentException("--origin needs a hex address, as in 0800"))
                    "--help", "-h" -> {
                        println(USAGE)
                        exitProcess(0)
                    }
                    else -> throw IllegalArgumentException("Unknown option: $arg")
                }
                i++
            }
            if (options.origin != null && options.disassemble == null) {
                throw IllegalArgumentException("--origin only goes with --disassemble")
            }
            if (!options.diskInterface && options.disks.any { it != null }) {
                throw IllegalArgumentException("--disk and --disk2 need the Disk II interface: leave out --no-disk")
            }
            return options
        }

        /** A hex address such as `FDED`, `$FDED`, `&FDED` or `0xFDED`, or null. */
        private fun address(text: String): Int? =
            text.trim().removePrefix("$").removePrefix("&").removePrefix("0x")
                .toIntOrNull(16)?.takeIf { it in 0..0xFFFF }

        /** `FA62-FA80`, or `$FA62-$FA80`, in hex; a single address gives one instruction. */
        private fun parseRange(text: String): IntRange {
            val parts = text.split("-")
            val start = address(parts[0])
            val end = if (parts.size == 2) address(parts[1]) else start.takeIf { parts.size == 1 }
            if (start == null || end == null || end < start) {
                throw IllegalArgumentException("--disassemble needs hex addresses START-END, as in FA62-FA80")
            }
            return start..end
        }
    }
}

/**
 * The ROMs (© Apple): the Apple II Plus's 12 KiB of Applesoft BASIC and
 * Autostart monitor, its character generator, and the Disk II interface's boot
 * ROM, which fits the interface if present. The build copies
 * roms/apple2plus.rom, roms/video.rom and roms/disk2.rom into the jar, so it
 * runs from anywhere; --roms DIR reads them from a directory instead.
 */
class Roms(val main: ByteArray, val video: ByteArray, val disk: ByteArray?) {
    fun newMachine() = Apple2Machine(main, video, disk)

    companion object {
        const val MAIN = "apple2plus.rom"
        const val VIDEO = "video.rom"
        const val DISK = "disk2.rom"
        private const val RESOURCES = "/appleiislop/roms/"

        /**
         * Loads the ROMs from [directory], or the built-in ones if it is null.
         * Without [diskInterface], or without disk2.rom, there is no Disk II.
         */
        fun load(directory: File?, diskInterface: Boolean = true): Roms {
            fun builtIn(name: String) = Roms::class.java.getResourceAsStream(RESOURCES + name)
            fun read(name: String, size: Int): ByteArray {
                val (where, bytes) = if (directory == null) {
                    val stream = builtIn(name) ?: throw IOException("The built-in $name is missing from the jar")
                    "Built-in $name" to stream.use { it.readBytes() }
                } else {
                    val file = File(directory, name)
                    if (!file.isFile) throw IOException("Missing ${file.absolutePath}")
                    file.absolutePath to file.readBytes()
                }
                if (bytes.size != size) throw IOException("$where must be $size bytes, was ${bytes.size}")
                return bytes
            }
            val hasDisk = if (directory == null) builtIn(DISK)?.use { true } == true else File(directory, DISK).isFile
            val disk = if (diskInterface && hasDisk) read(DISK, 0x100) else null
            return Roms(read(MAIN, 0x3000), read(VIDEO, 0x800), disk)
        }
    }
}

/** The directory the running jar is in; in the packaged app, AppleIIslop.app/Contents/app. */
fun jarDirectory(): File? =
    runCatching { File(Roms::class.java.protectionDomain.codeSource.location.toURI()).parentFile }.getOrNull()

/** [name] in the current directory if it is there, else [name] beside the jar, where the packaged app keeps its media. */
private fun mediaDirectory(name: String): File {
    val here = File(name)
    if (here.isDirectory) return here.absoluteFile
    val beside = jarDirectory()?.let { File(it, name) }
    return if (beside?.isDirectory == true) beside else here.absoluteFile
}

/** Where Insert Disk starts browsing. */
fun defaultDiskDirectory(): File = mediaDirectory("disks")
fun defaultTapeDirectory(): File = mediaDirectory("tapes")

/** Where Save As starts: [directory], unless it is inside the app; then ~/Documents, or home. */
fun saveDirectory(directory: File): File {
    if (!isBundled(directory)) return directory
    val home = File(System.getProperty("user.home"))
    return File(home, "Documents").takeIf { it.isDirectory } ?: home
}

/**
 * True if [file] is inside the jar's directory, which only holds media in the
 * packaged app. Writing to a disk there would change the signed bundle (macOS
 * then calls the app damaged), and fails anyway in /Applications, so such
 * disks are inserted without a backing file.
 */
fun isBundled(file: File): Boolean {
    val app = jarDirectory()?.canonicalFile ?: return false
    return generateSequence(file.canonicalFile) { it.parentFile }.any { it == app }
}

/**
 * Builds a machine and runs [Options.screenshotFrames] frames (60ths of a
 * second) as fast as possible, with the disks and text from [options]. It runs
 * on while a disk in drive 1 is turning, a tape plays, or text is being typed, for up to
 * [HEADLESS_LIMIT_FRAMES] more. Disk changes are not saved.
 */
fun runHeadless(roms: Roms, options: Options): Apple2Machine {
    val machine = roms.newMachine()
    machine.video.monochrome = options.monochrome
    for ((drive, file) in options.disks.withIndex()) {
        if (file != null) machine.disk?.drives?.get(drive)?.disk = DiskFile.load(file)
    }
    options.tape?.let { machine.cassette.insert(TapeFile.load(it)); machine.cassette.play() }
    val typist = Typist()
    options.typeText?.let { typist.enqueue(it, delayFrames = BOOT_FRAMES) }
    // With no disk in drive 1 the boot ROM turns the drive for ever, looking for one.
    fun loading() = machine.disk?.let { it.spinning && it.drives[0].disk != null } == true
    var frames = 0
    while (frames < options.screenshotFrames ||
        ((typist.busy || loading() || machine.cassette.mode == appleiislop.apple2.Cassette.Mode.PLAYING) && frames < options.screenshotFrames + HEADLESS_LIMIT_FRAMES)
    ) {
        typist.onFrame(machine)
        Emulator.runFrame(machine)
        frames++
    }
    return machine
}

/**
 * A listing of [range] as the CPU sees memory after [runHeadless]: RAM, ROM or
 * the Language Card, as it is switched then. Reading for it changes nothing.
 * The listing numbers the code from [origin], where it will run once copied.
 */
fun disassemble(machine: Apple2Machine, range: IntRange, origin: Int = range.first): String =
    Disassembler(machine.bus::peek).listing(range.first, range.last, origin)

/** Writes [machine]'s picture to [file] as a PNG, as the window shows it at scale 1. */
fun saveScreenshot(machine: Apple2Machine, file: File) {
    val view = ScreenPanel.VIEW
    val image = BufferedImage(view.width, view.height, BufferedImage.TYPE_INT_RGB)
    image.setRGB(0, 0, view.width, view.height, machine.video.pixels, view.y * Video.WIDTH + view.x, Video.WIDTH)
    file.absoluteFile.parentFile?.mkdirs()
    ImageIO.write(image, "png", file)
}

fun main(args: Array<String>) {
    System.setProperty("apple.awt.application.name", "AppleIIslop")
    System.setProperty("apple.laf.useScreenMenuBar", "true")

    val options = try {
        Options.parse(args)
    } catch (e: IllegalArgumentException) {
        System.err.println(e.message)
        System.err.println(USAGE)
        exitProcess(2)
    }
    val roms = try {
        Roms.load(options.romDirectory, options.diskInterface)
    } catch (e: IOException) {
        val message = if (options.romDirectory == null) {
            "${e.message}\n\nThis build is incomplete. Pass --roms DIR with the Apple II Plus apple2plus.rom (12 KiB), " +
                "video.rom (2 KiB) and, for disks, disk2.rom (256 bytes)."
        } else {
            "${e.message}\n\nPut the Apple II Plus apple2plus.rom (12 KiB) and video.rom (2 KiB) in that directory, " +
                "and disk2.rom (256 bytes) for disks, or leave out --roms to use the built-in ones."
        }
        System.err.println(message)
        if (options.screenshot == null && options.disassemble == null && !GraphicsEnvironment.isHeadless()) {
            JOptionPane.showMessageDialog(null, message, "AppleIIslop", JOptionPane.ERROR_MESSAGE)
        }
        exitProcess(1)
    }
    if (roms.disk == null && options.disks.any { it != null }) {
        val where = options.romDirectory?.let { "put disk2.rom in ${it.path}" } ?: "this build has no disk2.rom"
        System.err.println("--disk needs the Disk II interface: $where.")
        exitProcess(2)
    }
    if (options.screenshot != null || options.disassemble != null) {
        val machine = try {
            runHeadless(roms, options)
        } catch (e: IOException) {
            System.err.println("Couldn't load media: ${e.message}")
            exitProcess(1)
        }
        options.disassemble?.let { println(disassemble(machine, it, options.origin ?: it.first)) }
        options.screenshot?.let {
            saveScreenshot(machine, it)
            println("Saved ${it.path}")
        }
        return
    }
    SwingUtilities.invokeLater { EmulatorWindow(roms, options).show() }
}
