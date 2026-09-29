package com.hydrophobiccollapse

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sqrt

// Residue-level protein physics: one bead per amino acid (its Cα atom).
// Units: Å, kcal/mol, K. A line-for-line port of the engine in index.html.

private const val KB = 0.0019872      // kcal/(mol·K)
private const val COULOMB = 332.06    // kcal·Å/(mol·e²)
private const val EPS_WATER = 80.0
private const val DEG = PI / 180

private const val DT = 0.015
private const val GAMMA = 0.5
private const val K_BOND = 60.0
private const val R_BOND = 3.8
private const val K_ANGLE_WALL = 30.0
private const val THETA_MIN = 82 * DEG
private const val THETA_MAX = 150 * DEG
private const val A_HELIX_ANGLE = 1.2
private const val THETA_HELIX = 91 * DEG
private const val W_THETA_H = 10 * DEG
private const val A_STRAND_ANGLE = 0.7
private const val THETA_STRAND = 120 * DEG
private const val W_THETA_S = 12 * DEG
private const val D_HELIX = 2.0
private const val PHI_HELIX = 50 * DEG
private const val W_PHI_H = 22 * DEG
private const val D_STRAND = 0.9
private const val PHI_STRAND = -170 * DEG
private const val W_PHI_S = 28 * DEG
private const val H_BOND = 1.8
private const val R_HB = 6.2
private const val W_HB = 0.6
private const val EPS_MIN = 0.10
private const val EPS_SCALE = 1.35
private const val LJ_CUT = 1.8
private const val SIG_I3 = 4.2
private const val K_SS = 20.0
private const val R_SS = 5.5
private const val SS_REACT = 6.5
private const val SS_MIN_SEP = 6
private const val FMAX = 200.0
private const val MC_EVERY = 25

// radius (Å), Kyte–Doolittle hydropathy, Chou–Fasman helix and strand propensity
private val AA: Map<Char, DoubleArray> = mapOf(
    'A' to doubleArrayOf(2.5, 1.8, 1.42, 0.83), 'R' to doubleArrayOf(3.2, -4.5, 0.98, 0.93),
    'N' to doubleArrayOf(2.7, -3.5, 0.67, 0.89), 'D' to doubleArrayOf(2.7, -3.5, 1.01, 0.54),
    'C' to doubleArrayOf(2.6, 2.5, 0.70, 1.19), 'Q' to doubleArrayOf(2.9, -3.5, 1.11, 1.10),
    'E' to doubleArrayOf(2.9, -3.5, 1.51, 0.37), 'G' to doubleArrayOf(2.2, -0.4, 0.57, 0.75),
    'H' to doubleArrayOf(3.0, -3.2, 1.00, 0.87), 'I' to doubleArrayOf(3.0, 4.5, 1.08, 1.60),
    'L' to doubleArrayOf(3.0, 3.8, 1.21, 1.30), 'K' to doubleArrayOf(3.1, -3.9, 1.16, 0.74),
    'M' to doubleArrayOf(3.0, 1.9, 1.45, 1.05), 'F' to doubleArrayOf(3.2, 2.8, 1.13, 1.38),
    'P' to doubleArrayOf(2.7, -1.6, 0.57, 0.55), 'S' to doubleArrayOf(2.5, -0.8, 0.77, 0.75),
    'T' to doubleArrayOf(2.7, -0.7, 0.83, 1.19), 'W' to doubleArrayOf(3.5, -0.9, 1.08, 1.37),
    'Y' to doubleArrayOf(3.3, -1.3, 0.69, 1.47), 'V' to doubleArrayOf(2.8, 4.2, 1.06, 1.70),
)
private val THREE = mapOf(
    'A' to "Ala", 'R' to "Arg", 'N' to "Asn", 'D' to "Asp", 'C' to "Cys", 'Q' to "Gln", 'E' to "Glu",
    'G' to "Gly", 'H' to "His", 'I' to "Ile", 'L' to "Leu", 'K' to "Lys", 'M' to "Met", 'F' to "Phe",
    'P' to "Pro", 'S' to "Ser", 'T' to "Thr", 'W' to "Trp", 'Y' to "Tyr", 'V' to "Val",
)
// Model-compound pKa values. Acids go 0 → −1 on deprotonation, bases go +1 → 0.
private class SiteType(val pKa: Double, val acid: Boolean)
private val SITE_TYPES = mapOf(
    'D' to SiteType(3.9, true), 'E' to SiteType(4.2, true), 'C' to SiteType(8.3, true),
    'Y' to SiteType(10.1, true), 'H' to SiteType(6.0, false), 'K' to SiteType(10.5, false),
    'R' to SiteType(12.5, false),
)

