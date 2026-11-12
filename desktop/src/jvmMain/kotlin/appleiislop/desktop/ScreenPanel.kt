// SPDX-FileCopyrightText: 2026 The AppleIIslop contributors
// SPDX-License-Identifier: BSD-3-Clause

package appleiislop.desktop

import appleiislop.apple2.Video
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import javax.swing.JPanel

/**
 * Shows the latest picture, cropped to [VIEW], scaled to fit with its 4:3 shape
 * and letterboxed in black.
 */
class ScreenPanel(private val emulator: Emulator) : JPanel() {
    private val image = BufferedImage(Video.WIDTH, Video.HEIGHT, BufferedImage.TYPE_INT_RGB)
    private val pixels = (image.raster.dataBuffer as DataBufferInt).data

    var scale: Int = 1
        set(value) {
            field = value
            preferredSize = Dimension(VIEW.width * value, VIEW.height * value)
            revalidate()
        }

    init {
        background = Color.BLACK
        isFocusable = true
        focusTraversalKeysEnabled = false // TAB goes to the Apple II.
        isDoubleBuffered = true
        scale = 1
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        emulator.copyFrame(pixels)
        val target = fitRectangle(width, height)
        (g as Graphics2D).setRenderingHint(
            RenderingHints.KEY_INTERPOLATION,
            RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR,
        )
        g.drawImage(
            image,
            target.x, target.y, target.x + target.width, target.y + target.height,
            VIEW.x, VIEW.y, VIEW.x + VIEW.width, VIEW.y + VIEW.height,
            null,
        )
    }

    companion object {
        /**
         * The part of [Video]'s picture to show: all of it. The 560 x 384 display
         * sits in a 640 x 480 picture, 4:3 like the screen of a monitor, with a
         * border of 40 pixels at the sides and 48 at the top and bottom.
         */
        val VIEW = Rectangle(0, 0, Video.WIDTH, Video.HEIGHT)

        /** Largest 4:3 rectangle centred in a width x height area. */
        fun fitRectangle(width: Int, height: Int): Rectangle {
            val w = minOf(width, height * VIEW.width / VIEW.height)
            val h = w * VIEW.height / VIEW.width
            return Rectangle((width - w) / 2, (height - h) / 2, w, h)
        }
    }
}
