package com.doltu.biofizic.presentation

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    private var foregroundSensorsGranted = false

    private val backgroundSensorPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        SensorService.backgroundSensorsGranted = granted
        if (granted) {
            Log.i("MainActivity", "BODY_SENSORS_BACKGROUND acordat – măsurare cu ecran oprit OK")
            if (foregroundSensorsGranted && !SensorService.isRunning) {
                startTracking()
            }
        } else {
            Log.w("MainActivity", "BODY_SENSORS_BACKGROUND refuzat – date limitate când ecranul e oprit")
            if (!shouldShowRequestPermissionRationale(Manifest.permission.BODY_SENSORS_BACKGROUND)) {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:$packageName")
                })
            }
            if (foregroundSensorsGranted && !SensorService.isRunning) {
                startTracking()
            }
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        foregroundSensorsGranted = permissions[Manifest.permission.BODY_SENSORS] == true ||
                permissions["android.permission.health.READ_HEART_RATE"] == true
        if (!foregroundSensorsGranted) return@registerForActivityResult

        SensorService.backgroundSensorsGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.BODY_SENSORS_BACKGROUND
        ) == PackageManager.PERMISSION_GRANTED

        if (SensorService.backgroundSensorsGranted) {
            startTracking()
        } else {
            requestBackgroundSensorPermission()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        setContent { BiofizicWatchApp() }

        // Battery optimization
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            })
        }

        // Permissions (targetSdk 36 – Samsung Health Sensor SDK)
        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.BODY_SENSORS,
                Manifest.permission.ACTIVITY_RECOGNITION,
                "android.permission.health.READ_HEART_RATE",
                "android.permission.health.READ_HEART_RATE_VARIABILITY",
                "android.permission.health.READ_SKIN_TEMPERATURE",
                "android.permission.health.READ_OXYGEN_SATURATION",
                "android.permission.health.READ_BODY_TEMPERATURE",
                "android.permission.health.READ_ELECTROCARDIOGRAM",
                "com.samsung.android.hardware.sensormanager.permission.READ_ADDITIONAL_HEALTH_DATA",
            )
        )
    }

    private fun startTracking() {
        startForegroundService(
            Intent(this, SensorService::class.java).apply {
                action = SensorService.ACTION_START
            }
        )
    }

    private fun requestBackgroundSensorPermission() {
        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.BODY_SENSORS_BACKGROUND
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            backgroundSensorPermLauncher.launch(Manifest.permission.BODY_SENSORS_BACKGROUND)
        }
    }
}

// ══════════════════════════════════════════
//  Culori
// ══════════════════════════════════════════
private val Cyan = Color(0xFF00E5FF)
private val CyanDim = Color(0xFF006978)
private val GreenActive = Color(0xFF00E676)
private val RedStop = Color(0xFFFF1744)
private val Surface = Color(0xFF1A1A2E)
private val SurfaceLight = Color(0xFF16213E)
private val TextDim = Color(0xFF8892B0)

