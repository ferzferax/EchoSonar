# PROJETO_STATUS.md — EchoSonar (Ultrasonic Active Radar)

**Curador / Criador:** `@ferzferax` ([GitHub](https://github.com/ferzferax))  
**Pacote Android:** `com.ferzferax.echosonar` (`EchoSonar`)  
**Repositório Público:** [ferzferax/EchoSonar](https://github.com/ferzferax/EchoSonar)  
**Última atualização:** 2026-11-22

---

## Status da Separação e Calibração (`v0.1.0-alpha`)

- [x] Projeto separado completamente do reconhecimento neural YAMNet (`HeardWord`) para arquitetura DSP pura em Kotlin (~11,7 MB em Release sem TFLite)
- [x] Motor `SonarDSPProcessor.kt`: blanking elevado para `1.8 ms` (`>= 84` amostras ≈ `30 cm`), filtro SNR configurável (`2.6x` padrão), correção de classificação `RIGIDO`/`MACIO`/`HUMANO` e perfil **A-Scan** de 32 bandas (`0.2m – 3.0m`)
- [x] Motor `SonarEngine.kt`: suavização EMA de distância, confirmação de alvo (2 quadros) + hold suave `1.5s`, rastro de persistência fosforescente de `6s` por azimute, botão de zerar bússola `0°`, controles reais de Cadência, Sensibilidade SNR e Mute de Áudio Sci-Fi
- [x] Interface `SonarScreen.kt`, `OrientationTracker.kt`, `HelpScreen.kt` e `MainActivity.kt` 100% bilíngues (**Inglês padrão** + botão `EN / PT`)
- [x] Ícone vetorial adaptativo cyberpunk original criado (`ic_launcher_background.xml` + `ic_launcher_foreground.xml`)
- [x] Documentação GitHub padrão (`README.md`, `CHANGELOG.md`, `CONTRIBUTING.md`, `LICENSE`, banner SVG, template de bug report)
