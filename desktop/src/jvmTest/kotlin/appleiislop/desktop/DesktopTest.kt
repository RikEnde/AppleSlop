// SPDX-License-Identifier: BSD-3-Clause
package appleiislop.desktop

import java.nio.file.Files
import kotlin.test.*

class DesktopTest {
    @Test fun bundledRomsBootAndRender() {
        val m=runHeadless(Roms.load(null,false),Options.parse(arrayOf("--no-disk","--type","PRINT 2+2\\n")))
        assertTrue(m.textLines().any { it.trim()=="4" })
        val file=Files.createTempFile("apple-frame", ".png").toFile()
        try {
            saveScreenshot(m,file)
            val image=javax.imageio.ImageIO.read(file)
            assertEquals(640,image.width); assertEquals(480,image.height)
        } finally { file.delete() }
    }
    @Test fun invalidOptionsAreRejected() {
        for (args in listOf(arrayOf("--scale","0"),arrayOf("--disk","x.dsk","--no-disk"),arrayOf("--origin","1000"))) {
            assertFailsWith<IllegalArgumentException> { Options.parse(args) }
        }
    }
    @Test fun diskFileRoundtrip() {
        val directory=Files.createTempDirectory("apple-disks").toFile()
        try {
            val disk=appleiislop.apple2.Disk.blank()
            for (ext in listOf("dsk","po","nib")) {
                val file=directory.resolve("test.$ext"); DiskFile.save(disk,file)
                assertContentEquals(disk.toNibbles(),DiskFile.load(file).toNibbles())
            }
        } finally { directory.deleteRecursively() }
    }
}
