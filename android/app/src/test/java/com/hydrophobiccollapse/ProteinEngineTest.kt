package com.hydrophobiccollapse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.random.Random

class ProteinEngineTest {
    private fun gradientError(e: ProteinEngine): Double {
        e.forces(true)
        val f = e.forceArrays().let { a -> Array(3) { a[it].copyOf() } }
        val coords = arrayOf(e.x, e.y, e.z)
        val h = 1e-5
        var maxErr = 0.0
        for (i in 0 until e.n) for (d in 0..2) {
            val o = coords[d][i]
            coords[d][i] = o + h; val ep = e.forces(true)
            coords[d][i] = o - h; val em = e.forces(true)
            coords[d][i] = o
            maxErr = max(maxErr, abs(-(ep - em) / (2 * h) - f[d][i]))
        }
        return maxErr
    }

    @Test
    fun forcesMatchEnergyGradient() {
        val e = ProteinEngine()
        e.load(Proteins.byId("bpti"))
        e.forceDisulfide(4, 54)
        e.redox = 1.0
        e.step(4000)
        val err = gradientError(e)
        assertTrue("analytic forces off by $err", err < 1e-5)
    }

    @Test
    fun forcesMatchEnergyGradientAcrossChains() {
        val e = ProteinEngine()
        e.load(Proteins.byId("insulin"))
        e.forceDisulfide(6, 27) // A7–B7
        e.redox = 1.0
        e.step(4000)
        val err = gradientError(e)
        assertTrue("analytic forces off by $err", err < 1e-5)
    }

    @Test
    fun neighbourListsMatchAllPairs() {
        for (p in listOf(Proteins.byId("hemoglobin"), Proteins.random(900, 0, Random(7)))) {
            val e = ProteinEngine()
            e.load(p)
            e.temperature = 300.0
            e.step(3000)
            e.useNeighbourLists = true
            val fast = e.forces(true)
            e.useNeighbourLists = false
            val slow = e.forces(true)
            assertEquals("${p.name}: neighbour lists changed the energy", slow, fast, 1e-6 * max(1.0, abs(slow)))
        }
    }

    @Test
    fun chainCollapsesAtRoomTemperature() {
        // Generic physics alone (no structure guidance) collapses an unfolded chain
        val shrink = (1..3).map { attempt ->
            val e = ProteinEngine(); e.seed(31L * attempt); e.nativeBias = 0.0
            e.load(Proteins.byId("ubq"))
            val start = e.rg
            e.temperature = 300.0
            repeat(20) { e.step(2000) }
            e.measure()
            e.rg / start
        }
        assertTrue("Rg ratios $shrink", shrink.min() < 0.75)
    }

    @Test
    fun lowPhProtonatesAcids() {
        val e = ProteinEngine()
        e.load(Proteins.byId("villin"))
        e.pH = 1.0
        e.step(2000); e.measure()
        // At pH 1 every Asp/Glu and the C-terminus is neutral, every base is +1
        val bases = e.seq.count { it in "KRH" } + 1
        assertEquals(bases, e.netCharge)
    }

    @Test
    fun presetsAreWellFormed() {
        for (p in Proteins.presets) {
            assertTrue(p.name, p.chains.all { c -> c.all { it in AMINO_ACIDS } })
            val seq = p.chains.joinToString("")
            for (pair in p.native) {
                assertEquals("${p.name} native pair ${pair.toList()}", 'C', seq[pair[0] - 1])
                assertEquals("${p.name} native pair ${pair.toList()}", 'C', seq[pair[1] - 1])
            }
        }
        val hbb = Proteins.byId("hemoglobin").chains[1]; val hbs = Proteins.byId("sickle").chains[1]
        assertEquals(1, hbb.indices.count { hbb[it] != hbs[it] })
        assertEquals('E', hbb[5]); assertEquals('V', hbs[5])
    }

    @Test
    fun parsesSequences() {
        val fasta = Proteins.parse(">sp|A\nmqif vktl\n>B\nGIVEQCC 12")
        assertNull(fasta.error)
        assertEquals(listOf("MQIFVKTL", "GIVEQCC"), fasta.chains)
        assertEquals(listOf("ACD", "EFG"), Proteins.parse("acd / efg").chains)
        assertNotNull(Proteins.parse("ACDXZ").error)
        assertNotNull(Proteins.parse("  ").error)
    }

