package com.hydrophobiccollapse

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

// Residue-level protein physics: one bead per amino acid (its Cα atom), any number of chains.
// Units: Å, kcal/mol, K. Non-bonded pairs come from Verlet neighbour lists built on a cell grid,
// so the cost grows roughly linearly with the number of residues.

private const val KB = 0.0019872      // kcal/(mol·K)
private const val COULOMB = 332.06    // kcal·Å/(mol·e²)
private const val EPS_WATER = 80.0
private const val DEG = PI / 180

private const val DT = 0.015
private const val GAMMA = 0.15        // low solvent friction: same equilibrium, faster folding
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
private const val SS_MIN_SEP = 5          // shorter disulfide loops within one chain are too strained
private const val FMAX = 200.0
private const val MC_EVERY = 25
private const val ECUT_MAX = 18.0         // screened electrostatics are negligible beyond this
private const val SKIN = 2.5              // neighbour-list margin, Å

// Structure-based ("Gō") terms, switched on in proportion to nativeBias when a real structure is known
private const val NAT_CONTACT = 7.5       // Cα pairs closer than this in the real structure are native contacts…
private const val NAT_CONTACT_LONG = 10.0 // …or this, for residues far apart in sequence or on different chains:
                                          // packed helices sit 8–11 Å apart at their Cα atoms
private const val LONG_SEP = 8
private const val EPS_NATIVE = 0.6        // native contact well depth at full guidance, kcal/mol
private const val NAT_CUT = 1.8           // native attraction reaches out to this multiple of the native distance
private const val K_ANG_NAT = 30.0        // kcal/(mol·rad²) toward the native bond angle
private const val K1_DIH_NAT = 1.25       // dihedral cosine terms toward the native twist
private const val K3_DIH_NAT = 0.625
// Weak long-range pull between residues that touch across a real interface. It stands in for the diffusion
// and electrostatic steering that bring partners together in reality, which would take far too long to simulate.
private const val EPS_DOCK = 0.2
private const val W_DOCK = 6.0
private const val DISSOCIATE = 1.4       // complexes start with each chain this much further from the centre than in the real structure

const val AMINO_ACIDS = "ARNDCQEGHILKMFPSTWYV"
private const val MAX_SIG = 0.9 * 2 * 3.5

// Per amino acid, in AMINO_ACIDS order: radius (Å), Kyte–Doolittle hydropathy,
// Chou–Fasman helix and strand propensity
private val RAD = doubleArrayOf(2.5, 3.2, 2.7, 2.7, 2.6, 2.9, 2.9, 2.2, 3.0, 3.0, 3.0, 3.1, 3.0, 3.2, 2.7, 2.5, 2.7, 3.5, 3.3, 2.8)
private val HYD = doubleArrayOf(1.8, -4.5, -3.5, -3.5, 2.5, -3.5, -3.5, -0.4, -3.2, 4.5, 3.8, -3.9, 1.9, 2.8, -1.6, -0.8, -0.7, -0.9, -1.3, 4.2)
private val P_HELIX = doubleArrayOf(1.42, 0.98, 0.67, 1.01, 0.70, 1.11, 1.51, 0.57, 1.00, 1.08, 1.21, 1.16, 1.45, 1.13, 0.57, 0.77, 0.83, 1.08, 0.69, 1.06)
private val P_STRAND = doubleArrayOf(0.83, 0.93, 0.89, 0.54, 1.19, 1.10, 0.37, 0.75, 0.87, 1.60, 1.30, 0.74, 1.05, 1.38, 0.55, 0.75, 1.19, 1.37, 1.47, 1.70)
private val THREE = arrayOf("Ala", "Arg", "Asn", "Asp", "Cys", "Gln", "Glu", "Gly", "His", "Ile", "Leu", "Lys", "Met", "Phe", "Pro", "Ser", "Thr", "Trp", "Tyr", "Val")
private val EPS20 = DoubleArray(400).also { t ->
    fun hp01(h: Double) = (h + 4.5) / 9
    for (a in 0 until 20) for (b in 0 until 20) t[a * 20 + b] = EPS_MIN + EPS_SCALE * hp01(HYD[a]) * hp01(HYD[b])
}
// Model-compound pKa values. Acids go 0 → −1 on deprotonation, bases go +1 → 0.
private class SiteType(val pKa: Double, val acid: Boolean)
private val SITE_TYPES = mapOf(
    'D' to SiteType(3.9, true), 'E' to SiteType(4.2, true), 'C' to SiteType(8.3, true),
    'Y' to SiteType(10.1, true), 'H' to SiteType(6.0, false), 'K' to SiteType(10.5, false),
    'R' to SiteType(12.5, false),
)

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

/** A growable list of residue pairs. */
class PairList {
    var a = IntArray(256); private set
    var b = IntArray(256); private set
    var size = 0; private set
    fun clear() { size = 0 }
    fun add(i: Int, j: Int) {
        if (size == a.size) { a = a.copyOf(size * 2); b = b.copyOf(size * 2) }
        a[size] = i; b[size] = j; size++
    }
}

class ProteinEngine {
    var temperature = 300.0
    var pH = 7.0
    var saltMM = 150.0
    var redox = 0.4

    /** Tests switch this off to check the neighbour lists against every pair. */
    var useNeighbourLists = true

    var n = 0; private set
    var seq = ""; private set
    var nChains = 0; private set
    var chainOf = IntArray(0); private set
    var chainStart = IntArray(0); private set
    var native: List<IntArray> = emptyList(); private set
    var x = DoubleArray(0); private set
    var y = DoubleArray(0); private set
    var z = DoubleArray(0); private set
    var charge = DoubleArray(0); private set
    var partner = IntArray(0); private set
    var ss = IntArray(0); private set            // 0 coil, 1 helix, 2 strand
    var type = IntArray(0); private set

