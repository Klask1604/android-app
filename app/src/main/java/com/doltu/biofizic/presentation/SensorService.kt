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
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

class SensorService : Service(), SensorEventListener {

    /** MQTT JSON must use Locale.US decimal point, not comma. */
    private fun jsonFloat(value: Double, decimals: Int): String =
        String.format(Locale.US, "%.${decimals}f", value)

    /** RMS (caller), p90, std for epoch magnitude buffers (~30s). */
    private fun magnitudeStats(values: List<Double>): Triple<Double, Double, Double> {
        if (values.isEmpty()) return Triple(0.0, 0.0, 0.0)
        val sorted = values.sorted()
        val rms = sqrt(values.map { it * it }.average())
        val p90Idx = ((sorted.size - 1) * 0.9).toInt().coerceIn(0, sorted.size - 1)
        val mean = values.average()
        val std = sqrt(values.map { (it - mean) * (it - mean) }.average())
        return Triple(rms, sorted[p90Idx], std)
    }

    // ── Android sensors ──
    private lateinit var sensorManager: SensorManager

    // ── MQTT ──
    private lateinit var mqttClient: MqttClient
    private val BROKER_URL = BuildConfig.MQTT_BROKER_URL
    private val CLIENT_ID = "GalaxyWatch7"

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
    //  Companion: state exposed to UI
    // ══════════════════════════════════════════
    companion object {
        const val ACTION_START = "com.doltu.biofizic.ACTION_START"
        const val ACTION_STOP = "com.doltu.biofizic.ACTION_STOP"
        /** Connect Samsung SDK, list sensors, then stop (no MQTT tracking). */
        const val ACTION_SCAN = "com.doltu.biofizic.ACTION_SCAN"
        const val ACTION_RECALIBRATE = "com.doltu.biofizic.ACTION_RECALIBRATE"

        @Volatile var isRunning = false
        /** While MainActivity is visible: keep screen on (avoid watch face at ~30s). */
        /** false = allow screen off (FGS + sensors continue). */
        @Volatile var keepScreenAwake = false
        @Volatile var isMqttConnected = false
        @Volatile var lastHr = 0
        @Volatile var activeSensors = 0
        @Volatile var ppgActive = false
        @Volatile var accActive = false
        @Volatile var gyroActive = false
        @Volatile var skinTempActive = false
        @Volatile var ibiActive = false

        // Contoare pentru debug / UI
        @Volatile var msgCount = 0L

        /** Raport senzori (Android + Samsung); actualizat la scan / pornire SDK. */
        @Volatile var sensorReport: String = ""
        @Volatile var sdkScanDone = false

        /**
         * MQTT publish interval for all batch topics (ms).
         * Samples accumulate and ship as one message per topic.
         * Samsung sensors still sample at native rate (e.g. PPG ~25 Hz); MQTT at 1 Hz.
         */
        /** PPG/IBI/HR batch interval on MQTT (1 s). */
        @Volatile var mqttPublishIntervalMs = 1_000L
        /** Legacy: single biofizic/epoch every 30s (raw IBI + HRV). */
        @Volatile var hrvPublishIntervalMs = 30_000L

        /**
         * true = biofizic/features only; no ppg/ibi/hr/acc/gyro batch MQTT.
         */
        @Volatile var leanMqtt = true

        /** Flush SDK HR to fill ibiWindow (GW7 bursts ~4s). */
        @Volatile var hrFlushIntervalMs = 4_000L

        /** One MQTT message per PPG sample on biofizic/ppg/stream (signal + jitter debug). */
        @Volatile var ppgStreamEnabled = false

        /** Unified 1 Hz stream — all sensors + rolling HRV + classification. */
        @Volatile var liveWatchEnabled = true
        private const val LIVE_WATCH_INTERVAL_MS = 1_000L

        /** Live ACC (1 Hz) and instant HR for server-side context. */
        @Volatile var liveStreamEnabled = true
        @Volatile var liveAccIntervalMs = 1_000L

        /** Raw PPG batch publishing for external DSP pipeline. */
        @Volatile var ppgRawEnabled = true

        /** Seconds of PPG to accumulate before flush on biofizic/ppg/raw. */
        @Volatile var ppgBatchSec = 2

        @Volatile var lastSkinTempC = 0.0
        @Volatile var lastAmbientTempC = 0.0

        @Volatile var displayOn = true
        @Volatile var backgroundSensorsGranted = true
        @Volatile var lastRmssd = 0.0

        /** From biofizic/combined (compute-engine verdict for watch UI). */
        @Volatile var arousalFused = -1f
        @Volatile var arousal10 = -1
        @Volatile var valence10 = -1
        @Volatile var affectQuadrant = "—"
        @Volatile var arousalConfidence = 0f
        @Volatile var emotionLabel = "—"
        @Volatile var motionGated = false
        @Volatile var profileReady = false
        @Volatile var calibrationPhase = ""
        @Volatile var calibrationMessage = ""
        @Volatile var signalOk = false
        @Volatile var lastWindowSec = 0.0
        @Volatile var lastStateMessageMs = 0L

        /** From biofizic/state/live (server preview, ~1 Hz). */
        @Volatile var serverArousal10 = -1
        @Volatile var serverEmotion = "—"
        @Volatile var serverActivityMode = "UNKNOWN"
        @Volatile var serverMotionZ = 0f
        @Volatile var serverZHr = 0f

        private const val MAX_IBI_ENTRIES = 200
        private const val IBI_RETENTION_MS = 120_000L
        /** Fereastră HRV pe ceas = epoca de clasificare (30 s). */
        private const val HRV_WINDOW_MS = 30_000L
        const val EPOCH_SEC = 30
        @Volatile var strictIbiFilter = false
        private const val MIN_IBI_FOR_HRV = 8
        private const val MIN_WINDOW_SEC_FOR_SIGNAL = 6.0
        private const val MAX_ACC_MAGNITUDES = 750  // ~25 Hz × 30 s
        private const val EPOCH_IBI_MAX_MS = 60_000L
        private const val WAKE_LOCK_TIMEOUT_MS = 4 * 60 * 60 * 1000L
    }

