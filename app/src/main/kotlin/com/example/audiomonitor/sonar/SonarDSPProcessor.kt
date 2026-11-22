package com.example.audiomonitor.sonar

import java.util.Arrays
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Tipo de material provável do eco. */
enum class SonarMaterial { RIGIDO, MACIO, VAZIO, HUMANO }

/**
 * Resultado de uma varredura (um pulso/quadro).
 *
 * - [distanceMeters]: distância ao alvo (m). Válida somente se [hasTarget]; senão = -1.
 * - [amplitude]: amplitude relativa 0..1 (compensada pela divergência esférica, ver
 *   [SonarDSPProcessor.amplitudeFullScale] / [SonarDSPProcessor.amplitudeReferenceDistance]).
 * - [fwhmSamples]/[fwhmMeters]: largura a meia altura do pico da envolvente de correlação.
 * - [fwhmRatio]: FWHM medido / FWHM do chirp de referência (1.0 = ponto refletor ideal).
 * - [snr]: pico / mediana da envolvente na janela de busca.
 * - [phaseDriftRad]: excursão de fase da portadora (~1 s) — indica micro-movimento (respiração).
 */
data class SonarResult(
    val distanceMeters: Float,
    val amplitude: Float,
    val fwhmSamples: Float,
    val fwhmMeters: Float,
    val fwhmRatio: Float,
    val material: SonarMaterial,
    val snr: Float,
    val phaseDriftRad: Float,
    val peakLagSamples: Float,
    /** Modo auto-sincronizado: lag (amostras, sub-amostra) do som direto na janela; -1 se não usado. */
    val directLagSamples: Float = -1f,
    /** Amplitude do som direto (escala-cheia do microfone para TX unitário). */
    val directAmplitude: Float = 0f,
    /** true se o som direto foi encontrado/validado neste pulso (referência de tempo t=0 válida). */
    val directLocked: Boolean = false,
    /** 32-bin normalized echo envelope profile from 0.2m to maxRangeMeters for A-Scan display. */
    val echoProfile: List<Float> = emptyList()
) {
    val hasTarget: Boolean get() = material != SonarMaterial.VAZIO && distanceMeters >= 0f

    companion object {
        val EMPTY = SonarResult(-1f, 0f, 0f, 0f, 0f, SonarMaterial.VAZIO, 0f, 0f, 0f)
    }
}

/** Posição do som direto (alto-falante -> microfone) encontrada na aquisição. */
data class DirectFix(val lagSamples: Double, val amplitude: Float, val snr: Float)

/**
 * Processador DSP do sonar ativo (18.5–20.5 kHz).
 *
 * Fluxo: quadro do microfone (UNPROCESSED, 48 kHz, mono) -> passa-faixa Butterworth 4ª ordem
 * (HP 4ª @18 kHz + LP 4ª @21 kHz, 4 biquads) -> remoção opcional do caminho direto ->
 * correlação cruzada I/Q com o chirp filtrado (envolvente) -> blanking do som direto ->
 * pico + interpolação parabólica sub-amostra -> distância/FWHM/material.
 *
 * CONVENÇÃO DE QUADRO: o índice 0 do quadro deve corresponder ao instante de início da emissão do chirp
 * (ou use [calibrateLatency] para medir o atraso fixo alto-falante->microfone e compensá-lo).
 *
 * Resolução: 1 amostra = 3,57 mm de distância; com interpolação parabólica ~ <1 mm de passo (a precisão
 * real é limitada por SNR/temperatura; ajuste [speedOfSound]). A resolução de separação de alvos é
 * c/(2B) ≈ 8,6 cm (B = 2 kHz), por isso o FWHM é normalizado pelo FWHM do chirp de referência.
 *
 * Não é thread-safe por padrão, mas [process] e as calibrações são @Synchronized.
 */
