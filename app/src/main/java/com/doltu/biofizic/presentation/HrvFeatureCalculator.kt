package com.doltu.biofizic.presentation

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Feature-uri HRV din intervale IBI (ms) cu timestamp.
 * [windowSec] = sumă IBI valide / 1000 → durată cardiacă acoperită de fereastră.
 */
object HrvFeatureCalculator {

    data class Features(
        val rmssd: Double,
        val sdnn: Double,
        val meanIbiMs: Double,
        val meanHrBpm: Double,
        val pnn50: Double,
        val ibiCount: Int,
        val windowSec: Double,
    )

    private const val MIN_IBI_MS = 300
    private const val MAX_IBI_MS = 2_000
    /** Pereche (a,b) acceptată dacă |Δt_ts − IBI_b| < prag (evită gap-uri artefact). */
    private const val MAX_IBI_TS_MISMATCH_MS = 250L

    fun compute(ibiEntries: List<IbiWindowEntry>): Features? {
        val physiological = ibiEntries.filter { it.ibiMs in MIN_IBI_MS..MAX_IBI_MS }
        if (physiological.size < 2) return null

        // Filtru median Oura-style: respinge IBI care se abate >20% de la mediana
        // Previne artefacte rare (300ms sau 2000ms) care infleaza RMSSD cu sute de ms
        val sorted = physiological.map { it.ibiMs }.sorted()
        val median = sorted[sorted.size / 2].toDouble()
        val valid = physiological.filter { kotlin.math.abs(it.ibiMs - median) < 0.20 * median }
        if (valid.size < 2) return null

        val mean = valid.map { it.ibiMs.toDouble() }.average()
        val variance = valid.map { (it.ibiMs - mean) * (it.ibiMs - mean) }.average()
        val sdnn = sqrt(variance)

        val successiveDiffs = successiveDiffs(valid)
        val rmssd = if (successiveDiffs.isEmpty()) 0.0
        else sqrt(successiveDiffs.map { it * it }.average())

        val pnn50 = if (successiveDiffs.isEmpty()) 0.0
        else 100.0 * successiveDiffs.count { abs(it) > 50 } / successiveDiffs.size

        val meanHr = if (mean > 0) 60_000.0 / mean else 0.0
        val windowSec = valid.sumOf { it.ibiMs } / 1000.0

        return Features(
            rmssd = rmssd,
            sdnn = sdnn,
            meanIbiMs = mean,
            meanHrBpm = meanHr,
            pnn50 = pnn50,
            ibiCount = valid.size,
            windowSec = windowSec,
        )
    }

    /** Preferă perechi cu timestamp coerent; fallback la ΔIBI simplu (rafale GW7 = același ts). */
    private fun successiveDiffs(valid: List<IbiWindowEntry>): List<Double> {
        val withTs = successiveDiffsWithTemporalCheck(valid)
        if (withTs.isNotEmpty()) return withTs
        if (valid.size < 2) return emptyList()
        return (0 until valid.size - 1).map { i ->
            (valid[i + 1].ibiMs - valid[i].ibiMs).toDouble()
        }
    }

    private fun successiveDiffsWithTemporalCheck(valid: List<IbiWindowEntry>): List<Double> {
        val diffs = mutableListOf<Double>()
        for (i in 0 until valid.size - 1) {
            val a = valid[i]
            val b = valid[i + 1]
            val gapMs = b.ts - a.ts
            if (abs(gapMs - b.ibiMs.toLong()) < MAX_IBI_TS_MISMATCH_MS) {
                diffs.add((b.ibiMs - a.ibiMs).toDouble())
            }
        }
        return diffs
    }
}