private fun hp01(h: Double) = (h + 4.5) / 9
private fun clamp(v: Double, a: Double, b: Double) = if (v < a) a else if (v > b) b else v
private fun propWeight(p: Double) = clamp(0.3 + (p - 1) * 2, 0.05, 1.3)
private fun wrap(a0: Double): Double {
    var a = a0
    while (a > PI) a -= 2 * PI
    while (a < -PI) a += 2 * PI
    return a
}

class Site(val res: Int, val pKa: Double, val acid: Boolean, var prot: Boolean, var active: Boolean, val isCys: Boolean)
class ChemEvent(val t: Double, val text: String)
class Flash(val res: Int, val t: Double, val kind: Int) {
    companion object { const val LOST = 0; const val GAINED = 1; const val SS = 2 }
}

class ProteinEngine {
    var temperature = 300.0
    var pH = 7.0
    var saltMM = 150.0
    var redox = 0.4

    var n = 0; private set
    var seq = ""; private set
    var native: List<IntArray> = emptyList(); private set
    var x = DoubleArray(0); private set
    var y = DoubleArray(0); private set
    var z = DoubleArray(0); private set
    var charge = DoubleArray(0); private set
    var partner = IntArray(0); private set
    var ss = IntArray(0); private set            // 0 coil, 1 helix, 2 strand
    var contact = BooleanArray(0); private set

    private var vx = DoubleArray(0); private var vy = DoubleArray(0); private var vz = DoubleArray(0)
    private var fx = DoubleArray(0); private var fy = DoubleArray(0); private var fz = DoubleArray(0)
    private var rad = DoubleArray(0)
    private var sig = DoubleArray(0); private var eps = DoubleArray(0)
    private var wAngH = DoubleArray(0); private var wAngS = DoubleArray(0)
    private var wDihH = DoubleArray(0); private var wDihS = DoubleArray(0); private var wHB = DoubleArray(0)
    private val sites = ArrayList<Site>()
    private var cysSite = arrayOfNulls<Site>(0)
    private var cys = IntArray(0)
    private var charged = IntArray(0); private var nCharged = 0
    private var rBox = 40.0
    private var stepCount = 0L

    val events = ArrayDeque<ChemEvent>()
    val flashes = ArrayList<Flash>()
    var time = 0.0; private set
    var grab = -1
    val target = DoubleArray(3)

    // Observables, refreshed by measure()
    var energy = 0.0; private set
    var total = 0.0; private set
    var rg = 0.0; private set
    val center = DoubleArray(3)
    var helix = 0.0; private set
    var strand = 0.0; private set
    var netCharge = 0; private set
    var contacts = 0; private set
    val bridges = ArrayList<IntArray>()
    val disulfides = ArrayList<IntArray>()     // [i, j, native ? 1 : 0]

    // xorshift64* random numbers: fast and allocation-free
    private var seed = System.nanoTime() or 1L
    private fun rand(): Double {
        seed = seed xor (seed ushr 12); seed = seed xor (seed shl 25); seed = seed xor (seed ushr 27)
        return ((seed * 2685821657736338717L) ushr 11).toDouble() / (1L shl 53).toDouble()
    }
    private fun gauss(): Double {
        var u = rand()
        while (u == 0.0) u = rand()
        return sqrt(-2 * ln(u)) * cos(2 * PI * rand())
    }

    fun radius(i: Int) = rad[i]
    fun debye() = 3.04 / sqrt(maxOf(saltMM, 1.0) / 1000)

