package com.hydrophobiccollapse

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

/**
 * A protein made of one or more chains. [native] lists known disulfide pairs as 1-based
 * positions counted along all chains joined end to end.
 */
class Protein(
    val id: String,
    val name: String,
    val chains: List<String>,
    val native: List<IntArray> = emptyList(),
    val note: String = "",
    val group: String = "",
    /** Known folded structure: Cα x,y,z per residue (NaN where unknown), or null. */
    val ca: FloatArray? = null,
    /** Where [ca] came from, e.g. "PDB 1UBQ" or "AlphaFold P69905". */
    val structureSource: String? = null,
    /** Which copy each chain belongs to; native contacts only join chains of the same copy. */
    val copyOf: IntArray? = null,
) {
    val length get() = chains.sumOf { it.length }
    /** Changes whenever the chemistry or structure changes, so a running simulation knows to reload. */
    val signature get() = chains.joinToString("/") + "|" + (structureSource ?: "")

    fun withStructure(ca: FloatArray?, source: String?) =
        Protein(id, name, chains, native, note, group, ca, source, copyOf)
}

/** Cα coordinates as text: "x,y,z" in tenths of an Å per residue, ";" between residues, "_" for none. */
object CaCodec {
    fun decode(s: String, n: Int): FloatArray? {
        val parts = s.split(';')
        if (parts.size != n) return null
        val out = FloatArray(3 * n) { Float.NaN }
        for ((k, p) in parts.withIndex()) {
            if (p == "_") continue
            val xyz = p.split(',')
            if (xyz.size != 3) return null
            for (d in 0..2) out[3 * k + d] = (xyz[d].toIntOrNull() ?: return null) / 10f
        }
        return out
    }
    fun encode(ca: FloatArray): String = (0 until ca.size / 3).joinToString(";") { k ->
        if (ca[3 * k].isNaN()) "_" else "${Math.round(ca[3 * k] * 10)},${Math.round(ca[3 * k + 1] * 10)},${Math.round(ca[3 * k + 2] * 10)}"
    }
}

object Proteins {
    const val RANDOM_ID = "random"
    const val MAX_RESIDUES = 3000
    const val MAX_COPIES = 24

    private fun p(a: Int, b: Int) = intArrayOf(a, b)
    private const val AB42 = "DAEFRHDSGYEVHHQKLVFFAEDVGSNKGAIIGLMVGGVVIA"
    private const val HBA = "VLSPADKTNVKAAWGKVGAHAGEYGAEALERMFLSFPTTKTYFPHFDLSHGSAQVKGHGKKVADALTNAVAHVDDMPNALSALSDLHAHKLRVDPVNFKLLSHCLLVTLAAHLPAEFTPAVHASLDKFLASVSTVLTSKYR"
    private const val HBB = "VHLTPEEKSAVTALWGKVNVDEVGGEALGRLLVVYPWTQRFFESFGDLSTPDAVMGNPKVKAHGKKVLGAFSDGLAHLDNLKGTFATLSELHCDKLHVDPENFRLLGNVLVCVLAHHFGKEFTPPVQAAYQKVVAGVANALAHKYH"
    private const val HBS = "VHLTPVEKSAVTALWGKVNVDEVGGEALGRLLVVYPWTQRFFESFGDLSTPDAVMGNPKVKAHGKKVLGAFSDGLAHLDNLKGTFATLSELHCDKLHVDPENFRLLGNVLVCVLAHHFGKEFTPPVQAAYQKVVAGVANALAHKYH"
    private const val GCN4 = "RMKQLEDKVEELLSKNYHLENEVARLKKLVGER"

    // Natural sequences are checked against UniProt; mature chains without signal peptides or initiator Met.
    val presets: List<Protein> by lazy { basePresets.map { p ->
        val enc = NativeStructures.encoded[p.id] ?: return@map p
        p.withStructure(CaCodec.decode(enc.second, p.length), enc.first)
    } }

