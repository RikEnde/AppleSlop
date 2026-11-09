// SPDX-FileCopyrightText: 2026 The Beebslop contributors
// SPDX-FileCopyrightText: 2026 The AppleIIslop contributors
// SPDX-License-Identifier: BSD-3-Clause

package appleiislop.desktop

import appleiislop.apple2.Disk
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Disk images on disk. The extension gives the format: `.po` is a sector image
 * in ProDOS order, `.nib` holds raw nibbles, and anything else (`.dsk`, `.do`)
 * is a sector image in DOS 3.3 order.
 */
object DiskFile {
    enum class Format { DOS_ORDER, PRODOS_ORDER, NIBBLES }

    fun format(file: File): Format = when (file.extension.lowercase()) {
        "po" -> Format.PRODOS_ORDER
        "nib" -> Format.NIBBLES
        else -> Format.DOS_ORDER
    }

    /**
     * Reads an image. A file the host won't let us write is inserted write-protected.
     * @throws IOException if the file can't be read or isn't a disk image.
     */
    fun load(file: File): Disk {
        val bytes = file.readBytes()
        val disk = try {
            when (format(file)) {
                Format.DOS_ORDER -> Disk.parse(bytes, Disk.SectorOrder.DOS)
                Format.PRODOS_ORDER -> Disk.parse(bytes, Disk.SectorOrder.PRODOS)
                Format.NIBBLES -> Disk.parseNibbles(bytes)
            }
        } catch (e: IllegalArgumentException) {
            throw IOException(e.message, e)
        }
        disk.writeProtected = !file.canWrite()
        return disk
    }

    /**
     * Writes [disk] to [file] in the format of its extension, replacing the file
     * in one step.
     * @throws IOException if a sector image is asked for and the disk has sectors
     * that can't be read, as a copy-protected disk might; a `.nib` file takes anything.
     */
    fun save(disk: Disk, file: File) {
        val format = format(file)
        if (format != Format.NIBBLES) {
            val unreadable = disk.unreadableSectors()
            if (unreadable > 0) {
                throw IOException("$unreadable of the disk's sectors can't be read, so it only fits a .nib image")
            }
        }
        val bytes = when (format) {
            Format.DOS_ORDER -> disk.toImage(Disk.SectorOrder.DOS)
            Format.PRODOS_ORDER -> disk.toImage(Disk.SectorOrder.PRODOS)
            Format.NIBBLES -> disk.toNibbles()
        }
        val target = file.absoluteFile
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, ".${target.name}.tmp")
        temporary.writeBytes(bytes)
        Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}
