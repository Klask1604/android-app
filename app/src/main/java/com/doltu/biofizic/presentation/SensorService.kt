package com.doltu.biofizic.presentation

import android.app.*
import android.app.PendingIntent
import android.content.Intent
import android.hardware.Sensor
import android.os.SystemClock
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import com.samsung.android.service.health.tracking.ConnectionListener
import com.samsung.android.service.health.tracking.HealthTracker
import com.samsung.android.service.health.tracking.HealthTrackerException
import com.samsung.android.service.health.tracking.HealthTrackingService
import com.samsung.android.service.health.tracking.data.DataPoint
import com.samsung.android.service.health.tracking.data.HealthTrackerType
import com.samsung.android.service.health.tracking.data.ValueKey
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence

class SensorService : Service(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private lateinit var mqttClient: MqttClient
    private val BROKER_URL = "tcp://paxbespoke.automateflow.ro:1883"
    private val CLIENT_ID  = "GalaxyWatch7"

    private var healthTrackingService: HealthTrackingService? = null
    private var heartRateTracker: HealthTracker? = null
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var wakeLock: PowerManager.WakeLock

    companion object {
        @Volatile
        var isPaused = false
    }

    private val connectionListener = object : ConnectionListener {
        override fun onConnectionSuccess() {
            Log.i("SensorService", "Samsung Health SDK conectat")
            startHeartRateTracking()
        }
        override fun onConnectionEnded() {
            Log.w("SensorService", "Samsung Health SDK deconectat")
        }
        override fun onConnectionFailed(e: HealthTrackerException) {
            Log.e("SensorService", "Samsung Health SDK eroare: ${e.message}")
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (isPaused) return
        val ts = System.currentTimeMillis()
        when (event.sensor.type) {
            Sensor.TYPE_HEART_RATE -> {
                val hr = event.values[0]
                if (hr <= 0) return
                publish("biofizic/hr", """{"ts":$ts,"hr":$hr}""")
            }
            Sensor.TYPE_ACCELEROMETER ->
                publish("biofizic/acc", """{"ts":$ts,"ax":${event.values[0]},"ay":${event.values[1]},"az":${event.values[2]}}""")
            Sensor.TYPE_GYROSCOPE ->
                publish("biofizic/gyro", """{"ts":$ts,"gx":${event.values[0]},"gy":${event.values[1]},"gz":${event.values[2]}}""")
            Sensor.TYPE_STEP_COUNTER ->
                publish("biofizic/step", """{"ts":$ts,"steps":${event.values[0]}}""")
        }
    }

    private val heartRateListener = object : HealthTracker.TrackerEventListener {
        override fun onDataReceived(dataPoints: List<DataPoint>) {
            if (isPaused) return
            for (dp in dataPoints) {
                val hr     = dp.getValue(ValueKey.HeartRateSet.HEART_RATE)
                val status = dp.getValue(ValueKey.HeartRateSet.HEART_RATE_STATUS)
                val ts     = System.currentTimeMillis()

                if (status == 1 && hr > 0) {
                    publish("biofizic/hr_sdk", """{"ts":$ts,"hr":$hr}""")
                    Log.i("SensorService", "HR SDK = $hr bpm")
                }

                val ibiList = dp.getValue(ValueKey.HeartRateSet.IBI_LIST)
                if (ibiList != null) {
                    for (i in ibiList.indices) {
                        val ibi = ibiList[i]
                        if (ibi > 0) {
                            publish("biofizic/ibi", """{"ts":$ts,"ibi":$ibi}""")
                            Log.i("SensorService", "IBI = $ibi ms")
                        }
                    }
                }
            }
        }
        override fun onFlushCompleted() {}
        override fun onError(e: HealthTracker.TrackerError) {
            Log.e("SensorService", "HR tracker eroare: ${e.name}")
        }
    }

    override fun onCreate() {
        super.onCreate()

        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "biofizic:SensorWakeLock"
        )
        wakeLock.acquire()

        startForeground(1, buildNotification())
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        registerSensors()

        Thread {
            try {
                connectMqtt()
                Log.i("SensorService", "MQTT gata")
            } catch (e: Exception) {
                Log.e("SensorService", "MQTT init eroare: ${e.message}")
            }

            try {
                healthTrackingService = HealthTrackingService(connectionListener, this)
                healthTrackingService?.connectService()
                Log.i("SensorService", "Samsung SDK conectare initiata")
            } catch (e: Exception) {
                Log.e("SensorService", "Samsung SDK init eroare: ${e.message}")
            }

            startWatchdog()
        }.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    private fun startWatchdog() {
        while (true) {
            try {
                Thread.sleep(10_000)

                if (::mqttClient.isInitialized && !mqttClient.isConnected) {
                    Log.w("SensorService", "MQTT deconectat, reconectare...")
                    connectMqtt()
                }

                if (healthTrackingService == null) {
                    try {
                        healthTrackingService = HealthTrackingService(connectionListener, this)
                        healthTrackingService?.connectService()
                        Log.i("SensorService", "Samsung SDK reconectare initiata")
                    } catch (e: Exception) {
                        Log.e("SensorService", "Samsung SDK retry eroare: ${e.message}")
                    }
                }
            } catch (e: InterruptedException) {
                Log.w("SensorService", "Watchdog intrerupt")
                break
            } catch (e: Exception) {
                Log.e("SensorService", "Watchdog eroare: ${e.message}")
            }
        }
    }

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
            }
            mqttClient.connect(options)
            Log.i("SensorService", "MQTT conectat la $BROKER_URL")
        } catch (e: Exception) {
            Log.e("SensorService", "MQTT eroare conectare: ${e.message}")
        }
    }

    private fun publish(topic: String, payload: String) {
        try {
            if (::mqttClient.isInitialized && mqttClient.isConnected) {
                mqttClient.publish(topic, MqttMessage(payload.toByteArray()))
            }
        } catch (e: Exception) {
            Log.w("SensorService", "MQTT publish eroare: ${e.message}")
        }
    }

    private fun startHeartRateTracking() {
        try {
            val capabilities = healthTrackingService
                ?.getTrackingCapability()
                ?.getSupportHealthTrackerTypes()
            if (capabilities?.contains(HealthTrackerType.HEART_RATE_CONTINUOUS) == true) {
                heartRateTracker = healthTrackingService
                    ?.getHealthTracker(HealthTrackerType.HEART_RATE_CONTINUOUS)
                handler.post { heartRateTracker?.setEventListener(heartRateListener) }
                Log.i("SensorService", "HR Continuous tracking pornit")
            } else {
                Log.w("SensorService", "HEART_RATE_CONTINUOUS nu e suportat")
            }
        } catch (e: Exception) {
            Log.e("SensorService", "startHeartRateTracking eroare: ${e.message}")
        }
    }

    private fun registerSensors() {
        sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE)?.also {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.also {
            sensorManager.registerListener(this, it, 20_000)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.also {
            sensorManager.registerListener(this, it, 20_000)
            Log.i("SensorService", "Giroscop activ")
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)?.also {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
            Log.i("SensorService", "Step counter activ")
        }
    }

    private fun buildNotification(): Notification {
        val channelId = "biofizic_service"
        val channel = NotificationChannel(
            channelId, "Biofizic Service",
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        return Notification.Builder(this, channelId)
            .setContentTitle("Biofizic activ")
            .setContentText("MQTT — HR + ACC + IBI")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::wakeLock.isInitialized && wakeLock.isHeld) {
            wakeLock.release()
        }
        sensorManager.unregisterListener(this)
        heartRateTracker?.unsetEventListener()
        healthTrackingService?.disconnectService()
        if (::mqttClient.isInitialized && mqttClient.isConnected) mqttClient.disconnect()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val pending = PendingIntent.getService(
            this, 1,
            Intent(this, SensorService::class.java),
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )
        (getSystemService(ALARM_SERVICE) as AlarmManager)
            .setExactAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + 5_000L,
                pending
            )
        Log.i("SensorService", "onTaskRemoved - restart programat in 5s")
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}