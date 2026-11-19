// SPDX-FileCopyrightText: 2026 The AppleIIslop contributors
// SPDX-License-Identifier: BSD-3-Clause

package appleiislop.cpu

/** What one [Cpu6502.step] did. */
enum class StepKind {
    /** Fetched and executed one instruction. */
    INSTRUCTION,

    /** Took an IRQ: pushed PC and status and jumped through $FFFE. */
    IRQ,

    /** Took an NMI: pushed PC and status and jumped through $FFFA. */
    NMI,

    /** Did nothing: the CPU is halted by a JAM opcode until reset. */
    HALTED,
}