    fun load(protein: Protein) {
        seq = protein.seq; n = seq.length; native = protein.native
        val n = n
        x = DoubleArray(n); y = DoubleArray(n); z = DoubleArray(n)
        vx = DoubleArray(n); vy = DoubleArray(n); vz = DoubleArray(n)
        fx = DoubleArray(n); fy = DoubleArray(n); fz = DoubleArray(n)
        rad = DoubleArray(n) { AA.getValue(seq[it])[0] }
        charge = DoubleArray(n)
        partner = IntArray(n) { -1 }
        ss = IntArray(n)
        contact = BooleanArray(n * n)
        sig = DoubleArray(n * n); eps = DoubleArray(n * n)
        for (i in 0 until n) for (j in 0 until n) {
            sig[i * n + j] = 0.9 * (rad[i] + rad[j])
            eps[i * n + j] = EPS_MIN + EPS_SCALE * hp01(AA.getValue(seq[i])[1]) * hp01(AA.getValue(seq[j])[1])
        }
        fun avg(i: Int, len: Int, k: Int): Double { var s = 0.0; for (m in 0 until len) s += AA.getValue(seq[i + m])[k]; return s / len }
        wAngH = DoubleArray(n); wAngS = DoubleArray(n); wDihH = DoubleArray(n); wDihS = DoubleArray(n); wHB = DoubleArray(n)
        for (i in 0 until n - 2) { wAngH[i] = propWeight(avg(i, 3, 2)); wAngS[i] = propWeight(avg(i, 3, 3)) }
        for (i in 0 until n - 3) { wDihH[i] = propWeight(avg(i, 4, 2)); wDihS[i] = propWeight(avg(i, 4, 3)) }
        for (i in 0 until n - 4) wHB[i] = propWeight(avg(i, 5, 2))
        // Proline cannot donate a backbone hydrogen bond and breaks helices
        for (i in 0 until n) if (seq[i] == 'P') {
            for (k in maxOf(0, i - 4)..i) wHB[k] *= 0.2
            for (k in maxOf(0, i - 3)..i) wDihH[k] *= 0.4
        }
        // Titratable sites, including the chain termini
        sites.clear()
        cysSite = arrayOfNulls(n)
        sites.add(Site(0, 8.0, false, true, true, false))
        for (i in 0 until n) SITE_TYPES[seq[i]]?.let { t ->
            val s = Site(i, t.pKa, t.acid, true, true, seq[i] == 'C')
            sites.add(s)
            if (s.isCys) cysSite[i] = s
        }
        sites.add(Site(n - 1, 3.1, true, true, true, false))
        for (s in sites) s.prot = rand() < 1 / (1 + 10.0.pow(pH - s.pKa))
        cys = (0 until n).filter { seq[it] == 'C' }.toIntArray()
        charged = IntArray(n)
        rBox = 5 * sqrt(n.toDouble()) + 10
        events.clear(); flashes.clear(); time = 0.0; stepCount = 0
        grab = -1
        unfoldCoords()
        updateCharges()
        measure()
    }

    // Extended random coil with sensible bond angles
    private fun unfoldCoords() {
        var dx = 1.0; var dy = 0.0; var dz = 0.0
        x[0] = 0.0; y[0] = 0.0; z[0] = 0.0
        for (i in 1 until n) {
            var nx: Double; var ny: Double; var nz: Double; var len: Double
            do {
                nx = dx + (rand() - 0.5) * 1.3; ny = dy + (rand() - 0.5) * 1.3; nz = dz + (rand() - 0.5) * 1.3
                len = sqrt(nx * nx + ny * ny + nz * nz)
            } while (len < 0.3)
            dx = nx / len; dy = ny / len; dz = nz / len
            x[i] = x[i - 1] + R_BOND * dx; y[i] = y[i - 1] + R_BOND * dy; z[i] = z[i - 1] + R_BOND * dz
        }
        var cx = 0.0; var cy = 0.0; var cz = 0.0
        for (i in 0 until n) { cx += x[i]; cy += y[i]; cz += z[i] }
        cx /= n; cy /= n; cz /= n
        for (i in 0 until n) { x[i] -= cx; y[i] -= cy; z[i] -= cz }
        vx.fill(0.0); vy.fill(0.0); vz.fill(0.0)
        partner.fill(-1)
        for (s in sites) s.active = true
    }

    private fun siteCharge(s: Site): Double =
        if (!s.active) 0.0 else if (s.acid) (if (s.prot) 0.0 else -1.0) else (if (s.prot) 1.0 else 0.0)

    private fun updateCharges() {
        charge.fill(0.0)
        for (s in sites) charge[s.res] += siteCharge(s)
        nCharged = 0
        for (i in 0 until n) if (charge[i] != 0.0) charged[nCharged++] = i
    }

