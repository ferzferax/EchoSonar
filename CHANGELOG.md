# Changelog

All notable changes to **EchoSonar** are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [0.1.0-alpha] - 2026-11-22

### Added
- **Standalone Ultrasonic Sonar App:** Separated cleanly from the neural audio recognizer (`HeardWord`), removing all TFLite/YAMNet models and cutting release APK footprint to `11.7 MB` pure Kotlin DSP (`com.ferzferax.echosonar`).
- **Real-Time FMCW DSP Engine (`SonarDSPProcessor.kt`):**
  - 18.5–20.5 kHz Tukey-windowed linear chirp transmitted every 80 ms at 48 kHz.
  - 4th-order Butterworth IIR bandpass cascade (`18–21 kHz`) + quadrature I/Q cross-correlation + sub-sample parabolic interpolation (`~1 mm` resolution step).
  - Live **32-bin A-Scan Echo Envelope (`0.3m – 3.0m`)** visualization inside the radar display.
- **Direct-Path Self-Sync & Chassis Ringing Blanking:** Raised blanking delay to `1.8 ms` (`>= 84` samples ≈ `30 cm`) after the locked direct path (`pkDirect`) to prevent speaker-to-mic body bleed from creating ghost near-field targets.
- **Target Classification & Micro-Doppler Phase Tracking:** Separated rigid reflectors (`PAREDE`), soft/diffuse reflectors (`PORTA`), and living breathing targets (`HUMANO`) requiring sustained carrier phase drift (`>= 0.4 rad`).
- **Spatial Azimuth Tracking & 6-Second Phosphor Persistence Trail:** Uses fused rotation vector sensors with a **0° Recenter** button so panning horizontally maps obstacle reflections across `-45°..+45°`.
- **Target Hold & EMA Smoothing + Live UI Controls:** Connected Cadence (`50–240 ms`), Sensitivity SNR Gate (`1.8x–4.5x`), Sci-Fi Audio Mute (`🔊 / 🔇`), and instant **English (`EN`) / Portuguese (`PT-BR`)** toggle.
- **Original Adaptive Vector Icon:** Custom radar/cyan-emerald cyberpunk launcher artwork (`ic_launcher_foreground.xml` & `ic_launcher_background.xml`).
