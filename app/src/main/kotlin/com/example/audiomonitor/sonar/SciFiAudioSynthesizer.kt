package com.example.audiomonitor.sonar

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tanh

/** Timbre do bipe. */
enum class SciFiObjectType { GENERICO, HUMANO, PAREDE, PORTA, PET }

/**
 * Curvas de modulação 100% contínuas (sem degraus) em função da distância.
 * alpha = ((d - dMin)/(dMax - dMin))^gamma  em [0,1];  0 = colado, 1 = longe.
 */
object SciFiCurves {
    const val D_MIN = 0.10
    const val D_MAX = 2.50
    const val GAMMA = 1.2

    const val INTERVAL_FAR_MS = 1600.0
    const val INTERVAL_NEAR_MS = 60.0
    const val F0_FAR_HZ = 440.0
    const val F0_NEAR_HZ = 1350.0
    const val VOL_FAR = 0.15
    const val VOL_NEAR = 1.00
    const val BEEP_FAR_MS = 60.0
    const val BEEP_NEAR_MS = 25.0

    fun alpha(distanceMeters: Double): Double {
        val x = ((distanceMeters - D_MIN) / (D_MAX - D_MIN)).coerceIn(0.0, 1.0)
        return x.pow(GAMMA)
    }

    fun intervalMs(alpha: Double) = INTERVAL_NEAR_MS + (INTERVAL_FAR_MS - INTERVAL_NEAR_MS) * alpha
    fun f0Hz(alpha: Double) = F0_NEAR_HZ - (F0_NEAR_HZ - F0_FAR_HZ) * alpha
    fun volume(alpha: Double) = VOL_NEAR - (VOL_NEAR - VOL_FAR) * alpha
    fun beepMs(alpha: Double) = BEEP_NEAR_MS + (BEEP_FAR_MS - BEEP_NEAR_MS) * alpha
}

/**
 * Gerador de sinal puro (sem Android) — renderiza blocos PCM 16-bit mono.
 * Todos os setters são thread-safe (campos @Volatile); [render] deve ser chamado de uma única thread.
 *
 * Contínuo: a distância alvo é suavizada (one-pole, ~[smoothingMs]) por amostra e um acumulador de fase de
 * ciclo (cadência) integra 1/intervalo, de modo que o intervalo, tom e volume variam suavemente
 * mesmo no meio de um intervalo entre bipes.
 */
class SciFiSignalGenerator(private val sampleRate: Int = 48000) {

    // ------------------------------------------------------------ controle (thread-safe)
    @Volatile private var targetActive = false
    @Volatile private var targetDistance = SciFiCurves.D_MAX
    @Volatile private var targetType = SciFiObjectType.GENERICO

    @Volatile var masterVolume: Float = 1.0f
    @Volatile var smoothingMs: Float = 45f

    // telemetria para a UI
    @Volatile var currentIntervalMs: Float = 0f
        private set
    @Volatile var currentF0Hz: Float = 0f
        private set
    @Volatile var currentVolume: Float = 0f
        private set
    @Volatile var currentDistance: Float = 0f
        private set

    fun setTarget(distanceMeters: Float, type: SciFiObjectType) {
        if (distanceMeters.isNaN() || distanceMeters < 0f) { clearTarget(); return }
        targetDistance = distanceMeters.toDouble()
        targetType = type
        targetActive = true
    }

    fun clearTarget() { targetActive = false }

    // ------------------------------------------------------------ estado do renderizador
    private class Voice {
        var active = false
        var type = SciFiObjectType.GENERICO
        var age = 0            // amostras desde o início
        var len = 0            // duração total (amostras)
        var dur = 0.0          // duração nominal do bipe (s)
        var f0 = 440.0
        var vol = 1.0
        var phase = 0.0        // fase do oscilador principal (rad)
        var phase2 = 0.0
        var phase3 = 0.0
    }

    private val voices = Array(8) { Voice() }
    private var smoothDist = SciFiCurves.D_MAX
    private var wasActive = false
    private var cyclePhase = 1.0
    private var cycleInc = 0.0
    private var ctrlCounter = 0
    private var clock = 0L                     // relógio global (amostras) p/ vibrato
    private val twoPi = 2.0 * PI
    private val reverb = Reverb(sampleRate)

    private var curAlpha = 1.0

    private fun updateControl(dist: Double) {
        val a = SciFiCurves.alpha(dist)
        curAlpha = a
        val interval = SciFiCurves.intervalMs(a)
        cycleInc = 1000.0 / (interval * sampleRate)
        currentIntervalMs = interval.toFloat()
        currentF0Hz = SciFiCurves.f0Hz(a).toFloat()
        currentVolume = SciFiCurves.volume(a).toFloat()
        currentDistance = dist.toFloat()
    }

