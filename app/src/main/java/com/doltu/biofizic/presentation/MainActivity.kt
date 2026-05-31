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
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.CornerRadius
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Path
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

@Composable
fun BiofizicWatchApp() {
    val context = LocalContext.current
    val uiState by SensorService.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(uiState.isRunning) {
        if (uiState.isRunning) {
            (context as? MainActivity)?.applyKeepScreenOn()
        }
    }

    var showQuestionnaire by remember { mutableStateOf(false) }
    var questionnaireForStart by remember { mutableStateOf(true) }

    MaterialTheme {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(BgTop, BgBottom))),
        ) {
            val minSide = minOf(maxWidth, maxHeight)
            val contentW = minSide * SAFE_WIDTH_FRACTION

            if (showQuestionnaire) {
                MoodQuestionnaire(
                    contentWidth = contentW,
                    onDone = { arousal ->
                        val action = if (questionnaireForStart) SensorService.ACTION_START
                        else SensorService.ACTION_RECALIBRATE
                        context.startForegroundService(
                            Intent(context, SensorService::class.java).apply {
                                this.action = action
                                putExtra(SensorService.EXTRA_REPORTED_AROUSAL, arousal)
                            },
                        )
                        (context as? MainActivity)?.applyKeepScreenOn()
                        showQuestionnaire = false
                    },
                    onCancel = { showQuestionnaire = false },
                    modifier = Modifier.align(Alignment.Center).width(contentW),
                )
            } else {
                // One centered column: info on top, buttons below — no overlap.
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 8.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    WatchFaceContent(
                        isRunning = uiState.isRunning,
                        mqttOk = uiState.isMqttConnected,
                        hr = uiState.lastHr,
                        arousalFused = uiState.arousalFused,
                        arousal10 = uiState.arousal10,
                        arousalLabel = uiState.arousalLabel,
                        confidence = uiState.arousalConfidence,
                        dominantChannel = uiState.dominantChannel,
                        motionGated = uiState.motionGated,
                        profileReady = uiState.profileReady,
                        signalOk = uiState.signalOk,
                        decisionFidelity = uiState.decisionFidelity,
                        windowSec = uiState.lastWindowSec.toFloat(),
                        calibrating = uiState.calibrationPhase == "collecting",
                        calMessage = uiState.calibrationMessage,
                        contentWidth = contentW,
                        onLongPressRecalibrate = {
                            if (uiState.isRunning) {
                                questionnaireForStart = false
                                showQuestionnaire = true
                            }
                        },
                        modifier = Modifier.width(contentW),
                    )

                    Spacer(Modifier.height(10.dp))

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (uiState.isRunning) {
                            SquareButton(
                                color = AccentMotion,
                                side = 34.dp,
                                onClick = {
                                    questionnaireForStart = false
                                    showQuestionnaire = true
                                },
                            ) { RecalibrateGlyph() }
                        }
                        val btnColor by animateColorAsState(
                            if (uiState.isRunning) BtnStop else BtnStart,
                            animationSpec = tween(200),
                            label = "btn",
                        )
                        SquareButton(
                            color = btnColor,
                            side = 40.dp,
                            onClick = {
                                if (uiState.isRunning) {
                                    context.startForegroundService(
                                        Intent(context, SensorService::class.java).apply {
                                            action = SensorService.ACTION_STOP
                                        },
                                    )
                                    (context as? MainActivity)?.window?.clearFlags(
                                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                                    )
                                } else {
                                    questionnaireForStart = true
                                    showQuestionnaire = true
                                }
                            },
                        ) {
                            if (uiState.isRunning) StopGlyph() else PlayGlyph()
                        }
                    }
                }
            }
        }
    }
}

/** Square (rounded) button hosting a Canvas-drawn glyph (no icon dependency). */
@Composable
private fun SquareButton(
    color: Color,
    side: Dp,
    onClick: () -> Unit,
    glyph: @Composable () -> Unit,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(side)
            .clip(RoundedCornerShape(11.dp))
            .background(color)
            .clickable(onClick = onClick),
    ) {
        glyph()
    }
}

