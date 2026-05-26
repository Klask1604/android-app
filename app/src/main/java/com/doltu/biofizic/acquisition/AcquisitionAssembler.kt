package com.doltu.biofizic.acquisition

import com.doltu.biofizic.signal.IbiWindowEntry
import com.doltu.biofizic.signal.TimedSample
import java.util.Locale
import kotlin.math.sqrt

/**
 * Owns every per-second buffer that ends up in the atomic acquisition/batch v2
 * payload, plus all of the timestamp reconstruction that keeps IBI, PPG and
 * motion stats aligned to the same wall-clock window.
 *
 * Atomic sync, in one place: the Samsung Health SDK gives each tracker its
 * own delivery cadence (HR at ~4 s bursts, PPG at ~25 Hz, motion polled at
 * 1 Hz). The HRV math on the server needs every metric in the payload to
 * refer to the same time window. To get that, the assembler:
 *
 *   1. Walks IBI timestamps backwards from a known anchor in
 *      [buildIbiTimestamps] when the SDK ships data without per-beat
 *      epoch timestamps.
 *   2. Computes [ts_anchor] in [buildAcquisitionPayload] as the latest known
 *      timestamp across IBI, PPG, motion and skin temperature, so the
 *      server can align its 30 s rolling HRV window to the same instant
 *      the watch saw.
 *   3. Drains IBI accepted since the previous publish via
 *      [drainIbiForPublish] instead of slicing a 1 s window. HR ships in
 *      ~4 s bursts, so a fixed 1 s slice would lose entire batches at the
 *      boundary.
 *
 * The class is thread-safe: SDK callbacks add data from the SDK thread,
 * the publish loop drains from the main handler thread, and onSensorChanged
 * pushes gyro from the Android sensor thread. All mutation goes through the
 * two internal locks.
 */
