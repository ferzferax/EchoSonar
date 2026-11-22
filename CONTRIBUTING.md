# Contributing to EchoSonar

Thank you for exploring **EchoSonar**! This is an **early-stage experimental DSP & sensor fusion project** that still has plenty of room for calibration across different Android hardware microphones and speakers. Community testing, bug reports, and Pull Requests are very welcome!

---

## Testing Checklist on Real Hardware

Because ultrasonic frequency response (`18.5–20.5 kHz`) varies significantly across smartphone speakers and MEMS microphones, hardware field reports are super helpful:
1. Test in both **Standing Radar Mode (`35°–85°`)** and **Flat Compass Mode (`0°–30°`)**.
2. Adjust the **Echo Sensitivity (SNR Gate)** slider while watching the **A-Scan Echo Envelope** bar (`0.3m – 3.0m`).
3. Report your device model, Android version, `UNPROCESSED` mic support, and any false-echo observations via [Bug Reports](https://github.com/ferzferax/EchoSonar/issues/new?template=bug_report.md).

## Building from Source

1. Fork `https://github.com/ferzferax/EchoSonar`.
2. Ensure **JDK 17** and **Android SDK 36** are installed (`local.properties` -> `sdk.dir=...`).
3. Build and install:
   ```bash
   ./gradlew assembleRelease assembleDebug
   adb install -r EchoSonar.apk
   ```
4. Open a Pull Request describing your DSP or UI changes clearly in English.
