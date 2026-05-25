package com.doltu.biofizic.presentation

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.content.Context
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text

class MainActivity : ComponentActivity() {

    private var foregroundSensorsGranted = false

    private val prefs by lazy { getSharedPreferences("biofizic", Context.MODE_PRIVATE) }

    private val backgroundSensorPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        SensorService.backgroundSensorsGranted = granted
        if (foregroundSensorsGranted && !SensorService.isRunning) {
            startTracking()
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

    override fun onResume() {
        super.onResume()
        applyKeepScreenOn()
    }

    override fun onPause() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onPause()
    }

    internal fun applyKeepScreenOn() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun maybeRequestBatteryExemption() {
        if (prefs.getBoolean("asked_battery_opt", false)) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            prefs.edit().putBoolean("asked_battery_opt", true).apply()
            return
        }
        prefs.edit().putBoolean("asked_battery_opt", true).apply()
        startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:$packageName")
        })
    }

    private fun startTracking() {
        maybeRequestBatteryExemption()
        startForegroundService(
            Intent(this, SensorService::class.java).apply {
                action = SensorService.ACTION_START
            }
        )
        applyKeepScreenOn()
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

private val BgTop = Color(0xFF101218)
private val BgBottom = Color(0xFF050508)
private val RingTrack = Color(0xFF2A3040)
private val TextPrimary = Color(0xFFE8ECF5)
private val TextMuted = Color(0xFF6E7A90)
private val AccentCalm = Color(0xFF4DD0E1)
private val AccentActive = Color(0xFF69F0AE)
private val AccentHigh = Color(0xFFFF6E6E)
private val AccentMotion = Color(0xFFFFB74D)
private val AccentIdle = Color(0xFF5C6370)
private val BtnStart = Color(0xFF00C853)
private val BtnStop = Color(0xFFD50000)

/** Zonă sigură pe ecran rotund (~65% din diametru). */
private const val SAFE_WIDTH_FRACTION = 0.62f
private const val RING_FRACTION = 0.30f
private val RING_MIN = 60.dp
private val RING_MAX = 74.dp

@Composable
fun BiofizicWatchApp() {
    val context = LocalContext.current
    val uiState by SensorService.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(uiState.isRunning) {
        if (uiState.isRunning) {
            (context as? MainActivity)?.applyKeepScreenOn()
        }
    }

    MaterialTheme {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(BgTop, BgBottom))),
        ) {
            val minSide = minOf(maxWidth, maxHeight)
            val contentW = minSide * SAFE_WIDTH_FRACTION
            val ringSize = (minSide * RING_FRACTION).coerceIn(RING_MIN, RING_MAX)

            WatchFaceContent(
                isRunning = uiState.isRunning,
                mqttOk = uiState.isMqttConnected,
                hr = uiState.lastHr,
                arousalFused = uiState.arousalFused,
                arousal10 = uiState.arousal10,
                arousalLabel = uiState.arousalLabel,
                confidence = uiState.arousalConfidence,
                motionGated = uiState.motionGated,
                profileReady = uiState.profileReady,
                signalOk = uiState.signalOk,
                windowSec = uiState.lastWindowSec.toFloat(),
                calibrating = uiState.calibrationPhase == "collecting",
                calMessage = uiState.calibrationMessage,
                contentWidth = contentW,
                ringSize = ringSize,
                onLongPressRecalibrate = {
                    if (uiState.isRunning) {
                        context.startForegroundService(
                            Intent(context, SensorService::class.java).apply {
                                action = SensorService.ACTION_RECALIBRATE
                            },
                        )
                    }
                },
                modifier = Modifier
                    .width(contentW)
                    .align(Alignment.Center)
                    .offset(y = 8.dp),
            )

            val btnColor by animateColorAsState(
                if (uiState.isRunning) BtnStop else BtnStart,
                animationSpec = tween(200),
                label = "btn",
            )
            Button(
                onClick = {
                    val action = if (uiState.isRunning) SensorService.ACTION_STOP
                    else SensorService.ACTION_START
                    context.startForegroundService(
                        Intent(context, SensorService::class.java).apply { this.action = action },
                    )
                    if (action == SensorService.ACTION_STOP) {
                        (context as? MainActivity)?.window?.clearFlags(
                            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                        )
                    } else {
                        (context as? MainActivity)?.applyKeepScreenOn()
                    }
                },
                colors = ButtonDefaults.buttonColors(backgroundColor = btnColor),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 16.dp)
                    .size(width = (contentW * 0.72f).coerceAtLeast(52.dp), height = 28.dp),
            ) {
                Text(
                    text = if (uiState.isRunning) "Stop" else "Start",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WatchFaceContent(
    isRunning: Boolean,
    mqttOk: Boolean,
    hr: Int,
    arousalFused: Float,
    arousal10: Int,
    arousalLabel: String,
    confidence: Float,
    motionGated: Boolean,
    profileReady: Boolean,
    signalOk: Boolean,
    windowSec: Float,
    calibrating: Boolean,
    calMessage: String,
    contentWidth: Dp,
    ringSize: Dp,
    onLongPressRecalibrate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hasVerdict = isRunning && arousalFused >= 0f
    val accent = arousalAccent(hasVerdict, arousal10, motionGated, isRunning, mqttOk)

    Column(
        modifier = modifier.width(contentWidth),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CompactStatusLine(
            isRunning = isRunning,
            mqttOk = mqttOk,
            profileReady = profileReady && !calibrating,
            signalOk = signalOk,
            calibrating = calibrating,
        )

        Spacer(modifier = Modifier.height(4.dp))

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(ringSize)
                .combinedClickable(
                    onClick = {},
                    onLongClick = onLongPressRecalibrate,
                ),
        ) {
            ArousalGauge(
                progress = when {
                    !isRunning -> 0f
                    hasVerdict -> arousalFused / 10f
                    else -> 0f
                },
                accent = accent,
                modifier = Modifier.fillMaxSize(),
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = when {
                        hasVerdict -> "%.1f".format(arousalFused)
                        isRunning && mqttOk -> "…"
                        isRunning -> "—"
                        else -> "○"
                    },
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (hasVerdict || isRunning) TextPrimary else TextMuted,
                    lineHeight = 22.sp,
                )
                Text(text = "/10", fontSize = 7.sp, color = TextMuted)
            }
        }

        Spacer(modifier = Modifier.height(3.dp))

        Text(
            text = when {
                calibrating && calMessage.isNotBlank() -> calMessage
                hasVerdict -> arousalLabel
                isRunning && !mqttOk -> "Fără server"
                isRunning -> "Se încarcă"
                else -> "Gata"
            },
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = TextPrimary,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        Text(
            text = metaLine(
                isRunning, hasVerdict, hr, confidence, motionGated,
                profileReady, signalOk, windowSec, calibrating,
            ),
            fontSize = 7.sp,
            color = TextMuted,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 1.dp),
        )
    }
}