class AcquisitionAssembler(
    private val maxIbiEntries: Int = 200,
    private val ibiRetentionMs: Long = 120_000L,
    private val maxMotionSamples: Int = 750,  // ~25 Hz x 30 s
    private val publishHorizonMs: Long = 4_500L,
) {

    /** Snapshot of motion stats computed over the last 1 s wall-clock window. */
    data class MotionWindowStats(
        val accRms: Double,
        val accP90: Double,
        val accStd: Double,
        val gyroRms: Double,
        val gyroP90: Double,
        val gyroStd: Double,
    )

    private val bufferLock = Any()
    private val ppgBatchLock = Any()

    private val ibiWindow = ArrayDeque<IbiWindowEntry>(220)
    // IBI accepted since the previous publish. HR ships in ~4 s bursts and we
    // publish every 1 s, so we cannot rely on a fixed time slice or beats are
    // lost at the boundary.
    private val ibiPendingPublish = ArrayDeque<IbiWindowEntry>(64)
    private val accDynSamples = ArrayDeque<TimedSample>(maxMotionSamples)
    private val gyroDynSamples = ArrayDeque<TimedSample>(maxMotionSamples)
    private val ppgBatch = mutableListOf<Triple<Long, Int, Int>>()  // (ts_ms, green, ir)

    @Volatile var lastIbiTimestampMs: Long = 0L
        private set
    @Volatile var lastSkinTempTsMs: Long = 0L
    private var acquisitionSeq: Long = 0L

    /**
     * Prime the IBI watchdog timestamp when an HR tracker is (re)started so
     * the staleness check does not fire immediately while we wait for the
     * first burst from the Samsung SDK.
     */
    fun markIbiTrackerStarted() {
        lastIbiTimestampMs = System.currentTimeMillis()
    }

    // ------------------------------------------------------------------
    // Buffer mutation: called from SDK / sensor callbacks
    // ------------------------------------------------------------------

    /** Add a batch of accepted IBI beats with reconstructed per-beat timestamps. */
    fun addIbiBatch(beats: List<IbiWindowEntry>) {
        if (beats.isEmpty()) return
        synchronized(bufferLock) {
            for (entry in beats) {
                ibiWindow.addLast(entry)
                ibiPendingPublish.addLast(entry)
            }
            lastIbiTimestampMs = beats.last().ts
            trimIbiWindowLocked()
        }
    }

    fun addAccSample(ts: Long, dyn: Double) {
        synchronized(bufferLock) {
            accDynSamples.addLast(TimedSample(ts, dyn))
            while (accDynSamples.size > maxMotionSamples) accDynSamples.removeFirst()
        }
    }

    fun addGyroSample(ts: Long, mag: Double) {
        synchronized(bufferLock) {
            gyroDynSamples.addLast(TimedSample(ts, mag))
            while (gyroDynSamples.size > maxMotionSamples) gyroDynSamples.removeFirst()
        }
    }

    fun addPpgSample(ts: Long, green: Int, ir: Int) {
        synchronized(ppgBatchLock) { ppgBatch.add(Triple(ts, green, ir)) }
    }

    /** Drop everything. Called on stop and on baseline recalibration. */
    fun clear() {
        synchronized(bufferLock) {
            ibiWindow.clear()
            ibiPendingPublish.clear()
            accDynSamples.clear()
            gyroDynSamples.clear()
            lastIbiTimestampMs = 0L
        }
        synchronized(ppgBatchLock) { ppgBatch.clear() }
        acquisitionSeq = 0L
        lastSkinTempTsMs = 0L
    }

    // ------------------------------------------------------------------
    // Atomic sync helpers
    // ------------------------------------------------------------------

    /**
     * Reconstruct per-beat IBI timestamps walking backwards from the most
     * trustworthy anchor available. Samsung Health HR bursts ship an
     * aggregated dp.timestamp; if that is a real epoch ms we use it,
     * otherwise we fall back to the local receive time.
     */
    fun buildIbiTimestamps(
        accepted: List<Int>,
        dpTs: Long,
        recvMs: Long,
    ): Pair<LongArray, String> {
        val normDp = normalizeSensorTimestampMs(dpTs)
        val useDp = isEpochMillis(normDp)
        val anchor = if (useDp) normDp else recvMs
        val source = if (useDp) "dp_timestamp" else "reconstructed"
        val timestamps = LongArray(accepted.size)
        var endTs = anchor
        for (i in accepted.indices.reversed()) {
            timestamps[i] = endTs
            endTs -= accepted[i]
        }
        return Pair(timestamps, source)
    }

    /** Samsung sometimes ships nanoseconds, sometimes ms, sometimes 0. */
    fun normalizeSensorTimestampMs(raw: Long): Long {
        if (raw <= 0L) return 0L
        if (raw > 1_000_000_000_000_000L) return raw / 1_000_000L
        return raw
    }

    fun isEpochMillis(ts: Long): Boolean = ts in 1_000_000_000_000L..2_500_000_000_000L

    // ------------------------------------------------------------------
    // Drain helpers: called from the publish loop
    // ------------------------------------------------------------------

    fun ibiWindowSize(): Int = synchronized(bufferLock) { ibiWindow.size }

    fun ibiWindowSnapshot(): List<IbiWindowEntry> = synchronized(bufferLock) { ibiWindow.toList() }

    /** Drain IBI accepted since the previous publish, plus a horizon fallback. */
    fun drainIbiForPublish(tsPublish: Long): List<IbiWindowEntry> {
        synchronized(bufferLock) {
            if (ibiPendingPublish.isNotEmpty()) {
                val slice = ibiPendingPublish.toList()
                ibiPendingPublish.clear()
                return slice
            }
            val cutoff = tsPublish - publishHorizonMs
            return ibiWindow.filter { it.ts >= cutoff }
        }
    }

    /** Snapshot the last 1 s of PPG samples for the current acquisition batch. */
    fun snapshotPpgWindow(tsPublish: Long): List<Triple<Long, Int, Int>> {
        synchronized(ppgBatchLock) {
            val cutoff = tsPublish - 1_000L
            val snap = ppgBatch.filter { it.first >= cutoff }
            ppgBatch.removeAll { it.first >= cutoff }
            return snap
        }
    }

    /** Motion stats over the last 1 s wall-clock window. */
    fun computeMotionStats(tsPublish: Long): MotionWindowStats {
        val cutoff = tsPublish - 1_000L
        synchronized(bufferLock) {
            val accDyn = accDynSamples.filter { it.ts >= cutoff }.map { it.value }
            val gyroVals = gyroDynSamples.filter { it.ts >= cutoff }.map { it.value }
            val accRms = rms(accDyn)
            val gyroRms = rms(gyroVals)
            return MotionWindowStats(
                accRms = accRms,
                accP90 = percentile90(accDyn),
                accStd = stddev(accDyn),
                gyroRms = gyroRms,
                gyroP90 = percentile90(gyroVals),
                gyroStd = stddev(gyroVals),
            )
        }
    }

    fun trimIbiWindow() {
        synchronized(bufferLock) { trimIbiWindowLocked() }
    }

    private fun trimIbiWindowLocked() {
        val cutoff = System.currentTimeMillis() - ibiRetentionMs
        while (ibiWindow.isNotEmpty() && ibiWindow.first().ts < cutoff) {
            ibiWindow.removeFirst()
        }
        while (ibiWindow.size > maxIbiEntries) {
            ibiWindow.removeFirst()
        }
    }

    // ------------------------------------------------------------------
    // acquisition/batch v2 payload
    // ------------------------------------------------------------------

    /**
     * Build the schema-v2 atomic acquisition payload.
     *
     * ts_anchor is the latest known timestamp across IBI, PPG, motion and
     * skin temperature in this batch. The server uses it to align the 30 s
     * rolling HRV window to the same instant the watch observed, which is
     * the whole point of bundling these streams together: classification
     * must run on data from the same time window, otherwise the values are
     * eroded by per-stream delays.
     */
    fun buildAcquisitionPayload(
        tsPublish: Long,
        motion: MotionWindowStats,
        ppgSnapshot: List<Triple<Long, Int, Int>>,
        ibiSlice: List<IbiWindowEntry>,
        heartRateBpm: Int,
        displayOn: Boolean,
        skinTempC: Double,
        ambientTempC: Double,
    ): AcquisitionPayload {
        val ibiSource = ibiSlice.lastOrNull()?.tsSource ?: "reconstructed"
        var tsAnchor = tsPublish
        ibiSlice.maxOfOrNull { it.ts }?.let { tsAnchor = maxOf(tsAnchor, it) }
        ppgSnapshot.maxOfOrNull { it.first }?.let { tsAnchor = maxOf(tsAnchor, it) }
        if (lastSkinTempTsMs > 0L) {
            tsAnchor = maxOf(tsAnchor, lastSkinTempTsMs)
        }

        acquisitionSeq++
        val json = buildString {
            append(
                """{"schema":2,"seq":$acquisitionSeq,"ts_publish":$tsPublish,"ts_anchor":$tsAnchor,"clock":"wall_ms""""
            )
            append(""","hr":$heartRateBpm,"display_on":$displayOn""")
            append(""","skin_temp":${jsonFloat(skinTempC, 2)},"skin_temp_ts":$lastSkinTempTsMs""")
            append(""","ambient_temp":${jsonFloat(ambientTempC, 2)}""")
            append(
                ""","motion":{"acc_rms":${jsonFloat(motion.accRms, 3)},"acc_p90":${jsonFloat(motion.accP90, 3)},"acc_std":${jsonFloat(motion.accStd, 3)},"gyro_rms":${jsonFloat(motion.gyroRms, 4)},"gyro_p90":${jsonFloat(motion.gyroP90, 4)},"gyro_std":${jsonFloat(motion.gyroStd, 4)},"window_ms":1000}"""
            )
            append(""","ibi":{"ms":[""")
            append(ibiSlice.joinToString(",") { it.ibiMs.toString() })
            append("],\"ts\":[")
            append(ibiSlice.joinToString(",") { it.ts.toString() })
            append("],\"source\":\"")
            append(ibiSource)
            append("\"}")
            append(""","ppg":{"ts_ms":[""")
            append(ppgSnapshot.joinToString(",") { it.first.toString() })
            append("],\"green\":[")
            append(ppgSnapshot.joinToString(",") { it.second.toString() })
            append("],\"ir\":[")
            append(ppgSnapshot.joinToString(",") { it.third.toString() })
            append("]}")
            append("}")
        }
        return AcquisitionPayload(
            json = json,
            sequence = acquisitionSeq,
            ibiCount = ibiSlice.size,
            ppgCount = ppgSnapshot.size,
            ibiSource = ibiSource,
        )
    }

    data class AcquisitionPayload(
        val json: String,
        val sequence: Long,
        val ibiCount: Int,
        val ppgCount: Int,
        val ibiSource: String,
    )

    // ------------------------------------------------------------------
    // internals
    // ------------------------------------------------------------------

    private fun rms(values: List<Double>): Double =
        if (values.isEmpty()) 0.0 else sqrt(values.map { it * it }.average())

    private fun stddev(values: List<Double>): Double {
        if (values.size < 2) return 0.0
        val mean = values.average()
        return sqrt(values.map { (it - mean) * (it - mean) }.average())
    }

    private fun percentile90(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val idx = ((sorted.size - 1) * 0.9).toInt().coerceIn(0, sorted.lastIndex)
        return sorted[idx]
    }

    private fun jsonFloat(value: Double, decimals: Int): String =
        String.format(Locale.US, "%.${decimals}f", value)
}
