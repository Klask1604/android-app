package com.doltu.biofizic.presentation

/** Sensor reading with Samsung SDK or wall-clock timestamp (epoch ms). */
data class TimedSample(val ts: Long, val value: Double)