    private fun trigger(type: SciFiObjectType) {
        var v = voices.firstOrNull { !it.active }
        if (v == null) v = voices.maxByOrNull { it.age }!!   // rouba a mais antiga
        val a = curAlpha
        val dur = SciFiCurves.beepMs(a) / 1000.0
        var f0 = SciFiCurves.f0Hz(a)
        val intervalS = SciFiCurves.intervalMs(a) / 1000.0
        val lenS: Double
        when (type) {
            SciFiObjectType.GENERICO -> lenS = dur
            SciFiObjectType.HUMANO -> lenS = min(dur * 2.0, max(dur, intervalS * 0.9))
            SciFiObjectType.PAREDE -> lenS = 0.15
            SciFiObjectType.PORTA -> { f0 *= 0.85; lenS = dur * 2.5 }
            SciFiObjectType.PET -> lenS = max(0.040, dur * 1.4)
        }
        v.active = true
        v.type = type
        v.age = 0
        v.len = max(8, (lenS * sampleRate).toInt())
        v.dur = dur
        v.f0 = f0
        v.vol = SciFiCurves.volume(a)
        v.phase = 0.0
        v.phase2 = 0.0
        v.phase3 = 0.0
    }

    /** Envelope genérico: ataque linear [att], decaimento exponencial [tau], fade final de 3 ms. */
    private fun env(t: Double, att: Double, tau: Double, len: Double): Double {
        val a = if (t < att) t / att else 1.0
        val d = exp(-t / tau)
        val f = min(1.0, max(0.0, (len - t) / 0.003))
        return a * d * f
    }

    /** Renderiza uma amostra de uma voz. Retorna valor; [toReverb] indica o barramento. */
    private fun renderVoice(v: Voice): Double {
        val t = v.age.toDouble() / sampleRate
        val lenS = v.len.toDouble() / sampleRate
        val dt = twoPi / sampleRate
        val s: Double
        when (v.type) {
            SciFiObjectType.GENERICO -> {
                v.phase += dt * v.f0
                s = sin(v.phase) * env(t, 0.002, v.dur * 0.8, lenS)
            }
            SciFiObjectType.HUMANO -> {
                v.phase += dt * v.f0
                val vib = 0.15 * sin(twoPi * 6.0 * (clock.toDouble() / sampleRate))
                val tone = (sin(v.phase + vib) + 0.35 * sin(2.0 * v.phase)) / 1.35
                s = tone * env(t, 0.006, v.dur * 1.2, lenS)
            }
            SciFiObjectType.PAREDE -> {
                v.phase += dt * v.f0
                v.phase2 += dt * v.f0 * 2.76
                v.phase3 += dt * v.f0 * 5.40
                val tone = sin(v.phase) * exp(-t / 0.03) +
                    0.35 * sin(v.phase2) * exp(-t / 0.015) +
                    0.15 * sin(v.phase3) * exp(-t / 0.008)
                val att = if (t < 0.0004) t / 0.0004 else 1.0
                val fade = min(1.0, max(0.0, (lenS - t) / 0.01))
                s = tone / 1.3 * att * fade
            }
            SciFiObjectType.PORTA -> {
                val droop = 1.0 - 0.08 * min(t / 0.1, 1.0)
                v.phase += dt * v.f0 * droop
                val tone = (sin(v.phase) + 0.30 * sin(3.0 * v.phase) + 0.12 * sin(5.0 * v.phase)) / 1.3
                s = tone * env(t, 0.006, v.dur * 1.2, lenS)
            }
            SciFiObjectType.PET -> {
                val nl = lenS * 0.5
                val first = t < nl
                val tn = if (first) t else t - nl
                val glide = 1.0 + 0.12 * (tn / nl)
                val f = if (first) v.f0 * glide else v.f0 * 1.3 * glide
                v.phase += dt * f
                val e = min(1.0, tn / 0.001) * exp(-tn / (nl * 0.6)) *
                    min(1.0, max(0.0, (lenS - t) / 0.003))
                s = sin(v.phase) * e
            }
        }
        v.age++
        if (v.age >= v.len) v.active = false
        return s * v.vol
    }

    /** Preenche [out] com [count] amostras PCM16 mono. */
    fun render(out: ShortArray, count: Int = out.size) {
        val coef = 1.0 - exp(-1.0 / (max(1f, smoothingMs) * 1e-3 * sampleRate))
        val master = masterVolume.coerceIn(0f, 1f).toDouble()
        for (i in 0 until count) {
            val active = targetActive
            if (active) {
                val tgt = targetDistance
                if (!wasActive) {            // primeira detecção: salta e dispara um bipe imediatamente
                    smoothDist = tgt
                    cyclePhase = 1.0
                    updateControl(smoothDist)
                } else {
                    smoothDist += (tgt - smoothDist) * coef
                }
            }
            wasActive = active

            if ((ctrlCounter++ and 15) == 0 && active) updateControl(smoothDist)

            if (active) {
                cyclePhase += cycleInc
                if (cyclePhase >= 1.0) {
                    cyclePhase -= 1.0
                    if (cyclePhase > 1.0) cyclePhase = 0.0
                    trigger(targetType)
                }
            }

            var dry = 0.0
            var bus = 0.0
            for (v in voices) {
                if (!v.active) continue
                val x = renderVoice(v)
                if (v.type == SciFiObjectType.PORTA) bus += x else dry += x
            }
            clock++

            var y = (dry + reverb.process(bus)) * 0.8 * master
            if (y > 0.8 || y < -0.8) y = tanh(y)         // limitador suave
            out[i] = (y * 32767.0).roundToInt().coerceIn(-32768, 32767).toShort()
        }
    }

