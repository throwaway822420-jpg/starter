package com.hydrophobiccollapse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class GeneticCodeTest {
    @Test
    fun geneticCodeBasics() {
        assertEquals('M', GeneticCode.aminoAcid("ATG")); assertEquals('M', GeneticCode.aminoAcid("AUG"))
        assertEquals('*', GeneticCode.aminoAcid("TAA")); assertEquals('W', GeneticCode.aminoAcid("TGG"))
        assertEquals(61, AMINO_ACIDS.sumOf { GeneticCode.codonsFor(it).size })
        // Each anticodon letter pairs with the codon letter above it
        assertEquals("CGA", GeneticCode.anticodon("GCU")); assertEquals("UAC", GeneticCode.anticodon("AUG"))
    }

    @Test
    fun realGenesCodeForTheirPresets() {
        var real = 0
        for (p in Proteins.presets) {
            val g = Gene.forProtein(p)
            assertEquals(p.chains.size, g.chains.size)
            for ((c, gc) in g.chains.withIndex()) {
                // Start codon, the chain (its own Met may be the start), then a stop
                val made = GeneticCode.translate(gc.coding)
                val expect = (if (gc.codonOffset == 1) "M" else "") + p.chains[c] + "*"
                assertEquals("${p.id} chain $c", expect, made)
                if (gc.real) real++
                val s = gc.slowness
                assertEquals(1.0, s.average(), 1e-9)
                assertTrue(s.all { it > 0.1 && it < 6 })
            }
        }
        assertTrue("real genes: $real", real >= 40)
    }

    @Test
    fun sickleCellIsOneBaseChange() {
        val normal = Gene.forProtein(Proteins.byId("hemoglobin")).chains[1]
        val sickle = Gene.forProtein(Proteins.byId("sickle")).chains[1]
        assertTrue(normal.real && sickle.real)
        // β Glu6Val (residue 6, index 5 in the mature chain): GAG → GTG
        assertEquals("GAG", normal.codons[5]); assertEquals("GTG", sickle.codons[5])
        assertEquals(1, normal.coding.zip(sickle.coding).count { (a, b) -> a != b })
    }

    @Test
    fun designedProteinsGetAStableMadeUpGene() {
        val a = Gene.forProtein(Proteins.byId("top7")).chains[0]
        val b = Gene.forProtein(Proteins.byId("top7")).chains[0]
        assertTrue(!a.real)
        assertEquals(a.coding, b.coding)
    }

    @Test
    fun rareCodonsAreSlower() {
        val u = GeneticCode.usage('B')
        // In E. coli AGG is a famously rare arginine codon, CGT a common one
        assertTrue(GeneticCode.rawSlowness("AGG", u) > 2.5)
        assertTrue(abs(GeneticCode.rawSlowness("CTG", u) - 1.0) < 1e-9)
    }

    @Test
    fun codonTimedTranslationFinishes() {
        val p = Proteins.byId("ubq")
        val gene = Gene.forProtein(p)
        val e = ProteinEngine(); e.seed(31)
        e.ribosome = true; e.codonSlowness = gene.chains[0].slowness
        e.load(p); e.temperature = 300.0
        var steps = 0
        while (e.translating && steps < 100_000) { e.step(100); steps += 100 }
        assertTrue(!e.translating && e.released == e.n)
        assertTrue("no domain waits with codon timing", e.domainCuts.isEmpty())
    }
}
