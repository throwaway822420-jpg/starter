package com.hydrophobiccollapse

import java.util.Locale
import kotlin.math.roundToInt

/**
 * The last couple of minutes of a simulation, a frame every [INTERVAL] seconds, for time-lapse replay and
 * for saving the fold as a PDB trajectory. Frames hold everything the renderer needs, so a replay can be
 * drawn (and turned, zoomed and scrubbed) without touching the engine.
 */
class Recording(val n: Int) {
    class Frame(
        val xyz: FloatArray,          // x, y, z per residue
        val charge: ByteArray,        // −1, 0, +1
        val ss: ByteArray,            // 0 coil, 1 helix, 2 strand
        val disulfides: IntArray,     // i, j, native (0/1) triples
        val time: Double,             // simulation clock, s
        val progress: Float,
        val energy: Float,
        val temperature: Float,
        val cagePhase: Int,
        val cageProgress: Float,
        val made: Int,                // residues made by the ribosome so far (n when it's done)
        val released: Int,            // residues out of the ribosome's tunnel
    )

    /** Frames kept: two minutes, fewer for very big proteins so memory stays under about 24 MB. */
    val capacity = (24_000_000 / (14L * n + 64)).toInt().coerceIn(40, (120 / INTERVAL).toInt())
    private val frames = ArrayDeque<Frame>()
    val size get() = frames.size
    operator fun get(k: Int) = frames[k]
    val seconds get() = if (frames.size < 2) 0.0 else frames.last().time - frames.first().time
    fun clear() = frames.clear()

    fun add(eng: ProteinEngine, progress: Double, temperature: Double) {
        val n = eng.n
        if (n != this.n) return
        val xyz = FloatArray(3 * n)
        for (i in 0 until n) { xyz[3 * i] = eng.x[i].toFloat(); xyz[3 * i + 1] = eng.y[i].toFloat(); xyz[3 * i + 2] = eng.z[i].toFloat() }
        val q = ByteArray(n) { eng.charge[it].roundToInt().coerceIn(-1, 1).toByte() }
        val ss = ByteArray(n) { eng.ss[it].toByte() }
        val ds = eng.disulfides
        val pairs = IntArray(ds.size * 3)
        for (k in ds.indices) { pairs[3 * k] = ds[k][0]; pairs[3 * k + 1] = ds[k][1]; pairs[3 * k + 2] = ds[k][2] }
        if (frames.size >= capacity) frames.removeFirst()
        frames.addLast(Frame(xyz, q, ss, pairs, eng.time, progress.toFloat(), eng.energy.toFloat(), temperature.toFloat(),
            eng.cagePhase, eng.cageProgress.toFloat(), eng.made, eng.released))
    }

    companion object { const val INTERVAL = 0.25 }
}

/** Writes Cα-only PDB files that PyMOL, ChimeraX, Mol* or VMD can open. */
object PdbWriter {
    /**
     * One model for a single frame, or MODEL/ENDMDL blocks for a trajectory. Each frame is centred on its
     * centre of mass. Secondary structure (HELIX/SHEET) and disulfides (SSBOND) come from the last frame.
     */
    fun write(eng: ProteinEngine, title: String, frames: List<Recording.Frame>): String {
        val sb = StringBuilder()
        val n = eng.n
        fun line(fmt: String, vararg args: Any) { sb.append(String.format(Locale.ROOT, fmt, *args)).append('\n') }
        fun chainId(i: Int) = eng.chainLetter(eng.chainOf[i]).toString()
        fun resSeq(i: Int) = i - eng.chainStart[eng.chainOf[i]] + 1
        line("HEADER    PROTEIN FOLDING SIMULATION")
        line("TITLE     %s", title.uppercase(Locale.ROOT).take(60))
        line("REMARK   1 HYDROPHOBIC COLLAPSE: ONE BEAD PER RESIDUE (CA ATOMS ONLY)")
        if (frames.size > 1) line("REMARK   1 %d FRAMES, %.1f S OF SIMULATION", frames.size, frames.last().time - frames.first().time)
        val last = frames.last()
        // Secondary structure runs from the last frame
        var helixNo = 0; var sheetNo = 0
        var i = 0
        while (i < n) {
            val kind = last.ss[i].toInt()
            var j = i
            while (j + 1 < n && last.ss[j + 1].toInt() == kind && eng.chainOf[j + 1] == eng.chainOf[i]) j++
            if (kind == 1 && j - i >= 3) {
                helixNo++
                line("HELIX  %3d %3d %3s %1s %4d  %3s %1s %4d %2d%30s %5d", helixNo, helixNo, eng.residueName3(i), chainId(i), resSeq(i),
                    eng.residueName3(j), chainId(j), resSeq(j), 1, "", j - i + 1)
            } else if (kind == 2 && j - i >= 1) {
                sheetNo++
                line("SHEET  %3d %3s%2d %3s %1s%4d  %3s %1s%4d %2d", 1, "S$sheetNo".take(3), 1, eng.residueName3(i), chainId(i), resSeq(i),
                    eng.residueName3(j), chainId(j), resSeq(j), 0)
            }
            i = j + 1
        }
        val ds = last.disulfides
        for (k in 0 until ds.size / 3) {
            val a = ds[3 * k]; val b = ds[3 * k + 1]
            if (a < n && b < n) line("SSBOND %3d CYS %1s %4d    CYS %1s %4d", k + 1, chainId(a), resSeq(a), chainId(b), resSeq(b))
        }
        val multi = frames.size > 1
        for ((m, f) in frames.withIndex()) {
            if (multi) line("MODEL     %4d", m + 1)
            var cx = 0.0; var cy = 0.0; var cz = 0.0
            for (r in 0 until n) { cx += f.xyz[3 * r]; cy += f.xyz[3 * r + 1]; cz += f.xyz[3 * r + 2] }
            cx /= n; cy /= n; cz /= n
            var serial = 0
            for (r in 0 until n) {
                serial++
                line("ATOM  %5d  CA  %3s %1s%4d    %8.3f%8.3f%8.3f%6.2f%6.2f          %2s", serial % 100000, eng.residueName3(r), chainId(r), resSeq(r) % 10000,
                    f.xyz[3 * r] - cx, f.xyz[3 * r + 1] - cy, f.xyz[3 * r + 2] - cz, 1.0, 0.0, "C")
                if (r == n - 1 || eng.chainOf[r + 1] != eng.chainOf[r]) {
                    serial++
                    line("TER   %5d      %3s %1s%4d", serial % 100000, eng.residueName3(r), chainId(r), resSeq(r) % 10000)
                }
            }
            if (multi) line("ENDMDL")
        }
        // Bonds along each chain, so viewers draw the Cα trace (they are 3.8 Å, longer than normal bonds)
        if (n < 100000) {
            val serialOf = IntArray(n)
            var s = 0
            for (r in 0 until n) { s++; serialOf[r] = s; if (r == n - 1 || eng.chainOf[r + 1] != eng.chainOf[r]) s++ }
            for (r in 0 until n - 1) if (eng.chainOf[r + 1] == eng.chainOf[r]) line("CONECT%5d%5d", serialOf[r], serialOf[r + 1])
        }
        line("END")
        return sb.toString()
    }
}
