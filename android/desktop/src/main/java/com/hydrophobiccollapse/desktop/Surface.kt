package com.hydrophobiccollapse.desktop

import com.hydrophobiccollapse.Simulation
import com.hydrophobiccollapse.gfx.PointerEvent
import com.hydrophobiccollapse.lowFrameRate
import java.awt.Canvas
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GraphicsEnvironment
import java.awt.Toolkit
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.awt.image.BufferedImage
import java.util.concurrent.locks.LockSupport
import kotlin.math.ceil
import kotlin.math.pow

/**
 * Where the simulation is drawn: a native (heavyweight) canvas with its own back buffer, drawn by the
 * [Animator] thread directly ("active rendering"), so frames don't queue behind Swing's repaint system and
 * the controls stay responsive while the physics runs.
 */
class SimSurface(private val sim: Simulation, interactive: Boolean) : Canvas() {
    /** Only the surface on show sizes the simulation. */
    @Volatile var active = false
        set(v) { field = v; if (v && width > 0) sim.resize(width, height) }

    init {
        background = Ui.INK
        ignoreRepaint = true
        addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) { if (active) sim.resize(width, height) }
        })
        if (interactive) {
            isFocusable = true
            val mouse = object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) { requestFocusInWindow(); send(PointerEvent.ACTION_DOWN, e) }
                override fun mouseDragged(e: MouseEvent) { send(PointerEvent.ACTION_MOVE, e) }
                override fun mouseReleased(e: MouseEvent) { send(PointerEvent.ACTION_UP, e) }
                override fun mouseWheelMoved(e: MouseWheelEvent) { sim.zoomBy(1.12.pow(-e.preciseWheelRotation)) }
            }
            addMouseListener(mouse); addMouseMotionListener(mouse); addMouseWheelListener(mouse)
        } else isFocusable = false
    }
    private fun send(action: Int, e: MouseEvent) { sim.onPointer(PointerEvent(action, e.x.toFloat(), e.y.toFloat(), e.`when`)) }

    // Swing may ask for a repaint (say, when uncovered); the animator repaints anyway
    override fun paint(g: Graphics) {}
    override fun update(g: Graphics) {}

    // The frame is drawn in memory first, then copied to the screen in one go. Anti-aliased shapes are
    // rasterized on the CPU either way; drawing them straight onto a Direct3D surface uploads each one to the
    // GPU separately, which for thousands of small shapes costs far more than one upload per frame.
    private val direct = System.getProperty("hc.render") == "direct"
    private var frame: BufferedImage? = null
    /** Time spent drawing the picture (not copying it to the screen), for the debug readout. */
    @Volatile var paintNanos = 0L

    private fun drawFrame(g: Graphics2D) {
        if (direct) { sim.draw(Java2DCanvas(g)); return }
        // Match the screen's pixels on scaled (HiDPI) displays
        val sx = g.transform.scaleX; val sy = g.transform.scaleY
        val pw = ceil(width * sx).toInt(); val ph = ceil(height * sy).toInt()
        var img = frame
        if (img == null || img.width != pw || img.height != ph) { img = BufferedImage(pw, ph, BufferedImage.TYPE_INT_RGB); frame = img }
        val ig = img.createGraphics()
        val t0 = System.nanoTime()
        try { ig.scale(sx, sy); sim.draw(Java2DCanvas(ig)) } finally { ig.dispose() }
        paintNanos += System.nanoTime() - t0
        val t = g.transform
        g.scale(1 / sx, 1 / sy)
        g.drawImage(img, 0, 0, null)
        g.transform = t
    }

    /** Draws one frame; false if the canvas isn't on screen (being moved between windows, or hidden). */
    fun render(): Boolean {
        if (!isDisplayable || !isShowing || width <= 0 || height <= 0) return false
        return try {
            var bs = bufferStrategy
            if (bs == null) { createBufferStrategy(2); bs = bufferStrategy ?: return false }
            do {
                do {
                    val g = bs.drawGraphics as Graphics2D
                    try { drawFrame(g) } finally { g.dispose() }
                } while (bs.contentsRestored())
                bs.show()
            } while (bs.contentsLost())
            Toolkit.getDefaultToolkit().sync()
            true
        } catch (e: IllegalStateException) {
            false      // the native window went away mid-frame (full-screen switch, wallpaper on or off)
        }
    }
}

/**
 * The frame loop: advance the simulation by the real time that passed, draw, then sleep until the next
 * frame is due. Runs at the monitor's refresh rate (up to 120 Hz), or 30 fps in battery-saver and Extreme
 * modes. Waits are precise to about a millisecond: with 1 ms Windows timers, then a short spin.
 */
class Animator(private val sim: Simulation, private val surface: () -> SimSurface?) : Thread("hc-render") {
    @Volatile var running = true
    /** Nothing to show (minimized, or the wallpaper is hidden behind a full-screen window). */
    @Volatile var paused = false
    /** Called every frame, on this thread, before drawing (the wallpaper's mouse parallax). */
    @Volatile var beforeFrame: (() -> Unit)? = null

    init { isDaemon = true; priority = NORM_PRIORITY + 1 }

    private fun refreshHz(): Int {
        if (GraphicsEnvironment.isHeadless()) return 60
        val hz = runCatching { GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.displayMode.refreshRate }.getOrDefault(60)
        return if (hz < 30) 60 else hz.coerceAtMost(120)
    }

    override fun run() {
        WinDesktop.fineTimers(true)
        var hz = refreshHz(); var hzCheck = 0L
        var last = System.nanoTime()
        var next = last
        // With -Dhc.debug, report frames per second and the slowest frame every few seconds
        val debug = System.getProperty("hc.debug") != null
        var frames = 0; var worst = 0L; var since = last; var tUpdate = 0L; var tDraw = 0L
        try {
            while (running) {
                val now = System.nanoTime()
                if (now - hzCheck > 5_000_000_000L) { hz = refreshHz(); hzCheck = now }
                val period = 1_000_000_000L / (if (sim.settings.lowFrameRate()) 30 else hz)
                if (paused) { LockSupport.parkNanos(50_000_000L); last = System.nanoTime(); next = last; continue }
                val dt = (now - last) / 1e9
                if (debug) {
                    frames++; worst = maxOf(worst, now - last)
                    if (now - since > 5_000_000_000L) {
                        val paint = surface()?.let { s -> s.paintNanos.also { s.paintNanos = 0 } } ?: 0L
                        System.err.println("fps %.1f, slowest frame %.1f ms, per frame: simulation %.1f ms, painting %.1f ms, to screen %.1f ms".format(
                            frames * 1e9 / (now - since), worst / 1e6, tUpdate / 1e6 / frames, paint / 1e6 / frames, (tDraw - paint) / 1e6 / frames))
                        frames = 0; worst = 0; since = now; tUpdate = 0; tDraw = 0
                    }
                }
                last = now
                val t0 = System.nanoTime()
                sim.update(dt)
                beforeFrame?.invoke()
                val t1 = System.nanoTime()
                surface()?.render()
                if (debug) { tUpdate += t1 - t0; tDraw += System.nanoTime() - t1 }
                // Sleep until the next frame is due; yield through the last millisecond instead of oversleeping
                next += period
                if (System.nanoTime() - next > period) next = System.nanoTime()      // fell behind: don't try to catch up
                while (true) {
                    val left = next - System.nanoTime()
                    if (left <= 0) break
                    if (left > 1_000_000L) LockSupport.parkNanos(left - 700_000L) else yield()
                }
            }
        } finally {
            WinDesktop.fineTimers(false)
        }
    }
}
