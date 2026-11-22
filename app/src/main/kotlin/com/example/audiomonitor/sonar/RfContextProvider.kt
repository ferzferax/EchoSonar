package com.example.audiomonitor.sonar

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult as BleScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.LocationManager
import android.media.AudioManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.net.wifi.rtt.RangingRequest
import android.net.wifi.rtt.RangingResult
import android.net.wifi.rtt.RangingResultCallback
import android.net.wifi.rtt.WifiRttManager
import android.os.Build
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.sqrt

/** Modo operacional da camada RF. */
enum class RfMode {
    /** Sem root: BLE passivo + RSSI do AP conectado + scans em cache (+ RTT se disponível). */
    PASSIVE,
    /** Root detectado: ganchos privilegiados liberados (ex.: desativar throttle de scan Wi-Fi). */
    ROOT
}

/** Recursos detectados dinamicamente no aparelho (portabilidade S20 / S23 / outros). */
data class RfCapabilities(
    val sdkInt: Int = 0,
    val hasBle: Boolean = false,
    val hasWifi: Boolean = false,
    val hasWifiRtt: Boolean = false,
    val wifiRttAvailable: Boolean = false,
    val hasUwb: Boolean = false,
    val nativeOutputSampleRate: Int = 0,
    val supports48kHz: Boolean = false,
    val supportsUnprocessed: Boolean = false,
    val hasLowLatencyAudio: Boolean = false,
    val hasProAudio: Boolean = false,
    val micCount: Int = -1,
    val locationEnabled: Boolean = false,
    val rootAvailable: Boolean = false
)

/** Medição Wi-Fi RTT/FTM até um AP (identificado por hash, nunca pelo MAC). */
data class RttRange(
    val apKey: Int,
    val distanceMeters: Float,
    val stdDevMeters: Float,
    val rssiDbm: Int
)

/**
 * Snapshot imutável da camada RF. Campos nulos = desconhecido/indisponível.
 *
 * [humanPriorHint] (0..1) é só um prior FRACO para o classificador: nunca use como decisão dura.
 * [wifiRssiVariance] (dB²) só é preenchido com o aparelho parado ([stationary]).
 */
data class RfSnapshot(
    val timestampMs: Long = 0L,
    val mode: RfMode = RfMode.PASSIVE,
    val stationary: Boolean = false,

    val wifiConnected: Boolean = false,
    val wifiRssiDbm: Int? = null,
    val wifiLinkSpeedMbps: Int? = null,
    val wifiFrequencyMhz: Int? = null,
    val wifiRssiVariance: Float? = null,
    val wifiScanApCount: Int = 0,
    val wifiScanThrottleDisabled: Boolean = false,

    val bleScanning: Boolean = false,
    val bleCount: Int = 0,
    val bleNearCount: Int = 0,
    val bleStrongestRssi: Int? = null,

    val rttRanges: List<RttRange> = emptyList(),

    val humanPriorHint: Float = 0f,
    val capabilities: RfCapabilities = RfCapabilities(),
    val warnings: List<String> = emptyList()
) {
    companion object { val EMPTY = RfSnapshot() }
}

/**
 * Camada de contexto RF (Wi-Fi + BLE) com degradação graciosa e detecção de root em tempo de execução.
 *
 * REGRAS DE SEGURANÇA (mandatórias):
 *  - NUNCA liga, desliga, reconecta, desassocia ou alterna o Wi-Fi (nem o Bluetooth). Nenhuma chamada a
 *    `setWifiEnabled`, `disconnect`, `reassociate`, `reconnect`, `svc wifi`, `cmd wifi set-wifi-enabled`.
 *  - Scan Wi-Fi: apenas LEITURA de `connectionInfo.rssi` e dos `scanResults` em cache. `startScan()` só
 *    é chamado se o integrador ligar [activeWifiScanWithRoot] E o root tiver desativado o throttle.
 *  - Root: só roda comandos de uma allowlist fixa (`id` e os 2 comandos de throttle de scan). Qualquer falha
 *    (su ausente, negado, timeout, root removido) cai silenciosamente para o modo PASSIVE.
 *
 * Permissões que o integrador deve declarar/solicitar (manifest + runtime):
 *  - ACCESS_WIFI_STATE, ACCESS_FINE_LOCATION, NEARBY_WIFI_DEVICES (API 33+), BLUETOOTH_SCAN (API 31+),
 *    BLUETOOTH (<= API 30, maxSdkVersion=30), BLUETOOTH_ADMIN (<= API 30), CHANGE_WIFI_STATE (só para RTT/scan).
 *  - [requiredPermissions] devolve a lista correta para o SDK atual.
 *
 * Uso:
 *   val rf = RfContextProvider(applicationContext)
 *   rf.start()
 *   rf.snapshot.collect { ... }   // StateFlow<RfSnapshot>
 *   rf.stop()
 */