/** Un singur rând îngust — nu iese în marginile rotunde. */
@Composable
private fun CompactStatusLine(
    isRunning: Boolean,
    mqttOk: Boolean,
    profileReady: Boolean,
    signalOk: Boolean,
    calibrating: Boolean = false,
) {
    val liveColor = when {
        !isRunning -> AccentIdle
        mqttOk -> AccentActive
        else -> AccentHigh
    }
    val profColor = when {
        !isRunning -> AccentIdle
        !mqttOk -> AccentIdle
        profileReady -> AccentActive
        else -> AccentMotion
    }
    val liveLabel = when {
        !isRunning -> "Off"
        mqttOk -> "Live"
        else -> "MQTT"
    }
    val profLabel = when {
        !isRunning -> "Profil"
        !mqttOk -> "Profil-"
        calibrating -> "Calibr…"
        profileReady -> "Profil OK"
        else -> "Profil…"
    }
    val sigColor = when {
        !isRunning -> AccentIdle
        !mqttOk -> AccentIdle
        signalOk -> AccentActive
        else -> AccentMotion
    }
    val sigLabel = when {
        !isRunning -> "HRV"
        !mqttOk -> "HRV-"
        signalOk -> "HRV OK"
        else -> "HRV…"
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        StatusDot(label = liveLabel, color = liveColor)
        Text(text = " · ", fontSize = 7.sp, color = TextMuted)
        StatusDot(label = profLabel, color = profColor)
        Text(text = " · ", fontSize = 7.sp, color = TextMuted)
        StatusDot(label = sigLabel, color = sigColor)
    }
}

@Composable
private fun StatusDot(label: String, color: Color) {
    val c by animateColorAsState(color, animationSpec = tween(250), label = "c")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(4.dp)
                .clip(CircleShape)
                .background(c),
        )
        Spacer(modifier = Modifier.width(3.dp))
        Text(
            text = label,
            fontSize = 7.sp,
            color = TextMuted,
            maxLines = 1,
        )
    }
}

@Composable
private fun ArousalGauge(
    progress: Float,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val animatedProgress by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(400),
        label = "gauge",
    )
    val accentAnim by animateColorAsState(accent, animationSpec = tween(300), label = "accent")

    Canvas(modifier = modifier) {
        val stroke = 5.dp.toPx()
        val pad = stroke * 1.2f
        val arcSize = Size(size.width - pad * 2, size.height - pad * 2)
        val topLeft = Offset(pad, pad)
        // Arc cu gol jos (buton) — nu urcă spre marginea de sus
        val start = 155f
        val sweepTotal = 230f

        drawArc(
            color = RingTrack,
            startAngle = start,
            sweepAngle = sweepTotal,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
        if (animatedProgress > 0.01f) {
            drawArc(
                color = accentAnim,
                startAngle = start,
                sweepAngle = sweepTotal * animatedProgress,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
    }
}

private fun arousalAccent(
    hasArousal: Boolean,
    arousal10: Int,
    motionGated: Boolean,
    isRunning: Boolean,
    mqttOk: Boolean,
): Color = when {
    !isRunning -> AccentIdle
    !mqttOk -> AccentHigh.copy(alpha = 0.7f)
    motionGated -> AccentMotion
    !hasArousal -> TextMuted
    arousal10 <= 3 -> AccentCalm
    arousal10 <= 6 -> AccentActive
    else -> AccentHigh
}

private fun metaLine(
    isRunning: Boolean,
    hasVerdict: Boolean,
    hr: Int,
    confidence: Float,
    motionGated: Boolean,
    profileReady: Boolean,
    signalOk: Boolean,
    windowSec: Float,
    calibrating: Boolean = false,
): String = when {
    calibrating -> "5 min repaus liniștit"
    !isRunning -> "Start · ține apăsat gauge = recalibrare"
    !hasVerdict -> "Aștept verdict server"
    !signalOk && windowSec > 0f -> "Fereastră ${windowSec.toInt()}s · min 10s"
    !signalOk -> "Aștept HRV"
    motionGated -> "Mișcare · ${hr}bpm"
    hasVerdict && hr > 0 -> "Conf ${(confidence * 100).toInt()}% · ${hr}bpm"
    hr > 0 -> "${hr}bpm"
    else -> "Aștept"
}

private fun Dp.coerceAtLeast(min: Dp): Dp = if (this < min) min else this
