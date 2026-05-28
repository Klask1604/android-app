# presentation/ (watch)

## Purpose
The app shell: foreground service lifecycle, sensor orchestration, MQTT in/out,
calibration flow, and the Wear Compose UI. This is where the watch talks to the world
and to the user.

## Inputs
- Sensor callbacks (Samsung Health SDK + Android sensors) → fed to `signal/` + `acquisition/`.
- MQTT from the server: `biofizic/state` (verdict, retained), `biofizic/state/live`,
  `biofizic/calibration/status` (phase collecting/done).
- User taps: Start / Stop / Recalibrate + the mood questionnaire (reported_arousal).

## Outputs
- MQTT publishes: `biofizic/acquisition/batch` (1 Hz), `biofizic/cmd/calibrate`.
- UI: arousal score / label, confidence (+ "· HR" channel tag), HR, motion, calibration spinner.
- `WatchStateRepository.uiState` (StateFlow) → Compose re-render.

## Key files
| File | Role |
|---|---|
| `SensorService.kt` | Foreground service; starts trackers, owns state, handles `ACTION_START/STOP/RECALIBRATE`, parses server messages, `requestProfileRecalibration` |
| `MqttSession.kt` | MQTT connect + subscribe (state, state/live, calibration/status) + publish |
| `PublishScheduler.kt` | Drives the 1 Hz publish loop |
| `MainActivity.kt` | Compose UI: arousal card, MetricRows, square icon buttons, `MoodQuestionnaire`, `CalibrationSpinner` |
| `WatchStateRepository.kt` | Single source of truth (volatile fields) → `UiState` StateFlow |
| `UiState.kt` | Immutable snapshot for Compose (arousal, confidence, dominantChannel, calibrationPhase, …) |

## Data flow
```
sensors ─▶ signal/ + acquisition/ ─▶ MqttSession.publish(acquisition/batch)
user tap ─▶ SensorService(ACTION_RECALIBRATE) ─▶ publish(cmd/calibrate) + phase="collecting"
server MQTT ─▶ MqttSession ─▶ SensorService.parse* ─▶ WatchStateRepository ─▶ UiState ─▶ MainActivity
```

## Depends on / Used by
- **Depends on:** `signal/`, `acquisition/`, Samsung Health SDK, Wear Compose, paho/Android MQTT.
- **Used by:** the user; the server (consumes its publishes).
- Calibration robustness: ignores `calibration/status` whose `ts` predates the recalibrate
  request (kills the retained "done" replayed on (re)subscribe).
