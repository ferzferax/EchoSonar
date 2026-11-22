package com.example.audiomonitor.sonar

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

data class SonarPose(
    val pitchDeg: Float = 0f,
    val rollDeg: Float = 0f,
    val azimuthDeg: Float = 0f,
    val isAligned: Boolean = true,
    val guidancePrompt: String = "READY"
)

class OrientationTracker(context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val rotationSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        ?: sensorManager?.getDefaultSensor(Sensor.TYPE_GRAVITY)

    @Volatile
    var language: String = "EN"
        set(value) {
            field = if (value == "PT") "PT" else "EN"
            updatePose(lastElevation, lastRoll, lastAzimuth)
        }

    private var lastElevation = 45f
    private var lastRoll = 0f
    private var lastAzimuth = 0f

    private val _pose = MutableStateFlow(SonarPose())
    val pose: StateFlow<SonarPose> = _pose.asStateFlow()

    private val rotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)

    fun start() {
        rotationSensor?.let {
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    fun stop() {
        sensorManager?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return

        if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
            SensorManager.getOrientation(rotationMatrix, orientationAngles)
            val azimuth = Math.toDegrees(orientationAngles[0].toDouble()).toFloat()
            val rawPitch = Math.toDegrees(orientationAngles[1].toDouble()).toFloat()
            val elevationDeg = abs(rawPitch)
            val roll = Math.toDegrees(orientationAngles[2].toDouble()).toFloat()
            updatePose(elevationDeg, roll, azimuth)
        } else {
            val gX = event.values[0]
            val gY = event.values[1]
            val gZ = event.values[2]
            val pitch = Math.toDegrees(atan2(gY.toDouble(), sqrt((gX * gX + gZ * gZ).toDouble()))).toFloat()
            val roll = Math.toDegrees(atan2(-gX.toDouble(), gZ.toDouble())).toFloat()
            updatePose(abs(pitch), roll, lastAzimuth)
        }
    }

    private fun updatePose(elevationDeg: Float, roll: Float, azimuth: Float) {
        lastElevation = elevationDeg
        lastRoll = roll
        lastAzimuth = azimuth

        val isEn = language == "EN"
        val isRollOk = abs(roll) <= 30.0f

        val (isAligned, prompt) = when {
            !isRollOk -> {
                val msg = if (isEn) {
                    if (roll > 0) "↺ LEVEL LEFT" else "↻ LEVEL RIGHT"
                } else {
                    if (roll > 0) "↺ DESVIRE P/ ESQ" else "↻ DESVIRE P/ DIR"
                }
                Pair(false, msg)
            }
            elevationDeg in 35.0f..85.0f -> {
                val msg = if (isEn) {
                    "✓ RADAR • STANDING (${elevationDeg.toInt()}°)"
                } else {
                    "✓ MODO RADAR • EM PÉ (${elevationDeg.toInt()}°)"
                }
                Pair(true, msg)
            }
            elevationDeg in 0.0f..30.0f -> {
                val msg = if (isEn) {
                    "✓ COMPASS • FLAT (${elevationDeg.toInt()}°)"
                } else {
                    "✓ BÚSSOLA • DEITADO (${elevationDeg.toInt()}°)"
                }
                Pair(true, msg)
            }
            else -> {
                val msg = if (isEn) {
                    "✓ OPTIMAL POSE (${elevationDeg.toInt()}°)"
                } else {
                    "✓ POSIÇÃO ÓTIMA (${elevationDeg.toInt()}°)"
                }
                Pair(true, msg)
            }
        }

        _pose.value = SonarPose(
            pitchDeg = elevationDeg,
            rollDeg = roll,
            azimuthDeg = azimuth,
            isAligned = isAligned,
            guidancePrompt = prompt
        )
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
