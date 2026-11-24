# Changelog

All notable changes to Duo Phone will be documented in this file.

## [Unreleased]

### Phase 1 — USB Host, AT Engine, Foreground Service & Diagnostics (2026-11-24)
- Bootstrapped Gradle 9.1.0 + AGP 9.0.1 + Jetpack Compose Android project (`com.duophone.app`, `compileSdk 36`, `targetSdk 36`).
- Implemented safe QDC507 USB discovery (`2CA3:4006` / `2C7C:0125`) and `UsbInterfaceClassifier` excluding ADB (`FF/42/01`), UAC, storage, and Diag IF0.
- Implemented serialized `AtEngine` with continuous line-framed URC demuxing, SMS `>` prompt handling, and non-retryable side-effect command guard (`AtCommandPolicy`).
- Implemented `ModemController` state machine (`DISCONNECTED` → `READY` + 5 abnormal states) and Android 16 `connectedDevice` foreground `ModemService`.
- Implemented `DuoLogger` (12 categorized tags, PII redaction, rolling local file log) and Retroid Pocket Duo hardware diagnostics UI + AT Debug Console.
- Added 12 JVM unit tests (12/12 passing), passed `lintDebug` (0 errors), and verified installation and runtime startup on physical Retroid Pocket Duo (`1b0b84fc`).

### Phase 0 — Research, Audit & Device Diagnostics (2026-11-24)
- Completed source-code and license audit of CardDock, MaVo, DJOneHubNative, DJ4Hub, and DJOneHub-mac-enhanced.
- Collected real-device ADB diagnostics from Retroid Pocket Duo (`CQ7790S`, Android 16 / SDK 36, dual internal displays `Display 0: 1920x1080` & `Display 2: 1280x960`, `hall_switch`, `Retroid Pocket Controller`, USB-C dual-role Host).
- Established architecture, risk register, test plan, and full engineering documentation (`AGENTS.md`, `docs/`).
