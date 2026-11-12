// SPDX-FileCopyrightText: 2026 The AppleIIslop contributors
// SPDX-License-Identifier: BSD-3-Clause

package appleiislop.apple2

/**
 * Which key code types a character on the Apple II Plus keyboard.
 *
 * The keyboard makes ASCII `$20-$5A` and, with SHIFT on M, N and P, the codes
 * for `]`, `^` and `@`. It has no lower case, and no keys for `[`, `\` and `_`.
 * Lower-case letters are typed as capitals, as if on the real keyboard.
 */
object KeyboardText {
    const val RETURN = 0x0D
    const val ESCAPE = 0x1B
    const val LEFT_ARROW = 0x08
    const val RIGHT_ARROW = 0x15

    /** The key code for [char], or null if the keyboard cannot type it. */
    fun forCharacter(char: Char): Int? = when (char) {
        '\n', '\r' -> RETURN
        in 'a'..'z' -> char.uppercaseChar().code
        in ' '..'Z', ']', '^' -> char.code
        else -> null
    }

    fun canType(char: Char): Boolean = forCharacter(char) != null

    /** CTRL with a letter, or with `@`: codes 0-26. */
    fun control(char: Char): Int? = when (val upper = char.uppercaseChar()) {
        in '@'..'Z' -> upper.code and 0x1F
        else -> null
    }
}
