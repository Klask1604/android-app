package com.doltu.biofizic.presentation

import android.os.Handler

/**
 * Owns the five Runnables that drive periodic work on the Service's main
 * handler. Pulling them out keeps SensorService focused on lifecycle and
 * Samsung SDK plumbing.
 *
 * Each Runnable re-arms itself with `handler.postDelayed`. They stop on
 * their own when [isRunning] returns false, and [stop] additionally calls
 * `handler.removeCallbacks` to drop anything in flight.
 *
 * The intervals live in `WatchStateRepository` because they are mutated
 * from `updateDisplayState` (the Service tweaks them when the screen turns
 * on or off). The scheduler reads them through the lambdas at every tick so
 * a change is picked up on the next iteration.
 */
class PublishScheduler(
    private val handler: Handler,
    private val isRunning: () -> Boolean,
    private val mqttPublishIntervalMs: () -> Long,
    private val hrFlushIntervalMs: () -> Long,
    private val hrvPublishIntervalMs: () -> Long,
    private val liveWatchIntervalMs: Long,
    private val liveStreamEnabled: () -> Boolean,
    private val liveWatchEnabled: () -> Boolean,
    // Per-tick work the Service supplies as callbacks.
    private val onSdkFlush: () -> Unit,
    private val onHrFlush: () -> Unit,
    private val onPeriodicHrvLog: () -> Unit,
    private val onLiveBatch: () -> Unit,
    private val onStatusLog: () -> Unit,
) {

    private var sdkFlushRunning = false
    private var hrFlushRunning = false
    private var hrvPublishRunning = false
    private var liveWatchRunning = false
    private var mqttPublishRunning = false

    private val sdkFlushRunnable = object : Runnable {
        override fun run() {
            if (!isRunning()) { sdkFlushRunning = false; return }
            onSdkFlush()
            handler.postDelayed(this, mqttPublishIntervalMs())
        }
    }

    private val hrFlushRunnable = object : Runnable {
        override fun run() {
            if (!isRunning()) { hrFlushRunning = false; return }
            onHrFlush()
            handler.postDelayed(this, hrFlushIntervalMs())
        }
    }

    private val hrvPublishRunnable = object : Runnable {
        override fun run() {
            if (!isRunning()) { hrvPublishRunning = false; return }
            onPeriodicHrvLog()
            handler.postDelayed(this, hrvPublishIntervalMs())
        }
    }

    private val liveWatchRunnable = object : Runnable {
        override fun run() {
            if (!isRunning()) { liveWatchRunning = false; return }
            onLiveBatch()
            handler.postDelayed(this, liveWatchIntervalMs)
        }
    }

    private val mqttPublishRunnable = object : Runnable {
        override fun run() {
            if (!isRunning()) { mqttPublishRunning = false; return }
            onStatusLog()
            handler.postDelayed(this, mqttPublishIntervalMs())
        }
    }

    fun start() {
        if (!sdkFlushRunning) {
            sdkFlushRunning = true
            handler.post(sdkFlushRunnable)
        }
        if (!hrFlushRunning) {
            hrFlushRunning = true
            handler.post(hrFlushRunnable)
        }
        if (!hrvPublishRunning) {
            hrvPublishRunning = true
            // Delay the first HRV log by one interval so the rolling buffer
            // has time to fill before we start reporting on it.
            handler.postDelayed(hrvPublishRunnable, hrvPublishIntervalMs())
        }
        if (liveStreamEnabled() && liveWatchEnabled() && !liveWatchRunning) {
            liveWatchRunning = true
            handler.post(liveWatchRunnable)
        }
        if (!mqttPublishRunning) {
            mqttPublishRunning = true
            handler.post(mqttPublishRunnable)
        }
    }

    fun stop() {
        sdkFlushRunning = false
        hrFlushRunning = false
        hrvPublishRunning = false
        liveWatchRunning = false
        mqttPublishRunning = false
        handler.removeCallbacks(sdkFlushRunnable)
        handler.removeCallbacks(hrFlushRunnable)
        handler.removeCallbacks(hrvPublishRunnable)
        handler.removeCallbacks(liveWatchRunnable)
        handler.removeCallbacks(mqttPublishRunnable)
    }
}
