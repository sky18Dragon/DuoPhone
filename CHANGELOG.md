# Changelog

All notable changes to Duo Phone will be documented in this file.

## [Unreleased]

### Phase 5 & 6 — P1 Productization: Contacts, Ringtone, Quick Notifications, Headset Auto-Routing & Service Stability (2026-11-24)
- **Contacts & Caller-ID**: Upgraded `DuoDatabaseHelper` to schema v2 (`contacts` table), added `ContactRepository` & `ContactPhoneMatcher` (local SQLite CRUD + optional `READ_CONTACTS` system merge with local-priority deduplication), and wired caller/sender name resolution across `CallScreen`, `SmsScreen`, `SecondaryDeckScreen`, and notifications.
- **Ringtone & Game-Friendly Quick Actions**: Added `CallRingtoneController` (ringtone + vibration pinned strictly to local non-USB outputs so `Baiwang` UAC never hijacks ringing) and `CallActionReceiver` enabling direct notification **接听 / 拒接 / 挂断**, SMS **`RemoteInput` direct reply**, and **Copy OTP**.
- **Headset Auto-Routing, Call Volume & Runtime Auto-Recovery**: Added `AudioRoutePolicy` and upgraded `AndroidAudioBridge` with `AudioDeviceCallback` hot-plug detection (Bluetooth / 3.5mm wired / speaker), live mid-call endpoint switching, headset NLP echo-ducking bypass, `STREAM_VOICE_CALL` volume steps, and automatic `/tmp/duophone-voice` re-deployment in `DuoPhoneApplication` after dongle power-cycling.
- **Background Service & Power Diagnostics**: Upgraded `ModemController` with idle AT keepalive (paused during active calls) and `ModemService` with call-scoped `PARTIAL_WAKE_LOCK`, `ACTION_SCREEN_OFF`/`ON` + `DisplayManager.DisplayListener` diagnostics, and reconnect recovery.
- **Verification**: Added `ContactPhoneMatcherTest`, `AudioRoutePolicyTest`, and `ModemKeepaliveTest`; full suite **47/47 PASS** (`16` suites), `lintDebug` **0 Error**, Debug APK `9,419,147` bytes.

### Phase 4 Live Two-Way Voice Breakthrough, Uplink Gain & Echo Suppression (2026-11-24)
- Verified real-hardware QDC507 `AT+QADBKEY` unlock, `0x2CA3:0x4006` `adb=1, uac=1`, `/tmp/duophone-voice` `.ko` + ACDB VoLTE calibration, Android 16 full-duplex concurrent capture (`not silenced`), and live full-duplex cellular voice call (`downlink` + `uplink`).
- Added `PcmVoiceConditioner` (`80 Hz` high-pass + two-stage `3400 Hz` low-pass, `+9.5 dB` soft-limited speech gain, slewed speakerphone NLP echo ducking) with unit tests in `PcmResamplerTest` (**40/40 PASS**, **0 Lint errors**).
- Upgraded `AndroidAudioBridge`: switched UAC uplink `AudioTrack` to `USAGE_ALARM`, switched local mic `AudioRecord` to `VOICE_COMMUNICATION` with `AEC`/`NS`/`AGC`, throttled UI meter emissions to `250ms` (preventing ANR), and bound QDC507 `--voice-route-session` strictly to active calls.

### Phase 4 Guarded QDC507 Backup, QADBKEY Unlock & ADB/UAC Controls (2026-11-24)
- Added `Qdc507ConfigManager`, clean-room Unix `$1$` `Md5Crypt` / `QadbKeyDeriver`, and `UsbCfgTuple` preserving original `0x2CA3:0x4006` while supporting IMEI-backed config backup (`files/backups/`), `AT+QADBKEY?` unlock, guarded `adb=1, uac=1` write+readback+`AT+CFUN=1,1`, and one-click original `usbcfg` rollback.
- Extended `PiiRedactor` to redact `AT+QADBKEY="..."` secrets in logs and added confirmation `AlertDialog` flows in `DiagnosticsView`.
- Fixed stale in-flight `+CLCC` poll regression immediately after `ATA` answer in `CallController.kt` and added `Qdc507ConfigManagerTest` (full suite **39/39 PASS**, **0 Lint errors**).

### Dual-Screen Landscape Dialpad & Header Layout Fix (2026-11-24)
- Redesigned `CompactLandscapeDialer` and `CompactDtmfGrid` in `CallScreen.kt`, shared across `CallTabScreen` and `SecondaryDeckScreen.kt`: replaced the tall `OutlinedTextField` + fixed-height vertical stack with a `40dp` bordered `BasicTextField` bar, height-weighted 4x3 keypad rows, and a right-hand action column (`+号`/`退格`, `清空`, and prominent green `拨出 (ATD)`), eliminating bottom clipping on Retroid Pocket Duo `Display #0` and `Display #2`.
- Switched active-call DTMF + control actions on both screens to a side-by-side layout and compacted headers/tab bars (`DuoPhoneApp.kt`, `SecondaryDeckScreen.kt`) with `navigationBarsPadding()` so top-right `重连` and bottom dial controls remain unclipped.

### Phase 4 — USB ADB Client, QDC507 Reversible Voice Runtime & UAC Audio Bridge (2026-11-24)
- Implemented clean-room USB ADB wire framing (`AdbWireProtocol`, `AdbShellMarker`, `AdbSyncCodec`) and `AndroidUsbAdbClient` claiming only `FF/42/01` bulk endpoints (with explicit `AUTH` challenge abort and clean interface release).
- Implemented `Qdc507VoiceRuntime` enforcing ADR-004 GPL-2.0 isolation (reads external assets from `filesDir/voice_runtime/` only after exact SHA-256 verification, pushes strictly to `/tmp/duophone-voice`, never writes persistent partitions, and never calls `rmmod`).
- Implemented `StatefulLinearResampler`, `PcmLevelMeter` (with digital-silence frame counter for Android 16 concurrent capture detection), and `AndroidAudioBridge` (`AudioRecord` + `AEC`/`NS` + `AudioTrack` UAC endpoints + API 31+ `setCommunicationDevice` / legacy fallback).
- Wired `RECORD_AUDIO` / `MODIFY_AUDIO_SETTINGS` permissions, `CallController` mute/speaker/hangup hooks, Diagnostics Phase 4 control panel, and 10 new JVM tests (`AdbWireProtocolTest`, `Qdc507VoiceRuntimeTest`, `PcmResamplerTest`); full suite 36/36 PASS, 0 Lint errors.

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
