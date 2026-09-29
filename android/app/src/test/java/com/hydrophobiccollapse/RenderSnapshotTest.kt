package com.hydrophobiccollapse

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import android.widget.Button
import android.widget.EditText
import android.view.ViewGroup
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowLooper
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

    private fun frame(name: String, s: Settings, seconds: Int) {
        val sim = Simulation(density = 1.5f, wallpaperMode = true)
        sim.applySettings(s)
        sim.resize(1600, 1000)
        repeat(seconds * 30) { sim.update(1.0 / 30) }
        val b = Bitmap.createBitmap(1600, 1000, Bitmap.Config.ARGB_8888)
        sim.draw(Canvas(b))
        save(b, name)
    }

    @Test
    fun ubiquitinGuidedFrame() = frame("ubiquitin-guided.png", Settings(protein = "ubq", hud = true), 40)

    @Test
    fun hemoglobinFrame() = frame("hemoglobin.png", Settings(protein = "hemoglobin", hud = true), 20)

    @Test
    fun insulinFrame() = frame("insulin.png", Settings(protein = "insulin", hud = true, redox = 0.8f), 30)

    @Test
    fun randomLargeFrame() = frame("random-1500.png", Settings(protein = Proteins.RANDOM_ID, randomLength = 1500, randomStyle = 1, hud = true), 20)

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

    private fun <T : View> find(v: View, cls: Class<T>, pred: (T) -> Boolean): T? {
        if (cls.isInstance(v) && pred(cls.cast(v)!!)) return cls.cast(v)
        if (v is ViewGroup) for (k in 0 until v.childCount) find(v.getChildAt(k), cls, pred)?.let { return it }
        return null
    }

    @Test
    fun randomModeAndProteinEditor() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        Settings(protein = Proteins.RANDOM_ID, randomLength = 80).save(Settings.prefs(app))
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).create().start().postCreate(null).visible().get()
        val root = activity.window.decorView
        root.measure(View.MeasureSpec.makeMeasureSpec(1280, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 1280, 800)
        val b = Bitmap.createBitmap(1280, 800, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(b)); save(b, "settings-random.png")

        // Open the editor, enter a two-chain FASTA sequence, save
        find(root, Button::class.java) { it.text.toString().startsWith("Create a protein") }!!.performClick()
        ShadowLooper.idleMainLooper()
        val dialog = ShadowDialog.getLatestDialog() as android.app.AlertDialog
        val fields = ArrayList<EditText>()
        fun collect(v: View) { if (v is EditText) fields.add(v); if (v is ViewGroup) for (k in 0 until v.childCount) collect(v.getChildAt(k)) }
        collect(dialog.window!!.decorView)
        // Fields: PDB/AlphaFold ID, name, sequence
        fields[1].setText("Test dimer")
        fields[2].setText(">a\nMKTAYIAKQR\n>b\nGIVEQCCTSI")
        val dv = dialog.window!!.decorView
        dv.measure(View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.AT_MOST))
        dv.layout(0, 0, 900, dv.measuredHeight)
        val db = Bitmap.createBitmap(900, dv.measuredHeight, Bitmap.Config.ARGB_8888)
        dv.draw(Canvas(db)); save(db, "editor.png")
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
        ShadowLooper.idleMainLooper()

        val saved = Settings.load(Settings.prefs(app))
        assertTrue(saved.protein.startsWith("custom:"))
        val p = Proteins.byId(saved.protein, saved.customJson)
        assertEquals("Test dimer", p.name)
        assertEquals(listOf("MKTAYIAKQR", "GIVEQCCTSI"), p.chains)
    }
}
