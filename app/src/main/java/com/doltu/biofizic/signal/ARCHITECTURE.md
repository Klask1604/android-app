# signal/ (watch)

## Purpose
The lowest signal layer on the watch: validate and normalize raw beats from the Samsung
Health SDK into clean IBI entries, and define the small timed-sample types the rest of
the app shares.

## Inputs
- Raw IBI values + SDK status flags + current HR (bpm) from the Samsung HR tracker.

## Outputs
- Accepted beats (or `null` rejected) via `IbiSignalFilter.acceptBeat(...)`.
- `IbiWindowEntry(ibiMs, ts, tsSource)` — one beat with a timestamp, the atom the
  `acquisition/` layer bundles.
- `TimedSample(ts, value)` — a generic timestamped reading.

## Key files
| File | Role |
|---|---|
| `IbiPipeline.kt` | `IbiSignalFilter` (status + 300–2000 ms physiological band + ×10 unit-scale fix), `IbiWindowEntry`, `TimedSample` |

## Data flow
```
SDK raw IBI + status + HR ─▶ IbiSignalFilter
   ├─ isStatusOk?  ├─ normalizeIbiMs (×10 if GW7 sends 62 vs 620)  ├─ isPhysiological?
   ▼
accepted IBI ─▶ IbiWindowEntry ─▶ acquisition/AcquisitionAssembler
```

## Depends on / Used by
- **Depends on:** nothing (leaf; kotlin stdlib).
- **Used by:** `acquisition/AcquisitionAssembler.kt`, `presentation/SensorService.kt`.
- Sync note: `MIN_IBI_MS=300` / `MAX_IBI_MS=2000` must match the server
  (`biofizic/config.py` MIN/MAX_INTERBEAT_INTERVAL_MS) — kept in sync by hand.
