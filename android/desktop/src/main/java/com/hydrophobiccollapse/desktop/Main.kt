package com.hydrophobiccollapse.desktop

import com.formdev.flatlaf.FlatDarkLaf
import com.hydrophobiccollapse.CaCodec
import com.hydrophobiccollapse.KeyValueStore
import com.hydrophobiccollapse.Proteins
import com.hydrophobiccollapse.Settings
import com.hydrophobiccollapse.Simulation
import com.hydrophobiccollapse.StructureIO
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import java.awt.GraphicsEnvironment
import java.awt.KeyboardFocusManager
import java.awt.MenuItem
import java.awt.MouseInfo
import java.awt.PopupMenu
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.SystemTray
import java.awt.Toolkit
import java.awt.TrayIcon
import java.awt.Window
import java.awt.event.KeyEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.awt.image.BufferedImage
import java.io.File
import java.util.Properties
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JDialog
import javax.swing.JFileChooser
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JScrollPane
import javax.swing.JSlider
import javax.swing.JSpinner
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.Scrollable
import javax.swing.ScrollPaneConstants
import javax.swing.SpinnerNumberModel
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.SwingWorker
import javax.swing.Timer
import javax.swing.ToolTipManager
import javax.swing.UIManager
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.filechooser.FileNameExtensionFilter
import javax.swing.text.JTextComponent
import kotlin.math.roundToInt

// ---------- Settings storage: a properties file (custom proteins with structures can be large) ----------

class FileStore(private val file: File) : KeyValueStore {
    private val props = Properties().apply { if (file.exists()) file.inputStream().use { load(it) } }
    override fun getString(key: String, default: String) = props.getProperty(key) ?: default
    override fun getInt(key: String, default: Int) = props.getProperty(key)?.toIntOrNull() ?: default
    override fun getFloat(key: String, default: Float) = props.getProperty(key)?.toFloatOrNull() ?: default
    override fun getBoolean(key: String, default: Boolean) = props.getProperty(key)?.toBooleanStrictOrNull() ?: default
    override fun putString(key: String, value: String) { props.setProperty(key, value) }
    override fun putInt(key: String, value: Int) { props.setProperty(key, value.toString()) }
    override fun putFloat(key: String, value: Float) { props.setProperty(key, value.toString()) }
    override fun putBoolean(key: String, value: Boolean) { props.setProperty(key, value.toString()) }
    override fun commit() {
        try { file.parentFile?.mkdirs(); file.outputStream().use { props.store(it, "Hydrophobic Collapse settings") } } catch (e: Exception) { }
    }
    val isNew get() = props.isEmpty

    companion object {
        /** %APPDATA%\HydrophobicCollapse on Windows, ~/.hydrophobic-collapse elsewhere. */
        fun default(): FileStore {
            val appData = System.getenv("APPDATA")
            val dir = if (appData != null) File(appData, "HydrophobicCollapse") else File(System.getProperty("user.home"), ".hydrophobic-collapse")
            return FileStore(File(dir, "settings.properties"))
        }
    }
}

// ---------- Colours shared with the Android app ----------
object Ui {
    val INK = Color(0x0A0F1D)
    val PANEL = Color(0x0D1324)
    val TEXT = Color(0xE4E8F3)
    val HAZE = Color(0x8A94B0)
    val HYDRO = Color(0xF0A44B)
    val LINE = Color(0x2A3350)
    val ROSE = Color(0xFF7D8E)
}

// ---------- The live wallpaper: a borderless window moved behind the desktop icons ----------

class WallpaperWindow(sim: Simulation) : JFrame("Hydrophobic Collapse wallpaper") {
    val surface = SimSurface(sim, interactive = false)
    init {
        isUndecorated = true
        type = Window.Type.UTILITY               // no taskbar button
        focusableWindowState = false
        isAutoRequestFocus = false
        background = Ui.INK
        contentPane.background = Ui.INK
        contentPane.add(surface)
        val screen = GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration.bounds
        bounds = screen
    }
    /** Shows the window and sinks it behind the icons; false (and closed) if Windows wouldn't allow it. */
    fun open(): Boolean {
        isVisible = true
        if (WinDesktop.attachToDesktop(this)) return true
        dispose()
        return false
    }
}

// ---------- The window ----------

class MainWindow(startAsWallpaper: Boolean) : JFrame("Hydrophobic Collapse") {
    private val store = FileStore.default()
    // The desktop starts with the readout on; the wallpaper starts with it off
    private var s = Settings.load(store, Settings(hud = true))
    // Windows-only preferences
    private var pauseWhenHidden = store.getBoolean("desktop.pauseWhenHidden", true)
    private var wallpaperReadout = store.getBoolean("desktop.wallpaperReadout", false)
    private val sim = Simulation(density = 1f, wallpaperMode = false).apply {
        replayHint = "Drag to turn · wheel zooms · click to pause · ← → jump · P or Esc stops"
    }
    private val view = SimSurface(sim, interactive = true)
    private val wallpaperNote = JPanel()
    private val center = JPanel(CardLayout())
    private val controls: Controls
    private val side: JScrollPane
    private val saveLater = Timer(600) { saveAll() }.apply { isRepeats = false }
    @Volatile private var wallpaper: WallpaperWindow? = null
    @Volatile private var surface: SimSurface? = view
    private val animator = Animator(sim) { surface }
    private var tray: TrayIcon? = null
    private var minimized = false
    private var hiddenBehindWindow = false

