package com.doltu.biofizic.presentation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Single source of truth for runtime state that lives outside the Service
 * lifecycle and crosses thread boundaries.
 *
 * Before this object, all of these fields sat on `SensorService.Companion`
 * as `@Volatile` properties, which made the Service a god object: the watch
 * UI, MQTT callbacks, the Samsung SDK listeners and the publish loop all
 * mutated it from different threads. Pulling the state out keeps the
 * Service focused on lifecycle while giving Compose a single observable
 * snapshot through `uiState`.
 *
 * The fields are still `@Volatile` (writes happen on SDK threads, the
 * Compose collector reads on main) but live in one isolated place that is
 * easy to inspect in a debugger and could be unit-tested in isolation.
 */
object WatchStateRepository {

    // ----- service lifecycle / connectivity -----
    @Volatile var isRunning: Boolean = false
    @Volatile var isMqttConnected: Boolean = false
    @Volatile var activeSensors: Int = 0
    @Volatile var msgCount: Long = 0L

    // ----- per-tracker availability -----
    @Volatile var ppgActive: Boolean = false
    @Volatile var accActive: Boolean = false
    @Volatile var gyroActive: Boolean = false
    @Volatile var skinTempActive: Boolean = false
    @Volatile var ibiActive: Boolean = false

    // ----- last sensor readings -----
    @Volatile var lastHr: Int = 0
    @Volatile var lastRmssd: Double = 0.0
    @Volatile var lastSkinTempC: Double = 0.0
    @Volatile var lastAmbientTempC: Double = 0.0
    @Volatile var lastWindowSec: Double = 0.0
    @Volatile var signalOk: Boolean = false

    // ----- publish intervals (mutated by display-state changes) -----
    @Volatile var mqttPublishIntervalMs: Long = 1_000L
    @Volatile var hrvPublishIntervalMs: Long = 30_000L
    // Flush the Samsung HR tracker every second so IBI beats are delivered at
    // ~1 Hz instead of arriving in ~4-5 s bursts. acquisition/batch is already
    // published every second; this stops most batches from carrying empty IBI.
    @Volatile var hrFlushIntervalMs: Long = 1_000L
    @Volatile var liveWatchEnabled: Boolean = true
    @Volatile var liveStreamEnabled: Boolean = true

    // ----- environment flags -----
    @Volatile var displayOn: Boolean = true
    @Volatile var backgroundSensorsGranted: Boolean = true

    // ----- server-side decision arriving on biofizic/state/live -----
    @Volatile var arousalFused: Float = -1f
    @Volatile var arousal10: Int = -1
    @Volatile var arousalConfidence: Float = 0f
    @Volatile var dominantChannel: String = "hrv"
    @Volatile var arousalLabel: String = "-"
    @Volatile var motionGated: Boolean = false
    @Volatile var profileReady: Boolean = false
    @Volatile var calibrationPhase: String = ""
    @Volatile var calibrationMessage: String = ""
    // "preliminary" | "calibrated", see UiState.decisionFidelity.
    @Volatile var decisionFidelity: String = "calibrated"
    // 2D emotion verdict from the server (see UiState.emotionVerdict).
    @Volatile var emotionVerdict: String = "-"
    // Raw arousal/valence scores shown small under the verdict (see UiState).
    @Volatile var emotionScores: String = ""
    // The verdict's own confidence (distinct from arousalConfidence).
    @Volatile var emotionConfidence: Float = 0f

    // ----- Compose-visible snapshot -----
    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    /** Push the current field values into the StateFlow so Compose re-renders. */
    fun syncUiState() {
        _uiState.value = UiState(
            isRunning = isRunning,
            isMqttConnected = isMqttConnected,
            lastHr = lastHr,
            arousalFused = arousalFused,
            arousal10 = arousal10,
            arousalLabel = arousalLabel,
            arousalConfidence = arousalConfidence,
            dominantChannel = dominantChannel,
            motionGated = motionGated,
            profileReady = profileReady,
            signalOk = signalOk,
            lastWindowSec = lastWindowSec,
            calibrationPhase = calibrationPhase,
            calibrationMessage = calibrationMessage,
            decisionFidelity = decisionFidelity,
            emotionVerdict = emotionVerdict,
            emotionScores = emotionScores,
            emotionConfidence = emotionConfidence,
        )
    }
}
