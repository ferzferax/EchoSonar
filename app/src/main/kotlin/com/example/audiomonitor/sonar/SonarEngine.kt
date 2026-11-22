package com.example.audiomonitor.sonar

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import com.example.audiomonitor.ui.sonar.SonarTarget
import com.example.audiomonitor.ui.sonar.TargetClassification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class SonarEngine private constructor(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val sampleRate = 48000
    private val periodSamples = 3840 // 80 ms periodic scan base @ 48 kHz

    val dspProcessor = SonarDSPProcessor(sampleRate = sampleRate, maxRangeMeters = 3.0f, blankMs = 1.8f)
    val synthesizer = SciFiAudioSynthesizer(sampleRate = sampleRate)
    val orientationTracker = OrientationTracker(context)
    val rfProvider = RfContextProvider(context)

    private val _languageFlow = MutableStateFlow(prefs.getString(KEY_LANGUAGE, "EN") ?: "EN")
    val languageFlow: StateFlow<String> = _languageFlow.asStateFlow()

    private val _targetFlow = MutableStateFlow<SonarTarget?>(null)
    val targetFlow: StateFlow<SonarTarget?> = _targetFlow.asStateFlow()

    private val _radarBlipsFlow = MutableStateFlow<List<SonarTarget>>(emptyList())
    val radarBlipsFlow: StateFlow<List<SonarTarget>> = _radarBlipsFlow.asStateFlow()

    private val _echoProfileFlow = MutableStateFlow<List<Float>>(List(32) { 0.04f })
    val echoProfileFlow: StateFlow<List<Float>> = _echoProfileFlow.asStateFlow()

    private val _cadenceMsFlow = MutableStateFlow(80f)
    val cadenceMsFlow: StateFlow<Float> = _cadenceMsFlow.asStateFlow()

    private val _sensitivityFlow = MutableStateFlow(2.6f)
    val sensitivityFlow: StateFlow<Float> = _sensitivityFlow.asStateFlow()

    private val _audioFeedbackEnabled = MutableStateFlow(true)
    val audioFeedbackEnabled: StateFlow<Boolean> = _audioFeedbackEnabled.asStateFlow()

    private val _errorFlow = MutableStateFlow<String?>(null)
    val errorFlow: StateFlow<String?> = _errorFlow.asStateFlow()

    val poseFlow: StateFlow<SonarPose> = orientationTracker.pose
    val rfFlow: StateFlow<RfSnapshot> = rfProvider.snapshot

    private val _isActive = MutableStateFlow(false)
    val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

    private var txJob: Job? = null
    private var rxJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default)

    @Volatile
    private var referenceAzimuthDeg: Float = 0f

    @Volatile
    private var smoothedDist: Float? = null

    @Volatile
    private var lastHitTimestampMs: Long = 0L

    @Volatile
    private var consecutiveHits: Int = 0

    @Volatile
    private var lastUiEmitMs: Long = 0L

    private val radarHistory = ArrayList<SonarTarget>(48)

    // Chirp FMCW (18.5k–20.5k Hz, 5 ms = 240 samples)
    private val chirpPcm = dspProcessor.chirpPcm16(amplitude = 0.85f)

    // Periodic TX buffer: 5 ms chirp + 75 ms listening gap
    private val periodicTxBuffer = ShortArray(periodSamples).apply {
        chirpPcm.copyInto(this, destinationOffset = 0)
    }

    init {
        orientationTracker.language = _languageFlow.value
        dspProcessor.streamingSnrThreshold = _sensitivityFlow.value
    }

    fun setLanguage(lang: String) {
        val normalized = if (lang == "PT") "PT" else "EN"
        prefs.edit().putString(KEY_LANGUAGE, normalized).apply()
        _languageFlow.value = normalized
        orientationTracker.language = normalized
    }

    fun toggleLanguage() {
        setLanguage(if (_languageFlow.value == "EN") "PT" else "EN")
    }

    fun setSweepCadenceMs(cadenceMs: Float) {
        _cadenceMsFlow.value = cadenceMs.coerceIn(50f, 240f)
    }

    fun setSensitivity(snrThreshold: Float) {
        val clamped = snrThreshold.coerceIn(1.8f, 4.5f)
        _sensitivityFlow.value = clamped
        dspProcessor.streamingSnrThreshold = clamped
    }

    fun setAudioFeedbackEnabled(enabled: Boolean) {
        _audioFeedbackEnabled.value = enabled
        synthesizer.masterVolume = if (enabled) 1.0f else 0.0f
    }

    fun recenterAzimuth() {
        referenceAzimuthDeg = orientationTracker.pose.value.azimuthDeg
        synchronized(radarHistory) {
            radarHistory.clear()
        }
        _radarBlipsFlow.value = emptyList()
    }

    private fun wrapAngleDegrees(delta: Float): Float {
        var d = delta
        while (d > 180f) d -= 360f
        while (d < -180f) d += 360f
        return d
    }

    @Synchronized
    fun start() {
        if (_isActive.value) return
        _errorFlow.value = null
        _isActive.value = true

        smoothedDist = null
        lastHitTimestampMs = 0L
        consecutiveHits = 0
        lastUiEmitMs = 0L
        synchronized(radarHistory) { radarHistory.clear() }
        _radarBlipsFlow.value = emptyList()
        dspProcessor.reset()

        orientationTracker.start()
        referenceAzimuthDeg = orientationTracker.pose.value.azimuthDeg
        rfProvider.start()
        synthesizer.masterVolume = if (_audioFeedbackEnabled.value) 1.0f else 0.0f
        synthesizer.start()

        startStreamingPipeline()
    }

    @Synchronized
    fun stop() {
        _isActive.value = false
        txJob?.cancel()
        rxJob?.cancel()
        txJob = null
        rxJob = null

        orientationTracker.stop()
        rfProvider.stop()
        synthesizer.clearTarget()
        synthesizer.stop()
        _targetFlow.value = null
        _echoProfileFlow.value = List(32) { 0.04f }
        smoothedDist = null
        consecutiveHits = 0
    }

    private fun startStreamingPipeline() {
        val minBufOut = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val trackBufferSize = maxOf(minBufOut, periodSamples * 2 * 2)
        val audioTrack = createAudioTrack(sampleRate, trackBufferSize)
        if (audioTrack == null || audioTrack.state != AudioTrack.STATE_INITIALIZED) {
            Log.e("SonarEngine", "Failed to initialize ultrasonic AudioTrack")
            _errorFlow.value = if (_languageFlow.value == "EN") {
                "Failed to initialize speaker AudioTrack at 48 kHz."
            } else {
                "Falha ao inicializar AudioTrack do alto-falante a 48 kHz."
            }
            stop()
            return
        }

        val channelConfigIn = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBufIn = AudioRecord.getMinBufferSize(sampleRate, channelConfigIn, audioFormat)
        val recordBufferSize = maxOf(minBufIn, periodSamples * 2 * 4)
        val recorder = createAudioRecord(sampleRate, channelConfigIn, audioFormat, recordBufferSize)
        if (recorder == null || recorder.state != AudioRecord.STATE_INITIALIZED) {
            Log.e("SonarEngine", "Failed to initialize 48 kHz AudioRecord")
            try { audioTrack.release() } catch (_: Exception) {}
            _errorFlow.value = if (_languageFlow.value == "EN") {
                "Microphone permission missing or 48 kHz AudioRecord unavailable."
            } else {
                "Sem permissão de microfone ou AudioRecord a 48 kHz indisponível."
            }
            stop()
            return
        }

        // TX pipeline (continuous ultrasonic chirp every 80 ms)
        txJob = scope.launch(Dispatchers.IO) {
            try {
                audioTrack.play()
                Log.d("SonarEngine", "Ultrasonic TX streaming active.")
                while (isActive && _isActive.value) {
                    audioTrack.write(periodicTxBuffer, 0, periodicTxBuffer.size)
                }
            } catch (e: Exception) {
                Log.e("SonarEngine", "TX Streaming error", e)
            } finally {
                try {
                    audioTrack.stop()
                    audioTrack.release()
                } catch (_: Exception) {}
            }
        }

        // RX pipeline (48 kHz continuous capture & self-synchronized cross-correlation)
        rxJob = scope.launch(Dispatchers.Default) {
            try {
                recorder.startRecording()
                Log.d("SonarEngine", "48 kHz RX streaming active.")

                val chunk = ShortArray(periodSamples)
                val history = FloatArray(periodSamples * 2)

                while (isActive && _isActive.value) {
                    var readTotal = 0
                    while (readTotal < periodSamples && isActive && _isActive.value) {
                        val r = recorder.read(chunk, readTotal, periodSamples - readTotal)
                        if (r > 0) {
                            readTotal += r
                        } else {
                            break
                        }
                    }

                    if (readTotal < periodSamples) continue

                    System.arraycopy(history, periodSamples, history, 0, periodSamples)
                    for (i in 0 until periodSamples) {
                        history[periodSamples + i] = chunk[i] / 32768.0f
                    }

                    val result = dspProcessor.processStreamingWindow(history, history.size, periodSamples)
                    val now = System.currentTimeMillis()

                    if (result.echoProfile.isNotEmpty()) {
                        _echoProfileFlow.value = result.echoProfile
                    }

                    // Dynamic EMA smoothing factor based on cadence slider (50..240 ms)
                    val cadence = _cadenceMsFlow.value
                    val emaAlpha = (52f / cadence).coerceIn(0.22f, 0.85f)

                    if (result.hasTarget) {
                        consecutiveHits++
                        val rawDist = result.distanceMeters.coerceIn(0.20f, 3.0f)
                        val prev = smoothedDist
                        val nextDist = if (prev == null || kotlin.math.abs(rawDist - prev) > 1.2f) {
                            rawDist
                        } else {
                            emaAlpha * rawDist + (1f - emaAlpha) * prev
                        }
                        smoothedDist = nextDist
                        lastHitTimestampMs = now

                        // Require 2 consecutive hits on fresh acquisition (or immediate if continuing track)
                        if (consecutiveHits >= 2 || prev != null) {
                            val smoothedResult = result.copy(distanceMeters = nextDist)
                            synthesizer.updateFromSonar(smoothedResult)

                            val rf = rfProvider.snapshot.value
                            val classification = when {
                                result.material == SonarMaterial.HUMANO ||
                                    (result.material == SonarMaterial.MACIO && rf.humanPriorHint > 0.55f) ->
                                    TargetClassification.HUMANO
                                result.material == SonarMaterial.RIGIDO ->
                                    TargetClassification.PAREDE
                                else ->
                                    TargetClassification.PORTA
                            }

                            val pose = orientationTracker.pose.value
                            val relAngle = wrapAngleDegrees(pose.azimuthDeg - referenceAzimuthDeg)
                                .coerceIn(-50f, 50f)

                            val newTarget = SonarTarget(
                                distanceMeters = nextDist,
                                angleDegrees = relAngle,
                                confidence = result.snr,
                                classification = classification,
                                timestamp = now
                            )

                            val emitInterval = if (cadence <= 80f) 0L else (cadence - 60f).toLong()
                            if (now - lastUiEmitMs >= emitInterval) {
                                lastUiEmitMs = now
                                _targetFlow.value = newTarget
                            }

                            // Update 6-second phosphor persistence trail by quantized 6° azimuth sector
                            synchronized(radarHistory) {
                                val sector = (relAngle / 6f).toInt()
                                radarHistory.removeAll { existing ->
                                    now - existing.timestamp > BLIP_PERSISTENCE_MS ||
                                        (existing.angleDegrees / 6f).toInt() == sector
                                }
                                radarHistory.add(newTarget)
                                _radarBlipsFlow.value = radarHistory.toList()
                            }
                        }
                    } else {
                        consecutiveHits = 0
                        // Prune expired phosphor blips cleanly
                        synchronized(radarHistory) {
                            if (radarHistory.removeAll { now - it.timestamp > BLIP_PERSISTENCE_MS }) {
                                _radarBlipsFlow.value = radarHistory.toList()
                            }
                        }
                        // Hold last target for up to 1.5s to prevent single-frame blinking
                        if (now - lastHitTimestampMs > TARGET_HOLD_MS) {
                            synthesizer.clearTarget()
                            smoothedDist = null
                            _targetFlow.value = null
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("SonarEngine", "RX Streaming error", e)
            } finally {
                try {
                    recorder.stop()
                    recorder.release()
                } catch (_: Exception) {}
            }
        }
    }

    private fun createAudioTrack(sampleRate: Int, bufferSize: Int): AudioTrack? {
        val trackAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        val trackFormat = AudioFormat.Builder()
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build()

        return try {
            val track = AudioTrack.Builder()
                .setAudioAttributes(trackAttributes)
                .setAudioFormat(trackFormat)
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
            if (track.state == AudioTrack.STATE_INITIALIZED) track else {
                track.release()
                null
            }
        } catch (e: Exception) {
            Log.e("SonarEngine", "Failed to create AudioTrack", e)
            null
        }
    }

    private fun createAudioRecord(sampleRate: Int, channelConfig: Int, audioFormat: Int, bufferSize: Int): AudioRecord? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                val rec = AudioRecord(
                    MediaRecorder.AudioSource.UNPROCESSED,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    bufferSize
                )
                if (rec.state == AudioRecord.STATE_INITIALIZED) {
                    Log.d("SonarEngine", "AudioRecord UNPROCESSED initialized successfully.")
                    return rec
                }
                rec.release()
            } catch (e: Exception) {
                Log.w("SonarEngine", "UNPROCESSED failed at 48kHz, trying MIC", e)
            }
        }

        return try {
            val rec = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            )
            if (rec.state == AudioRecord.STATE_INITIALIZED) {
                Log.d("SonarEngine", "AudioRecord MIC initialized successfully.")
                rec
            } else {
                rec.release()
                null
            }
        } catch (e: Exception) {
            Log.e("SonarEngine", "Critical failure creating AudioRecord", e)
            null
        }
    }

    companion object {
        private const val PREFS_NAME = "echosonar_prefs"
        private const val KEY_LANGUAGE = "app_language"
        private const val TARGET_HOLD_MS = 1500L
        private const val BLIP_PERSISTENCE_MS = 6000L

        @Volatile
        private var INSTANCE: SonarEngine? = null

        fun getInstance(context: Context): SonarEngine {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SonarEngine(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