    init {
        defaultCloseOperation = DO_NOTHING_ON_CLOSE
        iconImages = listOf(16, 32, 64, 256).map { appIcon(it) }
        sim.applySettings(s)
        controls = Controls()
        side = JScrollPane(controls, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER)
        side.preferredSize = Dimension(370, 720)
        side.border = BorderFactory.createMatteBorder(0, 1, 0, 0, Ui.LINE)
        side.verticalScrollBar.unitIncrement = 16
        view.preferredSize = Dimension(960, 720)
        buildWallpaperNote()
        center.add(view, "live"); center.add(wallpaperNote, "wallpaper")
        contentPane.layout = BorderLayout()
        contentPane.add(center, BorderLayout.CENTER)
        contentPane.add(side, BorderLayout.EAST)
        pack()
        setLocationRelativeTo(null)
        view.active = true

        addWindowListener(object : WindowAdapter() {
            override fun windowOpened(e: WindowEvent) { sim.setActive(true); view.requestFocusInWindow() }
            override fun windowIconified(e: WindowEvent) { minimized = true; syncPause() }
            override fun windowDeiconified(e: WindowEvent) { minimized = false; syncPause() }
            override fun windowClosing(e: WindowEvent) {
                // With the wallpaper running, closing the controls leaves it running (the tray icon brings them back)
                if (wallpaper != null) isVisible = false else quit()
            }
        })
        installKeys()
        // Every half second: is the wallpaper hidden behind a maximized or full-screen window?
        Timer(500) {
            val covered = wallpaper != null && pauseWhenHidden && WinDesktop.desktopHidden(listOfNotNull(WinDesktop.hwnd(this)))
            if (covered != hiddenBehindWindow) { hiddenBehindWindow = covered; syncPause() }
        }.start()
        animator.start()
        // Started with --wallpaper (at sign-in): go straight behind the icons, or show the window if that fails
        if (startAsWallpaper) SwingUtilities.invokeLater { if (!startWallpaper()) isVisible = true }
    }

    /** Draw only when something can be seen; Extreme mode's physics thread follows. */
    private fun syncPause() {
        val idle = if (wallpaper != null) hiddenBehindWindow else minimized
        animator.paused = idle
        sim.setActive(!idle)
    }

    private fun quit() {
        saveAll()
        stopWallpaper()
        animator.running = false
        sim.release()
        tray?.let { SystemTray.getSystemTray().remove(it) }
        dispose()
        System.exit(0)
    }

    private fun saveAll() {
        s.save(store)
        store.putBoolean("desktop.pauseWhenHidden", pauseWhenHidden); store.putBoolean("desktop.wallpaperReadout", wallpaperReadout)
        store.commit()
    }

    // ---------- Live wallpaper ----------
    private fun startWallpaper(): Boolean {
        if (wallpaper != null || !WinDesktop.isWindows) return wallpaper != null
        val w = WallpaperWindow(sim)
        if (!w.open()) {
            JOptionPane.showMessageDialog(this, "Windows didn't let the app go behind the desktop icons.", "Wallpaper", JOptionPane.WARNING_MESSAGE)
            return false
        }
        wallpaper = w
        view.active = false; w.surface.active = true; surface = w.surface
        sim.bottomInset = taskbarHeight().toFloat()
        (center.layout as CardLayout).show(center, "wallpaper")
        applyToSim()
        // Moving the mouse turns the molecule a little, like swiping between home screens on the tablet
        val screen = w.bounds
        animator.beforeFrame = {
            MouseInfo.getPointerInfo()?.location?.let { p -> sim.setPageOffset((0.5 + 0.2 * ((p.x - screen.x) / screen.width.toDouble() - 0.5)).toFloat()) }
        }
        showTray()
        controls.refreshWallpaper()
        syncPause()
        return true
    }

    private fun stopWallpaper() {
        val w = wallpaper ?: return
        wallpaper = null
        animator.beforeFrame = null
        surface = view; w.surface.active = false; view.active = true
        w.dispose()
        WinDesktop.restoreWallpaper()
        sim.bottomInset = 0f; sim.setPageOffset(0.5f)
        (center.layout as CardLayout).show(center, "live")
        applyToSim()
        tray?.let { SystemTray.getSystemTray().remove(it) }; tray = null
        hiddenBehindWindow = false
        controls.refreshWallpaper()
        syncPause()
    }

    private fun taskbarHeight(): Int {
        val gc = GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration
        return Toolkit.getDefaultToolkit().getScreenInsets(gc).bottom
    }

    private fun showTray() {
        if (tray != null || !SystemTray.isSupported()) return
        val menu = PopupMenu()
        fun item(t: String, on: () -> Unit) = MenuItem(t).apply { addActionListener { SwingUtilities.invokeLater(on) } }
        menu.add(item("Show controls") { showControls() })
        menu.add(item("Replay the last two minutes") { sim.startReplay() })
        menu.add(item("Heat to unfold") { sim.heat() })
        menu.add(item("New protein / start over") { sim.reset(); controls.refreshProtein() })
        menu.addSeparator()
        menu.add(item("Stop the wallpaper") { stopWallpaper(); showControls() })
        menu.add(item("Exit") { quit() })
        val t = TrayIcon(appIcon(32), "Hydrophobic Collapse", menu).apply { isImageAutoSize = true; addActionListener { SwingUtilities.invokeLater { showControls() } } }
        runCatching { SystemTray.getSystemTray().add(t); tray = t }
    }
    private fun showControls() { isVisible = true; extendedState = NORMAL; toFront() }

