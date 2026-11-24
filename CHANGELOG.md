# Changelog

All notable changes to Duo Phone will be documented in this file.

## [Unreleased]

### Dual-Screen & Gamepad Controls (2026-11-24)
- Added `DualDisplayHelper`, `SecondaryDeckActivity`, and `SecondaryDeckScreen` for Retroid Pocket Duo secondary display (`Display #2`) dialer, active-call deck, quick SMS, and quick AT controls with full single-screen fallback.
- Added `GamepadKeyRouter` mapping `L1`/`R1`, `START` (Call), `SELECT` (SMS), and call-context-aware `A`/`B`/`X`/`Y` controls with unit tests (`GamepadKeyRouterTest`).

### Phase 3 — Call Control & Call History (2026-11-24)
- Implemented `CallAtParser` (`+CLCC`, `+CEER`, dial number validation, `AT+VTS` DTMF), `CallLogRepository` (SQLite), and `CallController` state machine (`IDLE`/`DIALING`/`ALERTING`/`INCOMING`/`ACTIVE`/`ENDING`) with zero automatic retries on `ATD`/`ATH`/`AT+VTS`.
- Implemented `CallTabScreen` with T9 dialpad, active call panel, DTMF keypad, call history, incoming call notification, and local `[SIMULATED]` UI test mode.
- Added 6 unit tests (`CallAtParserTest`, `CallControllerTest`).

### Phase 2 — 3GPP PDU SMS & SQLite Persistence (2026-11-24)
- Implemented `SmsPduCodec` supporting GSM 7-bit & UCS2 encoding/decoding, UTF-16 surrogate-safe multipart splitting, out-of-order multipart UDH reassembly, alphanumeric senders, and OTP extraction.
- Implemented `DuoDatabaseHelper` & `SmsRepository` (SQLite), `SmsServiceController`, and `SmsNotificationHelper`.
- Implemented `SmsTabScreen` (threads, conversation bubbles, quick reply, one-tap OTP copy, local `[SIMULATED]` test trigger) and 6 unit tests (`SmsPduCodecTest`, `SmsServiceControllerTest`).

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
