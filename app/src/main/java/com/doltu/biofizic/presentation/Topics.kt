package com.doltu.biofizic.presentation

/**
 * Single source of truth for the production MQTT topics on the watch.
 *
 * Organized by FLOW DIRECTION so each topic states its role in its name:
 *   - In.*  : what the watch SENDS to the engine (data, handshake, commands)
 *   - Out.* : what the watch SUBSCRIBES to (verdicts from the engine)
 *
 * Must match affectus/topics.py on the server. Research topics (legacy/test)
 * are not listed here.
 */
object Topics {
    object In {
        const val ACQUISITION = "biofizic/in/acquisition"  // sensor frames + 100 Hz PPG, 1 Hz
        const val HELLO = "biofizic/in/hello"              // capability handshake
        const val CMD_CALIBRATE = "biofizic/in/cmd/calibrate"
        const val CMD_FEEDBACK = "biofizic/in/cmd/feedback"
    }

    object Out {
        const val HELLO_ACK = "biofizic/out/hello/ack"
        const val AROUSAL = "biofizic/out/arousal"          // arousal verdict (retained)
        const val LIVE = "biofizic/out/live"                // arousal at 1 Hz
        const val EMOTION = "biofizic/out/emotion"          // Russell quadrant + emotions
        const val CALIBRATION = "biofizic/out/calibration"  // calibration status
    }
}
