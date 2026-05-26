package com.doltu.biofizic.signal

import kotlin.math.abs
import kotlin.math.sqrt

/** Un interval IBI (ms) cu timestamp senzor — pentru fereastră HRV pe timp. */
data class IbiWindowEntry(
    val ibiMs: Int,
    val ts: Long,
    val tsSource: String = "reconstructed",
)

/** Sensor reading with Samsung SDK or wall-clock timestamp (epoch ms). */
data class TimedSample(val ts: Long, val value: Double)

/**
 * Filtru minim IBI: status Samsung + interval fiziologic (ms).
 * Range 300–2000 ms aliniat cu server; outlier median 20% doar server-side.
 */
object IbiSignalFilter {

    // MUST stay in sync with the server: biofizic/config.py
    // MIN/MAX_INTERBEAT_INTERVAL_MS. Both ends filter the same physiological band.
    const val MIN_IBI_MS = 300
    const val MAX_IBI_MS = 2_000

    fun isStatusOk(status: Int?): Boolean =
        status == null || status == 0 || status == -1

    fun isPhysiological(ibiMs: Int): Boolean = ibiMs in MIN_IBI_MS..MAX_IBI_MS

    /** GW7 poate trimite 62 în loc de 620 ms — scalare ×10 când e plauzibil. */
    fun normalizeIbiMs(raw: Int, hrBpm: Int): Int {
        if (raw in MIN_IBI_MS..MAX_IBI_MS) return raw
        if (raw in 30..250) {
            val scaled = raw * 10
            if (scaled in MIN_IBI_MS..MAX_IBI_MS) {
                if (hrBpm > 0) {
                    val expected = 60_000 / hrBpm
                    if (abs(scaled - expected) < abs(raw - expected)) return scaled
                } else {
                    return scaled
                }
            }
        }
        return raw
    }

    fun acceptBeat(rawIbiMs: Int, status: Int?, hrBpm: Int): Int? {
        if (!isStatusOk(status)) return null
        val norm = normalizeIbiMs(rawIbiMs, hrBpm)
        return if (isPhysiological(norm)) norm else null
    }
}

/**
 * Feature-uri HRV locale pentru signalOk UI (verdict final pe server).
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

    private const val MIN_IBI_MS = IbiSignalFilter.MIN_IBI_MS
    private const val MAX_IBI_MS = IbiSignalFilter.MAX_IBI_MS
    // Cross-codebase constants — keep equal to biofizic/config.py:
    //   MAX_IBI_TS_MISMATCH_MS  <- MAX_TIMESTAMP_IBI_MISMATCH_MS
    //   OUTLIER_MEDIAN_DEVIATION_RATIO, PNN50_THRESHOLD_MS
    private const val MAX_IBI_TS_MISMATCH_MS = 250L
    private const val OUTLIER_MEDIAN_DEVIATION_RATIO = 0.20
    private const val PNN50_THRESHOLD_MS = 50.0

    fun compute(ibiEntries: List<IbiWindowEntry>): Features? {
        val physiological = ibiEntries.filter { it.ibiMs in MIN_IBI_MS..MAX_IBI_MS }
        if (physiological.size < 2) return null

        val sorted = physiological.map { it.ibiMs }.sorted()
        val median = sorted[sorted.size / 2].toDouble()
        val valid = physiological.filter {
            abs(it.ibiMs - median) < OUTLIER_MEDIAN_DEVIATION_RATIO * median
        }
        if (valid.size < 2) return null

        val mean = valid.map { it.ibiMs.toDouble() }.average()
        val variance = valid.map { (it.ibiMs - mean) * (it.ibiMs - mean) }.average()
        val sdnn = sqrt(variance)

        val successiveDiffs = successiveDiffs(valid)
        val rmssd = if (successiveDiffs.isEmpty()) 0.0
        else sqrt(successiveDiffs.map { it * it }.average())

        val pnn50 = if (successiveDiffs.isEmpty()) 0.0
        else 100.0 * successiveDiffs.count { abs(it) > PNN50_THRESHOLD_MS } / successiveDiffs.size

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