    private fun buildWallpaperNote() {
        wallpaperNote.layout = BoxLayout(wallpaperNote, BoxLayout.Y_AXIS)
        wallpaperNote.background = Ui.INK
        wallpaperNote.border = BorderFactory.createEmptyBorder(40, 40, 40, 40)
        wallpaperNote.add(Box.createVerticalGlue())
        wallpaperNote.add(JLabel("Running as your desktop wallpaper").apply { font = font.deriveFont(Font.BOLD, 20f); foreground = Ui.TEXT; alignmentX = Component.CENTER_ALIGNMENT })
        wallpaperNote.add(Box.createVerticalStrut(10))
        wallpaperNote.add(JLabel("<html><div style='text-align:center'>Minimize or close this window to see it. The controls still work, and the tray icon by the clock brings them back.</div></html>").apply {
            foreground = Ui.HAZE; alignmentX = Component.CENTER_ALIGNMENT; horizontalAlignment = SwingConstants.CENTER; maximumSize = Dimension(460, 80)
        })
        wallpaperNote.add(Box.createVerticalStrut(16))
        wallpaperNote.add(JPanel().apply {
            isOpaque = false; alignmentX = Component.CENTER_ALIGNMENT
            add(button("Hide this window") { isVisible = false })
            add(button("Stop the wallpaper") { stopWallpaper() })
        })
        wallpaperNote.add(Box.createVerticalGlue())
    }

    // ---------- Keys ----------
    // One dispatcher sees every key in this window (the native canvas bypasses Swing's key bindings).
    // Arrows, Space and +/− act on the molecule only while it has focus, so sliders and lists keep them.
    private fun installKeys() {
        focusTraversalKeysEnabled = false; view.focusTraversalKeysEnabled = false
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher { e ->
            if (e.id != KeyEvent.KEY_PRESSED || SwingUtilities.getWindowAncestor(e.component) !== this && e.component !== this) return@addKeyEventDispatcher false
            if (e.component is JTextComponent) return@addKeyEventDispatcher false
            val onView = e.component === view
            when (e.keyCode) {
                KeyEvent.VK_F11 -> toggleFullScreen()
                KeyEvent.VK_TAB -> { side.isVisible = !side.isVisible; revalidate() }
                KeyEvent.VK_P -> sim.toggleReplay()
                KeyEvent.VK_R -> { sim.reset(); controls.refreshProtein() }
                KeyEvent.VK_ESCAPE -> if (sim.replaying) sim.stopReplay() else if (fullScreen) toggleFullScreen() else return@addKeyEventDispatcher false
                KeyEvent.VK_SPACE -> if (onView) { if (sim.replaying) sim.toggleReplayPause() else sim.heat() } else return@addKeyEventDispatcher false
                KeyEvent.VK_LEFT -> if (onView) { if (sim.replaying) sim.scrubReplay(-5.0) else sim.rotateBy(-0.15, 0.0) } else return@addKeyEventDispatcher false
                KeyEvent.VK_RIGHT -> if (onView) { if (sim.replaying) sim.scrubReplay(5.0) else sim.rotateBy(0.15, 0.0) } else return@addKeyEventDispatcher false
                KeyEvent.VK_UP -> if (onView) sim.rotateBy(0.0, -0.12) else return@addKeyEventDispatcher false
                KeyEvent.VK_DOWN -> if (onView) sim.rotateBy(0.0, 0.12) else return@addKeyEventDispatcher false
                KeyEvent.VK_EQUALS, KeyEvent.VK_PLUS, KeyEvent.VK_ADD -> if (onView) sim.zoomBy(1.15) else return@addKeyEventDispatcher false
                KeyEvent.VK_MINUS, KeyEvent.VK_SUBTRACT -> if (onView) sim.zoomBy(1 / 1.15) else return@addKeyEventDispatcher false
                else -> return@addKeyEventDispatcher false
            }
            e.consume(); true
        }
    }

    private var fullScreen = false
    private var windowedBounds: Rectangle? = null
    private fun toggleFullScreen() {
        if (wallpaper != null) return
        fullScreen = !fullScreen
        dispose()
        if (fullScreen) { windowedBounds = bounds; isUndecorated = true; extendedState = MAXIMIZED_BOTH; side.isVisible = false }
        else { isUndecorated = false; extendedState = NORMAL; windowedBounds?.let { bounds = it }; side.isVisible = true }
        isVisible = true
        view.requestFocusInWindow()
    }

    private fun update(next: Settings) {
        s = next
        applyToSim()
        saveLater.restart()
    }
    /** The wallpaper has its own choice about the readout. */
    private fun applyToSim() = sim.applySettings(if (wallpaper != null) s.copy(hud = wallpaperReadout) else s)

    // ---------- Saving PDB files ----------
    private var lastDir: File? = null
    private fun savePdb(trajectory: Boolean) {
        val text = if (trajectory) sim.exportTrajectoryPdb() else sim.exportPdb()
        if (text == null) { JOptionPane.showMessageDialog(this, "Nothing recorded yet. Let it run for a few seconds first.", "Save", JOptionPane.INFORMATION_MESSAGE); return }
        val base = sim.protein.name.replace(Regex("[^A-Za-z0-9]+"), "-").trim('-').lowercase().ifEmpty { "protein" }
        val chooser = JFileChooser(lastDir).apply {
            dialogTitle = if (trajectory) "Save the fold as a PDB movie" else "Save the shape as a PDB file"
            fileFilter = FileNameExtensionFilter("PDB files (*.pdb)", "pdb")
            selectedFile = File(lastDir, "$base-${if (trajectory) "fold" else "shape"}.pdb")
        }
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return
        var file = chooser.selectedFile
        if (!file.name.lowercase().endsWith(".pdb")) file = File(file.parentFile, file.name + ".pdb")
        lastDir = file.parentFile
        try {
            file.writeText(text)
            controls.saved("Saved ${file.name}. Open it in PyMOL, ChimeraX or molstar.org/viewer.")
        } catch (e: Exception) {
            JOptionPane.showMessageDialog(this, "Couldn't save: ${e.message}", "Save", JOptionPane.ERROR_MESSAGE)
        }
    }