    @Test
    fun randomProteinsHaveTheRequestedLength() {
        for (style in 0..2) for (len in listOf(20, 137, 3000)) {
            val p = Proteins.random(len, style, Random(len + style))
            assertEquals(len, p.length)
            assertTrue(p.chains[0].all { it in AMINO_ACIDS })
        }
    }

    @Test
    fun largeProteinStepsInReasonableTime() {
        val e = ProteinEngine()
        e.load(Proteins.random(3000, 0, Random(1)))
        e.step(50)
        val t0 = System.nanoTime()
        e.step(200)
        val ms = (System.nanoTime() - t0) / 1e6 / 200
        println("3000 residues: %.2f ms per step".format(ms))
        assertTrue("too slow: $ms ms/step", ms < 20)
    }

    // ---------- Structure-based guidance ----------
    @Test
    fun forcesMatchEnergyGradientWithStructure() {
        for (bias in listOf(1.0, 0.6)) {
            val e = ProteinEngine(); e.nativeBias = bias
            e.load(Proteins.byId("insulin"))
            e.forceDisulfide(6, 27)
            e.redox = 1.0
            e.step(3000)
            val err = gradientError(e)
            assertTrue("bias $bias: analytic forces off by $err", err < 1e-5)
        }
    }

    @Test
    fun rmsdIsZeroForTheRealStructureMovedAndTurned() {
        val p = Proteins.byId("ubq"); val ca = p.ca!!
        val e = ProteinEngine(); e.load(p)
        val c = Math.cos(0.7); val s = Math.sin(0.7)
        for (i in 0 until e.n) {
            val x = ca[3 * i].toDouble(); val y = ca[3 * i + 1].toDouble()
            e.x[i] = c * x - s * y + 12; e.y[i] = s * x + c * y - 5; e.z[i] = ca[3 * i + 2] + 3.0
        }
        assertEquals(0.0, e.rmsdToNative(), 1e-3)
        for (i in 0 until e.n) e.z[i] = -e.z[i]   // the mirror image is not the same fold
        assertTrue(e.rmsdToNative() > 3)
    }

    @Test
    fun smallProteinFoldsToItsRealStructure() {
        // Folding is stochastic; Trp-cage folds in almost every 30 s run, so allow three tries
        val best = (1..3).map { attempt ->
            val e = ProteinEngine(); e.seed(1234L * attempt)
            e.load(Proteins.byId("trpcage")); e.temperature = 300.0
            repeat(30) { e.step(2000) }
            e.measure(); e.rmsd
        }.min()
        assertTrue("best RMSD $best Å", best < 3.0)
    }

    @Test
    fun heatUnfoldsAStructuredProtein() {
        // Average native contacts over the last 10 s: clearly fewer at 460 K than at 300 K
        fun meanQ(temp: Double): Double {
            val e = ProteinEngine(); e.seed(99)
            e.load(Proteins.byId("villin")); e.temperature = temp
            repeat(20) { e.step(2000) }
            return (1..10).map { e.step(2000); e.measure(); e.q }.average()
        }
        val cold = meanQ(300.0); val hot = meanQ(460.0)
        assertTrue("Q at 300 K $cold, at 460 K $hot", hot < cold - 0.15)
    }

    @Test
    fun presetsWithStructuresDecode() {
        val withStructure = Proteins.presets.filter { it.ca != null }
        assertTrue(withStructure.size >= 15)
        for (p in withStructure) {
            val known = (0 until p.length).count { !p.ca!![3 * it].isNaN() }
            assertTrue("${p.name}: only $known of ${p.length} residues placed", known >= 0.9 * p.length)
        }
        assertNull(Proteins.byId("abeta").ca)
    }

