package com.hydrophobiccollapse

import kotlin.math.roundToInt

/** Colours shared by every scene (same palette as the web version). */
internal object Col {
    const val INK = 0xFF0A0F1D.toInt()
    const val INK_EDGE = 0xFF04060C.toInt()
    const val PANEL = 0xC20D1324.toInt()
    const val LINE = 0x29A0B0D6
    const val TEXT = 0xFFE4E8F3.toInt()
    const val HAZE = 0xFF8A94B0.toInt()
    const val HYDRO = 0xFFF0A44B.toInt()
    const val POLAR = 0xFF74CFC0.toInt()
    const val POS = 0xFF8EA2FF.toInt()
    const val NEG = 0xFFFF7D8E.toInt()
    const val SULFUR = 0xFFF3D34A.toInt()
    const val SPECIAL = 0xFF9AA3B8.toInt()
    const val HELIX = 0xFFC4B1FF.toInt()
    const val STRAND = 0xFF9FE3A8.toInt()
    const val BOND = 0xFF5D6784.toInt()
    // Nucleotides, as molecular biology textbooks colour them
    const val BASE_A = 0xFF51CF66.toInt()
    const val BASE_T = 0xFFFF6B6B.toInt()
    const val BASE_G = 0xFFFFD43B.toInt()
    const val BASE_C = 0xFF4DABF7.toInt()
    const val DNA = 0xFF8C9AD6.toInt()
    const val RNA = 0xFFE599F7.toInt()
}

internal fun withAlpha(c: Int, a: Float) = (c and 0xFFFFFF) or ((a.coerceIn(0f, 1f) * 255).roundToInt() shl 24)
internal fun mix(a: Int, b: Int, t: Float): Int {
    val r = ((a shr 16 and 255) + ((b shr 16 and 255) - (a shr 16 and 255)) * t).roundToInt()
    val g = ((a shr 8 and 255) + ((b shr 8 and 255) - (a shr 8 and 255)) * t).roundToInt()
    val bl = ((a and 255) + ((b and 255) - (a and 255)) * t).roundToInt()
    return (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
}
internal fun baseColor(b: Char) = when (b) { 'A' -> Col.BASE_A; 'T', 'U' -> Col.BASE_T; 'G' -> Col.BASE_G; 'C' -> Col.BASE_C; else -> Col.HAZE }
internal const val HYDROPHOBIC = "AVLIMFW"
/** An amino acid's colour by its chemistry at neutral pH. */
internal fun aminoColor(aa: Char) = when {
    aa == 'C' -> Col.SULFUR
    aa == 'K' || aa == 'R' -> Col.POS
    aa == 'D' || aa == 'E' -> Col.NEG
    aa in HYDROPHOBIC -> Col.HYDRO
    aa == 'G' || aa == 'P' -> Col.SPECIAL
    else -> Col.POLAR
}