    // ---- Forces. With measure = true also returns the potential energy. ----
    fun forces(measure: Boolean): Double {
        val n = n; val x = x; val y = y; val z = z
        fx.fill(0.0); fy.fill(0.0); fz.fill(0.0)
        var eBond = 0.0; var eLocal = 0.0; var eContact = 0.0; var eElec = 0.0
        if (measure) contact.fill(false)

        // Bonds
        for (i in 0 until n - 1) {
            val dx = x[i + 1] - x[i]; val dy = y[i + 1] - y[i]; val dz = z[i + 1] - z[i]
            val r = sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(1e-6)
            var f = K_BOND * (r - R_BOND) / r
            if (abs(f * r) > FMAX) f = sign(f) * FMAX / r
            fx[i] += f * dx; fy[i] += f * dy; fz[i] += f * dz
            fx[i + 1] -= f * dx; fy[i + 1] -= f * dy; fz[i + 1] -= f * dz
            if (measure) eBond += 0.5 * K_BOND * (r - R_BOND) * (r - R_BOND)
        }

        // Virtual bond angles: walls plus helix (91°) and strand (120°) wells
        for (i in 0 until n - 2) {
            val j = i + 1; val k = i + 2
            val ax = x[i] - x[j]; val ay = y[i] - y[j]; val az = z[i] - z[j]
            val bx = x[k] - x[j]; val by = y[k] - y[j]; val bz = z[k] - z[j]
            val la = sqrt(ax * ax + ay * ay + az * az).coerceAtLeast(1e-6)
            val lb = sqrt(bx * bx + by * by + bz * bz).coerceAtLeast(1e-6)
            val c = clamp((ax * bx + ay * by + az * bz) / (la * lb), -0.9999, 0.9999)
            val th = acos(c); val s = sqrt(1 - c * c)
            var u = 0.0; var dU = 0.0
            if (th < THETA_MIN) { u += K_ANGLE_WALL * (THETA_MIN - th) * (THETA_MIN - th); dU += -2 * K_ANGLE_WALL * (THETA_MIN - th) }
            if (th > THETA_MAX) { u += K_ANGLE_WALL * (th - THETA_MAX) * (th - THETA_MAX); dU += 2 * K_ANGLE_WALL * (th - THETA_MAX) }
            val gh = exp(-(th - THETA_HELIX) * (th - THETA_HELIX) / (2 * W_THETA_H * W_THETA_H)) * A_HELIX_ANGLE * wAngH[i]
            val gs = exp(-(th - THETA_STRAND) * (th - THETA_STRAND) / (2 * W_THETA_S * W_THETA_S)) * A_STRAND_ANGLE * wAngS[i]
            u -= gh + gs
            dU += gh * (th - THETA_HELIX) / (W_THETA_H * W_THETA_H) + gs * (th - THETA_STRAND) / (W_THETA_S * W_THETA_S)
            val g = dU / s
            val inv = 1 / (la * lb)
            val fax = g * (bx * inv - c * ax / (la * la)); val fay = g * (by * inv - c * ay / (la * la)); val faz = g * (bz * inv - c * az / (la * la))
            val fkx = g * (ax * inv - c * bx / (lb * lb)); val fky = g * (ay * inv - c * by / (lb * lb)); val fkz = g * (az * inv - c * bz / (lb * lb))
            fx[i] += fax; fy[i] += fay; fz[i] += faz
            fx[k] += fkx; fy[k] += fky; fz[k] += fkz
            fx[j] -= fax + fkx; fy[j] -= fay + fky; fz[j] -= faz + fkz
            if (measure) eLocal += u
        }

        // Virtual dihedrals: right-handed helix (+50°) and extended strand (−170°) wells
        for (i in 0 until n - 3) {
            val i1 = i + 1; val i2 = i + 2; val i3 = i + 3
            val b1x = x[i1] - x[i]; val b1y = y[i1] - y[i]; val b1z = z[i1] - z[i]
            val b2x = x[i2] - x[i1]; val b2y = y[i2] - y[i1]; val b2z = z[i2] - z[i1]
            val b3x = x[i3] - x[i2]; val b3y = y[i3] - y[i2]; val b3z = z[i3] - z[i2]
            val n1x = b1y * b2z - b1z * b2y; val n1y = b1z * b2x - b1x * b2z; val n1z = b1x * b2y - b1y * b2x
            val n2x = b2y * b3z - b2z * b3y; val n2y = b2z * b3x - b2x * b3z; val n2z = b2x * b3y - b2y * b3x
            val n1sq = n1x * n1x + n1y * n1y + n1z * n1z; val n2sq = n2x * n2x + n2y * n2y + n2z * n2z
            if (n1sq < 1e-6 || n2sq < 1e-6) continue
            val lb2 = sqrt(b2x * b2x + b2y * b2y + b2z * b2z)
            val phi = atan2(lb2 * (b1x * n2x + b1y * n2y + b1z * n2z), n1x * n2x + n1y * n2y + n1z * n2z)
            val dh = wrap(phi - PHI_HELIX); val ds = wrap(phi - PHI_STRAND)
            val gh = D_HELIX * wDihH[i] * exp(-(dh * dh) / (2 * W_PHI_H * W_PHI_H))
            val gs = D_STRAND * wDihS[i] * exp(-(ds * ds) / (2 * W_PHI_S * W_PHI_S))
            val dU = gh * dh / (W_PHI_H * W_PHI_H) + gs * ds / (W_PHI_S * W_PHI_S)
            // dφ/dr for the four points (Bekker / Blondel–Karplus)
            val a0 = -lb2 / n1sq; val a3 = lb2 / n2sq
            val g0x = a0 * n1x; val g0y = a0 * n1y; val g0z = a0 * n1z
            val g3x = a3 * n2x; val g3y = a3 * n2y; val g3z = a3 * n2z
            val p = (b1x * b2x + b1y * b2y + b1z * b2z) / (lb2 * lb2)
            val q = (b3x * b2x + b3y * b2y + b3z * b2z) / (lb2 * lb2)
            val g1x = (-p - 1) * g0x + q * g3x; val g1y = (-p - 1) * g0y + q * g3y; val g1z = (-p - 1) * g0z + q * g3z
            val g2x = (-q - 1) * g3x + p * g0x; val g2y = (-q - 1) * g3y + p * g0y; val g2z = (-q - 1) * g3z + p * g0z
            fx[i] -= dU * g0x; fy[i] -= dU * g0y; fz[i] -= dU * g0z
            fx[i1] -= dU * g1x; fy[i1] -= dU * g1y; fz[i1] -= dU * g1z
            fx[i2] -= dU * g2x; fy[i2] -= dU * g2y; fz[i2] -= dU * g2z
            fx[i3] -= dU * g3x; fy[i3] -= dU * g3y; fz[i3] -= dU * g3z
            if (measure) eLocal -= gh + gs
        }

        // Helical i→i+4 hydrogen bond
        for (i in 0 until n - 4) {
            val j = i + 4
            val dx = x[i] - x[j]; val dy = y[i] - y[j]; val dz = z[i] - z[j]
            val r = sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(1e-6)
            val d = r - R_HB
            if (abs(d) > 4 * W_HB) continue
            val g = H_BOND * wHB[i] * exp(-(d * d) / (2 * W_HB * W_HB))
            val f = -(g * d / (W_HB * W_HB)) / r
            fx[i] += f * dx; fy[i] += f * dy; fz[i] += f * dz
            fx[j] -= f * dx; fy[j] -= f * dy; fz[j] -= f * dz
            if (measure) eLocal -= g
        }

        // Contacts: residue-specific Lennard-Jones (minimum at σ, depth ε)
        val sc = 1 / LJ_CUT.pow(6)
        for (i in 0 until n - 3) {
            val xi = x[i]; val yi = y[i]; val zi = z[i]
            for (j in i + 3 until n) {
                val dx = xi - x[j]; val dy = yi - y[j]; val dz = zi - z[j]
                var r2 = dx * dx + dy * dy + dz * dz
                val s: Double; val ep: Double; val cut2: Double; val attractive: Boolean
                if (j == i + 3) { s = SIG_I3; ep = 0.5; attractive = false; cut2 = s * s }
                else { s = sig[i * n + j]; ep = eps[i * n + j]; attractive = true; cut2 = (LJ_CUT * s) * (LJ_CUT * s) }
                if (r2 >= cut2) continue
                if (r2 < 1) r2 = 1.0
                val s2 = s * s / r2; val s6 = s2 * s2 * s2
                var f = 12 * ep * (s6 * s6 - s6) / r2
                val r = sqrt(r2)
                if (abs(f * r) > FMAX) f = sign(f) * FMAX / r
                fx[i] += f * dx; fy[i] += f * dy; fz[i] += f * dz
                fx[j] -= f * dx; fy[j] -= f * dy; fz[j] -= f * dz
                if (measure) {
                    if (attractive) {
                        eContact += ep * (s6 * s6 - 2 * s6) - ep * (sc * sc - 2 * sc)
                        if (r2 < (1.25 * s) * (1.25 * s)) contact[i * n + j] = true
                    } else {
                        eContact += ep * (s6 * s6 - 2 * s6 + 1)
                    }
                }
            }
        }

        // Screened electrostatics (Debye–Hückel) between charged residues
        val lam = debye(); val ecut = min(3 * lam, 30.0); val ecut2 = ecut * ecut
        for (a in 0 until nCharged) {
            val i = charged[a]; val qi = charge[i]
            for (b in a + 1 until nCharged) {
                val j = charged[b]
                if (j - i < 2) continue
                val dx = x[i] - x[j]; val dy = y[i] - y[j]; val dz = z[i] - z[j]
                var r2 = dx * dx + dy * dy + dz * dz
                if (r2 > ecut2) continue
                if (r2 < 9) r2 = 9.0
                val r = sqrt(r2)
                val u = COULOMB * qi * charge[j] * exp(-r / lam) / (EPS_WATER * r)
                val f = u * (1 / r + 1 / lam) / r
                fx[i] += f * dx; fy[i] += f * dy; fz[i] += f * dz
                fx[j] -= f * dx; fy[j] -= f * dy; fz[j] -= f * dz
                if (measure) eElec += u
            }
        }

        // Disulfide bonds
        var eSS = 0.0
        for (i in 0 until n) {
            val j = partner[i]
            if (j <= i) continue
            val dx = x[j] - x[i]; val dy = y[j] - y[i]; val dz = z[j] - z[i]
            val r = sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(1e-6)
            var f = K_SS * (r - R_SS) / r
            if (abs(f * r) > FMAX) f = sign(f) * FMAX / r
            fx[i] += f * dx; fy[i] += f * dy; fz[i] += f * dz
            fx[j] -= f * dx; fy[j] -= f * dy; fz[j] -= f * dz
            if (measure) eSS += 0.5 * K_SS * (r - R_SS) * (r - R_SS)
        }

        // Confinement sphere and a gentle pull back to the origin
        var cx = 0.0; var cy = 0.0; var cz = 0.0
        for (i in 0 until n) { cx += x[i]; cy += y[i]; cz += z[i] }
        cx /= n; cy /= n; cz /= n
        var eBox = 0.0
        for (i in 0 until n) {
            fx[i] -= 0.01 * cx; fy[i] -= 0.01 * cy; fz[i] -= 0.01 * cz
            val r = sqrt(x[i] * x[i] + y[i] * y[i] + z[i] * z[i])
            if (r > rBox) {
                val f = -5 * (r - rBox) / r
                fx[i] += f * x[i]; fy[i] += f * y[i]; fz[i] += f * z[i]
                if (measure) eBox += 2.5 * (r - rBox) * (r - rBox)
            }
        }
        if (measure) eBox += 0.005 * n * (cx * cx + cy * cy + cz * cz)

        // A finger pulling one residue
        val g = grab
        if (g in 0 until n) {
            var px = 4 * (target[0] - x[g]); var py = 4 * (target[1] - y[g]); var pz = 4 * (target[2] - z[g])
            val m = sqrt(px * px + py * py + pz * pz)
            if (m > FMAX) { px *= FMAX / m; py *= FMAX / m; pz *= FMAX / m }
            fx[g] += px; fy[g] += py; fz[g] += pz
        }

        if (measure) {
            total = eBond + eLocal + eContact + eElec + eSS + eBox
            energy = eLocal + eContact + eElec
        }
        return total
    }

