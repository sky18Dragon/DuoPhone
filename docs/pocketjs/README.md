# DuoPhone PocketJS Top Backend (`-PpocketUi=top`)

2026-11-26 / 2026-11-27. **Experimental top-only native GLES2 host** (`dual` remains blocked due to upstream v0.12.0 process-global singletons; Compose remains default for standard `assembleDebug` and `release`). See [implementation report](IMPLEMENTATION_REPORT.md).

> **2026-11-27 Update**: Upgraded to **Scheme 1 dual-pass GLES2 FBO (`720×1280`) + 4-corner homography rounded-corner compositor** with 60fps handheld breathing/key-press recoil and inverse-perspective touch, unlocked full 4G modem/call/SMS/diagnostics/gamepad actions (`safeMode = false`), removed the redundant bottom dial input box, and applied digits 4–7 (`*`) masking via `TopScreenPhoneMasker` (`70` JVM tests, `8` JS tests, `2/2` device smoke tests passing on `1b0b84fc`).

## Reproduce

Use the repository's Android/JDK setup. Required native tools are Rust `nightly-2026-07-02`, target `aarch64-linux-android`, Android NDK `28.2.13676358`, Python 3 with Pillow `11.3.0`. TypeScript `5.9.3` is locked in `pocket-ui/package-lock.json`; there are no npm dependencies in the running guest.

```bash
rustup toolchain install nightly-2026-07-02 --profile minimal --target aarch64-linux-android
python3 -m venv .pocket-build/venv
.pocket-build/venv/bin/pip install -r pocket-ui/requirements.txt
export POCKET_PYTHON="$PWD/.pocket-build/venv/bin/python"
npm --prefix pocket-ui ci --ignore-scripts
npm --prefix pocket-ui run typecheck
npm --prefix pocket-ui test
./gradlew -PpocketUi=top assembleDebug test lint assembleDebugAndroidTest
bash scripts/test-pocketjs-host.sh
```

The Gradle `buildPocketNative` task invokes `scripts/build-pocketjs.sh` before `preDebugBuild` only for `-PpocketUi=top`. The script downloads the two **exact commits**, verifies their identities and tracked source cleanliness, runs locked Cargo, builds QuickJS and JNI with NDK Clang, and bakes the guest/font. It does not install Rust or NDK implicitly. Default Compose and release builds require no Rust, Python or PocketJS checkout.

Optional `POCKET_SOURCE`, `QUICKJS_SOURCE`, `POCKET_RUST_OUT` select existing checked-out dependencies/caches; they do not relax commit checks. The implementation run used `/tmp/duophone-pocketjs-upstream`, `/tmp/duophone-quickjs-rs`, `/tmp/duophone-pocket-native/rust`. Dependencies are not vendored. `.pocket-build/` is ignored. On macOS with Homebrew rustup the script resolves the pinned compiler explicitly. Android SDK comes from `ANDROID_HOME`, `ANDROID_SDK_ROOT` or `local.properties`.

```bash
# Default / rollback. Install this APK to restore the normal UI and communication actions.
./gradlew assembleDebug
# Opt-in top-only experimental APK; bottom remains Compose.
./gradlew -PpocketUi=top assembleDebug
# Deliberate initialization fault, for device fallback acceptance.
./gradlew -PpocketUi=top -PpocketFailInit=true assembleDebug
# Intentionally fails configuration; upstream does not provide instance-safe core/runtime.
./gradlew -PpocketUi=dual assembleDebug
```

Actual output: `app/build/outputs/apk/debug/DuoPhone-v1.0.0-debug.apk`, package `com.duophone.app`. Debug selector is not persisted and cannot be changed by an exported intent. `release` always uses Compose and does not package the experimental native library. The top build has only the additional `arm64-v8a` library; on unsupported ABI it falls back to Compose. A native SIGSEGV/abort cannot be caught by Kotlin; recovery covers load errors, boot failures, message/frame return errors, Surface setup exceptions and first-frame timeout, not arbitrary native process crashes.

## Native ownership and threading

`MainActivity → Compose AndroidView → FrameLayout (existing GTA ImageView + GLSurfaceView) → DuoPhone JNI → pocket_runtime_boot/tick_contacts/gl_render → QuickJS + ui-cabi Rust core`.

All core/QuickJS calls run on one GLSurfaceView render thread. The token is an **exclusive ownership generation**, not an upstream instance handle. A process-wide atomic owner rejects any second Surface. `pthread_equal` and token checks reject stale/wrong-thread calls. State comes from lifecycle-aware Kotlin collectors and a conflated atomic slot; touch/key events use ordered `queueEvent`; the native intent FIFO and event FIFO are each bounded at 16. JSON strings enter the fixed `duoReceive` function via `JS_Call`, never `JS_Eval`. Native intents are polled and posted to main; no Binder, USB, DB or network work occurs on the render thread. JS heap is limited to 32 MiB.

