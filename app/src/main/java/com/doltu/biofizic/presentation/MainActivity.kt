package com.doltu.biofizic.presentation

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.PowerManager
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import android.provider.Settings
import android.net.Uri

class MainActivity : ComponentActivity() {

    private val backgroundSensorPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            Log.i("MainActivity", "BODY_SENSORS_BACKGROUND acordat")
        } else {
            Log.w("MainActivity", "BODY_SENSORS_BACKGROUND refuzat")
            if (!shouldShowRequestPermissionRationale(Manifest.permission.BODY_SENSORS_BACKGROUND)) {
                Log.w("MainActivity", "Permisiunea e blocata - redirectionare la Setari aplicatie")
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:$packageName")
                })
            }
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions[Manifest.permission.BODY_SENSORS] == true ||
                permissions["android.permission.health.READ_HEART_RATE"] == true
        if (granted) {
            startForegroundService(Intent(this, SensorService::class.java))
            requestBackgroundSensorPermission()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        setContent { WearApp() }
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            })
        }
        permissionLauncher.launch(arrayOf(
            Manifest.permission.BODY_SENSORS,
            "android.permission.health.READ_HEART_RATE"
        ))
    }

    private fun requestBackgroundSensorPermission() {
        val alreadyGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.BODY_SENSORS_BACKGROUND
        ) == PackageManager.PERMISSION_GRANTED

        if (alreadyGranted) {
            Log.i("MainActivity", "BODY_SENSORS_BACKGROUND deja acordat")
            return
        }

        backgroundSensorPermLauncher.launch(Manifest.permission.BODY_SENSORS_BACKGROUND)
    }
}

@Composable
fun WearApp() {
    var isTracking by remember { mutableStateOf(true) }

    MaterialTheme {
        Box(
            modifier = Modifier.fillMaxSize().background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = if (isTracking) "● Activ" else "○ Oprit",
                    fontSize = 18.sp,
                    color = if (isTracking) Color(0xFF00E5FF) else Color.Gray
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = {
                        SensorService.isPaused = isTracking
                        isTracking = !isTracking
                    }
                ) {
                    Text(
                        text = if (isTracking) "Stop" else "Start",
                        color = Color.White,
                        fontSize = 16.sp
                    )
                }
            }
        }
    }
}