@Composable
private fun PlayGlyph() {
    Canvas(modifier = Modifier.size(16.dp)) {
        val p = Path().apply {
            moveTo(size.width * 0.28f, size.height * 0.18f)
            lineTo(size.width * 0.82f, size.height * 0.5f)
            lineTo(size.width * 0.28f, size.height * 0.82f)
            close()
        }
        drawPath(p, Color.White)
    }
}

@Composable
private fun StopGlyph() {
    Canvas(modifier = Modifier.size(14.dp)) {
        drawRoundRect(
            color = Color.White,
            topLeft = Offset(size.width * 0.18f, size.height * 0.18f),
            size = Size(size.width * 0.64f, size.height * 0.64f),
        )
    }
}

@Composable
private fun RecalibrateGlyph() {
    Canvas(modifier = Modifier.size(14.dp)) {
        val stroke = 2.dp.toPx()
        drawArc(
            color = Color.White,
            startAngle = 40f,
            sweepAngle = 280f,
            useCenter = false,
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
        // small arrowhead at the open end
        val a = Path().apply {
            moveTo(size.width * 0.86f, size.height * 0.30f)
            lineTo(size.width * 0.98f, size.height * 0.52f)
            lineTo(size.width * 0.72f, size.height * 0.50f)
            close()
        }
        drawPath(a, Color.White)
    }
}

@Composable
private fun MoodQuestionnaire(
    contentWidth: Dp,
    onDone: (arousal: Double) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Single arousal self-report: anchors where the personal baseline sits on the
    // 0..1 arousal scale (Relaxat -> Stresat). The pick is sent as reported_arousal.
    val arousalOpts = listOf("Relaxat" to 0.15, "Normal" to 0.40, "Tensionat" to 0.65, "Stresat" to 0.85)
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Cât de stresat te simți?", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
        Spacer(Modifier.height(6.dp))
        arousalOpts.forEach { (label, value) ->
            Button(
                onClick = { onDone(value) },
                colors = ButtonDefaults.buttonColors(backgroundColor = RingTrack),
                modifier = Modifier.width(contentWidth).height(26.dp).padding(vertical = 2.dp),
            ) {
                Text(label, fontSize = 11.sp, color = TextPrimary)
            }
        }
        Spacer(Modifier.height(2.dp))
        Text("apoi stai liniștit 1–2 min", fontSize = 8.sp, color = TextMuted)
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
    dominantChannel: String,
    motionGated: Boolean,
    profileReady: Boolean,
    signalOk: Boolean,
    decisionFidelity: String,
    windowSec: Float,
    calibrating: Boolean,
    calMessage: String,
    contentWidth: Dp,
    onLongPressRecalibrate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hasVerdict = isRunning && arousalFused >= 0f
    val accent = arousalAccent(hasVerdict, arousal10, motionGated, isRunning, mqttOk)

    // Arousal-only classifier: big number /10 + the arousal label.
    val statusText = when {
        calibrating && calMessage.isNotBlank() -> calMessage
        hasVerdict -> arousalLabel
        isRunning && !mqttOk -> "Fără server"
        isRunning -> "Se încarcă"
        else -> "Apasă ▶"
    }
    val bigValue = if (hasVerdict) "%.0f".format(arousalFused) else "—"

    Column(
        modifier = modifier
            .width(contentWidth)
            .combinedClickable(onClick = {}, onLongClick = onLongPressRecalibrate),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CompactStatusLine(
            isRunning = isRunning,
            mqttOk = mqttOk,
            profileReady = profileReady && !calibrating,
            signalOk = signalOk,
            calibrating = calibrating,
            decisionFidelity = decisionFidelity,
            hasVerdict = hasVerdict,
        )

        Spacer(modifier = Modifier.height(6.dp))

        // Compact arousal card: label + value + bar (no oversized circle).
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(accent.copy(alpha = 0.16f))
                .border(1.dp, accent.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
                .padding(horizontal = 10.dp, vertical = 5.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (calibrating) {
                // While the profile calibrates there is no score yet: one short
                // row — "Calibrare" on the left, the rotating loader on the right
                // — so it keeps the card height and does not push the buttons down.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "Calibrare",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = accent,
                    )
                    CalibrationSpinner(accent = accent, size = 20.dp)
                }
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = bigValue,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary,
                            lineHeight = 22.sp,
                        )
                        Text(
                            text = "/10  ",
                            fontSize = 8.sp,
                            color = TextMuted,
                            modifier = Modifier.padding(bottom = 3.dp),
                        )
                        Text(
                            text = statusText,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = accent,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(bottom = 2.dp),
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    ArousalBar(
                        fraction = if (hasVerdict) arousalFused / 10f else 0f,
                        accent = accent,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Metrics stacked, one per line — readable, no edge wrap.
        MetricRow("Ritm", if (hr > 0) "$hr bpm" else "—")
        Spacer(modifier = Modifier.height(3.dp))
        val channelTag = when (dominantChannel) {
            "hr" -> " · HR"      // motion: verdict carried by heart rate
            "blend" -> " · mix"
            "none" -> " · —"
            else -> ""           // "hrv": still/precise, no tag needed
        }
        MetricRow(
            "Încredere",
            if (hasVerdict) "${(confidence * 100).toInt()}%$channelTag" else "—",
        )
        Spacer(modifier = Modifier.height(3.dp))
        MetricRow("Stare", if (motionGated) "mișcare" else "calm")
    }
}

/** Indeterminate loader shown in the central card while the profile calibrates:
 *  a faint full-circle track with a rotating accent arc. */
@Composable
private fun CalibrationSpinner(accent: Color, size: Dp = 30.dp) {
    val transition = rememberInfiniteTransition(label = "calib")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "calib-angle",
    )
    Canvas(modifier = Modifier.size(size)) {
        val stroke = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
        val inset = stroke.width / 2f
        val arcSize = Size(this.size.width - stroke.width, this.size.height - stroke.width)
        drawArc(
            color = RingTrack,
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = arcSize,
            style = stroke,
        )
        drawArc(
            color = accent,
            startAngle = angle,
            sweepAngle = 270f,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = arcSize,
            style = stroke,
        )
    }
}

@Composable
private fun ArousalBar(fraction: Float, accent: Color) {
    val animated by animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1f),
        animationSpec = tween(500),
        label = "bar",
    )
    Canvas(modifier = Modifier.fillMaxWidth().height(5.dp)) {
        val r = size.height / 2f
        drawRoundRect(color = RingTrack, cornerRadius = CornerRadius(r, r))
        if (animated > 0.01f) {
            drawRoundRect(
                color = accent,
                size = Size(size.width * animated, size.height),
                cornerRadius = CornerRadius(r, r),
            )
        }
    }
}

