package com.hydrophobiccollapse

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.view.MotionEvent
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The folding simulation plus everything around it: the heat cycle, the camera, drawing and touch.
 * Used by both the live wallpaper and the preview in the settings screen.
 */
class Simulation(private val density: Float, private val wallpaperMode: Boolean) {
    val eng = ProteinEngine()
    var settings = Settings(); private set
    private var loaded = false
    var protein: Protein = Proteins.presets[0]; private set
    private var w = 1f
    private var h = 1f
    var bottomInset = 0f

    // ---------- Colours (same palette as the web version) ----------
    private object Col {
        const val INK = 0xFF0A0F1D.toInt()
        const val INK_EDGE = 0xFF04060C.toInt()
        const val PANEL = 0xC20D1324.toInt()
        const val LINE = 0x29A0B0D6
        const val TEXT = 0xFFE4E8F3.toInt()
        const val HAZE = 0xFF8A94B0.toInt()
        const val HYDRO = 0xFFF0A44B.toInt()
        const val POLAR = 0xFF74CFC0.toInt()
        const val POS = 0xFF8EA2FF.toInt()
        const val NEG = 0xFFFF7D8E.toInt()
        const val SULFUR = 0xFFF3D34A.toInt()
        const val SPECIAL = 0xFF9AA3B8.toInt()
        const val HELIX = 0xFFC4B1FF.toInt()
        const val STRAND = 0xFF9FE3A8.toInt()
        const val BOND = 0xFF5D6784.toInt()
    }
    // Backbone tints that tell chains apart when there are several
    private val chainTints = intArrayOf(0xFF6F7FB0.toInt(), 0xFFB08A6F.toInt(), 0xFF6FB0A0.toInt(), 0xFFA97FB5.toInt(), 0xFFB0A66F.toInt(), 0xFF7FA6B5.toInt())
    private fun withAlpha(c: Int, a: Float) = (c and 0xFFFFFF) or ((a.coerceIn(0f, 1f) * 255).roundToInt() shl 24)
    private fun mix(a: Int, b: Int, t: Float): Int {
        val r = ((a shr 16 and 255) + ((b shr 16 and 255) - (a shr 16 and 255)) * t).roundToInt()
        val g = ((a shr 8 and 255) + ((b shr 8 and 255) - (a shr 8 and 255)) * t).roundToInt()
        val bl = ((a and 255) + ((b and 255) - (a and 255)) * t).roundToInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
    }
    private val hydrophobic = "AVLIMFW"
    private fun residueColor(i: Int): Int {
        val aa = eng.seq[i]; val q = eng.charge[i]
        return when {
            aa == 'C' -> Col.SULFUR
            q > 0.5 -> Col.POS
            q < -0.5 -> Col.NEG
            aa in hydrophobic -> Col.HYDRO
            aa == 'G' || aa == 'P' -> Col.SPECIAL
            else -> Col.POLAR
        }
    }

    // ---------- Temperature schedule ----------
    private enum class Phase(val label: String) { FOLD("Folding"), HEAT("Heating"), HOT("Denatured"), COOL("Cooling") }
    private var phase = Phase.HOT
    private var phaseT = 0.0
    private var temperature = 420.0
    private fun hotT() = min(460.0, settings.temp + 120.0)
    private fun setPhase(p: Phase) {
        phase = p; phaseT = 0.0
        if (p == Phase.HEAT) { bestE = 0.0; note("Heating to ${hotT().roundToInt()} K") }
        if (p == Phase.COOL) note("Cooling to ${settings.temp} K")
    }
    fun heat() { if (phase == Phase.FOLD || phase == Phase.COOL) setPhase(Phase.HEAT) }
    private fun updateSchedule(dt: Double) {
        phaseT += dt
        val lo = settings.temp.toDouble(); val hi = hotT()
        when (phase) {
            Phase.FOLD -> { temperature = lo; if (settings.cycle > 0 && phaseT > settings.cycle) setPhase(Phase.HEAT) }
            Phase.HEAT -> { temperature = lo + (hi - lo) * min(1.0, phaseT / 3); if (phaseT > 3) setPhase(Phase.HOT) }
            Phase.HOT -> { temperature = hi; if (phaseT > 6) setPhase(Phase.COOL) }
            Phase.COOL -> {
                val k = min(1.0, phaseT / 10)
                temperature = hi + (lo - hi) * (1 - (1 - k) * (1 - k))
                if (k >= 1) setPhase(Phase.FOLD)
            }
        }
        eng.temperature = temperature
    }