    private var vx = DoubleArray(0); private var vy = DoubleArray(0); private var vz = DoubleArray(0)
    private var fx = DoubleArray(0); private var fy = DoubleArray(0); private var fz = DoubleArray(0)
    private var rad = DoubleArray(0)
    private var wAngH = DoubleArray(0); private var wAngS = DoubleArray(0)
    private var wDihH = DoubleArray(0); private var wDihS = DoubleArray(0); private var wHB = DoubleArray(0)
    private val sites = ArrayList<Site>()
    private var cysSite = arrayOfNulls<Site>(0)
    private var cys = IntArray(0)
    private var titratable = IntArray(0)
    private var charged = IntArray(0); private var nCharged = 0
    private var rBox = 40.0

    /**
     * How tightly several chains are packed: 0 dilute, 1 crowded, 2 cell-like (the cytoplasm is 20–40% protein).
     * Sets the confining sphere so the folded protein fills 3%, 12% or 25% of it. Single chains ignore it.
     */
    var crowding = 2
        set(v) { field = v.coerceIn(0, 2); if (n > 0) rBox = boxRadius() }
    private fun boxRadius(): Double {
        val single = 5 * sqrt(n.toDouble()) + 10
        if (nChains <= 1) return single
        val folded = 3.18 * Math.cbrt(n.toDouble())          // radius of the packed protein, ~135 Å³ per residue
        val fraction = doubleArrayOf(0.03, 0.12, 0.25)[crowding]
        return max(folded / Math.cbrt(fraction), folded + 10)
    }
    /** Radius of the sphere the chains are kept in, Å. */
    val boxSize get() = rBox
    private var stepCount = 0L

    // Neighbour lists
    private val shortPairs = PairList()
    private val elecPairs = PairList()
    private var x0 = DoubleArray(0); private var y0 = DoubleArray(0); private var z0 = DoubleArray(0)
    private var listsValid = false
    private var cellHead = IntArray(0)
    private var cellNext = IntArray(0)

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
    val contacts = PairList()                  // residue pairs in contact (|i−j| ≥ 3 or different chains)
    var interfaceContacts = 0; private set     // contacts between different chains
    var largestComplex = 1; private set        // chains in the biggest group held together by contacts or disulfides

    // Known structure (PDB or AlphaFold) and how strongly to steer toward it, 0…1
    var nativeBias = 1.0
    var structureSource: String? = null; private set
    val hasStructure get() = nNat > 0
    var q = Double.NaN; private set            // fraction of native contacts formed
    var rmsd = Double.NaN; private set         // Cα RMSD to the known structure after best superposition, Å
    private var hasNat = BooleanArray(0)
    private var natX = DoubleArray(0); private var natY = DoubleArray(0); private var natZ = DoubleArray(0)
    private var theta0 = DoubleArray(0); private var phi0 = DoubleArray(0)
    private var ncI = IntArray(0); private var ncJ = IntArray(0); private var ncR = DoubleArray(0); private var nNat = 0
    private var natGroup = IntArray(0)
    private var nativeKeys = HashSet<Long>()
    private var shortNat = BooleanArray(0)
    private var rmsdMeaningful = false
    val bridges = ArrayList<IntArray>()
    val disulfides = ArrayList<IntArray>()     // [i, j, native ? 1 : 0]

    // xorshift64* random numbers: fast and allocation-free
    private var seed = System.nanoTime() or 1L
    /** Tests fix the random sequence. */
    fun seed(value: Long) { seed = value or 1L }
    private fun rand(): Double {
        seed = seed xor (seed ushr 12); seed = seed xor (seed shl 25); seed = seed xor (seed ushr 27)
        return ((seed * 2685821657736338717L) ushr 11).toDouble() / (1L shl 53).toDouble()
    }

    fun radius(i: Int) = rad[i]
    fun debye() = 3.04 / sqrt(maxOf(saltMM, 1.0) / 1000)
    fun sameChain(i: Int, j: Int) = chainOf[i] == chainOf[j]
    fun chainLetter(c: Int): Char = if (c < 26) 'A' + c else 'a' + (c - 26) % 26
    /** "Cys14", or "Cys B7" when there are several chains. */
    fun residueLabel(i: Int): String {
        val three = THREE[type[i]]
        return if (nChains <= 1) "$three${i + 1}" else "$three ${chainLetter(chainOf[i])}${i - chainStart[chainOf[i]] + 1}"
    }

