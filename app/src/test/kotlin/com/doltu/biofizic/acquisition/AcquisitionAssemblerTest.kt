package com.doltu.biofizic.acquisition

import com.doltu.biofizic.signal.IbiSignalFilter
import com.doltu.biofizic.signal.IbiWindowEntry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit tests for atomic-sync logic in AcquisitionAssembler.
 *
 * These pin the contract that makes server-side HRV math correct: every
 * acquisition/batch v2 payload bundles IBI, motion stats, cardiac-band motion
 * energy and skin temperature referenced to a single ts_anchor, with per-beat
 * IBI timestamps reconstructed walking backwards from that anchor.
 */
class AcquisitionAssemblerTest {

    private fun assembler(): AcquisitionAssembler = AcquisitionAssembler()

    // -----------------------------------------------------------------------
    // Timestamp normalization
    // -----------------------------------------------------------------------

    @Test
    fun `normalize accepts millisecond epochs as-is`() {
        val a = assembler()
        val ms = 1_716_000_000_000L  // mid-2024 in ms
        assertEquals(ms, a.normalizeSensorTimestampMs(ms))
        assertTrue(a.isEpochMillis(ms))
    }

    @Test
    fun `normalize converts nanoseconds to milliseconds`() {
        val a = assembler()
        val ns = 1_716_000_000_000_000_000L  // same instant in ns
        val ms = a.normalizeSensorTimestampMs(ns)
        assertEquals(1_716_000_000_000L, ms)
        assertTrue(a.isEpochMillis(ms))
    }

    @Test
    fun `normalize returns zero for invalid input`() {
        val a = assembler()
        assertEquals(0L, a.normalizeSensorTimestampMs(0L))
        assertEquals(0L, a.normalizeSensorTimestampMs(-1L))
    }

    // -----------------------------------------------------------------------
    // buildIbiTimestamps: anchor selection and backwards walk
    // -----------------------------------------------------------------------

    @Test
    fun `buildIbiTimestamps uses dp epoch ms when available`() {
        val a = assembler()
        val dpMs = 1_716_000_000_000L
        val recv = 1_716_000_000_500L
        val accepted = listOf(800, 820, 810)
        val (ts, source) = a.buildIbiTimestamps(accepted, dpMs, recv)
        assertEquals("dp_timestamp", source)
        // Last entry is anchored on dpMs and earlier entries walk backwards.
        assertEquals(dpMs, ts[2])
        assertEquals(dpMs - 810, ts[1])
        assertEquals(dpMs - 810 - 820, ts[0])
    }

    @Test
    fun `buildIbiTimestamps falls back to recv when dp is invalid`() {
        val a = assembler()
        val recv = 1_716_000_000_500L
        val accepted = listOf(800, 820)
        val (ts, source) = a.buildIbiTimestamps(accepted, dpTs = 0L, recvMs = recv)
        assertEquals("reconstructed", source)
        assertEquals(recv, ts[1])
        assertEquals(recv - 820, ts[0])
    }

    @Test
    fun `buildIbiTimestamps recognises nanosecond dp timestamps`() {
        val a = assembler()
        val dpNs = 1_716_000_000_000_000_000L
        val expectedMs = 1_716_000_000_000L
        val recv = 1_716_000_000_500L
        val (ts, source) = a.buildIbiTimestamps(listOf(800), dpNs, recv)
        assertEquals("dp_timestamp", source)
        assertEquals(expectedMs, ts[0])
    }

    // -----------------------------------------------------------------------
    // buildIbiTimestampsWithGaps: rejected beats leave a visible time gap so
    // RMSSD is not inflated across a dropped beat.
    // -----------------------------------------------------------------------

