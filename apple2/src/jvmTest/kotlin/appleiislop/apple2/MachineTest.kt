// SPDX-License-Identifier: BSD-3-Clause
package appleiislop.apple2

import java.io.File
import kotlin.test.*

class MachineTest {
    private fun rom(name: String) = File(System.getProperty("appleiislop.romDirectory", "roms"), name).readBytes()
    private fun machine(disk: Boolean = false) = Apple2Machine(rom("apple2plus.rom"), rom("video.rom"), if (disk) rom("disk2.rom") else null)
    private fun type(m: Apple2Machine, text: String) {
        val typist = Typist(); typist.enqueue(text)
        repeat(900) { typist.onFrame(m); m.runForTicks(Apple2Clock.TICKS_PER_FRAME.toLong()) }
        assertFalse(typist.busy)
    }
    private fun screen(m: Apple2Machine) = m.textLines().joinToString("\n")

    @Test fun basicBootAndProgram() {
        val m = machine(); m.runForSeconds(1.0)
        assertContains(screen(m), "APPLE ][")
        type(m, "10 FOR I=1 TO 3:PRINT I*I:NEXT\nRUN\n")
        val lines=m.textLines().map { it.trim() }
        assertTrue(lines.containsAll(listOf("1","4","9")), screen(m))
        assertFalse(m.cpu.halted)
        assertTrue(m.video.pixels.any { it != Video.BLACK })
        m.reset(); m.runForSeconds(1.0)
        type(m,"LIST\n")
        assertContains(screen(m),"FOR I = 1 TO 3")
    }

    @Test fun dosBootSaveAndReloadThroughDiskImage() {
        val m=machine(true)
        val source=File(System.getProperty("appleiislop.diskDirectory","disks"),"dos33-master.dsk").readBytes()
        val disk=Disk.parse(source,Disk.SectorOrder.DOS)
        m.disk!!.drives[0].disk=disk
        m.runForSeconds(25.0)
        assertContains(screen(m),"DOS VERSION 3.3")
        type(m,"10 PRINT 2468\nSAVE ROUNDTRIP\n")
        assertTrue(disk.changes > 0)
        val saved=disk.toImage(Disk.SectorOrder.DOS)
        assertEquals(0,disk.unreadableSectors())
        val restored=machine(true)
        restored.disk!!.drives[0].disk=Disk.parse(saved,Disk.SectorOrder.DOS)
        restored.runForSeconds(25.0)
        type(restored,"RUN ROUNDTRIP\n")
        assertTrue(restored.textLines().any { it.trim()=="2468" },screen(restored))
    }

    @Test fun nibbleRoundtripAndSectorOrdering() {
        val bytes=ByteArray(Disk.IMAGE_SIZE) { ((it*73) xor (it shr 8)).toByte() }
        for (order in Disk.SectorOrder.entries) {
            val disk=Disk.parse(bytes,order)
            assertEquals(0,disk.unreadableSectors())
            assertContentEquals(bytes,Disk.parseNibbles(disk.toNibbles()).toImage(order))
            val other=if(order==Disk.SectorOrder.DOS) Disk.SectorOrder.PRODOS else Disk.SectorOrder.DOS
            assertContentEquals(bytes,Disk.parse(disk.toImage(other),other).toImage(order))
        }
    }
}
