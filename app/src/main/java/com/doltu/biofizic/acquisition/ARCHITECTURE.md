# acquisition/ (watch)

## Purpose
Assemble the **atomic 1 Hz acquisition batch**: bundle IBI, motion stats and PPG so every
field in one packet refers to the same time window, anchored by a shared `ts_anchor`.

## Inputs
- Accepted `IbiWindowEntry` beats (from `signal/`).
- Per-second buffers of accelerometer / gyroscope samples (~25 Hz) + raw PPG (100 Hz) + skin temp,
  pushed by `presentation/SensorService.kt` from SDK / Android sensor threads.

## Outputs
- The `acquisition/batch` v2 JSON payload (published by `presentation/PublishScheduler`):
  `ts_publish`, `ts_anchor`, `seq`, `heart_rate_bpm`, acc/gyro `rms/p90/std`,
  **`acc_band_cardiac`** (0.5–4 Hz energy, ② computed), skin/ambient temp, drained IBI
  arrays, optional raw PPG arrays.

## Key files
| File | Role |
|---|---|
| `AcquisitionAssembler.kt` | Per-second buffers; `buildIbiTimestamps` (back-walk from anchor), `drainIbiForPublish` (drain since last publish, not a 1s slice), `computeCardiacBandEnergy` (FFT 0.5–4 Hz over 8s), `buildAcquisitionPayload` (compute `ts_anchor` = latest known ts) |

## Data flow
```
IbiWindowEntry + acc/gyro/ppg/temp buffers ─▶ AcquisitionAssembler
   ├─ drainIbiForPublish (all beats since last publish)
   ├─ computeCardiacBandEnergy ─▶ acc_band_cardiac
   ├─ ts_anchor = max(ts across IBI/PPG/motion/temp)
   ▼
acquisition/batch payload ─▶ PublishScheduler ─▶ MQTT
```

## Depends on / Used by
- **Depends on:** `signal/` (beats), kotlin math (FFT energy).
- **Used by:** `presentation/SensorService.kt` + `PublishScheduler.kt`; consumed server-side by `biofizic/ingestion`.
- Why atomic: the SDK delivers each tracker at a different cadence (HR ~4s bursts, PPG 100 Hz in bursts);
  `ts_anchor` lets the server align its 30s HRV window to the instant the watch saw.
