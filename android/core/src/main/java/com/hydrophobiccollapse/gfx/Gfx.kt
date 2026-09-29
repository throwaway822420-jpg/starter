package com.hydrophobiccollapse.gfx

// A small drawing layer so the renderer in Simulation runs on Android (android.graphics) and on the
// desktop (Java2D). It mirrors the few pieces of the Android graphics API the renderer uses; each
// platform supplies a Canvas and a TextMeasurer.

/** Colours are ARGB ints, as on Android. */
object Color {
    /** Hue in degrees, saturation and value 0…1, to opaque ARGB. */
    fun hsvToColor(h: Float, s: Float, v: Float): Int {
        val hh = ((h % 360f) + 360f) % 360f / 60f
        val i = hh.toInt(); val f = hh - i
        val p = v * (1 - s); val q = v * (1 - s * f); val t = v * (1 - s * (1 - f))
        val (r, g, b) = when (i) { 0 -> Triple(v, t, p); 1 -> Triple(q, v, p); 2 -> Triple(p, v, t); 3 -> Triple(p, q, v); 4 -> Triple(t, p, v); else -> Triple(v, p, q) }
        return (0xFF shl 24) or (Math.round(r * 255) shl 16) or (Math.round(g * 255) shl 8) or Math.round(b * 255)
    }
}

class Typeface private constructor(val mono: Boolean, val bold: Boolean) {
    companion object {
        const val NORMAL = 0
        val MONOSPACE = Typeface(mono = true, bold = false)
        /** "sans-serif" or "sans-serif-medium" (drawn bold on the desktop). */
        fun create(family: String, @Suppress("UNUSED_PARAMETER") style: Int) = Typeface(mono = false, bold = family.endsWith("medium"))
    }
}

class DashPathEffect(val intervals: FloatArray, @Suppress("unused") val phase: Float)

object Shader { enum class TileMode { CLAMP } }

class RadialGradient(val cx: Float, val cy: Float, val radius: Float, val colors: IntArray, val stops: FloatArray,
                     @Suppress("unused") val tile: Shader.TileMode)

/** Measures text; set once by the platform. */
interface TextMeasurer {
    fun width(text: String, paint: Paint): Float
}

class Paint(@Suppress("UNUSED_PARAMETER") flags: Int = 0) {
    enum class Style { FILL, STROKE }
    enum class Cap { BUTT, ROUND }
    enum class Align { LEFT, CENTER, RIGHT }

    var color = 0xFF000000.toInt()
    var style = Style.FILL
    var strokeWidth = 1f
    var strokeCap = Cap.BUTT
    var typeface: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    var textSize = 12f
    var textAlign = Align.LEFT
    var letterSpacing = 0f
    var pathEffect: DashPathEffect? = null
    var shader: RadialGradient? = null

    fun measureText(text: String): Float = measurer.width(text, this)

    /** How many characters of [text] fit in [maxWidth] (Android's breakText, forwards only). */
    fun breakText(text: String, @Suppress("UNUSED_PARAMETER") forwards: Boolean, maxWidth: Float, @Suppress("UNUSED_PARAMETER") measured: FloatArray?): Int {
        var lo = 0; var hi = text.length
        while (lo < hi) { val mid = (lo + hi + 1) / 2; if (measureText(text.substring(0, mid)) <= maxWidth) lo = mid else hi = mid - 1 }
        return lo
    }

    companion object {
        const val ANTI_ALIAS_FLAG = 1
        /** Set by the platform before drawing. The fallback guesses widths, for tests without a screen. */
        var measurer: TextMeasurer = object : TextMeasurer {
            override fun width(text: String, paint: Paint) = text.length * paint.textSize * (if (paint.typeface.mono) 0.6f else 0.52f)
        }
    }
}

class Path {
    val xs = ArrayList<Float>(); val ys = ArrayList<Float>()
    var closed = false; private set
    fun reset() { xs.clear(); ys.clear(); closed = false }
    fun moveTo(x: Float, y: Float) { reset(); xs.add(x); ys.add(y) }
    fun lineTo(x: Float, y: Float) { xs.add(x); ys.add(y) }
    fun close() { closed = true }
}

/** What the renderer draws on. */
interface Canvas {
    fun drawRect(left: Float, top: Float, right: Float, bottom: Float, paint: Paint)
    fun drawCircle(cx: Float, cy: Float, radius: Float, paint: Paint)
    fun drawLine(x0: Float, y0: Float, x1: Float, y1: Float, paint: Paint)
    fun drawRoundRect(left: Float, top: Float, right: Float, bottom: Float, rx: Float, ry: Float, paint: Paint)
    fun drawPath(path: Path, paint: Paint)
    fun drawText(text: String, x: Float, y: Float, paint: Paint)
}

/** A pointer (finger or mouse) event, in the terms of Android's MotionEvent. */
class PointerEvent(val actionMasked: Int, val x: Float, val y: Float, val eventTime: Long) {
    fun getPointerId(@Suppress("UNUSED_PARAMETER") index: Int) = 0
    companion object {
        const val ACTION_DOWN = 0
        const val ACTION_UP = 1
        const val ACTION_MOVE = 2
        const val ACTION_CANCEL = 3
        const val ACTION_POINTER_DOWN = 5
    }
}