    fun load(protein: Protein) {
        val chains = protein.chains
        seq = chains.joinToString(""); n = seq.length; native = protein.native
        nChains = chains.size
        chainStart = IntArray(nChains + 1)
        for (c in chains.indices) chainStart[c + 1] = chainStart[c] + chains[c].length
        chainOf = IntArray(n)
        for (c in chains.indices) for (i in chainStart[c] until chainStart[c + 1]) chainOf[i] = c
        val n = n
        type = IntArray(n) { AMINO_ACIDS.indexOf(seq[it]).coerceAtLeast(0) }
        x = DoubleArray(n); y = DoubleArray(n); z = DoubleArray(n)
        vx = DoubleArray(n); vy = DoubleArray(n); vz = DoubleArray(n)
        fx = DoubleArray(n); fy = DoubleArray(n); fz = DoubleArray(n)
        x0 = DoubleArray(n); y0 = DoubleArray(n); z0 = DoubleArray(n)
        cellNext = IntArray(n)
        rad = DoubleArray(n) { RAD[type[it]] }
        charge = DoubleArray(n)
        partner = IntArray(n) { -1 }
        ss = IntArray(n)
        // Local-structure weights; windows never span two chains
        fun avg(i: Int, len: Int, table: DoubleArray): Double {
            var s = 0.0; for (m in 0 until len) s += table[type[i + m]]; return s / len
        }
        fun window(i: Int, len: Int) = i + len - 1 < n && chainOf[i] == chainOf[i + len - 1]
        wAngH = DoubleArray(n); wAngS = DoubleArray(n); wDihH = DoubleArray(n); wDihS = DoubleArray(n); wHB = DoubleArray(n)
        for (i in 0 until n) {
            if (window(i, 3)) { wAngH[i] = propWeight(avg(i, 3, P_HELIX)); wAngS[i] = propWeight(avg(i, 3, P_STRAND)) }
            if (window(i, 4)) { wDihH[i] = propWeight(avg(i, 4, P_HELIX)); wDihS[i] = propWeight(avg(i, 4, P_STRAND)) }
            if (window(i, 5)) wHB[i] = propWeight(avg(i, 5, P_HELIX))
        }
        // Proline cannot donate a backbone hydrogen bond and breaks helices
        for (i in 0 until n) if (seq[i] == 'P') {
            for (k in maxOf(0, i - 4)..i) wHB[k] *= 0.2
            for (k in maxOf(0, i - 3)..i) wDihH[k] *= 0.4
        }
        // Titratable sites, including every chain's termini
        sites.clear()
        cysSite = arrayOfNulls(n)
        for (c in 0 until nChains) {
            val s0 = chainStart[c]; val s1 = chainStart[c + 1] - 1
            sites.add(Site(s0, 8.0, false, true, true, false))
            for (i in s0..s1) SITE_TYPES[seq[i]]?.let { t ->
                val s = Site(i, t.pKa, t.acid, true, true, seq[i] == 'C')
                sites.add(s)
                if (s.isCys) cysSite[i] = s
            }
            sites.add(Site(s1, 3.1, true, true, true, false))
        }
        for (s in sites) s.prot = rand() < 1 / (1 + 10.0.pow(pH - s.pKa))
        cys = (0 until n).filter { seq[it] == 'C' }.toIntArray()
        titratable = sites.map { it.res }.distinct().sorted().toIntArray()
        charged = IntArray(n)
        rBox = boxRadius()
        loadStructure(protein)
        events.clear(); flashes.clear(); time = 0.0; stepCount = 0
        grab = -1
        unfoldCoords()
        updateCharges()
        measure()
    }

    private fun loadStructure(protein: Protein) {
        val ca = protein.ca
        hasNat = BooleanArray(n); natX = DoubleArray(n); natY = DoubleArray(n); natZ = DoubleArray(n)
        theta0 = DoubleArray(n) { Double.NaN }; phi0 = DoubleArray(n) { Double.NaN }
        natGroup = IntArray(n) { protein.copyOf?.getOrNull(chainOf[it]) ?: 0 }
        nativeKeys = HashSet(); nNat = 0; q = Double.NaN; rmsd = Double.NaN
        structureSource = null
        if (ca == null || ca.size != 3 * n) return
        for (i in 0 until n) if (!ca[3 * i].isNaN()) {
            hasNat[i] = true; natX[i] = ca[3 * i].toDouble(); natY[i] = ca[3 * i + 1].toDouble(); natZ[i] = ca[3 * i + 2].toDouble()
        }
        fun ok(i: Int, len: Int) = i + len - 1 < n && chainOf[i] == chainOf[i + len - 1] && (0 until len).all { hasNat[i + it] }
        for (i in 0 until n) {
            if (ok(i, 3)) theta0[i] = angleOf(natX, natY, natZ, i)
            if (ok(i, 4)) phi0[i] = dihedralOf(natX, natY, natZ, i)
        }
        val ci = ArrayList<Int>(); val cj = ArrayList<Int>(); val cr = ArrayList<Double>()
        for (i in 0 until n) {
            if (!hasNat[i]) continue
            for (j in i + 1 until n) {
                if (!hasNat[j] || natGroup[i] != natGroup[j]) continue
                if (chainOf[i] == chainOf[j] && j - i < 4) continue
                val dx = natX[i] - natX[j]; val dy = natY[i] - natY[j]; val dz = natZ[i] - natZ[j]
                val d2 = dx * dx + dy * dy + dz * dz
                val cut = if (chainOf[i] != chainOf[j] || j - i >= LONG_SEP) NAT_CONTACT_LONG else NAT_CONTACT
                if (d2 < cut * cut) { ci.add(i); cj.add(j); cr.add(sqrt(d2)); nativeKeys.add(pairKey(i, j)) }
            }
        }
        ncI = ci.toIntArray(); ncJ = cj.toIntArray(); ncR = cr.toDoubleArray(); nNat = ncI.size
        if (nNat > 0) structureSource = protein.structureSource
        rmsdMeaningful = nNat > 0 && (protein.copyOf?.all { it == 0 } ?: true)
    }
    private fun pairKey(i: Int, j: Int) = (i.toLong() shl 32) or j.toLong()

