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

/** Safe zone on a round screen (~65% of the diameter). */
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
    var showFeedback by remember { mutableStateOf(false) }
    MaterialTheme {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(BgTop, BgBottom))),
        ) {
            val minSide = minOf(maxWidth, maxHeight)
            val contentW = minSide * SAFE_WIDTH_FRACTION

            if (showFeedback) {
                EmotionQuestionnaire(
                    contentWidth = contentW,
                    onDone = { quadrant ->
                        // Fire-and-forget label: the server pairs it with the
                        // recent PPG features. No recalibration, no spinner.
                        context.startForegroundService(
                            Intent(context, SensorService::class.java).apply {
                                this.action = SensorService.ACTION_FEEDBACK
                                putExtra(SensorService.EXTRA_FEEDBACK_QUADRANT, quadrant)
                            },
                        )
                        showFeedback = false
                    },
                    onCancel = { showFeedback = false },
                    modifier = Modifier.align(Alignment.Center).width(contentW),
                )
            } else if (showQuestionnaire) {
                MoodQuestionnaire(
                    contentWidth = contentW,
                    onDone = { arousal, reactivity ->
                        // The questionnaire is shown ONLY from the recalibrate
                        // button, so it always means a profile recalibration.
                        context.startForegroundService(
                            Intent(context, SensorService::class.java).apply {
                                this.action = SensorService.ACTION_RECALIBRATE
                                putExtra(SensorService.EXTRA_REPORTED_AROUSAL, arousal)
                                putExtra(SensorService.EXTRA_REACTIVITY, reactivity)
                            },
                        )
                        (context as? MainActivity)?.applyKeepScreenOn()
                        showQuestionnaire = false
                    },
                    onCancel = { showQuestionnaire = false },
                    modifier = Modifier.align(Alignment.Center).width(contentW),
                )
            } else {
                // One centered column: info on top, buttons below, no overlap.
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
                        emotionVerdict = uiState.emotionVerdict,
                        emotionScores = uiState.emotionScores,
                        emotionConfidence = uiState.emotionConfidence,
                        motionGated = uiState.motionGated,
                        profileReady = uiState.profileReady,
                        signalOk = uiState.signalOk,
                        decisionFidelity = uiState.decisionFidelity,
                        calibrating = uiState.calibrationPhase == "collecting",
                        calMessage = uiState.calibrationMessage,
                        contentWidth = contentW,
                        onLongPressRecalibrate = {
                            if (uiState.isRunning) {
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
                                    showQuestionnaire = true
                                },
                            ) { RecalibrateGlyph() }
                            // Emotion feedback: the user labels how they feel now.
                            SquareButton(
                                color = Color(0xFF8E44AD),
                                side = 34.dp,
                                onClick = {
                                    showFeedback = true
                                },
                            ) { FeedbackGlyph() }
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
                                    // Plain start: just begin tracking with the
                                    // persisted baseline, no questionnaire, no
                                    // recalibration. Recalibrate only via the
                                    // yellow button.
                                    context.startForegroundService(
                                        Intent(context, SensorService::class.java).apply {
                                            action = SensorService.ACTION_START
                                        },
                                    )
                                    (context as? MainActivity)?.applyKeepScreenOn()
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
private fun FeedbackGlyph() {
    // A simple smiley: outline circle + two eyes + a smile arc, the "label how
    // you feel" affordance.
    Canvas(modifier = Modifier.size(16.dp)) {
        val stroke = 1.6.dp.toPx()
        drawCircle(
            color = Color.White,
            radius = size.minDimension * 0.42f,
            center = Offset(size.width / 2f, size.height / 2f),
            style = Stroke(width = stroke),
        )
        val eyeR = size.minDimension * 0.06f
        drawCircle(Color.White, eyeR, Offset(size.width * 0.38f, size.height * 0.42f))
        drawCircle(Color.White, eyeR, Offset(size.width * 0.62f, size.height * 0.42f))
        drawArc(
            color = Color.White,
            startAngle = 20f,
            sweepAngle = 140f,
            useCenter = false,
            topLeft = Offset(size.width * 0.30f, size.height * 0.38f),
            size = Size(size.width * 0.40f, size.height * 0.32f),
            style = Stroke(width = stroke, cap = StrokeCap.Round),
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

/** Filled heart, the heart-rate metric icon. */
@Composable
private fun HeartIcon(color: Color, size: Dp = 13.dp) {
    Canvas(modifier = Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val p = Path().apply {
            moveTo(w * 0.5f, h * 0.86f)
            // left lobe
            cubicTo(w * 0.10f, h * 0.58f, w * 0.04f, h * 0.30f, w * 0.26f, h * 0.20f)
            cubicTo(w * 0.40f, h * 0.13f, w * 0.50f, h * 0.24f, w * 0.5f, h * 0.34f)
            // right lobe (mirror)
            cubicTo(w * 0.50f, h * 0.24f, w * 0.60f, h * 0.13f, w * 0.74f, h * 0.20f)
            cubicTo(w * 0.96f, h * 0.30f, w * 0.90f, h * 0.58f, w * 0.5f, h * 0.86f)
            close()
        }
        drawPath(p, color)
    }
}

/** A small running figure, the motion-state icon (calm vs moving). */
@Composable
private fun MotionIcon(color: Color, size: Dp = 13.dp) {
    Canvas(modifier = Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val stroke = 1.7.dp.toPx()
        // head
        drawCircle(color, radius = w * 0.10f, center = Offset(w * 0.62f, h * 0.18f))
        // torso + legs (running stance)
        val body = Path().apply {
            moveTo(w * 0.62f, h * 0.30f)
            lineTo(w * 0.50f, h * 0.55f)   // torso down
            lineTo(w * 0.30f, h * 0.78f)   // back leg
            moveTo(w * 0.50f, h * 0.55f)
            lineTo(w * 0.62f, h * 0.82f)   // front leg
        }
        drawPath(body, color, style = Stroke(width = stroke, cap = StrokeCap.Round))
        // arms
        val arms = Path().apply {
            moveTo(w * 0.58f, h * 0.40f)
            lineTo(w * 0.36f, h * 0.46f)   // back arm
            moveTo(w * 0.58f, h * 0.40f)
            lineTo(w * 0.80f, h * 0.34f)   // front arm
        }
        drawPath(arms, color, style = Stroke(width = stroke, cap = StrokeCap.Round))
    }
}

@Composable
private fun EmotionQuestionnaire(
    contentWidth: Dp,
    onDone: (quadrant: String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The user labels how they feel RIGHT NOW with one of the 3 model states. This
    // is the ground-truth the models are validated against and the personal model is
    // trained on. One tap = one labelled training pair. The display has the accented
    // word; the value sent is the canonical state the server's model uses (no
    // diacritics): Calm / Disconfort / Placut, matching STATE_CODE on the server.
    val states = listOf(
        Triple("Plăcut", "Placut", Color(0xFFB8860B)),
        Triple("Calm", "Calm", Color(0xFF2E7D32)),
        Triple("Disconfort", "Disconfort", Color(0xFFC62828)),
    )
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Cum te simți acum?", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
        Spacer(Modifier.height(6.dp))
        states.forEach { (display, value, color) ->
            Button(
                onClick = { onDone(value) },
                colors = ButtonDefaults.buttonColors(backgroundColor = color),
                modifier = Modifier.width(contentWidth).height(26.dp).padding(vertical = 2.dp),
            ) {
                Text(display, fontSize = 11.sp, color = Color.White)
            }
        }
    }
}

@Composable
private fun MoodQuestionnaire(
    contentWidth: Dp,
    onDone: (arousal: Double, reactivity: String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Two-step recalibration self-report (only shown from the yellow recalibrate
    // button, never on plain start):
    //   1. arousal -> anchors where the personal baseline sits on the 0..1 scale.
    //   2. reactivity -> the one-time emotional-responsiveness profile that scales
    //      the valence dead-band (low responder -> narrower neutral zone).
    val arousalOpts = listOf("Relaxat" to 0.15, "Normal" to 0.40, "Tensionat" to 0.65, "Stresat" to 0.85)
    val reactivityOpts = listOf("Puțin" to "low", "Normal" to "normal", "Mult" to "high")
    var pickedArousal by remember { mutableStateOf<Double?>(null) }

    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        if (pickedArousal == null) {
            Text("Cât de stresat te simți?", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
            Spacer(Modifier.height(6.dp))
            arousalOpts.forEach { (label, value) ->
                Button(
                    onClick = { pickedArousal = value },
                    colors = ButtonDefaults.buttonColors(backgroundColor = RingTrack),
                    modifier = Modifier.width(contentWidth).height(26.dp).padding(vertical = 2.dp),
                ) {
                    Text(label, fontSize = 11.sp, color = TextPrimary)
                }
            }
            // Escape hatch: an accidental recalibrate is never a trap.
            Spacer(Modifier.height(3.dp))
            Text(
                "✕ Anulează",
                fontSize = 10.sp,
                color = TextMuted,
                modifier = Modifier
                    .clickable { onCancel() }
                    .padding(4.dp),
            )
        } else {
            Text("Cât de reactiv emoțional ești?", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
            Spacer(Modifier.height(6.dp))
            reactivityOpts.forEach { (label, value) ->
                Button(
                    onClick = { onDone(pickedArousal!!, value) },
                    colors = ButtonDefaults.buttonColors(backgroundColor = RingTrack),
                    modifier = Modifier.width(contentWidth).height(26.dp).padding(vertical = 2.dp),
                ) {
                    Text(label, fontSize = 11.sp, color = TextPrimary)
                }
            }
            Spacer(Modifier.height(2.dp))
            Text("apoi stai liniștit 1-2 min", fontSize = 8.sp, color = TextMuted)
            // Back to step 1 (re-pick arousal) instead of being forced forward.
            Spacer(Modifier.height(2.dp))
            Text(
                "‹ Înapoi",
                fontSize = 10.sp,
                color = TextMuted,
                modifier = Modifier
                    .clickable { pickedArousal = null }
                    .padding(4.dp),
            )
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
    emotionVerdict: String,
    emotionScores: String,
    emotionConfidence: Float,
    motionGated: Boolean,
    profileReady: Boolean,
    signalOk: Boolean,
    decisionFidelity: String,
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
                // row, "Calibrare" on the left, the rotating loader on the right
                //, so it keeps the card height and does not push the buttons down.
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
                // Emotion-first layout: the verdict is the hero (large), the arousal
                // score + bar sit smaller above it, and the two-axis scores + verdict
                // confidence sit quietly below. One column, fixed heights, no overflow.
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    // Compact arousal line: number + /10 + label, all on one short row.
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = bigValue,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary,
                            lineHeight = 15.sp,
                        )
                        Text(
                            text = "/10 ",
                            fontSize = 7.sp,
                            color = TextMuted,
                            modifier = Modifier.padding(bottom = 2.dp),
                        )
                        Text(
                            text = statusText,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = accent,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(bottom = 1.dp),
                        )
                    }
                    Spacer(modifier = Modifier.height(3.dp))
                    ArousalBar(
                        fraction = if (hasVerdict) arousalFused / 10f else 0f,
                        accent = accent,
                    )

                    // THE HERO: the emotion verdict, large and centered.
                    if (hasVerdict && emotionVerdict.isNotBlank() &&
                        emotionVerdict != "—" && emotionVerdict != "-"
                    ) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = emotionVerdict,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = accent,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                            lineHeight = 17.sp,
                        )
                        // The two affective axes (arousal + valence), always shown,
                        // valence marked "~" when the server could not assert it.
                        if (emotionScores.isNotBlank()) {
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = emotionScores,
                                fontSize = 8.sp,
                                color = TextMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                            )
                        }
                        // The verdict's OWN confidence (not the arousal one).
                        if (emotionConfidence > 0f) {
                            Text(
                                text = "încredere ${(emotionConfidence * 100).toInt()}%",
                                fontSize = 8.sp,
                                color = TextMuted,
                                maxLines = 1,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(9.dp))

        // Compact metrics row with vector icons: heart-rate + motion state, one line.
        Row(
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                HeartIcon(color = Color(0xFFFF5E7A))
                Text(
                    text = if (hr > 0) "$hr" else "—",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary,
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                MotionIcon(color = if (motionGated) AccentMotion else AccentActive)
                Text(
                    text = if (motionGated) "mișcare" else "calm",
                    fontSize = 12.sp,
                    color = TextMuted,
                )
            }
        }
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

/** A single narrow row that stays clear of the round edges. */
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
    // fallback), display it as motion-yellow with "~" suffix so the user
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

