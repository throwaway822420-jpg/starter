package com.hydrophobiccollapse.desktop

import com.hydrophobiccollapse.gfx.Canvas
import com.hydrophobiccollapse.gfx.Paint
import com.hydrophobiccollapse.gfx.Path
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
import java.awt.font.FontRenderContext
import java.awt.font.TextAttribute
import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D
import java.awt.geom.Path2D
import java.awt.geom.Rectangle2D
import java.awt.geom.RoundRectangle2D

/** Draws the shared renderer's calls with Java2D. */
class Java2DCanvas(private val g: Graphics2D) : Canvas {
    init {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
    }
    private val line = Line2D.Float()
    private val ellipse = Ellipse2D.Float()
    private val rect = Rectangle2D.Float()
    private val round = RoundRectangle2D.Float()
    private val path2 = Path2D.Float()

    private fun color(argb: Int) = Color(argb, true)

    private fun prepare(p: Paint) {
        val sh = p.shader
        if (sh != null) {
            g.paint = RadialGradientPaint(sh.cx, sh.cy, sh.radius, sh.stops, Array(sh.colors.size) { color(sh.colors[it]) },
                MultipleGradientPaint.CycleMethod.NO_CYCLE)
        } else g.color = color(p.color)
        if (p.style == Paint.Style.STROKE) {
            val cap = if (p.strokeCap == Paint.Cap.ROUND) BasicStroke.CAP_ROUND else BasicStroke.CAP_BUTT
            val dash = p.pathEffect?.intervals
            g.stroke = if (dash != null) BasicStroke(p.strokeWidth, cap, BasicStroke.JOIN_ROUND, 10f, dash, 0f)
                       else BasicStroke(p.strokeWidth, cap, BasicStroke.JOIN_ROUND)
        }
    }
    private fun paintShape(s: java.awt.Shape, p: Paint) { prepare(p); if (p.style == Paint.Style.STROKE) g.draw(s) else g.fill(s) }

    override fun drawRect(left: Float, top: Float, right: Float, bottom: Float, paint: Paint) {
        rect.setRect(left, top, right - left, bottom - top); paintShape(rect, paint)
    }
    override fun drawCircle(cx: Float, cy: Float, radius: Float, paint: Paint) {
        ellipse.setFrame(cx - radius, cy - radius, 2 * radius, 2 * radius); paintShape(ellipse, paint)
    }
    override fun drawLine(x0: Float, y0: Float, x1: Float, y1: Float, paint: Paint) {
        // Lines are always stroked, whatever the paint's style (as on Android)
        prepare(paint)
        val cap = if (paint.strokeCap == Paint.Cap.ROUND) BasicStroke.CAP_ROUND else BasicStroke.CAP_BUTT
        val dash = paint.pathEffect?.intervals
        g.stroke = if (dash != null) BasicStroke(paint.strokeWidth, cap, BasicStroke.JOIN_ROUND, 10f, dash, 0f)
                   else BasicStroke(paint.strokeWidth, cap, BasicStroke.JOIN_ROUND)
        line.setLine(x0, y0, x1, y1); g.draw(line)
    }
    override fun drawRoundRect(left: Float, top: Float, right: Float, bottom: Float, rx: Float, ry: Float, paint: Paint) {
        round.setRoundRect(left, top, right - left, bottom - top, 2 * rx, 2 * ry); paintShape(round, paint)
    }
    override fun drawPath(path: Path, paint: Paint) {
        path2.reset()
        for (k in path.xs.indices) if (k == 0) path2.moveTo(path.xs[0], path.ys[0]) else path2.lineTo(path.xs[k], path.ys[k])
        if (path.closed) path2.closePath()
        paintShape(path2, paint)
    }
    override fun drawText(text: String, x: Float, y: Float, paint: Paint) {
        val f = font(paint)
        g.font = f; g.color = color(paint.color)
        val w = measure(text, paint)
        val x0 = when (paint.textAlign) { Paint.Align.LEFT -> x; Paint.Align.CENTER -> x - w / 2; Paint.Align.RIGHT -> x - w }
        g.drawString(text, x0, y)
    }

    companion object {
        private val frc = FontRenderContext(null, true, true)
        private val available: Set<String> by lazy { GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.toSet() }
        // Windows fonts first, then whatever the system has
        private val sansFamily by lazy { listOf("Segoe UI", "Inter", "Helvetica Neue", "Arial").firstOrNull { it in available } ?: Font.SANS_SERIF }
        private val monoFamily by lazy { listOf("Cascadia Mono", "Consolas", "DejaVu Sans Mono", "Menlo").firstOrNull { it in available } ?: Font.MONOSPACED }
        private val cache = HashMap<Long, Font>()

        fun font(p: Paint): Font {
            val t: Typeface = p.typeface
            val key = (java.lang.Float.floatToIntBits(p.textSize).toLong() shl 8) or (if (t.mono) 1L else 0L) or (if (t.bold) 2L else 0L) or
                ((Math.round(p.letterSpacing * 100).toLong() and 0x3F) shl 2)
            return cache.getOrPut(key) {
                val base = Font(if (t.mono) monoFamily else sansFamily, if (t.bold) Font.BOLD else Font.PLAIN, 12).deriveFont(p.textSize)
                if (p.letterSpacing != 0f) base.deriveFont(mapOf(TextAttribute.TRACKING to p.letterSpacing)) else base
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
