// SPDX-FileCopyrightText: 2026 The AppleIIslop contributors
// SPDX-License-Identifier: BSD-3-Clause

package appleiislop.apple2

/**
 * Types text, like a very steady typist. Call [onFrame] once per frame (a 60th
 * of a second).
 *
 * The keyboard holds one key, so the typist waits for the program to take each
 * one, by clearing the strobe, before it presses the next. RETURN gets extra
 * time, so that a program which empties the keyboard when it starts doesn't
 * lose what follows. It also waits while a disk drive is turning: DOS doesn't
 * read the keyboard while it loads, and the monitor throws a waiting key away
 * when it starts.
 *
 * Adapted from Beebslop's `Typist`, which came from Elkslop's `TextTyper`.
 */
class Typist {
    private val pending = ArrayDeque<Int>()
    private var waitFrames = 0

    val busy: Boolean get() = pending.isNotEmpty()

    /** Queue [text]; characters the Apple II Plus cannot type are skipped and returned. */
    fun enqueue(text: String, delayFrames: Int = 0): Set<Char> {
        val skipped = mutableSetOf<Char>()
        val normalised = text.replace("\r\n", "\n").replace('\t', ' ')
        for (char in normalised) {
            val code = KeyboardText.forCharacter(char)
            if (code != null) pending += code else skipped += char
        }
        waitFrames = maxOf(waitFrames, delayFrames)
        return skipped
    }

    /** Queue a key by its code, such as a control character. */
    fun enqueueKey(code: Int) {
        pending += code and 0x7F
    }

    fun cancel() {
        pending.clear()
        waitFrames = 0
    }

    fun onFrame(machine: Apple2Machine) {
        if (pending.isEmpty()) return
        if (machine.disk?.spinning == true) {
            waitFrames = maxOf(waitFrames, DISK_FRAMES)
            return
        }
        if (waitFrames > 0) {
            waitFrames--
            return
        }
        if (machine.keyboard.strobe) return
        val code = pending.removeFirst()
        machine.keyboard.press(code)
        waitFrames = if (code == KeyboardText.RETURN) RETURN_FRAMES else KEY_FRAMES
    }

    private companion object {
        const val KEY_FRAMES = 1
        const val RETURN_FRAMES = 6
        const val DISK_FRAMES = 6
    }
}