    /** For tests: the forces from the last forces() call. */
    fun forceArrays() = arrayOf(fx, fy, fz)
    /** For tests: form a disulfide directly. */
    fun forceDisulfide(i: Int, j: Int) { bond(i, j) }

    // ---- Chemistry: constant-pH titration and thiol–disulfide reactions (Monte Carlo) ----
    private fun elecAt(i: Int, dq: Double): Double {
        val lam = debye()
        var s = 0.0
        for (a in 0 until nCharged) {
            val j = charged[a]
            if (j == i || abs(j - i) < 2) continue
            val r = maxOf(3.0, dist(i, j))
            s += charge[j] * exp(-r / lam) / r
        }
        return COULOMB * dq * s / EPS_WATER
    }
    private fun dist(i: Int, j: Int): Double {
        val dx = x[i] - x[j]; val dy = y[i] - y[j]; val dz = z[i] - z[j]
        return sqrt(dx * dx + dy * dy + dz * dz)
    }
    private fun thiolate(i: Int): Boolean { val s = cysSite[i]; return s != null && s.active && !s.prot }
    private fun isNative(i: Int, j: Int) = native.any { (it[0] - 1 == i && it[1] - 1 == j) || (it[0] - 1 == j && it[1] - 1 == i) }
    private fun log(text: String) {
        events.addFirst(ChemEvent(time, text))
        while (events.size > 20) events.removeLast()
    }
    private fun bond(i: Int, j: Int) {
        partner[i] = j; partner[j] = i
        cysSite[i]?.active = false; cysSite[j]?.active = false
    }
    private fun unbond(i: Int, j: Int, jThiolate: Boolean) {
        partner[i] = -1; partner[j] = -1
        cysSite[i]?.let { it.active = true; it.prot = true }
        cysSite[j]?.let { it.active = true; it.prot = !jThiolate }
    }

