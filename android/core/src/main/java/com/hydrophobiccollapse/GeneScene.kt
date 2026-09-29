package com.hydrophobiccollapse

import com.hydrophobiccollapse.gfx.Canvas
import com.hydrophobiccollapse.gfx.Paint
import com.hydrophobiccollapse.gfx.RadialGradient
import com.hydrophobiccollapse.gfx.Shader
import com.hydrophobiccollapse.gfx.Typeface
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

private const val REAL_NT_PER_S = 45.0      // bacterial RNA polymerase copies 40–80 nt/s; human Pol II 20–70
private const val MAX_TRANSCRIBE_S = 24.0   // longer genes are shown faster than real, to keep the wait short
private const val BIND_S = 2.4
private const val RELEASE_S = 2.0
private const val HANDOFF_S = 1.8
private const val BUBBLE = 13               // bp held open by the polymerase
private const val HYBRID = 8                // nt of new RNA still paired with the template
private const val BP_PER_TURN = 10.5

/**
 * The central dogma, gene to protein, as a textbook-style side view drawn in screen space: an animation driven by
 * real sequences and rates, not a physics simulation. Transcription plays here, gene by gene (identical chains
 * share one): RNA polymerase binds the promoter, opens a bubble, copies the template strand into mRNA at a real
 * pace and lets go at the terminator. Translation is then shown by [drawTranslation], a strip under the 3D
 * ribosome of the folding scene: codons pass through the E, P and A sites while tRNAs bring amino acids.
 * Promoters, untranslated ends and terminators are typical ones, not each gene's own; the coding part is real
 * when [GeneChain.real].
 */
class GeneScene(val gene: Gene, private val density: Float) {
    /** One gene as transcribed: its coding strand with a promoter in front and a terminator behind. */
    private class Unit(val chain: GeneChain, seed: Int) {
        val bacterial = chain.host == 'B'
        val dna: String
        val tss: Int            // index of +1, the first transcribed base
        val codingStart: Int
        val codingEnd: Int
        val tEnd: Int           // one past the last transcribed base
        init {
            val rnd = java.util.Random(seed.toLong())
            fun filler(n: Int) = String(CharArray(n) { "ACGTTGCA"[rnd.nextInt(8)] })
            val up = StringBuilder(filler(80))
            // Promoter boxes at their usual distances from +1
            if (bacterial) { up.replace(80 - 35, 80 - 29, "TTGACA"); up.replace(80 - 12, 80 - 6, "TATAAT") }
            else up.replace(80 - 31, 80 - 25, "TATAAA")
            // 5′ untranslated leader ending just before the start codon: Shine–Dalgarno or Kozak
            val leader = if (bacterial) "ACAAGGAGGTATAACC" else "GTCAGATCGCCTGGAGCCACC"
            // 3′ end: a GC hairpin and U-run (bacterial terminator) or the AAUAAA poly(A) signal
            val trailer = if (bacterial) "AAAAGCCCGCTCATTAGGCGGGCTTTTTTT" else "GCTCAATAAAGCTTGCCTTGAG"
            tss = up.length
            codingStart = tss + leader.length
            codingEnd = codingStart + chain.coding.length
            tEnd = codingEnd + trailer.length
            dna = up.toString() + leader + chain.coding + trailer + filler(60)
        }
        val length get() = tEnd - tss
        fun label(i: Int) = chain.record?.gene ?: "gene ${i + 1}"
    }

    private val units = gene.distinct.mapIndexed { i, c -> Unit(c, c.coding.hashCode() + i) }
    private val rate = max(REAL_NT_PER_S, units.sumOf { it.length } / MAX_TRANSCRIBE_S)

    private enum class Phase { BIND, TRANSCRIBE, RELEASE, HANDOFF }
    private var unit = 0
    private var phase = Phase.BIND
    private var t = 0.0
    private var clock = 0.0
    /** True once transcription is over and translation can start. */
    var done = false; private set
    private val u get() = units[unit]
    /** Nucleotides made so far in the current gene. */
    private val transcribed get() = when (phase) {
        Phase.BIND -> 0.0
        Phase.TRANSCRIBE -> min(t * rate, u.length.toDouble())
        else -> u.length.toDouble()
    }

