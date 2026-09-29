package com.hydrophobiccollapse

import com.hydrophobiccollapse.gfx.Canvas
import com.hydrophobiccollapse.gfx.Color
import com.hydrophobiccollapse.gfx.DashPathEffect
import com.hydrophobiccollapse.gfx.Paint
import com.hydrophobiccollapse.gfx.Path
import com.hydrophobiccollapse.gfx.PointerEvent
import com.hydrophobiccollapse.gfx.RadialGradient
import com.hydrophobiccollapse.gfx.Shader
import com.hydrophobiccollapse.gfx.Typeface
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
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

private const val EFFECTS_MAX = 1200     // depth of field and sparks only up to this many residues
private const val SPARK_LIFE = 0.8        // s

/**
 * The folding simulation plus everything around it: the heat cycle, the camera, drawing and touch.
 * Shared by the Android wallpaper, its settings preview, and the Windows app.
 */
class Simulation(private val density: Float, private val wallpaperMode: Boolean) {
    val eng = ProteinEngine()
    var settings = Settings(); private set
    private var loaded = false
    var protein: Protein = Proteins.presets[0]; private set
    private var w = 1f
    private var h = 1f
    var bottomInset = 0f
    var topInset = 0f

    // Everything touching the engine holds this lock, so Extreme mode's physics thread and the display never overlap.
    // It is fair: a waiting frame gets its turn after the current batch of steps.
    private val lock = ReentrantLock(true)

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
    // Bold, distinct chain colours; one chain is coloured as a rainbow from N (blue) to C (red)
    private val chainPalette = intArrayOf(0xFFFF6B6B.toInt(), 0xFF4DABF7.toInt(), 0xFFFFD43B.toInt(), 0xFF51CF66.toInt(),
        0xFFCC5DE8.toInt(), 0xFFFF922B.toInt(), 0xFF22B8CF.toInt(), 0xFFF06595.toInt())
    private var chainCol = IntArray(0)
    private fun buildChainColours() {
        val n = eng.n
        chainCol = IntArray(n) { i ->
            if (eng.nChains > 1) chainPalette[eng.chainOf[i] % chainPalette.size]
            else Color.hsvToColor(240f * (1 - i / max(1f, n - 1f)), 0.62f, 1f)
        }
    }
    private fun colourByChain() = when (settings.colorBy) { 1 -> false; 2 -> true; else -> eng.nChains > 1 }
    /** The colour a residue is drawn in: its chemistry, or its chain. */
    private fun drawColor(i: Int, byChain: Boolean) = if (byChain) chainCol[i] else residueColor(i)
    private fun withAlpha(c: Int, a: Float) = (c and 0xFFFFFF) or ((a.coerceIn(0f, 1f) * 255).roundToInt() shl 24)
    private fun mix(a: Int, b: Int, t: Float): Int {
        val r = ((a shr 16 and 255) + ((b shr 16 and 255) - (a shr 16 and 255)) * t).roundToInt()
        val g = ((a shr 8 and 255) + ((b shr 8 and 255) - (a shr 8 and 255)) * t).roundToInt()
        val bl = ((a and 255) + ((b and 255) - (a and 255)) * t).roundToInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
    }
    private val hydrophobic = "AVLIMFW"
    private fun residueColor(i: Int): Int {
        val aa = eng.seq[i]; val q = snapQ[i]
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
        if (p == Phase.COOL) { note("Cooling to ${settings.temp} K"); foldStart = eng.time; foldedNoted = false }
    }
    fun heat() = lock.withLock { if (phase == Phase.FOLD || phase == Phase.COOL) setPhase(Phase.HEAT) }
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
    fun applySettings(s: Settings) = lock.withLock {
        val old = settings
        settings = s
        eng.pH = s.ph.toDouble(); eng.saltMM = s.salt.toDouble(); eng.redox = s.redox.toDouble()
        eng.nativeBias = s.nativeBias.toDouble()
        eng.crowding = s.crowding
        eng.assist = s.assist; eng.urea = s.urea.toDouble(); eng.pullPN = s.pullPN.toDouble()
        eng.chaperone = s.chaperone
        eng.ribosome = s.ribosome; eng.ribosomeSpeed = s.ribosomeSpeed
        val reload = when {
            !loaded -> true
            old.ribosome != s.ribosome -> true
            s.protein == Proteins.RANDOM_ID -> old.protein != s.protein || old.randomLength != s.randomLength || old.randomStyle != s.randomStyle
            else -> Proteins.byId(s.protein, s.customJson).signature != protein.signature || old.protein != s.protein
        }
        if (reload) load()
        if (!s.effects) sparks.clear()
        syncPhysicsThread()
    }
    /** Start again from an unfolded chain; in random mode, with a brand-new protein. */
    fun reset() = lock.withLock { load(); syncPhysicsThread() }
    /** Called when the screen turns on. */
    fun onScreenOn() = lock.withLock { if (settings.protein == Proteins.RANDOM_ID && settings.randomOnWake) { load(); syncPhysicsThread() } }
    private fun load() {
        protein = if (settings.protein == Proteins.RANDOM_ID) Proteins.random(settings.randomLength, settings.randomStyle)
                  else Proteins.byId(settings.protein, settings.customJson)
        eng.temperature = hotT()
        eng.load(protein)
        val n = eng.n
        px = FloatArray(n); py = FloatArray(n); pz = DoubleArray(n); pr = FloatArray(n); ps = FloatArray(n)
        keys = LongArray(n * CARTOON_SUB + n + 128)
        buildChainColours()
        allocCartoon(n)
        histLen = 0; bestE = 0.0; notes.clear(); measureAcc = 0.0
        for (k in 0..2) view[k] = eng.center[k]
        zoom = fitZoom()
        // A chain made on the ribosome folds at the set temperature as it grows; otherwise start hot and unfolded
        if (eng.translating) { setPhase(Phase.FOLD); note("Ribosome: translation started") } else { setPhase(Phase.HOT); phaseT = 3.0 }
        // Progress is measured from this unfolded start (for a growing chain, a random coil of the full length)
        rg0 = if (eng.translating) 1.93 * Math.pow(eng.n.toDouble(), 0.6) else eng.rg
        q0 = if (eng.q.isNaN()) 0.0 else min(eng.q, 0.6)
        r0 = if (eng.rmsd.isNaN()) max(20.0, 0.5 * rg0) else max(eng.rmsd, 6.0)
        riboAlpha = if (eng.translating) 1.0 else 0.0
        progress = 0.0; foldedNoted = false; foldStart = eng.time
        funLen = 0; funHead = 0; selected = -1; selectedInfo = null
        recording = Recording(n); replaying = false; recordAcc = 0.0
        sparks.clear(); prevContacts.clear(); lastSpark.clear(); havePrevContacts = false
        userZoom = 1.0
        takeSnapshot()
        loaded = true
    }

    // ---------- Snapshot of what the physics thread changes, for drawing ----------
    private var snapX = DoubleArray(0); private var snapY = DoubleArray(0); private var snapZ = DoubleArray(0)
    private var snapQ = DoubleArray(0)
    private var snapSS = IntArray(0)
    private var snapDisulfides: List<IntArray> = emptyList()
    private var snapFlashes = ArrayList<Flash>()
    private var snapEvents = ArrayList<ChemEvent>()
    private var snapCagePhase = CAGE_OFF
    private var snapCageProgress = 0.0
    private var snapCageR = 0.0
    private var snapCageH = 0.0
    private var snapMade = 0                  // residues made so far; the rest are drawn nowhere
    private var snapReleased = 0              // residues out of the ribosome tunnel
    private val snapTranslating get() = snapReleased < eng.n
    private var snapElongation = 0.0
    private var snapRiboWaiting = false
    private fun ensureSnapArrays(n: Int) {
        if (snapX.size != n) { snapX = DoubleArray(n); snapY = DoubleArray(n); snapZ = DoubleArray(n); snapQ = DoubleArray(n); snapSS = IntArray(n) }
    }
    private fun takeSnapshot() {
        val n = eng.n
        ensureSnapArrays(n)
        System.arraycopy(eng.x, 0, snapX, 0, n); System.arraycopy(eng.y, 0, snapY, 0, n); System.arraycopy(eng.z, 0, snapZ, 0, n)
        System.arraycopy(eng.charge, 0, snapQ, 0, n)
        System.arraycopy(eng.ss, 0, snapSS, 0, n)
        snapDisulfides = ArrayList(eng.disulfides)
        snapFlashes = ArrayList(eng.flashes); snapEvents = ArrayList(eng.events)
        snapCagePhase = eng.cagePhase; snapCageProgress = eng.cageProgress
        snapCageR = eng.cageRadius; snapCageH = eng.cageHalfHeight
        snapMade = eng.made; snapReleased = eng.released; snapElongation = eng.elongation; snapRiboWaiting = eng.riboWaiting
        shownProgress = progress
    }