    // Each chain an extended random coil, chains spread apart on a grid
    private fun unfoldCoords() {
        val side = Math.ceil(Math.cbrt(nChains.toDouble())).toInt()
        // Chains start about two coil-widths apart: separate, but close enough to find each other
        var longest = 1
        for (c in 0 until nChains) longest = maxOf(longest, chainStart[c + 1] - chainStart[c])
        // …and inside the box, so crowding holds from the start
        val spacing = if (nChains > 1) min(3.0 * sqrt(longest.toDouble()) + 12, 1.4 * rBox / max(side - 1, 1)) else 0.0
        val keepAll = 0.75 * rBox
        // With a known complex, each chain starts around its real place in it, pushed further out (DISSOCIATE):
        // the complex begins dissociated, with every subunit facing its real partners
        val fromComplex = rmsdMeaningful && nChains > 1 && (0 until nChains).all { c -> (chainStart[c] until chainStart[c + 1]).any { hasNat[it] } }
        var mx = 0.0; var my = 0.0; var mz = 0.0; var mc = 0
        if (fromComplex) for (i in 0 until n) if (hasNat[i]) { mx += natX[i]; my += natY[i]; mz += natZ[i]; mc++ }
        // Chain start centres, and how far each chain's coil may wander without reaching into a neighbour's space
        val cenX = DoubleArray(nChains); val cenY = DoubleArray(nChains); val cenZ = DoubleArray(nChains)
        for (c in 0 until nChains) {
            val gx = (c % side) - (side - 1) / 2.0
            val gy = ((c / side) % side) - (side - 1) / 2.0
            val gz = (c / (side * side)) - (side - 1) / 2.0
            cenX[c] = gx * spacing; cenY[c] = gy * spacing; cenZ[c] = gz * spacing
            if (fromComplex) {
                var cx = 0.0; var cy = 0.0; var cz = 0.0; var k = 0
                for (i in chainStart[c] until chainStart[c + 1]) if (hasNat[i]) { cx += natX[i]; cy += natY[i]; cz += natZ[i]; k++ }
                cenX[c] = DISSOCIATE * (cx / k - mx / mc); cenY[c] = DISSOCIATE * (cy / k - my / mc); cenZ[c] = DISSOCIATE * (cz / k - mz / mc)
            }
        }
        val wander = DoubleArray(nChains) { c ->
            var nearest = Double.MAX_VALUE
            for (d in 0 until nChains) if (d != c) nearest = min(nearest, sqrt((cenX[c] - cenX[d]).let { it * it } + (cenY[c] - cenY[d]).let { it * it } + (cenZ[c] - cenZ[d]).let { it * it }))
            if (nChains > 1) max(0.45 * nearest, 8.0) else keepAll
        }
        for (c in 0 until nChains) {
            val ox = cenX[c]; val oy = cenY[c]; val oz = cenZ[c]
            val s0 = chainStart[c]; val s1 = chainStart[c + 1]
            var dx = 1.0; var dy = 0.0; var dz = 0.0
            x[s0] = ox; y[s0] = oy; z[s0] = oz
            for (i in s0 + 1 until s1) {
                var nx: Double; var ny: Double; var nz: Double; var len: Double
                do {
                    nx = dx + (rand() - 0.5) * 1.3; ny = dy + (rand() - 0.5) * 1.3; nz = dz + (rand() - 0.5) * 1.3
                    // Turn back toward the chain's own start when wandering too far (long chains)
                    val rx = x[i - 1] - ox; val ry = y[i - 1] - oy; val rz = z[i - 1] - oz
                    val r = sqrt(rx * rx + ry * ry + rz * rz)
                    if (r > wander[c]) { nx -= 0.6 * rx / r; ny -= 0.6 * ry / r; nz -= 0.6 * rz / r }
                    len = sqrt(nx * nx + ny * ny + nz * nz)
                } while (len < 0.3)
                dx = nx / len; dy = ny / len; dz = nz / len
                x[i] = x[i - 1] + R_BOND * dx; y[i] = y[i - 1] + R_BOND * dy; z[i] = z[i - 1] + R_BOND * dz
            }
        }
        var cx = 0.0; var cy = 0.0; var cz = 0.0
        for (i in 0 until n) { cx += x[i]; cy += y[i]; cz += z[i] }
        cx /= n; cy /= n; cz /= n
        for (i in 0 until n) { x[i] -= cx; y[i] -= cy; z[i] -= cz }
        vx.fill(0.0); vy.fill(0.0); vz.fill(0.0)
        partner.fill(-1)
        for (s in sites) s.active = true
        listsValid = false
    }

    private fun siteCharge(s: Site): Double =
        if (!s.active) 0.0 else if (s.acid) (if (s.prot) 0.0 else -1.0) else (if (s.prot) 1.0 else 0.0)

    private fun updateCharges() {
        charge.fill(0.0)
        for (s in sites) charge[s.res] += siteCharge(s)
        nCharged = 0
        for (i in 0 until n) if (charge[i] != 0.0) charged[nCharged++] = i
    }

    // ---------- Neighbour lists ----------
    private fun excludedShort(i: Int, j: Int) = chainOf[i] == chainOf[j] && j - i < 3
    private fun excludedElec(i: Int, j: Int) = chainOf[i] == chainOf[j] && j - i < 2

    /** All pairs (i < j) among [idx] closer than [cutoff], using a cell grid, passed to [accept]. */
    private inline fun gridPairs(idx: IntArray, count: Int, cutoff: Double, accept: (Int, Int, Double) -> Unit) {
        if (count == 0) return
        var mnx = Double.MAX_VALUE; var mny = Double.MAX_VALUE; var mnz = Double.MAX_VALUE
        var mxx = -Double.MAX_VALUE; var mxy = -Double.MAX_VALUE; var mxz = -Double.MAX_VALUE
        for (k in 0 until count) {
            val i = idx[k]
            if (x[i] < mnx) mnx = x[i]; if (x[i] > mxx) mxx = x[i]
            if (y[i] < mny) mny = y[i]; if (y[i] > mxy) mxy = y[i]
            if (z[i] < mnz) mnz = z[i]; if (z[i] > mxz) mxz = z[i]
        }
        var cell = cutoff
        var nx = floor((mxx - mnx) / cell).toInt() + 1
        var ny = floor((mxy - mny) / cell).toInt() + 1
        var nz = floor((mxz - mnz) / cell).toInt() + 1
        while (nx.toLong() * ny * nz > 400_000) { // runaway coordinates: coarser cells, still correct
            cell *= 2
            nx = floor((mxx - mnx) / cell).toInt() + 1; ny = floor((mxy - mny) / cell).toInt() + 1; nz = floor((mxz - mnz) / cell).toInt() + 1
        }
        val reach = Math.ceil(cutoff / cell).toInt()
        val ncell = nx * ny * nz
        if (cellHead.size < ncell) cellHead = IntArray(ncell)
        cellHead.fill(-1, 0, ncell)
        for (k in 0 until count) {
            val i = idx[k]
            val c = (((floor((x[i] - mnx) / cell).toInt()) * ny + floor((y[i] - mny) / cell).toInt()) * nz) + floor((z[i] - mnz) / cell).toInt()
            cellNext[i] = cellHead[c]; cellHead[c] = i
        }
        val cut2 = cutoff * cutoff
        for (k in 0 until count) {
            val i = idx[k]
            val ci = floor((x[i] - mnx) / cell).toInt(); val cj = floor((y[i] - mny) / cell).toInt(); val ck = floor((z[i] - mnz) / cell).toInt()
            for (a in maxOf(0, ci - reach)..minOf(nx - 1, ci + reach))
                for (b in maxOf(0, cj - reach)..minOf(ny - 1, cj + reach))
                    for (cc in maxOf(0, ck - reach)..minOf(nz - 1, ck + reach)) {
                        var j = cellHead[(a * ny + b) * nz + cc]
                        while (j >= 0) {
                            if (j > i) {
                                val dx = x[i] - x[j]; val dy = y[i] - y[j]; val dz = z[i] - z[j]
                                val r2 = dx * dx + dy * dy + dz * dz
                                if (r2 < cut2) accept(i, j, r2)
                            }
                            j = cellNext[j]
                        }
                    }
        }
    }

