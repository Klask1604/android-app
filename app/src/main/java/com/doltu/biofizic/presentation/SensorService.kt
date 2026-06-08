package com.doltu.biofizic.presentation

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.*
import android.util.Log
import com.doltu.biofizic.BuildConfig
import com.doltu.biofizic.R
import com.doltu.biofizic.acquisition.AcquisitionAssembler
import com.doltu.biofizic.signal.HrvFeatureCalculator
import com.doltu.biofizic.signal.IbiSignalFilter
import com.doltu.biofizic.signal.IbiWindowEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONException
import org.json.JSONObject
import com.samsung.android.service.health.tracking.ConnectionListener
import com.samsung.android.service.health.tracking.HealthTracker
import com.samsung.android.service.health.tracking.HealthTrackerException
import com.samsung.android.service.health.tracking.HealthTrackingService
import com.samsung.android.service.health.tracking.data.DataPoint
import com.samsung.android.service.health.tracking.data.HealthTrackerType
import com.samsung.android.service.health.tracking.data.PpgType
import com.samsung.android.service.health.tracking.data.ValueKey
import androidx.core.content.ContextCompat
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

class SensorService : Service(), SensorEventListener {

    // All per-stream buffers, atomic-sync helpers and the JSON build for
    // acquisition/batch v2 live in AcquisitionAssembler. The Service only
    // owns lifecycle, the Samsung SDK glue and the MQTT publish loop.
    private val assembler = AcquisitionAssembler()

    /** Format a double for log lines using a US decimal point. */
    private fun jsonFloat(value: Double, decimals: Int): String =
        String.format(Locale.US, "%.${decimals}f", value)

    // ── Android sensors ──
    private lateinit var sensorManager: SensorManager

    // ── MQTT ──
    private val BROKER_URL: String by lazy { getString(R.string.mqtt_broker_url) }
    private val CLIENT_ID = "GalaxyWatch7"
    private val mqttSession: MqttSession by lazy {
        MqttSession(
            brokerUrl = BROKER_URL,
            clientId = CLIENT_ID,
            tag = TAG,
            onEpochState = { parseEpochStateMessage(it) },
            onStateLive = { parseStateLiveMessage(it) },
            onEmotionState = { parseEmotionStateMessage(it) },
            onCalibrationStatus = { parseCalibrationStatus(it) },
            onHelloAck = { parseHelloAck(it) },
            onMessagePublished = { msgCount++ },
        )
    }

    // Capability handshake state. The server validates the sensors we announce
    // and replies on biofizic/hello/ack; we only start streaming once it says
    // "ok". `handshakeOk` gates the publish loops so a device the server cannot
    // classify (e.g. skin-temp only) never floods the broker with unusable data.
    // Handshake state. handshakeOk: server replied "ok". handshakeAckReceived:
    // server replied at all (ok OR error). handshakeSentAtMs: when we last
    // announced, used for the fail-open timeout (stream anyway if no ack) and
    // the slow retry (re-announce until the server answers).
    @Volatile private var handshakeOk: Boolean = false
    @Volatile private var handshakeAckReceived: Boolean = false
    @Volatile private var handshakeRejected: Boolean = false
    @Volatile private var handshakeReason: String = ""
    @Volatile private var handshakeSentAtMs: Long = 0L
    @Volatile private var streamingStarted: Boolean = false

    // ── Samsung Health SDK ──
    private var healthTrackingService: HealthTrackingService? = null
    private var heartRateTracker: HealthTracker? = null
    private var skinTempTracker: HealthTracker? = null
    private var accSdkTracker: HealthTracker? = null
    private var ppgOnDemandTracker: HealthTracker? = null  // 100 Hz raw PPG (sole optical tracker)
    private val handler = Handler(Looper.getMainLooper())

    // ── Wake lock ──
    private lateinit var wakeLock: PowerManager.WakeLock

    // ── Watchdog thread ──
    private var watchdogThread: Thread? = null

    // ══════════════════════════════════════════
    //  Companion: thin facade over WatchStateRepository so existing call sites
    //  (`SensorService.isRunning`, `MainActivity` Compose collector, etc.)
    //  keep working without changes. All real state lives in the repository.
    // ══════════════════════════════════════════
    companion object {
        const val ACTION_START = "com.doltu.biofizic.ACTION_START"
        const val ACTION_STOP = "com.doltu.biofizic.ACTION_STOP"
        const val ACTION_RECALIBRATE = "com.doltu.biofizic.ACTION_RECALIBRATE"
        // User emotion feedback: a Russell quadrant the user tapped to label how
        // they feel right now. Pairs server-side with the recent PPG features.
        const val ACTION_FEEDBACK = "com.doltu.biofizic.ACTION_FEEDBACK"
        const val EXTRA_FEEDBACK_QUADRANT = "com.doltu.biofizic.EXTRA_FEEDBACK_QUADRANT"
        // Self-reported arousal in [0,1] from the watch questionnaire; anchors
        // where the new baseline sits on the arousal scale.
        const val EXTRA_REPORTED_AROUSAL = "com.doltu.biofizic.EXTRA_REPORTED_AROUSAL"
        const val EXTRA_REACTIVITY = "com.doltu.biofizic.EXTRA_REACTIVITY"

        // 100 Hz on-demand PPG: SINGURUL tracker optic acum. Forma de unda densa
        // intra direct in acquisition batch (nu mai e topic separat). Pe GW7 ruleaza
        // in paralel cu HR/IBI fara sa infometeze pipeline-ul HRV (verificat 2026-05:
        // arousal + valenta amandoua live).
        const val PUBLISH_PPG_ONDEMAND = true

        // Start the PPG_ON_DEMAND tracker (100 Hz). Gated together with
        // PUBLISH_PPG_ONDEMAND.
        const val START_PPG_ON_DEMAND = true

        // Last moment the finger was on the button, drives ONLY the live contact
        // indicator (green/orange), never the progress or the stop condition.
        @Volatile var lastLeadOnMs: Long = 0L

        // Pending self-report to send as a calibration once MQTT is connected.
        @Volatile var pendingReportedArousal: Double = Double.NaN
        @Volatile var pendingReactivity: String? = null

        // When we last asked for a (re)calibration. Used to ignore the RETAINED
        // "done" status that the broker replays on every (re)subscribe: any
        // calibration/status whose ts predates our request is stale and must not
        // clear the spinner. 0 = never asked (let the bootstrap message through).
        @Volatile var calibrateRequestedAtMs: Long = 0L
        // Skew margin (watch vs server clock) so a FRESH status is never dropped.
        const val CALIBRATION_STALE_MARGIN_MS = 10_000L

        // Handshake fail-open: start streaming if no ack arrives within this long
        // (server briefly down / message lost). Re-announce every retry interval
        // until the server answers. Only an explicit "error" ack stops streaming.
        const val HANDSHAKE_FAIL_OPEN_MS = 10_000L
        const val HANDSHAKE_RETRY_MS = 60_000L

        private val state = WatchStateRepository

        // Lifecycle / connectivity
        var isRunning: Boolean
            get() = state.isRunning
            set(value) { state.isRunning = value }
        var isMqttConnected: Boolean
            get() = state.isMqttConnected
            set(value) { state.isMqttConnected = value }
        var activeSensors: Int
            get() = state.activeSensors
            set(value) { state.activeSensors = value }
        var msgCount: Long
            get() = state.msgCount
            set(value) { state.msgCount = value }

        // Per-tracker availability
        var ppgActive: Boolean
            get() = state.ppgActive
            set(value) { state.ppgActive = value }
        var accActive: Boolean
            get() = state.accActive
            set(value) { state.accActive = value }
        var gyroActive: Boolean
            get() = state.gyroActive
            set(value) { state.gyroActive = value }
        var skinTempActive: Boolean
            get() = state.skinTempActive
            set(value) { state.skinTempActive = value }
        var ibiActive: Boolean
            get() = state.ibiActive
            set(value) { state.ibiActive = value }

        // Last sensor readings
        var lastHr: Int
            get() = state.lastHr
            set(value) { state.lastHr = value }
        var lastRmssd: Double
            get() = state.lastRmssd
            set(value) { state.lastRmssd = value }
        var lastSkinTempC: Double
            get() = state.lastSkinTempC
            set(value) { state.lastSkinTempC = value }
        var lastAmbientTempC: Double
            get() = state.lastAmbientTempC
            set(value) { state.lastAmbientTempC = value }
        var lastWindowSec: Double
            get() = state.lastWindowSec
            set(value) { state.lastWindowSec = value }
        var signalOk: Boolean
            get() = state.signalOk
            set(value) { state.signalOk = value }

        // Publish intervals
        var mqttPublishIntervalMs: Long
            get() = state.mqttPublishIntervalMs
            set(value) { state.mqttPublishIntervalMs = value }
        var hrvPublishIntervalMs: Long
            get() = state.hrvPublishIntervalMs
            set(value) { state.hrvPublishIntervalMs = value }
        var hrFlushIntervalMs: Long
            get() = state.hrFlushIntervalMs
            set(value) { state.hrFlushIntervalMs = value }
        var liveWatchEnabled: Boolean
            get() = state.liveWatchEnabled
            set(value) { state.liveWatchEnabled = value }
        var liveStreamEnabled: Boolean
            get() = state.liveStreamEnabled
            set(value) { state.liveStreamEnabled = value }
        private const val LIVE_WATCH_INTERVAL_MS = 1_000L

        // Environment
        var displayOn: Boolean
            get() = state.displayOn
            set(value) { state.displayOn = value }
        var backgroundSensorsGranted: Boolean
            get() = state.backgroundSensorsGranted
            set(value) { state.backgroundSensorsGranted = value }

        // Server-side decision values
        var arousalFused: Float
            get() = state.arousalFused
            set(value) { state.arousalFused = value }
        var arousal10: Int
            get() = state.arousal10
            set(value) { state.arousal10 = value }
        var arousalConfidence: Float
            get() = state.arousalConfidence
            set(value) { state.arousalConfidence = value }
        var dominantChannel: String
            get() = state.dominantChannel
            set(value) { state.dominantChannel = value }
        var arousalLabel: String
            get() = state.arousalLabel
            set(value) { state.arousalLabel = value }
        var motionGated: Boolean
            get() = state.motionGated
            set(value) { state.motionGated = value }
        var profileReady: Boolean
            get() = state.profileReady
            set(value) { state.profileReady = value }
        var calibrationPhase: String
            get() = state.calibrationPhase
            set(value) { state.calibrationPhase = value }
        var calibrationMessage: String
            get() = state.calibrationMessage
            set(value) { state.calibrationMessage = value }
        var decisionFidelity: String
            get() = state.decisionFidelity
            set(value) { state.decisionFidelity = value }
        var emotionVerdict: String
            get() = state.emotionVerdict
            set(value) { state.emotionVerdict = value }
        var emotionScores: String
            get() = state.emotionScores
            set(value) { state.emotionScores = value }
        var emotionConfidence: Float
            get() = state.emotionConfidence
            set(value) { state.emotionConfidence = value }

        // Compose snapshot
        val uiState: StateFlow<UiState> get() = state.uiState
        fun syncUiState() = state.syncUiState()

        // Min beats and covered seconds required before the watch UI claims the
        // signal is good. Server-side thresholds are stricter and authoritative;
        // these only gate the local "signalOk" indicator.
        private const val MIN_IBI_FOR_HRV = 8
        private const val MIN_WINDOW_SEC_FOR_SIGNAL = 6.0
        private const val WAKE_LOCK_TIMEOUT_MS = 4 * 60 * 60 * 1000L
    }