    // ---------- Settings / loading ----------
    fun applySettings(s: Settings) {
        val old = settings
        settings = s
        eng.pH = s.ph.toDouble(); eng.saltMM = s.salt.toDouble(); eng.redox = s.redox.toDouble()
        eng.nativeBias = s.nativeBias.toDouble()
        eng.crowding = s.crowding
        val reload = when {
            !loaded -> true
            s.protein == Proteins.RANDOM_ID -> old.protein != s.protein || old.randomLength != s.randomLength || old.randomStyle != s.randomStyle
            else -> Proteins.byId(s.protein, s.customJson).signature != protein.signature || old.protein != s.protein
        }
        if (reload) load()
    }
    /** Start again from an unfolded chain; in random mode, with a brand-new protein. */
    fun reset() = load()
    /** Called when the screen turns on. */
    fun onScreenOn() { if (settings.protein == Proteins.RANDOM_ID && settings.randomOnWake) load() }
    private fun load() {
        protein = if (settings.protein == Proteins.RANDOM_ID) Proteins.random(settings.randomLength, settings.randomStyle)
                  else Proteins.byId(settings.protein, settings.customJson)
        eng.temperature = hotT()
        eng.load(protein)
        val n = eng.n
        px = FloatArray(n); py = FloatArray(n); pz = DoubleArray(n); pr = FloatArray(n); ps = FloatArray(n)
        keys = LongArray(2 * n + 64)
        histLen = 0; bestE = 0.0; notes.clear(); measureAcc = 0.0
        for (k in 0..2) view[k] = eng.center[k]
        zoom = fitZoom()
        setPhase(Phase.HOT); phaseT = 3.0
        loaded = true
    }

    // ---------- Camera ----------
    private var yaw = 0.4
    private var pitch = -0.25
    private var zoom = 10.0
    private val view = DoubleArray(3)
    private var pageYaw = 0.0
    private var pageYawTarget = 0.0
    private fun cam() = max(140.0, 5 * eng.rg)
    private fun fitZoom() = min(w, h) * 0.42 / max(2.2 * eng.rg, 16.0)

    /** Home-screen page swipes turn the molecule (xOffset runs 0…1 across the pages). */
    fun setPageOffset(xOffset: Float) { pageYawTarget = (xOffset - 0.5) * 1.6 }

    private var px = FloatArray(0); private var py = FloatArray(0); private var pz = DoubleArray(0)
    private var pr = FloatArray(0); private var ps = FloatArray(0)
    private fun project() {
        val a = yaw + pageYaw; val cam = cam()
        val cy = cos(a); val sy = sin(a); val cp = cos(pitch); val sp = sin(pitch)
        for (i in 0 until eng.n) {
            val dx = eng.x[i] - view[0]; val dy = eng.y[i] - view[1]; val dz = eng.z[i] - view[2]
            val x1 = cy * dx + sy * dz; val z1 = -sy * dx + cy * dz
            val y2 = cp * dy - sp * z1; val z2 = sp * dy + cp * z1
            val s = zoom * cam / max(cam - z2, cam * 0.2)
            px[i] = (w / 2 + x1 * s).toFloat(); py[i] = (h / 2 + y2 * s).toFloat(); pz[i] = z2; ps[i] = s.toFloat()
            pr[i] = (eng.radius(i) * 0.5 * s).toFloat()
        }
    }
    private fun unproject(sx: Float, sy: Float, z2: Double, out: DoubleArray) {
        val cam = cam()
        val s = zoom * cam / max(cam - z2, cam * 0.2)
        val x1 = (sx - w / 2) / s; val y2 = (sy - h / 2) / s
        val a = yaw + pageYaw
        val cy = cos(a); val syw = sin(a); val cp = cos(pitch); val sp = sin(pitch)
        val dy = cp * y2 + sp * z2; val z1 = -sp * y2 + cp * z2
        out[0] = cy * x1 - syw * z1 + view[0]; out[1] = dy + view[1]; out[2] = syw * x1 + cy * z1 + view[2]
    }

