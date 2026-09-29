package com.hydrophobiccollapse

import org.junit.Test
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

// Survey: which presets fold, and how fast (seconds at 1x = 2000 steps). Skipped unless SURVEY is set, e.g.
// SURVEY=ubq,tim SEEDS=1,2 SECONDS=120 VARIANTS=base,gentle,ribo,riboslow ./gradlew :core:test --tests '*FoldSurvey*' -i
class FoldSurvey {
    @Test fun survey() {
        val ids = (System.getenv("SURVEY") ?: return).split(',')
        val seeds = (System.getenv("SEEDS") ?: "101,202").split(',').map { it.toLong() }
        val variants = (System.getenv("VARIANTS") ?: "base").split(',')
        val seconds = (System.getenv("SECONDS") ?: "120").toInt()
        val pool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())
        val jobs = ids.flatMap { id -> seeds.flatMap { seed -> variants.map { v -> Triple(id, seed, v) } } }.map { (id, seed, v) ->
            pool.submit<String> {
                val e = ProteinEngine(); e.seed(seed)
                if (v == "ribo" || v == "riboslow") { e.ribosome = true; e.ribosomeSpeed = if (v == "ribo") 1 else 0 }
                e.load(Proteins.byId(id)); e.temperature = 300.0
                if (v == "gentle") e.assist = 1
                // One fixed bar for every variant (95 % means Q ≥ 0.86 and RMSD ≤ 2.9 Å), so starts can be compared
                val q0 = 0.0; val r0 = 20.0
                var t95 = -1; var best = 0.0; var last = 0.0
                val t0 = System.nanoTime()
                for (s in 1..seconds) {
                    e.step(2000); e.measure()
                    val pq = ((e.q - q0) / (0.9 - q0)).coerceIn(0.0, 1.0)
                    val pr = if (e.rmsd.isNaN()) pq else ((r0 - e.rmsd) / (r0 - 2.0)).coerceIn(0.0, 1.0)
                    last = min(pq, pr); best = max(best, last)
                    if (t95 < 0 && last >= 0.95) { t95 = s; break }
                }
                val ms = (System.nanoTime() - t0) / 1e6 / (if (t95 > 0) t95 else seconds) / 2000
                "SURVEY %-11s %-10s n=%4d seed=%d  folded95=%4s  best=%3.0f%%  last=%3.0f%%  Q=%.2f  RMSD=%.1f  %.3f ms/step".format(
                    id, v, e.n, seed, if (t95 > 0) "${t95}s" else "no", best * 100, last * 100, e.q, e.rmsd, ms)
            }
        }
        jobs.forEach { println(it.get()) }
        pool.shutdown()
    }
}
