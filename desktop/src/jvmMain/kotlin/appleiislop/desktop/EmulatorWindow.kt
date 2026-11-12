// SPDX-FileCopyrightText: 2026 The AppleIIslop contributors
// SPDX-License-Identifier: BSD-3-Clause

package appleiislop.desktop

import appleiislop.apple2.Tape
import appleiislop.apple2.Disk
import appleiislop.apple2.KeyboardText
import java.awt.BorderLayout
import java.awt.FileDialog
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.event.ActionEvent
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.InputEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.io.File
import java.io.IOException
import javax.swing.AbstractAction
import javax.swing.ButtonGroup
import javax.swing.JCheckBoxMenuItem
import javax.swing.JFrame
import javax.swing.JMenu
import javax.swing.JMenuBar
import javax.swing.JMenuItem
import javax.swing.JOptionPane
import javax.swing.JRadioButtonMenuItem
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import javax.swing.WindowConstants
import kotlin.system.exitProcess

/** The emulator window: the picture, the keyboard and the menus. */
class EmulatorWindow(roms: Roms, private val options: Options) : Emulator.Listener {
    private val emulator = Emulator(roms::newMachine, options.sound, this)
    private val frame = JFrame()
    private val screen = ScreenPanel(emulator)
    private val isMac = System.getProperty("os.name").startsWith("Mac")
    private val pauseItem = JCheckBoxMenuItem("Pause")
    private var tapeName: String? = null
    private var displayedTapeStatus = ""
    private val diskNames = arrayOfNulls<String>(2)
    private val writeProtectItems = Array(2) { JCheckBoxMenuItem("Write Protect") }
    @Volatile private var diskMotor = false

    fun show() {
        frame.defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE
        frame.addWindowListener(object : WindowAdapter() {
            override fun windowClosed(e: WindowEvent) {
                emulator.stop()
                exitProcess(0)
            }
        })
        AppIcon.install(frame)
        frame.jMenuBar = createMenuBar()
        frame.contentPane.add(screen, BorderLayout.CENTER)
        screen.scale = options.scale
        screen.addKeyListener(keyListener)
        screen.addFocusListener(object : FocusAdapter() {
            // Keys released while another window has focus never reach us.
            override fun focusLost(e: FocusEvent) = emulator.releaseAll()
        })
        updateTitle()
        frame.pack()
        frame.setLocationRelativeTo(null)
        frame.isVisible = true
        screen.requestFocusInWindow()
        for ((drive, file) in options.disks.withIndex()) if (file != null) insertDisk(drive, file)
        options.tape?.let { if (insertTape(it)) emulator.playTape() }
        options.typeText?.let { emulator.type(it, delayFrames = BOOT_FRAMES) }
        emulator.post { it.video.monochrome = options.monochrome }
        emulator.start()
    }

    override fun frameReady(frameCount: Long) {
        screen.repaint()
        val status = emulator.tapeStatus
        if (status != displayedTapeStatus) {
            displayedTapeStatus = status
            SwingUtilities.invokeLater { updateTitle() }
        }
    }

    override fun stopped(error: Throwable) {
        SwingUtilities.invokeLater {
            pauseItem.isSelected = true
            updateTitle()
            JOptionPane.showMessageDialog(
                frame,
                "The emulated machine stopped:\n${error.message ?: error}\n\n" +
                    "Use Machine > Power Cycle to restart, or RESET (F12) to reset.",
                "AppleIIslop",
                JOptionPane.ERROR_MESSAGE,
            )
        }
    }

    override fun diskMotorChanged(on: Boolean) {
        diskMotor = on
        SwingUtilities.invokeLater { updateTitle() }
    }

    override fun diskSaveFailed(drive: Int, file: File, error: IOException) {
        SwingUtilities.invokeLater {
            JOptionPane.showMessageDialog(
                frame,
                "Couldn't save drive $drive's disk to ${file.name}:\n${error.message ?: error}\n\n" +
                    "Changes are no longer saved automatically. Use Disk > Drive $drive > Save Disk As to keep them.",
                "AppleIIslop",
                JOptionPane.ERROR_MESSAGE,
            )
        }
    }

