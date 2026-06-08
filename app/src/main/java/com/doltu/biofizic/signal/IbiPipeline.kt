package com.doltu.biofizic.signal

import kotlin.math.abs
import kotlin.math.sqrt

/** One IBI interval (ms) with a sensor timestamp, for the time-based HRV window. */
data class IbiWindowEntry(
    val ibiMs: Int,
    val ts: Long,
    val tsSource: String = "reconstructed",
)

/** Sensor reading with Samsung SDK or wall-clock timestamp (epoch ms). */
data class TimedSample(val ts: Long, val value: Double)

/**
 * Minimal IBI filter: Samsung status + physiological interval (ms).
 * Range 300-2000 ms aligned with the server; 20% median outlier rule is server-side only.
 */
object IbiSignalFilter {

    // MUST stay in sync with the server: biofizic/config.py
    // MIN/MAX_INTERBEAT_INTERVAL_MS. Both ends filter the same physiological band.
    const val MIN_IBI_MS = 300
    const val MAX_IBI_MS = 2_000

    // Empirically: Samsung's HR_CONTINUOUS marks bad beats with status == -1 in
    // the dataset's IBI_STATUS_LIST (verified on GalaxyPPG and our own live
    // sessions, typical -1 intervals are 260 ms or 1600 ms, physiologically
    // impossible). Earlier this filter accepted -1 thinking Samsung was just
    // signalling "low confidence" on a still-usable beat; that was wrong.
    // Rejecting -1 cuts the published artifact_rate roughly in half on motion
    // and keeps w30 RMSSD inside the physiological band.
    fun isStatusOk(status: Int?): Boolean =
        status == null || status == 0

    fun isPhysiological(ibiMs: Int): Boolean = ibiMs in MIN_IBI_MS..MAX_IBI_MS

    /** GW7 may send 62 instead of 620 ms; scale x10 when plausible. */
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

    /**
     * Outcome of evaluating one raw beat WITHOUT discarding it: the normalized
     * duration (always, so timestamp reconstruction can advance the clock even
     * over a rejected beat) plus whether the beat is accepted for HRV.
     *
     * Why we keep rejected durations: HR ships beats in bursts and we rebuild
     * per-beat timestamps by walking back from an anchor. If we drop a rejected
     * beat entirely, the two surviving neighbours look consecutive (their
     * timestamps end up [interval] apart) and a successive difference is taken
     * ACROSS the gap, inflating RMSSD. By advancing the reconstructed clock over
     * the rejected beat's duration the gap stays visible, so the server's
     * timestamp-coherence check (and the on-watch one) correctly skips that pair.
     */
    data class BeatEval(val normalizedMs: Int, val accepted: Boolean)

    /**
     * Evaluate a raw beat, returning a best-effort duration even when rejected.
     * A status-rejected beat still carries a duration estimate: the normalized
     * raw value when it lands in a plausible band, otherwise the expected IBI
     * from HR (60000/hr), otherwise 0 (clock simply does not advance, no worse
     * than today's behaviour).
     */
    fun evaluateBeat(rawIbiMs: Int, status: Int?, hrBpm: Int): BeatEval {
        val norm = normalizeIbiMs(rawIbiMs, hrBpm)
        val accepted = isStatusOk(status) && isPhysiological(norm)
        if (accepted) return BeatEval(norm, true)
        // Rejected: pick the most defensible duration so the clock advances by a
        // realistic amount across the gap.
        val duration = when {
            isPhysiological(norm) -> norm                       // physiological but status-flagged
            norm in 30..MAX_IBI_MS -> norm                      // scaled into a plausible range
            hrBpm > 0 -> (60_000 / hrBpm).coerceIn(MIN_IBI_MS, MAX_IBI_MS)
            else -> 0
        }
        return BeatEval(duration, false)
    }
}

/**
 * Feature-uri HRV locale pentru signalOk UI (verdict final pe server).
 * [windowSec] = sum of valid IBI / 1000 = cardiac duration covered by the window.
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
    // Cross-codebase constants, keep equal to biofizic/config.py:
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
