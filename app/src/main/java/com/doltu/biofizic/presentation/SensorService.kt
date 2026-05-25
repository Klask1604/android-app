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
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

class SensorService : Service(), SensorEventListener {

    /** MQTT JSON must use Locale.US decimal point, not comma. */
    private fun jsonFloat(value: Double, decimals: Int): String =
        String.format(Locale.US, "%.${decimals}f", value)

    // ── Android sensors ──
    private lateinit var sensorManager: SensorManager

    // ── MQTT ──
    private lateinit var mqttClient: MqttClient
    private val BROKER_URL: String by lazy { getString(R.string.mqtt_broker_url) }
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
        /** Local HRV/signalOk refresh interval (extended log only). */
        @Volatile var hrvPublishIntervalMs = 30_000L

        /** Flush SDK HR to fill ibiWindow (GW7 bursts ~4s). */
        @Volatile var hrFlushIntervalMs = 4_000L

        /** 1 Hz publish for ibi/sensors/ppg batch topics. */
        @Volatile var liveWatchEnabled = true
        private const val LIVE_WATCH_INTERVAL_MS = 1_000L

        @Volatile var liveStreamEnabled = true

        /** Raw PPG batch publishing for external DSP pipeline (dev only). */
        @Volatile var ppgRawEnabled = false

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

        /** Keep legacy ibi/ppg/sensors topics during v2 rollout (1–2 weeks). */
        @Volatile var publishLegacyBatches = true

        private val _uiState = MutableStateFlow(UiState())
        val uiState: StateFlow<UiState> = _uiState.asStateFlow()

        fun syncUiState() {
            _uiState.value = UiState(
                isRunning = isRunning,
                isMqttConnected = isMqttConnected,
                lastHr = lastHr,
                arousalFused = arousalFused,
                arousal10 = arousal10,
                valence10 = valence10,
                emotionLabel = emotionLabel,
                arousalConfidence = arousalConfidence,
                motionGated = motionGated,
                profileReady = profileReady,
                signalOk = signalOk,
                lastWindowSec = lastWindowSec,
                calibrationPhase = calibrationPhase,
                calibrationMessage = calibrationMessage,
            )
        }

