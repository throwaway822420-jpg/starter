package com.hydrophobiccollapse

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View

/** Live preview of the simulation inside the app's settings screen. */
class FoldingView(context: Context, attrs: AttributeSet? = null) : View(context, attrs), Choreographer.FrameCallback {
    val sim = Simulation(resources.displayMetrics.density, wallpaperMode = false)
    private var running = false
    private var lastNs = 0L

    fun start() {
        if (running) return
        running = true; lastNs = 0L
        sim.setActive(true)
        Choreographer.getInstance().postFrameCallback(this)
    }
    fun stop() {
        running = false
        sim.setActive(false)
        Choreographer.getInstance().removeFrameCallback(this)
    }

    override fun onDetachedFromWindow() { stop(); sim.release(); super.onDetachedFromWindow() }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        Choreographer.getInstance().postFrameCallback(this)
        if (lastNs != 0L && sim.settings.lowFrameRate() && frameTimeNanos - lastNs < 32_000_000L) return
        val dt = if (lastNs == 0L) 1.0 / 60 else (frameTimeNanos - lastNs) / 1e9
        lastNs = frameTimeNanos
        sim.update(dt)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = sim.resize(w, h)
    override fun onDraw(canvas: Canvas) = sim.draw(canvas)

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean = sim.onTouch(event)
}