    // ---------- Controls, matching the Android settings screen ----------
    inner class Controls : JPanel(), Scrollable {
        private val proteinBox = JComboBox<String>()
        private var entries = listOf<Pair<String, String>>()
        private val proteinNote = note("")
        private val randomBox = column()
        private val editRow = row()
        private val guidanceBox = column()
        private val savedNote = note("")
        private val wallpaperButton = button("Set as wallpaper") { if (wallpaper == null) startWallpaper() else stopWallpaper() }
        private var refreshing = false

        // Fill the scroll pane's width, so long notes wrap instead of running off the edge
        override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
        override fun getScrollableUnitIncrement(r: Rectangle, o: Int, d: Int) = 16
        override fun getScrollableBlockIncrement(r: Rectangle, o: Int, d: Int) = r.height - 32
        override fun getScrollableTracksViewportWidth() = true
        override fun getScrollableTracksViewportHeight() = false

        init {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createEmptyBorder(16, 16, 16, 16)
            background = Ui.PANEL

            add(JLabel("Hydrophobic Collapse").apply { font = font.deriveFont(Font.BOLD, 20f); foreground = Ui.TEXT })
            add(note("Real protein sequences folding in 3D, with pH-driven proton transfers and disulfide chemistry."))
            add(gap(10))
            add(grid(button("Heat to unfold", primary = true) { sim.heat() }, button("Start over") { sim.reset(); refreshProtein() }))
            add(gap(6))
            add(grid(button("Replay (P)") { if (!sim.startReplay()) saved("Let it run a few seconds first: there's nothing to replay yet.") }, button("Full screen (F11)") { toggleFullScreen() }))
            if (WinDesktop.isWindows) { add(gap(6)); add(grid(wallpaperButton, button("Hide panel (Tab)") { side.isVisible = false; this@MainWindow.revalidate() })) }
            else { add(gap(6)); add(grid(button("Hide panel (Tab)") { side.isVisible = false; this@MainWindow.revalidate() })) }

            section("Protein")
            proteinBox.addActionListener {
                if (refreshing) return@addActionListener
                val e = entries.getOrNull(proteinBox.selectedIndex) ?: return@addActionListener
                if (e.first != s.protein) { update(s.copy(protein = e.first)); refreshProtein() }
            }
            add(full(proteinBox)); add(gap(4)); add(proteinNote)
            randomBox.add(logSlider("Random length", 20, Proteins.MAX_RESIDUES, s.randomLength) { update(s.copy(randomLength = it)); refreshProtein() })
            randomBox.add(label("Style"))
            randomBox.add(full(combo(Proteins.RANDOM_STYLES, s.randomStyle) { update(s.copy(randomStyle = it)); refreshProtein() }))
            add(randomBox)
            add(gap(6))
            add(full(button("Create a protein…") { ProteinEditor(null).isVisible = true }))
            editRow.add(button("Edit") { ProteinEditor(s.protein).isVisible = true }); editRow.add(Box.createHorizontalStrut(8))
            editRow.add(button("Delete") { confirmDelete() })
            add(gap(6)); add(editRow)
            guidanceBox.add(slider("Native-structure guidance", 0.0, 1.0, 0.05, s.nativeBias.toDouble(), { "${(it * 100).roundToInt()} %" }) { update(s.copy(nativeBias = it.toFloat())) })
            guidanceBox.add(note("100 % folds toward the real structure. 0 % uses only generic physics, which collapses but rarely finds the real fold."))
            add(guidanceBox)

            section("Solution")
            add(slider("Temperature", 270.0, 420.0, 5.0, s.temp.toDouble(), { "${it.roundToInt()} K · ${it.roundToInt() - 273} °C" }) { update(s.copy(temp = it.roundToInt())) })
            add(slider("pH", 1.0, 13.0, 0.5, s.ph.toDouble(), { "%.1f".format(it) }) { update(s.copy(ph = it.toFloat())) })
            add(slider("Salt (NaCl)", 0.0, 1000.0, 25.0, s.salt.toDouble(), {
                val debye = 3.04 / Math.sqrt(maxOf(it, 1.0) / 1000)
                "${it.roundToInt()} mM · λD ${if (debye < 100) "%.1f".format(debye) else ">96"} Å"
            }) { update(s.copy(salt = it.roundToInt())) })
            add(slider("Redox buffer", -1.0, 1.0, 0.1, s.redox.toDouble(), { redoxLabel(it) }) { update(s.copy(redox = it.toFloat())) })
            add(slider("Urea (chemical denaturant)", 0.0, 8.0, 0.5, s.urea.toDouble(), { if (it == 0.0) "none" else "%.1f M".format(it) }) { update(s.copy(urea = it.toFloat())) })
            add(label("Crowding (when there are several chains)"))
            add(full(combo(listOf("Dilute: protein fills 3% of the space", "Crowded: 12%", "Cell-like: 25%, as in cytoplasm"), s.crowding) { update(s.copy(crowding = it)) }))

            section("Experiments")
            add(check("Grow on a ribosome", s.ribosome) { update(s.copy(ribosome = it)) })
            add(note("Each new protein is made one residue at a time, front end first, as in a real cell, pausing between domains. The first parts fold before the rest exists. Fun to watch, and it helps all-helix proteins like myoglobin, but most proteins fold a little faster without it."))
            add(label("Waiting between domains"))
            add(full(combo(listOf("Patient: waits until each domain is 90% folded", "Normal: 80%", "Hasty: 70%"), s.ribosomeSpeed) { update(s.copy(ribosomeSpeed = it)) }))
            add(gap(6))
            add(check("Chaperone cage (GroEL)", s.chaperone) { update(s.copy(chaperone = it)) })
            add(note("A barrel-shaped helper protein from real cells. Its sticky lining grabs the chain and pulls tangles apart, then a lid closes so it can fold alone inside, then it's released. The cycle repeats."))
            add(slider("Pull the ends apart (optical tweezers)", 0.0, 300.0, 10.0, s.pullPN.toDouble(), { if (it == 0.0) "off" else "${it.roundToInt()} pN" }) { update(s.copy(pullPN = it.toFloat())) })
            add(note("Real proteins unfold under roughly 100–300 pN in single-molecule experiments."))
            add(label("Folding assist (cheat)"))
            val assistNote = note(assistText(s.assist))
            add(full(combo(listOf("Off", "Gentle", "Strong", "Maximum"), s.assist) { update(s.copy(assist = it)); assistNote.text = assistText(it) }))
            add(assistNote)

            section("View")
            add(label("Style"))
            add(full(combo(listOf("Beads: every residue", "Cartoon: helix spirals, strand arrows", "Backbone trace", "Beads + cartoon overlay"), s.viewStyle) { update(s.copy(viewStyle = it)) }))
            add(label("Colour by"))
            add(full(combo(listOf("Auto: chains if several, else chemistry", "Chemistry", "Chain (one chain: rainbow N → C)"), s.colorBy) { update(s.copy(colorBy = it)) }))
            add(check("Depth of field and contact sparks", s.effects) { update(s.copy(effects = it)) })
            add(check("Show readout", s.hud) { update(s.copy(hud = it)) })
            add(check("Show folding progress", s.showProgress) { update(s.copy(showProgress = it)) })

            section("Replay and save")
            add(label("Replay speed"))
            val speeds = listOf(5, 10, 20)
            add(full(combo(speeds.map { "$it× faster than it happened" }, speeds.indexOf(s.replaySpeed).coerceAtLeast(1)) { update(s.copy(replaySpeed = speeds[it])) }))
            add(note("Replay shows the last two minutes sped up. Drag to turn it and use the wheel to zoom while it plays."))
            add(gap(6))
            add(grid(button("Save shape (PDB)…") { savePdb(false) }, button("Save fold movie…") { savePdb(true) }))
            add(savedNote.apply { isVisible = false })

            section("Playback")
            add(slider("Simulation speed", 0.25, 3.0, 0.25, s.speed.toDouble(), { "${it}×" }) { update(s.copy(speed = it.toFloat())) })
            add(label("Heat to unfold every"))
            val cycles = listOf(45 to "45 seconds", 90 to "90 seconds", 180 to "3 minutes", 300 to "5 minutes", 0 to "Never")
            add(full(combo(cycles.map { it.second }, cycles.indexOfFirst { it.first == s.cycle }.coerceAtLeast(1)) { update(s.copy(cycle = cycles[it].first)) }))
            add(label("Performance"))
            val perfNote = note("")
            fun perfText(p: Int) = when (p) {
                0 -> "30 frames a second and a lighter simulation: kind to laptops on battery."
                2 -> "Runs the simulation nonstop on its own thread and draws at 30 fps. The speed slider no longer applies."
                else -> "Draws at your screen's refresh rate (up to 120 Hz)."
            }
            add(full(combo(listOf("Battery saver: 30 fps, light computing", "Balanced", "Extreme: fold as fast as possible"), s.performance) {
                update(s.copy(performance = it)); perfNote.text = perfText(it)
            }))
            perfNote.text = perfText(s.performance); add(perfNote)

            if (WinDesktop.isWindows) {
                section("Wallpaper")
                add(note("Runs the simulation behind your desktop icons. It pauses by itself when a window covers the screen."))
                add(check("Pause when a window is maximized or full screen", pauseWhenHidden) { pauseWhenHidden = it; saveLater.restart() })
                add(check("Show the readout on the wallpaper", wallpaperReadout) { wallpaperReadout = it; applyToSim(); saveLater.restart() })
                val startup = check("Start as wallpaper when Windows starts", WinDesktop.startsWithWindows()) { WinDesktop.setStartWithWindows(it) }
                if (!WinDesktop.canStartWithWindows) { startup.isEnabled = false; startup.toolTipText = "Available in the installed app (HydrophobicCollapse.exe)" }
                add(startup)
            }

            section("How to use it")
            add(note("Click a residue to inspect it. Drag a residue to pull it. Drag open space to turn the molecule, and scroll to zoom. Click to stir, double-click to heat and unfold.\n\n" +
                "Keys: Space heats, R starts over, P replays, arrows turn (or jump through a replay), + and − zoom, Tab hides this panel, F11 goes full screen, Esc stops a replay."))
            add(Box.createVerticalGlue())
            refreshProtein()
            refreshWallpaper()
        }

        fun refreshProtein() {
            refreshing = true
            entries = listOf(Proteins.RANDOM_ID to "Random protein (new each time)") +
                Proteins.customs(s.customJson).map { it.id to "★ ${it.name} (${it.length})" } +
                Proteins.presets.map { it.id to "${it.name} (${it.length})" }
            proteinBox.removeAllItems(); entries.forEach { proteinBox.addItem(it.second) }
            proteinBox.selectedIndex = entries.indexOfFirst { it.first == s.protein }.let { if (it < 0) entries.indexOfFirst { e -> e.first == "bpti" } else it }
            refreshing = false
            val isRandom = s.protein == Proteins.RANDOM_ID
            val p = if (isRandom) sim.protein else Proteins.byId(s.protein, s.customJson)
            val structure = if (p.ca != null) " Folds toward its real structure (${p.structureSource})." else if (isRandom) "" else " No experimental structure, so generic physics only."
            proteinNote.text = if (isRandom) "Now showing: ${p.name}. ${p.note}${slowHint(p.length)}" else p.note + structure + slowHint(p.length)
            randomBox.isVisible = isRandom
            editRow.isVisible = s.protein.startsWith("custom:")
            guidanceBox.isVisible = p.ca != null
            revalidate(); repaint()
        }

        fun refreshWallpaper() { wallpaperButton.text = if (wallpaper != null) "Stop wallpaper" else "Set as wallpaper" }

        fun saved(message: String) { savedNote.text = message; savedNote.isVisible = true; revalidate() }

        private fun confirmDelete() {
            val p = Proteins.customs(s.customJson).firstOrNull { it.id == s.protein } ?: return
            if (JOptionPane.showConfirmDialog(this@MainWindow, "Delete ${p.name}? This can't be undone.", "Delete protein", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION) {
                update(s.copy(customJson = Proteins.deleteCustom(s.customJson, p.id), protein = "bpti")); refreshProtein()
            }
        }

        // ----- small builders -----
        private fun section(t: String) { add(gap(18)); add(JLabel(t.uppercase()).apply { font = font.deriveFont(Font.BOLD, 11f); foreground = Ui.HAZE }); add(gap(4)) }
        private fun gap(h: Int) = Box.createVerticalStrut(h)
        private fun label(t: String) = JLabel(t).apply { foreground = Ui.HAZE; border = BorderFactory.createEmptyBorder(8, 0, 3, 0); alignmentX = Component.LEFT_ALIGNMENT }
        private fun column() = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS); isOpaque = false; alignmentX = Component.LEFT_ALIGNMENT }
        private fun row(vararg c: JComponent) = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS); isOpaque = false; alignmentX = Component.LEFT_ALIGNMENT
            c.forEachIndexed { k, comp -> if (k > 0) add(Box.createHorizontalStrut(8)); add(comp) }
        }
        /** Buttons side by side, sharing the width equally. */
        private fun grid(vararg c: JComponent) = JPanel(java.awt.GridLayout(1, c.size, 8, 0)).apply {
            isOpaque = false; alignmentX = Component.LEFT_ALIGNMENT
            c.forEach { add(it) }
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
        }
        private fun <T : JComponent> full(c: T): T { c.alignmentX = Component.LEFT_ALIGNMENT; c.maximumSize = Dimension(Int.MAX_VALUE, c.preferredSize.height); return c }
        private fun check(t: String, v: Boolean, on: (Boolean) -> Unit) = JCheckBox(t, v).apply { isOpaque = false; alignmentX = Component.LEFT_ALIGNMENT; addActionListener { on(isSelected) } }
        private fun combo(items: List<String>, selected: Int, on: (Int) -> Unit) = JComboBox(items.toTypedArray()).apply {
            selectedIndex = selected.coerceIn(0, items.size - 1)
            // Long entries shouldn't make the panel wider than it is
            prototypeDisplayValue = "Cartoon: helix spirals, strand"
            addActionListener { on(selectedIndex) }
        }
        private fun slider(name: String, min: Double, max: Double, step: Double, value: Double, fmt: (Double) -> String, on: (Double) -> Unit): JComponent {
            val box = column()
            val out = JLabel(fmt(value)).apply { foreground = Ui.TEXT; font = Font(Font.MONOSPACED, Font.PLAIN, 12) }
            val head = row(JLabel(name).apply { foreground = Ui.HAZE }).apply { add(Box.createHorizontalGlue()); add(out); border = BorderFactory.createEmptyBorder(8, 0, 0, 0) }
            head.maximumSize = Dimension(Int.MAX_VALUE, head.preferredSize.height)
            val steps = ((max - min) / step).roundToInt()
            val sl = JSlider(0, steps, ((value - min) / step).roundToInt().coerceIn(0, steps)).apply { isOpaque = false }
            sl.addChangeListener {
                val v = Math.round((min + sl.value * step) * 100) / 100.0
                out.text = fmt(v)
                on(v)
            }
            box.add(head); box.add(full(sl))
            return box
        }
        private fun logSlider(name: String, min: Int, max: Int, value: Int, on: (Int) -> Unit): JComponent {
            val box = column()
            val ratio = max.toDouble() / min
            fun toLen(p: Int): Int {
                val raw = min * Math.pow(ratio, p / 1000.0)
                val step = if (raw < 100) 5 else if (raw < 1000) 10 else 50
                return ((raw / step).roundToInt() * step).coerceIn(min, max)
            }
            val out = JLabel("$value residues").apply { foreground = Ui.TEXT; font = Font(Font.MONOSPACED, Font.PLAIN, 12) }
            val head = row(JLabel(name).apply { foreground = Ui.HAZE }).apply { add(Box.createHorizontalGlue()); add(out); border = BorderFactory.createEmptyBorder(8, 0, 0, 0) }
            head.maximumSize = Dimension(Int.MAX_VALUE, head.preferredSize.height)
            val sl = JSlider(0, 1000, (1000 * Math.log(value.toDouble() / min) / Math.log(ratio)).roundToInt().coerceIn(0, 1000)).apply { isOpaque = false }
            // Every change makes a new protein, so apply when the slider is let go
            sl.addChangeListener { out.text = "${toLen(sl.value)} residues"; if (!sl.valueIsAdjusting) on(toLen(sl.value)) }
            box.add(head); box.add(full(sl))
            return box
        }
    }

    // ---------- Create or edit a protein ----------
    inner class ProteinEditor(private val existingId: String?) : JDialog(this@MainWindow, if (existingId != null) "Edit protein" else "Create a protein", true) {
        private val entry = existingId?.let { Proteins.customJsonEntry(s.customJson, it) }
        private var fetched: StructureIO.Loaded? = null
        private val query = JTextField(14)
        private val fetchBtn = JButton("Fetch")
        private val fetchStatus = JLabel(html("PDB ID for an experimental structure (add :A,B to pick chains), or a UniProt ID for an AlphaFold prediction."))
        private val nameField = JTextField(entry?.optString("name") ?: "", 24)
        private val seq = JTextArea(entry?.optJSONArray("chains")?.let { a -> (0 until a.length()).joinToString(" / ") { a.getString(it) } } ?: "", 8, 40)
        private val copies = JSpinner(SpinnerNumberModel(entry?.optInt("copies", 1) ?: 1, 1, Proteins.MAX_COPIES, 1))
        private val status = JLabel(" ")

        init {
            val p = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS); border = BorderFactory.createEmptyBorder(16, 16, 16, 16) }
            fun lab(t: String) = JLabel(t).apply { foreground = Ui.HAZE; alignmentX = Component.LEFT_ALIGNMENT; border = BorderFactory.createEmptyBorder(10, 0, 4, 0) }
            fun left(c: JComponent) = c.apply { alignmentX = Component.LEFT_ALIGNMENT }
            p.add(lab("Load from the PDB or AlphaFold (optional)"))
            query.toolTipText = "1UBQ, 2HHB:A,B or P69905"
            p.add(left(JPanel().apply { layout = BoxLayout(this, BoxLayout.X_AXIS); add(query); add(Box.createHorizontalStrut(8)); add(fetchBtn) }))
            p.add(left(fetchStatus.apply { foreground = Ui.HAZE; font = font.deriveFont(12f) }))
            p.add(lab("Name")); p.add(left(nameField))
            p.add(lab("Sequence: one-letter codes or FASTA, / between chains"))
            seq.font = Font(Font.MONOSPACED, Font.PLAIN, 13); seq.lineWrap = true
            p.add(left(JScrollPane(seq)))
            p.add(lab("Copies in the box (to watch them interact)")); p.add(left(copies))
            p.add(Box.createVerticalStrut(8))
            p.add(left(JPanel().apply {
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                add(JButton("Random fill").apply { addActionListener { seq.text = Proteins.random(60, 1).chains[0]; if (nameField.text.isBlank()) nameField.text = "My helix bundle" } })
                add(Box.createHorizontalStrut(8)); add(JButton("Clear").apply { addActionListener { seq.text = "" } })
            }))
            p.add(Box.createVerticalStrut(8)); p.add(left(status.apply { font = font.deriveFont(12f) }))
            p.add(Box.createVerticalStrut(12))
            val save = JButton("Save and fold").apply { background = Ui.HYDRO; foreground = Ui.INK }
            p.add(left(JPanel().apply { layout = BoxLayout(this, BoxLayout.X_AXIS); add(Box.createHorizontalGlue()); add(JButton("Cancel").apply { addActionListener { dispose() } }); add(Box.createHorizontalStrut(8)); add(save) }))
            contentPane = p
            fetchBtn.addActionListener { fetch() }
            save.addActionListener { saveProtein() }
            seq.document.addDocumentListener(object : DocumentListener {
                override fun insertUpdate(e: DocumentEvent) = validateSeq().let { }
                override fun removeUpdate(e: DocumentEvent) = validateSeq().let { }
                override fun changedUpdate(e: DocumentEvent) = validateSeq().let { }
            })
            copies.addChangeListener { validateSeq() }
            validateSeq()
            pack(); setLocationRelativeTo(this@MainWindow)
        }

        private fun fetch() {
            val q = query.text.trim()
            StructureIO.queryProblem(q)?.let { fetchStatus.foreground = Ui.ROSE; fetchStatus.text = html(it); return }
            fetchBtn.isEnabled = false; fetchStatus.foreground = Ui.HAZE; fetchStatus.text = "Downloading $q…"
            object : SwingWorker<StructureIO.Loaded, Unit>() {
                override fun doInBackground() = StructureIO.fetch(q)
                override fun done() {
                    fetchBtn.isEnabled = true
                    try {
                        val loaded = get()
                        fetched = loaded
                        nameField.text = loaded.title.take(60)
                        seq.text = loaded.chains.joinToString(" / ") { it.seq }
                        fetchStatus.foreground = Ui.HAZE
                        fetchStatus.text = html("Loaded ${loaded.source}: ${loaded.chains.size} chain${if (loaded.chains.size > 1) "s" else ""}, ${loaded.length} residues with known positions.")
                    } catch (e: Exception) {
                        val cause = e.cause ?: e
                        fetchStatus.foreground = Ui.ROSE
                        fetchStatus.text = html(when (cause) {
                            is java.net.UnknownHostException -> "No internet connection. Connect and try again."
                            is java.io.IOException -> cause.message ?: "The download failed. Try again."
                            else -> "That file couldn't be read. Try another ID."
                        })
                    }
                    pack()
                }
            }.execute()
        }

        private fun validateSeq(): Proteins.Parsed {
            val parsed = Proteins.parse(seq.text)
            val n = copies.value as Int
            val total = parsed.chains.sumOf { it.length } * n
            status.foreground = Ui.HAZE
            status.text = html(when {
                parsed.error != null -> { status.foreground = Ui.ROSE; parsed.error!! }
                total > Proteins.MAX_RESIDUES -> { status.foreground = Ui.ROSE; "$total residues in total. The limit is ${Proteins.MAX_RESIDUES}; shorten it or use fewer copies." }
                else -> {
                    val f = fetched
                    val structure = when {
                        f == null -> ""
                        f.chains.map { it.seq } == parsed.chains -> " Folds toward ${f.source}."
                        else -> " The sequence no longer matches ${f.source}, so its structure won't be used."
                    }
                    val nCh = parsed.chains.size * n
                    "$total residues · $nCh chain${if (nCh > 1) "s" else ""}.$structure${slowHint(total)}"
                }
            })
            return parsed
        }

        private fun saveProtein() {
            val parsed = validateSeq()
            val n = copies.value as Int
            if (parsed.error != null || parsed.chains.sumOf { it.length } * n > Proteins.MAX_RESIDUES) return
            val title = nameField.text.trim().ifEmpty { "My protein" }
            // Keep a structure only if the sequence still matches it exactly
            var ca: FloatArray? = null; var source: String? = null
            val f = fetched
            if (f != null && f.chains.map { it.seq } == parsed.chains) { ca = f.ca; source = f.source }
            else if (f == null && entry != null) {
                val oldCa = entry.optString("ca").ifEmpty { null }; val oldSource = entry.optString("source").ifEmpty { null }
                val oldChains = entry.optJSONArray("chains")?.let { a -> (0 until a.length()).map { a.getString(it) } }
                if (oldCa != null && oldSource != null && oldChains == parsed.chains) { ca = CaCodec.decode(oldCa, parsed.chains.sumOf { it.length }); source = oldSource }
            }
            val (json, id) = Proteins.saveCustom(s.customJson, existingId, title, parsed.chains, n, ca, source)
            update(s.copy(customJson = json, protein = id))
            controls.refreshProtein()
            dispose()
        }
    }
}

