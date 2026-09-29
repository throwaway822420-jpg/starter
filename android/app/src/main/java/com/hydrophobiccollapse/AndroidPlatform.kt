package com.hydrophobiccollapse

import android.content.Context
import android.content.SharedPreferences
import android.graphics.DashPathEffect as ADash
import android.graphics.RadialGradient as AGradient
import android.graphics.Shader as AShader
import android.graphics.Typeface as ATypeface
import android.view.MotionEvent
import com.hydrophobiccollapse.gfx.Canvas
import com.hydrophobiccollapse.gfx.Paint
import com.hydrophobiccollapse.gfx.Path
import com.hydrophobiccollapse.gfx.PointerEvent
import com.hydrophobiccollapse.gfx.TextMeasurer

// The Android side of the shared code: settings storage, drawing and touch.

fun Settings.Companion.prefs(c: Context): SharedPreferences = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
fun Settings.Companion.load(p: SharedPreferences) = load(PrefsStore(p))
fun Settings.save(p: SharedPreferences) = save(PrefsStore(p))
/** Ask any running simulation (the wallpaper or the preview) to heat or restart. */
fun Settings.Companion.sendCommand(p: SharedPreferences, key: String) { p.edit().putLong(key, System.nanoTime()).apply() }

class PrefsStore(private val p: SharedPreferences) : KeyValueStore {
    private var edit: SharedPreferences.Editor? = null
    private fun e() = edit ?: p.edit().also { edit = it }
    override fun getString(key: String, default: String) = p.getString(key, default) ?: default
    override fun getInt(key: String, default: Int) = p.getInt(key, default)
    override fun getFloat(key: String, default: Float) = p.getFloat(key, default)
    override fun getBoolean(key: String, default: Boolean) = p.getBoolean(key, default)
    override fun putString(key: String, value: String) { e().putString(key, value) }
    override fun putInt(key: String, value: Int) { e().putInt(key, value) }
    override fun putFloat(key: String, value: Float) { e().putFloat(key, value) }
    override fun putBoolean(key: String, value: Boolean) { e().putBoolean(key, value) }
    override fun commit() { edit?.apply(); edit = null }
}

fun MotionEvent.toPointer() = PointerEvent(
    when (actionMasked) {
        MotionEvent.ACTION_DOWN -> PointerEvent.ACTION_DOWN
        MotionEvent.ACTION_UP -> PointerEvent.ACTION_UP
        MotionEvent.ACTION_MOVE -> PointerEvent.ACTION_MOVE
        MotionEvent.ACTION_POINTER_DOWN -> PointerEvent.ACTION_POINTER_DOWN
        else -> PointerEvent.ACTION_CANCEL
    }, x, y, eventTime)

/** Draws the shared renderer's calls with android.graphics. */
class AndroidCanvas(val c: android.graphics.Canvas) : Canvas {
    private val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val path = android.graphics.Path()
    private var lastGradient: com.hydrophobiccollapse.gfx.RadialGradient? = null
    private var gradient: AGradient? = null
    private var lastDash: com.hydrophobiccollapse.gfx.DashPathEffect? = null
    private var dash: ADash? = null

    private fun apply(src: Paint): android.graphics.Paint {
        p.color = src.color
        p.style = if (src.style == Paint.Style.STROKE) android.graphics.Paint.Style.STROKE else android.graphics.Paint.Style.FILL
        p.strokeWidth = src.strokeWidth
        p.strokeCap = if (src.strokeCap == Paint.Cap.ROUND) android.graphics.Paint.Cap.ROUND else android.graphics.Paint.Cap.BUTT
        p.textSize = src.textSize
        p.letterSpacing = src.letterSpacing
        p.typeface = typeface(src.typeface)
        p.textAlign = when (src.textAlign) { Paint.Align.LEFT -> android.graphics.Paint.Align.LEFT; Paint.Align.CENTER -> android.graphics.Paint.Align.CENTER; Paint.Align.RIGHT -> android.graphics.Paint.Align.RIGHT }
        val d = src.pathEffect
        if (d !== lastDash) { lastDash = d; dash = d?.let { ADash(it.intervals, it.phase) } }
        p.pathEffect = dash
        val g = src.shader
        if (g !== lastGradient) { lastGradient = g; gradient = g?.let { AGradient(it.cx, it.cy, it.radius, it.colors, it.stops, AShader.TileMode.CLAMP) } }
        p.shader = gradient
        return p
    }
    override fun drawRect(left: Float, top: Float, right: Float, bottom: Float, paint: Paint) = c.drawRect(left, top, right, bottom, apply(paint))
    override fun drawCircle(cx: Float, cy: Float, radius: Float, paint: Paint) = c.drawCircle(cx, cy, radius, apply(paint))
    override fun drawLine(x0: Float, y0: Float, x1: Float, y1: Float, paint: Paint) = c.drawLine(x0, y0, x1, y1, apply(paint))
    override fun drawRoundRect(left: Float, top: Float, right: Float, bottom: Float, rx: Float, ry: Float, paint: Paint) =
        c.drawRoundRect(left, top, right, bottom, rx, ry, apply(paint))
    override fun drawPath(path: Path, paint: Paint) {
        this.path.reset()
        val xs = path.xs; val ys = path.ys
        for (k in 0 until path.size) if (k == 0) this.path.moveTo(xs[0], ys[0]) else this.path.lineTo(xs[k], ys[k])
        if (path.closed) this.path.close()
        c.drawPath(this.path, apply(paint))
    }
    override fun drawText(text: String, x: Float, y: Float, paint: Paint) = c.drawText(text, x, y, apply(paint))

    companion object {
        private val mono = ATypeface.MONOSPACE
        private val sans = ATypeface.create("sans-serif", ATypeface.NORMAL)
        private val sansBold = ATypeface.create("sans-serif-medium", ATypeface.NORMAL)
        fun typeface(t: com.hydrophobiccollapse.gfx.Typeface) = if (t.mono) mono else if (t.bold) sansBold else sans

        /** Install Android text measurement for the shared renderer. */
        fun install() {
            val mp = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            Paint.measurer = object : TextMeasurer {
                override fun width(text: String, paint: Paint): Float {
                    mp.textSize = paint.textSize; mp.letterSpacing = paint.letterSpacing; mp.typeface = typeface(paint.typeface)
                    return mp.measureText(text)
                }
            }
        }
    }
}
