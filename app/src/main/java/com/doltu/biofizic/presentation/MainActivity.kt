package com.doltu.biofizic.presentation

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import android.content.Intent

class MainActivity : ComponentActivity(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private val httpClient = OkHttpClient()
    private val SERVER_URL = "http://192.168.1.128:5005/data" // schimbă cu ngrok când e pe 4G

    // State-uri Compose — când se schimbă, UI se re-rendează automat
    private var hrState = mutableStateOf("--")
    private var emotieState = mutableStateOf("Asteptare...")
    private var statusState = mutableStateOf("Offline")

    // Launcher modern pentru permisiuni (înlocuiește requestPermissions deprecated)
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val bodySensors = permissions[Manifest.permission.BODY_SENSORS] == true
        val heartRate = permissions["android.permission.health.READ_HEART_RATE"] == true
        if (bodySensors || heartRate) registerSensors()
        else statusState.value = "Permisiune refuzata"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        // Inițializează ÎNTOTDEAUNA sensorManager aici, indiferent de permisiune
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager

        setContent {
            WearApp(
                hr = hrState.value,
                emotie = emotieState.value,
                status = statusState.value
            )
        }

        checkAndRequestPermission()
    }

    private fun checkAndRequestPermission() {
        permissionLauncher.launch(arrayOf(
            Manifest.permission.BODY_SENSORS,
            Manifest.permission.BODY_SENSORS_BACKGROUND,
            "android.permission.health.READ_HEART_RATE"
        ))
    }

    private fun registerSensors() {
        statusState.value = "Activ"
        val intent = Intent(this, SensorService::class.java)
        startForegroundService(intent)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val ts = System.currentTimeMillis()

        val json = when (event.sensor.type) {
            Sensor.TYPE_HEART_RATE -> {
                val hr = event.values[0]
                hrState.value = "${hr.toInt()} bpm"
                """{"type":"hr","ts":$ts,"hr":$hr}"""
            }
            Sensor.TYPE_ACCELEROMETER -> {
                """{"type":"acc","ts":$ts,"ax":${event.values[0]},"ay":${event.values[1]},"az":${event.values[2]}}"""
            }
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
                Log.w("Biofizic", "Eroare: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val body = it.body?.string() ?: return
                    if (body.contains("emotie")) {
                        val emotie = body
                            .substringAfter("\"emotie\":\"")
                            .substringBefore("\"")
                        runOnUiThread { emotieState.value = emotie }
                    }
                }
            }
        })
    }

    override fun onPause() {
        super.onPause()
        if (::sensorManager.isInitialized) {
            sensorManager.unregisterListener(this)
            statusState.value = "Pauza"
        }
    }

    override fun onResume() {
        super.onResume()
        if (::sensorManager.isInitialized &&
            checkSelfPermission(Manifest.permission.BODY_SENSORS)
            == PackageManager.PERMISSION_GRANTED) {
            registerSensors()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}

@Composable
fun WearApp(hr: String, emotie: String, status: String) {
    MaterialTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = hr,
                    fontSize = 28.sp,
                    color = Color.White
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = emotie,
                    fontSize = 16.sp,
                    color = Color(0xFF00E5FF),
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = status,
                    fontSize = 12.sp,
                    color = Color.Gray
                )
            }
        }
    }
}