    // ---------- Extreme performance: a physics thread that never waits for the display ----------
    private var active = false
    @Volatile private var physicsThread: Thread? = null
    private var threadSteps = 0L
    private var lastThreadSteps = 0L
    /** The view calls this when it becomes visible or hidden; Extreme mode only computes while visible. */
    fun setActive(on: Boolean) = lock.withLock { active = on; syncPhysicsThread() }
    /** Stop background work for good (the view or wallpaper is going away). */
    fun release() = lock.withLock { active = false; syncPhysicsThread() }
    private fun syncPhysicsThread() {
        val want = active && loaded && settings.performance == 2
        if (want && physicsThread == null) {
            threadSteps = 0; lastThreadSteps = 0
            physicsThread = Thread({ physicsLoop() }, "fold-physics").apply { isDaemon = true; start() }
        } else if (!want) physicsThread = null        // the loop notices and ends
    }
    private fun physicsLoop() {
        val me = Thread.currentThread()
        var batch = 10
        while (physicsThread === me) {
            if (replaying) { java.util.concurrent.locks.LockSupport.parkNanos(5_000_000L); continue }
            lock.lock()
            try {
                if (physicsThread !== me) break
                if (replaying) continue
                val t0 = System.nanoTime()
                eng.step(batch)
                msPerStep = 0.9 * msPerStep + 0.1 * ((System.nanoTime() - t0) / 1e6 / batch)
                threadSteps += batch
                batch = (2.0 / msPerStep).toInt().coerceIn(1, 4000)   // about 2 ms per turn at the lock
            } finally { lock.unlock() }
        }
    }

    // ---------- Residue inspector: tap a residue to see what it is and what it is doing ----------
    var selected = -1; private set
    private var selectedAt = 0.0
    private var selectedInfo: ProteinEngine.ResidueInfo? = null
    private fun select(i: Int) {
        selected = if (i == selected) -1 else i
        selectedAt = clock
        selectedInfo = if (selected >= 0) eng.residueInfo(selected) else null
    }
    /** For tests and the settings screen: select a residue directly. */
    fun selectResidue(i: Int) = lock.withLock { selected = -1; select(i) }

    // ---------- Folding funnel: energy against native contacts, the classic picture of folding ----------
    private val funQ = FloatArray(480); private val funE = FloatArray(480)
    private var funLen = 0; private var funHead = 0

    // ---------- Folding progress ----------
    // With a known structure: native contacts formed and closeness to it (RMSD), both measured from the
    // unfolded start; the lower of the two, so 100 % needs Q ≥ 0.9 and RMSD ≤ 2 Å. Without one, how far the
    // chain has collapsed toward the typical size of a folded protein that long (Rg ≈ 2.2·N^0.38 Å).
    private var q0 = 0.0; private var r0 = 20.0; private var rg0 = 20.0
    /** Smoothed progress, 0…1. */
    var progress = 0.0; private set
    private var foldedNoted = false
    private var foldStart = 0.0
    val progressIsFolding get() = eng.hasStructure && settings.nativeBias > 0
    private fun rawProgress(): Double {
        if (progressIsFolding) {
            val pq = ((eng.q - q0) / (0.9 - q0)).coerceIn(0.0, 1.0)
            val pr = if (eng.rmsd.isNaN()) pq else ((r0 - eng.rmsd) / (r0 - 2.0)).coerceIn(0.0, 1.0)
            return min(pq, pr)
        }
        val target = 2.2 * Math.pow(eng.n.toDouble(), 0.38)
        val collapse = if (rg0 <= target) 1.0 else ((rg0 - eng.rg) / (rg0 - target)).coerceIn(0.0, 1.0)
        // A part-made chain is compact because it is short: count only the share that exists
        return if (eng.translating) collapse * eng.released / eng.n else collapse
    }

    // ---------- Camera ----------
    private var yaw = 0.4
    private var pitch = -0.25
    private var zoom = 10.0
    private val view = DoubleArray(3)
    private var pageYaw = 0.0
    private var pageYawTarget = 0.0
    private var userZoom = 1.0
    private fun cam() = max(140.0, 5 * eng.rg)
    private fun fitZoom(): Double {
        var extent = max(2.2 * eng.rg, 16.0)
        if (eng.cagePhase != CAGE_OFF) extent = max(extent, 1.25 * eng.cageHalfHeight)
        if (riboAlpha > 0.5) extent = max(extent, abs(view[1] - eng.exitY) + 18)
        return min(w, h) * 0.42 / extent * userZoom
    }
    /** Turn the molecule (drag, arrow keys). */
    fun rotateBy(dYaw: Double, dPitch: Double) = lock.withLock { yaw += dYaw; pitch = (pitch + dPitch).coerceIn(-1.3, 1.3) }
    /** Zoom in (factor > 1) or out, on top of the automatic fit. */
    fun zoomBy(factor: Double) = lock.withLock { userZoom = (userZoom * factor).coerceIn(0.35, 5.0) }

    /** Home-screen page swipes turn the molecule (xOffset runs 0…1 across the pages). */
    fun setPageOffset(xOffset: Float) { pageYawTarget = (xOffset - 0.5) * 1.6 }