STOP, Surface loss and detach release QuickJS/core and GL resources before pausing/ending the GL thread. START boots anew and replays the latest snapshot. Context replacement resets stale GL resources. The logical viewport is fixed at **360×640**, raster density 2; physical Surface dimensions scale that viewport. This PoC does not implement responsive guest reflow or independent dual-runtime viewports. Ownership and lifecycle code is compiled but still needs Android device acceptance.

The thin adapter includes the pinned `engine/quickjs-c/pocket_runtime.c` translation unit to access its real private `JSContext`. `__duoDispatch` and `duoReceive` are **DuoPhone-owned extension names**, not claimed PocketJS SDK methods. The private host identifies itself as `duophone-poc`, ABI 1; it is not an official Moto profile/host contract. Official Android packaging uses `tools/moto-g-play-profile.ts` host ABI 9, profile `moto-g-play-dev`.

## Bridge v1

- Snapshot `initialize` / `state`: role, connection enum, 0–5 signal bars, SIM enum, call phase, page, phone visual state, masked dial display, draft character count, unread count, loading, safeMode. No identity, recipient, message body, OTP, mutable singleton or Android object.
- Events: version, kind=`event`, fixed result code, such as `NAVIGATED`, `DIGIT_UPDATED`, `SAFE_CALL_BLOCKED`, `SAFE_SMS_BLOCKED`, `IME_NATIVE_FALLBACK`.
- Intents: navigate(PHONE/SMS/SETTINGS), digit(0–9/*/#), backspace, back, call, hangup, sendSms, editDraft. Parser rejects wrong versions, duplicate/unknown fields, extra trailing tokens and malformed values; input limited to 4096 characters.
- Safe dispatcher has **no call/SMS capability**. Call, hangup and send only return blocked events. Existing Compose deck dial/answer/hangup/DTMF/SMS-send and communication gamepad actions are guarded while the top build is selected, including during fallback. Engineering diagnostics are unavailable in this mode. Communications core, notification receiver and Service are unchanged; this is a UI PoC guard, not a system-wide modem sandbox. No real calls or SMS were made during validation.
- Edit SMS opens an Android EditText dialog (up to 2000 characters), saves into the existing Kotlin draft; guest receives only draft length. **IME = native fallback**. Chinese IME operation on device is not verified.

## Upstream audit

Pinned links:

- [Android Java adapter](https://github.com/pocket-nexus/pocketjs/blob/d3f0be0c7739ea704ca25d8b5158581bb176abc6/hosts/android/app/src/dev/pocketstack/android/PocketActivity.java): standalone Activity with GLSurfaceView/Renderer; no reusable SDK View class.
- [Android JNI](https://github.com/pocket-nexus/pocketjs/blob/d3f0be0c7739ea704ca25d8b5158581bb176abc6/hosts/android/app/jni/runtime.c): static input, assets, runtime_booted and GL flags; no native handle. Touch latch supports eight contacts; keys map to portable button masks.
- [Portable runtime](https://github.com/pocket-nexus/pocketjs/blob/d3f0be0c7739ea704ca25d8b5158581bb176abc6/engine/quickjs-c/pocket_runtime.c): static JSRuntime/JSContext, boot begins with shutdown; a second boot destroys the first guest.
- [UI C ABI](https://github.com/pocket-nexus/pocketjs/blob/d3f0be0c7739ea704ca25d8b5158581bb176abc6/engine/ui-cabi/src/lib.rs): explicitly one Ui, `static mut UI`, framebuffer and renderer state. Rust staticlib/rlib, not a multi-instance cdylib SDK.
- [C ABI header](https://github.com/pocket-nexus/pocketjs/blob/d3f0be0c7739ea704ca25d8b5158581bb176abc6/engine/ui-cabi/include/pocket_ui_cabi.h): real viewport, tree, fonts, render, shutdown APIs, no instance parameters.
- [Android build tool](https://github.com/pocket-nexus/pocketjs/blob/d3f0be0c7739ea704ca25d8b5158581bb176abc6/tools/android.ts): links GLESv2/log/dl/m, standalone APK generation. No ANativeWindow/TextureView/wgpu path in the inspected Android host. EGL is owned by GLSurfaceView.
- Official Moto profile: arm64, Android API 23 minimum, target 34, NDK 27.1.12297006; older Blackberry profile: armv7/API 18, NDK 23.2.8568313. DuoPhone deliberately uses its installed NDK 28.2.13676358/API 26 and ARM64 only; real compilation passed.
- Official assets are JS + borrowed `.pak`; this guest uses no image/style pak and passes an empty borrowed buffer. Font atlas is baked into JS and loaded with real `ui.loadFontAtlas`. Existing GTA PNGs remain native Android assets.
- No multiple-display/Surface/runtime instance contract exists in these APIs. Two render threads would race global state. A singleton mutex alone would not create independent scenes. Completing dual mode requires converting runtime, UI, renderer, input and lifetime APIs to per-instance contexts or a separately evaluated process-isolation design. Neither is silently introduced here.

Licenses and exact versions are in `pocket-ui/dependencies.lock.json`, adjacent `licenses/`, and generated APK assets. Inter font is OFL; PocketJS and QuickJS are MIT. Rust transitive dependency versions are fixed by the pinned upstream Cargo.lock.

## Device acceptance — Retroid Pocket Duo Top-Only PoC PASSED (`dual` blocked)

Verified on Retroid Pocket Duo (Android 16 / SDK 36, Display #0 physical ID `4630947039571902866` `1920x1080`, Display #2 physical ID `4630946671160541331` `1280x960`) over wireless ADB (`192.168.1.21:45659`). Real cellular call/SMS flows remained blocked (`SAFE_CALL_BLOCKED`).

```bash
ANDROID_SERIAL=192.168.1.21:45659 ./gradlew -PpocketUi=top connectedDebugAndroidTest
# Result: 2/2 passed (selectedBackendSurvivesRecreate 2.298s, selectedBackendSurvivesDisplayZeroHomeAndRelaunch 2.33s)
```

| Case | Acceptance | Current evidence |
|---|---|---|
| 1 Compose | default APK launches original stage/deck | **PASS** — `.pocket-build/validation/device/top-compose-rollback.png` & `bottom-compose-rollback.png` |
| 2 top-only | TOP PocketJS GLES2 (`522x928`), BOTTOM original Compose; role and display IDs correct | **PASS** — `ms=136 handle=1`, `PHONE / PhoneDialing` on top, `LEONIDA #2` on bottom (`*v3-initial.png`) |
| 3 dual | two independent native scenes | **BLOCKED** by upstream singleton; config rejected (`./gradlew -PpocketUi=dual help` fails fast) |
| 4 init fault | `-PpocketFailInit=true`; Compose visible, logcat tag | **PARTIAL/PASS** — `PocketHost: fallback=init_failure_injected role=TOP`, Compose `TopStageScreen` visible (`top-fault-fallback.png`); bottom-start badge text not visible in `t+2s` frame |
| 5 lifecycle | recreate & Display #0 background/foreground recovery | **PASS** — `connectedDebugAndroidTest` `2/2 PASS`; live `KEYCODE_HOME` -> `am start` recovered `handle=2` (`frames=953 -> 954`, `top-pocket-v3-after-home.png`); wireless `KEYCODE_SLEEP` skipped |
| 6 secondary | Display #2 paired launch (`displayId=2`), top unaffected | **PASS** — `SecondaryDeckActivity` launched cleanly on Display #2; settings guard text verified (`bottom-compose-v3-settings.png`) |
| 7 touch | top & bottom tap sync, drag-off cancellation | **PASS** — real host 20-cycle PASS + device tap `'1'` & `'CALL*'` synced to bottom (`*v3-after-tap.png`) |
| 8 keys | dpad/L1/R1/confirm/back | **PARTIAL** — JS 7/7 + native-host tests PASS; hardware gamepad key injection over live PocketJS not separately captured on device |
| 9 state | bottom Compose digit & settings tab update Kotlin then top | **PASS** — bottom `'5'` synced to `'15'` on top (`top-pocket-v3-after-bottom-digit.png`); bottom settings synced `SETTINGS / Settings` (`top-pocket-v3-settings.png`) |
| 10 intent | top guest action updates Kotlin; safe call shows blocked event | **PASS** — device `DialDigit -> DIGIT_UPDATED` & `RequestCall -> SAFE_CALL_BLOCKED` verified in `duophone-latest.log` and `top-pocket-v3-after-tap.png` |

On device record runtime create/destroy pairs, bundle load, surface attach/detach, resize, state push, intent receive and fallback. Log only enum/type/result metadata, never payload strings. Collect memory/CPU/startup/gfx only after real frames and input are confirmed. `gfxinfo` is not a complete measure of a separate GLES Surface's frame pacing; also inspect frame timestamps/SurfaceFlinger if needed.
