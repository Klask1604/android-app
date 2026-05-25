package com.doltu.biofizic.presentation

/** Snapshot for watch UI — updated via StateFlow from SensorService. */
data class UiState(
    val isRunning: Boolean = false,
    val isMqttConnected: Boolean = false,
    val lastHr: Int = 0,
    val arousalFused: Float = -1f,
    val arousal10: Int = -1,
    val valence10: Int = -1,
    val emotionLabel: String = "—",
    val arousalConfidence: Float = 0f,
    val motionGated: Boolean = false,
    val profileReady: Boolean = false,
    val signalOk: Boolean = false,
    val lastWindowSec: Double = 0.0,
    val calibrationPhase: String = "",
    val calibrationMessage: String = "",
)