    // ---------- Solvent (ambient: moves faster when hot) ----------
    private var solX = FloatArray(0); private var solY = FloatArray(0)
    private var solVX = FloatArray(0); private var solVY = FloatArray(0); private var solA = FloatArray(0)
    private val rnd = java.util.Random()
    private fun gauss(): Double {
        var u = rnd.nextDouble(); while (u == 0.0) u = rnd.nextDouble()
        return sqrt(-2 * ln(u)) * cos(2 * PI * rnd.nextDouble())
    }
    private fun seedSolvent() {
        val count = min(420, (w * h / (8000 * density * density)).toInt())
        solX = FloatArray(count) { rnd.nextFloat() * w }; solY = FloatArray(count) { rnd.nextFloat() * h }
        solVX = FloatArray(count); solVY = FloatArray(count); solA = FloatArray(count) { 0.06f + rnd.nextFloat() * 0.12f }
    }
    private fun updateSolvent(dt: Double) {
        val kick = (sqrt(temperature / 300) * 90 * density * sqrt(dt)).toFloat()
        val damp = kotlin.math.exp(-2 * dt).toFloat()
        for (k in solX.indices) {
            solVX[k] = solVX[k] * damp + gauss().toFloat() * kick
            solVY[k] = solVY[k] * damp + gauss().toFloat() * kick
            solX[k] += solVX[k] * dt.toFloat(); solY[k] += solVY[k] * dt.toFloat()
            if (solX[k] < 0) solX[k] += w else if (solX[k] > w) solX[k] -= w
            if (solY[k] < 0) solY[k] += h else if (solY[k] > h) solY[k] -= h
        }
    }

    fun resize(width: Int, height: Int) {
        w = max(1, width).toFloat(); h = max(1, height).toFloat()
        bg.shader = RadialGradient(w / 2, h * 0.45f, max(w, h) * 0.75f,
            intArrayOf(Col.INK, Col.INK, Col.INK_EDGE), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        seedSolvent()
        if (loaded) zoom = fitZoom()
    }

    // ---------- Stepping ----------
    private var clock = 0.0
    private var msPerStep = 0.05
    private var stepDebt = 0.0
    private var hudAcc = 0.0
    private var measureAcc = 0.0
    private val hist = FloatArray(240)
    private var histLen = 0
    private var bestE = 0.0
    private class Note(val t: Double, val text: String)
    private val notes = ArrayDeque<Note>()
    private fun note(text: String) { notes.addFirst(Note(eng.time, text)); while (notes.size > 10) notes.removeLast() }
    /** Simulation steps actually run per second of real time, for the readout. */
    var stepsPerSecond = 0.0; private set

    fun update(dt0: Double) {
        if (!loaded) return
        val dt = min(dt0, 0.1)
        clock += dt
        updateSchedule(dt)
        eng.advanceClock(dt)
        // Aim for 2000 steps per second at 1×, within a per-frame time budget. Big proteins run slower.
        val budget = if (settings.saver) 6.0 else 10.0
        stepDebt += settings.speed * 2000 * dt
        val count = min(floor(stepDebt).toInt(), max(1, (budget / msPerStep).toInt()))
        stepDebt = min(stepDebt - count, 200.0)
        if (count > 0) {
            val t0 = System.nanoTime()
            eng.step(count)
            msPerStep = 0.9 * msPerStep + 0.1 * ((System.nanoTime() - t0) / 1e6 / count)
        }
        stepsPerSecond = 0.95 * stepsPerSecond + 0.05 * (count / max(dt, 1e-3))
        // Full measurement costs one extra force pass; for big proteins do it a few times a second
        measureAcc += dt
        if (eng.n <= 400 || measureAcc >= 0.25) { eng.measure(); measureAcc = 0.0 } else eng.updateCenter()
        // Camera: follow the centre, zoom to fit, turn slowly unless a finger is on it
        for (k in 0..2) view[k] += (eng.center[k] - view[k]) * min(1.0, dt * 3)
        zoom += (fitZoom() - zoom) * min(1.0, dt * 1.2)
        if (downId < 0) yaw += dt * 0.12
        pageYaw += (pageYawTarget - pageYaw) * min(1.0, dt * 4)
        updateSolvent(dt)
        hudAcc += dt
        if (hudAcc >= 0.25) {
            hudAcc = 0.0
            if (histLen < hist.size) hist[histLen++] = eng.energy.toFloat()
            else { System.arraycopy(hist, 1, hist, 0, hist.size - 1); hist[hist.size - 1] = eng.energy.toFloat() }
            if (eng.energy < bestE) bestE = eng.energy
        }
    }

    // ---------- Drawing ----------
    private val bg = Paint()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val dash = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = density
        pathEffect = DashPathEffect(floatArrayOf(3 * density, 4 * density), 0f)
    }
    private val mono = Typeface.MONOSPACE
    private val sans = Typeface.create("sans-serif", Typeface.NORMAL)
    private val sansBold = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    // Draw order: depth packed into the high bits, item id in the low bits, sorted as longs
    private var keys = LongArray(0)
    private fun depthKey(z: Double, id: Int): Long {
        // Flip the magnitude bits of negative floats so the int compares in the same order as the float
        val bits = java.lang.Float.floatToIntBits(z.toFloat())
        val sortable = bits xor ((bits shr 31) and 0x7FFFFFFF)
        return (sortable.toLong() shl 32) or id.toLong()
    }