    @Test
    fun `gaps reconstruction emits timestamps only for accepted beats`() {
        val a = assembler()
        val dpMs = 1_716_000_000_000L
        val recv = dpMs + 500
        val evals = listOf(
            IbiSignalFilter.BeatEval(820, accepted = true),
            IbiSignalFilter.BeatEval(260, accepted = false),  // rejected -1 beat
            IbiSignalFilter.BeatEval(810, accepted = true),
        )
        val (durations, timestamps, source) = a.buildIbiTimestampsWithGaps(evals, dpMs, recv)
        assertEquals("dp_timestamp", source)
        // Only the two accepted beats produce entries.
        assertEquals(listOf(820, 810), durations.toList())
        // Last accepted beat anchored on dpMs.
        assertEquals(dpMs, timestamps[1])
        // The first accepted beat's timestamp is pushed back by the REJECTED
        // beat's duration too (810 + 260), not just by 810. This is the gap
        // that keeps the two survivors from looking consecutive.
        assertEquals(dpMs - 810 - 260, timestamps[0])
    }

    @Test
    fun `gap leaves the surviving pair timestamp-incoherent so the server skips it`() {
        val a = assembler()
        val dpMs = 1_716_000_000_000L
        val evals = listOf(
            IbiSignalFilter.BeatEval(800, accepted = true),
            IbiSignalFilter.BeatEval(600, accepted = false),  // dropped beat
            IbiSignalFilter.BeatEval(810, accepted = true),
        )
        val (_, ts, _) = a.buildIbiTimestampsWithGaps(evals, dpMs, dpMs + 1)
        // The inter-beat gap between the two survivors is 810 + 600 = 1410 ms,
        // which differs from the later beat's IBI (810 ms) by 600 ms, far
        // above MAX_TIMESTAMP_IBI_MISMATCH_MS (250). The server's coherence
        // check will therefore drop this pair from RMSSD, which is the point.
        val gap = ts[1] - ts[0]
        assertEquals(1410L, gap)
        assertTrue(kotlin.math.abs(gap - 810) > 250)
    }

    @Test
    fun `evaluateBeat keeps a duration for a status-rejected beat`() {
        // Status -1 marks a bad beat; we still want a plausible duration so the
        // reconstruction clock advances over it.
        val rejected = IbiSignalFilter.evaluateBeat(rawIbiMs = 820, status = -1, hrBpm = 72)
        assertFalse(rejected.accepted)
        assertEquals(820, rejected.normalizedMs)  // physiological value retained as duration

        val accepted = IbiSignalFilter.evaluateBeat(rawIbiMs = 810, status = 0, hrBpm = 72)
        assertTrue(accepted.accepted)
        assertEquals(810, accepted.normalizedMs)
    }

    @Test
    fun `evaluateBeat falls back to HR-derived duration when value is unusable`() {
        // A wildly out-of-band rejected beat: use 60000/hr so the clock still
        // advances by a realistic amount.
        val r = IbiSignalFilter.evaluateBeat(rawIbiMs = 5_000, status = -1, hrBpm = 60)
        assertFalse(r.accepted)
        assertEquals(1_000, r.normalizedMs)  // 60000 / 60 bpm
    }

    // -----------------------------------------------------------------------
    // buildAcquisitionPayload: ts_anchor is max across streams
    // -----------------------------------------------------------------------

    @Test
    fun `ts_anchor is the max of ts_publish, IBI and skin temp`() {
        val a = assembler()
        val tsPublish = 1_716_000_010_000L
        val ibiTs = 1_716_000_010_400L  // newer than publish
        val skinTs = 1_716_000_010_700L  // newest of all
        a.lastSkinTempTsMs = skinTs

        val payload = a.buildAcquisitionPayload(
            tsPublish = tsPublish,
            motion = zeroMotion(),
            accBandCardiac = 0.0,
            ibiSlice = listOf(IbiWindowEntry(820, ibiTs, "dp_timestamp")),
            heartRateBpm = 72,
            displayOn = true,
            skinTempC = 33.5,
            ambientTempC = 23.0,
        )
        val anchorRegex = Regex(""""ts_anchor":(\d+)""")
        val anchor = anchorRegex.find(payload.json)?.groupValues?.get(1)?.toLong()
        assertEquals(skinTs, anchor, "ts_anchor must be the latest known timestamp across all streams")
    }