    private var accScaleLogRemaining = 5
    private var screenReceiverRegistered = false
    private var displayOnLocal = true
    private val timestampProbeEnabled = BuildConfig.DEBUG
    private var lastIbiSourceLogMs = 0L

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> updateDisplayState(true)
                Intent.ACTION_SCREEN_OFF -> updateDisplayState(false)
            }
        }
    }

    @Volatile private var lastHrDataMs = 0L
    @Volatile private var lastHrBatchPoints = 0
    @Volatile private var lastHrBatchIbi = 0
    private var lastHrBatchLogMs = 0L
    private var lastHrPulseLogMs = 0L
    private var inForeground = false

    // Owns the 5 periodic Runnables that used to live inline in this class.
    // Constructed lazily because it captures `this` via callbacks.
    private val publishScheduler: PublishScheduler by lazy {
        PublishScheduler(
            handler = handler,
            isRunning = { isRunning },
            mqttPublishIntervalMs = { mqttPublishIntervalMs },
            hrFlushIntervalMs = { hrFlushIntervalMs },
            hrvPublishIntervalMs = { hrvPublishIntervalMs },
            liveWatchIntervalMs = LIVE_WATCH_INTERVAL_MS,
            liveStreamEnabled = { liveStreamEnabled },
            liveWatchEnabled = { liveWatchEnabled },
            onSdkFlush = {
                ppgOnDemandTracker?.flush()
                skinTempTracker?.flush()
                accSdkTracker?.flush()
            },
            onHrFlush = {
                heartRateTracker?.flush()
                logHrBatchIfDue()
                logHrPulse()
            },
            onPeriodicHrvLog = { updateSignalStatus() },
            onLiveBatch = { publishSensorBatches() },
            onStatusLog = { logPeriodicStatus() },
        )
    }

    private fun updateDisplayState(on: Boolean) {
        displayOnLocal = on
        displayOn = on
        hrvPublishIntervalMs = 30_000L
        mqttPublishIntervalMs = 1_000L
        hrFlushIntervalMs = 1_000L  // deliver IBI every ~1 s, not in 4-5 s bursts
        Log.i(
            TAG,
            "Ecran ${if (on) "ON" else "OFF"} (signal=${hrvPublishIntervalMs}ms hrFlush=${hrFlushIntervalMs}ms)"
        )
    }

    private fun registerScreenReceiver() {
        if (screenReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        ContextCompat.registerReceiver(
            this, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED
        )
        screenReceiverRegistered = true
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        updateDisplayState(pm.isInteractive)
    }

    private fun unregisterScreenReceiver() {
        if (!screenReceiverRegistered) return
        try {
            unregisterReceiver(screenReceiver)
        } catch (_: Exception) {
        }
        screenReceiverRegistered = false
    }

    private fun logTrackingReadiness() {
        backgroundSensorsGranted = ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.BODY_SENSORS_BACKGROUND
        ) == PackageManager.PERMISSION_GRANTED
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val batteryOk = pm.isIgnoringBatteryOptimizations(packageName)
        Log.i(
            TAG,
            "Pregătire tracking: ecran=${if (displayOnLocal) "ON" else "OFF"}, " +
                "BODY_SENSORS_BACKGROUND=$backgroundSensorsGranted, batteryOptIgnored=$batteryOk"
        )
        if (!backgroundSensorsGranted) {
            Log.w(TAG, "Grant BODY_SENSORS_BACKGROUND for measurements with the screen off")
        }
    }

    private var lastStatusLogMs = 0L

    private fun logPeriodicStatus() {
        flushPendingCalibration()  // runs every tick (cheap) so a self-report is
        // sent as soon as MQTT comes up after Start
        val now = System.currentTimeMillis()
        if (now - lastStatusLogMs < 15_000L) return
        lastStatusLogMs = now
        backgroundSensorsGranted = ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.BODY_SENSORS_BACKGROUND
        ) == PackageManager.PERMISSION_GRANTED
        val snapshot = assembler.ibiWindowSnapshot()
        val liveHrv = HrvFeatureCalculator.compute(snapshot)
        val liveRmssd = liveHrv?.rmssd ?: 0.0
        val liveWin = liveHrv?.windowSec ?: 0.0
        val liveOk = snapshot.size >= MIN_IBI_FOR_HRV && liveWin >= MIN_WINDOW_SEC_FOR_SIGNAL
        Log.i(
            TAG,
            "Status: screen=${if (displayOn) "ON" else "OFF"}, bg=$backgroundSensorsGranted, " +
                "ibiWindow=${snapshot.size}, windowSec=${"%.0f".format(liveWin)}, " +
                "signalOk=$liveOk, rmssd=${"%.1f".format(liveRmssd)}, mqtt=$msgCount"
        )
    }

    private fun startPublishLoops() = publishScheduler.start()

    private fun stopPublishLoops() = publishScheduler.stop()

    // ══════════════════════════════════════════
    //  Samsung Health SDK: connection
    // ══════════════════════════════════════════
    private val connectionListener = object : ConnectionListener {
        override fun onConnectionSuccess() {
            Log.i(TAG, "Samsung Health SDK conectat")
            buildSensorInventory(probeAccessibility = true)
            startSdkTrackers()
        }
        override fun onConnectionEnded() {
            Log.w(TAG, "Samsung Health SDK deconectat")
            resetSdkState()
        }
        override fun onConnectionFailed(e: HealthTrackerException) {
            Log.e(TAG, "Samsung Health SDK eroare: ${e.message}")
            resetSdkState()
        }
    }

    // ══════════════════════════════════════════
    //  Samsung Health SDK: tracker listeners
    // ══════════════════════════════════════════

    /** HR + IBI */
    private val heartRateListener = object : HealthTracker.TrackerEventListener {
        override fun onDataReceived(dataPoints: List<DataPoint>) {
            val recvMs = System.currentTimeMillis()
            val gapMs = if (lastHrDataMs > 0L) recvMs - lastHrDataMs else 0L
            lastHrDataMs = recvMs
            var batchIbi = 0
            for (dp in dataPoints) {
                logTimestampProbe("HR", dp.timestamp, recvMs)
                val hr = dp.getValue(ValueKey.HeartRateSet.HEART_RATE)
                val status = dp.getValue(ValueKey.HeartRateSet.HEART_RATE_STATUS)

                if (status == 1 && hr > 0) {
                    lastHr = hr
                }

                val ibiList = dp.getValue(ValueKey.HeartRateSet.IBI_LIST)
                val ibiStatusList = dp.getValue(ValueKey.HeartRateSet.IBI_STATUS_LIST)
                if (ibiList != null && status == 1) {
                    val hrForNorm = if (hr > 0) hr else lastHr
                    // Evaluate EVERY beat (keep rejected ones too) so timestamp
                    // reconstruction can advance the clock over rejected beats and
                    // leave a visible gap, otherwise the two surviving neighbours
                    // look consecutive and RMSSD is inflated across the dropped beat.
                    val evals = ArrayList<IbiSignalFilter.BeatEval>(ibiList.size)
                    for (i in ibiList.indices) {
                        evals.add(
                            IbiSignalFilter.evaluateBeat(
                                ibiList[i],
                                ibiStatusList?.getOrNull(i),
                                hrForNorm,
                            )
                        )
                    }
                    val (durations, timestamps, source) =
                        assembler.buildIbiTimestampsWithGaps(evals, dp.timestamp, recvMs)
                    if (durations.isNotEmpty()) {
                        logIbiTimestampDecision(dp.timestamp, recvMs, source, durations.size)
                        val entries = ArrayList<IbiWindowEntry>(durations.size)
                        for (i in durations.indices) {
                            entries.add(IbiWindowEntry(durations[i], timestamps[i], source))
                        }
                        assembler.addIbiBatch(entries)
                        batchIbi += entries.size
                    }
                }
            }
            lastHrBatchPoints = dataPoints.size
            lastHrBatchIbi = batchIbi
            if (gapMs >= 3_000L || batchIbi > 0) {
                logHrBatch(gapMs, dataPoints.size, batchIbi)
            }
        }
        override fun onFlushCompleted() {
            logHrBatchIfDue(force = true)
        }
        override fun onError(e: HealthTracker.TrackerError) {
            Log.w(TAG, "HR tracker error: ${e.name}, restart in 3s")
            ibiActive = false
            heartRateTracker?.unsetEventListener()
            heartRateTracker = null
            handler.postDelayed({
                if (isRunning) restartHrTracker()
            }, 3_000L)
        }
    }


    /** PPG on-demand @ 100 Hz, the sole optical tracker. Dense pulse waveform for
     *  the morphology (valence) path, drained into the acquisition batch. */
    private val ppgOnDemandListener = object : HealthTracker.TrackerEventListener {
        override fun onDataReceived(dataPoints: List<DataPoint>) {
            // Buffer at 100 Hz; the publish loop flushes ONE aggregated message
            // per tick (~1 s) so the radio wakes once/s instead of ~70x/s. The
            // sensor still samples at 100 Hz, so valence is unaffected.
            bufferPpgOnDemand(dataPoints)
        }
        override fun onFlushCompleted() {}
        override fun onError(e: HealthTracker.TrackerError) {
            Log.w(TAG, "PPG on-demand eroare: ${e.name}")
        }
    }

    /** Skin temperature */
    private val skinTempListener = object : HealthTracker.TrackerEventListener {
        override fun onDataReceived(dataPoints: List<DataPoint>) {
            enqueueSkinTemp(dataPoints)
        }
        override fun onFlushCompleted() {}
        override fun onError(e: HealthTracker.TrackerError) {
            Log.e(TAG, "SkinTemp tracker eroare: ${e.name}")
            skinTempActive = false
        }
    }

    /** Accelerometer SDK – raw @ 25 Hz */
    private val accSdkListener = object : HealthTracker.TrackerEventListener {
        override fun onDataReceived(dataPoints: List<DataPoint>) {
            enqueueAcc(dataPoints)
        }
        override fun onFlushCompleted() {}
        override fun onError(e: HealthTracker.TrackerError) {
            Log.w(TAG, "ACC SDK tracker error: ${e.name}, restart in 5s")
            accActive = false
            accSdkTracker?.unsetEventListener()
            accSdkTracker = null
            handler.postDelayed({
                if (isRunning) restartAccTracker()
            }, 5_000L)
        }
    }

    // ══════════════════════════════════════════
    //  Service lifecycle
    // ══════════════════════════════════════════
    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Service onCreate (no startForeground, only from ACTION_START)")
        acquireWakeLock()
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
    }

    /**
     * Android 12+: startForeground() from onCreate / boot / alarm crashes with
     * ForegroundServiceStartNotAllowedException. Call it only from onStartCommand
     * after startForegroundService() from MainActivity.
     */
    private fun promoteToForeground(notificationId: Int, contentText: String): Boolean {
        if (inForeground) return true
        return try {
            val notification = buildNotification(contentText)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    notificationId,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
                )
            } else {
                startForeground(notificationId, notification)
            }
            inForeground = true
            Log.i(TAG, "Foreground service activ")
            true
        } catch (e: Exception) {
            Log.e(
                TAG,
                "startForeground respins (${e.javaClass.simpleName}): ${e.message} — " +
                    "deschide app și apasă Start"
            )
            false
        }
    }

    private fun leaveForeground() {
        if (!inForeground) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
        inForeground = false
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Log.i(TAG, "ACTION_STOP primit")
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                if (!promoteToForeground(1, "HR · PPG · ACC · Gyro · Temp")) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                // Plain start NEVER recalibrates: the persisted baseline is kept
                // and accumulates. Recalibration happens only via ACTION_RECALIBRATE
                // (the yellow button), so a start no longer arms a pending profile.
                if (!isRunning) {
                    Log.i(TAG, "Pornire tracking (ACTION_START)")
                    isRunning = true
                    syncUiState()
                    startAllTracking()
                }
            }
            ACTION_RECALIBRATE -> {
                val reported = intent.getDoubleExtra(EXTRA_REPORTED_AROUSAL, Double.NaN)
                val reactivity = intent.getStringExtra(EXTRA_REACTIVITY)
                if (isRunning && isMqttConnected) {
                    requestProfileRecalibration(reported, reactivity)
                } else {
                    // Defer until MQTT connects.
                    pendingReportedArousal = reported
                    pendingReactivity = reactivity
                    Log.w(TAG, "Recalibration deferred: MQTT inactive")
                }
                return START_STICKY
            }
            ACTION_FEEDBACK -> {
                val quadrant = intent.getStringExtra(EXTRA_FEEDBACK_QUADRANT)
                if (quadrant != null) sendEmotionFeedback(quadrant)
                return START_STICKY
            }
            else -> {
                // Automatic START_STICKY restart (null intent) or boot: resume tracking
                Log.i(TAG, "onStartCommand restart (action=${intent?.action}), reia tracking")
                if (!promoteToForeground(1, "HR · PPG · ACC · Gyro · Temp")) {
                    Log.w(TAG, "Restart: startForeground failed, stopping")
                    stopSelf()
                    return START_NOT_STICKY
                }
                if (!isRunning) {
                    isRunning = true
                    syncUiState()
                    startAllTracking()
                }
            }
        }
        return START_STICKY
    }

    private fun startAllTracking() {
        registerScreenReceiver()
        logTrackingReadiness()
        buildAndroidSensorInventory()
        // Android sensors: Gyro only (step counter removed, unused in thesis pipeline)
        registerAndroidSensors()

        // MQTT + Samsung SDK pe thread separat
        Thread {
            connectMqtt()
            try {
                healthTrackingService = HealthTrackingService(connectionListener, this)
                healthTrackingService?.connectService()
                Log.i(TAG, "Samsung SDK conectare initiata")
            } catch (e: Exception) {
                Log.e(TAG, "Samsung SDK init eroare: ${e.message}")
            }
            startWatchdog()
        }.start()
    }

    // ══════════════════════════════════════════
    //  Inventar senzori (Android + Samsung SDK)
    // ══════════════════════════════════════════

    private fun samsungCapabilities(): List<HealthTrackerType> = try {
        healthTrackingService
            ?.trackingCapability
            ?.supportHealthTrackerTypes ?: emptyList()
    } catch (e: Exception) {
        Log.e(TAG, "Nu pot citi capabilities: ${e.message}")
        emptyList()
    }

    /**
     * The capability names this watch announces to the server, in the server's
     * contract vocabulary (affectus.contract.Capability). Derived from the
     * trackers that actually started, not from a hardcoded device model: IBI +
     * HR come from the heart-rate tracker, MOTION from the accelerometer,
     * SKIN_TEMP from the skin-temperature tracker, PPG only when raw PPG is
     * published. The server replies on biofizic/hello/ack whether this set is
     * enough to classify arousal.
     */
    private fun capabilityNames(): List<String> {
        val caps = mutableListOf<String>()
        if (heartRateTracker != null) { caps.add("ibi"); caps.add("hr") }
        if (accSdkTracker != null || hasAndroidAccelerometer()) caps.add("motion")
        if (skinTempTracker != null) caps.add("temp")
        // "ppg" = forma de unda densa 100 Hz (de la PPG_ON_DEMAND, singurul tracker optic).
        if (AcquisitionAssembler.PUBLISH_RAW_PPG && ppgOnDemandTracker != null) caps.add("ppg")
        return caps
    }

    private fun hasAndroidAccelerometer(): Boolean =
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null

    private fun buildAndroidSensorInventory() {
        val lines = StringBuilder()
        lines.appendLine("Android (SensorManager):")
        val all = sensorManager.getSensorList(Sensor.TYPE_ALL)
        if (all.isEmpty()) {
            lines.appendLine("  (niciun senzor)")
        } else {
            for (s in all) {
                val marker = when (s.type) {
                    Sensor.TYPE_GYROSCOPE -> " ← folosit"
                    else -> ""
                }
                lines.appendLine("  ${shortTypeName(s.type)}: ${s.name}$marker")
            }
        }
        val report = lines.toString().trimEnd()
        Log.d(TAG, "Inventar Android:\n$report")
    }

    private fun buildSensorInventory(@Suppress("UNUSED_PARAMETER") probeAccessibility: Boolean) {
        val androidLines = StringBuilder()
        androidLines.appendLine("Android (SensorManager):")
        val all = sensorManager.getSensorList(Sensor.TYPE_ALL)
        if (all.isEmpty()) {
            androidLines.appendLine("  (niciun senzor)")
        } else {
            for (s in all) {
                val marker = when (s.type) {
                    Sensor.TYPE_GYROSCOPE -> " ← folosit"
                    else -> ""
                }
                androidLines.appendLine("  ${shortTypeName(s.type)}: ${s.name}$marker")
            }
        }
        val capabilities = samsungCapabilities()
        val lines = StringBuilder(androidLines.toString().trimEnd())
        lines.appendLine()
        lines.appendLine()
        lines.appendLine("Samsung SDK (capabilities=${capabilities.size}):")
        if (healthTrackingService == null) {
            lines.appendLine("  SDK neconectat")
        } else if (capabilities.isEmpty()) {
            lines.appendLine("  (empty list)")
        } else {
            for (type in HealthTrackerType.values()) {
                val tag = if (type in capabilities) "available" else "unavailable"
                lines.appendLine("  $type: $tag")
            }
            lines.appendLine()
            lines.appendLine("Acces la Start: getHealthTracker()")
        }
        Log.i(TAG, "── Inventar senzori ──\n${lines.toString().trimEnd()}")
    }

    private val ppgChannelTypes = setOf(PpgType.GREEN, PpgType.IR, PpgType.RED)

    private fun obtainTracker(type: HealthTrackerType): HealthTracker? {
        if (type !in samsungCapabilities()) return null
        return try {
            healthTrackingService?.getHealthTracker(type)
        } catch (e: Exception) {
            Log.e(TAG, "getHealthTracker($type): ${e.message}")
            null
        }
    }

    private fun obtainPpgTracker(type: HealthTrackerType): HealthTracker? {
        if (type !in samsungCapabilities()) return null
        return try {
            healthTrackingService?.getHealthTracker(type, ppgChannelTypes)
        } catch (e: Exception) {
            Log.e(TAG, "getHealthTracker($type, $ppgChannelTypes): ${e.message}")
            null
        }
    }

    private fun startSdkTrackers() {
        val capabilities = samsungCapabilities()
        Log.i(TAG, "Capabilities: $capabilities")
        var count = 0

        // HR + IBI: try continuous, then on-demand
        for (hrType in listOf(
            HealthTrackerType.HEART_RATE_CONTINUOUS,
            HealthTrackerType.HEART_RATE
        )) {
            if (hrType !in capabilities) continue
            heartRateTracker = obtainTracker(hrType)
            if (heartRateTracker != null) {
                handler.post { heartRateTracker?.setEventListener(heartRateListener) }
                ibiActive = true
                assembler.markIbiTrackerStarted()
                count++
                Log.i(TAG, "HR+IBI tracker started ($hrType)")
                break
            }
        }
        if (heartRateTracker == null) Log.w(TAG, "No HR tracker type available")

        // The dense PPG waveform comes solely from PPG_ON_DEMAND (100 Hz, started
        // below); a single optical tracker saves battery. IBI/HR come from their
        // own trackers and are unaffected.

        // SKIN_TEMPERATURE_CONTINUOUS exists on GW7; ON_DEMAND is the fallback.
        for (tempMode in listOf(
            HealthTrackerType.SKIN_TEMPERATURE_CONTINUOUS,
            HealthTrackerType.SKIN_TEMPERATURE_ON_DEMAND
        )) {
            if (tempMode !in capabilities) continue
            skinTempTracker = obtainTracker(tempMode)
            if (skinTempTracker != null) {
                handler.post { skinTempTracker?.setEventListener(skinTempListener) }
                skinTempActive = true
                count++
                Log.i(TAG, "✓ Skin temp tracker pornit ($tempMode)")
                break
            }
        }
        if (skinTempTracker == null) {
            Log.w(TAG, "Skin temp unavailable (no CONTINUOUS or ON_DEMAND)")
        }


        // PPG on-demand @100 Hz, TEST ONLY. Gated by START_PPG_ON_DEMAND
        // because on Galaxy Watch 7 this tracker silently blocks
        // HEART_RATE_CONTINUOUS from emitting IBI bursts, starving the server
        // HRV pipeline. Enable only for the cardiac-comparator experiment.
        if (PUBLISH_PPG_ONDEMAND && START_PPG_ON_DEMAND
            && HealthTrackerType.PPG_ON_DEMAND in capabilities) {
            ppgOnDemandTracker = obtainPpgTracker(HealthTrackerType.PPG_ON_DEMAND)
            if (ppgOnDemandTracker != null) {
                handler.post { ppgOnDemandTracker?.setEventListener(ppgOnDemandListener) }
                count++
                Log.i(TAG, "✓ PPG on-demand pornit (100 Hz → acquisition batch)")
            }
        }

        if (HealthTrackerType.ACCELEROMETER_CONTINUOUS in capabilities) {
            accSdkTracker = obtainTracker(HealthTrackerType.ACCELEROMETER_CONTINUOUS)
            if (accSdkTracker != null) {
                handler.post { accSdkTracker?.setEventListener(accSdkListener) }
                accActive = true
                count++
                Log.i(TAG, "✓ ACC SDK tracker pornit")
            }
        }

        activeSensors = count + countAndroidSensors()
        Log.i(TAG, "Total senzori activi: $activeSensors")
        Log.i(
            TAG,
            "MQTT: acquisition/batch at ${mqttPublishIntervalMs}ms"
        )
        // Do NOT start streaming yet. Announce our sensors and wait for the
        // server's ok on biofizic/hello/ack, parseHelloAck() starts the publish
        // loops once accepted. This makes the contract server-enforced: a device
        // the server can't classify never streams.
        publishHello()
    }

    private fun restartHrTracker() {
        val capabilities = samsungCapabilities()
        for (hrType in listOf(
            HealthTrackerType.HEART_RATE_CONTINUOUS,
            HealthTrackerType.HEART_RATE
        )) {
            if (hrType !in capabilities) continue
            val tracker = obtainTracker(hrType) ?: continue
            heartRateTracker = tracker
            handler.post { heartRateTracker?.setEventListener(heartRateListener) }
            ibiActive = true
            assembler.markIbiTrackerStarted()
            Log.i(TAG, "HR tracker restarted ($hrType)")
            return
        }
        Log.e(TAG, "HR tracker restart failed, retrying in 10s")
        handler.postDelayed({ if (isRunning) restartHrTracker() }, 10_000L)
    }

    private fun restartAccTracker() {
        val capabilities = samsungCapabilities()
        if (HealthTrackerType.ACCELEROMETER_CONTINUOUS !in capabilities) {
            Log.e(TAG, "ACC tracker restart failed, type unavailable")
            return
        }
        val tracker = obtainTracker(HealthTrackerType.ACCELEROMETER_CONTINUOUS)
        if (tracker == null) {
            Log.e(TAG, "ACC tracker restart failed, retry in 10s")
            handler.postDelayed({ if (isRunning) restartAccTracker() }, 10_000L)
            return
        }
        accSdkTracker = tracker
        handler.post { accSdkTracker?.setEventListener(accSdkListener) }
        accActive = true
        Log.i(TAG, "ACC tracker restartat")
    }

    private fun shortTypeName(type: Int): String = when (type) {
        Sensor.TYPE_ACCELEROMETER -> "ACC"
        Sensor.TYPE_GYROSCOPE -> "GYRO"
        Sensor.TYPE_STEP_COUNTER -> "STEPS"
        Sensor.TYPE_HEART_RATE -> "HR"
        Sensor.TYPE_LIGHT -> "LIGHT"
        else -> "type=$type"
    }

    // ══════════════════════════════════════════
    //  Android sensors (Gyro, Steps)
    // ══════════════════════════════════════════
    private var androidSensorCount = 0

    private fun registerAndroidSensors() {
        androidSensorCount = 0

        sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.also {
            sensorManager.registerListener(this, it, 40_000) // ~25 Hz
            gyroActive = true
            androidSensorCount++
            Log.i(TAG, "✓ Gyroscop Android activ")
        }
    }

    private fun countAndroidSensors() = androidSensorCount

    override fun onSensorChanged(event: SensorEvent) {
        val ts = System.currentTimeMillis()
        when (event.sensor.type) {
            Sensor.TYPE_GYROSCOPE -> {
                val gMag = sqrt(
                    event.values[0] * event.values[0] +
                        event.values[1] * event.values[1] +
                        event.values[2] * event.values[2]
                ).toDouble()
                assembler.addGyroSample(ts, gMag)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // ══════════════════════════════════════════
    //  MQTT
    // ══════════════════════════════════════════

    private fun connectMqtt() {
        if (mqttSession.connect()) {
            isMqttConnected = true
            syncUiState()
        } else {
            isMqttConnected = false
        }
    }

    /**
     * Announce our sensors to the server (biofizic/hello) so it can validate the
     * set and reply with the modules it will run. Called after the SDK trackers
     * start (so capabilityNames() reflects what actually came up) and whenever
     * MQTT (re)connects. Streaming stays gated on the ok reply.
     */
    private fun publishHello() {
        val caps = capabilityNames()
        val capsJson = caps.joinToString(",") { "\"$it\"" }
        val payload = "{\"client_id\":\"$CLIENT_ID\",\"schema\":2," +
            "\"capabilities\":[$capsJson],\"profile\":\"wrist_ppg\"}"
        mqttSession.publish(Topics.In.HELLO, payload)
        handshakeSentAtMs = System.currentTimeMillis()
        Log.i(TAG, "handshake: announced $caps")
    }

    /** Fail-open: start streaming if the server hasn't answered within the
     *  timeout. Better to send data (the server processes batches even without a
     *  handshake) than to stay mute when the server is briefly down. Called from
     *  the watchdog and after publishHello. A later "error" ack can still stop it. */
    private fun maybeStartStreamingAfterHandshakeTimeout() {
        if (streamingStarted || handshakeRejected) return
        val waited = System.currentTimeMillis() - handshakeSentAtMs
        if (handshakeSentAtMs > 0 && waited >= HANDSHAKE_FAIL_OPEN_MS) {
            Log.w(TAG, "handshake: no ack in ${waited}ms, streaming anyway (fail-open)")
            startStreaming()
        }
    }

    /** Slow retry: if we announced but never got ANY ack, re-announce so the
     *  verdict eventually arrives once the server is back. Stops once any ack
     *  (ok or error) is received. */
    private fun maybeRetryHandshake() {
        if (handshakeAckReceived || !isMqttConnected || heartRateTracker == null) return
        if (handshakeSentAtMs > 0 &&
            System.currentTimeMillis() - handshakeSentAtMs >= HANDSHAKE_RETRY_MS) {
            Log.i(TAG, "handshake: no ack yet, re-announcing")
            publishHello()
        }
    }

    private fun startStreaming() {
        if (streamingStarted) return
        streamingStarted = true
        startPublishLoops()
    }

    private fun parseHelloAck(json: String) {
        try {
            val obj = JSONObject(json)
            val status = obj.optString("status")
            handshakeReason = obj.optString("reason", "")
            handshakeAckReceived = true  // got a reply -> stop the retry loop
            if (status == "ok") {
                handshakeOk = true
                handshakeRejected = false
                val mods = obj.optJSONArray("modules_active")
                Log.i(TAG, "handshake OK: server will run $mods")
                startStreaming()  // idempotent: no-op if fail-open already started it
            } else {
                // Explicit rejection (e.g. too few sensors to classify): stop
                // streaming and surface the reason. This is the ONLY case that
                // halts data, a silent/absent server never does (fail-open).
                handshakeOk = false
                handshakeRejected = true
                Log.w(TAG, "handshake REJECTED: $handshakeReason")
                stopPublishLoops()
                streamingStarted = false
            }
            syncUiState()
        } catch (e: Exception) {
            Log.w(TAG, "hello/ack parse: ${e.message}")
        }
    }


    private fun enqueueSkinTemp(dataPoints: List<DataPoint>) {
        for (dp in dataPoints) {
            val skin = dp.getValue(ValueKey.SkinTemperatureSet.OBJECT_TEMPERATURE)
            val amb = dp.getValue(ValueKey.SkinTemperatureSet.AMBIENT_TEMPERATURE)
            val status = dp.getValue(ValueKey.SkinTemperatureSet.STATUS)
            if ((status == 0 || status == -1) && skin > 0) {
                lastSkinTempC = skin.toDouble()
                lastAmbientTempC = amb.toDouble()
                assembler.lastSkinTempTsMs = dp.timestamp
            }
        }
    }

    private fun enqueueAcc(dataPoints: List<DataPoint>) {
        val recvMs = System.currentTimeMillis()
        for (dp in dataPoints) {
            logTimestampProbe("ACC", dp.timestamp, recvMs)
            val x = dp.getValue(ValueKey.AccelerometerSet.ACCELEROMETER_X)
            val y = dp.getValue(ValueKey.AccelerometerSet.ACCELEROMETER_Y)
            val z = dp.getValue(ValueKey.AccelerometerSet.ACCELEROMETER_Z)
            // Samsung LSM6DSV at +/-8g range, 16-bit signed. 4096 LSB/g is the
            // raw-to-g divisor; verify on a fresh device by leaving it flat and
            // confirming az_mps2 ~ 9.81 in logcat.
            val xf = x.toDouble() / 4096.0 * 9.81
            val yf = y.toDouble() / 4096.0 * 9.81
            val zf = z.toDouble() / 4096.0 * 9.81
            if (accScaleLogRemaining > 0) {
                Log.i(
                    TAG,
                    "ACC m/s^2 sample: ax=${jsonFloat(xf, 3)} ay=${jsonFloat(yf, 3)} az=${jsonFloat(zf, 3)} (raw x=$x y=$y z=$z)"
                )
                accScaleLogRemaining--
            }
            val mag = sqrt(xf * xf + yf * yf + zf * zf)
            val dyn = abs(mag - 9.81)
            assembler.addAccSample(dp.timestamp, dyn)
        }
    }

    private fun logTimestampProbe(stream: String, dpTs: Long, recvMs: Long) {
        val norm = assembler.normalizeSensorTimestampMs(dpTs)
        val skew = if (norm > 0L) recvMs - norm else -1L
        if (timestampProbeEnabled) {
            Log.d(TAG, "TS_PROBE $stream raw=$dpTs norm=$norm recv=$recvMs skew=$skew")
        }
        if (stream == "PPG" && assembler.isEpochMillis(norm)) {
            lastKnownSkewMs = skew
        }
    }

    private fun logIbiTimestampDecision(rawDpTs: Long, recvMs: Long, source: String, count: Int) {
        val norm = assembler.normalizeSensorTimestampMs(rawDpTs)
        val skew = if (norm > 0L) recvMs - norm else -1L
        val now = System.currentTimeMillis()
        if (now - lastIbiSourceLogMs < 3_000L) return
        lastIbiSourceLogMs = now
        Log.i(
            TAG,
            "IBI_TS source=$source count=$count rawDpTs=$rawDpTs normDpTs=$norm skewMs=$skew epoch=${assembler.isEpochMillis(norm)}",
        )
    }

    @Volatile private var lastKnownSkewMs: Long? = null

    /** Extended HRV log at 30 s; signalOk is updated at 1 Hz in publishSensorBatches. */
    private fun updateSignalStatus() {
        assembler.trimIbiWindow()
        Log.i(
            TAG,
            "HRV extended: ibiWin=${assembler.ibiWindowSize()} win=${"%.0f".format(lastWindowSec)}s " +
                "rmssd=${"%.1f".format(lastRmssd)} signalOk=$signalOk hr=$lastHr"
        )
    }

    private fun logHrBatch(gapMs: Long, points: Int, ibiCount: Int) {
        val now = System.currentTimeMillis()
        if (now - lastHrBatchLogMs < 2_000L) return
        lastHrBatchLogMs = now
        Log.i(
            TAG,
            "HR batch: gapMs=$gapMs points=$points ibi=$ibiCount buf=${assembler.ibiWindowSize()} scr=${if (displayOn) "ON" else "OFF"}"
        )
    }

    private fun logHrBatchIfDue(force: Boolean = false) {
        if (!force && lastHrBatchPoints == 0 && lastHrBatchIbi == 0) return
        val gapMs = if (lastHrDataMs > 0L) System.currentTimeMillis() - lastHrDataMs else 0L
        logHrBatch(gapMs, lastHrBatchPoints, lastHrBatchIbi)
    }

    /** Heartbeat log every 15 s so logcat shows progress even without an SDK burst. */
    private fun logHrPulse() {
        val now = System.currentTimeMillis()
        if (now - lastHrPulseLogMs < 15_000L) return
        lastHrPulseLogMs = now
        val gapMs = if (lastHrDataMs > 0L) now - lastHrDataMs else -1L
        val trackerOk = heartRateTracker != null
        Log.i(
            TAG,
            "HR pulse: tracker=$trackerOk gapMs=$gapMs ibiWin=${assembler.ibiWindowSize()} " +
                "lastHr=$lastHr scr=${if (displayOn) "ON" else "OFF"}"
        )
    }

    private fun requestProfileRecalibration(
        reportedArousal: Double = Double.NaN,
        reactivity: String? = null,
    ) {
        val ts = System.currentTimeMillis()
        calibrateRequestedAtMs = ts  // anything older than this is stale/retained
        // Re-announce capabilities first. The hello is not retained, so if the
        // server missed the initial handshake (engine restart, lost message) it
        // never set this device's capabilities and never produces a verdict.
        // A recalibration is the natural point to re-declare them: idempotent, and
        // it guarantees the server has the capability set before it locks the new
        // baseline. Without this, a recalibration could re-collect rest samples but
        // still yield no verdict.
        publishHello()
        val arousalJson =
            if (reportedArousal.isNaN()) "" else ""","reported_arousal":$reportedArousal"""
        // Reactivity (low|normal|high) sets the one-time emotional-responsiveness
        // profile that scales the valence dead-band. Server validates the value.
        val reactivityJson =
            if (reactivity in listOf("low", "normal", "high")) ""","reactivity":"$reactivity"""" else ""
        publish(
            Topics.In.CMD_CALIBRATE,
            """{"ts":$ts,"action":"profile","source":"watch"$arousalJson$reactivityJson}""",
        )
        calibrationPhase = "collecting"
        calibrationMessage = "Recalibrare... stai liniștit 1-2 min"
        syncUiState()  // push "collecting" to Compose so the spinner shows now
        Log.i(TAG, "Recalibration sent (arousal=$reportedArousal, reactivity=$reactivity)")
    }

    /** Publish a user emotion-feedback label (one of the 3 model states: Calm /
     *  Disconfort / Placut). The server pairs it with the live model feature
     *  vectors for the watch-native labelled set and the personal retrain loop.
     *  The JSON key stays "quadrant" for server back-compat.
     *  Fire-and-forget: a missed label is harmless, no spinner / state change. */
    private fun sendEmotionFeedback(quadrant: String) {
        if (!isMqttConnected) {
            Log.w(TAG, "Feedback ignorat: MQTT inactiv ($quadrant)")
            return
        }
        val ts = System.currentTimeMillis()
        publish(
            Topics.In.CMD_FEEDBACK,
            """{"ts":$ts,"action":"emotion","quadrant":"$quadrant","source":"watch"}""",
        )
        Log.i(TAG, "Feedback trimis: $quadrant")
    }

    /** Send a deferred self-report calibration once MQTT is live. Only armed by a
     *  recalibrate request that hit while MQTT was down, a plain start never sets
     *  pendingReportedArousal, so this can no longer recalibrate on start. */
    private fun flushPendingCalibration() {
        if (!pendingReportedArousal.isNaN() && isRunning && isMqttConnected) {
            requestProfileRecalibration(pendingReportedArousal, pendingReactivity)
            pendingReportedArousal = Double.NaN
            pendingReactivity = null
        }
    }

    private fun parseCalibrationStatus(json: String) {
        try {
            val obj = JSONObject(json)
            // Drop the broker's RETAINED "done" replayed on (re)subscribe: if its
            // ts predates our recalibrate request (minus a skew margin), it is a
            // stale message and must not clear the live spinner.
            val msgTs = obj.optLong("ts", 0L)
            if (calibrateRequestedAtMs > 0L &&
                msgTs in 1 until (calibrateRequestedAtMs - CALIBRATION_STALE_MARGIN_MS)
            ) {
                Log.i(TAG, "Ignoring stale calibration status (ts=$msgTs < request=$calibrateRequestedAtMs)")
                return
            }
            calibrationPhase = obj.optString("phase", "")
            calibrationMessage = obj.optString("message", "")
            if (obj.has("profile_ready")) {
                profileReady = obj.optBoolean("profile_ready", profileReady)
            }
            if (calibrationPhase == "rejected") {
                Log.w(TAG, "Recalibration rejected: $calibrationMessage")
            }
            syncUiState()
        } catch (e: JSONException) {
            Log.w(TAG, "parseCalibrationStatus: ${e.message}")
        }
    }

    private fun applyServerDecision(obj: JSONObject) {
        val a10 = obj.optInt("arousal_10", -1)
        if (a10 < 0) return
        arousal10 = a10.coerceIn(0, 10)
        arousalFused = a10.toFloat().coerceIn(0f, 10f)
        arousalConfidence = obj.optDouble("confidence", 0.0).toFloat()
        dominantChannel = obj.optString("dominant_channel", dominantChannel).ifEmpty { dominantChannel }
        arousalLabel = obj.optString("emotion", "—").ifEmpty { "—" }
        if (obj.has("profile_ready")) {
            profileReady = obj.optBoolean("profile_ready", profileReady)
        }
        // Server honesty flag: "preliminary" (Kubios population fallback,
        // confidence capped at 0.5) vs "calibrated" (personal-z CDF). Empty
        // means an older server build, fall back to assuming calibrated so
        // we never accidentally label real calibrated verdicts as preliminary.
        val fidelity = obj.optString("decision_fidelity", "")
        if (fidelity.isNotEmpty()) {
            decisionFidelity = fidelity
        }
        val motionState = obj.optString("motion_state", "")
        if (motionState.isNotEmpty()) {
            motionGated = motionState != "still"
        }
        // Arousal-only classifier: valence/verdict are not consumed on the watch.
        syncUiState()
    }

    // Handles both the retained bootstrap message (received once on
    // (re)subscribe to biofizic/state) and every fresh 30 s epoch update.
    private fun parseEpochStateMessage(json: String) {
        try {
            val obj = JSONObject(json)
            applyServerDecision(obj)
            val hrFromEpoch = obj.optInt("hr", obj.optInt("mean_hr", 0))
            if (hrFromEpoch > 0) lastHr = hrFromEpoch
        } catch (e: JSONException) {
            Log.w(TAG, "parseEpochStateMessage: ${e.message}")
        }
    }

    private fun acquireWakeLock() {
        if (!::wakeLock.isInitialized) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "biofizic:SensorWakeLock")
                .apply { setReferenceCounted(false) }
        }
        if (!wakeLock.isHeld) {
            wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS)
        }
    }

    private fun parseStateLiveMessage(json: String) {
        try {
            applyServerDecision(JSONObject(json))
        } catch (e: JSONException) {
            Log.w(TAG, "parseStateLiveMessage: ${e.message}")
        }
    }

    /** Emotion verdict from biofizic/out/emotion: a human-named state (Relaxat /
     * Normal / Activat / Bucuros / Stresat) plus typical emotions, and the raw
     * arousal (1-10) + valence (-1..+1) scores shown small under the verdict so
     * the user sees what the verdict is based on. Ignores the "CALIBRARE"
     * placeholder so the UI doesn't flash it while the baseline is still locking. */
    private fun parseEmotionStateMessage(json: String) {
        try {
            val obj = JSONObject(json)
            val state = obj.optString("state", "").trim()
            if (state.isEmpty() || state == "CALIBRARE") return
            val emotions = obj.optJSONArray("emotions")
            val typical = if (emotions != null && emotions.length() > 0) {
                (0 until emotions.length()).joinToString(" / ") { emotions.optString(it) }
            } else {
                ""
            }
            // The verdict is the bare state name; typical emotions go to the UI
            // separately so the layout can size the headline on its own.
            emotionVerdict = state
            // Both axes are always shown. Arousal is always measured; valence is shown
            // with a "~" marker when the server could not assert it reliably (most of
            // the time: only high arousal + morphology makes it reliable), so the user
            // always sees two numbers but knows which one is uncertain.
            val arousal = obj.optDouble("arousal_y", -1.0)
            val valence = obj.optDouble("valence_x", 0.0)
            val valenceReliable = obj.optBoolean("valence_reliable", false)
            emotionScores = buildString {
                if (arousal >= 0) append("activare ${arousal.toInt()}")
                val vMark = if (valenceReliable) "" else "~"
                append("  ·  ${vMark}valenta ${"%+.1f".format(valence)}")
            }
            // The verdict's OWN confidence (from the Russell mapping), distinct from
            // the arousal confidence shown elsewhere.
            emotionConfidence = obj.optDouble("confidence", 0.0).toFloat()
            syncUiState()
        } catch (e: JSONException) {
            Log.w(TAG, "parseEmotionStateMessage: ${e.message}")
        }
    }

    private fun publishSensorBatches() {
        if (!liveStreamEnabled || !liveWatchEnabled) return
        val tsPublish = System.currentTimeMillis()
        val motion = assembler.computeMotionStats(tsPublish)

        val ibiSnapshot = assembler.ibiWindowSnapshot()
        val hrv = HrvFeatureCalculator.compute(ibiSnapshot)
        if (hrv != null) {
            lastRmssd = hrv.rmssd
            lastWindowSec = hrv.windowSec
            signalOk = ibiSnapshot.size >= MIN_IBI_FOR_HRV &&
                hrv.windowSec >= MIN_WINDOW_SEC_FOR_SIGNAL
        }

        val accBandCardiac = assembler.computeCardiacBandEnergy(tsPublish)
        val ibiSlice = assembler.drainIbiForPublish(tsPublish)
        // PPG-ul din batch vine acum din bufferul 100 Hz (un singur tracker optic).
        // Acumulat la 100 Hz pe tick, trimis o data/sec in acelasi batch, radio se
        // trezeste 1x/sec, iar morfologia/valenta primesc forma de unda densa.
        val ppgSnapshot =
            if (AcquisitionAssembler.PUBLISH_RAW_PPG) assembler.drainPpgOnDemandForPublish()
            else emptyList()
        val payload = assembler.buildAcquisitionPayload(
            tsPublish = tsPublish,
            motion = motion,
            accBandCardiac = accBandCardiac,
            ibiSlice = ibiSlice,
            heartRateBpm = lastHr,
            displayOn = displayOn,
            skinTempC = lastSkinTempC,
            ambientTempC = lastAmbientTempC,
            ppgSnapshot = ppgSnapshot,
        )
        publish(Topics.In.ACQUISITION, payload.json)
        if (payload.ibiCount > 0) {
            Log.i(
                TAG,
                "acquisition seq=${payload.sequence} ibi=${payload.ibiCount} source=${payload.ibiSource}",
            )
        }
        syncUiState()
    }

    private fun publish(topic: String, payload: String, retain: Boolean = false) {
        mqttSession.publish(topic, payload, retain)
    }

    // ══════════════════════════════════════════
    //  TEST firehose, raw, full-rate, all params, on biofizic/test
    // ══════════════════════════════════════════

    /** Buffer 100 Hz on-demand PPG samples (no publish here, flushed per tick). */
    private fun bufferPpgOnDemand(dataPoints: List<DataPoint>) {
        if (!PUBLISH_PPG_ONDEMAND) return
        // The Samsung SDK delivers a BATCH of samples per callback, all stamped
        // with the same ARRIVAL time (dp.timestamp) rather than each sample's
        // capture time. Stored verbatim, ~11 samples share one timestamp and then
        // jump ~57 ms, which destroys pulse morphology (the extractor cannot
        // reconstruct the waveform when 11 points have identical times). Re-space
        // the batch evenly over the gap since the previous batch so the per-sample
        // timestamps reflect the true 100 Hz capture cadence.
        val n = dataPoints.size
        if (n == 0) return
        val batchTs = dataPoints[0].timestamp
        val prev = lastPpgOnDemandBatchTs
        // Span = time since the previous batch; fall back to n*10 ms (100 Hz) on
        // the first batch or an implausible gap, so samples never collapse.
        val span = if (prev > 0 && batchTs > prev && batchTs - prev < 1000)
            (batchTs - prev) else (n * 10L)
        val step = if (n > 1) span.toDouble() / n else 0.0
        // Anchor so the batch ENDS at its arrival time (the most recent sample is
        // "now"); earlier samples are stepped back. Keeps timestamps monotonic.
        val start = batchTs - (span - (span / n))
        for ((i, dp) in dataPoints.withIndex()) {
            val g = dp.getValue(ValueKey.PpgSet.PPG_GREEN)
            val ir = dp.getValue(ValueKey.PpgSet.PPG_IR)
            val ts = (start + (i * step)).toLong()
            assembler.addPpgOnDemandSample(ts, g, ir)
        }
        lastPpgOnDemandBatchTs = batchTs
    }

    // Arrival time of the previous PPG on-demand batch, to re-space samples.
    private var lastPpgOnDemandBatchTs: Long = 0L

    // ══════════════════════════════════════════
    //  Watchdog: MQTT + SDK reconnection
    // ══════════════════════════════════════════
    private fun startWatchdog() {
        watchdogThread = Thread.currentThread()
        while (isRunning) {
            try {
                Thread.sleep(10_000)

                if (::wakeLock.isInitialized && !wakeLock.isHeld) {
                    wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS)
                }

                // Refresh MQTT status and reconnect if needed. The session
                // coalesces concurrent attempts, so the watchdog can call
                // connect freely without racing in-flight connects.
                val connected = mqttSession.isAlive
                isMqttConnected = connected
                if (!connected) {
                    Log.w(TAG, "MQTT disconnected, reconnecting")
                    connectMqtt()
                    Thread.sleep(5_000)
                    // A network reconnect does NOT change the watch's sensors, so
                    // we do NOT re-handshake here. The contract (which sensors we
                    // carry) is unchanged; streaming simply resumes.
                }

                // Fail-open handshake retry: if we announced but never got an ack
                // (server was down / message lost), keep re-announcing on a slow
                // cadence until the server replies. Streaming is NOT blocked in the
                // meantime (see maybeStartStreamingAfterHandshakeTimeout); this only
                // makes the verdict eventually arrive once the server is back.
                maybeStartStreamingAfterHandshakeTimeout()
                maybeRetryHandshake()

                // Verifica Samsung SDK
                if (healthTrackingService == null) {
                    try {
                        healthTrackingService = HealthTrackingService(connectionListener, this)
                        healthTrackingService?.connectService()
                    } catch (e: Exception) {
                        Log.e(TAG, "Samsung SDK retry eroare: ${e.message}")
                    }
                }

                if (assembler.lastIbiTimestampMs > 0 && isRunning) {
                    val ibiStaleSec = (System.currentTimeMillis() - assembler.lastIbiTimestampMs) / 1000
                    if (ibiStaleSec > 45L && heartRateTracker != null) {
                        Log.w(TAG, "HR tracker idle for ${ibiStaleSec}s, forcing restart")
                        handler.post {
                            heartRateTracker?.unsetEventListener()
                            heartRateTracker = null
                            ibiActive = false
                            restartHrTracker()
                        }
                    }
                }
            } catch (_: InterruptedException) {
                break
            } catch (e: Exception) {
                Log.e(TAG, "Watchdog eroare: ${e.message}")
            }
        }
    }

    // ══════════════════════════════════════════
    //  Cleanup
    // ══════════════════════════════════════════
    private fun resetSdkState() {
        ppgActive = false
        accActive = false
        skinTempActive = false
        ibiActive = false
    }

    private fun stopAllTracking() {
        isRunning = false
        arousalFused = -1f
        arousal10 = -1
        arousalConfidence = 0f
        arousalLabel = "-"
        motionGated = false
        profileReady = false
        signalOk = false
        lastWindowSec = 0.0
        stopPublishLoops()
        // Reset handshake so the next start re-announces (a stop may mean the app
        // is being reconfigured; re-validate the contract on the next run).
        handshakeOk = false
        handshakeAckReceived = false
        handshakeRejected = false
        streamingStarted = false
        handshakeSentAtMs = 0L
        updateSignalStatus()
        unregisterScreenReceiver()
        assembler.clear()
        lastHrDataMs = 0L
        lastHrBatchPoints = 0
        lastHrBatchIbi = 0
        lastHrBatchLogMs = 0L
        lastRmssd = 0.0
        watchdogThread?.interrupt()

        // Android sensors
        sensorManager.unregisterListener(this)
        gyroActive = false

        // Samsung SDK trackers
        heartRateTracker?.unsetEventListener()
        skinTempTracker?.unsetEventListener()
        accSdkTracker?.unsetEventListener()
        ppgOnDemandTracker?.unsetEventListener()
        healthTrackingService?.disconnectService()
        healthTrackingService = null
        resetSdkState()

        // MQTT
        mqttSession.disconnect()
        isMqttConnected = false

        activeSensors = 0
        lastHr = 0
        msgCount = 0
        syncUiState()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAllTracking()
        leaveForeground()
        if (::wakeLock.isInitialized && wakeLock.isHeld) {
            wakeLock.release()
        }
        Log.i(TAG, "Service distrus")
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.i(TAG, "onTaskRemoved, FGS stays until Stop (no restart from alarm)")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ══════════════════════════════════════════
    //  Notification
    // ══════════════════════════════════════════
    private fun buildNotification(contentText: String = "HR · PPG · ACC · Gyro · Temp"): Notification {
        val channelId = "biofizic_service"
        val channel = NotificationChannel(
            channelId, "Biofizic Tracking",
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, SensorService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )
        val openAppIntent = PendingIntent.getActivity(
            this, 1,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return Notification.Builder(this, channelId)
            .setContentTitle("Biofizic activ")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(openAppIntent)
            .addAction(
                Notification.Action.Builder(
                    null, "Stop", stopIntent
                ).build()
            )
            .setOngoing(true)
            .build()
    }
}

private const val TAG = "SensorService"