    fun draw(c: Canvas) {
        c.drawRect(0f, 0f, w, h, bg)
        for (k in solX.indices) {
            fill.color = withAlpha(Col.POLAR, solA[k])
            c.drawRect(solX[k], solY[k], solX[k] + 1.6f * density, solY[k] + 1.6f * density, fill)
        }
        val iter = ripples.iterator()
        while (iter.hasNext()) {
            val r = iter.next(); val age = (clock - r[2]) / 0.7
            if (age >= 1) { iter.remove(); continue }
            stroke.color = withAlpha(Col.POLAR, (0.35 * (1 - age)).toFloat()); stroke.strokeWidth = 1.5f * density
            c.drawCircle(r[0].toFloat(), r[1].toFloat(), ((8 + age * 60) * density).toFloat(), stroke)
        }
        if (!loaded) return
        val n = eng.n
        val big = n > 600
        project()
        var zmin = Double.MAX_VALUE; var zmax = -Double.MAX_VALUE
        for (i in 0 until n) { zmin = min(zmin, pz[i]); zmax = max(zmax, pz[i]) }
        val zspan = max(1.0, zmax - zmin)
        fun near(zv: Double) = ((zv - zmin) / zspan).toFloat()
        fun fog(col: Int, zv: Double) = mix(Col.INK, col, 0.35f + 0.65f * near(zv))

        // Hydrophobic contacts and salt bridges sit underneath everything
        stroke.color = withAlpha(Col.HYDRO, 0.16f); stroke.strokeWidth = max(0.6f * density, (zoom * 0.18).toFloat())
        val ca = eng.contacts.a; val cb = eng.contacts.b
        var drawn = 0
        for (p in 0 until eng.contacts.size) {
            val i = ca[p]; val j = cb[p]
            if (i < n && j < n && eng.seq[i] in hydrophobic && eng.seq[j] in hydrophobic) {
                c.drawLine(px[i], py[i], px[j], py[j], stroke)
                if (++drawn > 3000) break
            }
        }
        dash.color = withAlpha(Col.TEXT, 0.35f)
        for (b in eng.bridges) if (b[0] < n && b[1] < n) c.drawLine(px[b[0]], py[b[0]], px[b[1]], py[b[1]], dash)

        // Depth-sorted backbone segments (id < n), disulfides (n..n+63) and residues (n+64…)
        var m = 0
        for (i in 0 until n - 1) if (eng.chainOf[i] == eng.chainOf[i + 1]) keys[m++] = depthKey((pz[i] + pz[i + 1]) / 2 - 0.01, i)
        val ssList = eng.disulfides
        for (k in 0 until min(ssList.size, 64)) { val d = ssList[k]; keys[m++] = depthKey((pz[d[0]] + pz[d[1]]) / 2 - 0.02, n + k) }
        for (i in 0 until n) keys[m++] = depthKey(pz[i], n + 64 + i)
        java.util.Arrays.sort(keys, 0, m)
        val multi = eng.nChains > 1
        for (k in 0 until m) {
            val id = (keys[k] and 0xFFFFFFFFL).toInt()
            when {
                id < n -> {
                    val i = id; val a = eng.ss[i]; val b = eng.ss[i + 1]
                    val kind = if (a == b) a else 0
                    val col = when (kind) { 1 -> Col.HELIX; 2 -> Col.STRAND; else -> if (multi) chainTints[eng.chainOf[i] % chainTints.size] else Col.BOND }
                    val z = (pz[i] + pz[i + 1]) / 2
                    stroke.color = fog(col, z)
                    stroke.strokeWidth = (if (kind != 0) 1.3f else 0.55f) * (ps[i] + ps[i + 1]) / 2
                    c.drawLine(px[i], py[i], px[i + 1], py[i + 1], stroke)
                }
                id < n + 64 -> {
                    val d = ssList.getOrNull(id - n) ?: continue
                    val i = d[0]; val j = d[1]
                    if (i >= n || j >= n) continue
                    stroke.color = withAlpha(fog(Col.SULFUR, (pz[i] + pz[j]) / 2), 0.95f)
                    stroke.strokeWidth = 0.7f * (ps[i] + ps[j]) / 2
                    c.drawLine(px[i], py[i], px[j], py[j], stroke)
                }
                else -> { val i = id - n - 64; drawResidue(c, i, fog(residueColor(i), pz[i]), near(pz[i]), big) }
            }
        }

        // Reaction flashes: proton transfers (small rings) and disulfide chemistry (sulfur bursts)
        for (f in eng.flashes) {
            val age = eng.time - f.t; val i = f.res
            if (i >= n) continue
            if (f.kind == Flash.SS) {
                val k = (age / 1.2).toFloat()
                stroke.color = withAlpha(Col.SULFUR, 0.8f * (1 - k)); stroke.strokeWidth = 2 * density
                c.drawCircle(px[i], py[i], pr[i] + k * 30 * density, stroke)
            } else if (age < 0.45 && !big && pr[i] > 3 * density) {
                val k = (age / 0.45).toFloat()
                stroke.color = withAlpha(if (f.kind == Flash.GAINED) Col.POS else Col.TEXT, 0.5f * (1 - k)); stroke.strokeWidth = 1.2f * density
                c.drawCircle(px[i], py[i], pr[i] + (2 + k * 10) * density, stroke)
            }
        }

        // Termini: N and C for one chain; chain letters at each N-terminus for a few chains
        text.typeface = mono; text.textAlign = Paint.Align.CENTER; text.color = Col.HAZE
        text.textSize = max(10 * density, min(16 * density, (zoom * 1.1).toFloat()))
        fun label(i: Int, j: Int, t: String) {
            val dx = px[i] - px[j]; val dy = py[i] - py[j]; val len = max(1f, hypot(dx, dy))
            val off = pr[i] + 12 * density
            c.drawText(t, px[i] + dx / len * off, py[i] + dy / len * off + text.textSize * 0.35f, text)
        }
        if (eng.nChains == 1 && n > 1) { label(0, 1, "N"); label(n - 1, n - 2, "C") }
        else if (eng.nChains in 2..8) for (ch in 0 until eng.nChains) {
            val s0 = eng.chainStart[ch]
            if (eng.chainStart[ch + 1] - s0 > 1) label(s0, s0 + 1, eng.chainLetter(ch).toString())
        }

        if (settings.hud) drawHud(c)
    }

