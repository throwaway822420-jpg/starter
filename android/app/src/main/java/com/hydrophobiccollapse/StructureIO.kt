package com.hydrophobiccollapse

import org.json.JSONArray
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Reads Cα traces from PDB and mmCIF files, and downloads them from the RCSB PDB or the AlphaFold database. */
object StructureIO {
    class Chain(val id: String, val seq: String, val ca: FloatArray)
    class Loaded(val title: String, val source: String, val chains: List<Chain>) {
        val length get() = chains.sumOf { it.seq.length }
        val ca get() = FloatArray(3 * length).also { out -> var k = 0; for (c in chains) { c.ca.copyInto(out, k); k += c.ca.size } }
    }

    private val THREE = mapOf(
        "ALA" to 'A', "ARG" to 'R', "ASN" to 'N', "ASP" to 'D', "CYS" to 'C', "GLN" to 'Q', "GLU" to 'E', "GLY" to 'G',
        "HIS" to 'H', "ILE" to 'I', "LEU" to 'L', "LYS" to 'K', "MET" to 'M', "PHE" to 'F', "PRO" to 'P', "SER" to 'S',
        "THR" to 'T', "TRP" to 'W', "TYR" to 'Y', "VAL" to 'V', "MSE" to 'M', "SEC" to 'C', "HSD" to 'H', "HSE" to 'H',
        "HIE" to 'H', "HID" to 'H', "CYX" to 'C',
    )

    private class Builder(val id: String) {
        val seq = StringBuilder(); val xyz = ArrayList<Float>(); val seen = HashSet<String>()
        fun add(key: String, aa: Char, x: Float, y: Float, z: Float) {
            if (!seen.add(key)) return
            seq.append(aa); xyz.add(x); xyz.add(y); xyz.add(z)
        }
    }

    private fun finish(builders: Collection<Builder>, only: Set<String>?): List<Chain> =
        builders.filter { it.seq.length >= 2 && (only == null || it.id in only) }
            .flatMap { splitAtGaps(Chain(it.id, it.seq.toString(), it.xyz.toFloatArray())) }

    /** A missing stretch of residues shows up as a Cα–Cα jump; start a new chain there instead of bonding across it. */
    fun splitAtGaps(c: Chain, maxBond: Float = 4.3f): List<Chain> {
        val out = ArrayList<Chain>(); var start = 0; var part = 0
        fun cut(end: Int) {
            if (end - start >= 2) out.add(Chain(if (part++ == 0) c.id else "${c.id}${part}", c.seq.substring(start, end), c.ca.copyOfRange(3 * start, 3 * end)))
            start = end
        }
        for (i in 1 until c.seq.length) {
            val dx = c.ca[3 * i] - c.ca[3 * i - 3]; val dy = c.ca[3 * i + 1] - c.ca[3 * i - 2]; val dz = c.ca[3 * i + 2] - c.ca[3 * i - 1]
            if (dx * dx + dy * dy + dz * dz > maxBond * maxBond) cut(i)
        }
        cut(c.seq.length)
        return out
    }

    fun parsePdb(text: String, only: Set<String>? = null): List<Chain> {
        val chains = LinkedHashMap<String, Builder>()
        for (line in text.lineSequence()) {
            if (line.startsWith("ENDMDL")) break                       // first model only
            if (!(line.startsWith("ATOM") || line.startsWith("HETATM")) || line.length < 54) continue
            if (line.substring(12, 16).trim() != "CA") continue
            val alt = line[16]; if (alt != ' ' && alt != 'A') continue
            val aa = THREE[line.substring(17, 20).trim()] ?: continue
            val chain = line[21].toString().trim().ifEmpty { "A" }
            val x = line.substring(30, 38).trim().toFloatOrNull() ?: continue
            val y = line.substring(38, 46).trim().toFloatOrNull() ?: continue
            val z = line.substring(46, 54).trim().toFloatOrNull() ?: continue
            chains.getOrPut(chain) { Builder(chain) }.add(line.substring(22, 27), aa, x, y, z)
        }
        return finish(chains.values, only)
    }

    fun pdbTitle(text: String): String = sentenceCase(text.lineSequence().filter { it.startsWith("TITLE") }
        .joinToString(" ") { it.drop(10).trim() }.replace(Regex("\\s+"), " ").trim())

    /** Legacy PDB titles are all capitals; "CRYSTAL STRUCTURE OF…" reads better as "Crystal structure of…". */
    private fun sentenceCase(t: String) =
        if (t.any { it.isLowerCase() }) t else t.lowercase().replaceFirstChar { it.uppercaseChar() }

    /** Splits an mmCIF data row on whitespace, keeping quoted values together. */
    private fun tokens(line: String): List<String> {
        val out = ArrayList<String>(); var i = 0
        while (i < line.length) {
            while (i < line.length && line[i].isWhitespace()) i++
            if (i >= line.length) break
            val q = line[i]
            if (q == '\'' || q == '"') {
                val end = line.indexOf(q, i + 1).let { if (it < 0) line.length else it }
                out.add(line.substring(i + 1, end)); i = end + 1
            } else {
                val start = i
                while (i < line.length && !line[i].isWhitespace()) i++
                out.add(line.substring(start, i))
            }
        }
        return out
    }