private fun html(t: String) = "<html><body style='width: 290px'>$t</body></html>"

/** A paragraph that wraps to the width it's given (HTML labels don't, and ran off the panel's edge). */
class Note(t: String) : JTextArea() {
    init {
        // Setting the text mustn't scroll the panel to it (a text area's caret normally follows its text)
        (caret as? javax.swing.text.DefaultCaret)?.updatePolicy = javax.swing.text.DefaultCaret.NEVER_UPDATE
        isEditable = false; isFocusable = false; lineWrap = true; wrapStyleWord = true
        isOpaque = false; border = BorderFactory.createEmptyBorder(2, 0, 2, 0)
        foreground = Ui.HAZE; font = UIManager.getFont("Label.font").deriveFont(12f)
        alignmentX = Component.LEFT_ALIGNMENT
        text = t
    }
    // Width comes from the panel; height follows from the wrapped text
    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)
}
private fun note(t: String) = Note(t)
private fun button(t: String, primary: Boolean = false, on: () -> Unit) = JButton(t).apply {
    if (primary) { background = Ui.HYDRO; foreground = Ui.INK }
    addActionListener { on() }
}
private fun redoxLabel(r: Double) = when {
    r <= -0.6 -> "Strongly reducing"; r < -0.15 -> "Reducing"; r <= 0.15 -> "Balanced"; r < 0.6 -> "Oxidizing"; else -> "Strongly oxidizing"
}
private fun assistText(a: Int) = when (a) {
    0 -> "Off: the physics on its own."
    3 -> "Maximum: drags every residue to its place. Folds in seconds, but it's no longer a simulation."
    else -> "Adds forces toward the fold: with a known structure, pulls toward it; without one, squeezes the chain and makes it stickier."
}
private fun slowHint(len: Int) = when {
    len > 1500 -> " Very large: it will fold slowly."
    len > 600 -> " Large: expect slower motion."
    else -> ""
}

