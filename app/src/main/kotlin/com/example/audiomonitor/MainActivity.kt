package com.example.audiomonitor

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.audiomonitor.sonar.SonarEngine
import com.example.audiomonitor.ui.help.HelpScreen
import com.example.audiomonitor.ui.sonar.SonarScreen
import com.example.audiomonitor.ui.theme.AudioMonitorTheme

class MainActivity : ComponentActivity() {

    private lateinit var sonarEngine: SonarEngine

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        // Runtime permissions handled gracefully inside SonarEngine & RfContextProvider
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sonarEngine = SonarEngine.getInstance(this)

        checkAndRequestPermissions()
        enableEdgeToEdge()

        setContent {
            AudioMonitorTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    EchoSonarApp(sonarEngine = sonarEngine)
                }
            }
        }
    }

    override fun onDestroy() {
        sonarEngine.stop()
        super.onDestroy()
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        val needed = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }
}

@Composable
fun EchoSonarApp(sonarEngine: SonarEngine) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val language by sonarEngine.languageFlow.collectAsState()
    val isEn = language != "PT"

    val isSonarActive by sonarEngine.isActive.collectAsState()
    val sonarTarget by sonarEngine.targetFlow.collectAsState()
    val radarBlips by sonarEngine.radarBlipsFlow.collectAsState()
    val echoProfile by sonarEngine.echoProfileFlow.collectAsState()
    val sonarPose by sonarEngine.poseFlow.collectAsState()
    val sonarRf by sonarEngine.rfFlow.collectAsState()
    val cadenceMs by sonarEngine.cadenceMsFlow.collectAsState()
    val snrThreshold by sonarEngine.sensitivityFlow.collectAsState()
    val audioEnabled by sonarEngine.audioFeedbackEnabled.collectAsState()
    val errorMsg by sonarEngine.errorFlow.collectAsState()

    Scaffold(
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0A1410))
                    .statusBarsPadding()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "EchoSonar",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = Color(0xFF0D2E22),
                            border = BorderStroke(0.5.dp, Color(0xFF00E676))
                        ) {
                            Text(
                                text = "v0.1.0-alpha",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF69F0AE),
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                    Text(
                        text = if (isEn) {
                            "Ultrasonic Active Radar • FMCW 18.5–20.5 kHz"
                        } else {
                            "Radar Ultrassônico Ativo • FMCW 18.5–20.5 kHz"
                        },
                        fontSize = 11.sp,
                        color = Color(0xFF90A4AE)
                    )
                }

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Color(0xFF12232E),
                    border = BorderStroke(1.dp, Color(0xFF00E5FF)),
                    modifier = Modifier.clickable { sonarEngine.toggleLanguage() }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Language,
                            contentDescription = "Language",
                            tint = Color(0xFF00E5FF),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = if (isEn) "EN → PT" else "PT → EN",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
            }
        },
        bottomBar = {
            NavigationBar(containerColor = Color(0xFF121815)) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.Sensors, contentDescription = "Sonar") },
                    label = { Text(if (isEn) "Radar" else "Sonar") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.AutoMirrored.Filled.HelpOutline, contentDescription = "Guide") },
                    label = { Text(if (isEn) "Guide" else "Manual") }
                )
            }
        }
    ) { paddingValues ->
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            color = Color(0xFF0A0F0D)
        ) {
            when (selectedTab) {
                0 -> SonarScreen(
                    language = language,
                    isActive = isSonarActive,
                    onToggleActive = {
                        if (isSonarActive) sonarEngine.stop() else sonarEngine.start()
                    },
                    currentTarget = sonarTarget,
                    radarBlips = radarBlips,
                    echoProfile = echoProfile,
                    pose = sonarPose,
                    rfSnapshot = sonarRf,
                    cadenceMs = cadenceMs,
                    onCadenceChange = { sonarEngine.setSweepCadenceMs(it) },
                    snrThreshold = snrThreshold,
                    onSensitivityChange = { sonarEngine.setSensitivity(it) },
                    audioFeedbackEnabled = audioEnabled,
                    onToggleAudioFeedback = { sonarEngine.setAudioFeedbackEnabled(!audioEnabled) },
                    onRecenterAzimuth = { sonarEngine.recenterAzimuth() },
                    errorMessage = errorMsg
                )
                else -> HelpScreen(language = language)
            }
        }
    }
}
