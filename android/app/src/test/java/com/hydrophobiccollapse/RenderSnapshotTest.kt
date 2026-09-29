package com.hydrophobiccollapse

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Renders real frames to PNG (build/snapshots) so the drawing code can be checked without a device. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w1280dp-h800dp-land-mdpi")
class RenderSnapshotTest {
    private val out = File("build/snapshots").apply { mkdirs() }

    private fun save(b: Bitmap, name: String) = File(out, name).outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }

    @Test
    fun wallpaperFrame() {
        val sim = Simulation(density = 1.5f, wallpaperMode = true)
        sim.applySettings(Settings(protein = "bpti", hud = true))
        sim.resize(1600, 1000)
        repeat(900) { sim.update(1.0 / 30) } // 30 s of simulated wall time
        val b = Bitmap.createBitmap(1600, 1000, Bitmap.Config.ARGB_8888)
        sim.draw(Canvas(b))
        save(b, "wallpaper.png")
        assertTrue(sim.eng.rg < 20)
    }

    @Test
    fun settingsScreen() {
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).create().start().postCreate(null).visible().get()
        val root = activity.window.decorView
        root.measure(View.MeasureSpec.makeMeasureSpec(1280, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 1280, 800)
        val b = Bitmap.createBitmap(1280, 800, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(b))
        save(b, "settings.png")
    }
}