    private fun chemistry() {
        val kT = KB * temperature; val ln10 = ln(10.0)
        // Titration: Metropolis on ΔG = ±kT·ln10·(pKa − pH) + ΔE_elec
        for (s in sites) {
            if (!s.active) continue
            val toDeprot = s.prot
            val dq = if (toDeprot) -1.0 else 1.0
            val intrinsic = kT * ln10 * (s.pKa - pH)
            val dG = (if (toDeprot) intrinsic else -intrinsic) + elecAt(s.res, dq)
            if (dG <= 0 || rand() < exp(-dG / kT)) {
                s.prot = !s.prot
                charge[s.res] += dq
                flashes.add(Flash(s.res, time, if (toDeprot) Flash.LOST else Flash.GAINED))
            }
        }
        // Disulfides: oxidation needs a thiolate; reduction by the redox buffer
        val ox = (1 + redox) / 2; val red = (1 - redox) / 2
        for (a in cys.indices) {
            val i = cys[a]
            if (partner[i] >= 0) {
                val j = partner[i]
                if (j > i && rand() < 0.0015 * red) {
                    unbond(i, j, false)
                    log("Cys${i + 1}–Cys${j + 1} disulfide reduced")
                    flashes.add(Flash(i, time, Flash.SS)); flashes.add(Flash(j, time, Flash.SS))
                }
                continue
            }
            for (b in a + 1 until cys.size) {
                val j = cys[b]
                if (partner[j] >= 0 || j - i < SS_MIN_SEP) continue
                if (!(thiolate(i) || thiolate(j))) continue
                if (dist(i, j) < SS_REACT && rand() < 0.05 * ox) {
                    bond(i, j)
                    log("Cys${i + 1}–Cys${j + 1} disulfide formed${if (isNative(i, j)) " · native" else ""}")
                    flashes.add(Flash(i, time, Flash.SS)); flashes.add(Flash(j, time, Flash.SS))
                    break
                }
            }
        }
        // Shuffling: free thiolate k attacks disulfide i–j, forming k–i and releasing j as a thiolate
        for (k in cys) {
            if (partner[k] >= 0 || !thiolate(k)) continue
            for (i in cys) {
                val j = partner[i]
                if (j < 0 || i == k || j == k || abs(k - i) < SS_MIN_SEP) continue
                if (dist(k, i) < SS_REACT && rand() < 0.008) {
                    unbond(i, j, true)
                    bond(k, i)
                    val lo = min(k, i) + 1; val hi = maxOf(k, i) + 1
                    log("Cys${k + 1} attacked Cys${i + 1}–Cys${j + 1} → Cys$lo–Cys$hi${if (isNative(k, i)) " · native" else ""}")
                    flashes.add(Flash(k, time, Flash.SS)); flashes.add(Flash(j, time, Flash.SS))
                    break
                }
            }
        }
        updateCharges()
        val cutoff = time - 1.2
        flashes.removeAll { it.t <= cutoff }
    }