class SonarDSPProcessor(
    val sampleRate: Int = 48000,
    maxRangeMeters: Float = 3.0f,
    @Volatile var speedOfSound: Float = 343.0f,
    blankMs: Float = 1.8f
) {

    companion object {
        const val F_START_HZ = 18500.0
        const val F_END_HZ = 20500.0
        const val CHIRP_DURATION_S = 0.005
        const val TUKEY_R = 0.25
        const val BAND_LOW_HZ = 18000.0
        const val BAND_HIGH_HZ = 21000.0

        /** Q dos 2 biquads de um Butterworth de 4ª ordem. */
        private const val Q1 = 0.5411961
        private const val Q2 = 1.3065630

        private const val TAIL_SAMPLES = 96          // cauda do template (ringing do filtro)
        private const val PHASE_WINDOW = 24          // quadros no histórico de fase (~1 s a ~20 fps)
        private const val DIRECT_TEMPLATE_S = 0.008  // duração do template do caminho direto
    }

    // ---------------------------------------------------------------- parâmetros ajustáveis

    /** Alcance máximo (m) considerado na busca de pico. */
    @Volatile var maxRangeMeters: Float = maxRangeMeters

    /** Duração do blanking do som direto (padrão 1.8 ms ≈ 30 cm), a partir de [latencySamples]. */
    @Volatile var blankMs: Float = blankMs.coerceIn(0.5f, 3.0f)

    /** Atraso fixo (amostras) entre início do quadro e chegada do som direto. Ver [calibrateLatency]. */
    @Volatile var latencySamples: Int = 0

    /** SNR mínimo (pico/mediana) para declarar alvo no modo quadro sincronizado. */
    @Volatile var snrThreshold: Float = 5.0f

    /** SNR mínimo ajustável pela UI para declarar alvo no modo streaming contínuo. */
    @Volatile var streamingSnrThreshold: Float = 2.6f

    /** Amplitude bruta mínima do pico de eco na correlação streaming. */
    @Volatile var minEchoAmplitude: Float = 0.0035f

    /** Amplitude relativa mínima para declarar alvo. */
    @Volatile var minAmplitude: Float = 0.03f

    /** Eco (em escala-cheia do microfone, para ganho unitário de TX) que corresponde a amplitude=1 na distância de referência. */
    @Volatile var amplitudeFullScale: Float = 0.05f

    /** Distância (m) usada na compensação de divergência esférica (amplitude * (d/ref)^2). */
    @Volatile var amplitudeReferenceDistance: Float = 0.5f

    /** Limiares de classificação. */
    @Volatile var rigidAmplitude: Float = 0.65f
    @Volatile var softAmplitudeMax: Float = 0.40f
    @Volatile var rigidFwhmRatioMax: Float = 1.5f
    /** Excursão de fase (rad) em ~1 s que indica micro-movimento (1 rad ≈ 1,4 mm de deslocamento a 19,5 kHz). */
    @Volatile var humanPhaseDriftRad: Float = 0.4f
    /** Variação máxima de distância (m) na janela para ser candidato a humano parado. */
    @Volatile var humanMaxDistanceSpreadM: Float = 0.35f

    /** Se true (padrão) zera o estado do filtro a cada quadro (quadros não contíguos). */
    @Volatile var resetFilterPerFrame: Boolean = true

    // ---------------------------------------------------------------- chirp / template

    private val chirpLength: Int = (CHIRP_DURATION_S * sampleRate).roundToInt()

    /** Chirp de TX em ponto flutuante (amplitude 1.0), com janela Tukey r=0.25. */
    val chirp: FloatArray = generateChirp(quadrature = false)

    private val tplLen: Int = chirpLength + TAIL_SAMPLES
    private val tplI = FloatArray(tplLen)
    private val tplQ = FloatArray(tplLen)
    private var tplEnergy = 1.0     // sqrt(sum(I^2) * sum(Q^2))
    private var tplEi = 1.0         // sum(tplI^2)
    private var tplEq = 1.0         // sum(tplQ^2)
    private var refFwhm = 24.0      // FWHM da autocorrelação do template (amostras)
    private var phasePerLag = 0.0   // rad por amostra de lag na portadora (auto-calibrado)

    private val cascade = newCascade()

    // buffers de trabalho
    private var work = FloatArray(0)
    private var env = FloatArray(0)
    private var cI = FloatArray(0)
    private var cQ = FloatArray(0)
    private var scratch = FloatArray(0)

    // remoção do caminho direto
    private var directTemplate: FloatArray? = null

    // histórico de fase / distância
    private val thetaRing = DoubleArray(PHASE_WINDOW)
    private val distRing = DoubleArray(PHASE_WINDOW)
    private var ringCount = 0
    private var ringHead = 0
    private var lastRawTheta = 0.0
    private var lastUnwrapped = 0.0

    init {
        buildTemplate()
        measureReference()
    }

    // ---------------------------------------------------------------- geração do chirp

    /**
     * Gera o chirp FMCW linear 18.5→20.5 kHz, 5 ms, janela Tukey (r=0.25).
     * quadrature=true gera a versão defasada de 90° (cos) usada na demodulação I/Q.
     */
    fun generateChirp(quadrature: Boolean = false): FloatArray {
        val n = (CHIRP_DURATION_S * sampleRate).roundToInt()
        val k = (F_END_HZ - F_START_HZ) / CHIRP_DURATION_S
        val out = FloatArray(n)
        for (i in 0 until n) {
            val t = i.toDouble() / sampleRate
            val phase = 2.0 * PI * (F_START_HZ * t + 0.5 * k * t * t)
            val s = if (quadrature) cos(phase) else sin(phase)
            out[i] = (s * tukey(i, n, TUKEY_R)).toFloat()
        }
        return out
    }

    /** Chirp pronto para AudioTrack PCM 16-bit. [amplitude] 0..1. */
    fun chirpPcm16(amplitude: Float = 0.8f): ShortArray {
        val a = amplitude.coerceIn(0f, 1f) * 32767f
        return ShortArray(chirp.size) { (chirp[it] * a).roundToInt().coerceIn(-32768, 32767).toShort() }
    }

    private fun tukey(i: Int, n: Int, r: Double): Double {
        if (n <= 1) return 1.0
        val x = i.toDouble() / (n - 1)
        val h = r / 2.0
        return when {
            x < h -> 0.5 * (1.0 + cos(2.0 * PI / r * (x - h)))
            x <= 1.0 - h -> 1.0
            else -> 0.5 * (1.0 + cos(2.0 * PI / r * (x - 1.0 + h)))
        }
    }

    // ---------------------------------------------------------------- filtro IIR

    private class Biquad(
        val b0: Double, val b1: Double, val b2: Double,
        val a1: Double, val a2: Double
    ) {
        private var z1 = 0.0
        private var z2 = 0.0
        fun reset() { z1 = 0.0; z2 = 0.0 }
        fun process(x: Double): Double {
            val y = b0 * x + z1
            z1 = b1 * x - a1 * y + z2
            z2 = b2 * x - a2 * y
            return y
        }
    }

    private fun highpass(fc: Double, q: Double): Biquad {
        val w0 = 2.0 * PI * fc / sampleRate
        val c = cos(w0)
        val alpha = sin(w0) / (2.0 * q)
        val a0 = 1.0 + alpha
        return Biquad(
            (1.0 + c) / 2.0 / a0, -(1.0 + c) / a0, (1.0 + c) / 2.0 / a0,
            -2.0 * c / a0, (1.0 - alpha) / a0
        )
    }

    private fun lowpass(fc: Double, q: Double): Biquad {
        val w0 = 2.0 * PI * fc / sampleRate
        val c = cos(w0)
        val alpha = sin(w0) / (2.0 * q)
        val a0 = 1.0 + alpha
        return Biquad(
            (1.0 - c) / 2.0 / a0, (1.0 - c) / a0, (1.0 - c) / 2.0 / a0,
            -2.0 * c / a0, (1.0 - alpha) / a0
        )
    }

    /** Passa-faixa 18–21 kHz: Butterworth 4ª ordem HP + Butterworth 4ª ordem LP (4 biquads). */
    private fun newCascade(): Array<Biquad> = arrayOf(
        highpass(BAND_LOW_HZ, Q1), highpass(BAND_LOW_HZ, Q2),
        lowpass(BAND_HIGH_HZ, Q1), lowpass(BAND_HIGH_HZ, Q2)
    )

    private fun runCascade(c: Array<Biquad>, src: FloatArray, dst: FloatArray, n: Int) {
        for (i in 0 until n) {
            var v = src[i].toDouble()
            for (b in c) v = b.process(v)
            dst[i] = v.toFloat()
        }
    }

    // ---------------------------------------------------------------- template / referência

    /** Template = chirp (seno e cosseno) passado pelo MESMO filtro => sem viés de atraso de grupo. */
    private fun buildTemplate() {
        val sinC = FloatArray(tplLen)
        val cosC = FloatArray(tplLen)
        generateChirp(false).copyInto(sinC)
        generateChirp(true).copyInto(cosC)
        runCascade(newCascade(), sinC, tplI, tplLen)
        runCascade(newCascade(), cosC, tplQ, tplLen)
        var ei = 0.0
        var eq = 0.0
        for (i in 0 until tplLen) {
            ei += tplI[i].toDouble() * tplI[i]
            eq += tplQ[i].toDouble() * tplQ[i]
        }
        tplEnergy = sqrt(ei * eq).coerceAtLeast(1e-12)
        tplEi = ei.coerceAtLeast(1e-12)
        tplEq = eq.coerceAtLeast(1e-12)
    }

    /** Mede FWHM de referência e a inclinação de fase por lag via autocorrelação do template. */
    private fun measureReference() {
        val pad = 160
        val total = pad * 2 + tplLen
        val buf = FloatArray(total)
        tplI.copyInto(buf, pad)
        ensureBuffers(total)
        val lastLag = total - tplLen
        correlate(buf, lastLag)
        var pk = pad
        for (k in 0..lastLag) if (env[k] > env[pk]) pk = k
        refFwhm = fwhm(pk, lastLag).coerceAtLeast(2.0)
        val p0 = atan2(cQ[pk].toDouble(), cI[pk].toDouble())
        val p1 = atan2(cQ[pk + 1].toDouble(), cI[pk + 1].toDouble())
        phasePerLag = wrapPi(p1 - p0)
    }

    // ---------------------------------------------------------------- correlação

    private fun ensureBuffers(n: Int) {
        if (work.size < n) {
            work = FloatArray(n)
            env = FloatArray(n)
            cI = FloatArray(n)
            cQ = FloatArray(n)
            scratch = FloatArray(n)
        }
    }

    /** Correlação I/Q para lags 0..lastLag; preenche cI, cQ e env (normalizada: env = amplitude do eco / TX unitário). */
    private fun correlate(x: FloatArray, lastLag: Int) = correlateRange(x, 0, lastLag)

    /** Idem, mas só para lags firstLag..lastLag (demais posições de env/cI/cQ ficam obsoletas). */
    private fun correlateRange(x: FloatArray, firstLag: Int, lastLag: Int) {
        val inv = (1.0 / tplEnergy).toFloat()
        for (k in firstLag..lastLag) {
            var si = 0.0f
            var sq = 0.0f
            var m = 0
            while (m < tplLen) {
                val v = x[k + m]
                si += v * tplI[m]
                sq += v * tplQ[m]
                m++
            }
            si *= inv
            sq *= inv
            cI[k] = si
            cQ[k] = sq
            env[k] = sqrt(si * si + sq * sq)
        }
    }

    /** FWHM (amostras) do pico em env[pk], com interpolação linear nos cruzamentos da meia altura. */
    private fun fwhm(pk: Int, lastLag: Int, firstLag: Int = 0): Double {
        val half = env[pk] * 0.5f
        var l = pk
        while (l > firstLag && env[l] > half) l--
        val left = if (env[l] <= half && l < pk) {
            l + (half - env[l]) / max(env[l + 1] - env[l], 1e-20f).toDouble()
        } else l.toDouble()
        var r = pk
        while (r < lastLag && env[r] > half) r++
        val right = if (env[r] <= half && r > pk) {
            r - (half - env[r]) / max(env[r - 1] - env[r], 1e-20f).toDouble()
        } else r.toDouble()
        return (right - left).coerceAtLeast(0.0)
    }

    private fun wrapPi(a: Double): Double {
        var x = a
        while (x > PI) x -= 2.0 * PI
        while (x < -PI) x += 2.0 * PI
        return x
    }

    // ---------------------------------------------------------------- calibrações

    /**
     * Mede o atraso fixo do caminho direto (alto-falante->microfone + latência de áudio) em um quadro
     * capturado sem alvos próximos. Ajusta [latencySamples] e o retorna.
     * [searchSamples]: janela de busca a partir do início do quadro (padrão ~8 ms).
     */
    @Synchronized
    fun calibrateLatency(frame: FloatArray, length: Int = frame.size, searchSamples: Int = (0.008 * sampleRate).toInt()): Int {
        val n = min(length, frame.size)
        if (n < tplLen + 8) return latencySamples
        ensureBuffers(n)
        filterFrame(frame, n)
        val lastLag = min(n - tplLen, searchSamples)
        correlate(work, lastLag)
        var pk = 0
        for (k in 0..lastLag) if (env[k] > env[pk]) pk = k
        latencySamples = pk
        return pk
    }

    /**
     * Captura o caminho direto (alto-falante->microfone) filtrado dos primeiros ~8 ms de um quadro "limpo"
     * (sem alvos em < 1.4 m). Os quadros seguintes têm esse template subtraído (cancelamento do som direto).
     * Chame [clearDirectPath] para desativar.
     */
    @Synchronized
    fun calibrateDirectPath(frame: FloatArray, length: Int = frame.size) {
        val n = min(length, frame.size)
        val len = min(n, (DIRECT_TEMPLATE_S * sampleRate).toInt())
        if (len < tplLen) return
        ensureBuffers(n)
        filterFrame(frame, n)
        directTemplate = work.copyOf(len)
    }

    @Synchronized
    fun clearDirectPath() { directTemplate = null }

    @Synchronized
    fun reset() {
        ringCount = 0
        ringHead = 0
        for (b in cascade) b.reset()
    }

    // ---------------------------------------------------------------- processamento

    fun process(frame: ShortArray, length: Int = frame.size): SonarResult {
        val n = min(length, frame.size)
        val f = FloatArray(n)
        for (i in 0 until n) f[i] = frame[i] / 32768f
        return process(f, n)
    }

    /** Processa um quadro (PCM float -1..1) iniciado no instante da emissão do chirp. */
    @Synchronized
    fun process(frame: FloatArray, length: Int = frame.size): SonarResult {
        val n = min(length, frame.size)
        val blankSamples = max(1, (blankMs * 1e-3f * sampleRate).roundToInt())
        val latency = latencySamples.coerceAtLeast(0)
        val searchStart = max(1, latency + blankSamples)
        val rangeLag = ceil(2.0 * maxRangeMeters / speedOfSound * sampleRate).toInt() + latency
        val lastLag = min(n - tplLen, rangeLag)
        if (lastLag - searchStart < 6) return SonarResult.EMPTY

        ensureBuffers(n)
        filterFrame(frame, n)
        directTemplate?.let { d ->
            val m = min(d.size, n)
            for (i in 0 until m) work[i] -= d[i]
        }
        correlate(work, lastLag)

        // pico máximo (máximo local) após o blanking
        var pk = -1
        var pv = 0f
        for (k in searchStart until lastLag) {
            val v = env[k]
            if (v >= env[k - 1] && v > env[k + 1] && v > pv) {
                pv = v
                pk = k
            }
        }
        if (pk < 0) {
            clearHistory()
            return SonarResult.EMPTY
        }

        // SNR contra a mediana da envolvente
        val cnt = lastLag - searchStart + 1
        System.arraycopy(env, searchStart, scratch, 0, cnt)
        Arrays.sort(scratch, 0, cnt)
        val median = scratch[cnt / 2]
        val snr = pv / (median + 1e-12f)

        // interpolação parabólica sub-amostra (3 pontos)
        val y0 = env[pk - 1].toDouble()
        val y1 = env[pk].toDouble()
        val y2 = env[pk + 1].toDouble()
        val denom = y0 - 2.0 * y1 + y2
        val delta = if (denom < -1e-20) (0.5 * (y0 - y2) / denom).coerceIn(-0.5, 0.5) else 0.0
        val lag = pk + delta
        val peakAmp = if (denom < -1e-20) (y1 - 0.25 * (y0 - y2) * delta) else y1

        val distance = (speedOfSound.toDouble() * (lag - latency).coerceAtLeast(0.0) / sampleRate / 2.0)
        val fw = fwhm(pk, lastLag)
        val fwRatio = (fw / refFwhm)
        val fwMeters = fw * speedOfSound / (2.0 * sampleRate)

        val ref = amplitudeReferenceDistance.toDouble()
        val dComp = max(distance, 0.1) / ref
        val ampRel = (peakAmp * dComp * dComp / amplitudeFullScale).coerceIn(0.0, 1.0)

        val detected = snr >= snrThreshold && ampRel >= minAmplitude
        if (!detected) {
            clearHistory()
            return SonarResult(-1f, ampRel.toFloat(), fw.toFloat(), fwMeters.toFloat(), fwRatio.toFloat(),
                SonarMaterial.VAZIO, snr, 0f, lag.toFloat())
        }

        // fase da portadora (independente do índice inteiro do pico) -> micro-movimento
        val theta = atan2(cQ[pk].toDouble(), cI[pk].toDouble()) + phasePerLag * delta
        val drift = updateHistory(theta, distance)
        val spread = distanceSpread()

        val material = classify(ampRel.toFloat(), fwRatio.toFloat(), drift.toFloat(), spread.toFloat())

        return SonarResult(
            distance.toFloat(), ampRel.toFloat(), fw.toFloat(), fwMeters.toFloat(), fwRatio.toFloat(),
            material, snr, drift.toFloat(), lag.toFloat()
        )
    }

    /**
     * Processa uma janela deslizante contínua (ex: 160ms = 7680 amostras) para detectar
     * o Som Direto (TX) e o Eco Refletido (RX) com auto-sincronismo 100% imune à latência do Android.
     */
    @Synchronized
    fun processStreamingWindow(window: FloatArray, length: Int = window.size, periodSamples: Int = 3840): SonarResult {
        val n = min(length, window.size)
        if (n < periodSamples + tplLen + 50) return SonarResult.EMPTY

        ensureBuffers(n)
        filterFrame(window, n)

        // 1. Encontrar o Som Direto (pico de maior energia emitido pelo próprio falante)
        val directSearchLimit = min(periodSamples, n - tplLen)
        correlateRange(work, 0, directSearchLimit)

        var pkDirect = -1
        var pvDirect = 0.02f // limiar mínimo para som direto
        for (k in 1 until directSearchLimit - 1) {
            val v = env[k]
            if (v > pvDirect && v >= env[k - 1] && v > env[k + 1]) {
                pvDirect = v
                pkDirect = k
            }
        }

        if (pkDirect < 0) {
            return SonarResult.EMPTY
        }

        // 2. Janela de busca do eco após o som direto (blanking mínimo 84 amostras ≈ 30 cm)
        val blankSamples = max(84, (blankMs * 1e-3f * sampleRate).roundToInt())
        val maxRangeSamples = ceil(2.0 * maxRangeMeters / speedOfSound * sampleRate).toInt()
        val searchStart = pkDirect + blankSamples
        val searchEnd = min(pkDirect + maxRangeSamples, n - tplLen - 1)

        if (searchEnd - searchStart < 8) return SonarResult.EMPTY

        // 3. Correlacionar na faixa do eco
        correlateRange(work, searchStart, searchEnd)

        var pkEcho = -1
        var pvEcho = 0.0f
        for (k in searchStart until searchEnd) {
            val v = env[k]
            if (v > pvEcho && v >= env[k - 1] && v > env[k + 1]) {
                pvEcho = v
                pkEcho = k
            }
        }

        // 4. SNR contra a mediana do ruído de fundo na janela de busca
        val cnt = searchEnd - searchStart + 1
        System.arraycopy(env, searchStart, scratch, 0, cnt)
        Arrays.sort(scratch, 0, cnt)
        val median = scratch[cnt / 2]
        val snr = if (pkEcho >= 0) pvEcho / (median + 1e-12f) else 0f

        // 5. Extrair perfil A-Scan de 32 bandas (0.20 m .. maxRangeMeters)
        val directRef = if (pkDirect > 0 && pkDirect + 1 < env.size) {
            val d0 = env[pkDirect - 1].toDouble()
            val d1 = env[pkDirect].toDouble()
            val d2 = env[pkDirect + 1].toDouble()
            val denomD = d0 - 2.0 * d1 + d2
            val deltaDirect = if (denomD < -1e-20) (0.5 * (d0 - d2) / denomD).coerceIn(-0.5, 0.5) else 0.0
            pkDirect + deltaDirect
        } else pkDirect.toDouble()

        val echoProfile = buildEchoProfile(directRef, searchStart, searchEnd, median, pvEcho)

        // Limiar configurável pela sensibilidade na UI
        if (pkEcho < 0 || snr < streamingSnrThreshold || pvEcho < minEchoAmplitude) {
            if (pkEcho < 0 || snr < streamingSnrThreshold * 0.7f) clearHistory()
            return SonarResult(
                distanceMeters = -1f,
                amplitude = 0f,
                fwhmSamples = 0f,
                fwhmMeters = 0f,
                fwhmRatio = 0f,
                material = SonarMaterial.VAZIO,
                snr = snr,
                phaseDriftRad = 0f,
                peakLagSamples = 0f,
                directLagSamples = directRef.toFloat(),
                directAmplitude = pvDirect,
                directLocked = true,
                echoProfile = echoProfile
            )
        }

        // 6. Interpolação parabólica sub-amostra no pico do eco
        val directLag = directRef

        val y0 = if (pkEcho > 0) env[pkEcho - 1].toDouble() else env[pkEcho].toDouble()
        val y1 = env[pkEcho].toDouble()
        val y2 = if (pkEcho + 1 < env.size) env[pkEcho + 1].toDouble() else env[pkEcho].toDouble()
        val denomY = y0 - 2.0 * y1 + y2
        val deltaEcho = if (denomY < -1e-20) (0.5 * (y0 - y2) / denomY).coerceIn(-0.5, 0.5) else 0.0
        val echoLag = pkEcho + deltaEcho
        val peakAmp = if (denomY < -1e-20) (y1 - 0.25 * (y0 - y2) * deltaEcho) else y1

        // 7. Diferença de tempo exata (ToF) e distância física
        val delaySamples = (echoLag - directLag).coerceAtLeast(0.0)
        val distance = (speedOfSound.toDouble() * delaySamples / sampleRate / 2.0)
        val fw = fwhm(pkEcho, searchEnd, searchStart)
        val fwRatio = (fw / refFwhm).toFloat()
        val fwMeters = (fw * speedOfSound / (2.0 * sampleRate)).toFloat()

        val ref = amplitudeReferenceDistance.toDouble()
        val dComp = max(distance, 0.1) / ref
        val ampRel = (peakAmp * dComp * dComp / amplitudeFullScale).coerceIn(0.0, 1.0).toFloat()

        // 8. Micro-Doppler de fase
        val theta = atan2(cQ[pkEcho].toDouble(), cI[pkEcho].toDouble()) + phasePerLag * deltaEcho
        val drift = updateHistory(theta, distance)
        val spread = distanceSpread()
        val material = classify(ampRel, fwRatio, drift.toFloat(), spread.toFloat())

        return SonarResult(
            distanceMeters = distance.toFloat(),
            amplitude = ampRel,
            fwhmSamples = fw.toFloat(),
            fwhmMeters = fwMeters,
            fwhmRatio = fwRatio,
            material = material,
            snr = snr,
            phaseDriftRad = drift.toFloat(),
            peakLagSamples = delaySamples.toFloat(),
            directLagSamples = directLag.toFloat(),
            directAmplitude = pvDirect,
            directLocked = true,
            echoProfile = echoProfile
        )
    }

    private fun buildEchoProfile(
        directLag: Double,
        searchStart: Int,
        searchEnd: Int,
        median: Float,
        peakVal: Float
    ): List<Float> {
        val bins = 32
        val minD = 0.20
        val maxD = maxRangeMeters.toDouble().coerceAtLeast(0.8)
        val span = maxD - minD
        val scaleRef = maxOf(median * 5.5f, peakVal * 1.05f, 0.012f)
        val profile = ArrayList<Float>(bins)

        for (b in 0 until bins) {
            val dStart = minD + b * span / bins
            val dEnd = minD + (b + 1) * span / bins
            val k0 = (directLag + (2.0 * dStart / speedOfSound) * sampleRate).roundToInt().coerceIn(searchStart, searchEnd)
            val k1 = (directLag + (2.0 * dEnd / speedOfSound) * sampleRate).roundToInt().coerceIn(k0, searchEnd)
            var localMax = 0f
            for (k in k0..k1) {
                if (env[k] > localMax) localMax = env[k]
            }
            profile.add((localMax / scaleRef).coerceIn(0.02f, 1.0f))
        }
        return profile
    }

    private fun classify(amp: Float, fwRatio: Float, drift: Float, spread: Float): SonarMaterial {
        // Presença viva / Humano: exige micro-movimento de fase (respiração/oscilação sub-milimétrica)
        if (ringCount >= 3 && drift >= humanPhaseDriftRad && spread <= humanMaxDistanceSpreadM) {
            return SonarMaterial.HUMANO
        }
        // Superfície rígida (parede/porta fechada): eco consistente e pico estreito
        if (amp > softAmplitudeMax && fwRatio <= rigidFwhmRatioMax) {
            return SonarMaterial.RIGIDO
        }
        // Eco atenuado com pico estreito ainda pode ser parede distante; picos largos ou muito fracos são objetos macios/vãos
        return if (fwRatio <= rigidFwhmRatioMax && amp > 0.22f) SonarMaterial.RIGIDO else SonarMaterial.MACIO
    }

    private fun filterFrame(src: FloatArray, n: Int) {
        if (resetFilterPerFrame) for (b in cascade) b.reset()
        runCascade(cascade, src, work, n)
    }

    // ---------------------------------------------------------------- histórico de fase

    private fun clearHistory() {
        ringCount = 0
        ringHead = 0
    }

    private fun updateHistory(theta: Double, distance: Double): Double {
        val unwrapped = if (ringCount == 0) theta else lastUnwrapped + wrapPi(theta - lastRawTheta)
        lastRawTheta = theta
        lastUnwrapped = unwrapped
        thetaRing[ringHead] = unwrapped
        distRing[ringHead] = distance
        ringHead = (ringHead + 1) % PHASE_WINDOW
        if (ringCount < PHASE_WINDOW) ringCount++
        if (ringCount < PHASE_WINDOW / 2) return 0.0
        var mn = Double.MAX_VALUE
        var mx = -Double.MAX_VALUE
        for (i in 0 until ringCount) {
            val v = thetaRing[i]
            if (v < mn) mn = v
            if (v > mx) mx = v
        }
        return mx - mn
    }

    private fun distanceSpread(): Double {
        if (ringCount == 0) return 0.0
        var mn = Double.MAX_VALUE
        var mx = -Double.MAX_VALUE
        for (i in 0 until ringCount) {
            val v = distRing[i]
            if (v < mn) mn = v
            if (v > mx) mx = v
        }
        return abs(mx - mn)
    }
}
