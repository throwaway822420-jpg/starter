package com.hydrophobiccollapse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max

class ProteinEngineTest {
    private val bpti = Proteins.byId("bpti")

    @Test
    fun forcesMatchEnergyGradient() {
        val e = ProteinEngine()
        e.load(bpti)
        e.forceDisulfide(4, 54)
        e.redox = 1.0
        e.step(4000)
        val analytic = e.forceArrays().let { f -> Array(3) { f[it].copyOf() } }
        e.forces(true)
        val fresh = e.forceArrays().let { f -> Array(3) { f[it].copyOf() } }
        val coords = arrayOf(e.x, e.y, e.z)
        val h = 1e-5
        var maxErr = 0.0
        for (i in 0 until e.n) for (d in 0..2) {
            val o = coords[d][i]
            coords[d][i] = o + h; val ep = e.forces(true)
            coords[d][i] = o - h; val em = e.forces(true)
            coords[d][i] = o
            maxErr = max(maxErr, abs(-(ep - em) / (2 * h) - fresh[d][i]))
        }
        assertTrue("analytic forces off by $maxErr", maxErr < 1e-5)
        assertEquals(analytic.size, 3)
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
}