    private val basePresets = listOf(
        Protein("chignolin", "Chignolin", listOf("GYDPETGTWG"),
            note = "A 10-residue designed β-hairpin, one of the smallest things that folds.", group = "Small and fast"),
        Protein("trpcage", "Trp-cage TC5b", listOf("NLYIQWLKDGGPSSGRPPPS"),
            note = "A 20-residue designed miniprotein: a short helix packed around a tryptophan.", group = "Small and fast"),
        Protein("gb1", "GB1 hairpin", listOf("GEWTYDDATKTFTVTE"),
            note = "Residues 41–56 of protein G: a β-hairpin that folds on its own.", group = "Small and fast"),
        Protein("villin", "Villin headpiece HP35", listOf("LSDEDFKAVFGMTRSAFANLPLWKQQNLKKEKGLF"),
            note = "A fast-folding three-helix bundle, a favourite of folding simulations.", group = "Small and fast"),
        Protein("oxytocin", "Oxytocin", listOf("CYIQNCPLG"), listOf(p(1, 6)),
            note = "The 9-residue hormone, closed into a ring by a Cys1–Cys6 disulfide.", group = "Hormones and peptides"),
        Protein("melittin", "Melittin", listOf("GIGAVLKVLTTGLPALISWIKRKRQQ"),
            note = "Honeybee venom peptide. A bent helix with a very basic tail.", group = "Hormones and peptides"),
        Protein("glucagon", "Glucagon", listOf("HSQGTFTSDYSKYLDSRRAQDFVQWLMNT"),
            note = "29-residue hormone that raises blood sugar.", group = "Hormones and peptides"),
        Protein("glp1", "GLP-1 (7–37)", listOf("HAEGTFTSDVSSYLEGQAAKEFIAWLVKGRG"),
            note = "The gut hormone that drugs like semaglutide mimic.", group = "Hormones and peptides"),
        Protein("insulin", "Insulin (A + B chains)", listOf("GIVEQCCTSICSLYQLENYCN", "FVNQHLCGSHLVEALYLVCGERGFFYTPKT"),
            listOf(p(6, 11), p(7, 28), p(20, 40)),
            note = "Two chains held together by disulfides A7–B7 and A20–B19, plus A6–A11 inside chain A.", group = "Disulfide-bonded"),
        Protein("bpti", "BPTI", listOf("RPDFCLEPPYTGPCKARIIRYFYNAKAGLCQTFVYGGCRAKRNNFKSAEDCMRTCGGA"),
            listOf(p(5, 55), p(14, 38), p(30, 51)),
            note = "Bovine pancreatic trypsin inhibitor. Six cysteines; the native fold pairs 5–55, 14–38 and 30–51.", group = "Disulfide-bonded"),
        Protein("lysozyme", "Lysozyme (hen egg white)",
            listOf("KVFGRCELAAAMKRHGLDNYRGYSLGNWVCAAKFESNFNTQATNRNTDGSTDYGILQINSRWWCNDGRTPGSRNLCNIPCSALLSSDITASVNCAKKIVSDGNGMNAWVAWRNRCKGTDVQAWIRGCRL"),
            listOf(p(6, 127), p(30, 115), p(64, 80), p(76, 94)),
            note = "129 residues, four disulfides. The first enzyme whose structure was solved.", group = "Disulfide-bonded"),
        Protein("abeta", "Amyloid-β 1–42", listOf(AB42),
            note = "The Alzheimer’s peptide. Mostly disordered, with a very hydrophobic C-terminal tail.", group = "Classic folds"),
        Protein("ubq", "Ubiquitin", listOf("MQIFVKTLTGKTITLEVEPSDTIENVKAKIQDKEGIPPDQQRLIFAGKQLEDGRTLSDYNIQKESTLHLVLRLRGG"),
            note = "76 residues, mixed helix and sheet. Tags proteins for destruction.", group = "Classic folds"),
        Protein("myoglobin", "Myoglobin (sperm whale)",
            listOf("VLSEGEWQLVLHVWAKVEADVAGHGQDILIRLFKSHPETLEKFDRFKHLKTEAEMKASEDLKKHGVTVLTALGAILKKKGHHEAELKPLAQSHATKHKIPIKYLEFISEAIIHVLHSRHPGDFGADAQGAMNKALELFRKDIAAKYKELGYQG"),
            note = "153 residues, eight helices. The first protein structure ever solved (the heme is not modelled).", group = "Classic folds"),
        Protein("gfp", "Green fluorescent protein",
            listOf("MSKGEELFTGVVPILVELDGDVNGHKFSVSGEGEGDATYGKLTLKFICTTGKLPVPWPTLVTTFSYGVQCFSRYPDHMKQHDFFKSAMPEGYVQERTIFFKDDGNYKTRAEVKFEGDTLVNRIELKGIDFKEDGNILGHKLEYNYNSHNVYIMADKQKNGIKVNFKIRHNIEDGSVQLADHYQQNTPIGDGPVLLPDNHYLSTQSALSKDPNEKRDHMVLLEFVTAAGITHGMDELYK"),
            note = "238 residues from the jellyfish Aequorea victoria. Natively an 11-strand β-barrel.", group = "Classic folds"),
        Protein("gcn4", "GCN4 leucine zipper (dimer)", listOf(GCN4, GCN4),
            note = "Two helices that wrap around each other, zipped by leucines every seven residues.", group = "Interactions"),
        Protein("hemoglobin", "Hemoglobin α₂β₂", listOf(HBA, HBB, HBA, HBB),
            note = "Four chains, 574 residues. The oxygen carrier in red blood cells (hemes not modelled).", group = "Interactions"),
        Protein("sickle", "Sickle hemoglobin (β Glu6Val)", listOf(HBA, HBS, HBA, HBS),
            note = "One change, Glu→Val at β6, puts a sticky hydrophobic patch on the surface. That patch makes HbS polymerise.", group = "Interactions"),
        Protein("abeta6", "Amyloid-β 1–42 × 6", List(6) { AB42 },
            note = "Six peptides in one box. Watch the hydrophobic tails find each other and clump.", group = "Interactions"),
        Protein("abeta24", "Amyloid-β 1–42 × 24", List(24) { AB42 },
            note = "24 peptides, 1008 residues: an aggregation run. Slow on a tablet.", group = "Interactions"),
    )