    private var scanOnlyMode = false
    private var accScaleLogRemaining = 5
    private var screenReceiverRegistered = false
    private var displayOnLocal = true
    private val ibiWindow = ArrayDeque<IbiWindowEntry>(220)
    private val accMagnitudes = mutableListOf<Double>()
    private val gyroMagnitudes = mutableListOf<Double>()
    private val livePpgGreen = mutableListOf<Int>()
    private val livePpgIr = mutableListOf<Int>()
    private val livePpgRed = mutableListOf<Int>()
    @Volatile private var lastSteps = 0f
    @Volatile private var lastIbiTimestampMs = 0L

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> updateDisplayState(true)
                Intent.ACTION_SCREEN_OFF -> updateDisplayState(false)
            }
        }
    }

    private val bufferLock = Any()
    private val ppgSamples = mutableListOf<String>()

    // PPG raw batch pentru pipeline-ul DSP extern
    private val ppgBatchLock = Any()
    private val ppgBatch = mutableListOf<Triple<Long, Int, Int>>()  // (ts_ms, green, ir)
    private val skinSamples = mutableListOf<String>()
    private val accSamples = mutableListOf<String>()
    private val hrSamples = mutableListOf<String>()
    private val ibiSamples = mutableListOf<String>()
    private val gyroSamples = mutableListOf<String>()
    private var lastStepJson: String? = null

    private var sdkFlushLoopRunning = false
    private var hrFlushLoopRunning = false
    private var hrvPublishLoopRunning = false
    private var liveWatchLoopRunning = false
    private var mqttPublishLoopRunning = false
    private var ppgBatchLoopRunning = false

    @Volatile private var lastHrDataMs = 0L
    @Volatile private var lastHrBatchPoints = 0
    @Volatile private var lastHrBatchIbi = 0
    private var lastHrBatchLogMs = 0L
    private var lastHrPulseLogMs = 0L
    private var inForeground = false

    private val sdkFlushRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) {
                sdkFlushLoopRunning = false
                return
            }
            ppgTracker?.flush()
            skinTempTracker?.flush()
            accSdkTracker?.flush()
            handler.postDelayed(this, mqttPublishIntervalMs)
        }
    }

    private val hrFlushRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) {
                hrFlushLoopRunning = false
                return
            }
            heartRateTracker?.flush()
            if (leanMqtt) discardLeanIbiBuffer()
            logHrBatchIfDue()
            logHrPulse()
            handler.postDelayed(this, hrFlushIntervalMs)
        }
    }

    private val hrvPublishRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) {
                hrvPublishLoopRunning = false
                return
            }
            publishEpoch()
            handler.postDelayed(this, hrvPublishIntervalMs)
        }
    }

    private val liveWatchRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) {
                liveWatchLoopRunning = false
                return
            }
            publishWatchLive()
            handler.postDelayed(this, LIVE_WATCH_INTERVAL_MS)
        }
    }

    private val mqttPublishRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) {
                mqttPublishLoopRunning = false
                return
            }
            flushAllMqttBuffers()
            logPeriodicStatus()
            handler.postDelayed(this, mqttPublishIntervalMs)
        }
    }

    private fun updateDisplayState(on: Boolean) {
        displayOnLocal = on
        displayOn = on
        if (leanMqtt) {
            hrvPublishIntervalMs = 30_000L
            mqttPublishIntervalMs = 1_000L
            hrFlushIntervalMs = if (on) 2_000L else 4_000L
        }
        Log.i(
            TAG,
            "Ecran ${if (on) "ON" else "OFF"} (epoch=${hrvPublishIntervalMs}ms hrFlush=${hrFlushIntervalMs}ms lean=$leanMqtt)"
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
        val now = System.currentTimeMillis()
        if (now - lastStatusLogMs < 15_000L) return
        lastStatusLogMs = now
        backgroundSensorsGranted = ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.BODY_SENSORS_BACKGROUND
        ) == PackageManager.PERMISSION_GRANTED
        synchronized(bufferLock) {
            val liveHrv = HrvFeatureCalculator.compute(ibiWindow.toList())
            val liveRmssd = liveHrv?.rmssd ?: 0.0
            val liveWin = liveHrv?.windowSec ?: 0.0
            val liveOk = ibiWindow.size >= MIN_IBI_FOR_HRV && liveWin >= MIN_WINDOW_SEC_FOR_SIGNAL
            Log.i(
                TAG,
                "Status: ecran=${if (displayOn) "ON" else "OFF"}, bg=$backgroundSensorsGranted, " +
                    "ibiWindow=${ibiWindow.size}, windowSec=${"%.0f".format(liveWin)}, " +
                    "signalOk=$liveOk, rmssd=${"%.1f".format(liveRmssd)}, mqtt=$msgCount"
            )
        }
    }

    private fun startPublishLoops() {
        if (!sdkFlushLoopRunning) {
            sdkFlushLoopRunning = true
            handler.post(sdkFlushRunnable)
        }
        if (!hrFlushLoopRunning) {
            hrFlushLoopRunning = true
            handler.post(hrFlushRunnable)
        }
        if (!hrvPublishLoopRunning) {
            hrvPublishLoopRunning = true
            // Prima epocă după 30s (fereastra HRV plină), nu la pornire goală
            handler.postDelayed(hrvPublishRunnable, hrvPublishIntervalMs)
        }
        if (liveStreamEnabled && liveWatchEnabled && !liveWatchLoopRunning) {
            liveWatchLoopRunning = true
            handler.post(liveWatchRunnable)
        }
        if (!mqttPublishLoopRunning) {
            mqttPublishLoopRunning = true
            handler.post(mqttPublishRunnable)
        }
        if (ppgRawEnabled && !ppgBatchLoopRunning) {
            ppgBatchLoopRunning = true
            handler.postDelayed(ppgBatchRunnable, ppgBatchSec * 1000L)
        }
    }

    private fun stopPublishLoops() {
        sdkFlushLoopRunning = false
        hrFlushLoopRunning = false
        hrvPublishLoopRunning = false
        liveWatchLoopRunning = false
        mqttPublishLoopRunning = false
        ppgBatchLoopRunning = false
        handler.removeCallbacks(sdkFlushRunnable)
        handler.removeCallbacks(hrFlushRunnable)
        handler.removeCallbacks(hrvPublishRunnable)
        handler.removeCallbacks(liveWatchRunnable)
        handler.removeCallbacks(mqttPublishRunnable)
        handler.removeCallbacks(ppgBatchRunnable)
    }

    // ══════════════════════════════════════════
    //  Samsung Health SDK – connection
    // ══════════════════════════════════════════
    private val connectionListener = object : ConnectionListener {
        override fun onConnectionSuccess() {
            Log.i(TAG, "Samsung Health SDK conectat")
            if (scanOnlyMode) {
                buildSensorInventory(probeAccessibility = true)
                scanOnlyMode = false
                if (!isRunning) {
                    healthTrackingService?.disconnectService()
                    healthTrackingService = null
                    stopSelf()
                }
                return
            }
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
                val hr = dp.getValue(ValueKey.HeartRateSet.HEART_RATE)
                val status = dp.getValue(ValueKey.HeartRateSet.HEART_RATE_STATUS)

                if (status == 1 && hr > 0) {
                    lastHr = hr
                    publishHrLive(hr, status)
                    enqueueSample(
                        hrSamples,
                        """{"ts":${dp.timestamp},"hr":$hr}"""
                    )
                }

                val ibiList = dp.getValue(ValueKey.HeartRateSet.IBI_LIST)
                val ibiStatusList = dp.getValue(ValueKey.HeartRateSet.IBI_STATUS_LIST)
                if (ibiList != null && status == 1) {
                    synchronized(bufferLock) {
                        val hrForNorm = if (hr > 0) hr else lastHr
                        // Filtrare
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
                            // Reconstituie timestamps: ultima bătaie s-a terminat acum,
                            // fiecare bătaie anterioară cu IBI ms mai devreme.
                            val nowMs = System.currentTimeMillis()
                            val timestamps = LongArray(accepted.size)
                            var endTs = nowMs
                            for (i in accepted.indices.reversed()) {
                                timestamps[i] = endTs
                                endTs -= accepted[i]
                            }
                            for (i in accepted.indices) {
                                ibiWindow.addLast(IbiWindowEntry(accepted[i], timestamps[i]))
                                batchIbi++
                            }
                            lastIbiTimestampMs = nowMs
                            trimIbiWindow()
                        }
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
            ACTION_SCAN -> {
                if (!promoteToForeground(2, "Scanare senzori…")) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                Log.i(TAG, "ACTION_SCAN – inventar senzori")
                acquireWakeLock()
                scanOnlyMode = true
                sdkScanDone = false
                sensorReport = "Scanare în curs…"
                if (!::sensorManager.isInitialized) {
                    sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
                }
                buildAndroidSensorInventory()
                Thread {
                    try {
                        if (healthTrackingService == null) {
                            healthTrackingService = HealthTrackingService(connectionListener, this)
                            healthTrackingService?.connectService()
                        } else {
                            buildSensorInventory(probeAccessibility = true)
                            scanOnlyMode = false
                        }
                    } catch (e: Exception) {
                        sensorReport = "SDK eroare: ${e.message}"
                        scanOnlyMode = false
                        Log.e(TAG, "Scan SDK init: ${e.message}")
                    }
                }.start()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                if (!promoteToForeground(1, "HR · PPG · ACC · Gyro · Temp")) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                if (!isRunning) {
                    Log.i(TAG, "Pornire tracking (ACTION_START)")
                    isRunning = true
                    startAllTracking()
                }
            }
            ACTION_RECALIBRATE -> {
                if (isRunning && isMqttConnected) {
                    requestProfileRecalibration()
                } else {
                    Log.w(TAG, "Recalibrare: tracking sau MQTT inactiv")
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
        // Android sensors: Gyro + Steps
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
                    Sensor.TYPE_STEP_COUNTER -> " ← folosit"
                    else -> ""
                }
                lines.appendLine("  ${shortTypeName(s.type)}: ${s.name}$marker")
            }
        }
        sensorReport = lines.toString().trimEnd()
    }

    private fun buildSensorInventory(@Suppress("UNUSED_PARAMETER") probeAccessibility: Boolean) {
        buildAndroidSensorInventory()
        val capabilities = samsungCapabilities()
        val lines = StringBuilder(sensorReport)
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
        sensorReport = lines.toString().trimEnd()
        sdkScanDone = true
        Log.i(TAG, "── Inventar senzori ──\n$sensorReport")
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
                lastIbiTimestampMs = System.currentTimeMillis()
                count++
                Log.i(TAG, "✓ HR+IBI tracker pornit ($hrType)")
                break
            }
        }
        if (heartRateTracker == null) Log.w(TAG, "✗ Niciun tip HR accesibil")

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
            "MQTT: batch la ${mqttPublishIntervalMs}ms; PPG stream=${if (ppgStreamEnabled) "ON (biofizic/ppg/stream)" else "OFF"}"
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
            lastIbiTimestampMs = System.currentTimeMillis()
            Log.i(TAG, "HR tracker restartat ($hrType)")
            return
        }
        Log.e(TAG, "HR tracker restart eșuat — retry în 10s")
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

        sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)?.also {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
            androidSensorCount++
            Log.i(TAG, "✓ Step counter activ")
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
                synchronized(bufferLock) {
                    gyroMagnitudes.add(gMag)
                    while (gyroMagnitudes.size > MAX_ACC_MAGNITUDES) {
                        gyroMagnitudes.removeAt(0)
                    }
                }
                enqueueSample(
                    gyroSamples,
                    """{"ts":$ts,"gx":${event.values[0]},"gy":${event.values[1]},"gz":${event.values[2]}}"""
                )
            }
            Sensor.TYPE_STEP_COUNTER -> {
                lastSteps = event.values[0]
                lastStepJson = """{"ts":$ts,"steps":${event.values[0]}}"""
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // ══════════════════════════════════════════
    //  MQTT
    // ══════════════════════════════════════════
    @Volatile private var mqttConnecting = false

    private fun connectMqtt() {
        if (mqttConnecting) return
        mqttConnecting = true
        try {
            if (::mqttClient.isInitialized) {
                try {
                    mqttClient.disconnect(0)
                } catch (_: Exception) {}
                try { mqttClient.close() } catch (_: Exception) {}
            }
            mqttClient = MqttClient(BROKER_URL, CLIENT_ID, MemoryPersistence())
            val options = MqttConnectOptions().apply {
                isCleanSession = true
                connectionTimeout = 8
                keepAliveInterval = 45
                isAutomaticReconnect = false  // watchdog-ul gestionează reconectarea
            }
            mqttClient.setCallback(object : MqttCallback {
                override fun connectionLost(cause: Throwable?) {
                    isMqttConnected = false
                    Log.w(TAG, "MQTT pierdut: ${cause?.message}")
                    // Nu reconectăm direct — watchdog-ul va detecta și va apela connectMqtt()
                }

                override fun messageArrived(topic: String?, message: MqttMessage?) {
                    if (message == null || topic == null) return
                    try {
                        val json = message.payload.decodeToString()
                        when (topic) {
                            "biofizic/combined" -> parseCombinedMessage(json)
                            "biofizic/state/live" -> parseStateLiveMessage(json)
                            "biofizic/calibration/status" -> parseCalibrationStatus(json)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "MQTT parse $topic: ${e.message}")
                    }
                }

                override fun deliveryComplete(token: IMqttDeliveryToken?) {}
            })
            mqttClient.connect(options)
            mqttClient.subscribe("biofizic/combined", 0)
            mqttClient.subscribe("biofizic/state/live", 0)
            mqttClient.subscribe("biofizic/calibration/status", 1)
            isMqttConnected = true
            Log.i(TAG, "MQTT conectat la $BROKER_URL (+ combined, state/live)")
        } catch (e: Exception) {
            isMqttConnected = false
            Log.e(TAG, "MQTT eroare conectare: ${e.message}")
        } finally {
            mqttConnecting = false
        }
    }

    private fun enqueueSample(buffer: MutableList<String>, json: String) {
        synchronized(bufferLock) { buffer.add(json) }
    }

    private fun enqueuePpg(dataPoints: List<DataPoint>) {
        val streamPayloads = mutableListOf<String>()
        synchronized(bufferLock) {
            for (dp in dataPoints) {
                val g = dp.getValue(ValueKey.PpgSet.PPG_GREEN)
                val ir = dp.getValue(ValueKey.PpgSet.PPG_IR)
                val r = dp.getValue(ValueKey.PpgSet.PPG_RED)
                livePpgGreen.add(g)
                livePpgIr.add(ir)
                livePpgRed.add(r)
                ppgSamples.add("""{"ts":${dp.timestamp},"green":$g,"ir":$ir,"red":$r}""")
                if (ppgStreamEnabled) {
                    val pub = System.currentTimeMillis()
                    streamPayloads.add(
                        """{"ts":${dp.timestamp},"pub":$pub,"green":$g,"ir":$ir,"red":$r}"""
                    )
                }
            }
        }
        // Acumulare batch PPG raw cu timestamps (pentru pipeline DSP extern).
        // Flush imediat pe handler thread — nu asteapta timer-ul periodic.
        var shouldFlush = false
        if (ppgRawEnabled) {
            synchronized(ppgBatchLock) {
                for (dp in dataPoints) {
                    val g = dp.getValue(ValueKey.PpgSet.PPG_GREEN)
                    val ir = dp.getValue(ValueKey.PpgSet.PPG_IR)
                    ppgBatch.add(Triple(dp.timestamp, g, ir))
                }
                shouldFlush = ppgBatch.isNotEmpty()
            }
        }
        if (shouldFlush) {
            handler.post { flushPpgBatch() }
        }
        for (payload in streamPayloads) {
            publish("biofizic/ppg/stream", payload)
        }
    }

    // Safety valve: daca un batch e ramas neflushed (ex. enqueuePpg nu a mai fost apelat).
    private val ppgBatchRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return
            flushPpgBatch()
            handler.postDelayed(this, 10_000L)  // safety la 10s, flush-ul real e imediat
        }
    }

    private fun flushPpgBatch() {
        if (!ppgRawEnabled) return
        val snapshot: List<Triple<Long, Int, Int>>
        synchronized(ppgBatchLock) {
            if (ppgBatch.isEmpty()) return
            snapshot = ppgBatch.toList()
            ppgBatch.clear()
        }
        val tsArr  = snapshot.joinToString(",") { it.first.toString() }
        val gArr   = snapshot.joinToString(",") { it.second.toString() }
        val irArr  = snapshot.joinToString(",") { it.third.toString() }
        val payload = """{"ts_start":${snapshot.first().first},"fs":25,"n":${snapshot.size},"ts":[$tsArr],"green":[$gArr],"ir":[$irArr]}"""
        publish("biofizic/ppg/raw", payload)
    }

    private fun enqueueSkinTemp(dataPoints: List<DataPoint>) {
        synchronized(bufferLock) {
            for (dp in dataPoints) {
                val skin = dp.getValue(ValueKey.SkinTemperatureSet.OBJECT_TEMPERATURE)
                val amb = dp.getValue(ValueKey.SkinTemperatureSet.AMBIENT_TEMPERATURE)
                val status = dp.getValue(ValueKey.SkinTemperatureSet.STATUS)
                if (status == 0 && skin > 0) {
                    lastSkinTempC = skin.toDouble()
                    lastAmbientTempC = amb.toDouble()
                }
                skinSamples.add(
                    """{"ts":${dp.timestamp},"skin":$skin,"ambient":$amb,"status":$status}"""
                )
            }
        }
    }

    private fun enqueueAcc(dataPoints: List<DataPoint>) {
        synchronized(bufferLock) {
            for (dp in dataPoints) {
                val x = dp.getValue(ValueKey.AccelerometerSet.ACCELEROMETER_X)
                val y = dp.getValue(ValueKey.AccelerometerSet.ACCELEROMETER_Y)
                val z = dp.getValue(ValueKey.AccelerometerSet.ACCELEROMETER_Z)
                // Samsung LSM6DSV ±8g range, 16-bit; de verificat cu logcat la repaus:
                // az_mps2 ar trebui ≈9.81, dacă nu ajustează scala (4096).
                val xf = x.toDouble() / 4096.0 * 9.81
                val yf = y.toDouble() / 4096.0 * 9.81
                val zf = z.toDouble() / 4096.0 * 9.81
                if (accScaleLogRemaining > 0) {
                    Log.i(
                        TAG,
                        "ACC m/s² sample: ax=${jsonFloat(xf, 3)} ay=${jsonFloat(yf, 3)} az=${jsonFloat(zf, 3)} (raw x=$x y=$y z=$z)"
                    )
                    accScaleLogRemaining--
                }
                accSamples.add(
                    """{"ts":${dp.timestamp},"ax":${jsonFloat(xf, 4)},"ay":${jsonFloat(yf, 4)},"az":${jsonFloat(zf, 4)}}"""
                )
                val mag = sqrt(xf * xf + yf * yf + zf * zf)
                accMagnitudes.add(mag)
                while (accMagnitudes.size > MAX_ACC_MAGNITUDES) {
                    accMagnitudes.removeAt(0)
                }
            }
        }
    }

    private fun trimIbiWindow() {
        val cutoff = System.currentTimeMillis() - IBI_RETENTION_MS
        while (ibiWindow.isNotEmpty() && ibiWindow.first().ts < cutoff) {
            ibiWindow.removeFirst()
        }
        while (ibiWindow.size > MAX_IBI_ENTRIES) {
            ibiWindow.removeFirst()
        }
    }

    /** Un mesaj / 30s: IBI din fereastră + HRV calculat o dată (sursă unică pentru clasificator). */
    private fun publishEpoch() {
        val ibiSnapshot: List<IbiWindowEntry>
        val accRms: Double
        val accP90: Double
        val accStd: Double
        val gyroRms: Double
        val gyroP90: Double
        val gyroStd: Double
        val ts = System.currentTimeMillis()
        synchronized(bufferLock) {
            trimIbiWindow()
            ibiSnapshot = ibiWindow.filter { it.ts >= ts - EPOCH_IBI_MAX_MS }
            val accDyn = accMagnitudes.map { abs(it - 9.81) }
            val (_, accP90Val, accStdVal) = magnitudeStats(accDyn)
            accP90 = accP90Val
            accStd = accStdVal
            accRms = if (accDyn.isEmpty()) 0.0 else sqrt(accDyn.map { it * it }.average())
            val (_, gyroP90Val, gyroStdVal) = magnitudeStats(gyroMagnitudes.toList())
            gyroP90 = gyroP90Val
            gyroStd = gyroStdVal
            gyroRms = if (gyroMagnitudes.isEmpty()) 0.0
            else sqrt(gyroMagnitudes.map { it * it }.average())
        }
        val secSinceIbi = if (lastIbiTimestampMs > 0) (ts - lastIbiTimestampMs) / 1000.0 else -1.0
        val features = HrvFeatureCalculator.compute(ibiSnapshot)
        val ibiMsJson = ibiSnapshot.joinToString(",") { it.ibiMs.toString() }
        val ibiTsJson = ibiSnapshot.joinToString(",") { it.ts.toString() }
        val ibiN: Int
        val windowSec: Double
        val rmssd: Double
        val sdnn: Double
        val meanIbi: Double
        val meanHr: Double
        val pnn50: Double
        if (features != null) {
            lastRmssd = features.rmssd
            lastWindowSec = features.windowSec
            ibiN = features.ibiCount
            windowSec = features.windowSec
            rmssd = features.rmssd
            sdnn = features.sdnn
            meanIbi = features.meanIbiMs
            meanHr = features.meanHrBpm
            pnn50 = features.pnn50
        } else {
            ibiN = ibiSnapshot.size
            windowSec = 0.0
            rmssd = 0.0
            sdnn = 0.0
            meanIbi = 0.0
            meanHr = if (lastHr > 0) lastHr.toDouble() else 0.0
            pnn50 = 0.0
        }
        val hrvReady = ibiN >= MIN_IBI_FOR_HRV && windowSec >= MIN_WINDOW_SEC_FOR_SIGNAL
        signalOk = hrvReady
        if (!hrvReady) {
            Log.i(
                TAG,
                "Epoch skip MQTT: ibi_n=$ibiN win=${"%.0f".format(windowSec)}s rmssd=${"%.1f".format(rmssd)} " +
                    "buf=${ibiWindow.size} (min ${MIN_IBI_FOR_HRV} ibi, ${MIN_WINDOW_SEC_FOR_SIGNAL.toInt()}s)"
            )
            return
        }
        val payload =
            """{"ts":$ts,"epoch_sec":$EPOCH_SEC,"displayOn":$displayOn,"bgSensors":$backgroundSensorsGranted,"hr":$lastHr,"ibi_n":$ibiN,"ibi_ms":[$ibiMsJson],"ibi_ts":[$ibiTsJson],"window_sec":${jsonFloat(windowSec, 1)},"sec_since_last_ibi":${jsonFloat(secSinceIbi, 1)},"hrv_ready":$hrvReady,"rmssd":${jsonFloat(rmssd, 2)},"sdnn":${jsonFloat(sdnn, 2)},"mean_ibi":${jsonFloat(meanIbi, 1)},"mean_hr":${jsonFloat(meanHr, 1)},"pnn50":${jsonFloat(pnn50, 1)},"acc_rms":${jsonFloat(accRms, 3)},"acc_p90":${jsonFloat(accP90, 3)},"acc_std":${jsonFloat(accStd, 3)},"gyro_rms":${jsonFloat(gyroRms, 4)},"gyro_p90":${jsonFloat(gyroP90, 4)},"gyro_std":${jsonFloat(gyroStd, 4)},"skin_temp":${jsonFloat(lastSkinTempC, 2)},"ambient_temp":${jsonFloat(lastAmbientTempC, 2)}}"""
        publish("biofizic/epoch", payload)
        Log.i(
            TAG,
            "Epoch MQTT: ibi_n=$ibiN win=${"%.0f".format(windowSec)}s rmssd=${"%.1f".format(rmssd)} hr=$lastHr scr=${if (displayOn) "ON" else "OFF"}"
        )
    }

    private fun logHrBatch(gapMs: Long, points: Int, ibiCount: Int) {
        val now = System.currentTimeMillis()
        if (now - lastHrBatchLogMs < 2_000L) return
        lastHrBatchLogMs = now
        Log.i(
            TAG,
            "HR batch: gapMs=$gapMs points=$points ibi=$ibiCount buf=${ibiWindow.size} scr=${if (displayOn) "ON" else "OFF"}"
        )
    }

    private fun logHrBatchIfDue(force: Boolean = false) {
        if (!force && lastHrBatchPoints == 0 && lastHrBatchIbi == 0) return
        val gapMs = if (lastHrDataMs > 0L) System.currentTimeMillis() - lastHrDataMs else 0L
        logHrBatch(gapMs, lastHrBatchPoints, lastHrBatchIbi)
    }

    /** La fiecare 15s — vizibil în logcat chiar fără rafală Samsung. */
    private fun logHrPulse() {
        val now = System.currentTimeMillis()
        if (now - lastHrPulseLogMs < 15_000L) return
        lastHrPulseLogMs = now
        val gapMs = if (lastHrDataMs > 0L) now - lastHrDataMs else -1L
        val trackerOk = heartRateTracker != null
        synchronized(bufferLock) {
            Log.i(
                TAG,
                "HR pulse: tracker=$trackerOk gapMs=$gapMs ibiWin=${ibiWindow.size} " +
                    "lastHr=$lastHr scr=${if (displayOn) "ON" else "OFF"} lean=$leanMqtt"
            )
        }
    }

    /**
     * Lean: IBI rămâne doar în ibiWindow pe ceas (RMSSD acolo).
     * Batch-uri 4–6 la 4s pe MQTT nu ajută LF/HF (prea puține) — nu mai publicăm.
     */
    private fun discardLeanIbiBuffer() {
        synchronized(bufferLock) {
            ibiSamples.clear()
        }
    }

    private fun flushAllMqttBuffers() {
        if (leanMqtt) {
            synchronized(bufferLock) {
                ppgSamples.clear()
                skinSamples.clear()
                accSamples.clear()
                hrSamples.clear()
                ibiSamples.clear()
                gyroSamples.clear()
                lastStepJson = null
            }
            return
        }
        synchronized(bufferLock) {
            flushBuffer("biofizic/ppg", ppgSamples)
            flushBuffer("biofizic/skin_temp", skinSamples)
            flushBuffer("biofizic/acc", accSamples)
            flushBuffer("biofizic/hr", hrSamples)
            flushBuffer("biofizic/ibi", ibiSamples)
            flushBuffer("biofizic/gyro", gyroSamples)
            lastStepJson?.let {
                publish("biofizic/step", it)
                lastStepJson = null
            }
        }
    }

    private fun flushBuffer(topic: String, buffer: MutableList<String>) {
        if (buffer.isEmpty()) return
        publish(topic, """{"n":${buffer.size},"samples":[${buffer.joinToString(",")}]}""")
        buffer.clear()
    }

    private fun requestProfileRecalibration() {
        val ts = System.currentTimeMillis()
        publish(
            "biofizic/cmd/calibrate",
            """{"ts":$ts,"action":"profile","source":"watch"}""",
        )
        calibrationPhase = "collecting"
        calibrationMessage = "Recalibrare… profil vechi activ ~5 min"
        Log.i(TAG, "Recalibrare profil trimisă pe MQTT")
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
        } catch (e: JSONException) {
            Log.w(TAG, "parseCalibrationStatus: ${e.message}")
        }
    }

    private fun parseCombinedMessage(json: String) {
        try {
            val obj = JSONObject(json)
            val a10 = obj.optInt("arousal_10", -1)
            if (a10 < 0) return
            lastStateMessageMs = System.currentTimeMillis()
            arousal10 = a10.coerceIn(0, 10)
            arousalFused = a10.toFloat().coerceIn(0f, 10f)
            valence10 = obj.optInt("valence_10", -1)
            affectQuadrant = obj.optString("affect_quadrant", "—").ifEmpty { "—" }
            arousalConfidence = obj.optDouble("confidence", 0.0).toFloat()
            emotionLabel = obj.optString("emotion", "—").ifEmpty { "—" }
            profileReady = obj.optBoolean("profile_ready", profileReady)
            val mode = obj.optString("activity_mode", "")
            motionGated = mode == "LOCOMOTION"
            val hrFromCombined = obj.optInt("hr", 0)
            if (hrFromCombined > 0) lastHr = hrFromCombined
        } catch (e: JSONException) {
            Log.w(TAG, "parseCombinedMessage: ${e.message}")
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
            val obj = JSONObject(json)
            val a10 = obj.optInt("arousal_10", -1)
            if (a10 >= 0) serverArousal10 = a10
            serverEmotion = obj.optString("emotion", "—").ifEmpty { "—" }
            serverActivityMode = obj.optString("activity_mode", "UNKNOWN").ifEmpty { "UNKNOWN" }
            serverMotionZ = obj.optDouble("motion_z", 0.0).toFloat()
            serverZHr = obj.optDouble("z_hr", 0.0).toFloat()
            if (obj.has("profile_ready")) {
                profileReady = obj.optBoolean("profile_ready", profileReady)
            }
        } catch (e: JSONException) {
            Log.w(TAG, "parseStateLiveMessage: ${e.message}")
        }
    }

    private fun publishWatchLive() {
        if (!liveStreamEnabled || !liveWatchEnabled) return
        val ts = System.currentTimeMillis()
        val secSinceIbi = if (lastIbiTimestampMs > 0) (ts - lastIbiTimestampMs) / 1000.0 else -1.0

        var accRms = 0.0
        var accMag = 0.0
        var gyroRms = 0.0
        var ppgN = 0
        var ppgGreenMean = 0.0
        var ppgGreenStd = 0.0
        var ppgIrMean = 0.0
        var ppgRedMean = 0.0
        var ibiN = 0
        var rmssdLive = 0.0
        var sdnnLive = 0.0
        var meanHrLive = 0.0
        var meanIbiLive = 0.0
        var pnn50Live = 0.0
        var windowSec = 0.0
        var hrvReady = false

        var accStd = 0.0
        var gyroP90 = 0.0
        var gyroStd = 0.0

        synchronized(bufferLock) {
            if (accMagnitudes.isNotEmpty()) {
                val tail = accMagnitudes.takeLast(minOf(40, accMagnitudes.size))
                val dyn = tail.map { abs(it - 9.81) }
                accRms = sqrt(dyn.map { it * it }.average())
                accMag = tail.average()
                if (dyn.size > 1) {
                    val mean = dyn.average()
                    accStd = sqrt(dyn.map { (it - mean) * (it - mean) }.average())
                }
            }
            if (gyroMagnitudes.isNotEmpty()) {
                val tail = gyroMagnitudes.takeLast(minOf(40, gyroMagnitudes.size))
                gyroRms = sqrt(tail.map { it * it }.average())
                if (tail.isNotEmpty()) {
                    val sorted = tail.sorted()
                    gyroP90 = sorted[(sorted.size * 0.9).toInt().coerceAtMost(sorted.size - 1)]
                }
                if (tail.size > 1) {
                    val mean = tail.average()
                    gyroStd = sqrt(tail.map { (it - mean) * (it - mean) }.average())
                }
            }

            ppgN = livePpgGreen.size
            if (ppgN > 0) {
                ppgGreenMean = livePpgGreen.map { it.toDouble() }.average()
                ppgIrMean = livePpgIr.map { it.toDouble() }.average()
                ppgRedMean = livePpgRed.map { it.toDouble() }.average()
                if (ppgN > 1) {
                    val varG = livePpgGreen.map { (it - ppgGreenMean) * (it - ppgGreenMean) }.average()
                    ppgGreenStd = sqrt(varG)
                }
            }
            livePpgGreen.clear()
            livePpgIr.clear()
            livePpgRed.clear()

            ibiN = ibiWindow.size
            val hrv = HrvFeatureCalculator.compute(ibiWindow.toList())
            if (hrv != null) {
                rmssdLive = hrv.rmssd
                sdnnLive = hrv.sdnn
                meanHrLive = hrv.meanHrBpm
                meanIbiLive = hrv.meanIbiMs
                pnn50Live = hrv.pnn50
                windowSec = hrv.windowSec
                lastRmssd = hrv.rmssd
                lastWindowSec = hrv.windowSec
                hrvReady = ibiN >= MIN_IBI_FOR_HRV && windowSec >= MIN_WINDOW_SEC_FOR_SIGNAL
                signalOk = hrvReady
            }
        }

        val arousal10Val = if (arousal10 >= 0) arousal10 else -1
        val serverA10 = if (serverArousal10 >= 0) serverArousal10 else -1

        val payload = buildString {
            append("""{"ts":$ts,"live":true,"display_on":$displayOn,"mqtt_connected":$isMqttConnected,"hr":$lastHr,"hr_status":${if (lastHr > 0) 1 else 0},"acc_rms":${jsonFloat(accRms, 3)},"acc_mag":${jsonFloat(accMag, 3)},"gyro_rms":${jsonFloat(gyroRms, 4)},"skin_temp_c":${jsonFloat(lastSkinTempC, 2)},"ambient_temp_c":${jsonFloat(lastAmbientTempC, 2)},"ppg_n":$ppgN,"ppg_green_mean":${jsonFloat(ppgGreenMean, 1)},"ppg_green_std":${jsonFloat(ppgGreenStd, 1)},"ppg_ir_mean":${jsonFloat(ppgIrMean, 1)},"ppg_red_mean":${jsonFloat(ppgRedMean, 1)},"steps":${lastSteps.toInt()}""")
            append(""","ibi_n":$ibiN,"ibi_window_sec":${jsonFloat(windowSec, 1)},"sec_since_ibi":${jsonFloat(secSinceIbi, 1)},"hrv_ready":$hrvReady,"rmssd_live":${jsonFloat(rmssdLive, 2)},"sdnn_live":${jsonFloat(sdnnLive, 2)},"mean_hr_live":${jsonFloat(meanHrLive, 1)},"mean_ibi_live":${jsonFloat(meanIbiLive, 1)},"pnn50_live":${jsonFloat(pnn50Live, 1)}""")
            append(""","arousal_10":$arousal10Val,"valence_10":${if (valence10 >= 0) valence10 else -1},"affect_quadrant":"${affectQuadrant.replace("\"", "")}","confidence":${jsonFloat(arousalConfidence.toDouble(), 3)},"emotion":"${emotionLabel.replace("\"", "")}","profile_ready":$profileReady,"signal_ok":$signalOk""")
            append(""","server_arousal_10":$serverA10,"server_emotion":"${serverEmotion.replace("\"", "")}","server_activity_mode":"${serverActivityMode.replace("\"", "")}","server_motion_z":${jsonFloat(serverMotionZ.toDouble(), 3)},"server_z_hr":${jsonFloat(serverZHr.toDouble(), 3)}}""")
        }
        publish("biofizic/watch/live", payload)
        publishAcquisitionBatches(
            ts = ts,
            accRms = accRms,
            accMag = accMag,
            gyroRms = gyroRms,
            accStd = accStd,
            gyroP90 = gyroP90,
            gyroStd = gyroStd,
        )
        // Compatibilitate pipeline existent
        if (accRms > 0) {
            publish(
                "biofizic/acc/live",
                """{"ts":$ts,"acc_rms":${jsonFloat(accRms, 3)},"sma_g":${jsonFloat(accRms, 3)}}"""
            )
        }
    }

    private fun publishAcquisitionBatches(
        ts: Long,
        accRms: Double,
        accMag: Double,
        gyroRms: Double,
        accStd: Double,
        gyroP90: Double,
        gyroStd: Double,
    ) {
        publishIbiBatch(ts)
        publishPpgBatch(ts)
        val accP90 = accRms * 1.2
        val payload = buildString {
            append("""{"ts":$ts,"hr":$lastHr,"acc_rms":${jsonFloat(accRms, 3)},"acc_p90":${jsonFloat(accP90, 3)},"acc_std":${jsonFloat(accStd, 3)},"gyro_rms":${jsonFloat(gyroRms, 4)},"gyro_p90":${jsonFloat(gyroP90, 4)},"gyro_std":${jsonFloat(gyroStd, 4)},"skin_temp":${jsonFloat(lastSkinTempC, 2)},"ambient_temp":${jsonFloat(lastAmbientTempC, 2)},"displayOn":$displayOn}""")
        }
        publish("biofizic/sensors/batch", payload)
    }

    private fun publishIbiBatch(ts: Long) {
        synchronized(bufferLock) {
            val cutoff = ts - 1_000L
            val recent = ibiWindow.filter { it.ts >= cutoff }
            if (recent.isEmpty()) return
            val ibiMs = recent.joinToString(",") { it.ibiMs.toString() }
            val ibiTs = recent.joinToString(",") { it.ts.toString() }
            publish("biofizic/ibi/batch", """{"ts":$ts,"ibi_ms":[$ibiMs],"ibi_ts":[$ibiTs]}""")
        }
    }

    private fun publishPpgBatch(ts: Long) {
        val snapshot: List<Triple<Long, Int, Int>>
        synchronized(ppgBatchLock) {
            val cutoff = ts - 1_000L
            snapshot = ppgBatch.filter { it.first >= cutoff }
        }
        if (snapshot.isEmpty()) return
        val tsArr = snapshot.joinToString(",") { it.first.toString() }
        val gArr = snapshot.joinToString(",") { it.second.toString() }
        val irArr = snapshot.joinToString(",") { it.third.toString() }
        publish(
            "biofizic/ppg/batch",
            """{"ts":$ts,"ts_ms":[$tsArr],"green":[$gArr],"ir":[$irArr]}"""
        )
    }

    private fun publishAccLive() {
        if (!liveStreamEnabled) return
        val rms: Double
        synchronized(bufferLock) {
            if (accMagnitudes.isEmpty()) return
            val tail = accMagnitudes.takeLast(minOf(40, accMagnitudes.size))
            val dyn = tail.map { kotlin.math.abs(it - 9.81) }
            rms = kotlin.math.sqrt(dyn.map { it * it }.average())
        }
        val ts = System.currentTimeMillis()
        publish(
            "biofizic/acc/live",
            """{"ts":$ts,"acc_rms":${jsonFloat(rms, 3)},"sma_g":${jsonFloat(rms, 3)}}"""
        )
    }

    private fun publishHrLive(hr: Int, status: Int) {
        if (!liveStreamEnabled || hr <= 0) return
        val ts = System.currentTimeMillis()
        publish(
            "biofizic/hr/live",
            """{"ts":$ts,"hr":$hr,"status":$status,"source":"samsung_sdk"}"""
        )
    }

    private fun publish(topic: String, payload: String, retain: Boolean = false) {
        try {
            if (::mqttClient.isInitialized && mqttClient.isConnected) {
                mqttClient.publish(topic, MqttMessage(payload.toByteArray()).apply {
                    qos = 0
                    isRetained = retain
                })
                msgCount++
            }
        } catch (e: Exception) {
            Log.w(TAG, "MQTT publish eroare pe $topic: ${e.message}")
        }
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

                // Verifica MQTT
                val connected = ::mqttClient.isInitialized && mqttClient.isConnected
                isMqttConnected = connected
                if (!connected && !mqttConnecting) {
                    Log.w(TAG, "MQTT deconectat, reconectare...")
                    connectMqtt()
                    Thread.sleep(5_000)  // pauză după tentativă, indiferent de succes
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

                if (lastIbiTimestampMs > 0 && isRunning) {
                    val ibiStaleSec = (System.currentTimeMillis() - lastIbiTimestampMs) / 1000
                    if (ibiStaleSec > 45L && heartRateTracker != null) {
                        Log.w(TAG, "HR tracker inactiv de ${ibiStaleSec}s — restart forțat")
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
        valence10 = -1
        affectQuadrant = "—"
        arousalConfidence = 0f
        emotionLabel = "—"
        motionGated = false
        profileReady = false
        signalOk = false
        lastWindowSec = 0.0
        lastStateMessageMs = 0L
        stopPublishLoops()
        publishEpoch()
        flushAllMqttBuffers()
        unregisterScreenReceiver()
        synchronized(bufferLock) {
            ppgSamples.clear()
            skinSamples.clear()
            accSamples.clear()
            hrSamples.clear()
            ibiSamples.clear()
            gyroSamples.clear()
            ibiWindow.clear()
            lastIbiTimestampMs = 0L
            accMagnitudes.clear()
            gyroMagnitudes.clear()
            livePpgGreen.clear()
            livePpgIr.clear()
            livePpgRed.clear()
            lastStepJson = null
        }
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
        if (::mqttClient.isInitialized && mqttClient.isConnected) {
            try { mqttClient.disconnect() } catch (_: Exception) {}
        }
        isMqttConnected = false

        activeSensors = 0
        lastHr = 0
        msgCount = 0
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