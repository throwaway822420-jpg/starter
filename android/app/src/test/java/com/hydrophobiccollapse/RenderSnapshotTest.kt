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
    init { AndroidCanvas.install() }
    private val out = File("build/snapshots").apply { mkdirs() }

    private fun save(b: Bitmap, name: String) = File(out, name).outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }

    @Test
    fun wallpaperFrame() {
        val sim = Simulation(density = 1.5f, wallpaperMode = true)
        sim.applySettings(Settings(protein = "bpti", hud = true, ribosome = false))
        sim.resize(1600, 1000)
        repeat(900) { sim.update(1.0 / 30) } // 30 s of simulated wall time
        val b = Bitmap.createBitmap(1600, 1000, Bitmap.Config.ARGB_8888)
        sim.draw(AndroidCanvas(Canvas(b)))
        save(b, "wallpaper.png")
        assertTrue(sim.eng.rg < 20)
    }

    /** Frames start from a full unfolded chain unless [ribosome] is set. */
    private fun frame(name: String, s: Settings, seconds: Int, ribosome: Boolean = false): Simulation {
        val sim = Simulation(density = 1.5f, wallpaperMode = true)
        sim.applySettings(s.copy(ribosome = ribosome))
        sim.resize(1600, 1000)
        repeat(seconds * 30) { sim.update(1.0 / 30) }
        val b = Bitmap.createBitmap(1600, 1000, Bitmap.Config.ARGB_8888)
        sim.draw(AndroidCanvas(Canvas(b)))
        save(b, name)
        return sim
    }

    @Test
    fun ubiquitinGuidedFrame() { frame("ubiquitin-guided.png", Settings(protein = "ubq", hud = true), 40) }

    @Test
    fun cartoonFrames() {
        frame("cartoon-ubiquitin.png", Settings(protein = "ubq", viewStyle = 1), 40)
        frame("cartoon-ubiquitin-rainbow.png", Settings(protein = "ubq", viewStyle = 1, colorBy = 2), 40)
        frame("cartoon-myoglobin.png", Settings(protein = "myoglobin", viewStyle = 1, colorBy = 2), 60)
        frame("cartoon-hemoglobin.png", Settings(protein = "hemoglobin", viewStyle = 1), 60)
        frame("beads-gcn4-chains.png", Settings(protein = "gcn4"), 30)
        frame("trace-lysozyme.png", Settings(protein = "lysozyme", viewStyle = 2, colorBy = 2), 30)
    }

    @Test
    fun chaperoneEffectsAndReplayFrames() {
        frame("chaperone-ubiquitin.png", Settings(protein = "ubq", viewStyle = 1, chaperone = true, effects = true), 20)
        frame("effects-beads-bpti.png", Settings(protein = "bpti", effects = true), 20)
        // A replay: the recording plays back while the engine waits
        val sim = Simulation(density = 1.5f, wallpaperMode = true)
        sim.applySettings(Settings(protein = "ubq", viewStyle = 3, ribosome = false))
        sim.resize(1600, 1000)
        repeat(30 * 30) { sim.update(1.0 / 30) }
        val t = sim.eng.time
        assertTrue(sim.startReplay())
        repeat(30) { sim.update(1.0 / 30) }
        assertTrue(sim.replaying)
        assertEquals("the engine waits during a replay", t, sim.eng.time, 1e-9)
        val b = Bitmap.createBitmap(1600, 1000, Bitmap.Config.ARGB_8888)
        sim.draw(AndroidCanvas(Canvas(b)))
        save(b, "replay-ubiquitin.png")
        repeat(30 * 30) { sim.update(1.0 / 30) }
        assertTrue("the replay ends by itself", !sim.replaying)
        assertTrue(sim.exportTrajectoryPdb()!!.lines().count { it.startsWith("MODEL") } > 100)
    }

    @Test
    fun overlayAndProgressFrames() {
        frame("overlay-ubiquitin.png", Settings(protein = "ubq", viewStyle = 3), 40)
        frame("overlay-insulin.png", Settings(protein = "insulin", viewStyle = 3), 40)
    }

    @Test
    fun progressReachesFoldedForTrpCage() {
        val best = (1..3).maxOf {
            val sim = Simulation(density = 1.5f, wallpaperMode = true)
            sim.applySettings(Settings(protein = "trpcage", ribosome = false))
            sim.resize(800, 600)
            repeat(40 * 30) { sim.update(1.0 / 30) }
            sim.progress
        }
        assertTrue("progress $best", best > 0.8)
    }

    /** Steps per wall-clock second in Balanced and Extreme, drawing at the rate each mode uses. */
    @Test
    fun extremeModeRunsMoreSteps() {
        for (id in listOf("villin", "hemoglobin")) {
            val rates = listOf(1, 2).map { perf ->
                val sim = Simulation(density = 1.5f, wallpaperMode = true)
                sim.applySettings(Settings(protein = id, performance = perf, ribosome = false))
                sim.resize(800, 600)
                sim.setActive(true)
                val frame = if (perf == 2) 33L else 16L
                val b = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888); val c = Canvas(b)
                val t0 = System.nanoTime(); var frames = 0
                while (System.nanoTime() - t0 < 4_000_000_000L) {
                    val f0 = System.nanoTime()
                    sim.update(frame / 1000.0); sim.draw(AndroidCanvas(c)); frames++
                    val left = frame - (System.nanoTime() - f0) / 1_000_000
                    if (left > 0) Thread.sleep(left)
                }
                val r = sim.stepsPerSecond
                sim.release()
                r
            }
            println("SPEED $id balanced=%.0f extreme=%.0f steps/s (%.1fx)".format(rates[0], rates[1], rates[1] / rates[0]))
            assertTrue("$id: extreme ${rates[1]} vs balanced ${rates[0]}", rates[1] > rates[0])
        }
    }

    @Test
    fun experimentFrames() {
        // Readout with the folding funnel, and the inspector on a residue
        val sim = Simulation(density = 1.5f, wallpaperMode = true)
        sim.applySettings(Settings(protein = "ubq", hud = true, ribosome = false))
        sim.resize(1600, 1000)
        repeat(40 * 30) { sim.update(1.0 / 30) }
        sim.selectResidue(42)
        sim.update(1.0 / 30)
        val b = Bitmap.createBitmap(1600, 1000, Bitmap.Config.ARGB_8888)
        sim.draw(AndroidCanvas(Canvas(b))); save(b, "funnel-inspector.png")
        frame("pulling.png", Settings(protein = "ubq", pullPN = 200f, viewStyle = 1), 30)
        frame("assist-hemoglobin.png", Settings(protein = "hemoglobin", assist = 3, viewStyle = 1), 15)
    }

    @Test
    fun hemoglobinFrame() { frame("hemoglobin.png", Settings(protein = "hemoglobin", hud = true), 20) }

    @Test
    fun insulinFrame() { frame("insulin.png", Settings(protein = "insulin", hud = true, redox = 0.8f), 30) }

    @Test
    fun randomLargeFrame() { frame("random-1500.png", Settings(protein = Proteins.RANDOM_ID, randomLength = 1500, randomStyle = 1, hud = true), 20) }

    /** Proteins made on the ribosome: part-way through, a multi-chain protein, and one that has finished. */
    @Test
    fun ribosomeFrames() {
        frame("ribosome-myoglobin.png", Settings(protein = "myoglobin", viewStyle = 1, hud = true), 2, ribosome = true)
        frame("ribosome-hemoglobin.png", Settings(protein = "hemoglobin", viewStyle = 3), 5, ribosome = true)
        val sim = frame("ribosome-ubiquitin-done.png", Settings(protein = "ubq", viewStyle = 1), 40, ribosome = true)
        assertTrue("translation finishes", !sim.eng.translating && sim.eng.released == sim.eng.n)
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