    @Test
    fun `ts_anchor falls back to ts_publish when streams are empty`() {
        val a = assembler()
        val tsPublish = 1_716_000_020_000L
        val payload = a.buildAcquisitionPayload(
            tsPublish = tsPublish,
            motion = zeroMotion(),
            accBandCardiac = 0.0,
            ibiSlice = emptyList(),
            heartRateBpm = 72,
            displayOn = true,
            skinTempC = 0.0,
            ambientTempC = 0.0,
        )
        val anchorRegex = Regex(""""ts_anchor":(\d+)""")
        val anchor = anchorRegex.find(payload.json)?.groupValues?.get(1)?.toLong()
        assertEquals(tsPublish, anchor)
    }

    @Test
    fun `acquisition payload carries acc_band_cardiac in the motion block`() {
        val a = assembler()
        val payload = a.buildAcquisitionPayload(
            tsPublish = 1L, motion = zeroMotion(),
            accBandCardiac = 0.1234, ibiSlice = emptyList(),
            heartRateBpm = 70, displayOn = true,
            skinTempC = 0.0, ambientTempC = 0.0,
        )
        assertTrue(payload.json.contains("\"acc_band_cardiac\":0.1234"))
        assertTrue(!payload.json.contains("\"ppg\""))
    }

    @Test
    fun `acquisition payload sequence increments on every call`() {
        val a = assembler()
        val first = a.buildAcquisitionPayload(
            tsPublish = 1L, motion = zeroMotion(),
            accBandCardiac = 0.0, ibiSlice = emptyList(),
            heartRateBpm = 70, displayOn = true,
            skinTempC = 0.0, ambientTempC = 0.0,
        )
        val second = a.buildAcquisitionPayload(
            tsPublish = 2L, motion = zeroMotion(),
            accBandCardiac = 0.0, ibiSlice = emptyList(),
            heartRateBpm = 70, displayOn = true,
            skinTempC = 0.0, ambientTempC = 0.0,
        )
        assertEquals(first.sequence + 1, second.sequence)
    }

    // -----------------------------------------------------------------------
    // drainIbiForPublish: pending queue first, then horizon fallback
    // -----------------------------------------------------------------------

    @Test
    fun `drain prefers the pending queue when it has entries`() {
        val a = assembler()
        val now = 1_716_000_030_000L
        a.addIbiBatch(
            listOf(
                IbiWindowEntry(820, now - 1_000, "dp_timestamp"),
                IbiWindowEntry(810, now - 500, "dp_timestamp"),
            ),
        )
        val drained = a.drainIbiForPublish(now)
        assertEquals(2, drained.size)
        // After drain, the pending queue must be empty so a subsequent call
        // falls through to the horizon fallback (which will still see the
        // window snapshot, so we feed a tsPublish far in the future to get an
        // empty result).
        val later = now + 60_000L
        assertTrue(a.drainIbiForPublish(later).isEmpty())
    }

    @Test
    fun `drain falls back to horizon window when pending is empty`() {
        // Two beats: one inside the 4.5 s horizon of the upcoming publish,
        // one outside. The horizon fallback must include the inside beat and
        // skip the older one.
        //
        // Anchor synthetic timestamps to the wall clock because trimIbiWindow
        // uses System.currentTimeMillis() for the retention cutoff.
        val a = assembler()
        val now = System.currentTimeMillis()
        a.addIbiBatch(
            listOf(
                IbiWindowEntry(820, now - 10_000, "dp_timestamp"),  // outside 4.5 s horizon
                IbiWindowEntry(810, now - 1_000, "dp_timestamp"),    // inside
            ),
        )
        // First drain consumes the pending queue and clears it.
        a.drainIbiForPublish(now)
        // Second drain has no pending and must use the horizon fallback.
        val tsPublish = now + 500
        val viaHorizon = a.drainIbiForPublish(tsPublish)
        assertEquals(
            1,
            viaHorizon.size,
            "horizon fallback must include only the beat inside the 4.5 s window",
        )
        assertEquals(810, viaHorizon[0].ibiMs)
    }

