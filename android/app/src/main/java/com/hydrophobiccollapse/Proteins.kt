package com.hydrophobiccollapse

import android.content.Context
import android.content.SharedPreferences

class Protein(val id: String, val name: String, val seq: String, val native: List<IntArray> = emptyList(), val note: String)

object Proteins {
    val all = listOf(
        Protein("bpti", "BPTI", "RPDFCLEPPYTGPCKARIIRYFYNAKAGLCQTFVYGGCRAKRNNFKSAEDCMRTCGGA",
            listOf(intArrayOf(5, 55), intArrayOf(14, 38), intArrayOf(30, 51)),
            "Bovine pancreatic trypsin inhibitor. Six cysteines; the native fold pairs 5–55, 14–38 and 30–51."),
        Protein("villin", "Villin headpiece HP35", "LSDEDFKAVFGMTRSAFANLPLWKQQNLKKEKGLF",
            note = "A fast-folding three-helix bundle, a favourite of folding simulations."),
        Protein("trpcage", "Trp-cage TC5b", "NLYIQWLKDGGPSSGRPPPS",
            note = "A 20-residue designed miniprotein: a short helix packed around a tryptophan."),
        Protein("gb1", "GB1 hairpin", "GEWTYDDATKTFTVTE",
            note = "Residues 41–56 of protein G: a β-hairpin that folds on its own."),
        Protein("abeta", "Amyloid-β 1–42", "DAEFRHDSGYEVHHQKLVFFAEDVGSNKGAIIGLMVGGVVIA",
            note = "The Alzheimer’s peptide. Mostly disordered, with a very hydrophobic C-terminal tail."),
        Protein("ubq", "Ubiquitin", "MQIFVKTLTGKTITLEVEPSDTIENVKAKIQDKEGIPPDQQRLIFAGKQLEDGRTLSDYNIQKESTLHLVLRLRGG",
            note = "76 residues, mixed helix and sheet. The heaviest to simulate here."),
    )
    fun byId(id: String) = all.firstOrNull { it.id == id } ?: all[0]
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
    val saver: Boolean = false,
) {
    fun save(p: SharedPreferences) {
        p.edit()
            .putString("protein", protein).putInt("temp", temp).putFloat("ph", ph).putInt("salt", salt)
            .putFloat("redox", redox).putFloat("speed", speed).putInt("cycle", cycle)
            .putBoolean("hud", hud).putBoolean("saver", saver)
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
                saver = p.getBoolean("saver", d.saver),
            )
        }

        /** Ask any running simulation (the wallpaper or the preview) to heat or restart. */
        fun sendCommand(p: SharedPreferences, key: String) {
            p.edit().putLong(key, System.nanoTime()).apply()
        }
    }
}