    private fun drawResidue(c: Canvas, i: Int, col: Int, nearness: Float, big: Boolean) {
        val r = pr[i]; val x = px[i]; val y = py[i]
        fill.color = col; c.drawCircle(x, y, r, fill)
        if (!big) {
            stroke.color = 0x7304060C; stroke.strokeWidth = density; c.drawCircle(x, y, r, stroke)
            fill.color = withAlpha(0xFFFFFF, 0.1f + 0.2f * nearness); c.drawCircle(x - r * 0.3f, y - r * 0.3f, r * 0.38f, fill)
        }
        val q = eng.charge[i]
        if (abs(q) > 0.5 && r > 5 * density) {
            stroke.color = 0xBF04060C.toInt(); stroke.strokeWidth = max(1.2f * density, r * 0.18f)
            val g = r * 0.42f
            c.drawLine(x - g, y, x + g, y, stroke)
            if (q > 0) c.drawLine(x, y - g, x, y + g, stroke)
        }
        if (i == eng.grab) {
            stroke.color = withAlpha(Col.TEXT, 0.7f); stroke.strokeWidth = 1.5f * density
            c.drawCircle(x, y, r + 5 * density, stroke)
        }
    }

    // ---------- Readout, drawn on the canvas ----------
    private fun dp(v: Float) = v * density
    private fun minus(v: Double) = (if (v < -0.5) "−" else "") + abs(v).roundToInt()
    private val seqShown = 200
    private fun drawHud(c: Canvas) {
        val pad = dp(14f)
        val pw = min(dp(340f), w - dp(32f))
        val inner = pw - 2 * pad
        text.typeface = mono; text.textSize = dp(10f); text.textAlign = Paint.Align.LEFT
        val charW = text.measureText("M") * 1.04f
        val perLine = max(1, (inner / charW).toInt())
        // Chains are separated by "/" in the sequence line
        val cells = min(eng.n + eng.nChains - 1, seqShown)
        val seqLines = ceil(cells / perLine.toFloat()).toInt()
        val truncated = eng.n + eng.nChains - 1 > seqShown
        val multi = eng.nChains > 1
        val structured = eng.hasStructure && settings.nativeBias > 0
        val statRows = 2 + (if (multi) 1 else 0) + (if (structured) 1 else 0)
        val events = mergedEvents()
        val height = pad + dp(30f) + dp(14f) + seqLines * dp(15f) + (if (truncated) dp(14f) else 0f) + dp(12f) +
            statRows * dp(40f) + dp(4f) + dp(62f) + events.size * dp(14f) + pad
        val x0 = dp(16f)
        val bottom = h - bottomInset - (if (wallpaperMode) dp(110f) else dp(16f))
        val y0 = max(dp(16f), bottom - height)
        fill.color = Col.PANEL; c.drawRoundRect(x0, y0, x0 + pw, y0 + height, dp(10f), dp(10f), fill)
        stroke.color = Col.LINE; stroke.strokeWidth = density; c.drawRoundRect(x0, y0, x0 + pw, y0 + height, dp(10f), dp(10f), stroke)
        val left = x0 + pad
        var y = y0 + pad

        // Phase and conditions
        val hot = phase == Phase.HEAT || phase == Phase.HOT
        fill.color = if (hot) Col.HYDRO else Col.POLAR
        c.drawCircle(left + dp(4f), y + dp(10f), dp(4f), fill)
        text.typeface = sansBold; text.textSize = dp(15f); text.color = Col.TEXT
        c.drawText(phase.label, left + dp(16f), y + dp(15f), text)
        text.typeface = mono; text.textSize = dp(10.5f); text.color = Col.HAZE; text.textAlign = Paint.Align.RIGHT
        c.drawText("${temperature.roundToInt()} K · pH ${"%.1f".format(settings.ph)} · ${settings.salt} mM", left + inner, y + dp(14f), text)
        text.textAlign = Paint.Align.LEFT
        y += dp(30f)

        // Protein and sequence, coloured by current charge, underlined by secondary structure
        text.textSize = dp(10f)
        val chainsTxt = if (multi) " · ${eng.nChains} chains" else ""
        val nat = if (protein.native.isNotEmpty()) " · ${protein.native.size} native S–S" else ""
        val title = "${protein.name} · ${eng.n} res$chainsTxt$nat"
        c.drawText(ellipsize(title, inner), left, y + dp(10f), text)
        y += dp(14f)
        var cell = 0; var i = 0
        while (i < eng.n && cell < cells) {
            if (i > 0 && eng.chainOf[i] != eng.chainOf[i - 1]) {
                text.color = Col.HAZE
                c.drawText("/", left + (cell % perLine) * charW, y + (cell / perLine) * dp(15f) + dp(11f), text)
                cell++
                if (cell >= cells) break
            }
            val lx = left + (cell % perLine) * charW; val ly = y + (cell / perLine) * dp(15f) + dp(11f)
            text.color = residueColor(i)
            c.drawText(eng.seq[i].toString(), lx, ly, text)
            val s = eng.ss[i]
            if (s != 0) { fill.color = if (s == 1) Col.HELIX else Col.STRAND; c.drawRect(lx, ly + dp(2f), lx + charW, ly + dp(4f), fill) }
            cell++; i++
        }
        y += seqLines * dp(15f)
        if (truncated) {
            text.color = Col.HAZE
            c.drawText("… ${eng.n - i} more residues", left, y + dp(10f), text)
            y += dp(14f)
        }
        y += dp(12f)

        // Stats: two rows, plus a row about interfaces when there are several chains
        val colW = inner / 3
        val nds = eng.disulfides.size; val nnat = eng.disulfides.count { it[2] == 1 }
        val stats = arrayListOf(
            "ENERGY, KCAL/MOL" to minus(eng.energy), "RG" to "${"%.1f".format(eng.rg)} Å",
            "HELIX · STRAND" to "${(eng.helix * 100).roundToInt()} · ${(eng.strand * 100).roundToInt()} %",
            "NET CHARGE" to "${if (eng.netCharge > 0) "+" else if (eng.netCharge < 0) "−" else ""}${abs(eng.netCharge)} e",
            "SALT BRIDGES" to "${eng.bridges.size}",
            "DISULFIDES" to if (protein.native.isNotEmpty()) "$nds · $nnat native" else if ('C' in eng.seq) "$nds" else "no Cys",
        )
        if (multi) {
            stats += "CHAINS" to "${eng.nChains}"
            stats += "INTERFACE" to "${eng.interfaceContacts} contacts"
            stats += "LARGEST COMPLEX" to "${eng.largestComplex} of ${eng.nChains}"
        }
        if (structured) {
            // How close to the real structure: share of its contacts formed, and shape difference after superposition
            stats += "NATIVE CONTACTS" to "${(eng.q * 100).roundToInt()} %"
            stats += "RMSD TO REAL" to if (eng.rmsd.isNaN()) "—" else "${"%.1f".format(eng.rmsd)} Å"
            stats += "STRUCTURE" to (eng.structureSource ?: "")
        }
        for ((k, s) in stats.withIndex()) {
            val sx = left + (k % 3) * colW; val sy = y + (k / 3) * dp(40f)
            text.typeface = sans; text.textSize = dp(9f); text.color = Col.HAZE; text.letterSpacing = 0.08f
            c.drawText(s.first, sx, sy + dp(10f), text)
            text.letterSpacing = 0f; text.typeface = mono; text.textSize = dp(12.5f); text.color = Col.TEXT
            c.drawText(ellipsize(s.second, colW - dp(6f)), sx, sy + dp(28f), text)
        }
        y += statRows * dp(40f) + dp(4f)

        // Energy trace with the lowest energy since the last unfold
        drawSpark(c, left, y, inner, dp(40f))
        text.typeface = mono; text.textSize = dp(9f); text.color = Col.HAZE
        val rate = if (eng.n > 300) " · ${(stepsPerSecond / 1000).let { "%.1f".format(it) }}k steps/s" else ""
        c.drawText("Energy, last 60 s$rate", left, y + dp(52f), text)
        y += dp(62f)

        // Newest events
        text.textSize = dp(10f)
        for (e in events) {
            text.color = Col.HAZE
            c.drawText("${e.first.roundToInt()} s", left, y + dp(10f), text)
            text.color = if (e.third) Col.SULFUR else Col.TEXT
            c.drawText(ellipsize(e.second, inner - dp(44f)), left + dp(44f), y + dp(10f), text)
            y += dp(14f)
        }
    }
    private fun ellipsize(s: String, maxW: Float): String {
        if (text.measureText(s) <= maxW) return s
        val keep = text.breakText(s, true, maxW - text.measureText("…"), null)
        return s.take(keep) + "…"
    }
    private fun mergedEvents(): List<Triple<Double, String, Boolean>> {
        val all = ArrayList<Triple<Double, String, Boolean>>()
        for (e in eng.events) all.add(Triple(e.t, e.text, true))
        for (nt in notes) all.add(Triple(nt.t, nt.text, false))
        all.sortByDescending { it.first }
        return all.take(3)
    }
    private fun drawSpark(c: Canvas, x: Float, y: Float, sw: Float, sh: Float) {
        if (histLen < 2) return
        var lo = bestE.toFloat(); var hi = -Float.MAX_VALUE
        for (k in 0 until histLen) { lo = min(lo, hist[k]); hi = max(hi, hist[k]) }
        if (hi - lo < 5) lo = hi - 5
        val pad = dp(3f); val off = hist.size - histLen
        fun X(k: Int) = x + (k + off) / (hist.size - 1f) * sw
        fun Y(v: Float) = y + pad + (1 - (v - lo) / (hi - lo)) * (sh - 2 * pad)
        dash.color = withAlpha(Col.HAZE, 0.5f)
        c.drawLine(x, Y(bestE.toFloat()), x + sw, Y(bestE.toFloat()), dash)
        val path = Path()
        path.moveTo(X(0), Y(hist[0]))
        for (k in 1 until histLen) path.lineTo(X(k), Y(hist[k]))
        stroke.color = Col.HYDRO; stroke.strokeWidth = 1.5f * density
        c.drawPath(path, stroke)
        path.lineTo(x + sw, y + sh); path.lineTo(X(0), y + sh); path.close()
        fill.color = withAlpha(Col.HYDRO, 0.12f); c.drawPath(path, fill)
        fill.color = Col.HYDRO; c.drawCircle(X(histLen - 1), Y(hist[histLen - 1]), dp(2.5f), fill)
    }