    // ---------- Custom proteins ----------
    fun customs(json: String): List<Protein> = try {
        val arr = JSONArray(json)
        (0 until arr.length()).mapNotNull { k ->
            val o = arr.getJSONObject(k)
            val chains = o.getJSONArray("chains").let { a -> (0 until a.length()).map { a.getString(it) } }
            val copies = o.optInt("copies", 1).coerceIn(1, MAX_COPIES)
            val all = List(copies) { chains }.flatten()
            val oneCopy = chains.sumOf { it.length }
            val source = o.optString("source").ifEmpty { null }
            val one = if (source != null) CaCodec.decode(o.optString("ca"), oneCopy) else null
            val ca = one?.let { c -> FloatArray(c.size * copies) { c[it % c.size] } }
            if (all.isEmpty() || all.sumOf { it.length } > MAX_RESIDUES) null
            else Protein(o.getString("id"), o.getString("name"), all,
                note = "Your protein: ${chains.size} chain${if (chains.size > 1) "s" else ""}${if (copies > 1) " × $copies copies" else ""}, ${all.sumOf { it.length }} residues.",
                group = "Yours", ca = ca, structureSource = if (ca != null) source else null,
                copyOf = IntArray(all.size) { it / chains.size })
        }
    } catch (e: Exception) { emptyList() }

    fun customJsonEntry(json: String, id: String): JSONObject? = try {
        val arr = JSONArray(json)
        (0 until arr.length()).map { arr.getJSONObject(it) }.firstOrNull { it.getString("id") == id }
    } catch (e: Exception) { null }

    fun saveCustom(json: String, id: String?, name: String, chains: List<String>, copies: Int,
                   ca: FloatArray? = null, source: String? = null): Pair<String, String> {
        val arr = try { JSONArray(json) } catch (e: Exception) { JSONArray() }
        val newId = id ?: "custom:${System.currentTimeMillis().toString(36)}"
        val obj = JSONObject().put("id", newId).put("name", name).put("chains", JSONArray(chains)).put("copies", copies)
        if (ca != null && source != null) obj.put("ca", CaCodec.encode(ca)).put("source", source)
        val out = JSONArray()
        var replaced = false
        for (k in 0 until arr.length()) {
            val o = arr.getJSONObject(k)
            if (o.getString("id") == newId) { out.put(obj); replaced = true } else out.put(o)
        }
        if (!replaced) out.put(obj)
        return out.toString() to newId
    }

    fun deleteCustom(json: String, id: String): String {
        val arr = try { JSONArray(json) } catch (e: Exception) { return "[]" }
        val out = JSONArray()
        for (k in 0 until arr.length()) if (arr.getJSONObject(k).getString("id") != id) out.put(arr.getJSONObject(k))
        return out.toString()
    }

    class Parsed(val chains: List<String>, val error: String?)

