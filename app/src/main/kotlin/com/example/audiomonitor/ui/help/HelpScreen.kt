package com.example.audiomonitor.ui.help

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun HelpScreen(language: String = "EN") {
    val isEn = language != "PT"

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text(
                text = if (isEn) "ECHOSONAR ACOUSTIC & DSP GUIDE" else "MANUAL ACÚSTICO & DSP — ECHOSONAR",
                fontWeight = FontWeight.Bold,
                fontSize = 19.sp,
                color = Color.White
            )
            Text(
                text = if (isEn) {
                    "How 18.5–20.5 kHz FMCW chirps, I/Q demodulation, micro-Doppler & RF context work together."
                } else {
                    "Entenda os chirps FMCW 18.5–20.5 kHz, demodulação I/Q, micro-Doppler e telemetria RF."
                },
                fontSize = 12.sp,
                color = Color(0xFFAAAAAA)
            )
        }

        item {
            HelpCard(
                title = if (isEn) "🎯 How to Hold Your Phone for Active Sonar" else "🎯 Como Segurar o Celular para o Sonar",
                description = if (isEn) {
                    "At 19.5 kHz (ultrasound), the acoustic wavelength is only 1.76 cm. Human skin absorbs high-frequency sound immediately!\n\n" +
                        "• PROPER GRIP: Pinch the phone gently along the middle side edges.\n" +
                        "• KEEP VENTS UNBLOCKED: Never cover the top earpiece/mic hole or the bottom speaker/USB mic ports.\n" +
                        "• BODY DISTANCE: Hold the device 30–40 cm away from your chest so your torso does not create a persistent close-range reflection."
                } else {
                    "Em 19.5 kHz (ultrassom), a onda acústica tem apenas 1,76 cm de comprimento. A pele humana absorve esse som imediatamente!\n\n" +
                        "• PEGADA CORRETA: Segure o aparelho em pinça pelas bordas laterais no terço médio.\n" +
                        "• DEIXE 100% LIVRE: O topo (furo do mic superior) e a base (alto-falante e microfone ao lado do USB) não podem ter dedos em cima.\n" +
                        "• DISTÂNCIA DO PEITO: Mantenha o celular a ~30–40 cm do seu corpo para evitar eco permanente do próprio tronco."
                }
            )
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, Color(0xFF00E676)),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0B1F11))
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = if (isEn) "📐 TRANSDUCER CLEARANCE DIAGRAM" else "📐 DIAGRAMA DOS TRANSDUTORES",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = Color(0xFF00E676)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF050E07), RoundedCornerShape(8.dp))
                            .padding(10.dp)
                    ) {
                        val diagram = if (isEn) {
                            """
  [• TOP MIC / EARPIECE]       <-- DO NOT COVER
  ┌──────────────────────────┐
  │                          │
  │    CRT RADAR DISPLAY     │  <-- AIM AT TARGET
  │                          │
  ├─ [ FINGERS HERE ] ───────┤  <-- PINCH MIDDLE
  │                          │      SIDE EDGES
  ├─ [ FINGERS HERE ] ───────┤
  │                          │
  └──────────────────────────┘
  [• BOTTOM MIC • SPEAKER]     <-- KEEP BASE OPEN
                            """.trimIndent()
                        } else {
                            """
  [• MIC SUPERIOR / AURICULAR] <-- NÃO TAMPAR
  ┌──────────────────────────┐
  │                          │
  │    TELA DO RADAR CRT     │  <-- APONTE AO ALVO
  │                          │
  ├─ [ DEDOS AQUI ] ─────────┤  <-- SEGURE PELAS
  │                          │      LATERAIS MEIO
  ├─ [ DEDOS AQUI ] ─────────┤
  │                          │
  └──────────────────────────┘
  [• MIC PRINCIPAL • FALANTE]  <-- NÃO TAMPAR BASE
                            """.trimIndent()
                        }
                        Text(
                            text = diagram,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = Color(0xFF81C784),
                            lineHeight = 16.sp
                        )
                    }
                }
            }
        }

        item {
            HelpCard(
                title = if (isEn) "🦇 FMCW Chirp, I/Q Correlation & A-Scan" else "🦇 Chirp FMCW, Correlação I/Q e A-Scan",
                description = if (isEn) {
                    "EchoSonar transmits an inaudible 5 ms linear chirp sweeping 18.5 → 20.5 kHz every 80 ms at 48 kHz sample rate.\n\n" +
                        "• DIRECT-PATH AUTO-SYNC: Automatically locks onto the speaker-to-mic direct wave (t=0) and blanks the first 1.8 ms (~30 cm) to eliminate chassis ringing.\n" +
                        "• SUB-SAMPLE INTERPOLATION: 4th-order Butterworth bandpass filtering (18–21 kHz) followed by I/Q envelope cross-correlation and parabolic interpolation gives millimeter-step range precision up to 3.0 m.\n" +
                        "• LIVE A-SCAN BAR: Displays the real-time normalized echo amplitude across 32 distance bins (0.3 m – 3.0 m) so you can tune the SNR sensitivity gate."
                } else {
                    "O EchoSonar emite um chirp linear inaudível de 5 ms (18.5 → 20.5 kHz) a cada 80 ms em 48 kHz.\n\n" +
                        "• AUTO-SINCRONISMO PELO SOM DIRETO: Trava automaticamente na onda direta falante→microfone (t=0) e aplica blanking nos primeiros 1,8 ms (~30 cm) para eliminar reverberação da carcaça.\n" +
                        "• INTERPOLAÇÃO SUB-AMOSTRA: Filtro passa-faixa Butterworth de 4ª ordem (18–21 kHz) + correlação cruzada I/Q e interpolação parabólica oferecem precisão de milímetros até 3,0 m.\n" +
                        "• BARRA A-SCAN AO VIVO: Mostra a envolvente real dos ecos em 32 faixas de distância (0,3 m – 3,0 m) para ajudar a calibrar o filtro de SNR."
                }
            )
        }

        item {
            HelpCard(
                title = if (isEn) "🌀 Spatial Sweep, Phosphor Trail & Zero Button (0°)" else "🌀 Varredura Espacial, Rastro Fósforo & Botão Zerar (0°)",
                description = if (isEn) {
                    "Rotate the phone smoothly left and right (-45° to +45°) while pointing at objects or walls:\n\n" +
                        "• PHOSPHOR PERSISTENCE: Detected targets remain plotted on the polar CRT radar by azimuth sector for 6 seconds with gradual glow decay.\n" +
                        "• 0° RECENTER: Tap the 0° target button anytime to set your current compass heading as the forward center line."
                } else {
                    "Gire o celular suavemente da esquerda para a direita (-45° a +45°) apontando para objetos e paredes:\n\n" +
                        "• PERSISTÊNCIA DE FÓSFORO: Os alvos detectados ficam marcados no radar CRT por setor angular durante 6 segundos com decaimento suave.\n" +
                        "• ZERAR BÚSSOLA (0°): Toque no botão 0° para definir a direção atual do celular como o eixo frontal do radar."
                }
            )
        }

        item {
            HelpCard(
                title = if (isEn) "🫀 Micro-Doppler Human Detection & Material ID" else "🫀 Micro-Doppler Humano & Identificação de Material",
                description = if (isEn) {
                    "• RIGID WALL: Strong reflection with narrow correlation peak (FWHM ratio ≤ 1.5x).\n" +
                        "• SOFT OBJECT / PASSAGE: Broad or attenuated echo dispersion.\n" +
                        "• LIVING PRESENCE: Tracks carrier phase excursion across consecutive frames (~1 rad ≈ 1.4 mm motion at 19.5 kHz) combined with passive BLE/Wi-Fi stationary variance hints."
                } else {
                    "• SUPERFÍCIE RÍGIDA / PAREDE: Eco consistente com pico de correlação estreito (FWHM ≤ 1,5x).\n" +
                        "• OBJETO MACIO / PASSAGEM: Eco atenuado ou dispersão larga.\n" +
                        "• PRESENÇA VIVA: Rastreia a deriva de fase da portadora (~1 rad ≈ 1,4 mm de deslocamento respiratório em 19,5 kHz) combinada com contexto passivo BLE/Wi-Fi."
                }
            )
        }
    }
}

@Composable
fun HelpCard(title: String, description: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, Color(0xFF2C2C2C)),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF191919))
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color(0xFF80CBC4))
            Spacer(modifier = Modifier.height(6.dp))
            Text(description, fontSize = 12.sp, color = Color(0xFFE0E0E0), lineHeight = 18.sp)
        }
    }
}