        private const val MAX_IBI_ENTRIES = 200
        private const val IBI_RETENTION_MS = 120_000L
        private const val MIN_IBI_FOR_HRV = 8
        private const val MIN_WINDOW_SEC_FOR_SIGNAL = 6.0
        private const val MAX_ACC_MAGNITUDES = 750  // ~25 Hz × 30 s
        private const val WAKE_LOCK_TIMEOUT_MS = 4 * 60 * 60 * 1000L
    }

    private var scanOnlyMode = false
    private var accScaleLogRemaining = 5
    private var screenReceiverRegistered = false
    private var displayOnLocal = true
    private val ibiWindow = ArrayDeque<IbiWindowEntry>(220)
    /** IBI acceptate de la ultimul publish MQTT — evită golire la HR burst 4s vs publish 1s. */
    private val ibiPendingPublish = ArrayDeque<IbiWindowEntry>(64)
    private val accDynSamples = ArrayDeque<TimedSample>(800)
    private val gyroDynSamples = ArrayDeque<TimedSample>(800)
    @Volatile private var lastIbiTimestampMs = 0L
    @Volatile private var lastSkinTempTsMs = 0L
    private var acquisitionSeq = 0L
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

    private val bufferLock = Any()

    // PPG batch for ppg-processor (biofizic/ppg/batch at 1 Hz)
    private val ppgBatchLock = Any()
    private val ppgBatch = mutableListOf<Triple<Long, Int, Int>>()  // (ts_ms, green, ir)

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
            updateSignalStatus()
            handler.postDelayed(this, hrvPublishIntervalMs)
        }
    }

    private val liveWatchRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) {
                liveWatchLoopRunning = false
                return
            }
            publishSensorBatches()
            handler.postDelayed(this, LIVE_WATCH_INTERVAL_MS)
        }
    }

    private val mqttPublishRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) {
                mqttPublishLoopRunning = false
                return
            }
            logPeriodicStatus()
            handler.postDelayed(this, mqttPublishIntervalMs)
        }
    }

    private fun updateDisplayState(on: Boolean) {
        displayOnLocal = on
        displayOn = on
        hrvPublishIntervalMs = 30_000L
        mqttPublishIntervalMs = 1_000L
        hrFlushIntervalMs = 4_000L
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
                logTimestampProbe("HR", dp.timestamp, recvMs)
                val hr = dp.getValue(ValueKey.HeartRateSet.HEART_RATE)
                val status = dp.getValue(ValueKey.HeartRateSet.HEART_RATE_STATUS)

                if (status == 1 && hr > 0) {
                    lastHr = hr
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
                            val (timestamps, source) = buildIbiTimestamps(accepted, dp.timestamp, recvMs)
                            logIbiTimestampDecision(dp.timestamp, recvMs, source, accepted.size)
                            for (i in accepted.indices) {
                                val entry = IbiWindowEntry(accepted[i], timestamps[i], source)
                                ibiWindow.addLast(entry)
                                ibiPendingPublish.addLast(entry)
                                batchIbi++
                            }
                            lastIbiTimestampMs = timestamps.last()
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
                    syncUiState()
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
            "MQTT: batch la ${mqttPublishIntervalMs}ms; ppg/raw=${if (ppgRawEnabled) "ON" else "OFF"}"
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
                    gyroDynSamples.addLast(TimedSample(ts, gMag))
                    while (gyroDynSamples.size > MAX_ACC_MAGNITUDES) {
                        gyroDynSamples.removeFirst()
                    }
                }
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
            syncUiState()
        } catch (e: Exception) {
            isMqttConnected = false
            Log.e(TAG, "MQTT eroare conectare la $BROKER_URL: ${e.message}")
        } finally {
            mqttConnecting = false
        }
    }


    private fun enqueuePpg(dataPoints: List<DataPoint>) {
        val recvMs = System.currentTimeMillis()
        var shouldFlushRaw = false
        synchronized(ppgBatchLock) {
            for (dp in dataPoints) {
                logTimestampProbe("PPG", dp.timestamp, recvMs)
                val g = dp.getValue(ValueKey.PpgSet.PPG_GREEN)
                val ir = dp.getValue(ValueKey.PpgSet.PPG_IR)
                ppgBatch.add(Triple(dp.timestamp, g, ir))
            }
            shouldFlushRaw = ppgRawEnabled && ppgBatch.isNotEmpty()
        }
        if (shouldFlushRaw) {
            handler.post { flushPpgBatch() }
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
                if ((status == 0 || status == -1) && skin > 0) {
                    lastSkinTempC = skin.toDouble()
                    lastAmbientTempC = amb.toDouble()
                    lastSkinTempTsMs = dp.timestamp
                }
            }
        }
    }

    private fun enqueueAcc(dataPoints: List<DataPoint>) {
        val recvMs = System.currentTimeMillis()
        synchronized(bufferLock) {
            for (dp in dataPoints) {
                logTimestampProbe("ACC", dp.timestamp, recvMs)
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
                val mag = sqrt(xf * xf + yf * yf + zf * zf)
                val dyn = abs(mag - 9.81)
                accDynSamples.addLast(TimedSample(dp.timestamp, dyn))
                while (accDynSamples.size > MAX_ACC_MAGNITUDES) {
                    accDynSamples.removeFirst()
                }
            }
        }
    }

    private fun logTimestampProbe(stream: String, dpTs: Long, recvMs: Long) {
        val norm = normalizeSensorTimestampMs(dpTs)
        val skew = if (norm > 0L) recvMs - norm else -1L
        if (timestampProbeEnabled) {
            Log.d(TAG, "TS_PROBE $stream raw=$dpTs norm=$norm recv=$recvMs skew=$skew")
        }
        if (stream == "PPG" && isEpochMillis(norm)) {
            lastKnownSkewMs = skew
        }
    }

    private fun logIbiTimestampDecision(rawDpTs: Long, recvMs: Long, source: String, count: Int) {
        val norm = normalizeSensorTimestampMs(rawDpTs)
        val skew = if (norm > 0L) recvMs - norm else -1L
        val now = System.currentTimeMillis()
        if (now - lastIbiSourceLogMs < 3_000L) return
        lastIbiSourceLogMs = now
        Log.i(
            TAG,
            "IBI_TS source=$source count=$count rawDpTs=$rawDpTs normDpTs=$norm skewMs=$skew epoch=${isEpochMillis(norm)}",
        )
    }

    /** Samsung SDK: epoch ms; uneori ns sau 0 pe HR — normalizăm ce putem. */
    private fun normalizeSensorTimestampMs(raw: Long): Long {
        if (raw <= 0L) return 0L
        if (raw > 1_000_000_000_000_000L) return raw / 1_000_000L
        return raw
    }

    private fun isEpochMillis(ts: Long): Boolean = ts in 1_000_000_000_000L..2_500_000_000_000L

    @Volatile private var lastKnownSkewMs: Long? = null

    private fun buildIbiTimestamps(
        accepted: List<Int>,
        dpTs: Long,
        recvMs: Long,
    ): Pair<LongArray, String> {
        val normDp = normalizeSensorTimestampMs(dpTs)
        val useDp = isEpochMillis(normDp)
        val anchor = when {
            useDp -> normDp
            else -> recvMs
        }
        val source = if (useDp) "dp_timestamp" else "reconstructed"
        val timestamps = LongArray(accepted.size)
        var endTs = anchor
        for (i in accepted.indices.reversed()) {
            timestamps[i] = endTs
            endTs -= accepted[i]
        }
        return Pair(timestamps, source)
    }

    /** IBI noi de la ultimul cadru MQTT (nu filtru 1s — HR vine la ~4s). */
    private fun drainIbiForPublish(tsPublish: Long): List<IbiWindowEntry> {
        synchronized(bufferLock) {
            if (ibiPendingPublish.isNotEmpty()) {
                val slice = ibiPendingPublish.toList()
                ibiPendingPublish.clear()
                return slice
            }
            val cutoff = tsPublish - 4_500L
            return ibiWindow.filter { it.ts >= cutoff }
        }
    }

    private data class MotionWindowStats(
        val accRms: Double,
        val accP90: Double,
        val accStd: Double,
        val gyroRms: Double,
        val gyroP90: Double,
        val gyroStd: Double,
    )

    private fun percentile90(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val idx = ((sorted.size - 1) * 0.9).toInt().coerceIn(0, sorted.lastIndex)
        return sorted[idx]
    }

    private fun computeMotionStats(tsPublish: Long): MotionWindowStats {
        val cutoff = tsPublish - 1_000L
        synchronized(bufferLock) {
            val accDyn = accDynSamples.filter { it.ts >= cutoff }.map { it.value }
            val gyroVals = gyroDynSamples.filter { it.ts >= cutoff }.map { it.value }
            val accRms = if (accDyn.isEmpty()) {
                0.0
            } else {
                sqrt(accDyn.map { it * it }.average())
            }
            val accP90 = percentile90(accDyn)
            val accStd = if (accDyn.size > 1) {
                val mean = accDyn.average()
                sqrt(accDyn.map { (it - mean) * (it - mean) }.average())
            } else {
                0.0
            }
            val gyroRms = if (gyroVals.isEmpty()) {
                0.0
            } else {
                sqrt(gyroVals.map { it * it }.average())
            }
            val gyroP90 = percentile90(gyroVals)
            val gyroStd = if (gyroVals.size > 1) {
                val mean = gyroVals.average()
                sqrt(gyroVals.map { (it - mean) * (it - mean) }.average())
            } else {
                0.0
            }
            return MotionWindowStats(accRms, accP90, accStd, gyroRms, gyroP90, gyroStd)
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

    /** Extended HRV log at 30s (signalOk updated at 1 Hz in publishSensorBatches). */
    private fun updateSignalStatus() {
        synchronized(bufferLock) {
            trimIbiWindow()
            Log.i(
                TAG,
                "HRV extended: ibiWin=${ibiWindow.size} win=${"%.0f".format(lastWindowSec)}s " +
                    "rmssd=${"%.1f".format(lastRmssd)} signalOk=$signalOk hr=$lastHr"
            )
        }
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
                    "lastHr=$lastHr scr=${if (displayOn) "ON" else "OFF"}"
            )
        }
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
            syncUiState()
        } catch (e: JSONException) {
            Log.w(TAG, "parseCalibrationStatus: ${e.message}")
        }
    }

    private fun applyServerDecision(obj: JSONObject) {
        val a10 = obj.optInt("arousal_10", -1)
        if (a10 < 0) return
        lastStateMessageMs = System.currentTimeMillis()
        arousal10 = a10.coerceIn(0, 10)
        arousalFused = a10.toFloat().coerceIn(0f, 10f)
        valence10 = obj.optInt("valence_10", -1)
        affectQuadrant = obj.optString("affect_quadrant", "—").ifEmpty { "—" }
        arousalConfidence = obj.optDouble("confidence", 0.0).toFloat()
        emotionLabel = obj.optString("emotion", "—").ifEmpty { "—" }
        if (obj.has("profile_ready")) {
            profileReady = obj.optBoolean("profile_ready", profileReady)
        }
        val mode = obj.optString("activity_mode", "")
        if (mode.isNotEmpty()) {
            motionGated = mode == "LOCOMOTION"
        }
        syncUiState()
    }

    private fun parseCombinedMessage(json: String) {
        try {
            val obj = JSONObject(json)
            applyServerDecision(obj)
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
            applyServerDecision(JSONObject(json))
        } catch (e: JSONException) {
            Log.w(TAG, "parseStateLiveMessage: ${e.message}")
        }
    }

    private fun publishSensorBatches() {
        if (!liveStreamEnabled || !liveWatchEnabled) return
        val tsPublish = System.currentTimeMillis()
        val motion = computeMotionStats(tsPublish)

        synchronized(bufferLock) {
            val hrv = HrvFeatureCalculator.compute(ibiWindow.toList())
            if (hrv != null) {
                lastRmssd = hrv.rmssd
                lastWindowSec = hrv.windowSec
                signalOk = ibiWindow.size >= MIN_IBI_FOR_HRV &&
                    hrv.windowSec >= MIN_WINDOW_SEC_FOR_SIGNAL
            }
        }

        val ppgSnapshot = snapshotPpgWindow(tsPublish)
        val ibiSlice = drainIbiForPublish(tsPublish)
        publishAcquisitionBatchV2(tsPublish, motion, ppgSnapshot, ibiSlice)
        if (publishLegacyBatches) {
            publishLegacyAcquisitionBatches(tsPublish, motion, ppgSnapshot, ibiSlice)
        }
        syncUiState()
    }

    private fun snapshotPpgWindow(tsPublish: Long): List<Triple<Long, Int, Int>> {
        synchronized(ppgBatchLock) {
            val cutoff = tsPublish - 1_000L
            val snap = ppgBatch.filter { it.first >= cutoff }
            ppgBatch.removeAll { it.first >= cutoff }
            return snap
        }
    }

    private fun publishAcquisitionBatchV2(
        tsPublish: Long,
        motion: MotionWindowStats,
        ppgSnapshot: List<Triple<Long, Int, Int>>,
        ibiSlice: List<IbiWindowEntry>,
    ) {
        val ibiSource = ibiSlice.lastOrNull()?.tsSource ?: "reconstructed"
        var tsAnchor = tsPublish
        ibiSlice.maxOfOrNull { it.ts }?.let { tsAnchor = maxOf(tsAnchor, it) }
        ppgSnapshot.maxOfOrNull { it.first }?.let { tsAnchor = maxOf(tsAnchor, it) }
        if (lastSkinTempTsMs > 0L) {
            tsAnchor = maxOf(tsAnchor, lastSkinTempTsMs)
        }

        acquisitionSeq++
        val payload = buildString {
            append(
                """{"schema":2,"seq":$acquisitionSeq,"ts_publish":$tsPublish,"ts_anchor":$tsAnchor,"clock":"wall_ms"""",
            )
            append(""","hr":$lastHr,"display_on":$displayOn""")
            append(""","skin_temp":${jsonFloat(lastSkinTempC, 2)},"skin_temp_ts":$lastSkinTempTsMs""")
            append(""","ambient_temp":${jsonFloat(lastAmbientTempC, 2)}""")
            append(
                ""","motion":{"acc_rms":${jsonFloat(motion.accRms, 3)},"acc_p90":${jsonFloat(motion.accP90, 3)},"acc_std":${jsonFloat(motion.accStd, 3)},"gyro_rms":${jsonFloat(motion.gyroRms, 4)},"gyro_p90":${jsonFloat(motion.gyroP90, 4)},"gyro_std":${jsonFloat(motion.gyroStd, 4)},"window_ms":1000}""",
            )
            append(""","ibi":{"ms":[""")
            append(ibiSlice.joinToString(",") { it.ibiMs.toString() })
            append("],\"ts\":[")
            append(ibiSlice.joinToString(",") { it.ts.toString() })
            append("],\"source\":\"")
            append(ibiSource)
            append("\"}")
            append(""","ppg":{"ts_ms":[""")
            append(ppgSnapshot.joinToString(",") { it.first.toString() })
            append("],\"green\":[")
            append(ppgSnapshot.joinToString(",") { it.second.toString() })
            append("],\"ir\":[")
            append(ppgSnapshot.joinToString(",") { it.third.toString() })
            append("]}")
            append("}")
        }
        publish("biofizic/acquisition/batch", payload)
        if (ibiSlice.isNotEmpty() || ppgSnapshot.isNotEmpty()) {
            Log.i(
                TAG,
                "acquisition seq=$acquisitionSeq ibi=${ibiSlice.size} source=$ibiSource ppg=${ppgSnapshot.size}",
            )
        }
    }

    private fun publishLegacyAcquisitionBatches(
        ts: Long,
        motion: MotionWindowStats,
        ppgSnapshot: List<Triple<Long, Int, Int>>,
        ibiSlice: List<IbiWindowEntry>,
    ) {
        publishIbiBatchFromSlice(ibiSlice, ts)
        publishPpgBatchFromSnapshot(ppgSnapshot)
        val payload = buildString {
            append(
                """{"ts":$ts,"hr":$lastHr,"acc_rms":${jsonFloat(motion.accRms, 3)},"acc_p90":${jsonFloat(motion.accP90, 3)},"acc_std":${jsonFloat(motion.accStd, 3)},"gyro_rms":${jsonFloat(motion.gyroRms, 4)},"gyro_p90":${jsonFloat(motion.gyroP90, 4)},"gyro_std":${jsonFloat(motion.gyroStd, 4)},"skin_temp":${jsonFloat(lastSkinTempC, 2)},"ambient_temp":${jsonFloat(lastAmbientTempC, 2)},"displayOn":$displayOn}""",
            )
        }
        publish("biofizic/sensors/batch", payload)
    }

    private fun publishIbiBatchFromSlice(recent: List<IbiWindowEntry>, ts: Long) {
        if (recent.isEmpty()) return
        val ibiMs = recent.joinToString(",") { it.ibiMs.toString() }
        val ibiTs = recent.joinToString(",") { it.ts.toString() }
        publish("biofizic/ibi/batch", """{"ts":$ts,"ibi_ms":[$ibiMs],"ibi_ts":[$ibiTs]}""")
    }

    private fun publishIbiBatch(ts: Long) {
        val recent = drainIbiForPublish(ts)
        publishIbiBatchFromSlice(recent, ts)
    }

    private fun publishPpgBatchFromSnapshot(snapshot: List<Triple<Long, Int, Int>>) {
        if (snapshot.isEmpty()) return
        val tsArr = snapshot.joinToString(",") { it.first.toString() }
        val gArr = snapshot.joinToString(",") { it.second.toString() }
        val irArr = snapshot.joinToString(",") { it.third.toString() }
        publish(
            "biofizic/ppg/batch",
            """{"ts":${snapshot.last().first},"ts_ms":[$tsArr],"green":[$gArr],"ir":[$irArr]}""",
        )
    }

    private fun publishPpgBatch(ts: Long) {
        val snapshot: List<Triple<Long, Int, Int>>
        synchronized(ppgBatchLock) {
            val cutoff = ts - 1_000L
            snapshot = ppgBatch.filter { it.first >= cutoff }
            if (snapshot.isEmpty()) return
            ppgBatch.removeAll { it.first >= cutoff }
        }
        publishPpgBatchFromSnapshot(snapshot)
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
        updateSignalStatus()
        unregisterScreenReceiver()
        synchronized(bufferLock) {
            ibiWindow.clear()
            ibiPendingPublish.clear()
            lastIbiTimestampMs = 0L
            accDynSamples.clear()
            gyroDynSamples.clear()
        }
        synchronized(ppgBatchLock) {
            ppgBatch.clear()
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
        acquisitionSeq = 0L
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