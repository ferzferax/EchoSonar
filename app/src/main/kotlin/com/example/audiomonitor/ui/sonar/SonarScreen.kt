package com.example.audiomonitor.ui.sonar

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.audiomonitor.sonar.RfMode
import com.example.audiomonitor.sonar.RfSnapshot
import com.example.audiomonitor.sonar.SonarPose
import kotlin.math.cos
import kotlin.math.sin

enum class TargetClassification(
    private val enLabel: String,
    private val ptLabel: String,
    val emoji: String,
    val color: Color
) {
    PAREDE("SOLID WALL / RIGID", "SUPERFÍCIE RÍGIDA / PAREDE", "🧱", Color(0xFF00E676)),
    HUMANO("LIVING PRESENCE / HUMAN", "PRESENÇA VIVA / HUMANO", "👤", Color(0xFFFF5252)),
    PORTA("SOFT OBJECT / PASSAGE", "OBJETO MACIO / PASSAGEM", "🚪", Color(0xFF64B5F6)),
    INDEFINIDO("SCANNING BEAM", "VARRENDO FEIXE", "📡", Color(0xFFB0BEC5));

    fun label(language: String): String = if (language == "PT") ptLabel else enLabel
}

data class SonarTarget(
    val distanceMeters: Float,
    val angleDegrees: Float = 0.0f,
    val confidence: Float,
    val classification: TargetClassification = TargetClassification.PAREDE,
    val timestamp: Long = System.currentTimeMillis()
)