class RfContextProvider(
    context: Context,
    /** Tenta detectar root (`su -c id`). Na 1ª vez pode aparecer o prompt do gerenciador de root. */
    private val allowRootProbe: Boolean = true,
    /** Se true e houver root, desativa o throttle de scan Wi-Fi ao iniciar (restaurado em [stop]). */
    private val autoDisableScanThrottleWhenRoot: Boolean = false,
    /** Se true, com root + throttle desativado, chama startScan() a cada [activeScanIntervalMs]. */
    private val activeWifiScanWithRoot: Boolean = false,
    private val activeScanIntervalMs: Long = 10_000L,
    /** Tenta Wi-Fi RTT/FTM quando houver APs 802.11mc no scan em cache. */
    private val enableRtt: Boolean = true,
    private val rttIntervalMs: Long = 15_000L
) {

    companion object {
        private const val TICK_MS = 500L
        private const val BLE_TTL_MS = 8_000L
        private const val BLE_NEAR_RSSI = -60
        private const val ROOT_REPROBE_MS = 5 * 60_000L
        private const val SCAN_CACHE_READ_MS = 5_000L
        private const val BLE_RETRY_MS = 30_000L
        private const val RSSI_WINDOW = 16
        private const val MAX_WARNINGS = 6

        private const val CMD_ID = "id"
        private const val CMD_THROTTLE_OFF = "cmd wifi set-scan-throttle-enabled disabled"
        private const val CMD_THROTTLE_ON = "cmd wifi set-scan-throttle-enabled enabled"
        /** Allowlist: nenhum comando que mexa em Wi-Fi on/off ou conexão é permitido. */
        private val ROOT_ALLOWLIST = setOf(CMD_ID, CMD_THROTTLE_OFF, CMD_THROTTLE_ON)

        /** Permissões de runtime recomendadas para o SDK atual. */
        fun requiredPermissions(): Array<String> {
            val list = mutableListOf("android.permission.ACCESS_FINE_LOCATION")
            if (Build.VERSION.SDK_INT >= 31) list += "android.permission.BLUETOOTH_SCAN"
            if (Build.VERSION.SDK_INT >= 33) list += "android.permission.NEARBY_WIFI_DEVICES"
            return list.toTypedArray()
        }
    }

    private val appContext: Context = context.applicationContext

    private val wifiManager: WifiManager? =
        appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val sensorManager: SensorManager? =
        appContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val audioManager: AudioManager? =
        appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private val _snapshot = MutableStateFlow(RfSnapshot.EMPTY)
    val snapshot: StateFlow<RfSnapshot> = _snapshot.asStateFlow()

    private var scope: CoroutineScope? = null
    private var loopJob: Job? = null
    @Volatile private var running = false

    // ------------------------------------------------------------------ root
    @Volatile private var rootProbed = false
    @Volatile private var rootAvailable = false
    @Volatile private var lastRootProbeMs = 0L
    @Volatile private var throttleDisabledByUs = false

    /**
     * true se `su` responde com uid=0. Cacheado; a 1ª chamada faz a sondagem de forma síncrona
     * (até ~2,5 s) — chame fora da thread principal. Re-sondado periodicamente quando o provider roda.
     */
    fun isRootAvailable(): Boolean {
        if (!allowRootProbe) return false
        if (!rootProbed) refreshRoot()
        return rootAvailable
    }

    @Synchronized
    private fun refreshRoot() {
        lastRootProbeMs = System.currentTimeMillis()
        val ok = execRoot(CMD_ID)?.contains("uid=0") == true
        if (rootAvailable && !ok) {
            // root foi removido/revogado: não há como restaurar nada, volta ao modo sem root
            throttleDisabledByUs = false
        }
        rootAvailable = ok
        rootProbed = true
    }

    /** Executa um comando da allowlist via su, com timeout. Nunca lança; retorna null em qualquer falha. */
    private fun execRoot(cmd: String, timeoutMs: Long = 2_500L): String? {
        if (cmd !in ROOT_ALLOWLIST) return null
        var p: java.lang.Process? = null
        return try {
            p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
            if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                p.destroyForcibly()
                null
            } else if (p.exitValue() != 0) {
                null
            } else {
                p.inputStream.bufferedReader().use { it.readText() }
            }
        } catch (e: Exception) {
            try { p?.destroyForcibly() } catch (_: Exception) {}
            null
        }
    }

    /** Gancho root: desativa o throttle de scan Wi-Fi (não toca na conexão). Retorna true se aplicado. */
    fun disableWifiScanThrottle(): Boolean {
        if (!isRootAvailable()) return false
        val ok = execRoot(CMD_THROTTLE_OFF) != null
        if (ok) throttleDisabledByUs = true
        return ok
    }

    /** Restaura o throttle padrão, se (e somente se) fomos nós que o desativamos. */
    fun restoreWifiScanThrottle() {
        if (!throttleDisabledByUs) return
        if (rootAvailable) execRoot(CMD_THROTTLE_ON)
        throttleDisabledByUs = false
    }

    // ------------------------------------------------------------------ sensores (parado?)
    @Volatile private var gyroEma = 1.0f
    @Volatile private var accDevEma = 1.0f
    @Volatile private var sensorsRegistered = false

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            when (e.sensor.type) {
                Sensor.TYPE_GYROSCOPE -> {
                    val m = sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2])
                    gyroEma += 0.1f * (m - gyroEma)
                }
                Sensor.TYPE_ACCELEROMETER -> {
                    val m = sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2])
                    accDevEma += 0.1f * (abs(m - SensorManager.GRAVITY_EARTH) - accDevEma)
                }
            }
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private fun isStationary(): Boolean =
        sensorsRegistered && gyroEma < 0.06f && accDevEma < 0.12f

    private fun registerSensors() {
        val sm = sensorManager ?: return
        try {
            val gyro = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
            val acc = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            if (gyro != null && acc != null) {
                sm.registerListener(sensorListener, gyro, SensorManager.SENSOR_DELAY_UI)
                sm.registerListener(sensorListener, acc, SensorManager.SENSOR_DELAY_UI)
                sensorsRegistered = true
            }
        } catch (_: Exception) { sensorsRegistered = false }
    }

    // ------------------------------------------------------------------ BLE (passivo)
    private class BleEntry(@Volatile var lastSeenMs: Long, @Volatile var rssiEma: Float)

    /** Chave = hash do endereço, apenas em memória; nada é persistido. */
    private val bleDevices = ConcurrentHashMap<Int, BleEntry>()
    @Volatile private var bleScanning = false
    @Volatile private var lastBleAttemptMs = 0L
    private var bleScanner: android.bluetooth.le.BluetoothLeScanner? = null

    private val bleCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: BleScanResult) {
            val key = try { result.device.address.hashCode() } catch (_: Exception) { return }
            val now = System.currentTimeMillis()
            val rssi = result.rssi.toFloat()
            val e = bleDevices[key]
            if (e == null) bleDevices[key] = BleEntry(now, rssi)
            else { e.rssiEma += 0.3f * (rssi - e.rssiEma); e.lastSeenMs = now }
        }
        override fun onBatchScanResults(results: MutableList<BleScanResult>) {
            for (r in results) onScanResult(0, r)
        }
        override fun onScanFailed(errorCode: Int) {
            bleScanning = false
            bleScanner = null
        }
    }

    private fun hasPermission(p: String) =
        appContext.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun blePermissionOk(): Boolean =
        if (Build.VERSION.SDK_INT >= 31) hasPermission("android.permission.BLUETOOTH_SCAN")
        else hasPermission("android.permission.ACCESS_FINE_LOCATION")

    /** Inicia (ou tenta reiniciar) o scan BLE. Nunca liga o Bluetooth: se estiver desligado, apenas aguarda. */
    @SuppressLint("MissingPermission")
    private fun ensureBle(now: Long, warn: (String) -> Unit) {
        if (bleScanning) return
        if (now - lastBleAttemptMs < 2_000L) return
        if (!appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) return
        if (!blePermissionOk()) { warn("BLE: permissão de scan ausente"); return }
        val adapter = (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        if (adapter == null || !adapter.isEnabled) { warn("BLE: Bluetooth desligado"); return }
        lastBleAttemptMs = now
        try {
            val scanner = adapter.bluetoothLeScanner ?: return
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                .build()
            scanner.startScan(null, settings, bleCallback)
            bleScanner = scanner
            bleScanning = true
        } catch (e: Exception) {
            bleScanning = false
            warn("BLE: falha ao iniciar scan (${e.javaClass.simpleName})")
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopBle() {
        try { bleScanner?.stopScan(bleCallback) } catch (_: Exception) {}
        bleScanner = null
        bleScanning = false
        bleDevices.clear()
    }

    // ------------------------------------------------------------------ Wi-Fi (somente leitura)
    private val rssiWindow = ArrayDeque<Int>()
    private var cachedScan: List<ScanResult> = emptyList()
    private var lastScanReadMs = 0L
    private var lastActiveScanMs = 0L

    @Suppress("DEPRECATION")
    private fun readWifiInfo(): Triple<Int?, Int?, Int?> {
        val info = try { wifiManager?.connectionInfo } catch (_: Exception) { null }
            ?: return Triple(null, null, null)
        val rssi = info.rssi
        if (rssi <= -127 || rssi >= 0) return Triple(null, null, null)   // desconectado / inválido
        val speed = info.linkSpeed.takeIf { it > 0 }
        val freq = info.frequency.takeIf { it > 0 }
        return Triple(rssi, speed, freq)
    }

    @SuppressLint("MissingPermission")
    private fun readScanCache(now: Long) {
        if (now - lastScanReadMs < SCAN_CACHE_READ_MS) return
        lastScanReadMs = now
        cachedScan = try { wifiManager?.scanResults ?: emptyList() } catch (_: SecurityException) { emptyList() }
        catch (_: Exception) { emptyList() }
    }

    @Suppress("DEPRECATION")
    private fun maybeActiveScan(now: Long) {
        if (!activeWifiScanWithRoot || !rootAvailable || !throttleDisabledByUs) return
        if (now - lastActiveScanMs < activeScanIntervalMs) return
        lastActiveScanMs = now
        try { wifiManager?.startScan() } catch (_: Exception) {}
    }

    private fun updateRssiWindow(rssi: Int?, stationary: Boolean): Float? {
        if (rssi == null || !stationary) { rssiWindow.clear(); return null }
        rssiWindow.addLast(rssi)
        while (rssiWindow.size > RSSI_WINDOW) rssiWindow.removeFirst()
        if (rssiWindow.size < 8) return null
        val mean = rssiWindow.sumOf { it.toDouble() } / rssiWindow.size
        val v = rssiWindow.sumOf { (it - mean) * (it - mean) } / rssiWindow.size
        return v.toFloat()
    }

    // ------------------------------------------------------------------ Wi-Fi RTT (opcional)
    private var rttExecutor: java.util.concurrent.ExecutorService? = null
    @Volatile private var rttLatest: List<RttRange> = emptyList()
    @Volatile private var lastRttMs = 0L
    @Volatile private var rttBusy = false

    @RequiresApi(28)
    @SuppressLint("MissingPermission")
    private fun maybeRtt(now: Long, warn: (String) -> Unit) {
        if (!enableRtt || rttBusy || now - lastRttMs < rttIntervalMs) return
        val pm = appContext.packageManager
        if (!pm.hasSystemFeature("android.hardware.wifi.rtt")) return
        val rtt = appContext.getSystemService(WifiRttManager::class.java) ?: return
        if (!rtt.isAvailable) return
        val perm = if (Build.VERSION.SDK_INT >= 33) "android.permission.NEARBY_WIFI_DEVICES"
        else "android.permission.ACCESS_FINE_LOCATION"
        if (!hasPermission(perm)) { warn("RTT: permissão ausente"); return }
        val aps = cachedScan.filter { it.is80211mcResponder() }
            .sortedByDescending { it.level }
            .take(minOf(RangingRequest.getMaxPeers(), 4))
        if (aps.isEmpty()) return
        val ex = rttExecutor ?: return
        lastRttMs = now
        rttBusy = true
        try {
            val req = RangingRequest.Builder().addAccessPoints(aps).build()
            rtt.startRanging(req, ex, object : RangingResultCallback() {
                override fun onRangingFailure(code: Int) { rttBusy = false }
                override fun onRangingResults(results: List<RangingResult>) {
                    rttLatest = results.filter { it.status == RangingResult.STATUS_SUCCESS }.map {
                        RttRange(
                            apKey = (it.macAddress?.toString() ?: "").hashCode(),
                            distanceMeters = it.distanceMm / 1000f,
                            stdDevMeters = it.distanceStdDevMm / 1000f,
                            rssiDbm = it.rssi
                        )
                    }
                    rttBusy = false
                }
            })
        } catch (e: Exception) {
            rttBusy = false
            warn("RTT: ${e.javaClass.simpleName}")
        }
    }

    // ------------------------------------------------------------------ capacidades
    @SuppressLint("NewApi")
    private fun detectCapabilities(): RfCapabilities {
        val pm = appContext.packageManager
        val outRate = audioManager?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 0
        val unprocessed = audioManager?.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        val mics = try {
            if (Build.VERSION.SDK_INT >= 28) audioManager?.microphones?.size ?: -1 else -1
        } catch (_: Exception) { -1 }
        val hasRttFeature = Build.VERSION.SDK_INT >= 28 && pm.hasSystemFeature("android.hardware.wifi.rtt")
        val rttOk = hasRttFeature && try {
            appContext.getSystemService(WifiRttManager::class.java)?.isAvailable == true
        } catch (_: Exception) { false }
        val locOn = try {
            val lm = appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            if (Build.VERSION.SDK_INT >= 28) lm?.isLocationEnabled == true else true
        } catch (_: Exception) { false }
        return RfCapabilities(
            sdkInt = Build.VERSION.SDK_INT,
            hasBle = pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE),
            hasWifi = pm.hasSystemFeature(PackageManager.FEATURE_WIFI),
            hasWifiRtt = hasRttFeature,
            wifiRttAvailable = rttOk,
            hasUwb = pm.hasSystemFeature("android.hardware.uwb"),
            nativeOutputSampleRate = outRate,
            supports48kHz = outRate == 48000 || outRate == 0 /* desconhecido: 48 kHz é universal no AudioRecord */,
            supportsUnprocessed = unprocessed,
            hasLowLatencyAudio = pm.hasSystemFeature(PackageManager.FEATURE_AUDIO_LOW_LATENCY),
            hasProAudio = pm.hasSystemFeature(PackageManager.FEATURE_AUDIO_PRO),
            micCount = mics,
            locationEnabled = locOn,
            rootAvailable = rootAvailable
        )
    }

    // ------------------------------------------------------------------ ciclo de vida
    @Synchronized
    fun start() {
        if (running) return
        running = true
        rttExecutor = Executors.newSingleThreadExecutor()
        registerSensors()
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope = s
        loopJob = s.launch {
            var caps = detectCapabilities()
            var capsMs = System.currentTimeMillis()
            var firstTick = true
            while (isActive) {
                try {
                    val now = System.currentTimeMillis()
                    val warnings = LinkedHashSet<String>()
                    val warn: (String) -> Unit = { if (warnings.size < MAX_WARNINGS) warnings.add(it) }

                    // root: sonda no início e a cada 5 min; falha => PASSIVE sem crash
                    if (allowRootProbe && (firstTick || now - lastRootProbeMs > ROOT_REPROBE_MS)) {
                        withContext(Dispatchers.IO) { refreshRoot() }
                        if (rootAvailable && autoDisableScanThrottleWhenRoot && !throttleDisabledByUs) {
                            withContext(Dispatchers.IO) { disableWifiScanThrottle() }
                        }
                    }
                    if (firstTick || now - capsMs > 10_000L) {
                        caps = withContext(Dispatchers.IO) { detectCapabilities() }
                        capsMs = now
                    }
                    firstTick = false

                    ensureBle(now, warn)
                    readScanCache(now)
                    maybeActiveScan(now)
                    if (Build.VERSION.SDK_INT >= 28) maybeRtt(now, warn)

                    val stationary = isStationary()
                    val (rssi, speed, freq) = readWifiInfo()
                    if (rssi == null) warn("Wi-Fi: sem RSSI (desconectado ou sem permissão)")
                    val variance = updateRssiWindow(rssi, stationary)

                    // BLE: expira entradas antigas e agrega (sem MACs)
                    val iter = bleDevices.entries.iterator()
                    var count = 0
                    var near = 0
                    var best: Float? = null
                    while (iter.hasNext()) {
                        val e = iter.next().value
                        if (now - e.lastSeenMs > BLE_TTL_MS) { iter.remove(); continue }
                        count++
                        if (e.rssiEma >= BLE_NEAR_RSSI) near++
                        if (best == null || e.rssiEma > best) best = e.rssiEma
                    }

                    val bleProx = best?.let { ((it + 80f) / 30f).coerceIn(0f, 1f) } ?: 0f
                    val wifiMotion = variance?.let { (it / 6f).coerceIn(0f, 1f) } ?: 0f
                    val prior = (0.6f * bleProx + 0.4f * wifiMotion).coerceIn(0f, 1f)

                    val mode = if (rootAvailable) RfMode.ROOT else RfMode.PASSIVE
                    _snapshot.value = RfSnapshot(
                        timestampMs = now,
                        mode = mode,
                        stationary = stationary,
                        wifiConnected = rssi != null,
                        wifiRssiDbm = rssi,
                        wifiLinkSpeedMbps = speed,
                        wifiFrequencyMhz = freq,
                        wifiRssiVariance = variance,
                        wifiScanApCount = cachedScan.size,
                        wifiScanThrottleDisabled = throttleDisabledByUs,
                        bleScanning = bleScanning,
                        bleCount = count,
                        bleNearCount = near,
                        bleStrongestRssi = best?.toInt(),
                        rttRanges = rttLatest,
                        humanPriorHint = prior,
                        capabilities = caps.copy(rootAvailable = rootAvailable),
                        warnings = warnings.toList()
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // nunca derruba o app: mantém o último snapshot
                }
                delay(TICK_MS)
            }
        }
    }

    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        loopJob?.cancel()
        loopJob = null
        scope?.cancel()
        scope = null
        stopBle()
        try { sensorManager?.unregisterListener(sensorListener) } catch (_: Exception) {}
        sensorsRegistered = false
        // restaura o throttle apenas se nós o desativamos (em thread separada: su pode demorar)
        if (throttleDisabledByUs) {
            Thread { try { restoreWifiScanThrottle() } catch (_: Exception) {} }.start()
        }
        try { rttExecutor?.shutdown() } catch (_: Exception) {}
        rttExecutor = null
        _snapshot.value = RfSnapshot.EMPTY
    }
}