// ══════════════════════════════════════════
//  Main Composable
// ══════════════════════════════════════════
@Composable
fun BiofizicWatchApp() {
    val context = LocalContext.current

    // Poll service state la fiecare 500ms
    var isRunning by remember { mutableStateOf(SensorService.isRunning) }
    var mqttOk by remember { mutableStateOf(false) }
    var hr by remember { mutableIntStateOf(0) }
    var sensorCount by remember { mutableIntStateOf(0) }
    var msgCount by remember { mutableLongStateOf(0L) }
    var ppgOn by remember { mutableStateOf(false) }
    var accOn by remember { mutableStateOf(false) }
    var gyroOn by remember { mutableStateOf(false) }
    var tempOn by remember { mutableStateOf(false) }
    var ibiOn by remember { mutableStateOf(false) }
    var sensorReport by remember { mutableStateOf(SensorService.sensorReport) }
    var showSensors by remember { mutableStateOf(false) }
    var displayOn by remember { mutableStateOf(SensorService.displayOn) }
    var bgSensors by remember { mutableStateOf(SensorService.backgroundSensorsGranted) }
    var rmssd by remember { mutableStateOf(SensorService.lastRmssd) }

    LaunchedEffect(Unit) {
        while (true) {
            isRunning = SensorService.isRunning
            mqttOk = SensorService.isMqttConnected
            hr = SensorService.lastHr
            sensorCount = SensorService.activeSensors
            msgCount = SensorService.msgCount
            ppgOn = SensorService.ppgActive
            accOn = SensorService.accActive
            gyroOn = SensorService.gyroActive
            tempOn = SensorService.skinTempActive
            ibiOn = SensorService.ibiActive
            sensorReport = SensorService.sensorReport
            displayOn = SensorService.displayOn
            bgSensors = SensorService.backgroundSensorsGranted
            rmssd = SensorService.lastRmssd
            delay(500)
        }
    }

    val backgroundBrush = Brush.radialGradient(
        colors = listOf(SurfaceLight, Color.Black),
        radius = 400f
    )

    MaterialTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(backgroundBrush)
        ) {
            if (showSensors) {
                SensorInventoryScreen(
                    report = sensorReport,
                    onBack = { showSensors = false },
                    onScan = {
                        context.startForegroundService(
                            Intent(context, SensorService::class.java).apply {
                                action = SensorService.ACTION_SCAN
                            }
                        )
                    }
                )
            } else {
                MainDashboard(
                    context = context,
                    isRunning = isRunning,
                    mqttOk = mqttOk,
                    hr = hr,
                    msgCount = msgCount,
                    ppgOn = ppgOn,
                    accOn = accOn,
                    gyroOn = gyroOn,
                    tempOn = tempOn,
                    ibiOn = ibiOn,
                    displayOn = displayOn,
                    bgSensors = bgSensors,
                    rmssd = rmssd,
                    onOpenSensors = {
                        showSensors = true
                        if (!SensorService.sdkScanDone) {
                            context.startForegroundService(
                                Intent(context, SensorService::class.java).apply {
                                    action = SensorService.ACTION_SCAN
                                }
                            )
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun MainDashboard(
    context: android.content.Context,
    isRunning: Boolean,
    mqttOk: Boolean,
    hr: Int,
    msgCount: Long,
    ppgOn: Boolean,
    accOn: Boolean,
    gyroOn: Boolean,
    tempOn: Boolean,
    ibiOn: Boolean,
    displayOn: Boolean,
    bgSensors: Boolean,
    rmssd: Double,
    onOpenSensors: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        content = {
            Spacer(modifier = Modifier.weight(0.15f))

            MqttStatusIndicator(mqttOk, isRunning)
            TrackingContextLine(isRunning, displayOn, bgSensors, rmssd)
            HrDisplay(hr, isRunning)

            if (isRunning) {
                SensorBadges(ppgOn, accOn, gyroOn, tempOn, ibiOn)
            }

            if (isRunning && msgCount > 0) {
                Text(
                    text = "${formatCount(msgCount)} mesaje",
                    fontSize = 10.sp,
                    color = TextDim
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onOpenSensors,
                    colors = ButtonDefaults.buttonColors(backgroundColor = SurfaceLight),
                    modifier = Modifier.height(28.dp)
                ) {
                    Text(text = "Senzori", fontSize = 10.sp, color = Cyan)
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            val btnColor by animateColorAsState(
                if (isRunning) RedStop else GreenActive,
                label = "btnColor"
            )

            Button(
                onClick = {
                    if (isRunning) {
                        context.startForegroundService(
                            Intent(context, SensorService::class.java).apply {
                                action = SensorService.ACTION_STOP
                            }
                        )
                    } else {
                        context.startForegroundService(
                            Intent(context, SensorService::class.java).apply {
                                action = SensorService.ACTION_START
                            }
                        )
                    }
                },
                colors = ButtonDefaults.buttonColors(backgroundColor = btnColor),
                modifier = Modifier
                    .height(40.dp)
                    .fillMaxWidth(0.65f)
            ) {
                Text(
                    text = if (isRunning) "■  Stop" else "▶  Start",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }

            Spacer(modifier = Modifier.weight(0.1f))
        }
    )
}

@Composable
private fun SensorInventoryScreen(
    report: String,
    onBack: () -> Unit,
    onScan: () -> Unit,
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = onBack,
                colors = ButtonDefaults.buttonColors(backgroundColor = SurfaceLight),
                modifier = Modifier.height(32.dp)
            ) {
                Text(text = "← Înapoi", fontSize = 10.sp, color = Cyan)
            }
            Button(
                onClick = onScan,
                colors = ButtonDefaults.buttonColors(backgroundColor = SurfaceLight),
                modifier = Modifier.height(32.dp)
            ) {
                Text(text = "Scan", fontSize = 10.sp, color = TextDim)
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = "Inventar senzori",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = report.ifBlank { "Apasă Scan sau Start pentru a încărca lista." },
            fontSize = 7.sp,
            color = TextDim,
            lineHeight = 9.sp,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(scrollState)
        )
    }
}

// ══════════════════════════════════════════
//  Components
// ══════════════════════════════════════════

@Composable
private fun TrackingContextLine(running: Boolean, displayOn: Boolean, bgSensors: Boolean, rmssd: Double) {
    if (!running) return
    Text(
        text = buildString {
            append(if (displayOn) "Ecran ON" else "Ecran OFF")
            append(" · ")
            append(if (bgSensors) "BG OK" else "fără BG")
            if (rmssd > 0) append(" · RMSSD ${"%.0f".format(rmssd)}")
        },
        fontSize = 8.sp,
        color = TextDim,
        textAlign = TextAlign.Center
    )
}

@Composable
private fun MqttStatusIndicator(connected: Boolean, running: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        // Pulsing dot
        val dotColor by animateColorAsState(
            when {
                !running -> TextDim
                connected -> GreenActive
                else -> RedStop
            },
            label = "dotColor"
        )

        val alpha = if (running && connected) {
            val infiniteTransition = rememberInfiniteTransition(label = "pulse")
            infiniteTransition.animateFloat(
                initialValue = 0.4f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1000),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "pulseAlpha"
            ).value
        } else 1f

        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(dotColor.copy(alpha = alpha))
        )

        Spacer(modifier = Modifier.width(6.dp))

        Text(
            text = when {
                !running -> "Oprit"
                connected -> "MQTT conectat"
                else -> "MQTT deconectat"
            },
            fontSize = 11.sp,
            color = if (running) Color.White else TextDim
        )
    }
}

@Composable
private fun HrDisplay(hr: Int, running: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = if (running && hr > 0) "$hr" else "—",
            fontSize = 40.sp,
            fontWeight = FontWeight.Bold,
            color = if (running) Cyan else TextDim,
            textAlign = TextAlign.Center
        )
        Text(
            text = "BPM",
            fontSize = 10.sp,
            color = TextDim,
            letterSpacing = 2.sp
        )
    }
}

