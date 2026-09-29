package com.hydrophobiccollapse

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Canvas
import android.os.Build
import android.os.PowerManager
import android.service.wallpaper.WallpaperService
import android.view.Choreographer
import android.view.MotionEvent
import android.view.SurfaceHolder

class FoldingWallpaperService : WallpaperService() {
    override fun onCreateEngine(): Engine = FoldEngine()

    inner class FoldEngine : Engine(), Choreographer.FrameCallback, SharedPreferences.OnSharedPreferenceChangeListener {
        private val prefs = Settings.prefs(this@FoldingWallpaperService)
        private val sim = Simulation(resources.displayMetrics.density, wallpaperMode = true).also { AndroidCanvas.install() }
        private var visible = false
        private var hasSurface = false
        private var lastNs = 0L
        // Random mode: a new protein every time the screen turns on
        private val screenOn = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_SCREEN_ON) sim.onScreenOn()
            }
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            setTouchEventsEnabled(true)
            setOffsetNotificationsEnabled(true)
            sim.applySettings(Settings.load(prefs))
            prefs.registerOnSharedPreferenceChangeListener(this)
            val filter = IntentFilter(Intent.ACTION_SCREEN_ON)
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenOn, filter, Context.RECEIVER_NOT_EXPORTED)
            else registerReceiver(screenOn, filter)
        }

        override fun onDestroy() {
            prefs.unregisterOnSharedPreferenceChangeListener(this)
            try { unregisterReceiver(screenOn) } catch (e: IllegalArgumentException) { }
            stop()
            sim.release()
            super.onDestroy()
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) { super.onSurfaceCreated(holder); hasSurface = true }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            sim.resize(width, height)
            drawFrame()
            if (visible) start()
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            hasSurface = false
            stop()
            super.onSurfaceDestroyed(holder)
        }

        // Only run while someone can see it
        override fun onVisibilityChanged(v: Boolean) {
            visible = v
            if (v) start() else stop()
        }

        private fun start() {
            if (!hasSurface) return
            lastNs = 0L
            sim.setActive(true)
            Choreographer.getInstance().removeFrameCallback(this)
            Choreographer.getInstance().postFrameCallback(this)
        }
        private fun stop() { sim.setActive(false); Choreographer.getInstance().removeFrameCallback(this) }

        override fun doFrame(frameTimeNanos: Long) {
            if (!visible || !hasSurface) return
            Choreographer.getInstance().postFrameCallback(this)
            val power = getSystemService(POWER_SERVICE) as PowerManager
            // Extreme mode also draws at 30 fps, leaving more of the CPU for folding
            val slow = sim.settings.lowFrameRate() || power.isPowerSaveMode
            if (lastNs != 0L && slow && frameTimeNanos - lastNs < 32_000_000L) return
            val dt = if (lastNs == 0L) 1.0 / 60 else (frameTimeNanos - lastNs) / 1e9
            lastNs = frameTimeNanos
            sim.update(dt)
            drawFrame()
        }

        private fun drawFrame() {
            val holder = surfaceHolder
            var c: Canvas? = null
            try {
                c = try { holder.lockHardwareCanvas() } catch (e: Exception) { holder.lockCanvas() }
                if (c != null) sim.draw(AndroidCanvas(c))
            } catch (e: Exception) {
                // Surface went away mid-frame; the next visible frame will redraw.
            } finally {
                if (c != null) try { holder.unlockCanvasAndPost(c) } catch (e: Exception) { }
            }
        }

        override fun onTouchEvent(event: MotionEvent) { sim.onPointer(event.toPointer()) }

        override fun onApplyWindowInsets(insets: android.view.WindowInsets) {
            super.onApplyWindowInsets(insets)
            @Suppress("DEPRECATION")
            sim.topInset = insets.systemWindowInsetTop.toFloat()
        }

        override fun onOffsetsChanged(xOffset: Float, yOffset: Float, xStep: Float, yStep: Float, xPixels: Int, yPixels: Int) {
            sim.setPageOffset(xOffset)
        }

        override fun onSharedPreferenceChanged(p: SharedPreferences, key: String?) {
            when (key) {
                Settings.CMD_HEAT -> sim.heat()
                Settings.CMD_RESET -> sim.reset()
                Settings.CMD_REPLAY -> sim.startReplay()
                else -> sim.applySettings(Settings.load(p))
            }
        }
    }
}