@Composable
fun SonarScreen(
    language: String = "EN",
    isActive: Boolean,
    onToggleActive: () -> Unit,
    currentTarget: SonarTarget? = null,
    radarBlips: List<SonarTarget> = emptyList(),
    echoProfile: List<Float> = emptyList(),
    pose: SonarPose = SonarPose(),
    rfSnapshot: RfSnapshot? = null,
    cadenceMs: Float = 80f,
    onCadenceChange: (Float) -> Unit = {},
    snrThreshold: Float = 2.6f,
    onSensitivityChange: (Float) -> Unit = {},
    audioFeedbackEnabled: Boolean = true,
    onToggleAudioFeedback: () -> Unit = {},
    onRecenterAzimuth: () -> Unit = {},
    errorMessage: String? = null
) {
    val isEn = language != "PT"
    val targetType = currentTarget?.classification ?: TargetClassification.INDEFINIDO

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (!errorMessage.isNullOrBlank()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                color = Color(0xFF3A1212),
                border = BorderStroke(1.dp, Color(0xFFFF5252))
            ) {
                Text(
                    text = errorMessage,
                    color = Color(0xFFFFCDD2),
                    fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        }

        // 1. COMPACT SONAR CONTROL BAR
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, if (isActive) Color(0xFF00E676) else Color(0xFF424242)),
            colors = CardDefaults.cardColors(containerColor = if (isActive) Color(0xFF0D2413) else Color(0xFF161616))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(if (isActive) Color(0xFF2E7D32) else Color(0xFFD32F2F)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isActive) Icons.Default.Stop else Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    Column {
                        Text(
                            text = when {
                                isActive && isEn -> "ULTRASONIC SONAR ACTIVE"
                                isActive -> "SONAR ULTRASSÔNICO ATIVO"
                                isEn -> "SONAR IN STANDBY"
                                else -> "SONAR EM STANDBY"
                            },
                            fontSize = 12.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White
                        )
                        Text(
                            text = "FMCW 18.5–20.5 kHz • 48 kHz • SNR ≥ ${"%.1f".format(snrThreshold)}x",
                            fontSize = 9.sp,
                            color = Color(0xFF9E9E9E),
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        onClick = onToggleAudioFeedback,
                        shape = RoundedCornerShape(8.dp),
                        color = if (audioFeedbackEnabled) Color(0xFF132F26) else Color(0xFF252525),
                        border = BorderStroke(
                            1.dp,
                            if (audioFeedbackEnabled) Color(0xFF00E5FF) else Color(0xFF555555)
                        )
                    ) {
                        Icon(
                            imageVector = if (audioFeedbackEnabled) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
                            contentDescription = if (isEn) "Toggle Sci-Fi Audio" else "Alternar Som Sci-Fi",
                            tint = if (audioFeedbackEnabled) Color(0xFF00E5FF) else Color(0xFF888888),
                            modifier = Modifier
                                .padding(6.dp)
                                .size(18.dp)
                        )
                    }

                    Surface(
                        onClick = onRecenterAzimuth,
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFF12232D),
                        border = BorderStroke(1.dp, Color(0xFF42A5F5))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.CenterFocusStrong,
                                contentDescription = if (isEn) "Recenter 0°" else "Zerar Bússola 0°",
                                tint = Color(0xFF64B5F6),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(3.dp))
                            Text("0°", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFF64B5F6))
                        }
                    }

                    Button(
                        onClick = onToggleActive,
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isActive) Color(0xFFD32F2F) else Color(0xFF2E7D32)
                        ),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text(
                            text = when {
                                isActive && isEn -> "STOP"
                                isActive -> "PARAR"
                                isEn -> "START"
                                else -> "ATIVAR"
                            },
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // 2. CRT POLAR RADAR + PERSISTENCE TRAIL + A-SCAN OSCILLOSCOPE + TILT HUD
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.5.dp, if (pose.isAligned) Color(0xFF1B5E20) else Color(0xFF5D4037)),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF060E09))
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    AlienRadarView(
                        isActive = isActive,
                        currentTarget = currentTarget,
                        radarBlips = radarBlips,
                        pose = pose
                    )

                    // Top-Left Range & RF Telemetry HUD
                    Column(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = Color(0xCC000000),
                            border = BorderStroke(
                                0.5.dp,
                                if (currentTarget != null) Color(0xFF00E676) else Color(0xFF2E7D32)
                            )
                        ) {
                            val distLabel = when {
                                !isActive -> "STANDBY"
                                currentTarget != null -> {
                                    val snrStr = "%.1f".format(currentTarget.confidence)
                                    val degStr = "${currentTarget.angleDegrees.toInt()}°"
                                    val prefix = if (isEn) "TGT" else "ALVO"
                                    "$prefix: ${"%.2f".format(currentTarget.distanceMeters)} m • $degStr • ${snrStr}x"
                                }
                                isEn -> "SCANNING..."
                                else -> "VARRENDO..."
                            }
                            Text(
                                text = distLabel,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = when {
                                    !isActive -> Color.Gray
                                    currentTarget != null -> Color(0xFF00E676)
                                    else -> Color(0xFF81C784)
                                },
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }

                        rfSnapshot?.let { rf ->
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = Color(0xDD071524),
                                border = BorderStroke(0.5.dp, Color(0xFF1E88E5))
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "📡 RF: ${rf.bleCount} BLE",
                                        fontSize = 9.sp,
                                        color = Color(0xFF64B5F6),
                                        fontFamily = FontFamily.Monospace
                                    )
                                    if (rf.wifiRssiDbm != null) {
                                        Text(
                                            text = " • ${rf.wifiRssiDbm} dBm",
                                            fontSize = 9.sp,
                                            color = Color(0xFF90CAF9),
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                    if (rf.mode == RfMode.ROOT) {
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = "⚡ROOT",
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFFFFD54F),
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Top-Right Tilt Gauge HUD
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(4.dp),
                        shape = RoundedCornerShape(6.dp),
                        color = Color(0xDD000000),
                        border = BorderStroke(1.dp, if (pose.isAligned) Color(0xFF00E676) else Color(0xFFFFB300))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(if (pose.isAligned) Color(0xFF00E676) else Color(0xFFFFB300))
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = pose.guidancePrompt,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (pose.isAligned) Color(0xFF00E676) else Color(0xFFFFB300)
                            )
                        }
                    }

                    // Active Target Classification Pill
                    if (isActive && currentTarget != null && currentTarget.distanceMeters <= 3.0f) {
                        Surface(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 4.dp),
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0xDD000000),
                            border = BorderStroke(1.dp, targetType.color)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(targetType.emoji, fontSize = 15.sp)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = targetType.label(language),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = targetType.color,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                // A-SCAN ECHO ENVELOPE OSCILLOSCOPE (0.2m .. 3.0m)
                AScanOscilloscopeBar(
                    isActive = isActive,
                    echoProfile = echoProfile,
                    isEn = isEn
                )
            }
        }

        // 3. REAL-TIME ENGINE CONTROLS: CADENCE & SNR SENSITIVITY
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, Color(0xFF262626)),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF141414))
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                // Row 1: Cadence / EMA Smoothing
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isEn) "RESPONSE CADENCE & SMOOTHING" else "CADÊNCIA DE RESPOSTA & SUAVIZAÇÃO",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF999999)
                    )
                    Text(
                        text = "${cadenceMs.toInt()} ms",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF00E676),
                        fontFamily = FontFamily.Monospace
                    )
                }

                Slider(
                    value = cadenceMs,
                    onValueChange = onCadenceChange,
                    valueRange = 50f..240f,
                    colors = SliderDefaults.colors(
                        thumbColor = Color(0xFF00E676),
                        activeTrackColor = Color(0xFF00E676),
                        inactiveTrackColor = Color(0xFF222222)
                    ),
                    modifier = Modifier.height(26.dp)
                )

                Spacer(modifier = Modifier.height(4.dp))

                // Row 2: Sensitivity (SNR Threshold)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isEn) "ECHO SENSITIVITY (SNR GATE)" else "SENSIBILIDADE DE ECO (FILTRO SNR)",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF999999)
                    )
                    Text(
                        text = "${"%.1f".format(snrThreshold)}x SNR",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF00E5FF),
                        fontFamily = FontFamily.Monospace
                    )
                }

                Slider(
                    value = snrThreshold,
                    onValueChange = onSensitivityChange,
                    valueRange = 1.8f..4.5f,
                    colors = SliderDefaults.colors(
                        thumbColor = Color(0xFF00E5FF),
                        activeTrackColor = Color(0xFF00E5FF),
                        inactiveTrackColor = Color(0xFF222222)
                    ),
                    modifier = Modifier.height(26.dp)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = if (isEn) "← High Sensitivity (1.8x)" else "← Alta Sensibilidade (1.8x)",
                        fontSize = 9.sp,
                        color = Color(0xFF666666)
                    )
                    Text(
                        text = if (isEn) "Strict Noise Filter (4.5x) →" else "Filtro Rigoroso (4.5x) →",
                        fontSize = 9.sp,
                        color = Color(0xFF666666)
                    )
                }
            }
        }
    }
}

