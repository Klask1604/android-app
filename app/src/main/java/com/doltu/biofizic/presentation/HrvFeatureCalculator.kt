package com.doltu.biofizic.presentation

import kotlin.math.sqrt

/**
 * Feature-uri HRV din intervale IBI (ms).
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

    fun compute(ibiMs: List<Int>): Features? {
        val valid = ibiMs.filter { it in MIN_IBI_MS..MAX_IBI_MS }
        if (valid.size < 2) return null

        val mean = valid.average()
        val variance = valid.map { (it - mean) * (it - mean) }.average()
        val sdnn = sqrt(variance)

        val successiveDiffs = valid.zipWithNext { a, b -> (b - a).toDouble() }
        val rmssd = if (successiveDiffs.isEmpty()) 0.0
        else sqrt(successiveDiffs.map { it * it }.average())

        val pnn50 = if (successiveDiffs.isEmpty()) 0.0
        else 100.0 * successiveDiffs.count { kotlin.math.abs(it) > 50 } / successiveDiffs.size

        val meanHr = if (mean > 0) 60_000.0 / mean else 0.0
        val windowSec = valid.sum() / 1000.0

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
}