/** A small folded chain in the app's colours, for the window icon. */
fun appIcon(size: Int): BufferedImage {
    val img = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
    val g = img.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    val k = size / 108f
    g.color = Ui.INK; g.fillRoundRect(0, 0, size, size, (24 * k).toInt(), (24 * k).toInt())
    val pts = listOf(34 to 64, 42 to 45, 60 to 38, 74 to 51, 68 to 70, 50 to 74)
    val cols = listOf(0x74CFC0, 0xF0A44B, 0x8EA2FF, 0xF0A44B, 0xFF7D8E, 0xF3D34A)
    g.color = Color(0x5D6784); g.stroke = java.awt.BasicStroke(4 * k, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND)
    for (i in 1 until pts.size) g.drawLine((pts[i - 1].first * k).toInt(), (pts[i - 1].second * k).toInt(), (pts[i].first * k).toInt(), (pts[i].second * k).toInt())
    for ((i, p) in pts.withIndex()) { g.color = Color(cols[i]); val r = 8 * k; g.fill(java.awt.geom.Ellipse2D.Float(p.first * k - r, p.second * k - r, 2 * r, 2 * r)) }
    g.dispose()
    return img
}

fun main(args: Array<String>) {
    // Direct3D drawing, no background erase between frames (it flickers), native popups over the native canvas
    System.setProperty("sun.java2d.d3d", "true")
    System.setProperty("sun.awt.noerasebackground", "true")
    if (args.contains("--write-icon")) { IconFile.write(File(args[args.indexOf("--write-icon") + 1])); return }
    FlatDarkLaf.setup()
    UIManager.put("Component.accentColor", Ui.HYDRO)
    UIManager.put("Panel.background", Ui.PANEL)
    UIManager.put("ScrollPane.background", Ui.PANEL)
    UIManager.put("Viewport.background", Ui.PANEL)
    JPopupMenu.setDefaultLightWeightPopupEnabled(false)
    ToolTipManager.sharedInstance().isLightWeightPopupEnabled = false
    Java2DCanvas.install()
    if (GraphicsEnvironment.isHeadless()) { System.err.println("Hydrophobic Collapse needs a screen."); return }
    val asWallpaper = args.contains("--wallpaper")
    SwingUtilities.invokeLater {
        val w = MainWindow(asWallpaper)
        if (!asWallpaper) w.isVisible = true
    }
}