@Composable
fun AScanOscilloscopeBar(
    isActive: Boolean,
    echoProfile: List<Float>,
    isEn: Boolean
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = if (isEn) "A-SCAN ECHO ENVELOPE (0.3m – 3.0m)" else "OSCILOSCÓPIO DE ECO A-SCAN (0.3m – 3.0m)",
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = Color(0xFF4CAF50)
            )
            Text(
                text = if (isActive) "LIVE" else "IDLE",
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                color = if (isActive) Color(0xFF00E5FF) else Color(0xFF666666)
            )
        }
        Spacer(modifier = Modifier.height(3.dp))
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xFF030805))
        ) {
            val safe = if (echoProfile.size >= 32) echoProfile else List(32) { 0.04f }
            val count = safe.size
            val gap = 2.dp.toPx()
            val barWidth = ((size.width - gap * (count + 1)) / count).coerceAtLeast(2f)

            for (i in 0 until count) {
                val amp = if (isActive) safe[i].coerceIn(0.04f, 1.0f) else 0.04f
                val h = size.height * amp
                val x = gap + i * (barWidth + gap)
                val y = size.height - h
                val c = when {
                    amp > 0.72f -> Color(0xFFFF5252)
                    amp > 0.42f -> Color(0xFF00E5FF)
                    else -> Color(0xFF00E676)
                }
                drawRect(
                    color = c.copy(alpha = 0.85f),
                    topLeft = Offset(x, y),
                    size = Size(barWidth, h)
                )
            }
        }
    }
}

