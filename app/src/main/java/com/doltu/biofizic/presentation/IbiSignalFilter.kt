package com.doltu.biofizic.presentation

import kotlin.math.abs

/**
 * Filtru minim IBI: status Samsung + interval fiziologic (ms).
 * Fără mediană/outliers — HRV se calculează pe epocă din lista completă.
 */
object IbiSignalFilter {

    const val MIN_IBI_MS = 250
    const val MAX_IBI_MS = 2_500

    /** GW7: bătăi valide des cu st=-1; respingem doar erori (ex. -10). */
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