/** Full-width metric row: label on the left, value on the right. */
@Composable
private fun MetricRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(9.dp))
            .background(Color(0xFF1A1F2B))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 9.sp, color = TextMuted)
        Text(value, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
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
    decisionFidelity: String = "calibrated",
    hasVerdict: Boolean = false,
) {
    val liveColor = when {
        !isRunning -> AccentIdle
        mqttOk -> AccentActive
        else -> AccentHigh
    }
    // Server-side fidelity is the source of truth once a verdict exists.
    // "preliminary" means we already show arousal_10 (Kubios population
    // fallback) — display it as motion-yellow with "~" suffix so the user
    // sees the verdict immediately AND knows it isn't personalised yet.
    // "calibrated" + profileReady → green "OK". This replaces the old
    // 6-min spinner that hid arousal until baseline locked.
    val isPreliminaryVerdict = hasVerdict && decisionFidelity == "preliminary"
    val profColor = when {
        !isRunning -> AccentIdle
        !mqttOk -> AccentIdle
        calibrating -> AccentMotion
        profileReady && decisionFidelity == "calibrated" -> AccentActive
        isPreliminaryVerdict -> AccentMotion
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
        profileReady && decisionFidelity == "calibrated" -> "Profil OK"
        isPreliminaryVerdict -> "Profil~"
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