    private val keyListener = object : KeyAdapter() {
        override fun keyPressed(e: KeyEvent) {
            if (e.isMetaDown || (e.isControlDown && e.isShiftDown)) return
            if (e.keyCode == KeyEvent.VK_F12) { resume(); emulator.pressBreak(); e.consume(); return }
            val code = when (e.keyCode) {
                KeyEvent.VK_ENTER -> KeyboardText.RETURN
                KeyEvent.VK_ESCAPE -> KeyboardText.ESCAPE
                KeyEvent.VK_LEFT, KeyEvent.VK_BACK_SPACE, KeyEvent.VK_DELETE -> KeyboardText.LEFT_ARROW
                KeyEvent.VK_RIGHT -> KeyboardText.RIGHT_ARROW
                else -> if (e.isControlDown) KeyboardText.control(e.keyChar) else null
            }
            if (code != null) { emulator.press(code); e.consume() }
            if (options.logKeys) println("pressed ${e.keyCode} -> $code")
        }
        override fun keyTyped(e: KeyEvent) {
            if (e.isMetaDown || e.isControlDown || e.isAltDown) return
            KeyboardText.forCharacter(e.keyChar)?.takeIf { it >= 32 }?.let(emulator::press)
            e.consume()
        }
    }

    private fun createMenuBar() = JMenuBar().apply {
        add(JMenu("Machine").apply {
            pauseItem.accelerator = shortcut(KeyEvent.VK_P)
            pauseItem.addActionListener {
                emulator.paused = pauseItem.isSelected
                updateTitle()
            }
            add(pauseItem)
            add(item("Power Cycle", null) {
                resume()
                emulator.powerCycle()
            })
            addSeparator()
            add(JCheckBoxMenuItem("Sound", options.sound).apply {
                isEnabled = options.sound
                addActionListener { emulator.volume = if (isSelected) 1f else 0f }
            })
            if (!isMac) {
                addSeparator()
                add(item("Quit", null) { frame.dispose() })
            }
        })
        add(JMenu("Edit").apply {
            add(item("Paste as Typing", shortcut(KeyEvent.VK_V)) { pasteClipboard() })
            add(item("Stop Typing", null) { emulator.cancelTyping() })
        })
        add(createKeysMenu())
        add(JMenu("Tape").apply {
            add(item("Insert Tape...", shortcut(KeyEvent.VK_O)) { chooseTape() })
            add(item("New Blank Tape", null) {
                emulator.insertTape(Tape()); tapeName = "blank tape"; updateTitle()
            })
            add(item("Save Tape As...", shortcut(KeyEvent.VK_S)) { saveTape() })
            addSeparator()
            add(item("Play", null) { emulator.playTape(); screen.requestFocusInWindow() })
            add(item("Record", null) { emulator.recordTape(); screen.requestFocusInWindow() })
            add(item("Stop", null) { emulator.stopTape() })
            add(item("Rewind", null) { emulator.rewindTape() })
            add(item("Eject", null) { emulator.insertTape(null); tapeName = null; updateTitle() })
        })
        add(JMenu("Disk").apply {
            if (!emulator.hasDisks) {
                isEnabled = false
                toolTipText = "No disk interface: put disk2.rom in the ROM directory"
            }
            for (drive in 0..1) add(createDriveMenu(drive))
        })
        add(JMenu("View").apply {
            add(JCheckBoxMenuItem("Green Monitor", options.monochrome).apply {
                addActionListener { val mono = isSelected; emulator.post { it.video.monochrome = mono } }
            })
            val group = ButtonGroup()
            for (scale in 1..2) {
                val choice = JRadioButtonMenuItem("${scale}x", scale == options.scale)
                choice.accelerator = shortcut(KeyEvent.VK_0 + scale)
                choice.addActionListener {
                    screen.scale = scale
                    frame.pack()
                }
                group.add(choice)
                add(choice)
            }
        })
        add(JMenu("Help").apply {
            add(item("Keyboard", null) { showKeyboardHelp() })
        })
    }

