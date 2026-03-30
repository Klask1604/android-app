package com.doltu.biofizic.presentation

import android.app.*
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import com.samsung.android.service.health.tracking.HealthTracker
import com.samsung.android.service.health.tracking.HealthTrackerException
import com.samsung.android.service.health.tracking.HealthTrackingService
import com.samsung.android.service.health.tracking.data.DataPoint
import com.samsung.android.service.health.tracking.data.HealthTrackerType
import com.samsung.android.service.health.tracking.data.ValueKey
import com.samsung.android.service.health.tracking.ConnectionListener
class SensorService : Service(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private val httpClient = OkHttpClient()
    private val SERVER_URL = "http://192.168.1.128:5005/data"

    private var healthTrackingService: HealthTrackingService? = null
    private var heartRateTracker: HealthTracker? = null
    private val handler = Handler(Looper.getMainLooper())

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

    private val heartRateListener = object : HealthTracker.TrackerEventListener {
        override fun onDataReceived(dataPoints: List<DataPoint>) {
            Log.i("SensorService", "onDataReceived: ${dataPoints.size} puncte")
            for (dp in dataPoints) {
                val hr     = dp.getValue(ValueKey.HeartRateSet.HEART_RATE)
                val status = dp.getValue(ValueKey.HeartRateSet.HEART_RATE_STATUS)
                val ts     = System.currentTimeMillis()
                Log.i("SensorService", "HR SDK raw: hr=$hr status=$status")
                if (status == 1 && hr > 0) {
                    val json = """{"type":"hr_sdk","ts":$ts,"hr":$hr}"""
                    sendToServer(json)
                    Log.i("SensorService", "HR SDK = $hr bpm (status=$status)")
                }

                // IBI — lista de inter-beat intervals
                val ibiList = dp.getValue(ValueKey.HeartRateSet.IBI_LIST)
                val ibiStatusList = dp.getValue(ValueKey.HeartRateSet.IBI_STATUS_LIST)
                if (ibiList != null) {
                    for (i in ibiList.indices) {
                        val ibi = ibiList[i]
                        val ibiStatus = ibiStatusList?.getOrNull(i) ?: 0
                        if (ibi > 0) {
                            val json = """{"type":"ibi","ts":$ts,"ibi":$ibi}"""
                            sendToServer(json)
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
        startForeground(1, buildNotification())
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        registerSensors()

        // Pornește Samsung Health SDK
        healthTrackingService = HealthTrackingService(connectionListener, this)
        healthTrackingService?.connectService()
    }

    private fun startHeartRateTracking() {
        try {
            val capabilities = healthTrackingService
                ?.getTrackingCapability()
                ?.getSupportHealthTrackerTypes()

            if (capabilities?.contains(HealthTrackerType.HEART_RATE_CONTINUOUS) == true) {
                heartRateTracker = healthTrackingService
                    ?.getHealthTracker(HealthTrackerType.HEART_RATE_CONTINUOUS)
                handler.post {
                    heartRateTracker?.setEventListener(heartRateListener)
                }
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

    override fun onSensorChanged(event: SensorEvent) {
        val ts = System.currentTimeMillis()
        val json = when (event.sensor.type) {
            Sensor.TYPE_HEART_RATE -> {
                val hr = event.values[0]
                if (hr <= 0) return
                """{"type":"hr","ts":$ts,"hr":$hr}"""
            }
            Sensor.TYPE_ACCELEROMETER ->
                """{"type":"acc","ts":$ts,"ax":${event.values[0]},"ay":${event.values[1]},"az":${event.values[2]}}"""
            Sensor.TYPE_GYROSCOPE ->
                """{"type":"gyro","ts":$ts,"gx":${event.values[0]},"gy":${event.values[1]},"gz":${event.values[2]}}"""
            Sensor.TYPE_STEP_COUNTER ->
                """{"type":"step","ts":$ts,"steps":${event.values[0]}}"""
            else -> return
        }
        sendToServer(json)
    }

    private fun sendToServer(jsonString: String) {
        val request = Request.Builder()
            .url(SERVER_URL)
            .post(jsonString.toRequestBody("application/json".toMediaType()))
            .build()
        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.w("SensorService", "Eroare: ${e.message}")
            }
            override fun onResponse(call: Call, response: Response) {
                response.close()
            }
        })
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
            .setContentText("Colectare date HR + ACC + IBI")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        sensorManager.unregisterListener(this)
        heartRateTracker?.unsetEventListener()
        healthTrackingService?.disconnectService()
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}