    /**
     * Reads one-letter sequences. Accepts FASTA (each ">" record is a chain), "/" between chains,
     * spaces, line breaks and digits. Returns the chains, or an error saying what to fix.
     */
    fun parse(text: String): Parsed {
        val records = ArrayList<StringBuilder>()
        var cur = StringBuilder().also { records.add(it) }
        val bad = sortedSetOf<Char>()
        for (line in text.lines()) {
            val t = line.trim()
            if (t.startsWith(">")) { if (cur.isNotEmpty()) cur = StringBuilder().also { records.add(it) }; continue }
            for (ch in t) {
                val c = ch.uppercaseChar()
                when {
                    c == '/' -> { if (cur.isNotEmpty()) cur = StringBuilder().also { records.add(it) } }
                    c.isWhitespace() || c.isDigit() || c == '*' || c == '-' -> {}
                    c in AMINO_ACIDS -> cur.append(c)
                    else -> bad.add(c)
                }
            }
        }
        val chains = records.map { it.toString() }.filter { it.isNotEmpty() }
        val error = when {
            bad.isNotEmpty() -> "Only the 20 standard one-letter codes are supported. Remove: ${bad.joinToString(" ")}"
            chains.isEmpty() -> "Type or paste a sequence, like MKTAYIAKQR…"
            chains.any { it.length < 2 } -> "Each chain needs at least 2 residues."
            else -> null
        }
        return Parsed(chains, error)
    }

    // ---------- Random proteins ----------
    // Natural amino-acid frequencies (UniProtKB/Swiss-Prot, %), in AMINO_ACIDS order
    private val FREQ = doubleArrayOf(8.3, 5.5, 4.1, 5.5, 1.4, 3.9, 6.8, 7.1, 2.3, 5.9, 9.9, 5.8, 2.4, 3.9, 4.7, 6.6, 5.3, 1.1, 2.9, 6.9)
    val RANDOM_STYLES = listOf("Natural composition", "Designed helix bundle", "Designed α/β mix")

    private fun natural(r: Random): Char {
        var u = r.nextDouble() * FREQ.sum()
        for (k in FREQ.indices) { u -= FREQ[k]; if (u <= 0) return AMINO_ACIDS[k] }
        return 'A'
    }
    private fun pick(r: Random, s: String) = s[r.nextInt(s.length)]

    fun random(length: Int, style: Int, r: Random = Random.Default): Protein {
        val len = length.coerceIn(10, MAX_RESIDUES)
        val sb = StringBuilder()
        when (style) {
            1 -> { // Amphipathic helices (heptad abcdefg: a and d buried) joined by short loops
                while (sb.length < len) {
                    val h = 14 + r.nextInt(12)
                    val start = r.nextInt(7)
                    for (k in 0 until h) {
                        when ((start + k) % 7) {
                            0, 3 -> sb.append(pick(r, "LLLIIVAMF"))
                            4, 6 -> sb.append(pick(r, "EEKKRQ"))
                            else -> sb.append(pick(r, "AEKQSRAE"))
                        }
                    }
                    sb.append(pick(r, "GNDS")).append(pick(r, "PGSD")).append(pick(r, "GSTN"))
                }
            }
            2 -> { // Alternating helices and β-hairpins
                var helixNext = r.nextBoolean()
                while (sb.length < len) {
                    if (helixNext) {
                        val h = 12 + r.nextInt(8)
                        for (k in 0 until h) sb.append(if (k % 7 == 0 || k % 7 == 3) pick(r, "LIVFM") else pick(r, "AEKQRS"))
                    } else {
                        repeat(2) { strand ->
                            val s = 5 + r.nextInt(3)
                            for (k in 0 until s) sb.append(if (k % 2 == 0) pick(r, "VIFYWT") else pick(r, "TKESRQ"))
                            if (strand == 0) sb.append("NG") else sb.append(pick(r, "GDN"))
                        }
                    }
                    sb.append(pick(r, "GSPD")).append(pick(r, "GNS"))
                    helixNext = !helixNext
                }
            }
            else -> repeat(len) { sb.append(natural(r)) }
        }
        val seq = if (sb.length > len) sb.substring(0, len) else sb.toString()
        val tag = r.nextInt(0x10000).toString(16).uppercase().padStart(4, '0')
        return Protein(RANDOM_ID, "Random ${seq.length}-mer #$tag", listOf(seq),
            note = "${RANDOM_STYLES[style.coerceIn(0, 2)]}. A new one is generated each time.", group = "Random")
    }