    /** One of the two Disk II drives. */
    private fun createDriveMenu(drive: Int) = JMenu("Drive ${drive + 1}").apply {
        add(item("Insert Disk...", if (drive == 0) shortcut(KeyEvent.VK_D) else null) { chooseDisk(drive) })
        add(item("New Blank Disk", null) { insertBlank(drive) })
        add(item("Save Disk As...", null) { saveDisk(drive) })
        addSeparator()
        add(writeProtectItems[drive].apply {
            toolTipText = "Writes fail with \"Disk read only\", like a disk with its notch covered"
            addActionListener { emulator.setWriteProtected(drive, isSelected) }
        })
        add(item("Eject", null) {
            emulator.ejectDisk(drive)
            diskNames[drive] = null
            writeProtectItems[drive].isSelected = false
            updateTitle()
        })
    }

    private fun createKeysMenu() = JMenu("Keys").apply {
        add(item("RESET (F12)", null) { resume(); emulator.pressBreak() })
        add(item("ESCAPE", null) { emulator.press(KeyboardText.ESCAPE) })
        add(item("CTRL+C", null) { emulator.press(3) })
    }

    private fun item(title: String, key: KeyStroke?, action: () -> Unit) =
        JMenuItem(object : AbstractAction(title) {
            override fun actionPerformed(e: ActionEvent) = action()
        }).apply { accelerator = key }

    /** Command on macOS. Ctrl+Shift elsewhere, so plain Ctrl stays the Apple II's. */
    private fun shortcut(keyCode: Int): KeyStroke = KeyStroke.getKeyStroke(
        keyCode,
        if (isMac) InputEvent.META_DOWN_MASK else InputEvent.CTRL_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK,
    )

    private fun resume() {
        emulator.paused = false
        pauseItem.isSelected = false
        updateTitle()
    }

    private fun updateTitle() {
        frame.title = "AppleIIslop - Apple II Plus" +
            diskNames.withIndex().joinToString("") { (drive, name) -> name?.let { "  [${drive + 1}: $it]" } ?: "" } +
            (tapeName?.let { "  [$it: ${emulator.tapeStatus}]" } ?: "") +
            (if (diskMotor) "  [DISK]" else "") +
            (if (emulator.paused) "  (paused)" else "")
    }

    private fun chooseTape() {
        val dialog = FileDialog(frame, "Insert WAV Tape", FileDialog.LOAD)
        dialog.directory = defaultTapeDirectory().path
        dialog.isVisible = true
        dialog.file?.let { insertTape(File(dialog.directory, it)) }
        screen.requestFocusInWindow()
    }

    private fun insertTape(file: File): Boolean = try {
        emulator.insertTape(TapeFile.load(file))
        tapeName = file.name
        updateTitle()
        true
    } catch (e: IOException) {
        JOptionPane.showMessageDialog(frame, e.message, "Insert Tape", JOptionPane.ERROR_MESSAGE)
        false
    }

    private fun saveTape() {
        val dialog = FileDialog(frame, "Save Tape As WAV", FileDialog.SAVE)
        dialog.directory = saveDirectory(defaultTapeDirectory()).path
        dialog.file = tapeName?.takeIf { it.endsWith(".wav", true) } ?: "tape.wav"
        dialog.isVisible = true
        val name = dialog.file ?: return
        val file = File(dialog.directory, if (name.endsWith(".wav", true)) name else "$name.wav")
        if (isBundled(file)) {
            JOptionPane.showMessageDialog(frame, "Choose a folder outside the application.", "Save Tape", JOptionPane.ERROR_MESSAGE)
            return
        }
        emulator.exportTape { bytes ->
            SwingUtilities.invokeLater {
                try {
                    if (bytes == null) throw IOException("No tape is inserted.")
                    TapeFile.save(bytes, file)
                    tapeName = file.name
                    updateTitle()
                } catch (e: IOException) {
                    JOptionPane.showMessageDialog(frame, e.message, "Save Tape", JOptionPane.ERROR_MESSAGE)
                }
            }
        }
        screen.requestFocusInWindow()
    }

    private fun chooseDisk(drive: Int) {
        val dialog = FileDialog(frame, "Insert Disk in Drive $drive", FileDialog.LOAD)
        dialog.directory = defaultDiskDirectory().path
        dialog.isVisible = true
        val name = dialog.file
        if (name != null) insertDisk(drive, File(dialog.directory, name))
        screen.requestFocusInWindow()
    }

