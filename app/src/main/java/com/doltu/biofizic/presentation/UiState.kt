package com.doltu.biofizic.presentation

/** Snapshot for watch UI — updated via StateFlow from SensorService. */
data class UiState(
    val isRunning: Boolean = false,
    val isMqttConnected: Boolean = false,
    val lastHr: Int = 0,
    val arousalFused: Float = -1f,
    val arousal10: Int = -1,
    val arousalLabel: String = "—",
    val arousalConfidence: Float = 0f,
    // Which signal drives the verdict: "hrv" (still, precise), "hr" (motion,
    // robust), "blend", "none". Lets the UI show confidence is HR-based in motion.
    val dominantChannel: String = "hrv",
    val motionGated: Boolean = false,
    val profileReady: Boolean = false,
    val signalOk: Boolean = false,
    val lastWindowSec: Double = 0.0,
    val calibrationPhase: String = "",
    val calibrationMessage: String = "",
    // Server-side honesty flag: "preliminary" means arousal_10 came from the
    // Kubios population zones (no personal baseline yet) and confidence is
    // capped at 0.5; "calibrated" means it came from the personal-z CDF.
    // UI uses this to show a small badge so a preliminary verdict isn't
    // confused with a fully calibrated one.
    val decisionFidelity: String = "calibrated",
)
