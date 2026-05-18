package com.doltu.biofizic.presentation

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.*
import android.util.Log
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
import kotlin.math.sqrt

class SensorService : Service(), SensorEventListener {

    /** JSON MQTT trebuie punct zecimal (Locale.US), nu virgulă RO. */
    private fun jsonFloat(value: Double, decimals: Int): String =
        String.format(Locale.US, "%.${decimals}f", value)

    // ── Android sensors ──
    private lateinit var sensorManager: SensorManager

    // ── MQTT ──
    private lateinit var mqttClient: MqttClient
    private val BROKER_URL = "tcp://paxbespoke.automateflow.ro:1883"
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
    //  Companion: stare expusa catre UI
    // ══════════════════════════════════════════
    companion object {
        const val ACTION_START = "com.doltu.biofizic.ACTION_START"
        const val ACTION_STOP = "com.doltu.biofizic.ACTION_STOP"
        /** Conectează Samsung SDK, listează senzorii și se oprește (fără tracking MQTT). */
        const val ACTION_SCAN = "com.doltu.biofizic.ACTION_SCAN"

        @Volatile var isRunning = false
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
         * La câte ms se trimit datele pe MQTT (toate tipurile).
         * Eșantioanele se acumulează și pleacă într-un singur mesaj per topic.
         * Senzorul Samsung tot măsoară la rata lui (ex. PPG ~25 Hz); MQTT doar la 1 s.
         */
        /** Batch PPG/IBI/HR pe MQTT (1 s). */
        @Volatile var mqttPublishIntervalMs = 1_000L
        /** HRV + features pentru clasificator (500 ms). */
        @Volatile var hrvPublishIntervalMs = 500L

        /** PPG: câte un mesaj MQTT per eșantion pe biofizic/ppg/stream (pentru semnal + jitter). */
        @Volatile var ppgStreamEnabled = false

        @Volatile var lastSkinTempC = 0.0
        @Volatile var lastAmbientTempC = 0.0

        @Volatile var displayOn = true
        @Volatile var backgroundSensorsGranted = false
        @Volatile var lastRmssd = 0.0

        /** Din biofizic/state (clasificator v2 pe VPS). */
        @Volatile var arousal10 = -1
        @Volatile var arousalConfidence = 0f
        @Volatile var emotionLabel = "—"
        @Volatile var motionGated = false
        @Volatile var profileReady = false
        @Volatile var signalOk = false
        @Volatile var lastWindowSec = 0.0
        @Volatile var lastStateMessageMs = 0L

        private const val MAX_IBI_ENTRIES = 200
        private const val IBI_RETENTION_MS = 90_000L
        /** Fereastră HRV: ultimele 60 s (timp perete). */
        private const val HRV_WINDOW_MS = 60_000L
        private const val MIN_IBI_FOR_HRV = 12
        private const val MIN_WINDOW_SEC_FOR_SIGNAL = 10.0
        private const val MAX_ACC_MAGNITUDES = 64
    }

    private var scanOnlyMode = false
    private var screenReceiverRegistered = false
    private var displayOnLocal = true
    private val ibiWindow = ArrayDeque<IbiWindowEntry>(220)
    private val accMagnitudes = mutableListOf<Double>()
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
    private val skinSamples = mutableListOf<String>()
    private val accSamples = mutableListOf<String>()
    private val hrSamples = mutableListOf<String>()
    private val ibiSamples = mutableListOf<String>()
    private val gyroSamples = mutableListOf<String>()
    private var lastStepJson: String? = null

    private var sdkFlushLoopRunning = false
    private var hrvPublishLoopRunning = false
    private var mqttPublishLoopRunning = false