    private val allIdx get() = IntArray(n) { it }
    private fun rebuildLists() {
        shortPairs.clear(); elecPairs.clear()
        if (!useNeighbourLists) {
            for (i in 0 until n) for (j in i + 1 until n) if (!excludedShort(i, j)) shortPairs.add(i, j)
            for (a in titratable.indices) for (b in a + 1 until titratable.size) {
                val i = titratable[a]; val j = titratable[b]
                if (!excludedElec(i, j)) elecPairs.add(i, j)
            }
        } else {
            val all = allIdx
            gridPairs(all, n, LJ_CUT * MAX_SIG + SKIN) { i, j, _ -> if (!excludedShort(i, j)) shortPairs.add(i, j) }
            gridPairs(titratable, titratable.size, ECUT_MAX + SKIN) { i, j, _ -> if (!excludedElec(i, j)) elecPairs.add(i, j) }
        }
        // Mark which short-range pairs are native contacts; their generic attraction fades as guidance rises
        if (shortNat.size < shortPairs.size) shortNat = BooleanArray(shortPairs.a.size)
        if (nNat > 0) for (p in 0 until shortPairs.size) shortNat[p] = nativeKeys.contains(pairKey(shortPairs.a[p], shortPairs.b[p]))
        else shortNat.fill(false, 0, shortPairs.size)
        System.arraycopy(x, 0, x0, 0, n); System.arraycopy(y, 0, y0, 0, n); System.arraycopy(z, 0, z0, 0, n)
        listsValid = true
    }
    private fun checkLists() {
        if (!listsValid || !useNeighbourLists) { rebuildLists(); return }
        val lim = (SKIN / 2) * (SKIN / 2)
        for (i in 0 until n) {
            val dx = x[i] - x0[i]; val dy = y[i] - y0[i]; val dz = z[i] - z0[i]
            if (dx * dx + dy * dy + dz * dz > lim) { rebuildLists(); return }
        }
    }