    // ---------- Touch ----------
    // Wallpaper: drag a residue to pull it, tap to stir, double-tap to heat; page swipes turn the view.
    // Preview: the same, plus dragging open space turns the molecule.
    private var downId = -1
    private var downX = 0f; private var downY = 0f; private var lastX = 0f; private var lastY = 0f
    private var downTime = 0L
    private var moved = false
    private var lastTapTime = 0L; private var lastTapX = 0f; private var lastTapY = 0f
    private val ripples = ArrayList<DoubleArray>()
    private val tmp = DoubleArray(3)

    fun onTouch(e: MotionEvent): Boolean {
        if (!loaded) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downId = e.getPointerId(0)
                downX = e.x; downY = e.y; lastX = e.x; lastY = e.y; downTime = e.eventTime; moved = false
                var best = -1; var bz = -Double.MAX_VALUE
                for (i in 0 until eng.n) {
                    val d = hypot(px[i] - e.x, py[i] - e.y)
                    if (d < pr[i] + 14 * density && pz[i] > bz) { bz = pz[i]; best = i }
                }
                eng.grab = best
                if (best >= 0) { unproject(e.x, e.y, pz[best], tmp); System.arraycopy(tmp, 0, eng.target, 0, 3) }
            }
            MotionEvent.ACTION_MOVE -> {
                if (downId < 0) return false
                if (!moved && hypot(e.x - downX, e.y - downY) > 8 * density) moved = true
                if (eng.grab >= 0) { unproject(e.x, e.y, pz[eng.grab], tmp); System.arraycopy(tmp, 0, eng.target, 0, 3) }
                else if (moved && !wallpaperMode) {
                    yaw += (e.x - lastX) * 0.012 / density
                    pitch = (pitch + (e.y - lastY) * 0.012 / density).coerceIn(-1.3, 1.3)
                }
                lastX = e.x; lastY = e.y
            }
            MotionEvent.ACTION_UP -> {
                if (downId >= 0 && !moved && e.eventTime - downTime < 350) tap(e.x, e.y, e.eventTime)
                eng.grab = -1; downId = -1
            }
            MotionEvent.ACTION_CANCEL -> { eng.grab = -1; downId = -1 }
            MotionEvent.ACTION_POINTER_DOWN -> { eng.grab = -1; moved = true }
        }
        return true
    }
    private fun tap(x: Float, y: Float, time: Long) {
        if (eng.grab < 0) {
            ripples.add(doubleArrayOf(x.toDouble(), y.toDouble(), clock))
            unproject(x, y, 0.0, tmp)
            eng.kick(tmp[0], tmp[1], tmp[2], 14.0, 2.5)
        }
        if (time - lastTapTime < 330 && hypot(x - lastTapX, y - lastTapY) < 50 * density) { heat(); lastTapTime = 0 }
        else { lastTapTime = time; lastTapX = x; lastTapY = y }
    }
}