    fun byId(id: String, customJson: String = "[]"): Protein =
        presets.firstOrNull { it.id == id } ?: customs(customJson).firstOrNull { it.id == id } ?: presets.first { it.id == "bpti" }
}

data class Settings(
    val protein: String = "bpti",
    val temp: Int = 300,
    val ph: Float = 7f,
    val salt: Int = 150,
    val redox: Float = 0.4f,
    val speed: Float = 1f,
    val cycle: Int = 90,
    val hud: Boolean = false,
    /** 0 battery saver, 1 balanced, 2 extreme (fold as fast as possible) */
    val performance: Int = 1,
    val showProgress: Boolean = true,
    /** Folding assist ("cheat"): 0 off, 1 gentle, 2 strong, 3 maximum */
    val assist: Int = 0,
    /** Urea, M */
    val urea: Float = 0f,
    /** Force pulling the ends apart, pN */
    val pullPN: Float = 0f,
    val randomLength: Int = 120,
    val randomStyle: Int = 0,
    val randomOnWake: Boolean = true,
    val customJson: String = "[]",
    val nativeBias: Float = 1f,
    val crowding: Int = 2,
    /** 0 auto (chains when there are several), 1 chemistry, 2 chain */
    val colorBy: Int = 0,
    /** 0 beads, 1 cartoon, 2 backbone trace, 3 beads with the cartoon on top */
    val viewStyle: Int = 0,
) {
    fun save(p: SharedPreferences) {
        p.edit()
            .putString("protein", protein).putInt("temp", temp).putFloat("ph", ph).putInt("salt", salt)
            .putFloat("redox", redox).putFloat("speed", speed).putInt("cycle", cycle)
            .putBoolean("hud", hud).putInt("performance", performance).putBoolean("showProgress", showProgress)
            .putInt("assist", assist).putFloat("urea", urea).putFloat("pullPN", pullPN)
            .putInt("randomLength", randomLength).putInt("randomStyle", randomStyle).putBoolean("randomOnWake", randomOnWake)
            .putString("customJson", customJson).putFloat("nativeBias", nativeBias).putInt("crowding", crowding)
            .putInt("colorBy", colorBy).putInt("viewStyle", viewStyle)
            .apply()
    }

    companion object {
        const val PREFS = "settings"
        const val CMD_HEAT = "cmd_heat"
        const val CMD_RESET = "cmd_reset"

        fun prefs(c: Context): SharedPreferences = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        fun load(p: SharedPreferences): Settings {
            val d = Settings()
            return Settings(
                protein = p.getString("protein", d.protein) ?: d.protein,
                temp = p.getInt("temp", d.temp),
                ph = p.getFloat("ph", d.ph),
                salt = p.getInt("salt", d.salt),
                redox = p.getFloat("redox", d.redox),
                speed = p.getFloat("speed", d.speed),
                cycle = p.getInt("cycle", d.cycle),
                hud = p.getBoolean("hud", d.hud),
                // Older versions had an on/off battery saver
                performance = p.getInt("performance", if (p.getBoolean("saver", false)) 0 else d.performance),
                showProgress = p.getBoolean("showProgress", d.showProgress),
                assist = p.getInt("assist", d.assist),
                urea = p.getFloat("urea", d.urea),
                pullPN = p.getFloat("pullPN", d.pullPN),
                randomLength = p.getInt("randomLength", d.randomLength),
                randomStyle = p.getInt("randomStyle", d.randomStyle),
                randomOnWake = p.getBoolean("randomOnWake", d.randomOnWake),
                customJson = p.getString("customJson", d.customJson) ?: d.customJson,
                nativeBias = p.getFloat("nativeBias", d.nativeBias),
                crowding = p.getInt("crowding", d.crowding),
                colorBy = p.getInt("colorBy", d.colorBy),
                viewStyle = p.getInt("viewStyle", d.viewStyle),
            )
        }

        /** Ask any running simulation (the wallpaper or the preview) to heat or restart. */
        fun sendCommand(p: SharedPreferences, key: String) {
            p.edit().putLong(key, System.nanoTime()).apply()
        }
    }
}

/** Frames per second the display runs at for a performance setting. */
fun Settings.lowFrameRate() = performance != 1
