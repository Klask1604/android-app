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
            onCalibrationStatus = { parseCalibrationStatus(it) },
            onMessagePublished = { msgCount++ },
        )
    }

    // ── Samsung Health SDK ──
    private var healthTrackingService: HealthTrackingService? = null
    private var heartRateTracker: HealthTracker? = null
    private var ppgTracker: HealthTracker? = null
    private var skinTempTracker: HealthTracker? = null
    private var accSdkTracker: HealthTracker? = null
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
        // Self-reported arousal in [0,1] from the watch questionnaire; anchors
        // where the new baseline sits on the arousal scale.
        const val EXTRA_REPORTED_AROUSAL = "com.doltu.biofizic.EXTRA_REPORTED_AROUSAL"

        // Pending self-report to send as a calibration once MQTT is connected.
        @Volatile var pendingReportedArousal: Double = Double.NaN

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
                ppgTracker?.flush()
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
            Log.w(TAG, "Acordă BODY_SENSORS_BACKGROUND pentru măsurători cu ecranul oprit")
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
    //  Samsung Health SDK – connection
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
    //  Samsung Health SDK – tracker listeners
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
                    val accepted = mutableListOf<Int>()
                    for (i in ibiList.indices) {
                        val norm = IbiSignalFilter.acceptBeat(
                            ibiList[i],
                            ibiStatusList?.getOrNull(i),
                            hrForNorm,
                        ) ?: continue
                        accepted.add(norm)
                    }
                    if (accepted.isNotEmpty()) {
                        val (timestamps, source) = assembler.buildIbiTimestamps(
                            accepted, dp.timestamp, recvMs,
                        )
                        logIbiTimestampDecision(dp.timestamp, recvMs, source, accepted.size)
                        val entries = ArrayList<IbiWindowEntry>(accepted.size)
                        for (i in accepted.indices) {
                            entries.add(IbiWindowEntry(accepted[i], timestamps[i], source))
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
            Log.w(TAG, "HR tracker eroare: ${e.name} — restart în 3s")
            ibiActive = false
            heartRateTracker?.unsetEventListener()
            heartRateTracker = null
            handler.postDelayed({
                if (isRunning) restartHrTracker()
            }, 3_000L)
        }
    }

    /** PPG raw – green, IR, red (SDK: ~25 Hz continuous) */
    private val ppgListener = object : HealthTracker.TrackerEventListener {
        override fun onDataReceived(dataPoints: List<DataPoint>) {
            enqueuePpg(dataPoints)
        }
        override fun onFlushCompleted() {}
        override fun onError(e: HealthTracker.TrackerError) {
            Log.w(TAG, "PPG tracker eroare: ${e.name} — restart în 5s")
            ppgActive = false
            ppgTracker?.unsetEventListener()
            ppgTracker = null
            handler.postDelayed({
                if (isRunning) restartPpgTracker()
            }, 5_000L)
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
            Log.w(TAG, "ACC SDK tracker eroare: ${e.name} — restart în 5s")
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
        Log.i(TAG, "Service onCreate (fără startForeground — doar din ACTION_START)")
        acquireWakeLock()
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
    }

    /**
     * Android 12+: startForeground() din onCreate / boot / alarm crapă cu
     * ForegroundServiceStartNotAllowedException. Apelăm doar din onStartCommand
     * după startForegroundService() din MainActivity.
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
                if (intent.hasExtra(EXTRA_REPORTED_AROUSAL)) {
                    // Sent once MQTT is up (see logPeriodicStatus).
                    pendingReportedArousal = intent.getDoubleExtra(EXTRA_REPORTED_AROUSAL, Double.NaN)
                }
                if (!isRunning) {
                    Log.i(TAG, "Pornire tracking (ACTION_START)")
                    isRunning = true
                    syncUiState()
                    startAllTracking()
                }
            }
            ACTION_RECALIBRATE -> {
                val reported = intent.getDoubleExtra(EXTRA_REPORTED_AROUSAL, Double.NaN)
                if (isRunning && isMqttConnected) {
                    requestProfileRecalibration(reported)
                } else {
                    // Defer until MQTT connects.
                    pendingReportedArousal = reported
                    Log.w(TAG, "Recalibrare amânată: MQTT inactiv")
                }
                return START_STICKY
            }
            else -> {
                // Restart automat START_STICKY (intent null) sau boot — reia tracking-ul
                Log.i(TAG, "onStartCommand restart (action=${intent?.action}) — reia tracking")
                if (!promoteToForeground(1, "HR · PPG · ACC · Gyro · Temp")) {
                    Log.w(TAG, "Restart: startForeground eșuat — oprire")
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
        // Android sensors: Gyro only (step counter removed — unused in thesis pipeline)
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
            lines.appendLine("  (listă goală)")
        } else {
            for (type in HealthTrackerType.values()) {
                val tag = if (type in capabilities) "✓ în capabilities" else "– indisponibil"
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

        // HR + IBI – încearcă continuous, apoi on-demand
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

        for (ppgMode in listOf(
            HealthTrackerType.PPG_CONTINUOUS,
            HealthTrackerType.PPG_ON_DEMAND
        )) {
            if (ppgMode !in capabilities) continue
            ppgTracker = obtainPpgTracker(ppgMode)
            if (ppgTracker != null) {
                handler.post { ppgTracker?.setEventListener(ppgListener) }
                ppgActive = true
                count++
                Log.i(TAG, "✓ PPG tracker pornit ($ppgMode, canale=$ppgChannelTypes)")
                break
            }
        }
        if (ppgTracker == null) Log.w(TAG, "✗ PPG indisponibil (GREEN+IR+RED)")

        // SKIN_TEMPERATURE_CONTINUOUS există pe GW7; ON_DEMAND e fallback (~1 măsurătoare)
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
            Log.w(TAG, "✗ Skin temp indisponibil (lipsește CONTINUOUS și ON_DEMAND)")
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
        startPublishLoops()
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

    private fun restartPpgTracker() {
        val capabilities = samsungCapabilities()
        for (ppgMode in listOf(
            HealthTrackerType.PPG_CONTINUOUS,
            HealthTrackerType.PPG_ON_DEMAND
        )) {
            if (ppgMode !in capabilities) continue
            val tracker = obtainPpgTracker(ppgMode) ?: continue
            ppgTracker = tracker
            handler.post { ppgTracker?.setEventListener(ppgListener) }
            ppgActive = true
            Log.i(TAG, "PPG tracker restartat ($ppgMode)")
            return
        }
        Log.e(TAG, "PPG tracker restart eșuat — retry în 10s")
        handler.postDelayed({ if (isRunning) restartPpgTracker() }, 10_000L)
    }

    private fun restartAccTracker() {
        val capabilities = samsungCapabilities()
        if (HealthTrackerType.ACCELEROMETER_CONTINUOUS !in capabilities) {
            Log.e(TAG, "ACC tracker restart eșuat — tip indisponibil")
            return
        }
        val tracker = obtainTracker(HealthTrackerType.ACCELEROMETER_CONTINUOUS)
        if (tracker == null) {
            Log.e(TAG, "ACC tracker restart eșuat — retry în 10s")
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


    private fun enqueuePpg(dataPoints: List<DataPoint>) {
        // Raw PPG feeds production only indirectly (cardiac-band energy is from
        // the accelerometer). It is buffered into the acquisition batch only for
        // the server's research/legacy peak-detection demos, behind the
        // PUBLISH_RAW_PPG toggle. Off by default to save bandwidth/power.
        if (!AcquisitionAssembler.PUBLISH_RAW_PPG) return
        for (dp in dataPoints) {
            val g = dp.getValue(ValueKey.PpgSet.PPG_GREEN)
            val ir = dp.getValue(ValueKey.PpgSet.PPG_IR)
            assembler.addPpgSample(dp.timestamp, g, ir)
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

    private fun requestProfileRecalibration(reportedArousal: Double = Double.NaN) {
        val ts = System.currentTimeMillis()
        val reportedJson =
            if (reportedArousal.isNaN()) "" else ""","reported_arousal":$reportedArousal"""
        publish(
            "biofizic/cmd/calibrate",
            """{"ts":$ts,"action":"profile","source":"watch"$reportedJson}""",
        )
        calibrationPhase = "collecting"
        calibrationMessage = "Recalibrare… stai liniștit 1–2 min"
        syncUiState()  // push "collecting" to Compose so the spinner shows now
        Log.i(TAG, "Recalibrare profil trimisă (reported=$reportedArousal)")
    }

    /** Send a deferred self-report calibration once MQTT is live. */
    private fun flushPendingCalibration() {
        if (!pendingReportedArousal.isNaN() && isRunning && isMqttConnected) {
            requestProfileRecalibration(pendingReportedArousal)
            pendingReportedArousal = Double.NaN
        }
    }

    private fun parseCalibrationStatus(json: String) {
        try {
            val obj = JSONObject(json)
            calibrationPhase = obj.optString("phase", "")
            calibrationMessage = obj.optString("message", "")
            if (obj.has("profile_ready")) {
                profileReady = obj.optBoolean("profile_ready", profileReady)
            }
            if (calibrationPhase == "rejected") {
                Log.w(TAG, "Recalibrare respinsă: $calibrationMessage")
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
        val motionState = obj.optString("motion_state", "")
        if (motionState.isNotEmpty()) {
            motionGated = motionState != "still"
        }
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
        val ppgSnapshot =
            if (AcquisitionAssembler.PUBLISH_RAW_PPG) assembler.drainPpgForPublish()
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
        publish("biofizic/acquisition/batch", payload.json)
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
    //  Watchdog – reconectare MQTT + SDK
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
                }

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
        ppgTracker?.unsetEventListener()
        skinTempTracker?.unsetEventListener()
        accSdkTracker?.unsetEventListener()
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
        Log.i(TAG, "onTaskRemoved — FGS rămâne până la Stop (fără restart din alarm)")
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