@Composable
fun AlienRadarView(
    isActive: Boolean,
    currentTarget: SonarTarget?,
    radarBlips: List<SonarTarget>,
    pose: SonarPose
) {
    val transition = rememberInfiniteTransition(label = "radar_sweep")
    val sweepAngle by transition.animateFloat(
        initialValue = -50f,
        targetValue = 50f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "sweep"
    )

    Canvas(modifier = Modifier.fillMaxSize()) {
        val center = Offset(size.width / 2f, size.height * 0.92f)
        val maxRadius = size.height * 0.84f
        val maxScaleMeters = 3.0f

        // 1. Concentric range rings (0.5m, 1.0m, 1.5m, 2.0m, 2.5m, 3.0m)
        val rings = listOf(0.5f, 1.0f, 1.5f, 2.0f, 2.5f, 3.0f)
        rings.forEach { distM ->
            val r = (distM / maxScaleMeters) * maxRadius
            drawCircle(
                color = Color(0xFF0D3319),
                radius = r,
                center = center,
                style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
            )
        }

        // 2. Azimuth radial lines (-45° .. +45°)
        val angles = listOf(-45f, -30f, -15f, 0f, 15f, 30f, 45f)
        angles.forEach { deg ->
            val rad = Math.toRadians((deg - 90).toDouble())
            val endX = center.x + (maxRadius * cos(rad)).toFloat()
            val endY = center.y + (maxRadius * sin(rad)).toFloat()
            drawLine(
                color = if (deg == 0f) Color(0xFF144222) else Color(0xFF0A2914),
                start = center,
                end = Offset(endX, endY),
                strokeWidth = 1.dp.toPx()
            )
        }

        // 3. Bi-modal tilt gauge on right edge
        val barRightX = size.width - 10.dp.toPx()
        val barTopY = size.height * 0.22f
        val barBottomY = size.height * 0.78f
        val barHeight = barBottomY - barTopY

        drawLine(
            color = Color(0xFF1B3D22),
            start = Offset(barRightX, barTopY),
            end = Offset(barRightX, barBottomY),
            strokeWidth = 2.dp.toPx()
        )

        val standingTop = barTopY + barHeight * (1.0f - (85f / 90f))
        val standingBottom = barTopY + barHeight * (1.0f - (35f / 90f))
        drawLine(
            color = Color(0xFF00E676),
            start = Offset(barRightX, standingTop),
            end = Offset(barRightX, standingBottom),
            strokeWidth = 4.dp.toPx(),
            cap = StrokeCap.Round
        )

        val flatTop = barTopY + barHeight * (1.0f - (30f / 90f))
        drawLine(
            color = Color(0xFF81C784),
            start = Offset(barRightX, flatTop),
            end = Offset(barRightX, barBottomY),
            strokeWidth = 3.dp.toPx(),
            cap = StrokeCap.Round
        )

        val clampedPitch = pose.pitchDeg.coerceIn(0f, 90f)
        val pitchY = barTopY + barHeight * (1.0f - (clampedPitch / 90f))
        drawCircle(
            color = if (pose.isAligned) Color(0xFF00E676) else Color(0xFFFFB300),
            radius = 5.dp.toPx(),
            center = Offset(barRightX, pitchY)
        )

        // 4. Phosphorescent sweep ray
        if (isActive) {
            val sweepRad = Math.toRadians((sweepAngle - 90).toDouble())
            val sweepEndX = center.x + (maxRadius * cos(sweepRad)).toFloat()
            val sweepEndY = center.y + (maxRadius * sin(sweepRad)).toFloat()

            drawLine(
                brush = Brush.linearGradient(
                    listOf(Color(0xFF00E676), Color(0x2200E676)),
                    start = center,
                    end = Offset(sweepEndX, sweepEndY)
                ),
                start = center,
                end = Offset(sweepEndX, sweepEndY),
                strokeWidth = 2.5.dp.toPx(),
                cap = StrokeCap.Round
            )

            // 5. 6-second Phosphor Persistence Blip Trail (Spatial Sweep Mapping)
            val now = System.currentTimeMillis()
            radarBlips.forEach { blip ->
                val ageMs = (now - blip.timestamp).coerceAtLeast(0L)
                if (ageMs <= 6000L && blip.distanceMeters <= maxScaleMeters) {
                    val decay = (1.0f - ageMs / 6000f).coerceIn(0.10f, 0.85f)
                    val normD = (blip.distanceMeters / maxScaleMeters).coerceIn(0.08f, 1.0f)
                    val rad = Math.toRadians((blip.angleDegrees - 90).toDouble())
                    val bx = center.x + (maxRadius * normD * cos(rad)).toFloat()
                    val by = center.y + (maxRadius * normD * sin(rad)).toFloat()
                    drawCircle(
                        color = blip.classification.color.copy(alpha = decay * 0.45f),
                        radius = 7.dp.toPx(),
                        center = Offset(bx, by)
                    )
                    drawCircle(
                        color = blip.classification.color.copy(alpha = decay),
                        radius = 3.dp.toPx(),
                        center = Offset(bx, by)
                    )
                }
            }
        }

        // 6. Active locked target & wireframe silhouette
        if (isActive && currentTarget != null && currentTarget.distanceMeters <= maxScaleMeters) {
            val normDist = (currentTarget.distanceMeters / maxScaleMeters).coerceIn(0.08f, 1.0f)
            val targetRad = Math.toRadians((currentTarget.angleDegrees - 90).toDouble())
            val targetX = center.x + (maxRadius * normDist * cos(targetRad)).toFloat()
            val targetY = center.y + (maxRadius * normDist * sin(targetRad)).toFloat()
            val tType = currentTarget.classification

            drawCircle(
                color = tType.color.copy(alpha = 0.24f),
                radius = 22.dp.toPx(),
                center = Offset(targetX, targetY)
            )
            drawCircle(
                color = tType.color,
                radius = 5.5.dp.toPx(),
                center = Offset(targetX, targetY)
            )

            when (tType) {
                TargetClassification.PAREDE -> {
                    drawLine(
                        color = Color(0xFF00E676),
                        start = Offset(targetX - 24.dp.toPx(), targetY),
                        end = Offset(targetX + 24.dp.toPx(), targetY),
                        strokeWidth = 3.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                }
                TargetClassification.PORTA -> {
                    drawLine(
                        color = Color(0xFF64B5F6),
                        start = Offset(targetX - 18.dp.toPx(), targetY - 9.dp.toPx()),
                        end = Offset(targetX - 18.dp.toPx(), targetY + 9.dp.toPx()),
                        strokeWidth = 2.dp.toPx()
                    )
                    drawLine(
                        color = Color(0xFF64B5F6),
                        start = Offset(targetX + 18.dp.toPx(), targetY - 9.dp.toPx()),
                        end = Offset(targetX + 18.dp.toPx(), targetY + 9.dp.toPx()),
                        strokeWidth = 2.dp.toPx()
                    )
                }
                TargetClassification.HUMANO -> {
                    drawCircle(
                        color = Color(0xFFFF5252),
                        radius = 5.dp.toPx(),
                        center = Offset(targetX, targetY - 7.dp.toPx()),
                        style = Stroke(1.5.dp.toPx())
                    )
                    drawArc(
                        color = Color(0xFFFF5252),
                        startAngle = 0f,
                        sweepAngle = 180f,
                        useCenter = false,
                        topLeft = Offset(targetX - 10.dp.toPx(), targetY),
                        size = Size(20.dp.toPx(), 12.dp.toPx()),
                        style = Stroke(1.5.dp.toPx())
                    )
                }
                else -> {}
            }
        }

        // 7. Device origin emitter triangle
        drawCircle(color = Color(0xFF00E676), radius = 4.dp.toPx(), center = center)
        val pointerPath = Path().apply {
            moveTo(center.x, center.y - 11.dp.toPx())
            lineTo(center.x - 6.dp.toPx(), center.y + 4.dp.toPx())
            lineTo(center.x + 6.dp.toPx(), center.y + 4.dp.toPx())
            close()
        }
        drawPath(path = pointerPath, color = Color(0xFF00E676))
    }
}