    // -----------------------------------------------------------------------
    // Motion stats: 1 s window cutoff
    // -----------------------------------------------------------------------

    @Test
    fun `motion stats only include samples inside the 1 s window`() {
        val a = assembler()
        val now = 1_716_000_050_000L
        a.addAccSample(now - 5_000, 1.0)   // outside the 1 s window
        a.addAccSample(now - 500, 0.5)     // inside
        a.addAccSample(now - 100, 0.3)     // inside
        val stats = a.computeMotionStats(now)
        // Only the two inside-window samples contribute to RMS.
        val expectedRms = kotlin.math.sqrt((0.5 * 0.5 + 0.3 * 0.3) / 2.0)
        assertEquals(expectedRms, stats.accRms, 1e-9)
    }

    // -----------------------------------------------------------------------
    // clear: full reset
    // -----------------------------------------------------------------------

    @Test
    fun `clear resets buffers, skin temp ts and acquisition sequence`() {
        val a = assembler()
        val now = 1_716_000_060_000L
        a.addIbiBatch(listOf(IbiWindowEntry(800, now, "dp_timestamp")))
        a.addAccSample(now, 0.4)
        a.addGyroSample(now, 0.1)
        a.lastSkinTempTsMs = now
        // Force a payload build to advance the sequence past zero.
        a.buildAcquisitionPayload(
            tsPublish = now, motion = zeroMotion(),
            accBandCardiac = 0.0, ibiSlice = emptyList(),
            heartRateBpm = 70, displayOn = true,
            skinTempC = 0.0, ambientTempC = 0.0,
        )

        a.clear()

        assertEquals(0, a.ibiWindowSize())
        assertEquals(0L, a.lastIbiTimestampMs)
        assertEquals(0L, a.lastSkinTempTsMs)
        assertTrue(a.drainIbiForPublish(now).isEmpty())
        val stats = a.computeMotionStats(now)
        assertEquals(0.0, stats.accRms, 1e-9)
        assertEquals(0.0, stats.gyroRms, 1e-9)
        // First payload after clear must restart sequence numbering at 1.
        val first = a.buildAcquisitionPayload(
            tsPublish = now, motion = zeroMotion(),
            accBandCardiac = 0.0, ibiSlice = emptyList(),
            heartRateBpm = 70, displayOn = true,
            skinTempC = 0.0, ambientTempC = 0.0,
        )
        assertEquals(1L, first.sequence)
    }

    // -----------------------------------------------------------------------
    // Cardiac-band motion energy (0.5-4 Hz)
    // -----------------------------------------------------------------------

    @Test
    fun `cardiac band energy is zero below the minimum sample count`() {
        val a = assembler()
        val now = 1_716_000_070_000L
        // Only a handful of samples in the window: not enough to estimate.
        for (i in 0 until 10) a.addAccSample(now - i * 40L, 1.0)
        assertEquals(0.0, a.computeCardiacBandEnergy(now), 1e-12)
    }

    @Test
    fun `cardiac band energy rises for in-band motion vs a still wrist`() {
        val still = assembler()
        val moving = assembler()
        val now = 1_716_000_080_000L
        val fs = 25.0
        val n = 200                       // 8 s at 25 Hz
        for (i in 0 until n) {
            val ts = now - ((n - 1 - i) * 1000L / fs.toLong())
            still.addAccSample(ts, 0.0)   // perfectly still
            // 1.5 Hz oscillation -> energy inside the 0.5-4 Hz band
            val v = kotlin.math.sin(2.0 * Math.PI * 1.5 * i / fs)
            moving.addAccSample(ts, v)
        }
        val stillEnergy = still.computeCardiacBandEnergy(now)
        val movingEnergy = moving.computeCardiacBandEnergy(now)
        assertTrue(movingEnergy > stillEnergy, "in-band motion must raise cardiac-band energy")
    }

    private fun zeroMotion(): AcquisitionAssembler.MotionWindowStats =
        AcquisitionAssembler.MotionWindowStats(0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
}
