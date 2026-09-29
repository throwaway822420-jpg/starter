package com.hydrophobiccollapse.desktop

import com.hydrophobiccollapse.gfx.Canvas
import com.hydrophobiccollapse.gfx.Paint
import com.hydrophobiccollapse.gfx.Path
import com.hydrophobiccollapse.gfx.RadialGradient
import com.hydrophobiccollapse.gfx.TextMeasurer
import com.hydrophobiccollapse.gfx.Typeface
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.GraphicsEnvironment
import java.awt.MultipleGradientPaint
import java.awt.RadialGradientPaint
import java.awt.RenderingHints
import java.awt.Stroke
import java.awt.font.FontRenderContext
import java.awt.font.TextAttribute
import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D
import java.awt.geom.Path2D
import java.awt.geom.Rectangle2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage

/**
 * Draws the shared renderer's calls with Java2D.
 * A frame makes thousands of calls, so strokes, colours and gradients are cached and the
 * Graphics2D state is only touched when it actually changes.
 */
class Java2DCanvas(private val g: Graphics2D) : Canvas {
    init {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_SPEED)
    }
    private val line = Line2D.Float()
    private val ellipse = Ellipse2D.Float()
    private val rect = Rectangle2D.Float()
    private val round = RoundRectangle2D.Float()
    private val path2 = Path2D.Float()

    private var curArgb = 0
    private var curColor: Color? = null
    private var curGradient: RadialGradient? = null
    private var curStroke: Stroke? = null

    private fun setColor(argb: Int) {
        if (curGradient == null && curColor != null && argb == curArgb) return
        curArgb = argb; curGradient = null
        val c = color(argb); curColor = c; g.color = c
    }

    private fun prepare(p: Paint) {
        val sh = p.shader
        if (sh != null) {
            if (sh !== curGradient) { curGradient = sh; curColor = null; g.paint = gradient(sh) }
        } else setColor(p.color)
        if (p.style == Paint.Style.STROKE) setStroke(p)
    }
    private fun setStroke(p: Paint) {
        val s = stroke(p.strokeWidth, p.strokeCap == Paint.Cap.ROUND, p.pathEffect?.intervals)
        if (s !== curStroke) { curStroke = s; g.stroke = s }
    }
    private fun paintShape(s: java.awt.Shape, p: Paint) { antialias(); prepare(p); if (p.style == Paint.Style.STROKE) g.draw(s) else g.fill(s) }

    override fun drawRect(left: Float, top: Float, right: Float, bottom: Float, paint: Paint) {
        val sh = paint.shader
        if (sh != null && paint.style == Paint.Style.FILL) {
            // The background gradient: painted once into an image, then copied each frame
            g.drawImage(gradientImage(sh, Math.round(right - left), Math.round(bottom - top)), Math.round(left), Math.round(top), null)
            return
        }
        rect.setRect(left, top, right - left, bottom - top)
        if (paint.style == Paint.Style.FILL) {
            // Filled rectangles are axis-aligned (bars, and the specks of water): no need to anti-alias them
            setColor(paint.color)
            if (aa) { g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF); aa = false }
            g.fill(rect)
            return
        }
        paintShape(rect, paint)
    }
    private var aa = true
    private fun antialias() { if (!aa) { g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON); aa = true } }
    override fun drawCircle(cx: Float, cy: Float, radius: Float, paint: Paint) {
        ellipse.setFrame(cx - radius, cy - radius, 2 * radius, 2 * radius); paintShape(ellipse, paint)
    }
    override fun drawLine(x0: Float, y0: Float, x1: Float, y1: Float, paint: Paint) {
        // Lines are always stroked, whatever the paint's style (as on Android)
        val sh = paint.shader
        if (sh != null) { if (sh !== curGradient) { curGradient = sh; curColor = null; g.paint = gradient(sh) } } else setColor(paint.color)
        setStroke(paint)
        antialias()
        line.setLine(x0, y0, x1, y1); g.draw(line)
    }
    override fun drawRoundRect(left: Float, top: Float, right: Float, bottom: Float, rx: Float, ry: Float, paint: Paint) {
        round.setRoundRect(left, top, right - left, bottom - top, 2 * rx, 2 * ry); paintShape(round, paint)
    }
    override fun drawPath(path: Path, paint: Paint) {
        path2.reset()
        val xs = path.xs; val ys = path.ys
        for (k in 0 until path.size) if (k == 0) path2.moveTo(xs[0], ys[0]) else path2.lineTo(xs[k], ys[k])
        if (path.closed) path2.closePath()
        paintShape(path2, paint)
    }
    override fun drawText(text: String, x: Float, y: Float, paint: Paint) {
        g.font = font(paint); setColor(paint.color)
        val x0 = when (paint.textAlign) {
            Paint.Align.LEFT -> x
            Paint.Align.CENTER -> x - measure(text, paint) / 2
            Paint.Align.RIGHT -> x - measure(text, paint)
        }
        g.drawString(text, x0, y)
    }

    companion object {
        private val frc = FontRenderContext(null, true, true)
        private val available: Set<String> by lazy { GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.toSet() }
        // Windows fonts first, then whatever the system has
        private val sansFamily by lazy { listOf("Segoe UI", "Inter", "Helvetica Neue", "Arial").firstOrNull { it in available } ?: Font.SANS_SERIF }
        private val monoFamily by lazy { listOf("Cascadia Mono", "Consolas", "DejaVu Sans Mono", "Menlo").firstOrNull { it in available } ?: Font.MONOSPACED }
        // Caches are touched from the drawing thread and, for text measurement, the UI thread
        private val fonts = HashMap<Long, Font>()
        private val colors = HashMap<Int, Color>()
        private val strokes = HashMap<Long, BasicStroke>()
        private val gradients = HashMap<RadialGradient, RadialGradientPaint>()

        @Synchronized fun font(p: Paint): Font {
            val t: Typeface = p.typeface
            val key = (java.lang.Float.floatToIntBits(p.textSize).toLong() shl 8) or (if (t.mono) 1L else 0L) or (if (t.bold) 2L else 0L) or
                ((Math.round(p.letterSpacing * 100).toLong() and 0x3F) shl 2)
            return fonts.getOrPut(key) {
                val base = Font(if (t.mono) monoFamily else sansFamily, if (t.bold) Font.BOLD else Font.PLAIN, 12).deriveFont(p.textSize)
                if (p.letterSpacing != 0f) base.deriveFont(mapOf(TextAttribute.TRACKING to p.letterSpacing)) else base
            }
        }
        @Synchronized private fun color(argb: Int): Color {
            if (colors.size > 4096) colors.clear()     // fog makes many shades; keep the cache bounded
            return colors.getOrPut(argb) { Color(argb, true) }
        }
        /** Strokes, with widths rounded to 1/16 px so nearby widths share one object. */
        @Synchronized private fun stroke(width: Float, round: Boolean, dash: FloatArray?): BasicStroke {
            val q = Math.round(width * 16).coerceAtLeast(0)
            val key = (q.toLong() shl 2) or (if (round) 1L else 0L) or (if (dash != null) 2L else 0L) or
                (if (dash != null) (dash.contentHashCode().toLong() shl 34) else 0L)
            if (strokes.size > 2048) strokes.clear()
            return strokes.getOrPut(key) {
                val cap = if (round) BasicStroke.CAP_ROUND else BasicStroke.CAP_BUTT
                if (dash != null) BasicStroke(q / 16f, cap, BasicStroke.JOIN_ROUND, 10f, dash, 0f) else BasicStroke(q / 16f, cap, BasicStroke.JOIN_ROUND)
            }
        }
        private var gradientKey: Any? = null
        private var gradientImg: BufferedImage? = null
        @Synchronized private fun gradientImage(sh: RadialGradient, w: Int, h: Int): BufferedImage {
            val key = Triple(sh, w, h)
            gradientImg?.let { if (key == gradientKey) return it }
            val img = BufferedImage(maxOf(1, w), maxOf(1, h), BufferedImage.TYPE_INT_RGB)
            val ig = img.createGraphics()
            ig.paint = gradient(sh); ig.fillRect(0, 0, img.width, img.height); ig.dispose()
            gradientKey = key; gradientImg = img
            return img
        }
        @Synchronized private fun gradient(sh: RadialGradient): RadialGradientPaint {
            if (gradients.size > 16) gradients.clear()
            return gradients.getOrPut(sh) {
                RadialGradientPaint(sh.cx, sh.cy, sh.radius, sh.stops, Array(sh.colors.size) { Color(sh.colors[it], true) },
                    MultipleGradientPaint.CycleMethod.NO_CYCLE)
            }
        }
        fun measure(text: String, p: Paint): Float = font(p).getStringBounds(text, frc).width.toFloat()

        /** Install Java2D text measurement for the shared renderer. */
        fun install() {
            Paint.measurer = object : TextMeasurer {
                override fun width(text: String, paint: Paint) = measure(text, paint)
            }
        }
    }
}
