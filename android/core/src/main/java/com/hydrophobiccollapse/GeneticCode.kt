package com.hydrophobiccollapse

import kotlin.math.max
import kotlin.math.sqrt

/**
 * A preset chain's real coding sequence (see tools/extract_genes.py): its codons only (no start or stop), the
 * ENA protein id it came from, gene and organism names, whose codon usage applies (B bacteria and phages, H animals
 * and plants, Y yeast), the stop codon that follows it in the real gene ("" when the chain is cut out of a longer
 * protein) and how many codons were changed to match the preset (a mutation, or an old sequencing difference).
 */
class GeneRecord(
    val dna: String, val source: String, val gene: String, val organism: String,
    val host: Char, val stop: String, val edited: Int,
)

/** The standard genetic code and codon speeds. Sequences are DNA (T); [rna] turns them into RNA (U). */
object GeneticCode {
    private const val BASES = "TCAG"
    private const val AAS = "FFLLSSSSYY**CC*WLLLLPPPPHHQQRRRRIIIMTTTTNNKKSSRRVVVVAAAADDEEGGGG"
    const val START = "ATG"

    private fun baseIndex(b: Char) = when (b) { 'T', 'U' -> 0; 'C' -> 1; 'A' -> 2; 'G' -> 3; else -> -1 }

    /** The amino acid a codon (DNA or RNA) codes for: one letter, '*' for stop, 'X' for anything else. */
    fun aminoAcid(codon: CharSequence): Char {
        if (codon.length != 3) return 'X'
        val a = baseIndex(codon[0]); val b = baseIndex(codon[1]); val c = baseIndex(codon[2])
        return if (a < 0 || b < 0 || c < 0) 'X' else AAS[a * 16 + b * 4 + c]
    }
    fun translate(dna: String) = String(CharArray(dna.length / 3) { aminoAcid(dna.substring(3 * it, 3 * it + 3)) })
    fun rna(dna: String) = dna.replace('T', 'U')
    fun complement(b: Char) = when (b) { 'A' -> 'T'; 'T' -> 'A'; 'U' -> 'A'; 'G' -> 'C'; 'C' -> 'G'; else -> 'N' }
    /** The tRNA anticodon that pairs with an mRNA codon, written 3'→5' under it (so each letter pairs with the one above). */
    fun anticodon(codonRna: String) = String(CharArray(3) { if (codonRna[it] == 'A') 'U' else complement(codonRna[it]) })

    /** All codons for an amino acid, as DNA. */
    fun codonsFor(aa: Char): List<String> {
        val out = ArrayList<String>()
        for (i in 0 until 64) if (AAS[i] == aa) out += "${BASES[i / 16]}${BASES[i / 4 % 4]}${BASES[i % 4]}"
        return out
    }

    fun usage(host: Char): Map<String, Double> = when (host) { 'B' -> Genes.USAGE_ECOLI; 'Y' -> Genes.USAGE_YEAST; else -> Genes.USAGE_HUMAN }
    fun hostName(host: Char) = when (host) { 'B' -> "E. coli"; 'Y' -> "yeast"; else -> "human" }

    /**
     * How long a ribosome takes over each codon, relative to the gene's average (mean 1). A codon's "relative
     * adaptiveness" is its share of the amino acid's codons over that of the most used synonym (as in the codon
     * adaptation index). Rare codons have few matching tRNAs, so the ribosome waits longer for one: here the time
     * goes as 1/√w, from 1× for the favourite codon to 5× at most.
     */
    fun slowness(codons: List<String>, usage: Map<String, Double>): DoubleArray {
        if (codons.isEmpty()) return DoubleArray(0)
        val raw = DoubleArray(codons.size) { k -> rawSlowness(codons[k], usage) }
        val mean = raw.average()
        return DoubleArray(raw.size) { raw[it] / mean }
    }
    /** Time over a codon relative to the fastest synonym, 1…5. */
    fun rawSlowness(codon: String, usage: Map<String, Double>): Double {
        val aa = aminoAcid(codon)
        val best = codonsFor(aa).maxOf { usage[it] ?: 0.0 }
        val w = if (best <= 0) 1.0 else (usage[codon] ?: 0.0) / best
        return (1 / sqrt(max(w, 0.04))).coerceIn(1.0, 5.0)
    }

    /** Codons for a protein with no known gene, drawn by the host's usage (seeded, so the same protein gets the same gene). */
    fun reverseTranslate(protein: String, usage: Map<String, Double>, seed: Long): List<String> {
        val rnd = java.util.Random(seed)
        return protein.map { aa ->
            val options = codonsFor(aa)
            if (options.isEmpty()) "NNN" else {
                val total = options.sumOf { usage[it] ?: 0.0 }
                var r = rnd.nextDouble() * total
                options.firstOrNull { r -= usage[it] ?: 0.0; r <= 0 } ?: options.last()
            }
        }
    }
}

/** One chain's gene: the codons for its residues, the stop codon, and where they came from. */
class GeneChain(val protein: String, val codons: List<String>, val stop: String, val record: GeneRecord?, val host: Char) {
    val real get() = record != null
    /** 1 when a start codon comes before the chain's own codons (its initiator Met is trimmed off later), else 0. */
    val codonOffset = if (codons.firstOrNull() == GeneticCode.START) 0 else 1
    /** The codons the ribosome reads, start codon to stop codon (DNA letters). */
    val mrnaCodons: List<String> = (if (codonOffset == 1) listOf(GeneticCode.START) else emptyList()) + codons + stop
    /** The mRNA's coding part, start codon to stop codon. */
    val coding get() = mrnaCodons.joinToString("")
    /** Relative time the ribosome spends on each residue's codon (mean 1). */
    val slowness by lazy { GeneticCode.slowness(codons, GeneticCode.usage(host)) }
    /** Short description of the source, for the screen. */
    val sourceText: String get() {
        val r = record ?: return "No natural gene: codons picked by ${GeneticCode.hostName(host)} usage"
        val org = r.organism.substringBefore(" (")
        val edits = if (r.edited > 0) " · ${r.edited} codon${if (r.edited > 1) "s" else ""} changed to match" else ""
        return "Real gene: ${r.gene}, $org (ENA ${r.source})$edits"
    }
}

/** The genes behind a protein, one per chain (identical chains share one). */
class Gene(val chains: List<GeneChain>) {
    /** Distinct genes, in order of first use: the ones transcription has to show. */
    val distinct: List<GeneChain> get() = chains.distinctBy { it.coding }

    companion object {
        fun forProtein(p: Protein): Gene {
            val records = Genes.byPreset[p.id]
            return Gene(p.chains.mapIndexed { c, seq ->
                val rec = records?.getOrNull(c)?.takeIf { GeneticCode.translate(it.dna) == seq }
                if (rec != null) GeneChain(seq, rec.dna.chunked(3), rec.stop.ifEmpty { "TAA" }, rec, rec.host)
                else GeneChain(seq, GeneticCode.reverseTranslate(seq, Genes.USAGE_ECOLI, seq.hashCode().toLong()), "TAA", null, 'B')
            })
        }
    }
}