    fun update(dt: Double) {
        if (done) return
        clock += dt; t += dt
        when (phase) {
            Phase.BIND -> if (t >= BIND_S) { phase = Phase.TRANSCRIBE; t = 0.0 }
            Phase.TRANSCRIBE -> if (t * rate >= u.length) { phase = Phase.RELEASE; t = 0.0 }
            Phase.RELEASE -> if (t >= RELEASE_S) {
                t = 0.0
                if (unit + 1 < units.size) { unit++; phase = Phase.BIND } else phase = Phase.HANDOFF
            }
            Phase.HANDOFF -> if (t >= HANDOFF_S) done = true
        }
    }
    /** Jump to the end (a tap skips the intro). */
    fun skip() { done = true }

    // ---------- Drawing ----------
    private fun dp(v: Float) = v * density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val mono = Typeface.MONOSPACE
    private val sans = Typeface.create("sans-serif", Typeface.NORMAL)
    private val sansBold = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    /** Draws the transcription scene over an already painted background. */
    fun draw(c: Canvas, w: Float, h: Float, topInset: Float, bottomInset: Float) {
        val fade = if (phase == Phase.HANDOFF) (1 - t / HANDOFF_S).toFloat().coerceIn(0f, 1f) else 1f
        val sp = min(dp(15f), max(dp(9f), w / 105f))           // screen distance per base pair
        val amp = dp(17f)
        val cy = topInset + (h - topInset - bottomInset) * 0.6f
        val gap = dp(30f)                                      // how far the bubble pulls the strands apart
        val polPos = when (phase) {
            Phase.BIND -> u.tss.toDouble()
            Phase.TRANSCRIBE -> u.tss + transcribed
            else -> u.tEnd.toDouble()
        }
        val anchor = w * 0.6f
        fun gx(g: Double) = (anchor + (g - polPos) * sp).toFloat()
        // How open the bubble is: opens as the polymerase settles, closes as it leaves
        val open = when (phase) {
            Phase.BIND -> ((t - BIND_S * 0.55) / (BIND_S * 0.45)).coerceIn(0.0, 1.0)
            Phase.TRANSCRIBE -> 1.0
            Phase.RELEASE -> (1 - t / (RELEASE_S * 0.5)).coerceIn(0.0, 1.0)
            Phase.HANDOFF -> 0.0
        }
        val bubbleC = polPos - 4
        fun bubble(g: Double): Double {
            val d = (g - bubbleC) / (BUBBLE / 2.0)
            return if (abs(d) >= 1) 0.0 else open * (1 - d * d).let { it * it }
        }
        fun theta(g: Double) = 2 * PI * g / BP_PER_TURN
        fun topY(g: Double): Float { val f = bubble(g); return (cy + amp * sin(theta(g)) * (1 - f) - gap * f).toFloat() }
        fun botY(g: Double): Float { val f = bubble(g); return (cy + amp * sin(theta(g) + 2.4) * (1 - f) + gap * f).toFloat() }

        val g0 = max(0, floor(polPos - anchor / sp).toInt() - 1)
        val g1 = min(u.dna.length - 1, (polPos + (w - anchor) / sp).toInt() + 1)

        // Backbones behind the rungs
        fun strand(front: Boolean) {
            for (g in g0 until g1) {
                for (s in 0..1) {
                    val th = theta(g + 0.5) + (if (s == 1) 2.4 else 0.0)
                    val z = cos(th) * (1 - bubble(g + 0.5))
                    if ((z > 0) != front) continue
                    val y0 = if (s == 0) topY(g.toDouble()) else botY(g.toDouble())
                    val y1 = if (s == 0) topY(g + 1.0) else botY(g + 1.0)
                    stroke.color = withAlpha(Col.DNA, fade * (if (front) 0.95f else 0.4f))
                    stroke.strokeWidth = if (front) dp(3.2f) else dp(2f)
                    c.drawLine(gx(g.toDouble()), y0, gx(g + 1.0), y1, stroke)
                }
            }
        }
        strand(front = false)
        // Base pairs; inside the bubble the bases stick out unpaired
        for (g in g0..g1) {
            val x = gx(g.toDouble()); val f = bubble(g.toDouble())
            val top = u.dna[g]; val bot = GeneticCode.complement(top)
            val yt = topY(g.toDouble()); val yb = botY(g.toDouble())
            val depth = (0.55 + 0.45 * cos(theta(g.toDouble()) + 1.2)).toFloat()
            stroke.strokeWidth = dp(2.4f)
            if (f < 0.35) {
                val mid = (yt + yb) / 2
                stroke.color = withAlpha(baseColor(top), fade * depth); c.drawLine(x, yt, x, mid, stroke)
                stroke.color = withAlpha(baseColor(bot), fade * depth); c.drawLine(x, mid, x, yb, stroke)
            } else {
                val stub = dp(9f)
                stroke.color = withAlpha(baseColor(top), fade); c.drawLine(x, yt, x, yt + stub, stroke)
                stroke.color = withAlpha(baseColor(bot), fade); c.drawLine(x, yb, x, yb - stub, stroke)
            }
        }
        strand(front = true)

        // Letters of both strands, close to the polymerase
        if (sp >= dp(9.5f)) {
            text.typeface = mono; text.textSize = min(dp(10.5f), sp * 0.95f); text.textAlign = Paint.Align.CENTER
            val ly0 = cy - amp - gap - dp(14f); val ly1 = cy + amp + gap + dp(22f)
            for (g in g0..g1) {
                val x = gx(g.toDouble())
                val a = fade * (1 - (abs(x - anchor) / (w * 0.75f))).coerceIn(0.25f, 1f)
                text.color = withAlpha(baseColor(u.dna[g]), a); c.drawText(u.dna[g].toString(), x, ly0, text)
                val b = GeneticCode.complement(u.dna[g])
                text.color = withAlpha(baseColor(b), a * 0.8f); c.drawText(b.toString(), x, ly1, text)
            }
            text.textAlign = Paint.Align.LEFT; text.typeface = sans; text.textSize = dp(10f); text.color = withAlpha(Col.HAZE, fade)
            c.drawText("coding strand 5′→3′", dp(16f), ly0 - dp(14f), text)
            c.drawText("template strand 3′→5′", dp(16f), ly1 + dp(16f), text)
        }

        // Landmarks along the gene
        landmarks(c, w, cy + amp + gap + dp(44f), ::gx, fade)

        // RNA polymerase: a translucent body around the bubble, so the copying shows through
        val polX = when (phase) {
            Phase.BIND -> gx(u.tss - 4.0) - ((1 - (t / (BIND_S * 0.6)).coerceIn(0.0, 1.0)).let { it * it } * (w * 0.7)).toFloat()
            else -> gx(polPos - 4)
        }
        val lift = when (phase) { Phase.RELEASE -> (t / RELEASE_S).toFloat(); Phase.HANDOFF -> 1f; else -> 0f }
        val polA = fade * (1 - 0.85f * lift)
        val bw = max(sp * 22, dp(170f)); val bh = dp(118f)
        val by = cy - dp(8f) - lift * dp(70f)
        glow.shader = RadialGradient(polX - bw * 0.2f, by - bh * 0.25f, bw * 0.75f,
            intArrayOf(withAlpha(0xFF7C8CD8.toInt(), 0.42f * polA), withAlpha(0xFF3C4880.toInt(), 0.3f * polA), withAlpha(0xFF1A2040.toInt(), 0.18f * polA)),
            floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
        c.drawRoundRect(polX - bw / 2, by - bh / 2, polX + bw / 2, by + bh / 2, bh / 2, bh / 2, glow)
        stroke.color = withAlpha(0xFF9AA8E8.toInt(), 0.55f * polA); stroke.strokeWidth = dp(1.3f)
        c.drawRoundRect(polX - bw / 2, by - bh / 2, polX + bw / 2, by + bh / 2, bh / 2, bh / 2, stroke)
        text.typeface = sansBold; text.textSize = dp(12f); text.textAlign = Paint.Align.CENTER; text.color = withAlpha(Col.TEXT, polA)
        c.drawText("RNA polymerase", polX, by - bh / 2 - dp(8f), text)
        text.textAlign = Paint.Align.LEFT

        // The new RNA: paired with the template inside the bubble, then peeling away up and to the left
        drawRna(c, w, topInset, ::gx, { g -> botY(g) }, polPos, sp, cy - gap, lift, fade)

        panel(c, w, topInset, fade)
    }

    private val rx = FloatArray(4096); private val ry = FloatArray(4096)
    private fun drawRna(c: Canvas, w: Float, topInset: Float, gx: (Double) -> Float, botY: (Double) -> Float,
                        polPos: Double, sp: Float, exitY: Float, lift: Float, fade: Float) {
        val m = floor(transcribed).toInt().coerceAtMost(rx.size)
        if (m <= 0) return
        val drift = lift * dp(110f)
        val hybrid = if (phase == Phase.TRANSCRIBE) min(HYBRID, m) else 0
        // Hybrid part, newest last
        for (k in m - hybrid until m) {
            val g = u.tss + k + 0.0
            rx[k] = gx(g); ry[k] = botY(g) - dp(11f)
        }
        // The rest leaves through the exit channel and trails away, oldest furthest
        var x = if (hybrid > 0) rx[m - hybrid] else gx(polPos - 2)
        var y = if (hybrid > 0) ry[m - hybrid] - dp(8f) else exitY - dp(6f)
        var heading = -PI * 0.62
        val step = sp * 0.72f
        for (k in m - hybrid - 1 downTo 0) {
            val d = m - hybrid - 1 - k
            heading += (-PI - heading) * 0.07 + 0.05 * sin(clock * 1.3 + d * 0.23)
            if (y < topInset + dp(150f)) heading += (-PI - heading) * 0.25
            x += (step * cos(heading)).toFloat(); y += (step * sin(heading)).toFloat()
            rx[k] = x; ry[k] = y
        }
        val start = max(0, m - 700)
        stroke.color = withAlpha(Col.RNA, 0.8f * fade); stroke.strokeWidth = dp(2.2f)
        for (k in start until m - 1) {
            if (rx[k] < -dp(20f) && rx[k + 1] < -dp(20f)) continue
            c.drawLine(rx[k], ry[k] - drift, rx[k + 1], ry[k + 1] - drift, stroke)
        }
        // Bases as coloured dots, letters for the newest; the hybrid pairs with the template below
        text.typeface = mono; text.textSize = dp(9.5f); text.textAlign = Paint.Align.CENTER
        for (k in start until m) {
            val xx = rx[k]; val yy = ry[k] - drift
            if (xx < -dp(10f) || xx > w + dp(10f)) continue
            val b = GeneticCode.rna(u.dna[u.tss + k].toString())[0]
            fill.color = withAlpha(baseColor(b), fade); c.drawCircle(xx, yy, dp(3f), fill)
            if (k >= m - 48) { text.color = withAlpha(baseColor(b), fade * 0.95f); c.drawText(b.toString(), xx, yy - dp(6f), text) }
            if (k >= m - hybrid) {
                stroke.color = withAlpha(baseColor(b), 0.6f * fade); stroke.strokeWidth = dp(1.4f)
                c.drawLine(xx, yy + dp(3f), xx, yy + dp(9f), stroke)
            }
        }
        // Marks on the RNA: the 5′ end, the start codon and the stop codon once made
        text.typeface = sans; text.textSize = dp(10f)
        text.color = withAlpha(Col.TEXT, fade)
        if (rx[0] > dp(4f)) c.drawText(if (u.bacterial) "5′" else "5′ cap", rx[0], ry[0] - drift - dp(18f), text)
        val sStart = u.codingStart - u.tss; val sStop = u.codingEnd - 3 - u.tss
        if (m > sStart + 2 && rx[sStart] > dp(10f)) { text.color = withAlpha(Col.STRAND, fade); c.drawText("AUG start", rx[sStart + 1], ry[sStart + 1] - drift - dp(18f), text) }
        if (m > sStop + 2 && rx[sStop] > dp(10f)) { text.color = withAlpha(Col.NEG, fade); c.drawText("stop", rx[sStop + 1], ry[sStop + 1] - drift - dp(18f), text) }
        text.textAlign = Paint.Align.LEFT
    }

    private fun landmarks(c: Canvas, w: Float, y: Float, gx: (Double) -> Float, fade: Float) {
        text.typeface = sans; text.textSize = dp(10f)
        fun span(a: Int, b: Int, label: String, col: Int) {
            val x0 = gx(a.toDouble()); val x1 = gx(b.toDouble())
            if (x1 < 0 || x0 > w) return
            stroke.color = withAlpha(col, 0.8f * fade); stroke.strokeWidth = dp(1.5f)
            c.drawLine(x0, y, x1, y, stroke); c.drawLine(x0, y - dp(4f), x0, y + dp(4f), stroke); c.drawLine(x1, y - dp(4f), x1, y + dp(4f), stroke)
            text.color = withAlpha(col, fade); text.textAlign = Paint.Align.CENTER
            c.drawText(label, (max(x0, dp(60f)) + min(x1, w - dp(60f))) / 2, y + dp(16f), text)
        }
        val s = u.tss
        if (u.bacterial) { span(s - 35, s - 29, "−35", Col.HYDRO); span(s - 12, s - 6, "−10 box", Col.HYDRO) }
        else span(s - 31, s - 25, "TATA box", Col.HYDRO)
        span(s, s + 1, "+1", Col.TEXT)
        val codons = u.chain.codons.size
        span(u.codingStart, u.codingEnd, "${u.label(unit)} coding sequence · $codons codons", Col.RNA)
        span(u.codingEnd, u.tEnd, if (u.bacterial) "terminator" else "poly(A) signal", Col.NEG)
        text.textAlign = Paint.Align.LEFT
    }

    private fun panel(c: Canvas, w: Float, topInset: Float, fade: Float) {
        val pw = min(dp(470f), w - dp(32f)); val x0 = dp(16f); val y0 = topInset + dp(16f)
        val lines = ArrayList<Pair<String, Int>>()
        lines += u.chain.sourceText to Col.HAZE
        if (units.size > 1) lines += "Gene ${unit + 1} of ${units.size}" to Col.HAZE
        lines += when (phase) {
            Phase.BIND -> (if (u.bacterial) "It recognises the −35 and −10 boxes of the promoter" else "It is recruited to the TATA box by transcription factors") to Col.TEXT
            Phase.TRANSCRIBE -> "It reads the template strand, adding the RNA letter that pairs with each base" to Col.TEXT
            Phase.RELEASE -> (if (u.bacterial) "The terminator hairpin makes it let go" else "The RNA is cut after the poly(A) signal and gets a poly(A) tail") to Col.TEXT
            Phase.HANDOFF -> "The mRNA goes to a ribosome, which reads it three letters at a time" to Col.TEXT
        }
        val speed = if (rate > REAL_NT_PER_S * 1.01) "shown ${"%.1f".format(rate / REAL_NT_PER_S)}× real speed" else "real speed"
        lines += "${transcribed.toInt()} of ${u.length} nt · ${rate.roundToInt()} nt/s ($speed)" to Col.HAZE
        if (!u.bacterial) lines += "Genes of animals and yeast are also spliced (introns removed); not shown yet" to Col.HAZE
        if (phase == Phase.BIND || phase == Phase.TRANSCRIBE) lines += "Tap to skip ahead to translation" to Col.HAZE
        val lh = dp(16f)
        val ph = dp(38f) + lines.size * lh + dp(26f)
        fill.color = withAlpha(Col.PANEL, (Col.PANEL ushr 24) / 255f * fade); c.drawRoundRect(x0, y0, x0 + pw, y0 + ph, dp(10f), dp(10f), fill)
        stroke.color = withAlpha(Col.LINE, 0.16f * fade); stroke.strokeWidth = density; c.drawRoundRect(x0, y0, x0 + pw, y0 + ph, dp(10f), dp(10f), stroke)
        text.typeface = sansBold; text.textSize = dp(15f); text.color = withAlpha(Col.TEXT, fade); text.textAlign = Paint.Align.LEFT
        c.drawText(when (phase) {
            Phase.BIND -> "Transcription · RNA polymerase binds"
            Phase.TRANSCRIBE -> "Transcription · DNA → mRNA"
            Phase.RELEASE -> "Transcription done · mRNA released"
            Phase.HANDOFF -> "mRNA → ribosome"
        }, x0 + dp(14f), y0 + dp(26f), text)
        text.typeface = sans; text.textSize = dp(11.5f)
        var y = y0 + dp(38f)
        for ((s, col) in lines) {
            text.color = withAlpha(col, fade); c.drawText(ellipsize(s, pw - dp(28f)), x0 + dp(14f), y + dp(11f), text); y += lh
        }
        // Base colour key
        var kx = x0 + dp(14f); val ky = y + dp(12f)
        for ((b, name) in listOf('A' to "A", 'T' to "T / U", 'G' to "G", 'C' to "C")) {
            fill.color = withAlpha(baseColor(b), fade); c.drawCircle(kx + dp(4f), ky - dp(4f), dp(4f), fill)
            text.color = withAlpha(Col.HAZE, fade); c.drawText(name, kx + dp(12f), ky, text)
            kx += dp(12f) + text.measureText(name) + dp(16f)
        }
    }

    private fun ellipsize(s: String, maxW: Float): String {
        if (text.measureText(s) <= maxW) return s
        val keep = text.breakText(s, true, maxW - text.measureText("…"), null)
        return s.take(keep) + "…"
    }

    // ---------- Translation strip, under the 3D ribosome ----------
    /** Height of the translation strip. */
    val stripHeight get() = dp(160f)
    /**
     * The ribosome's view of the mRNA while the 3D chain grows: codons slide through the E, P and A sites, a tRNA
     * whose anticodon pairs with the A-site codon brings the next amino acid, the P-site tRNA holds the growing
     * chain, and the empty tRNA leaves from E. [residue] is the residue being added in chain [chain] (its length
     * or more once the stop codon is reached), [progress] how far that step is (0…1).
     */
    fun drawTranslation(c: Canvas, w: Float, y0: Float, chain: Int, residue: Int, progress: Double, alpha: Float) {
        val gc = gene.chains.getOrNull(chain) ?: return
        val a = alpha.coerceIn(0f, 1f)
        if (a <= 0.01f) return
        val codons = gc.mrnaCodons
        val offset = gc.codonOffset
        val stopIdx = codons.size - 1
        val terminating = residue >= gc.protein.length
        val aIdx = if (terminating) stopIdx else residue + offset
        val prog = if (terminating) 0.0 else progress.coerceIn(0.0, 1.0)
        val pw = min(dp(580f), w - dp(32f)); val ph = stripHeight
        val x0 = (w - pw) / 2; val x1 = x0 + pw
        fill.color = withAlpha(Col.PANEL, (Col.PANEL ushr 24) / 255f * a); c.drawRoundRect(x0, y0, x1, y0 + ph, dp(10f), dp(10f), fill)
        stroke.color = withAlpha(Col.LINE, 0.16f * a); stroke.strokeWidth = density; c.drawRoundRect(x0, y0, x1, y0 + ph, dp(10f), dp(10f), stroke)

        // Headline: what the ribosome is reading, and why it is slow if it is
        val codonRna = GeneticCode.rna(codons[aIdx])
        val usage = GeneticCode.usage(gc.host)
        val rare = !terminating && GeneticCode.rawSlowness(codons[aIdx], usage) >= 2.2
        text.textAlign = Paint.Align.LEFT; text.typeface = sansBold; text.textSize = dp(13f)
        text.color = withAlpha(if (rare) Col.HYDRO else Col.TEXT, a)
        val head = when {
            terminating -> "Stop codon $codonRna: a release factor frees the finished chain"
            rare -> "Rare codon $codonRna → ${name3(gc.protein[residue])}: few matching tRNAs, so the ribosome waits"
            else -> "Codon $codonRna → ${name3(gc.protein[residue])} · tRNA anticodon ${GeneticCode.anticodon(codonRna)}"
        }
        c.drawText(ellipsize(head, pw - dp(28f)), x0 + dp(14f), y0 + dp(21f), text)
        text.typeface = sans; text.textSize = dp(10.5f); text.color = withAlpha(Col.HAZE, a); text.textAlign = Paint.Align.RIGHT
        val pos = "residue ${min(residue, gc.protein.length)} of ${gc.protein.length}${if (gene.chains.size > 1) " · chain ${'A' + chain}" else ""}"
        c.drawText(pos, x1 - dp(14f), y0 + ph - dp(10f), text)
        text.textAlign = Paint.Align.LEFT
        c.drawText(ellipsize(gc.sourceText, pw * 0.62f), x0 + dp(14f), y0 + ph - dp(10f), text)

        val cw = dp(46f); val my = y0 + dp(124f)
        val aX = x0 + pw * 0.64f
        fun codonX(j: Int) = (aX + (j - aIdx - prog) * cw).toFloat()
        // Ribosome: large subunit above the mRNA (holding the tRNAs), small one below
        val rl = aX - 2.55f * cw; val rr = aX + 0.55f * cw
        fill.color = withAlpha(0xFF4E5A8E.toInt(), 0.32f * a); c.drawRoundRect(rl, my - dp(70f), rr, my - dp(2f), dp(16f), dp(16f), fill)
        fill.color = withAlpha(0xFF6A5A8C.toInt(), 0.4f * a); c.drawRoundRect(rl + dp(6f), my + dp(4f), rr - dp(6f), my + dp(20f), dp(8f), dp(8f), fill)
        text.typeface = sans; text.textSize = dp(9f); text.textAlign = Paint.Align.CENTER; text.color = withAlpha(Col.HAZE, a)
        for ((k, site) in listOf("E", "P", "A").withIndex()) c.drawText(site, aX - (2 - k) * cw, my + dp(16f), text)
        // mRNA with codons
        stroke.color = withAlpha(Col.RNA, 0.7f * a); stroke.strokeWidth = dp(2f)
        c.drawLine(x0 + dp(10f), my - dp(12f), x1 - dp(10f), my - dp(12f), stroke)
        text.typeface = mono; text.textSize = dp(12f)
        for (j in max(0, aIdx - 8)..min(stopIdx, aIdx + 6)) {
            val cx = codonX(j)
            val edge = min(cx - x0, x1 - cx) / cw
            if (edge < 0.55f) continue
            val ea = a * (edge - 0.55f).coerceIn(0f, 1f).let { if (edge > 1.55f) 1f else it }
            val s = GeneticCode.rna(codons[j])
            for (k in 0..2) {
                text.color = withAlpha(baseColor(s[k]), ea)
                c.drawText(s[k].toString(), cx + (k - 1) * dp(11f), my - dp(1f), text)
            }
            if (j == 0 && offset == 1) { text.textSize = dp(8.5f); text.color = withAlpha(Col.STRAND, ea); c.drawText("start", cx, my + dp(30f), text); text.textSize = dp(12f) }
            if (j == stopIdx) { text.textSize = dp(8.5f); text.color = withAlpha(Col.NEG, ea); c.drawText("stop", cx, my + dp(30f), text); text.textSize = dp(12f) }
        }
        // tRNAs: A site arriving, P site holding the chain, E site leaving
        fun trna(j: Int, x: Float, ta: Float, rise: Float, aa: Char?, releaseFactor: Boolean = false) {
            if (ta <= 0.01f) return
            val top = my - dp(64f) - rise; val bottom = my - dp(26f) - rise
            val body = if (releaseFactor) Col.SPECIAL else 0xFFB197FC.toInt()
            fill.color = withAlpha(body, 0.85f * ta)
            c.drawRoundRect(x - dp(15f), bottom - dp(20f), x + dp(15f), bottom, dp(6f), dp(6f), fill)
            c.drawRoundRect(x - dp(4f), top + dp(6f), x + dp(4f), bottom - dp(18f), dp(3f), dp(3f), fill)
            text.typeface = mono; text.textSize = dp(10.5f)
            if (releaseFactor) { text.color = withAlpha(Col.INK, ta); c.drawText("RF", x, bottom - dp(6f), text) }
            else {
                val anti = GeneticCode.anticodon(GeneticCode.rna(codons[j]))
                for (k in 0..2) { text.color = withAlpha(Col.INK, ta); c.drawText(anti[k].toString(), x + (k - 1) * dp(9f), bottom - dp(6f), text) }
            }
            if (aa != null) {
                fill.color = withAlpha(aminoColor(aa), ta); c.drawCircle(x, top, dp(8f), fill)
                text.color = withAlpha(Col.INK, ta); text.textSize = dp(9.5f); c.drawText(aa.toString(), x, top + dp(3.5f), text)
            }
        }
        fun aaAt(j: Int): Char? { val r = j - offset; return if (r in 0 until gc.protein.length) gc.protein[r] else if (j == 0 && offset == 1) 'M' else null }
        if (terminating) {
            trna(aIdx, codonX(aIdx), a, 0f, null, releaseFactor = true)
        } else {
            val arrive = (prog / 0.35).coerceIn(0.0, 1.0).toFloat()
            trna(aIdx, codonX(aIdx), a * arrive, (1 - arrive) * dp(30f), aaAt(aIdx))
        }
        val pIdx = aIdx - 1
        if (pIdx >= 0) trna(pIdx, codonX(pIdx), a, 0f, if (terminating || prog < 0.6) aaAt(pIdx) else null)
        val eIdx = aIdx - 2
        if (eIdx >= 0 && !terminating) trna(eIdx, codonX(eIdx), a * (1 - (prog * 2).toFloat()).coerceIn(0f, 1f), (prog * dp(40f)).toFloat(), null)
        // The growing chain, newest residue at the P site (or already on the A-site amino acid after transfer)
        val newest = if (!terminating && prog >= 0.6) aIdx else pIdx
        var bx = codonX(newest); var by = my - dp(64f)
        text.typeface = mono; text.textSize = dp(8.5f)
        for (k in 0 until 9) {
            val r = newest - offset - k
            if (r < 0) break
            if (k > 0) {
                // Up out of the ribosome first (as through its exit tunnel), then away to the left
                if (k <= 2) { bx -= dp(5f); by -= dp(11f) } else bx -= dp(14f)
                if (bx < x0 + dp(12f)) break
                fill.color = withAlpha(aminoColor(gc.protein[r]), a * (1 - k / 11f)); c.drawCircle(bx, by, dp(6f), fill)
                text.color = withAlpha(Col.INK, a); c.drawText(gc.protein[r].toString(), bx, by + dp(3f), text)
            }
        }
        text.textAlign = Paint.Align.LEFT
    }

    companion object {
        private const val NAMES = "AlaArgAsnAspCysGlnGluGlyHisIleLeuLysMetPheProSerThrTrpTyrVal"
        fun name3(aa: Char): String { val i = AMINO_ACIDS.indexOf(aa); return if (i < 0) "?" else NAMES.substring(3 * i, 3 * i + 3) }
    }
}