    // ---- Integration (Langevin dynamics) ----
    fun step(count: Int) {
        val c1 = exp(-GAMMA * DT); val c2 = sqrt((1 - c1 * c1) * KB * temperature)
        repeat(count) {
            forces(false)
            for (i in 0 until n) {
                vx[i] = (vx[i] + fx[i] * DT) * c1 + c2 * gauss()
                vy[i] = (vy[i] + fy[i] * DT) * c1 + c2 * gauss()
                vz[i] = (vz[i] + fz[i] * DT) * c1 + c2 * gauss()
                x[i] += vx[i] * DT; y[i] += vy[i] * DT; z[i] += vz[i] * DT
            }
            stepCount++
            if (stepCount % MC_EVERY == 0L) chemistry()
        }
    }
    fun advanceClock(dt: Double) { time += dt }

    // ---- Observables ----
    fun measure() {
        forces(true)
        var cx = 0.0; var cy = 0.0; var cz = 0.0
        for (i in 0 until n) { cx += x[i]; cy += y[i]; cz += z[i] }
        cx /= n; cy /= n; cz /= n
        var s2 = 0.0
        for (i in 0 until n) { val dx = x[i] - cx; val dy = y[i] - cy; val dz = z[i] - cz; s2 += dx * dx + dy * dy + dz * dz }
        rg = sqrt(s2 / n)
        center[0] = cx; center[1] = cy; center[2] = cz
        // Secondary structure from Cα geometry: two consecutive helical (or extended) dihedrals
        val angs = DoubleArray(n); val dihs = DoubleArray(n)
        for (i in 0 until n - 2) angs[i] = angleAt(i)
        for (i in 0 until n - 3) dihs[i] = dihedral(i)
        ss.fill(0)
        fun helical(i: Int) = abs(wrap(dihs[i] - PHI_HELIX)) < 35 * DEG && angs[i] < 105 * DEG && angs[i + 1] < 105 * DEG
        fun extended(i: Int) = abs(wrap(dihs[i] - PHI_STRAND)) < 45 * DEG && angs[i] > 105 * DEG && angs[i + 1] > 105 * DEG
        for (i in 0 until n - 4) if (helical(i) && helical(i + 1)) for (k in i..i + 4) ss[k] = 1
        for (i in 0 until n - 4) if (extended(i) && extended(i + 1)) for (k in i..i + 4) if (ss[k] == 0) ss[k] = 2
        var h = 0; var st = 0
        for (i in 0 until n) { if (ss[i] == 1) h++ else if (ss[i] == 2) st++ }
        helix = h.toDouble() / n; strand = st.toDouble() / n
        bridges.clear()
        for (a in 0 until nCharged) for (b in a + 1 until nCharged) {
            val i = charged[a]; val j = charged[b]
            if (j - i >= 3 && charge[i] * charge[j] < 0 && dist(i, j) < 7.5) bridges.add(intArrayOf(i, j))
        }
        var q = 0.0
        for (i in 0 until n) q += charge[i]
        netCharge = Math.round(q).toInt()
        var nc = 0
        for (c in contact) if (c) nc++
        contacts = nc
        disulfides.clear()
        for (i in 0 until n) if (partner[i] > i) disulfides.add(intArrayOf(i, partner[i], if (isNative(i, partner[i])) 1 else 0))
    }
    private fun angleAt(i: Int): Double {
        val j = i + 1; val k = i + 2
        val ax = x[i] - x[j]; val ay = y[i] - y[j]; val az = z[i] - z[j]
        val bx = x[k] - x[j]; val by = y[k] - y[j]; val bz = z[k] - z[j]
        val la = sqrt(ax * ax + ay * ay + az * az); val lb = sqrt(bx * bx + by * by + bz * bz)
        return acos(clamp((ax * bx + ay * by + az * bz) / (la * lb), -1.0, 1.0))
    }
    private fun dihedral(i: Int): Double {
        val b1x = x[i + 1] - x[i]; val b1y = y[i + 1] - y[i]; val b1z = z[i + 1] - z[i]
        val b2x = x[i + 2] - x[i + 1]; val b2y = y[i + 2] - y[i + 1]; val b2z = z[i + 2] - z[i + 1]
        val b3x = x[i + 3] - x[i + 2]; val b3y = y[i + 3] - y[i + 2]; val b3z = z[i + 3] - z[i + 2]
        val n1x = b1y * b2z - b1z * b2y; val n1y = b1z * b2x - b1x * b2z; val n1z = b1x * b2y - b1y * b2x
        val n2x = b2y * b3z - b2z * b3y; val n2y = b2z * b3x - b2x * b3z; val n2z = b2x * b3y - b2y * b3x
        val lb2 = sqrt(b2x * b2x + b2y * b2y + b2z * b2z)
        return atan2(lb2 * (b1x * n2x + b1y * n2y + b1z * n2z), n1x * n2x + n1y * n2y + n1z * n2z)
    }

    // ---- Interaction helpers ----
    fun kick(px: Double, py: Double, pz: Double, radius: Double, strength: Double) {
        for (i in 0 until n) {
            val dx = x[i] - px; val dy = y[i] - py; val dz = z[i] - pz
            val d = sqrt(dx * dx + dy * dy + dz * dz)
            if (d < radius && d > 0.1) {
                val k = strength * (1 - d / radius) / d
                vx[i] += dx * k; vy[i] += dy * k; vz[i] += dz * k
            }
        }
    }

    companion object {
        fun threeLetter(c: Char) = THREE[c] ?: c.toString()
    }
}
