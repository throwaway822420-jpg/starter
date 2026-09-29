package com.hydrophobiccollapse.desktop

import com.hydrophobiccollapse.Simulation
import com.hydrophobiccollapse.Settings
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

class RenderTest {
    @Test fun rendersAFrame() {
        System.setProperty("java.awt.headless", "true")
        Java2DCanvas.install()
        val sim = Simulation(1.5f, wallpaperMode = false)
        sim.resize(1600, 1000)
        sim.applySettings(Settings(protein = "bpti", hud = true))
        repeat(600) { sim.update(1.0 / 30) }
        val img = BufferedImage(1600, 1000, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        sim.draw(Java2DCanvas(g)); g.dispose(); sim.release()
        File("build/render").mkdirs(); ImageIO.write(img, "png", File("build/render/desktop.png"))
        val colours = HashSet<Int>()
        for (y in 0 until 1000 step 7) for (x in 0 until 1600 step 7) colours.add(img.getRGB(x, y))
        assertTrue("frame looks blank", colours.size > 50)
    }

    /** Part-way through making a protein on the ribosome → build/render/desktop-ribosome.png */
    @Test fun rendersTheRibosome() {
        System.setProperty("java.awt.headless", "true")
        Java2DCanvas.install()
        val sim = Simulation(1.5f, wallpaperMode = false)
        sim.resize(1600, 1000)
        sim.applySettings(Settings(protein = "myoglobin", viewStyle = 1, hud = true, ribosome = true))
        repeat(150) { sim.update(1.0 / 30) }
        val img = BufferedImage(1600, 1000, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        sim.draw(Java2DCanvas(g)); g.dispose(); sim.release()
        File("build/render").mkdirs(); ImageIO.write(img, "png", File("build/render/desktop-ribosome.png"))
        assertTrue("still translating", sim.eng.translating && sim.eng.made > 1)
    }
}