@Composable
private fun SensorBadges(ppg: Boolean, acc: Boolean, gyro: Boolean, temp: Boolean, ibi: Boolean) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Spacer(modifier = Modifier.weight(1f))
        SensorBadge("PPG", ppg)
        SensorBadge("ACC", acc)
        SensorBadge("GYR", gyro)
        SensorBadge("TMP", temp)
        SensorBadge("IBI", ibi)
        Spacer(modifier = Modifier.weight(1f))
    }
}

@Composable
private fun SensorBadge(label: String, active: Boolean) {
    val bgColor by animateColorAsState(
        if (active) CyanDim.copy(alpha = 0.4f) else Color.White.copy(alpha = 0.05f),
        label = "badge_$label"
    )
    val textColor by animateColorAsState(
        if (active) Cyan else TextDim.copy(alpha = 0.5f),
        label = "badgeText_$label"
    )

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(bgColor)
            .padding(horizontal = 4.dp, vertical = 2.dp)
    ) {
        Text(
            text = label,
            fontSize = 8.sp,
            fontWeight = FontWeight.Medium,
            color = textColor,
            letterSpacing = 0.5.sp
        )
    }
}

private fun formatCount(n: Long): String = when {
    n >= 1_000_000 -> "${n / 1_000_000}.${(n % 1_000_000) / 100_000}M"
    n >= 1_000 -> "${n / 1_000}.${(n % 1_000) / 100}k"
    else -> "$n"
}