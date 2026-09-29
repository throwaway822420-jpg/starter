package com.hydrophobiccollapse.desktop

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import javax.imageio.ImageIO

/**
 * Writes the app icon as a Windows .ico file (PNG images inside, which Windows reads since Vista), for the
 * .exe that jpackage builds. CI runs this with --write-icon before packaging.
 */
object IconFile {
    fun write(file: File) {
        val sizes = listOf(16, 24, 32, 48, 64, 128, 256)
        val pngs = sizes.map { s -> ByteArrayOutputStream().also { ImageIO.write(appIcon(s), "png", it) }.toByteArray() }
        file.parentFile?.mkdirs()
        DataOutputStream(file.outputStream().buffered()).use { out ->
            fun le16(v: Int) { out.writeByte(v and 0xFF); out.writeByte(v shr 8 and 0xFF) }
            fun le32(v: Int) { le16(v and 0xFFFF); le16(v ushr 16) }
            le16(0); le16(1); le16(sizes.size)                  // header: reserved, type 1 = icon, count
            var offset = 6 + 16 * sizes.size
            for ((k, s) in sizes.withIndex()) {
                out.writeByte(if (s >= 256) 0 else s); out.writeByte(if (s >= 256) 0 else s)   // 0 means 256
                out.writeByte(0); out.writeByte(0)               // no palette, reserved
                le16(1); le16(32)                                // planes, bits per pixel
                le32(pngs[k].size); le32(offset)
                offset += pngs[k].size
            }
            pngs.forEach { out.write(it) }
        }
    }
}