    private var px = FloatArray(0); private var py = FloatArray(0); private var pz = DoubleArray(0)
    private var pr = FloatArray(0); private var ps = FloatArray(0)
    private fun project() {
        val a = yaw + pageYaw; val cam = cam()
        val cy = cos(a); val sy = sin(a); val cp = cos(pitch); val sp = sin(pitch)
        for (i in 0 until eng.n) {
            val dx = snapX[i] - view[0]; val dy = snapY[i] - view[1]; val dz = snapZ[i] - view[2]
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

    fun resize(width: Int, height: Int) = lock.withLock {
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

    fun update(dt0: Double) = lock.withLock {
        if (!loaded) return@withLock
        val dt = min(dt0, 0.1)
        clock += dt
        if (replaying) {
            // The engine waits while a recording plays; the camera and the water keep moving
            updateReplay(dt)
            if (replaying) { updateCamera(dt); updateSolvent(dt); return@withLock }
        }
        updateSchedule(dt)
        eng.advanceClock(dt)
        if (physicsThread != null) {
            // Extreme: the physics thread steps as fast as it can; just count what it did
            val done = threadSteps - lastThreadSteps; lastThreadSteps = threadSteps
            stepsPerSecond = 0.9 * stepsPerSecond + 0.1 * (done / max(dt, 1e-3))
        } else {
            // Aim for 2000 steps per second at 1×, within a per-frame time budget. Big proteins run slower.
            val budget = if (settings.performance == 0) 6.0 else 10.0
            stepDebt += settings.speed * 2000 * dt
            val count = min(floor(stepDebt).toInt(), max(1, (budget / msPerStep).toInt()))
            stepDebt = min(stepDebt - count, 200.0)
            if (count > 0) {
                val t0 = System.nanoTime()
                eng.step(count)
                msPerStep = 0.9 * msPerStep + 0.1 * ((System.nanoTime() - t0) / 1e6 / count)
            }
            stepsPerSecond = 0.95 * stepsPerSecond + 0.05 * (count / max(dt, 1e-3))
        }
        // Full measurement costs one extra force pass; for big proteins do it a few times a second
        measureAcc += dt
        if (eng.n <= 400 || measureAcc >= 0.25) {
            eng.measure(); measureAcc = 0.0
            if (settings.effects && eng.n <= EFFECTS_MAX) detectSparks()
            progress += (rawProgress() - progress) * min(1.0, 0.5 * max(dt, 0.25))
            if (!foldedNoted && progress > 0.985 && phase != Phase.HEAT && phase != Phase.HOT) {
                foldedNoted = true
                note("${if (progressIsFolding) "Folded" else "Collapsed"} in ${(eng.time - foldStart).roundToInt()} s")
            }
        } else eng.updateCenter()
        updateCamera(dt)
        updateSolvent(dt)
        takeSnapshot()
        if (selected >= 0) {
            if (selected >= eng.n || (wallpaperMode && clock - selectedAt > 12)) { selected = -1; selectedInfo = null }
            else if (hudAcc == 0.0 || measureAcc == 0.0) selectedInfo = eng.residueInfo(selected)
        }
        hudAcc += dt
        if (hudAcc >= 0.25) {
            hudAcc = 0.0
            if (histLen < hist.size) hist[histLen++] = eng.energy.toFloat()
            else { System.arraycopy(hist, 1, hist, 0, hist.size - 1); hist[hist.size - 1] = eng.energy.toFloat() }
            if (eng.energy < bestE) bestE = eng.energy
            if (eng.hasStructure && !eng.q.isNaN()) {
                funQ[funHead] = eng.q.toFloat(); funE[funHead] = eng.energy.toFloat()
                funHead = (funHead + 1) % funQ.size; if (funLen < funQ.size) funLen++
            }
        }
        recordAcc += dt
        if (recordAcc >= Recording.INTERVAL) { recordAcc -= Recording.INTERVAL; recording.add(eng, progress, temperature) }
    }

    /** Camera: follow the protein (or the chaperone cage), zoom to fit, turn slowly unless a finger is on it. */
    private fun updateCamera(dt: Double) {
        val target = when {
            replaying -> replayCenter
            eng.cagePhase != CAGE_OFF -> origin
            else -> eng.center
        }
        for (k in 0..2) view[k] += (target[k] - view[k]) * min(1.0, dt * 3)
        zoom += (fitZoom() - zoom) * min(1.0, dt * 1.2)
        // The ribosome fades out once the last chain has left it
        riboAlpha += ((if (snapTranslating) 1.0 else 0.0) - riboAlpha) * min(1.0, dt * 0.8)
        if (downId < 0 && !(replaying && replayPaused)) yaw += dt * 0.12
        pageYaw += (pageYawTarget - pageYaw) * min(1.0, dt * 4)
    }
    private val origin = DoubleArray(3)

    // ---------- Time-lapse replay: the recent past, sped up; the camera stays free to turn and zoom ----------
    private var recording = Recording(0)
    private var recordAcc = 0.0
    /** True while a recording plays instead of the live simulation. */
    @Volatile var replaying = false; private set
    private var replayPos = 0.0                  // frame index, fractional
    var replayPaused = false; private set
    private var scrubbing = false
    private val replayCenter = DoubleArray(3)
    private var shownProgress = 0.0
    /** Seconds of simulation recorded so far (a replay needs a couple). */
    val recordedSeconds get() = recording.seconds
    fun startReplay(): Boolean = lock.withLock {
        if (!loaded || recording.size < 8) return@withLock false
        replaying = true; replayPos = 0.0; replayPaused = false; scrubbing = false
        eng.grab = -1; selected = -1; selectedInfo = null; sparks.clear()
        showReplayFrame()
        true
    }
    fun stopReplay() = lock.withLock {
        if (!replaying) return@withLock
        replaying = false; scrubbing = false; replayPaused = false
        takeSnapshot()
    }
    fun toggleReplay() = lock.withLock { if (replaying) stopReplay() else startReplay() }
    fun toggleReplayPause() = lock.withLock { if (replaying) replayPaused = !replayPaused }
    /** Jump forward or back through a replay by some seconds of simulation. */
    fun scrubReplay(seconds: Double) = lock.withLock {
        if (!replaying) return@withLock
        replayPos = (replayPos + seconds / Recording.INTERVAL).coerceIn(0.0, recording.size - 1.0)
        showReplayFrame()
    }
    /**
     * How many times faster than real time the replay plays right now: the chosen speed (slowed for short
     * recordings so a replay lasts at least four seconds), easing to a tenth of that for a slow-motion finish.
     */
    private fun replaySpeed(): Double {
        val base = min(settings.replaySpeed.toDouble(), max(1.0, recording.seconds / 4))
        val howFar = (replayPos / max(recording.size - 1.0, 1.0)).coerceIn(0.0, 1.0)
        return base * (1.0 - 0.9 * howFar)
    }
    private fun updateReplay(dt: Double) {
        if (!replayPaused && !scrubbing) {
            replayPos += dt * replaySpeed() / Recording.INTERVAL
            if (replayPos >= recording.size - 1) {
                // The end of the recording is exactly where the live simulation paused: carry on from there
                stopReplay(); return
            }
        }
        showReplayFrame()
    }
    /** Fills the drawing snapshot from the recording, blending the two frames either side of the play position. */
    private fun showReplayFrame() {
        if (recording.size == 0) return
        val k0 = floor(replayPos).toInt().coerceIn(0, recording.size - 1)
        val k1 = min(k0 + 1, recording.size - 1)
        val t = (replayPos - k0).toFloat().coerceIn(0f, 1f)
        val a = recording[k0]; val b = recording[k1]
        val n = eng.n
        ensureSnapArrays(n)
        var cx = 0.0; var cy = 0.0; var cz = 0.0
        val near = if (t < 0.5f) a else b
        val counted = max(1, min(a.released, b.released))
        for (i in 0 until n) {
            val x = a.xyz[3 * i] + (b.xyz[3 * i] - a.xyz[3 * i]) * t
            val y = a.xyz[3 * i + 1] + (b.xyz[3 * i + 1] - a.xyz[3 * i + 1]) * t
            val z = a.xyz[3 * i + 2] + (b.xyz[3 * i + 2] - a.xyz[3 * i + 2]) * t
            snapX[i] = x.toDouble(); snapY[i] = y.toDouble(); snapZ[i] = z.toDouble()
            if (i < counted) { cx += x; cy += y; cz += z }
        }
        for (i in 0 until n) { snapQ[i] = near.charge[i].toDouble(); snapSS[i] = near.ss[i].toInt() }
        snapDisulfides = List(near.disulfides.size / 3) { k -> intArrayOf(near.disulfides[3 * k], near.disulfides[3 * k + 1], near.disulfides[3 * k + 2]) }
        snapFlashes = ArrayList(); snapEvents = ArrayList()
        snapCagePhase = near.cagePhase
        snapCageProgress = (if (a.cagePhase == b.cagePhase) a.cageProgress + (b.cageProgress - a.cageProgress) * t else near.cageProgress).toDouble()
        snapCageR = eng.cageRadiusFor(snapCagePhase); snapCageH = 1.15 * snapCageR
        snapMade = near.made; snapReleased = near.released; snapElongation = 0.0; snapRiboWaiting = false
        temperature = (a.temperature + (b.temperature - a.temperature) * t).toDouble()
        shownProgress = (a.progress + (b.progress - a.progress) * t).toDouble()
        if (snapCagePhase != CAGE_OFF) replayCenter.fill(0.0)
        else { replayCenter[0] = cx / counted; replayCenter[1] = cy / counted; replayCenter[2] = cz / counted }
    }
    /** Seconds before the end of the recording that the replay is showing. */
    private fun replayAgo(): Double {
        if (recording.size == 0) return 0.0
        val k0 = floor(replayPos).toInt().coerceIn(0, recording.size - 1)
        val k1 = min(k0 + 1, recording.size - 1)
        val t = recording[k0].time + (recording[k1].time - recording[k0].time) * (replayPos - k0)
        return recording[recording.size - 1].time - t
    }

    // ---------- Saving: the current shape, or the recorded fold, as a PDB file ----------
    /** The current structure as a PDB file (Cα atoms only). */
    fun exportPdb(): String = lock.withLock {
        val now = Recording(eng.n).apply { add(eng, progress, temperature) }
        PdbWriter.write(eng, protein.name, listOf(now[0]))
    }
    /** The recorded fold as a multi-model PDB trajectory, or null with nothing recorded yet. */
    fun exportTrajectoryPdb(): String? = lock.withLock {
        if (recording.size < 2) return@withLock null
        PdbWriter.write(eng, protein.name, List(recording.size) { recording[it] })
    }

    // ---------- Sparks where new contacts form (with the effects setting) ----------
    private class Spark(val i: Int, val j: Int, val t: Double)
    private val sparks = ArrayList<Spark>()
    private val lastSpark = HashMap<Long, Double>()     // pairs that flicker in and out of contact spark once
    private var prevContacts = HashSet<Long>()
    private var curContacts = HashSet<Long>()
    private var havePrevContacts = false
    private fun detectSparks() {
        val ca = eng.contacts.a; val cb = eng.contacts.b; val m = eng.contacts.size
        curContacts.clear()
        var added = 0
        sparks.removeAll { clock - it.t > SPARK_LIFE }
        for (p in 0 until m) {
            val i = ca[p]; val j = cb[p]
            if (eng.chainOf[i] == eng.chainOf[j] && abs(j - i) < 4) continue
            val key = (i.toLong() shl 32) or j.toLong()
            curContacts.add(key)
            if (havePrevContacts && key !in prevContacts && added < 2 && sparks.size < 24 && clock - (lastSpark[key] ?: -1e9) > 4.0) {
                sparks.add(Spark(i, j, clock)); lastSpark[key] = clock; added++
            }
        }
        val swap = prevContacts; prevContacts = curContacts; curContacts = swap
        if (lastSpark.size > 4000) lastSpark.values.removeAll { clock - it > 4.0 }
        havePrevContacts = true
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

    /**
     * Drawing runs on the display thread without the lock: everything the physics thread changes (positions,
     * charges, flashes, events) is read from the snapshot update() takes; the rest only changes on this thread.
     */
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
        for (i in 0 until snapMade) { zmin = min(zmin, pz[i]); zmax = max(zmax, pz[i]) }
        val zspan = max(1.0, zmax - zmin)

        zMin = zmin; zSpan = zspan
        dof = settings.effects && n <= EFFECTS_MAX
        val byChain = colourByChain()
        val cageOn = snapCagePhase != CAGE_OFF
        if (cageOn) drawCage(c, front = false)
        if (riboAlpha > 0.01) drawRibosome(c)
        when (settings.viewStyle) {
            0 -> drawBeads(c, byChain, big)
            3 -> { drawBeads(c, byChain, big, overlay = true); drawCartoon(c, byChain, ribbons = true, alpha = 0.88f) }
            else -> drawCartoon(c, byChain, ribbons = settings.viewStyle == 1)
        }
        if (cageOn) drawCage(c, front = true)
        if (dof) drawSparks(c)

        // Reaction flashes: proton transfers (small rings) and disulfide chemistry (sulfur bursts)
        for (f in snapFlashes) {
            val age = eng.time - f.t; val i = f.res
            if (i >= n) continue
            if (f.kind == Flash.SS) {
                val k = (age / 1.2).toFloat()
                stroke.color = withAlpha(Col.SULFUR, 0.8f * (1 - k)); stroke.strokeWidth = 2 * density
                c.drawCircle(px[i], py[i], pr[i] + k * 30 * density, stroke)
            } else if (age < 0.45 && !big && (settings.viewStyle == 0 || settings.viewStyle == 3) && pr[i] > 3 * density) {
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
        if (eng.nChains == 1 && snapMade > 1) { label(0, 1, "N"); if (snapMade == n) label(n - 1, n - 2, "C") }
        else if (eng.nChains in 2..8) for (ch in 0 until eng.nChains) {
            val s0 = eng.chainStart[ch]
            if (eng.chainStart[ch + 1] - s0 > 1 && s0 + 1 < snapMade) label(s0, s0 + 1, eng.chainLetter(ch).toString())
        }

        if (!replaying) drawPull(c)
        if (selected in 0 until n && !replaying) {
            stroke.color = withAlpha(Col.TEXT, 0.9f); stroke.strokeWidth = 2 * density
            c.drawCircle(px[selected], py[selected], max(pr[selected], 4 * density) + 6 * density, stroke)
        }
        if (settings.hud && !replaying) drawHud(c)
        if (settings.showProgress) drawProgress(c)
        if (!replaying) selectedInfo?.let { if (selected in 0 until n) drawInspector(c, it) }
        if (replaying) drawReplayBar(c)
    }

    /** Optical tweezers: arrows on the two pulled ends, and how far apart they are. */
    private fun drawPull(c: Canvas) {
        val n = eng.n
        if (settings.pullPN <= 0 || n < 2) return
        val a = 0; val b = n - 1
        val dx = px[b] - px[a]; val dy = py[b] - py[a]; val len = max(1f, hypot(dx, dy))
        val ux = dx / len; val uy = dy / len
        stroke.color = Col.SULFUR; stroke.strokeWidth = 2.5f * density; stroke.strokeCap = Paint.Cap.ROUND
        fun arrow(x0: Float, y0: Float, sx: Float, sy: Float) {
            val l = 34 * density; val x1 = x0 + sx * l; val y1 = y0 + sy * l
            c.drawLine(x0 + sx * 8 * density, y0 + sy * 8 * density, x1, y1, stroke)
            val hx = -sy; val hy = sx; val hl = 8 * density
            c.drawLine(x1, y1, x1 - sx * hl + hx * hl * 0.6f, y1 - sy * hl + hy * hl * 0.6f, stroke)
            c.drawLine(x1, y1, x1 - sx * hl - hx * hl * 0.6f, y1 - sy * hl - hy * hl * 0.6f, stroke)
        }
        arrow(px[b], py[b], ux, uy); arrow(px[a], py[a], -ux, -uy)
        text.typeface = mono; text.textSize = dp(11f); text.color = Col.SULFUR; text.textAlign = Paint.Align.CENTER
        c.drawText("${settings.pullPN.roundToInt()} pN · ends ${eng.endToEnd.roundToInt()} Å apart", px[b] + ux * 50 * density, py[b] + uy * 50 * density + dp(14f), text)
        text.textAlign = Paint.Align.LEFT
    }

    private fun residueClass(aa: Char, q: Double) = when {
        aa == 'C' -> "Cysteine: can form disulfide bonds"
        aa == 'G' -> "Glycine: the smallest, most flexible"
        aa == 'P' -> "Proline: rigid, breaks helices"
        q > 0.5 -> "Positively charged (basic)"
        q < -0.5 -> "Negatively charged (acidic)"
        aa in "DE" -> "Acidic, neutral right now"
        aa in "KRH" -> "Basic, neutral right now"
        aa in hydrophobic -> "Hydrophobic: avoids water"
        else -> "Polar: likes water"
    }

    /** The inspector card for the tapped residue. */
    private fun drawInspector(c: Canvas, info: ProteinEngine.ResidueInfo) {
        val lines = ArrayList<String>()
        lines += residueClass(info.aa, info.charge)
        val qTxt = when { info.charge > 0.5 -> "Charge +1"; info.charge < -0.5 -> "Charge −1"; else -> "No charge" }
        lines += if (info.pKa != null) "$qTxt · pKa ${"%.1f".format(info.pKa)}, ${if (info.protonated == true) "protonated" else "deprotonated"}" else qTxt
        lines += when (info.ss) { 1 -> "In a helix"; 2 -> "In a β-strand"; else -> "In a loop" }
        lines += when {
            info.contacts >= 5 -> "Buried: ${info.contacts} contacts"
            info.contacts >= 2 -> "Partly buried: ${info.contacts} contacts"
            else -> "Exposed to water"
        }
        if (info.partner >= 0) lines += "Disulfide with ${eng.residueLabel(info.partner)}"
        if (info.nativeTotal > 0) lines += "Real-structure contacts: ${info.nativeFormed} of ${info.nativeTotal}"
        val pw = min(dp(250f), w - dp(32f)); val lh = dp(17f)
        val ph = dp(14f) + dp(22f) + lines.size * lh + dp(10f)
        val x0 = w - pw - dp(16f)
        val y0 = h - bottomInset - (if (wallpaperMode) dp(110f) else dp(16f)) - ph
        fill.color = Col.PANEL; c.drawRoundRect(x0, y0, x0 + pw, y0 + ph, dp(10f), dp(10f), fill)
        stroke.color = Col.LINE; stroke.strokeWidth = density; c.drawRoundRect(x0, y0, x0 + pw, y0 + ph, dp(10f), dp(10f), stroke)
        fill.color = residueColor(selected); c.drawCircle(x0 + dp(20f), y0 + dp(22f), dp(6f), fill)
        text.textAlign = Paint.Align.LEFT; text.typeface = sansBold; text.textSize = dp(15f); text.color = Col.TEXT
        c.drawText(info.label, x0 + dp(34f), y0 + dp(27f), text)
        text.typeface = sans; text.textSize = dp(12f); text.color = Col.HAZE
        for ((k, l) in lines.withIndex()) c.drawText(ellipsize(l, pw - dp(28f)), x0 + dp(14f), y0 + dp(36f) + (k + 1) * lh - dp(4f), text)
    }

    /** Energy against native contacts: an unfolded chain sits high on the left, the folded protein low on the right. */
    private fun drawFunnel(c: Canvas, x: Float, y: Float, fw: Float, fh: Float) {
        stroke.color = Col.LINE; stroke.strokeWidth = density
        c.drawLine(x, y + fh, x + fw, y + fh, stroke); c.drawLine(x, y, x, y + fh, stroke)
        if (funLen < 2) return
        var lo = Float.MAX_VALUE; var hi = -Float.MAX_VALUE
        for (k in 0 until funLen) { lo = min(lo, funE[k]); hi = max(hi, funE[k]) }
        if (hi - lo < 5) hi = lo + 5
        val pad = dp(3f)
        for (k in 0 until funLen) {
            val idx = (funHead - funLen + k + funQ.size) % funQ.size
            val age = k / funLen.toFloat()                        // 0 oldest … 1 newest
            val px0 = x + pad + funQ[idx].coerceIn(0f, 1f) * (fw - 2 * pad)
            val py0 = y + pad + (hi - funE[idx]) / (hi - lo) * (fh - 2 * pad)   // high energy at the top
            fill.color = withAlpha(mix(Col.POLAR, Col.HYDRO, age), 0.15f + 0.6f * age)
            c.drawCircle(px0, py0, dp(1.6f), fill)
        }
        val last = (funHead - 1 + funQ.size) % funQ.size
        fill.color = Col.HYDRO
        c.drawCircle(x + pad + funQ[last].coerceIn(0f, 1f) * (fw - 2 * pad), y + pad + (hi - funE[last]) / (hi - lo) * (fh - 2 * pad), dp(3.5f), fill)
    }

    /** A small bar at the top: how close to folded (or, without a known structure, to collapsed). */
    private fun drawProgress(c: Canvas) {
        val pw = min(dp(220f), w - dp(32f)); val ph = dp(34f)
        val x0 = (w - pw) / 2; val y0 = topInset + (if (wallpaperMode) dp(28f) else dp(16f))
        fill.color = Col.PANEL; c.drawRoundRect(x0, y0, x0 + pw, y0 + ph, dp(9f), dp(9f), fill)
        stroke.color = Col.LINE; stroke.strokeWidth = density; c.drawRoundRect(x0, y0, x0 + pw, y0 + ph, dp(9f), dp(9f), stroke)
        val progress = shownProgress
        val pct = (progress * 100).roundToInt()
        text.textAlign = Paint.Align.LEFT; text.typeface = sans; text.textSize = dp(11f); text.color = Col.HAZE; text.letterSpacing = 0.04f
        val label = if (snapRiboWaiting) "Ribosome waits · domain folding"
                    else if (snapTranslating) "Ribosome · ${snapMade} of ${eng.n} made"
                    else (if (progressIsFolding) "Folded" else "Collapsed") + (if (settings.assist > 0) " · assisted" else "")
        c.drawText(ellipsize(label, pw - dp(70f)), x0 + dp(12f), y0 + dp(15f), text)
        text.letterSpacing = 0f
        text.textAlign = Paint.Align.RIGHT; text.typeface = mono; text.textSize = dp(12f); text.color = Col.TEXT
        c.drawText("$pct %", x0 + pw - dp(12f), y0 + dp(15f), text)
        text.textAlign = Paint.Align.LEFT
        val bx = x0 + dp(12f); val bw = pw - dp(24f); val by = y0 + dp(23f)
        fill.color = Col.LINE; c.drawRoundRect(bx, by, bx + bw, by + dp(4f), dp(2f), dp(2f), fill)
        fill.color = if (pct >= 99) Col.STRAND else mix(Col.HAZE, Col.POLAR, progress.toFloat())
        if (pct > 0) c.drawRoundRect(bx, by, bx + bw * progress.toFloat(), by + dp(4f), dp(2f), dp(2f), fill)
    }

    // Depth cueing: far things fade into the background
    private var zMin = 0.0
    private var zSpan = 1.0
    private fun near(zv: Double) = ((zv - zMin) / zSpan).toFloat().coerceIn(0f, 1f)
    private fun fog(col: Int, zv: Double) = mix(Col.INK, col, 0.35f + 0.65f * near(zv))
    // Depth of field (effects setting): the front of the molecule is in focus, the back softens
    private var dof = false
    private fun blurAt(nearness: Float): Float { if (!dof) return 0f; val f = 1 - nearness; return (f * f * 1.15f).coerceIn(0f, 1f) }

    // ---------- Style 0: beads on a backbone ----------
    private fun drawBeads(c: Canvas, byChain: Boolean, big: Boolean, overlay: Boolean = false) {
        val n = eng.n
        if (overlay) {
            // Under a cartoon: smaller, see-through beads only, depth-sorted
            var m = 0
            for (i in 0 until snapMade) keys[m++] = depthKey(pz[i], i)
            java.util.Arrays.sort(keys, 0, m)
            for (k in 0 until m) { val i = (keys[k] and 0xFFFFFFFFL).toInt(); drawResidue(c, i, fog(drawColor(i, byChain), pz[i]), near(pz[i]), big, alpha = 0.55f, scale = 0.8f) }
            return
        }
        // Hydrophobic contacts and salt bridges sit underneath everything (live only: a replay doesn't record them)
        if (!replaying) drawContacts(c)

        // Depth-sorted backbone segments (id < n), disulfides (n..n+63) and residues (n+64…)
        var m = 0
        for (i in 0 until snapMade - 1) if (eng.chainOf[i] == eng.chainOf[i + 1]) keys[m++] = depthKey((pz[i] + pz[i + 1]) / 2 - 0.01, i)
        val ssList = snapDisulfides
        for (k in 0 until min(ssList.size, 64)) { val d = ssList[k]; keys[m++] = depthKey((pz[d[0]] + pz[d[1]]) / 2 - 0.02, n + k) }
        for (i in 0 until snapMade) keys[m++] = depthKey(pz[i], n + 64 + i)
        java.util.Arrays.sort(keys, 0, m)
        for (k in 0 until m) {
            val id = (keys[k] and 0xFFFFFFFFL).toInt()
            when {
                id < n -> {
                    val i = id; val a = snapSS[i]; val b = snapSS[i + 1]
                    val kind = if (a == b) a else 0
                    val col = if (byChain) chainCol[i] else when (kind) { 1 -> Col.HELIX; 2 -> Col.STRAND; else -> Col.BOND }
                    val zc = (pz[i] + pz[i + 1]) / 2
                    val width = (if (kind != 0) 1.3f else 0.55f) * (ps[i] + ps[i + 1]) / 2
                    val bl = blurAt(near(zc))
                    if (bl > 0.06f) {
                        stroke.color = withAlpha(fog(col, zc), 0.22f); stroke.strokeWidth = width * (1 + 1.2f * bl)
                        c.drawLine(px[i], py[i], px[i + 1], py[i + 1], stroke)
                    }
                    stroke.color = withAlpha(fog(col, zc), 1 - 0.4f * bl)
                    stroke.strokeWidth = width
                    c.drawLine(px[i], py[i], px[i + 1], py[i + 1], stroke)
                }
                id < n + 64 -> drawDisulfide(c, ssList.getOrNull(id - n))
                else -> { val i = id - n - 64; drawResidue(c, i, fog(drawColor(i, byChain), pz[i]), near(pz[i]), big) }
            }
        }
    }

    private fun drawContacts(c: Canvas) {
        val n = eng.n
        stroke.strokeCap = Paint.Cap.ROUND
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
    }

    private fun drawDisulfide(c: Canvas, d: IntArray?) {
        if (d == null || d[0] >= eng.n || d[1] >= eng.n) return
        val i = d[0]; val j = d[1]
        stroke.strokeCap = Paint.Cap.ROUND
        stroke.color = withAlpha(fog(Col.SULFUR, (pz[i] + pz[j]) / 2), 0.95f)
        stroke.strokeWidth = 0.7f * (ps[i] + ps[j]) / 2
        c.drawLine(px[i], py[i], px[j], py[j], stroke)
    }

    // ---------- Styles 1 and 2: cartoon ribbons, or a plain backbone trace ----------
    // A Catmull–Rom spline runs through the Cα atoms, CARTOON_SUB samples per residue. Each residue gets a
    // "normal" pointing into the curve (toward a helix axis); the ribbon's width runs along tangent × normal,
    // so helices twist into spirals and strands lie flat. Brightness follows how squarely a face points at you.
    private val CARTOON_SUB get() = if (eng.n > 800) 2 else 5
    private var spX = DoubleArray(0); private var spY = DoubleArray(0); private var spZ = DoubleArray(0)
    private var spWx = DoubleArray(0); private var spWy = DoubleArray(0); private var spWz = DoubleArray(0)
    private var spNx = DoubleArray(0); private var spNy = DoubleArray(0); private var spNz = DoubleArray(0)
    private var spU = DoubleArray(0); private var spChain = IntArray(0)
    private var resNx = DoubleArray(0); private var resNy = DoubleArray(0); private var resNz = DoubleArray(0)
    private val quad = Path()
    private fun allocCartoon(n: Int) {
        val m = n * 5 + 8
        spX = DoubleArray(m); spY = DoubleArray(m); spZ = DoubleArray(m)
        spWx = DoubleArray(m); spWy = DoubleArray(m); spWz = DoubleArray(m)
        spNx = DoubleArray(m); spNy = DoubleArray(m); spNz = DoubleArray(m)
        spU = DoubleArray(m); spChain = IntArray(m)
        resNx = DoubleArray(n); resNy = DoubleArray(n); resNz = DoubleArray(n)
    }

    private var tpx = 0f; private var tpy = 0f; private var tpz = 0.0; private var tps = 0f
    private fun projectPoint(x: Double, y: Double, z: Double) {
        val a = yaw + pageYaw; val cam = cam()
        val cy = cos(a); val sy = sin(a); val cp = cos(pitch); val sp = sin(pitch)
        val dx = x - view[0]; val dy = y - view[1]; val dz = z - view[2]
        val x1 = cy * dx + sy * dz; val z1 = -sy * dx + cy * dz
        val y2 = cp * dy - sp * z1; val z2 = sp * dy + cp * z1
        val s = zoom * cam / max(cam - z2, cam * 0.2)
        tpx = (w / 2 + x1 * s).toFloat(); tpy = (h / 2 + y2 * s).toFloat(); tpz = z2; tps = s.toFloat()
    }
    /** How squarely a direction points at the viewer, 0…1. */
    private fun facing(vx: Double, vy: Double, vz: Double): Float {
        val a = yaw + pageYaw
        val z1 = -sin(a) * vx + cos(a) * vz
        return abs(sin(pitch) * vy + cos(pitch) * z1).toFloat()
    }

    private fun buildSpline(): Int {
        val n = eng.n; val x = snapX; val y = snapY; val z = snapZ; val ch = eng.chainOf
        // Per-residue normals, pointing into the local curve; flipped along strands so the sheet doesn't twist
        for (i in 0 until n) {
            val a = if (i > 0 && ch[i - 1] == ch[i]) i - 1 else -1
            val b = if (i < n - 1 && ch[i + 1] == ch[i]) i + 1 else -1
            var nx = 0.0; var ny = 0.0; var nz = 0.0
            if (a >= 0 && b >= 0) { nx = x[a] + x[b] - 2 * x[i]; ny = y[a] + y[b] - 2 * y[i]; nz = z[a] + z[b] - 2 * z[i] }
            val len = sqrt(nx * nx + ny * ny + nz * nz)
            if (len < 1e-6) { if (i > 0 && ch[i - 1] == ch[i]) { nx = resNx[i - 1]; ny = resNy[i - 1]; nz = resNz[i - 1] } else { nx = 0.0; ny = 1.0; nz = 0.0 } }
            else { nx /= len; ny /= len; nz /= len }
            if (i > 0 && ch[i - 1] == ch[i] && snapSS[i] != 1 && nx * resNx[i - 1] + ny * resNy[i - 1] + nz * resNz[i - 1] < 0) { nx = -nx; ny = -ny; nz = -nz }
            resNx[i] = nx; resNy[i] = ny; resNz[i] = nz
        }
        for (i in 0 until n) if (eng.chainStart.contains(i) && i + 1 < n && ch[i + 1] == ch[i]) { resNx[i] = resNx[i + 1]; resNy[i] = resNy[i + 1]; resNz[i] = resNz[i + 1] }
        for (i in n - 1 downTo 1) if (ch[i - 1] == ch[i] && (i == n - 1 || ch[i + 1] != ch[i])) { resNx[i] = resNx[i - 1]; resNy[i] = resNy[i - 1]; resNz[i] = resNz[i - 1] }
        // Samples along each chain
        val sub = CARTOON_SUB
        var m = 0
        for (c0 in 0 until eng.nChains) {
            val s0 = eng.chainStart[c0]; val s1 = min(eng.chainStart[c0 + 1], snapMade) - 1
            if (s1 <= s0) continue
            for (i in s0 until s1) {
                val i0 = max(s0, i - 1); val i2 = i + 1; val i3 = min(s1, i + 2)
                val steps = if (i == s1 - 1) sub + 1 else sub
                for (k in 0 until steps) {
                    val t = k / sub.toDouble(); val t2 = t * t; val t3 = t2 * t
                    // Catmull–Rom weights
                    val w0 = -0.5 * t3 + t2 - 0.5 * t; val w1 = 1.5 * t3 - 2.5 * t2 + 1
                    val w2 = -1.5 * t3 + 2 * t2 + 0.5 * t; val w3 = 0.5 * t3 - 0.5 * t2
                    spX[m] = w0 * x[i0] + w1 * x[i] + w2 * x[i2] + w3 * x[i3]
                    spY[m] = w0 * y[i0] + w1 * y[i] + w2 * y[i2] + w3 * y[i3]
                    spZ[m] = w0 * z[i0] + w1 * z[i] + w2 * z[i2] + w3 * z[i3]
                    var nx = (1 - t) * resNx[i] + t * resNx[i2]; var ny = (1 - t) * resNy[i] + t * resNy[i2]; var nz = (1 - t) * resNz[i] + t * resNz[i2]
                    val nl = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-9); nx /= nl; ny /= nl; nz /= nl
                    val tx = x[i2] - x[i]; val ty = y[i2] - y[i]; val tz = z[i2] - z[i]
                    var wx = ty * nz - tz * ny; var wy = tz * nx - tx * nz; var wz = tx * ny - ty * nx
                    val wl = sqrt(wx * wx + wy * wy + wz * wz).coerceAtLeast(1e-9); wx /= wl; wy /= wl; wz /= wl
                    spNx[m] = nx; spNy[m] = ny; spNz[m] = nz; spWx[m] = wx; spWy[m] = wy; spWz[m] = wz
                    spU[m] = i + t; spChain[m] = c0
                    m++
                }
            }
        }
        return m
    }

    /** Half-width of the ribbon at position u (in residues) for a segment starting at u0, Å; 0 means a thin tube. */
    private fun halfWidth(u: Double, u0: Double): Double {
        val i = (u0 + 0.5).toInt().coerceIn(0, eng.n - 1)   // the residue this segment belongs to
        val kind = snapSS[i]
        return when (kind) {
            1 -> 1.5
            2 -> {
                // Arrowhead over the last residue step of a strand
                var e = i; while (e + 1 < eng.n && snapSS[e + 1] == 2 && eng.chainOf[e + 1] == eng.chainOf[i]) e++
                if (u0 >= e - 1 - 1e-6) 2.6 * (e - u).coerceIn(0.0, 1.0) + 0.1 else 1.5
            }
            else -> 0.0
        }
    }

    private fun drawCartoon(c: Canvas, byChain: Boolean, ribbons: Boolean, alpha: Float = 1f) {
        val n = eng.n
        val m = buildSpline()
        // Depth-sort spline segments (id < m) and disulfides (m…m+63)
        var cnt = 0
        for (k in 0 until m - 1) {
            if (spChain[k] != spChain[k + 1]) continue
            projectPoint(spX[k], spY[k], spZ[k]); val z0 = tpz
            projectPoint(spX[k + 1], spY[k + 1], spZ[k + 1])
            keys[cnt++] = depthKey((z0 + tpz) / 2, k)
        }
        val ssList = snapDisulfides
        for (k in 0 until min(ssList.size, 64)) { val d = ssList[k]; keys[cnt++] = depthKey((pz[d[0]] + pz[d[1]]) / 2 + 0.5, m + k) }
        java.util.Arrays.sort(keys, 0, cnt)
        for (q in 0 until cnt) {
            val id = (keys[q] and 0xFFFFFFFFL).toInt()
            if (id >= m) { drawDisulfide(c, ssList.getOrNull(id - m)); continue }
            val k = id
            val u0 = spU[k]; val u1 = spU[k + 1]
            val res = (u0 + 0.5).toInt().coerceIn(0, n - 1)
            val kind = if (ribbons) snapSS[res] else 0
            val base = if (byChain) chainCol[res] else when (kind) { 1 -> Col.HELIX; 2 -> Col.STRAND; else -> if (ribbons) Col.SPECIAL else residueColor(res) }
            val hw0 = if (ribbons) halfWidth(u0, u0) else 0.0
            val hw1 = if (ribbons) halfWidth(u1, u0) else 0.0
            projectPoint(spX[k], spY[k], spZ[k]); val ax = tpx; val ay = tpy; val az = tpz; val aS = tps
            projectPoint(spX[k + 1], spY[k + 1], spZ[k + 1]); val bx = tpx; val by = tpy; val bz = tpz; val bS = tps
            val zc = (az + bz) / 2
            val bl = blurAt(near(zc))
            if (hw0 == 0.0 && hw1 == 0.0) {
                // Loops and the plain trace: a round tube
                stroke.strokeCap = Paint.Cap.ROUND
                val width = (if (ribbons) 0.7f else 0.9f) * (aS + bS) / 2
                if (bl > 0.06f) {
                    stroke.color = withAlpha(fog(base, zc), alpha * 0.22f); stroke.strokeWidth = width * (1 + 1.2f * bl)
                    c.drawLine(ax, ay, bx, by, stroke)
                }
                stroke.color = withAlpha(fog(base, zc), alpha * (1 - 0.4f * bl))
                stroke.strokeWidth = width
                c.drawLine(ax, ay, bx, by, stroke)
                continue
            }
            // Ribbon quad between the two samples
            projectPoint(spX[k] + spWx[k] * hw0, spY[k] + spWy[k] * hw0, spZ[k] + spWz[k] * hw0); val l0x = tpx; val l0y = tpy
            projectPoint(spX[k] - spWx[k] * hw0, spY[k] - spWy[k] * hw0, spZ[k] - spWz[k] * hw0); val r0x = tpx; val r0y = tpy
            projectPoint(spX[k + 1] + spWx[k + 1] * hw1, spY[k + 1] + spWy[k + 1] * hw1, spZ[k + 1] + spWz[k + 1] * hw1); val l1x = tpx; val l1y = tpy
            projectPoint(spX[k + 1] - spWx[k + 1] * hw1, spY[k + 1] - spWy[k + 1] * hw1, spZ[k + 1] - spWz[k + 1] * hw1); val r1x = tpx; val r1y = tpy
            val light = 0.45f + 0.55f * facing(spNx[k], spNy[k], spNz[k])
            val faceCol = mix(Col.INK, fog(base, zc), light)
            quad.reset(); quad.moveTo(l0x, l0y); quad.lineTo(l1x, l1y); quad.lineTo(r1x, r1y); quad.lineTo(r0x, r0y); quad.close()
            if (bl > 0.06f) {
                // Out of focus: a soft, spread edge around a slightly fainter face
                stroke.strokeCap = Paint.Cap.ROUND; stroke.strokeWidth = (1 + 6 * bl) * density
                stroke.color = withAlpha(faceCol, alpha * 0.3f); c.drawPath(quad, stroke)
            }
            fill.color = withAlpha(faceCol, alpha * (1 - 0.3f * bl))
            c.drawPath(quad, fill)
            // A fine outline on the edges keeps thin, edge-on ribbons visible
            if (bl < 0.3f) {
                stroke.strokeCap = Paint.Cap.BUTT; stroke.strokeWidth = 0.7f * density
                stroke.color = withAlpha(fog(base, zc), alpha)
                c.drawLine(l0x, l0y, l1x, l1y, stroke); c.drawLine(r0x, r0y, r1x, r1y, stroke)
            }
        }
        stroke.strokeCap = Paint.Cap.ROUND
        // The residue being pulled
        val g = eng.grab
        if (g in 0 until n) {
            stroke.color = withAlpha(Col.TEXT, 0.7f); stroke.strokeWidth = 1.5f * density
            c.drawCircle(px[g], py[g], 6 * density, stroke)
        }
    }

    private fun drawResidue(c: Canvas, i: Int, col: Int, nearness: Float, big: Boolean, alpha: Float = 1f, scale: Float = 1f) {
        val r = pr[i] * scale; val x = px[i]; val y = py[i]
        val bl = blurAt(nearness)
        if (bl > 0.06f) {
            // Out of focus: a fainter disc inside two soft halos, with no sharp outline or highlight
            fill.color = withAlpha(col, 0.16f * alpha); c.drawCircle(x, y, r * (1 + 0.6f * bl), fill)
            fill.color = withAlpha(col, 0.22f * alpha); c.drawCircle(x, y, r * (1 + 0.3f * bl), fill)
            fill.color = withAlpha(col, alpha * (1 - 0.45f * bl)); c.drawCircle(x, y, r * (1 - 0.08f * bl), fill)
        } else {
            // In focus; the nearest residues glow faintly
            if (dof && nearness > 0.88f) { fill.color = withAlpha(col, 0.14f * alpha); c.drawCircle(x, y, r * 1.5f, fill) }
            fill.color = withAlpha(col, alpha); c.drawCircle(x, y, r, fill)
            if (!big) {
                stroke.color = withAlpha(0x04060C, 0.45f * alpha); stroke.strokeWidth = density; c.drawCircle(x, y, r, stroke)
                fill.color = withAlpha(0xFFFFFF, (0.1f + 0.2f * nearness) * alpha); c.drawCircle(x - r * 0.3f, y - r * 0.3f, r * 0.38f, fill)
            }
        }
        val q = snapQ[i]
        if (abs(q) > 0.5 && r > 5 * density && bl < 0.3f) {
            stroke.color = withAlpha(0x04060C, 0.75f * alpha); stroke.strokeWidth = max(1.2f * density, r * 0.18f)
            val g = r * 0.42f
            c.drawLine(x - g, y, x + g, y, stroke)
            if (q > 0) c.drawLine(x, y - g, x, y + g, stroke)
        }
        if (i == eng.grab) {
            stroke.color = withAlpha(Col.TEXT, 0.7f); stroke.strokeWidth = 1.5f * density
            c.drawCircle(x, y, r + 5 * density, stroke)
        }
    }

    // ---------- Sparks where contacts form ----------
    private fun drawSparks(c: Canvas) {
        val n = eng.n
        for (k in sparks.indices.reversed()) {
            val sp = sparks.getOrNull(k) ?: continue
            val age = ((clock - sp.t) / SPARK_LIFE).toFloat()
            if (age !in 0f..1f || sp.i >= n || sp.j >= n) continue
            projectPoint((snapX[sp.i] + snapX[sp.j]) / 2, (snapY[sp.i] + snapY[sp.j]) / 2, (snapZ[sp.i] + snapZ[sp.j]) / 2)
            val x = tpx; val y = tpy; val life = 1 - age
            fill.color = withAlpha(0xFFFFE2A8.toInt(), 0.16f * life); c.drawCircle(x, y, dp(4f + 14f * age), fill)
            stroke.strokeCap = Paint.Cap.ROUND; stroke.strokeWidth = dp(1.3f)
            stroke.color = withAlpha(0xFFFFF1D0.toInt(), 0.9f * life)
            val len = dp(3f + 10f * age); val rot = sp.i * 0.7 + age * 1.5
            for (m in 0 until 4) {
                val a = rot + m * PI / 2; val ca = cos(a).toFloat(); val sa = sin(a).toFloat()
                c.drawLine(x + ca * len * 0.35f, y + sa * len * 0.35f, x + ca * len, y + sa * len, stroke)
            }
            fill.color = withAlpha(0xFFFFFF, life); c.drawCircle(x, y, dp(0.6f) + dp(1.8f) * life, fill)
        }
    }

    // ---------- Ribosome ----------
    private var riboAlpha = 0.0
    private val riboPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    /**
     * The ribosome the chain is coming out of, above the tunnel mouth (the top of the screen): the large subunit
     * with the exit tunnel through it, the small subunit on top with the mRNA threading through. Drawn behind the
     * protein, so the chain inside the tunnel shows through it like a cutaway.
     */
    private fun drawRibosome(c: Canvas) {
        val a = riboAlpha.toFloat()
        val ey = eng.exitY
        val big = 62.0
        fun blob(cy: Double, r: Double, col: Int) {
            projectPoint(0.0, cy, 0.0)
            val rad = (r * tps).toFloat()
            riboPaint.shader = RadialGradient(tpx - rad * 0.3f, tpy + rad * 0.3f, rad * 1.35f,
                intArrayOf(withAlpha(mix(col, Col.TEXT, 0.35f), 0.9f * a), withAlpha(col, 0.85f * a), withAlpha(mix(col, Col.INK, 0.6f), 0.8f * a)),
                floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
            c.drawCircle(tpx, tpy, rad, riboPaint)
        }
        // Small subunit first (it sits beyond the large one), with the mRNA running through it
        val smallY = ey - 2 * big - 14
        blob(smallY, 0.62 * big, 0xFF6A5A8C.toInt())
        stroke.strokeCap = Paint.Cap.ROUND
        stroke.color = withAlpha(Col.SULFUR, 0.7f * a); stroke.strokeWidth = dp(1.6f)
        projectPoint(-120.0, smallY + 8, 0.0); val m0x = tpx; val m0y = tpy
        projectPoint(120.0, smallY + 8, 0.0)
        c.drawLine(m0x, m0y, tpx, tpy, stroke)
        // Codon ticks that slide along as residues are added (one codon, three bases, per residue)
        val shift = ((snapMade + snapElongation) * 9.0) % 18.0
        stroke.strokeWidth = dp(1f)
        var tx = -117.0 + 18.0 - shift
        while (tx < 118) {
            projectPoint(tx, smallY + 8, 0.0); val ax = tpx; val ay = tpy
            projectPoint(tx, smallY + 12, 0.0)
            c.drawLine(ax, ay, tpx, tpy, stroke)
            tx += 18.0
        }
        blob(ey - big + 4, big, 0xFF4E5A8E.toInt())
        // The tunnel: a faint groove up through the large subunit, and a rim at its mouth
        projectPoint(0.0, ey, 0.0); val mx = tpx; val my = tpy; val mouth = (4.5 * tps).toFloat()
        projectPoint(0.0, ey - 2 * big + 20, 0.0)
        stroke.color = withAlpha(Col.INK, 0.35f * a); stroke.strokeWidth = 1.6f * mouth
        c.drawLine(mx, my, tpx, tpy, stroke)
        stroke.color = withAlpha(Col.HAZE, 0.6f * a); stroke.strokeWidth = dp(1.4f); c.drawCircle(mx, my, mouth, stroke)
        projectPoint(0.0, ey - 2 * big + 8, 0.0)
        text.typeface = sans; text.textSize = dp(11f); text.textAlign = Paint.Align.CENTER; text.color = withAlpha(Col.HAZE, 0.9f * a)
        c.drawText(if (snapTranslating) "Ribosome · residue ${snapMade} of ${eng.n}" else "Ribosome", tpx, tpy, text)
        text.textAlign = Paint.Align.LEFT
    }

    // ---------- Chaperone cage ----------
    /**
     * GroEL: a barrel of two stacked seven-part rings around the origin (y is its axis; the open end is at −y,
     * the top of the screen), amber while its
     * hydrophobic lining is grabbing the chain, teal once the GroES lid is on. Called twice: the back half
     * before the protein, the front half (fainter) over it.
     */
    private fun drawCage(c: Canvas, front: Boolean) {
        val phase = snapCagePhase
        val r = snapCageR; val hh = snapCageH; val prog = snapCageProgress
        projectPoint(0.0, 0.0, 0.0); val zc = tpz
        val col = when (phase) { CAGE_CAPTURE -> Col.HYDRO; CAGE_ENCLOSED -> Col.POLAR; else -> Col.HAZE }
        val fade = if (phase == CAGE_RELEASE) (1 - 0.5 * prog).toFloat() else 1f
        val a = (if (front) 0.28f else 0.5f) * fade
        stroke.strokeCap = Paint.Cap.ROUND
        fun segment(x0: Double, y0: Double, z0: Double, x1: Double, y1: Double, z1: Double, width: Float, alpha: Float = a) {
            projectPoint(x0, y0, z0); val ax = tpx; val ay = tpy; val az = tpz
            projectPoint(x1, y1, z1); val bx = tpx; val by = tpy; val bz = tpz
            if (((az + bz) / 2 > zc) != front) return
            stroke.color = withAlpha(fog(col, (az + bz) / 2), alpha); stroke.strokeWidth = width
            c.drawLine(ax, ay, bx, by, stroke)
        }
        val seg = 42
        fun ring(y: Double, radius: Double, width: Float, alpha: Float = a) {
            for (k in 0 until seg) {
                val t0 = 2 * PI * k / seg; val t1 = 2 * PI * (k + 1) / seg
                segment(radius * cos(t0), y, radius * sin(t0), radius * cos(t1), y, radius * sin(t1), width, alpha)
            }
        }
        // Rims, the equator between the two rings, and the domain boundaries inside each ring
        ring(-hh, r, dp(2.2f)); ring(hh, r, dp(2.2f)); ring(0.0, r, dp(1.6f))
        ring(-0.5 * hh, r, dp(0.9f), a * 0.6f); ring(0.5 * hh, r, dp(0.9f), a * 0.6f)
        // Seven subunits per ring
        for (k in 0 until 7) {
            val t = 2 * PI * k / 7 + PI / 14
            val x = r * cos(t); val z = r * sin(t)
            segment(x, -hh, z, x, hh, z, dp(1.4f))
        }
        // The GroES lid: drops on when the cage closes, lifts away on release
        val lidOffset = when (phase) {
            CAGE_ENCLOSED -> if (prog < 0.08) 14 * (1 - prog / 0.08) else 0.0
            CAGE_RELEASE -> 4 + 30 * prog
            else -> Double.NaN
        }
        if (!lidOffset.isNaN()) {
            val base = -(hh + 1.5 + lidOffset); val dome = -0.42 * r
            val la = a * (if (phase == CAGE_RELEASE) (1 - prog).toFloat() else 1f)
            ring(base, r * 0.96, dp(2f), la)
            for (k in 0 until 7) {
                val t = 2 * PI * k / 7
                for (m in 0 until 6) {
                    val s0 = (PI / 2) * m / 6; val s1 = (PI / 2) * (m + 1) / 6
                    segment(r * 0.96 * cos(s0) * cos(t), base + dome * sin(s0), r * 0.96 * cos(s0) * sin(t),
                        r * 0.96 * cos(s1) * cos(t), base + dome * sin(s1), r * 0.96 * cos(s1) * sin(t), dp(1.3f), la)
                }
            }
        }
        if (front) {
            projectPoint(0.0, -(hh + (if (lidOffset.isNaN()) 4.0 else 0.42 * r + 8)), 0.0)
            text.typeface = sans; text.textSize = dp(11f); text.textAlign = Paint.Align.CENTER
            text.color = withAlpha(col, 0.9f * fade)
            c.drawText(when (phase) {
                CAGE_CAPTURE -> "GroEL · hydrophobic lining grabs the chain"
                CAGE_ENCLOSED -> "GroES lid on · folding alone inside"
                else -> "Lid off · releasing"
            }, tpx, tpy - dp(10f), text)
            text.textAlign = Paint.Align.LEFT
        }
    }

    // ---------- Replay bar ----------
    /** Shown under the replay; each platform can say how its controls work. */
    var replayHint = "Drag to turn · tap to pause · drag along the bar to scrub"
    private var barL = 0f; private var barR = 0f; private var barT = 0f; private var barB = 0f
    private var trackL = 0f; private var trackR = 0f
    private val icon = Path()
    private fun drawReplayBar(c: Canvas) {
        val bw = min(dp(460f), w - dp(32f)); val bh = dp(62f)
        val x0 = (w - bw) / 2; val y0 = h - bottomInset - (if (wallpaperMode) dp(110f) else dp(20f)) - bh
        barL = x0; barR = x0 + bw; barT = y0; barB = y0 + bh
        fill.color = Col.PANEL; c.drawRoundRect(x0, y0, x0 + bw, y0 + bh, dp(10f), dp(10f), fill)
        stroke.color = Col.LINE; stroke.strokeWidth = density; c.drawRoundRect(x0, y0, x0 + bw, y0 + bh, dp(10f), dp(10f), stroke)
        // Play or pause symbol
        val ix = x0 + dp(16f); val iy = y0 + dp(17f)
        fill.color = Col.HYDRO
        if (replayPaused) {
            c.drawRect(ix, iy - dp(5f), ix + dp(3.5f), iy + dp(5f), fill); c.drawRect(ix + dp(6.5f), iy - dp(5f), ix + dp(10f), iy + dp(5f), fill)
        } else {
            icon.moveTo(ix, iy - dp(6f)); icon.lineTo(ix + dp(10f), iy); icon.lineTo(ix, iy + dp(6f)); icon.close(); c.drawPath(icon, fill)
        }
        text.textAlign = Paint.Align.LEFT; text.typeface = sansBold; text.textSize = dp(13f); text.color = Col.TEXT
        c.drawText((if (replayPaused) "Paused" else "Replay") + " · ${replaySpeed().roundToInt()}× speed", x0 + dp(34f), y0 + dp(22f), text)
        text.textAlign = Paint.Align.RIGHT; text.typeface = mono; text.textSize = dp(12f); text.color = Col.HAZE
        c.drawText("${replayAgo().roundToInt()} s ago", x0 + bw - dp(14f), y0 + dp(22f), text)
        // Track: the whole recording, with the play position
        trackL = x0 + dp(16f); trackR = x0 + bw - dp(16f)
        val ty = y0 + dp(35f)
        fill.color = Col.LINE; c.drawRoundRect(trackL, ty - dp(2f), trackR, ty + dp(2f), dp(2f), dp(2f), fill)
        val f = if (recording.size > 1) (replayPos / (recording.size - 1)).toFloat() else 0f
        val tx = trackL + (trackR - trackL) * f
        fill.color = Col.POLAR; c.drawRoundRect(trackL, ty - dp(2f), tx, ty + dp(2f), dp(2f), dp(2f), fill)
        fill.color = Col.TEXT; c.drawCircle(tx, ty, dp(6f), fill)
        text.textAlign = Paint.Align.LEFT; text.typeface = sans; text.textSize = dp(10.5f); text.color = Col.HAZE
        c.drawText(ellipsize(replayHint, bw - dp(32f)), x0 + dp(16f), y0 + dp(54f), text)
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
            statRows * dp(40f) + dp(4f) + dp(62f) + (if (structured) dp(92f) else 0f) + events.size * dp(14f) + pad
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
        val ureaTxt = if (settings.urea > 0) " · ${"%.1f".format(settings.urea)} M urea" else ""
        c.drawText("${temperature.roundToInt()} K · pH ${"%.1f".format(settings.ph)} · ${settings.salt} mM$ureaTxt", left + inner, y + dp(14f), text)
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
            text.color = if (i < snapMade) residueColor(i) else withAlpha(Col.HAZE, 0.3f)
            c.drawText(eng.seq[i].toString(), lx, ly, text)
            val s = snapSS[i]
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
        val rate = if (eng.n > 300 || physicsThread != null) " · ${(stepsPerSecond / 1000).let { "%.1f".format(it) }}k steps/s" else ""
        c.drawText("Energy, last 60 s$rate", left, y + dp(52f), text)
        y += dp(62f)
        if (structured) {
            drawFunnel(c, left, y, inner, dp(68f))
            text.typeface = mono; text.textSize = dp(9f); text.color = Col.HAZE; text.textAlign = Paint.Align.LEFT
            c.drawText("Folding funnel: energy vs native contacts (0 → 1)", left, y + dp(82f), text)
            y += dp(92f)
        }

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
        for (e in snapEvents) all.add(Triple(e.t, e.text, true))
        for (nt in notes) all.add(Triple(nt.t, nt.text, false))
        all.sortByDescending { it.first }
        return all.take(3)
    }
    private val tracePath = Path()
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
        val path = tracePath
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

    fun onPointer(e: PointerEvent): Boolean = lock.withLock { if (replaying) onReplayTouch(e) else onTouchLocked(e) }
    /** During a replay: drag to turn (even on the wallpaper), drag along the bar to scrub, tap to pause. */
    private fun onReplayTouch(e: PointerEvent): Boolean {
        when (e.actionMasked) {
            PointerEvent.ACTION_DOWN -> {
                downId = e.getPointerId(0); downX = e.x; downY = e.y; lastX = e.x; lastY = e.y; downTime = e.eventTime; moved = false
                scrubbing = e.x in barL..barR && e.y in barT..barB
                if (scrubbing) scrubTo(e.x)
            }
            PointerEvent.ACTION_MOVE -> {
                if (downId < 0) return false
                if (scrubbing) scrubTo(e.x)
                else {
                    if (!moved && hypot(e.x - downX, e.y - downY) > 8 * density) moved = true
                    if (moved) { yaw += (e.x - lastX) * 0.012 / density; pitch = (pitch + (e.y - lastY) * 0.012 / density).coerceIn(-1.3, 1.3) }
                }
                lastX = e.x; lastY = e.y
            }
            PointerEvent.ACTION_UP -> {
                if (downId >= 0 && !scrubbing && !moved && e.eventTime - downTime < 350) replayPaused = !replayPaused
                scrubbing = false; downId = -1
            }
            PointerEvent.ACTION_CANCEL, PointerEvent.ACTION_POINTER_DOWN -> { scrubbing = false; downId = -1 }
        }
        return true
    }
    private fun scrubTo(x: Float) {
        val f = ((x - trackL) / max(1f, trackR - trackL)).coerceIn(0f, 1f)
        replayPos = f * (recording.size - 1.0)
        showReplayFrame()
    }
    private fun onTouchLocked(e: PointerEvent): Boolean {
        if (!loaded) return false
        when (e.actionMasked) {
            PointerEvent.ACTION_DOWN -> {
                downId = e.getPointerId(0)
                downX = e.x; downY = e.y; lastX = e.x; lastY = e.y; downTime = e.eventTime; moved = false
                var best = -1; var bz = -Double.MAX_VALUE
                for (i in 0 until snapMade) {
                    val d = hypot(px[i] - e.x, py[i] - e.y)
                    if (d < pr[i] + 14 * density && pz[i] > bz) { bz = pz[i]; best = i }
                }
                eng.grab = best
                if (best >= 0) { unproject(e.x, e.y, pz[best], tmp); System.arraycopy(tmp, 0, eng.target, 0, 3) }
            }
            PointerEvent.ACTION_MOVE -> {
                if (downId < 0) return false
                if (!moved && hypot(e.x - downX, e.y - downY) > 8 * density) moved = true
                if (eng.grab >= 0) { unproject(e.x, e.y, pz[eng.grab], tmp); System.arraycopy(tmp, 0, eng.target, 0, 3) }
                else if (moved && !wallpaperMode) {
                    yaw += (e.x - lastX) * 0.012 / density
                    pitch = (pitch + (e.y - lastY) * 0.012 / density).coerceIn(-1.3, 1.3)
                }
                lastX = e.x; lastY = e.y
            }
            PointerEvent.ACTION_UP -> {
                if (downId >= 0 && !moved && e.eventTime - downTime < 350) tap(e.x, e.y, e.eventTime)
                eng.grab = -1; downId = -1
            }
            PointerEvent.ACTION_CANCEL -> { eng.grab = -1; downId = -1 }
            PointerEvent.ACTION_POINTER_DOWN -> { eng.grab = -1; moved = true }
        }
        return true
    }
    private fun tap(x: Float, y: Float, time: Long) {
        if (eng.grab >= 0) select(eng.grab)
        else {
            if (selected >= 0) { selected = -1; selectedInfo = null }
            ripples.add(doubleArrayOf(x.toDouble(), y.toDouble(), clock))
            unproject(x, y, 0.0, tmp)
            eng.kick(tmp[0], tmp[1], tmp[2], 14.0, 2.5)
        }
        if (time - lastTapTime < 330 && hypot(x - lastTapX, y - lastTapY) < 50 * density) { heat(); lastTapTime = 0 }
        else { lastTapTime = time; lastTapX = x; lastTapY = y }
    }
}