    private val sdkFlushRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) {
                sdkFlushLoopRunning = false
                return
            }
            ppgTracker?.flush()
            skinTempTracker?.flush()
            accSdkTracker?.flush()
            heartRateTracker?.flush()
            handler.postDelayed(this, mqttPublishIntervalMs)
        }
    }

    private val hrvPublishRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) {
                hrvPublishLoopRunning = false
                return
            }
            publishHrvFeatures()
            handler.postDelayed(this, hrvPublishIntervalMs)
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
        Log.i(TAG, "Ecran ${if (on) "ON" else "OFF"} (măsurare ${if (on) "live" else "batch + flush"})")
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
        if (now - lastStatusLogMs < 30_000L) return
        lastStatusLogMs = now
        synchronized(bufferLock) {
            Log.i(
                TAG,
                "Status: ecran=${if (displayOn) "ON" else "OFF"}, bg=$backgroundSensorsGranted, " +
                    "ibiWindow=${ibiWindow.size}, windowSec=${"%.0f".format(lastWindowSec)}, signalOk=$signalOk, rmssd=${"%.1f".format(lastRmssd)}, mqtt=$msgCount"
            )
        }
    }

    private fun startPublishLoops() {
        if (!sdkFlushLoopRunning) {
            sdkFlushLoopRunning = true
            handler.post(sdkFlushRunnable)
        }
        if (!hrvPublishLoopRunning) {
            hrvPublishLoopRunning = true
            handler.post(hrvPublishRunnable)
        }
        if (!mqttPublishLoopRunning) {
            mqttPublishLoopRunning = true
            handler.post(mqttPublishRunnable)
        }
    }

    private fun stopPublishLoops() {
        sdkFlushLoopRunning = false
        hrvPublishLoopRunning = false
        mqttPublishLoopRunning = false
        handler.removeCallbacks(sdkFlushRunnable)
        handler.removeCallbacks(hrvPublishRunnable)
        handler.removeCallbacks(mqttPublishRunnable)
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
            for (dp in dataPoints) {
                val hr = dp.getValue(ValueKey.HeartRateSet.HEART_RATE)
                val status = dp.getValue(ValueKey.HeartRateSet.HEART_RATE_STATUS)

                if (status == 1 && hr > 0) {
                    lastHr = hr
                    enqueueSample(
                        hrSamples,
                        """{"ts":${dp.timestamp},"hr":$hr}"""
                    )
                }

                if (status != 1) continue

                val ibiList = dp.getValue(ValueKey.HeartRateSet.IBI_LIST)
                val ibiStatusList = dp.getValue(ValueKey.HeartRateSet.IBI_STATUS_LIST)
                if (ibiList != null) {
                    synchronized(bufferLock) {
                        for (i in ibiList.indices) {
                            val ibi = ibiList[i]
                            val ibiStatus = ibiStatusList?.getOrNull(i)
                            val recent = ibiWindow.map { it.ibiMs }
                            if (!IbiSignalFilter.accept(ibi, ibiStatus, recent)) continue
                            val ts = dp.timestamp.takeIf { it > 0 } ?: System.currentTimeMillis()
                            ibiWindow.addLast(IbiWindowEntry(ibi, ts))
                            lastIbiTimestampMs = System.currentTimeMillis()
                            trimIbiWindow()
                            enqueueSample(
                                ibiSamples,
                                """{"ts":${dp.timestamp},"ibi":$ibi,"st":${ibiStatus ?: 0}}"""
                            )
                        }
                        IbiSignalFilter.sanitizeWindow(ibiWindow)
                    }
                }
            }
        }
        override fun onFlushCompleted() {}
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
        Log.i(TAG, "Service onCreate")

        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "biofizic:SensorWakeLock")
        wakeLock.acquire()

        startForeground(1, buildNotification())
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Log.i(TAG, "ACTION_STOP primit")
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_SCAN -> {
                Log.i(TAG, "ACTION_SCAN – inventar senzori")
                if (!::wakeLock.isInitialized) {
                    val pm = getSystemService(POWER_SERVICE) as PowerManager
                    wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "biofizic:SensorWakeLock")
                    wakeLock.acquire()
                }
                startForeground(2, buildNotification("Scanare senzori…"))
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
            else -> {
                // ACTION_START sau restart automat
                if (!isRunning) {
                    Log.i(TAG, "Pornire tracking")
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
            Sensor.TYPE_GYROSCOPE ->
                enqueueSample(
                    gyroSamples,
                    """{"ts":$ts,"gx":${event.values[0]},"gy":${event.values[1]},"gz":${event.values[2]}}"""
                )
            Sensor.TYPE_STEP_COUNTER ->
                lastStepJson = """{"ts":$ts,"steps":${event.values[0]}}"""
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // ══════════════════════════════════════════
    //  MQTT
    // ══════════════════════════════════════════
    private fun connectMqtt() {
        try {
            if (::mqttClient.isInitialized) {
                try { mqttClient.close() } catch (_: Exception) {}
            }
            mqttClient = MqttClient(BROKER_URL, CLIENT_ID, MemoryPersistence())
            val options = MqttConnectOptions().apply {
                isCleanSession = true
                connectionTimeout = 5
                keepAliveInterval = 30
                isAutomaticReconnect = true
            }
            mqttClient.setCallback(object : MqttCallback {
                override fun connectionLost(cause: Throwable?) {
                    isMqttConnected = false
                    Log.w(TAG, "MQTT pierdut: ${cause?.message}")
                }

                override fun messageArrived(topic: String?, message: MqttMessage?) {
                    if (topic != "biofizic/state" || message == null) return
                    try {
                        val json = message.payload.decodeToString()
                        parseStateMessage(json)
                    } catch (e: Exception) {
                        Log.w(TAG, "state parse: ${e.message}")
                    }
                }

                override fun deliveryComplete(token: IMqttDeliveryToken?) {}
            })
            mqttClient.connect(options)
            mqttClient.subscribe("biofizic/state", 0)
            isMqttConnected = true
            Log.i(TAG, "MQTT conectat la $BROKER_URL (+ subscribe biofizic/state)")
        } catch (e: Exception) {
            isMqttConnected = false
            Log.e(TAG, "MQTT eroare conectare: ${e.message}")
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
                ppgSamples.add("""{"ts":${dp.timestamp},"green":$g,"ir":$ir,"red":$r}""")
                if (ppgStreamEnabled) {
                    val pub = System.currentTimeMillis()
                    streamPayloads.add(
                        """{"ts":${dp.timestamp},"pub":$pub,"green":$g,"ir":$ir,"red":$r}"""
                    )
                }
            }
        }
        for (payload in streamPayloads) {
            publish("biofizic/ppg/stream", payload)
        }
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
                accSamples.add("""{"ts":${dp.timestamp},"ax":$x,"ay":$y,"az":$z}""")
                val xf = x.toDouble()
                val yf = y.toDouble()
                val zf = z.toDouble()
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

    /** Toate IBI din buffer (până la 90s) — evită fereastră 12s când bătăile sunt rare în ultimul minut. */
    private fun ibiMsForHrvWindow(): List<Int> = ibiWindow.map { it.ibiMs }

    private fun publishHrvFeatures() {
        val ibiSnapshot: List<Int>
        val accRms: Double
        synchronized(bufferLock) {
            trimIbiWindow()
            ibiSnapshot = ibiMsForHrvWindow()
            accRms = if (accMagnitudes.isEmpty()) 0.0
            else sqrt(accMagnitudes.map { it * it }.average())
        }
        val ts = System.currentTimeMillis()
        val secSinceIbi = if (lastIbiTimestampMs > 0) (ts - lastIbiTimestampMs) / 1000.0 else -1.0
        val features = HrvFeatureCalculator.compute(ibiSnapshot)
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
            // Mișcare intensă (ex. flotări): fără IBI valid — tot publicăm heartbeat MQTT
            ibiN = ibiSnapshot.size
            windowSec = 0.0
            rmssd = 0.0
            sdnn = 0.0
            meanIbi = 0.0
            meanHr = if (lastHr > 0) lastHr.toDouble() else 0.0
            pnn50 = 0.0
        }
        val localHrvReady = ibiN >= MIN_IBI_FOR_HRV && windowSec >= MIN_WINDOW_SEC_FOR_SIGNAL
        if (ts - lastStateMessageMs > 4_000L) {
            signalOk = localHrvReady
        }
        val hrvJson =
            """{"ts":$ts,"ibi_n":$ibiN,"window_sec":${jsonFloat(windowSec, 1)},"hrv_ready":$localHrvReady,"rmssd":${jsonFloat(rmssd, 2)},"sdnn":${jsonFloat(sdnn, 2)},"mean_ibi":${jsonFloat(meanIbi, 1)},"mean_hr":${jsonFloat(meanHr, 1)},"pnn50":${jsonFloat(pnn50, 1)}}"""
        publish("biofizic/hrv", hrvJson)
        publish(
            "biofizic/features",
            """{"ts":$ts,"displayOn":$displayOn,"bgSensors":$backgroundSensorsGranted,"hr":$lastHr,"ibi_n":$ibiN,"window_sec":${jsonFloat(windowSec, 1)},"sec_since_last_ibi":${jsonFloat(secSinceIbi, 1)},"ibi_buffer":${ibiWindow.size},"hrv_ready":$localHrvReady,"rmssd":${jsonFloat(rmssd, 2)},"sdnn":${jsonFloat(sdnn, 2)},"mean_ibi":${jsonFloat(meanIbi, 1)},"mean_hr":${jsonFloat(meanHr, 1)},"pnn50":${jsonFloat(pnn50, 1)},"acc_rms":${jsonFloat(accRms, 3)},"skin_temp":${jsonFloat(lastSkinTempC, 2)},"ambient_temp":${jsonFloat(lastAmbientTempC, 2)}}"""
        )
    }

    private fun flushAllMqttBuffers() {
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

    private fun parseStateMessage(json: String) {
        val a10 = extractJsonInt(json, "arousal_10") ?: return
        lastStateMessageMs = System.currentTimeMillis()
        arousal10 = a10.coerceIn(0, 10)
        arousalConfidence = extractJsonFloat(json, "confidence") ?: 0f
        emotionLabel = extractJsonString(json, "emotion") ?: "—"
        extractJsonBool(json, "motion_gated")?.let { motionGated = it }
        extractJsonBool(json, "profile_ready")?.let { profileReady = it }
        extractJsonBool(json, "signal_ok")?.let { signalOk = it }
        extractJsonFloat(json, "window_sec")?.let { lastWindowSec = it.toDouble() }
    }

    private fun extractJsonBool(json: String, key: String): Boolean? {
        val re = Regex("\"$key\"\\s*:\\s*(true|false)", RegexOption.IGNORE_CASE)
        return when (re.find(json)?.groupValues?.get(1)?.lowercase()) {
            "true" -> true
            "false" -> false
            else -> null
        }
    }

    private fun extractJsonInt(json: String, key: String): Int? {
        val re = Regex("\"$key\"\\s*:\\s*(-?\\d+)")
        return re.find(json)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun extractJsonFloat(json: String, key: String): Float? {
        val re = Regex("\"$key\"\\s*:\\s*([\\d.]+)")
        return re.find(json)?.groupValues?.get(1)?.toFloatOrNull()
    }

    private fun extractJsonString(json: String, key: String): String? {
        val re = Regex("\"$key\"\\s*:\\s*\"([^\"]+)\"")
        return re.find(json)?.groupValues?.get(1)
    }

    private fun publish(topic: String, payload: String) {
        try {
            if (::mqttClient.isInitialized && mqttClient.isConnected) {
                mqttClient.publish(topic, MqttMessage(payload.toByteArray()).apply {
                    qos = 0  // fire-and-forget pentru date high-frequency
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

                // Verifica MQTT
                val connected = ::mqttClient.isInitialized && mqttClient.isConnected
                isMqttConnected = connected
                if (!connected) {
                    Log.w(TAG, "MQTT deconectat, reconectare...")
                    connectMqtt()
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
        arousal10 = -1
        arousalConfidence = 0f
        emotionLabel = "—"
        motionGated = false
        profileReady = false
        signalOk = false
        lastWindowSec = 0.0
        lastStateMessageMs = 0L
        stopPublishLoops()
        publishHrvFeatures()
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
            lastStepJson = null
        }
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
        if (::wakeLock.isInitialized && wakeLock.isHeld) {
            wakeLock.release()
        }
        Log.i(TAG, "Service distrus")
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (isRunning) {
            val pending = PendingIntent.getService(
                this, 1,
                Intent(this, SensorService::class.java).apply { action = ACTION_START },
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            )
            (getSystemService(ALARM_SERVICE) as AlarmManager)
                .setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + 5_000L,
                    pending
                )
            Log.i(TAG, "onTaskRemoved – restart programat in 5s")
        }
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

        return Notification.Builder(this, channelId)
            .setContentTitle("Biofizic activ")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_media_play)
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