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
        val e = ProteinEngine()
        e.load(Proteins.byId("villin"))
        val start = e.rg
        e.temperature = 300.0
        repeat(20) { e.step(2000); e.advanceClock(1.0) }
        e.measure()
        assertTrue("Rg ${e.rg} should shrink from $start", e.rg < start * 0.7)
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
}
