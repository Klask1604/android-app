package com.doltu.biofizic.presentation

import kotlin.collections.ArrayDeque
import kotlin.math.abs

/**
 * Filtrare IBI înainte de HRV: status Samsung, interval fiziologic, outliers față de mediană.
 */
object IbiSignalFilter {

    const val MIN_IBI_MS = 300
    const val MAX_IBI_MS = 2_000
    private const val MAX_DEVIATION_RATIO = 0.30

    /** [ValueKey.EdaSet] / HeartRate: 0 = normal; -1 = eroare. */
    fun isStatusOk(status: Int?): Boolean = status == null || status == 0

    fun isPhysiological(ibiMs: Int): Boolean = ibiMs in MIN_IBI_MS..MAX_IBI_MS

    /**
     * Respinge bătăi izolate față de mediană (artefact PPG, ex. IBI 522 ms la HR ~96).
     */
    fun isConsistentWithWindow(ibiMs: Int, recentIbIs: Collection<Int>): Boolean {
        if (recentIbIs.size < 3) return true
        val median = recentIbIs.sorted()[recentIbIs.size / 2]
        if (median <= 0) return true
        return abs(ibiMs - median) <= median * MAX_DEVIATION_RATIO
    }

    fun accept(
        ibiMs: Int,
        status: Int?,
        recentIbIs: Collection<Int>,
    ): Boolean =
        isStatusOk(status) &&
            isPhysiological(ibiMs) &&
            isConsistentWithWindow(ibiMs, recentIbIs)

    /** Curăță fereastra existentă de outliers. */
    fun sanitizeWindow(window: ArrayDeque<IbiWindowEntry>) {
        if (window.size < 4) return
        val ms = window.map { it.ibiMs }
        val median = ms.sorted()[ms.size / 2]
        val keep = window.filter { abs(it.ibiMs - median) <= median * MAX_DEVIATION_RATIO }
        window.clear()
        keep.forEach { window.addLast(it) }
    }
}