    // ---------- Forces. With measure = true also returns the potential energy. ----------
    fun forces(measure: Boolean): Double {
        checkLists()
        val n = n; val x = x; val y = y; val z = z
        fx.fill(0.0); fy.fill(0.0); fz.fill(0.0)
        var eBond = 0.0; var eLocal = 0.0; var eContact = 0.0; var eElec = 0.0
        val lam = if (nNat > 0) nativeBias.coerceIn(0.0, 1.0) else 0.0
        if (measure) contacts.clear()

        // Bonds (within a chain)
        for (i in 0 until n - 1) {
            if (chainOf[i] != chainOf[i + 1]) continue
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
            if (chainOf[i] != chainOf[i + 2]) continue
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
            val t0 = theta0[i]
            val gw = if (t0.isNaN()) 1.0 else 1 - lam   // generic preferences give way to the known structure
            val gh = exp(-(th - THETA_HELIX) * (th - THETA_HELIX) / (2 * W_THETA_H * W_THETA_H)) * A_HELIX_ANGLE * wAngH[i] * gw
            val gs = exp(-(th - THETA_STRAND) * (th - THETA_STRAND) / (2 * W_THETA_S * W_THETA_S)) * A_STRAND_ANGLE * wAngS[i] * gw
            if (!t0.isNaN() && lam > 0) { val d = th - t0; u += lam * K_ANG_NAT * d * d; dU += 2 * lam * K_ANG_NAT * d }
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
            if (chainOf[i] != chainOf[i + 3]) continue
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
            val p0 = phi0[i]
            val gw = if (p0.isNaN()) 1.0 else 1 - lam
            val gh = D_HELIX * wDihH[i] * exp(-(dh * dh) / (2 * W_PHI_H * W_PHI_H)) * gw
            val gs = D_STRAND * wDihS[i] * exp(-(ds * ds) / (2 * W_PHI_S * W_PHI_S)) * gw
            var dU = gh * dh / (W_PHI_H * W_PHI_H) + gs * ds / (W_PHI_S * W_PHI_S)
            var uNat = 0.0
            if (!p0.isNaN() && lam > 0) {
                val d = phi - p0
                uNat = lam * (K1_DIH_NAT * (1 - cos(d)) + K3_DIH_NAT * (1 - cos(3 * d)))
                dU += lam * (K1_DIH_NAT * sin(d) + 3 * K3_DIH_NAT * sin(3 * d))
            }
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
            if (measure) eLocal += uNat - gh - gs
        }

        // Helical i→i+4 hydrogen bond
        for (i in 0 until n - 4) {
            if (wHB[i] == 0.0) continue
            val j = i + 4
            val dx = x[i] - x[j]; val dy = y[i] - y[j]; val dz = z[i] - z[j]
            val r = sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(1e-6)
            val d = r - R_HB
            if (abs(d) > 4 * W_HB) continue
            val g = H_BOND * wHB[i] * exp(-(d * d) / (2 * W_HB * W_HB)) * (if (hasNat[i] && hasNat[j]) 1 - lam else 1.0)
            val f = -(g * d / (W_HB * W_HB)) / r
            fx[i] += f * dx; fy[i] += f * dy; fz[i] += f * dz
            fx[j] -= f * dx; fy[j] -= f * dy; fz[j] -= f * dz
            if (measure) eLocal -= g
        }

        // Contacts: residue-specific Lennard-Jones (minimum at σ, depth ε). Chains interact exactly as residues within one chain do.
        val sc = 1 / LJ_CUT.pow(6)
        val sa = shortPairs.a; val sb = shortPairs.b
        val nat = shortNat
        for (p in 0 until shortPairs.size) {
            val i = sa[p]; val j = sb[p]
            val dx = x[i] - x[j]; val dy = y[i] - y[j]; val dz = z[i] - z[j]
            var r2 = dx * dx + dy * dy + dz * dz
            val s: Double; val ep: Double; val cut2: Double; val attractive: Boolean
            if (j == i + 3 && chainOf[i] == chainOf[j]) { s = SIG_I3; ep = 0.5; attractive = false; cut2 = s * s }
            else {
                s = 0.9 * (rad[i] + rad[j]); attractive = true; cut2 = (LJ_CUT * s) * (LJ_CUT * s)
                // With a known structure, native pairs hand over to the native term and other contacts weaken
                val w = if (lam == 0.0) 1.0 else if (nat[p]) 1 - lam
                        else if (hasNat[i] && hasNat[j] && natGroup[i] == natGroup[j]) 1 - 0.8 * lam else 1.0
                ep = EPS20[type[i] * 20 + type[j]] * w
            }
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
                    if (r2 < (1.25 * s) * (1.25 * s)) contacts.add(i, j)
                } else {
                    eContact += ep * (s6 * s6 - 2 * s6 + 1)
                }
            }
        }

        // Structure-based contacts: a 12–10 well centred on each pair's distance in the real structure
        if (lam > 0) {
            val epsN = lam * EPS_NATIVE
            val c2 = 1 / (NAT_CUT * NAT_CUT); val c10 = c2 * c2 * c2 * c2 * c2; val c12 = c10 * c2
            val shift = 5 * c12 - 6 * c10
            for (k in 0 until nNat) {
                val i = ncI[k]; val j = ncJ[k]; val r0 = ncR[k]
                val dx = x[i] - x[j]; val dy = y[i] - y[j]; val dz = z[i] - z[j]
                var r2 = dx * dx + dy * dy + dz * dz
                if (chainOf[i] != chainOf[j]) {
                    val r = sqrt(r2).coerceAtLeast(1e-6); val d = r - r0
                    if (d < 4 * W_DOCK) {
                        val g = lam * EPS_DOCK * exp(-d * d / (2 * W_DOCK * W_DOCK))
                        val f = -(g * d / (W_DOCK * W_DOCK)) / r
                        fx[i] += f * dx; fy[i] += f * dy; fz[i] += f * dz
                        fx[j] -= f * dx; fy[j] -= f * dy; fz[j] -= f * dz
                        if (measure) eContact -= g
                    }
                }
                if (r2 >= NAT_CUT * NAT_CUT * r0 * r0) continue
                if (r2 < 1) r2 = 1.0
                val s2 = r0 * r0 / r2; val s10 = s2 * s2 * s2 * s2 * s2; val s12 = s10 * s2
                var f = 60 * epsN * (s12 - s10) / r2
                val r = sqrt(r2)
                if (abs(f * r) > FMAX) f = sign(f) * FMAX / r
                fx[i] += f * dx; fy[i] += f * dy; fz[i] += f * dz
                fx[j] -= f * dx; fy[j] -= f * dy; fz[j] -= f * dz
                if (measure) eContact += epsN * (5 * s12 - 6 * s10 - shift)
            }
        }

        // Screened electrostatics (Debye–Hückel) between charged residues
        val debyeLen = debye(); val ecut = min(3 * debyeLen, ECUT_MAX); val ecut2 = ecut * ecut
        val ea = elecPairs.a; val eb = elecPairs.b
        for (p in 0 until elecPairs.size) {
            val i = ea[p]; val j = eb[p]
            val qq = charge[i] * charge[j]
            if (qq == 0.0) continue
            val dx = x[i] - x[j]; val dy = y[i] - y[j]; val dz = z[i] - z[j]
            var r2 = dx * dx + dy * dy + dz * dz
            if (r2 > ecut2) continue
            if (r2 < 9) r2 = 9.0
            val r = sqrt(r2)
            val u = COULOMB * qq * exp(-r / debyeLen) / (EPS_WATER * r)
            val f = u * (1 / r + 1 / debyeLen) / r
            fx[i] += f * dx; fy[i] += f * dy; fz[i] += f * dz
            fx[j] -= f * dx; fy[j] -= f * dy; fz[j] -= f * dz
            if (measure) eElec += u
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
                val f = -min(5 * (r - rBox), FMAX) / r
                fx[i] += f * x[i]; fy[i] += f * y[i]; fz[i] += f * z[i]
                if (measure) { val d = r - rBox; val dc = FMAX / 5; eBox += if (d < dc) 2.5 * d * d else 2.5 * dc * dc + FMAX * (d - dc) }
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

    // ---------- Chemistry: constant-pH titration and thiol–disulfide reactions (Monte Carlo) ----------
    private fun elecAt(i: Int, dq: Double): Double {
        val lam = debye(); val ecut = min(3 * lam, ECUT_MAX)
        var s = 0.0
        for (a in 0 until nCharged) {
            val j = charged[a]
            if (j == i || (chainOf[i] == chainOf[j] && abs(j - i) < 2)) continue
            val dx = x[i] - x[j]; val dy = y[i] - y[j]; val dz = z[i] - z[j]
            val r2 = dx * dx + dy * dy + dz * dz
            if (r2 > ecut * ecut) continue
            val r = maxOf(3.0, sqrt(r2))
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
    private fun loopOk(i: Int, j: Int) = chainOf[i] != chainOf[j] || abs(j - i) >= SS_MIN_SEP
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
    private fun ssName(i: Int, j: Int) = "${residueLabel(minOf(i, j))}–${residueLabel(maxOf(i, j))}"

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
                if (flashes.size < 400) flashes.add(Flash(s.res, time, if (toDeprot) Flash.LOST else Flash.GAINED))
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
                    log("${ssName(i, j)} disulfide reduced")
                    flashes.add(Flash(i, time, Flash.SS)); flashes.add(Flash(j, time, Flash.SS))
                }
                continue
            }
            for (b in a + 1 until cys.size) {
                val j = cys[b]
                if (partner[j] >= 0 || !loopOk(i, j)) continue
                if (!(thiolate(i) || thiolate(j))) continue
                if (dist(i, j) < SS_REACT && rand() < 0.05 * ox) {
                    bond(i, j)
                    log("${ssName(i, j)} disulfide formed${if (isNative(i, j)) " · native" else ""}")
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
                if (j < 0 || i == k || j == k || !loopOk(k, i)) continue
                if (dist(k, i) < SS_REACT && rand() < 0.008) {
                    unbond(i, j, true)
                    bond(k, i)
                    log("${residueLabel(k)} attacked ${ssName(i, j)} → ${ssName(k, i)}${if (isNative(k, i)) " · native" else ""}")
                    flashes.add(Flash(k, time, Flash.SS)); flashes.add(Flash(j, time, Flash.SS))
                    break
                }
            }
        }
        updateCharges()
        val cutoff = time - 1.2
        flashes.removeAll { it.t <= cutoff }
    }

    // ---------- Integration (Langevin dynamics) ----------
    fun step(count: Int) {
        val c1 = exp(-GAMMA * DT); val c2 = sqrt((1 - c1 * c1) * KB * temperature)
        // Thermal kicks: uniform noise with unit variance. Over many steps it acts like Gaussian noise
        // (the usual shortcut in Langevin codes) and avoids three log/cos calls per residue per step.
        val u = c2 * sqrt(12.0)
        repeat(count) {
            forces(false)
            for (i in 0 until n) {
                vx[i] = (vx[i] + fx[i] * DT) * c1 + u * (rand() - 0.5)
                vy[i] = (vy[i] + fy[i] * DT) * c1 + u * (rand() - 0.5)
                vz[i] = (vz[i] + fz[i] * DT) * c1 + u * (rand() - 0.5)
                x[i] += vx[i] * DT; y[i] += vy[i] * DT; z[i] += vz[i] * DT
            }
            stepCount++
            if (stepCount % MC_EVERY == 0L) chemistry()
        }
    }
    fun advanceClock(dt: Double) { time += dt }

    /** Cheap per-frame update of the centre of mass (measure() does this too). */
    fun updateCenter() {
        var cx = 0.0; var cy = 0.0; var cz = 0.0
        for (i in 0 until n) { cx += x[i]; cy += y[i]; cz += z[i] }
        center[0] = cx / n; center[1] = cy / n; center[2] = cz / n
    }

    // ---------- Observables ----------
    fun measure() {
        forces(true)
        updateCenter()
        val cx = center[0]; val cy = center[1]; val cz = center[2]
        var s2 = 0.0
        for (i in 0 until n) { val dx = x[i] - cx; val dy = y[i] - cy; val dz = z[i] - cz; s2 += dx * dx + dy * dy + dz * dz }
        rg = sqrt(s2 / n)
        // Secondary structure from Cα geometry: two consecutive helical (or extended) dihedrals, within one chain
        ss.fill(0)
        val angs = DoubleArray(n) { Double.NaN }; val dihs = DoubleArray(n) { Double.NaN }
        for (i in 0 until n - 2) if (chainOf[i] == chainOf[i + 2]) angs[i] = angleAt(i)
        for (i in 0 until n - 3) if (chainOf[i] == chainOf[i + 3]) dihs[i] = dihedral(i)
        fun helical(i: Int) = abs(wrap(dihs[i] - PHI_HELIX)) < 35 * DEG && angs[i] < 105 * DEG && angs[i + 1] < 105 * DEG
        fun extended(i: Int) = abs(wrap(dihs[i] - PHI_STRAND)) < 45 * DEG && angs[i] > 105 * DEG && angs[i + 1] > 105 * DEG
        for (i in 0 until n - 4) {
            if (chainOf[i] != chainOf[i + 4]) continue
            if (helical(i) && helical(i + 1)) for (k in i..i + 4) ss[k] = 1
        }
        for (i in 0 until n - 4) {
            if (chainOf[i] != chainOf[i + 4]) continue
            if (extended(i) && extended(i + 1)) for (k in i..i + 4) if (ss[k] == 0) ss[k] = 2
        }
        var h = 0; var st = 0
        for (i in 0 until n) { if (ss[i] == 1) h++ else if (ss[i] == 2) st++ }
        helix = h.toDouble() / n; strand = st.toDouble() / n
        // Salt bridges from the electrostatics list
        bridges.clear()
        for (p in 0 until elecPairs.size) {
            val i = elecPairs.a[p]; val j = elecPairs.b[p]
            if (charge[i] * charge[j] < 0 && (chainOf[i] != chainOf[j] || j - i >= 3) && dist(i, j) < 7.5) bridges.add(intArrayOf(i, j))
        }
        var qSum = 0.0
        for (i in 0 until n) qSum += charge[i]
        netCharge = Math.round(qSum).toInt()
        disulfides.clear()
        for (i in 0 until n) if (partner[i] > i) disulfides.add(intArrayOf(i, partner[i], if (isNative(i, partner[i])) 1 else 0))
        // Interfaces: contacts between chains, and the largest group of chains stuck together
        interfaceContacts = 0
        if (nChains > 1) {
            val parent = IntArray(nChains) { it }
            fun find(a: Int): Int { var r = a; while (parent[r] != r) r = parent[r]; return r }
            fun union(a: Int, b: Int) { val ra = find(a); val rb = find(b); if (ra != rb) parent[ra] = rb }
            for (p in 0 until contacts.size) {
                val ci = chainOf[contacts.a[p]]; val cj = chainOf[contacts.b[p]]
                if (ci != cj) { interfaceContacts++; union(ci, cj) }
            }
            for (d in disulfides) union(chainOf[d[0]], chainOf[d[1]])
            val size = IntArray(nChains)
            for (c in 0 until nChains) size[find(c)]++
            largestComplex = size.max()
        } else largestComplex = 1
        // How close to the known structure
        if (nNat > 0) {
            var formed = 0
            for (k in 0 until nNat) {
                val r0 = ncR[k] * 1.2
                val dx = x[ncI[k]] - x[ncJ[k]]; val dy = y[ncI[k]] - y[ncJ[k]]; val dz = z[ncI[k]] - z[ncJ[k]]
                if (dx * dx + dy * dy + dz * dz < r0 * r0) formed++
            }
            q = formed.toDouble() / nNat
            rmsd = if (rmsdMeaningful) rmsdToNative() else Double.NaN
        }
    }
    private fun angleOf(ax: DoubleArray, ay: DoubleArray, az: DoubleArray, i: Int): Double {
        val j = i + 1; val k = i + 2
        val ux = ax[i] - ax[j]; val uy = ay[i] - ay[j]; val uz = az[i] - az[j]
        val vx = ax[k] - ax[j]; val vy = ay[k] - ay[j]; val vz = az[k] - az[j]
        val lu = sqrt(ux * ux + uy * uy + uz * uz); val lv = sqrt(vx * vx + vy * vy + vz * vz)
        return acos(clamp((ux * vx + uy * vy + uz * vz) / (lu * lv), -1.0, 1.0))
    }
    private fun dihedralOf(ax: DoubleArray, ay: DoubleArray, az: DoubleArray, i: Int): Double {
        val b1x = ax[i + 1] - ax[i]; val b1y = ay[i + 1] - ay[i]; val b1z = az[i + 1] - az[i]
        val b2x = ax[i + 2] - ax[i + 1]; val b2y = ay[i + 2] - ay[i + 1]; val b2z = az[i + 2] - az[i + 1]
        val b3x = ax[i + 3] - ax[i + 2]; val b3y = ay[i + 3] - ay[i + 2]; val b3z = az[i + 3] - az[i + 2]
        val n1x = b1y * b2z - b1z * b2y; val n1y = b1z * b2x - b1x * b2z; val n1z = b1x * b2y - b1y * b2x
        val n2x = b2y * b3z - b2z * b3y; val n2y = b2z * b3x - b2x * b3z; val n2z = b2x * b3y - b2y * b3x
        val lb2 = sqrt(b2x * b2x + b2y * b2y + b2z * b2z)
        return atan2(lb2 * (b1x * n2x + b1y * n2y + b1z * n2z), n1x * n2x + n1y * n2y + n1z * n2z)
    }

    /** Cα RMSD after optimal superposition (Horn's quaternion method), over residues with known positions. */
    fun rmsdToNative(): Double {
        var m = 0
        var ax = 0.0; var ay = 0.0; var az = 0.0; var bx = 0.0; var by = 0.0; var bz = 0.0
        for (i in 0 until n) if (hasNat[i]) { m++; ax += x[i]; ay += y[i]; az += z[i]; bx += natX[i]; by += natY[i]; bz += natZ[i] }
        if (m < 3) return Double.NaN
        ax /= m; ay /= m; az /= m; bx /= m; by /= m; bz /= m
        val s = DoubleArray(9); var g = 0.0
        for (i in 0 until n) if (hasNat[i]) {
            val p = doubleArrayOf(x[i] - ax, y[i] - ay, z[i] - az); val t = doubleArrayOf(natX[i] - bx, natY[i] - by, natZ[i] - bz)
            for (a in 0..2) for (b in 0..2) s[a * 3 + b] += p[a] * t[b]
            g += p[0] * p[0] + p[1] * p[1] + p[2] * p[2] + t[0] * t[0] + t[1] * t[1] + t[2] * t[2]
        }
        val (sxx, sxy, sxz) = Triple(s[0], s[1], s[2]); val (syx, syy, syz) = Triple(s[3], s[4], s[5]); val (szx, szy, szz) = Triple(s[6], s[7], s[8])
        val k = arrayOf(
            doubleArrayOf(sxx + syy + szz, syz - szy, szx - sxz, sxy - syx),
            doubleArrayOf(syz - szy, sxx - syy - szz, sxy + syx, szx + sxz),
            doubleArrayOf(szx - sxz, sxy + syx, -sxx + syy - szz, syz + szy),
            doubleArrayOf(sxy - syx, szx + sxz, syz + szy, -sxx - syy + szz),
        )
        return sqrt(maxOf(0.0, (g - 2 * largestEigenvalue4(k)) / m))
    }
    /** Largest eigenvalue of a symmetric 4×4 matrix by Jacobi rotations. */
    private fun largestEigenvalue4(a: Array<DoubleArray>): Double {
        repeat(60) {
            var p = 0; var q = 1; var big = 0.0
            for (i in 0 until 4) for (j in i + 1 until 4) if (abs(a[i][j]) > big) { big = abs(a[i][j]); p = i; q = j }
            if (big < 1e-12) return@repeat
            val theta = (a[q][q] - a[p][p]) / (2 * a[p][q])
            val t = (if (theta >= 0) 1.0 else -1.0) / (abs(theta) + sqrt(theta * theta + 1))
            val c = 1 / sqrt(t * t + 1); val sn = t * c
            for (r in 0 until 4) { val rp = a[r][p]; val rq = a[r][q]; a[r][p] = c * rp - sn * rq; a[r][q] = sn * rp + c * rq }
            for (r in 0 until 4) { val pr = a[p][r]; val qr = a[q][r]; a[p][r] = c * pr - sn * qr; a[q][r] = sn * pr + c * qr }
        }
        return maxOf(maxOf(a[0][0], a[1][1]), maxOf(a[2][2], a[3][3]))
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

    // ---------- Interaction helpers ----------
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
}