    private fun insertDisk(drive: Int, file: File) {
        try {
            val disk = DiskFile.load(file)
            // A disk inside the app keeps its changes in memory until Save Disk As.
            emulator.insertDisk(drive, disk, file.takeUnless(::isBundled))
            diskNames[drive] = file.name
            writeProtectItems[drive].isSelected = disk.writeProtected
            updateTitle()
        } catch (e: IOException) {
            JOptionPane.showMessageDialog(frame, "Couldn't load ${file.name}:\n${e.message ?: e}", "Insert Disk", JOptionPane.ERROR_MESSAGE)
        }
    }

    private fun insertBlank(drive: Int) {
        emulator.insertDisk(drive, Disk.blank(), null)
        diskNames[drive] = "blank disk (use INIT in DOS)"
        writeProtectItems[drive].isSelected = false
        updateTitle()
    }

    /** Writes the disk to a file, which then keeps receiving its changes. */
    private fun saveDisk(drive: Int) {
        val dialog = FileDialog(frame, "Save Drive $drive's Disk As", FileDialog.SAVE)
        dialog.directory = saveDirectory(defaultDiskDirectory()).path
        dialog.file = diskNames[drive]?.takeIf { it.endsWith(".dsk") || it.endsWith(".po") || it.endsWith(".nib") } ?: "disk.dsk"
        dialog.isVisible = true
        val name = dialog.file
        if (name == null) {
            screen.requestFocusInWindow()
            return
        }
        val chosen = File(dialog.directory, name)
        if (isBundled(chosen)) {
            JOptionPane.showMessageDialog(frame, "Disks can't be saved inside the app. Choose another folder.", "Save Disk", JOptionPane.ERROR_MESSAGE)
            screen.requestFocusInWindow()
            return
        }
        emulator.saveDiskAs(drive, chosen) { error ->
            SwingUtilities.invokeLater {
                if (error != null) {
                    JOptionPane.showMessageDialog(frame, "Couldn't save ${chosen.name}:\n${error.message ?: error}", "Save Disk", JOptionPane.ERROR_MESSAGE)
                } else {
                    diskNames[drive] = chosen.name
                    updateTitle()
                }
            }
        }
        screen.requestFocusInWindow()
    }

    private fun pasteClipboard() {
        val text = try {
            Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor) as String
        } catch (e: Exception) {
            return
        }
        resume()
        val skipped = emulator.type(text)
        if (skipped.isNotEmpty()) {
            JOptionPane.showMessageDialog(
                frame,
                "The Apple II Plus keyboard can't type these characters, so they were skipped:\n" +
                    skipped.joinToString(" ") { if (it.isWhitespace()) "U+%04X".format(it.code) else it.toString() },
                "Paste as Typing",
                JOptionPane.WARNING_MESSAGE,
            )
        }
    }

    private fun showKeyboardHelp() {
        JOptionPane.showMessageDialog(
            frame,
            """
            Letters type in uppercase, as on an Apple II Plus.
            RETURN: Enter    RESET: F12    ESC: Escape
            Left arrow / Backspace: erase    Right arrow: copy
            CTRL+letter sends a control code. Edit > Paste as Typing pastes text.

            Tapes: start with --no-disk to use BASIC's cassette commands.
            Insert a WAV, type LOAD and Return, then Tape > Play. After loading, RUN.
            To save: New Blank Tape, Record, then type SAVE and Return.
            When the BASIC prompt returns: Stop, then Save Tape As.
            Rewind stops the tape; select Play to replay. Save before ejecting,
            replacing a tape or closing the window; recordings are kept in memory.

            With no disk inserted, the Disk II boot ROM waits for a disk.
            Press F12 to enter BASIC, or start with --no-disk.
            Insert a boot disk in drive 1 and Power Cycle to boot it.
            DOS 3.3: CATALOG lists files; RUN HELLO runs the welcome program.
            Disk changes are saved back to their image when the motor stops.
            Bundled disks keep changes in memory; Save Disk As keeps a copy.
            """.trimIndent(),
            "Keyboard",
            JOptionPane.PLAIN_MESSAGE,
        )
    }
}
