# Biofizic — Android (Galaxy Watch)

Wear OS companion app for **sensor acquisition only**. HRV, baseline, and arousal are computed on the Python server (`Licenta/`).

## Role in the stack

The watch **does not** produce the final affect verdict. It:

1. Reads Samsung Health SDK trackers (HR + IBI, PPG, skin temp, accelerometer)
2. Filters raw IBI beats (`IbiSignalFilter` in `signal/IbiPipeline.kt`)
3. Publishes 1 Hz **`biofizic/acquisition/batch`** (schema v2: IBI + PPG + motion + HR)
4. Subscribes to `biofizic/state/live` (1 Hz UI, hysteresis-smoothed by the server) and `biofizic/state` (retained 30 s epoch, doubles as reconnect bootstrap)

```
Samsung SDK → SensorService → acquisition/batch v2 → server compute-engine
                    ↑
            state/live (1 Hz) + combined (bootstrap)
```

Watch UI shows **arousal only** (A X/10 + Kubios label). Experimental valence proxy is Grafana-only on the server.

## Requirements

- Samsung Galaxy Watch 4+ with Samsung Health Platform
- `samsung-health-sensor-api-*.aar` in `app/libs/`
- MQTT broker reachable from the watch
- Permissions: body sensors, background body sensors, internet, wake lock

## Configure MQTT

Create `local.properties` in the project root (gitignored):

```properties
sdk.dir=C\:\\Users\\YOU\\AppData\\Local\\Android\\Sdk
mqtt.broker.url=tcp://YOUR_BROKER_HOST:1883
```

Default if unset: `tcp://localhost:1883` (via `R.string.mqtt_broker_url` in `build.gradle.kts`).

## Build & install

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## MQTT topics published

| Topic | Rate | Content |
|-------|------|---------|
| `biofizic/acquisition/batch` | 1 Hz | Schema v2 with IBI, PPG, motion stats and HR in a single payload |
| `biofizic/cmd/calibrate` | on demand | Baseline reset request |

## MQTT topics consumed

| Topic | Purpose |
|-------|---------|
| `biofizic/state/live` | Arousal + Kubios label for the watch face (1 Hz, hysteresis-smoothed) |
| `biofizic/state` | Retained 30 s epoch decision; arrives immediately on reconnect as bootstrap |
| `biofizic/calibration/status` | Baseline recalibration feedback |

Recalibrate the personal baseline by long-pressing the arousal gauge on the watch. That sends `biofizic/cmd/calibrate`.

## Local HRV on watch (not used for decisions)

`HrvFeatureCalculator` runs on-device for `signalOk` UI hints only. **Server HRV is authoritative** for arousal.

## Project structure

```
app/src/main/java/com/doltu/biofizic/
  presentation/SensorService.kt   Foreground service, SDK + MQTT
  presentation/MainActivity.kt    Watch face UI (Compose)
  presentation/UiState.kt         UI snapshot
  signal/IbiPipeline.kt           IBI filter + local RMSSD
```

## Related documentation

- Server: `Licenta/docs/E2E_ARCHITECTURE.md`, `Licenta/docs/THESIS_LIMITATIONS.md`