    /** Reverb Schroeder simples (3 combs com amortecimento + 1 all-pass) para o timbre "oco" da PORTA. */
    private class Reverb(sr: Int) {
        private val combLens = intArrayOf((0.0297 * sr).toInt(), (0.0371 * sr).toInt(), (0.0411 * sr).toInt())
        private val combBuf = Array(3) { FloatArray(combLens[it]) }
        private val combIdx = IntArray(3)
        private val combLp = FloatArray(3)
        private val apLen = (0.0050 * sr).toInt()
        private val apBuf = FloatArray(apLen)
        private var apIdx = 0
        private val g = 0.66f
        private val damp = 0.30f
        private val wet = 0.45f

        fun process(x: Double): Double {
            val xin = x.toFloat()
            var sum = 0f
            for (c in 0 until 3) {
                val buf = combBuf[c]
                val y = buf[combIdx[c]]
                combLp[c] = y * (1f - damp) + combLp[c] * damp
                buf[combIdx[c]] = xin + combLp[c] * g
                combIdx[c] = (combIdx[c] + 1) % buf.size
                sum += y
            }
            sum /= 3f
            // all-pass (g=0.5)
            val d = apBuf[apIdx]
            val ap = -sum + d
            apBuf[apIdx] = sum + d * 0.5f
            apIdx = (apIdx + 1) % apLen
            return (xin * (1f - wet) + ap * wet * 2.2f).toDouble()
        }
    }
}

/**
 * Sintetizador contínuo "Alien Motion Tracker" via AudioTrack PCM 16-bit mono (48 kHz).
 *
 * Uso:
 *   val synth = SciFiAudioSynthesizer()
 *   synth.start()
 *   synth.updateTarget(1.28f, SciFiObjectType.HUMANO)   // a cada medição do sonar
 *   synth.clearTarget()                                  // sem alvo -> silêncio
 *   synth.release()
 *
 * Intervalo 1600→60 ms, tom 440→1350 Hz, volume 15→100 % (d = 2.5→0.1 m), contínuos.
 * Todo o conteúdo espectral fica < 12 kHz, bem abaixo da banda do sonar (18–21 kHz, rejeitada pelo filtro).
 */
class SciFiAudioSynthesizer(private val sampleRate: Int = 48000) {

    private val generator = SciFiSignalGenerator(sampleRate)
    private var track: AudioTrack? = null
    private var thread: Thread? = null
    @Volatile private var running = false

    val isRunning: Boolean get() = running

    var masterVolume: Float
        get() = generator.masterVolume
        set(value) { generator.masterVolume = value.coerceIn(0f, 1f) }

    /** Telemetria atual (para exibir "Cadência: 124 ms" etc.). */
    val currentIntervalMs: Float get() = generator.currentIntervalMs
    val currentF0Hz: Float get() = generator.currentF0Hz
    val currentVolume: Float get() = generator.currentVolume

    /** Atualize a cada medição; pode ser chamado de qualquer thread, em qualquer taxa. */
    fun updateTarget(distanceMeters: Float, type: SciFiObjectType) = generator.setTarget(distanceMeters, type)

    /** Mapeia o resultado do DSP: HUMANO->HUMANO, RIGIDO->PAREDE, MACIO->GENERICO, VAZIO->silêncio. */
    fun updateFromSonar(result: SonarResult, overrideType: SciFiObjectType? = null) {
        if (!result.hasTarget) { generator.clearTarget(); return }
        val type = overrideType ?: when (result.material) {
            SonarMaterial.HUMANO -> SciFiObjectType.HUMANO
            SonarMaterial.RIGIDO -> SciFiObjectType.PAREDE
            SonarMaterial.MACIO -> SciFiObjectType.HUMANO
            SonarMaterial.VAZIO -> SciFiObjectType.GENERICO
        }
        generator.setTarget(result.distanceMeters, type)
    }

    fun clearTarget() = generator.clearTarget()

    @Synchronized
    fun start() {
        if (running) return
        val block = 256
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufBytes = max(minBuf, block * 2 * 4)
        val at = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
        track = at
        running = true
        at.play()
        thread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val buf = ShortArray(block)
            while (running) {
                generator.render(buf, block)
                val w = at.write(buf, 0, block)   // bloqueante
                if (w < 0) break
            }
        }, "SciFiAudioSynth").also { it.start() }
    }

    @Synchronized
    fun stop() {
        running = false
        thread?.let { try { it.join(500) } catch (_: InterruptedException) {} }
        thread = null
        track?.let {
            try { it.stop() } catch (_: IllegalStateException) {}
            it.release()
        }
        track = null
    }

    fun release() = stop()
}

