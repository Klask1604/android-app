# Biofizic watch app

This is the Wear OS app for Biofizic, a real-time physiological arousal estimator for
virtual reality. The app runs on a Samsung Galaxy Watch and does one job: it reads the
sensors and streams the raw signals over MQTT. It does not compute the arousal verdict.
That happens on the Python server, which lives in a separate repository.

## What the app does

The watch reads the Samsung Health sensors, does a light first pass on the raw beats,
bundles everything into one packet per second, and publishes it. It also subscribes to
the server state so it can show the current arousal on the watch face.

The flow on the device is:

1. Read the Samsung Health SDK trackers: heart rate and inter-beat intervals, PPG, skin
   temperature, and the accelerometer.
2. Filter the raw inter-beat intervals in `signal/IbiPipeline.kt`. This drops invalid
   beats and anything outside a plausible physiological range, before they reach the
   server.
3. Assemble one atomic packet per second in `acquisition/AcquisitionAssembler.kt`. The
   packet carries the beats collected since the last one, the motion statistics, the
   temperature, and a shared timestamp anchor so the server can line everything up to
   the same window.
4. Publish the packet on `biofizic/acquisition/batch`.
5. Subscribe to `biofizic/state/live` for the per-second value and to `biofizic/state`
   for the committed 30 second verdict, which also arrives on reconnect as a bootstrap.

The watch face shows arousal only: a 1 to 10 score and a Kubios label. The valence proxy
is experimental and lives only in the Grafana dashboards on the server.

The app does run a local HRV calculation on the device, in `HrvFeatureCalculator`, but
only to drive a signal-quality hint in the UI. The server HRV is the one that decides
the arousal.

## Requirements

- Samsung Galaxy Watch 4 or newer with the Samsung Health platform installed.
- The Samsung Health Sensor SDK, `samsung-health-sensor-api-1.4.1.aar`, placed in
  `app/libs/`.
- An MQTT broker reachable from the watch.
- Android Studio with the Android SDK.

The app targets `minSdk 30` and uses these permissions: body sensors, background body
sensors, internet, wake lock, foreground service, activity recognition, high sampling
rate sensors, and the Samsung Health read permissions for heart rate, HRV, skin
temperature, and related channels.

## Local setup

Copy the example file and create your own `local.properties` in the project root. It is
gitignored, so your broker address stays out of the repository:

```bash
cp local.properties.example local.properties
```

Edit it to point at your Android SDK and your broker:

```properties
sdk.dir=C\:\\Users\\YOU\\AppData\\Local\\Android\\Sdk
mqtt.broker.url=tcp://YOUR_BROKER_HOST:1883
```

Use the same broker address as `MQTT_BROKER` in the server's `.env`. Do not use
`localhost` for a physical watch; it has to be a host the watch can actually reach over
WiFi. If `mqtt.broker.url` is left unset, the build falls back to
`tcp://localhost:1883`.

## Build and install

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Tests

The JVM unit tests cover the atomic acquisition assembler: timestamp reconstruction for
the beats, the shared timestamp anchor, the drain semantics, and the motion-statistics
window cutoff.

```bash
./gradlew :app:testDebugUnitTest
```

## MQTT topics

Published by the watch:

| Topic | Rate | Content |
|---|---|---|
| `biofizic/acquisition/batch` | 1 Hz | One packet with IBI, PPG, motion stats, and HR |
| `biofizic/cmd/calibrate` | on demand | Baseline reset request |

Consumed by the watch:

| Topic | Purpose |
|---|---|
| `biofizic/state/live` | Arousal and Kubios label for the watch face, 1 Hz |
| `biofizic/state` | The retained 30 second verdict; arrives at once on reconnect |
| `biofizic/calibration/status` | Recalibration feedback |

To recalibrate the personal baseline, long-press the arousal gauge on the watch. That
publishes `biofizic/cmd/calibrate` and the server starts a fresh baseline.

## Project layout

```
app/src/main/java/com/doltu/biofizic/
  signal/IbiPipeline.kt              Beat filter and local RMSSD
  acquisition/AcquisitionAssembler.kt  Atomic one-second packet
  presentation/SensorService.kt      Foreground service, SDK plus MQTT
  presentation/MqttSession.kt        MQTT connection
  presentation/PublishScheduler.kt   One publish per second
  presentation/MainActivity.kt       Watch face UI in Compose
  presentation/WatchStateRepository.kt  State the UI reads
  presentation/UiState.kt            UI snapshot
```

Each of the `signal/`, `acquisition/`, and `presentation/` folders has its own
`ARCHITECTURE.md` with the exact input and output of that layer.

## Related documentation

The server repository holds the architecture docs for the processing pipeline, the
limitations write-up, and the dashboards.