    // ---------- Structure files ----------
    private val pdbText = """
HEADER    TEST
TITLE     A TINY TEST PEPTIDE
ATOM      1  N   MET A   1      27.340  24.430   2.614  1.00  9.67           N
ATOM      2  CA  MET A   1      26.266  25.413   2.842  1.00 10.38           C
ATOM      3  CA AGLN A   2      26.850  29.021   3.898  0.50  9.62           C
ATOM      4  CA BGLN A   2      99.000  99.000  99.000  0.50  9.62           C
ATOM      5  CA  ILE A   3      26.235  30.058   7.497  1.00  7.19           C
ATOM      6  CA  PHE A   4      26.772  33.436   9.197  1.00  6.15           C
ATOM      7  CA  VAL A   5      60.000  33.436   9.197  1.00  6.15           C
ATOM      8  CA  LYS A   6      63.500  33.436   9.197  1.00  6.15           C
HETATM    9 CA    CA A 101      10.000  10.000  10.000  1.00  6.15          CA
ATOM     10  CA  GLY B   1       1.000   1.000   1.000  1.00  6.15           C
ATOM     11  CA  ALA B   2       4.500   1.000   1.000  1.00  6.15           C
ENDMDL
ATOM     12  CA  TRP A   7       0.000   0.000   0.000  1.00  6.15           C
""".trimIndent()

    @Test
    fun readsPdbFiles() {
        val chains = StructureIO.parsePdb(pdbText)
        // Chain A splits at the 33 Å gap after residue 4; the second model and the calcium ion are ignored
        assertEquals(listOf("MQIF", "VK", "GA"), chains.map { it.seq })
        assertEquals(26.85f, chains[0].ca[3], 1e-3f)   // alternate location A, not B
        assertEquals(listOf("GA"), StructureIO.parsePdb(pdbText, setOf("B")).map { it.seq })
        assertEquals("A tiny test peptide", StructureIO.pdbTitle(pdbText))
    }

    @Test
    fun readsMmcifFiles() {
        val cif = """
data_TEST
_struct.title 'A tiny test peptide'
loop_
_atom_site.group_PDB
_atom_site.id
_atom_site.label_atom_id
_atom_site.label_alt_id
_atom_site.label_comp_id
_atom_site.auth_asym_id
_atom_site.auth_seq_id
_atom_site.pdbx_PDB_ins_code
_atom_site.Cartn_x
_atom_site.Cartn_y
_atom_site.Cartn_z
_atom_site.pdbx_PDB_model_num
ATOM 1 N . MET A 1 ? 27.340 24.430 2.614 1
ATOM 2 CA . MET A 1 ? 26.266 25.413 2.842 1
ATOM 3 CA A GLN A 2 ? 26.850 29.021 3.898 1
ATOM 4 CA B GLN A 2 ? 99.0 99.0 99.0 1
ATOM 5 CA . ILE A 3 ? 26.235 30.058 7.497 1
ATOM 6 "O5'" . ILE A 3 ? 0 0 0 1
ATOM 7 CA . PHE A 4 ? 26.772 33.436 9.197 2
#
""".trimIndent()
        assertEquals(listOf("MQI"), StructureIO.parseCif(cif).map { it.seq })
        assertEquals("A tiny test peptide", StructureIO.cifTitle(cif))
    }

    @Test
    fun recognisesStructureIds() {
        assertNull(StructureIO.queryProblem("1UBQ"))
        assertNull(StructureIO.queryProblem("2hhb:A,B"))
        assertNull(StructureIO.queryProblem("P69905"))
        assertNull(StructureIO.queryProblem("AF-P69905-F1"))
        assertNotNull(StructureIO.queryProblem("hemoglobin"))
    }

    // ---------- Crowding ----------
    @Test
    fun severalChainsAreKeptCloseEnoughToMeet() {
        val e = ProteinEngine()
        e.load(Proteins.byId("hemoglobin"))
        // Cell-like crowding: the folded tetramer (~26 Å radius) fills a quarter of the sphere
        assertTrue("box ${e.boxSize} Å", e.boxSize in 35.0..50.0)
        e.crowding = 0
        assertTrue("dilute box ${e.boxSize} Å", e.boxSize > 70)
        val single = ProteinEngine(); single.load(Proteins.byId("myoglobin")); single.crowding = 2
        assertEquals(5 * Math.sqrt(153.0) + 10, single.boxSize, 1e-9)   // one chain: unchanged
    }

    @Test
    fun amyloidPeptidesClumpWhenCrowded() {
        val e = ProteinEngine(); e.seed(7)
        e.load(Proteins.byId("abeta6")); e.temperature = 300.0
        repeat(30) { e.step(2000) }
        e.measure()
        assertTrue("largest clump ${e.largestComplex} of 6", e.largestComplex >= 4)
    }
}