    fun parseCif(text: String, only: Set<String>? = null): List<Chain> {
        val lines = text.lines()
        var k = 0
        val cols = ArrayList<String>()
        while (k < lines.size) {
            if (lines[k].trim() == "loop_" && k + 1 < lines.size && lines[k + 1].startsWith("_atom_site.")) {
                k++
                while (k < lines.size && lines[k].startsWith("_atom_site.")) { cols.add(lines[k].trim().removePrefix("_atom_site.")); k++ }
                break
            }
            k++
        }
        if (cols.isEmpty()) return emptyList()
        fun col(vararg names: String) = names.map { cols.indexOf(it) }.firstOrNull { it >= 0 } ?: -1
        val cAtom = col("label_atom_id", "auth_atom_id"); val cAlt = col("label_alt_id"); val cRes = col("label_comp_id", "auth_comp_id")
        val cChain = col("auth_asym_id", "label_asym_id"); val cSeq = col("auth_seq_id", "label_seq_id"); val cIns = col("pdbx_PDB_ins_code")
        val cx = col("Cartn_x"); val cy = col("Cartn_y"); val cz = col("Cartn_z"); val cModel = col("pdbx_PDB_model_num")
        if (listOf(cAtom, cRes, cChain, cSeq, cx, cy, cz).any { it < 0 }) return emptyList()
        val chains = LinkedHashMap<String, Builder>()
        var firstModel: String? = null
        while (k < lines.size) {
            val line = lines[k++]
            if (line.startsWith("#") || line.startsWith("loop_") || line.startsWith("_")) break
            val t = tokens(line)
            if (t.size < cols.size) continue
            if (cModel >= 0) { if (firstModel == null) firstModel = t[cModel]; if (t[cModel] != firstModel) break }
            if (t[cAtom] != "CA") continue
            if (cAlt >= 0 && t[cAlt] != "." && t[cAlt] != "A") continue
            val aa = THREE[t[cRes]] ?: continue
            val chain = t[cChain]
            val key = t[cSeq] + (if (cIns >= 0) t[cIns] else "")
            chains.getOrPut(chain) { Builder(chain) }.add(key, aa, t[cx].toFloatOrNull() ?: continue, t[cy].toFloatOrNull() ?: continue, t[cz].toFloatOrNull() ?: continue)
        }
        return finish(chains.values, only)
    }

    fun cifTitle(text: String): String {
        val line = text.lineSequence().firstOrNull { it.startsWith("_struct.title") } ?: return ""
        return tokens(line).drop(1).joinToString(" ").trim()
    }

    // ---------- Downloading (call off the main thread) ----------
    private val PDB_ID = Regex("^[0-9][A-Za-z0-9]{3}$")
    private val UNIPROT = Regex("^([OPQ][0-9][A-Z0-9]{3}[0-9]|[A-NR-Z][0-9]([A-Z][A-Z0-9]{2}[0-9]){1,2})$")

    /** Returns null if the text looks like a PDB ID ("1UBQ", "2HHB:A,B") or UniProt accession ("P69905"), else a hint. */
    fun queryProblem(q: String): String? {
        val (id, _) = splitQuery(q)
        return if (PDB_ID.matches(id) || UNIPROT.matches(id)) null
        else "Enter a PDB ID like 1UBQ (add :A,B to pick chains) or a UniProt ID like P69905."
    }
    private fun splitQuery(q: String): Pair<String, Set<String>?> {
        val t = q.trim().removePrefix("AF-").removeSuffix("-F1")
        val parts = t.split(':', limit = 2)
        val chains = parts.getOrNull(1)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
        return parts[0].uppercase() to chains?.ifEmpty { null }
    }

    private fun get(url: String, limit: Int = 40_000_000): String? {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 30000
        c.setRequestProperty("User-Agent", "HydrophobicCollapse/2")
        try {
            if (c.responseCode == 404) return null
            if (c.responseCode !in 200..299) throw IOException("The server answered ${c.responseCode}.")
            val bytes = c.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(65536); var total = 0
                while (true) {
                    val r = input.read(buf); if (r < 0) break
                    total += r; if (total > limit) throw IOException("That file is too large to load on a tablet. Pick chains, like 4V6X:A,B.")
                    out.write(buf, 0, r)
                }
                out.toByteArray()
            }
            return String(bytes)
        } finally { c.disconnect() }
    }

    /** Downloads and parses a structure. Throws IOException with a message fit to show the user. */
    fun fetch(query: String): Loaded {
        queryProblem(query)?.let { throw IOException(it) }
        val (id, only) = splitQuery(query)
        val loaded = if (PDB_ID.matches(id)) {
            val pdb = get("https://files.rcsb.org/download/$id.pdb")
            if (pdb != null) Loaded(pdbTitle(pdb).ifEmpty { "PDB $id" }, "PDB $id", parsePdb(pdb, only))
            else {
                val cif = get("https://files.rcsb.org/download/$id.cif") ?: throw IOException("No PDB entry called $id.")
                Loaded(cifTitle(cif).ifEmpty { "PDB $id" }, "PDB $id", parseCif(cif, only))
            }
        } else {
            val api = get("https://alphafold.ebi.ac.uk/api/prediction/$id") ?: throw IOException("AlphaFold has no prediction for $id.")
            val entry = JSONArray(api).getJSONObject(0)
            val url = entry.optString("pdbUrl").ifEmpty { throw IOException("AlphaFold has no model file for $id.") }
            val pdb = get(url) ?: throw IOException("The AlphaFold model for $id could not be downloaded.")
            val title = entry.optString("uniprotDescription").ifEmpty { entry.optString("gene") }.ifEmpty { "AlphaFold $id" }
            Loaded(title, "AlphaFold $id", parsePdb(pdb, only))
        }
        if (loaded.chains.isEmpty()) throw IOException(if (only != null) "None of those chains has protein residues." else "No protein chains found in $id.")
        if (loaded.length > Proteins.MAX_RESIDUES)
            throw IOException("$id has ${loaded.length} residues; the limit is ${Proteins.MAX_RESIDUES}. Pick chains, like $id:A,B.")
        return loaded
    }
}
