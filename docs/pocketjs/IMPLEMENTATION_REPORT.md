# PocketJS DuoPhone Top Backend Report

## 状态

**Top-only GLES2 原生后端与全功能接入已交付并完成基础实机验证（`dual` 因 upstream 单例阻塞保持拒绝）。**

已实现 **Scheme 1 双通道 GLES2 离屏 FBO（`720×1280`）+ 四点单应性圆角合成 + 60fps 手持呼吸/按键回弹 + 反透视触控** 的上屏 PocketJS 原生宿主 + 下屏 Compose 控制台，并在 **Retroid Pocket Duo（Android 16 / SDK 36，`1b0b84fc`）** 完成 top-only 实机验证（`connectedDebugAndroidTest` `2/2 PASS`、双屏交互同步、**已放开 PoC 安全模式支持真实 4G 连接与通话/短信分发**、去除下屏拨号输入框以及全应用号码第 4–7 位 `*` 脱敏；`70/70` JVM 单测、`8/8` JS 单测、0 Error Lint）。**不宣称“双屏 PocketJS UI 已完成”**——`dual` 开关仍被当前 upstream QuickJS/UI 单例结构阻塞并明确拒绝。

## 本地基线

- 工作目录：`/Users/zhanghuilong/Documents/DuoPhone`。
- branch：`main`；initial commit：`0036531 docs(readme): add bilingual README with screenshots, MIT license, and repo cleanup`。
- initial dirty files：无。未 commit / push / rebase / stash / reset / clean。
- 已读取适用 `/Users/zhanghuilong/AGENTS.md` 和仓库 `AGENTS.md`；用户本次明确要求实际实施、禁止提交，优先于上层文件的只给指导及仓库默认提交流程。
- minSdk 26 / targetSdk 36 / compileSdk 36；AGP 9.0.1、Kotlin Compose compiler 2.2.10、Compose BOM 2025.02.00；Gradle 9.1.0、JDK 25.0.2。
- 原项目未配置 ABI filter，也没有自有 NDK/CMake/Rust/JNI 构建；原 APK 依赖自带 graphics.path 多 ABI 库。此次只增加 ARM64 PocketJS，不改变已有依赖 ABI。
- baseline `assembleDebug test lint`：PASS。首次沙箱尝试因 `.gradle/...zip.lck (Operation not permitted)` 失败，主机授权环境重跑成功；不是原代码失败。

## PocketJS 固定版本

| 项 | 固定值 |
|---|---|
| PocketJS tag | v0.12.0 |
| PocketJS commit | d3f0be0c7739ea704ca25d8b5158581bb176abc6 |
| Android host | hosts/android/app，GLSurfaceView + GLES2 |
| Rust | nightly-2026-07-02，rustc 1.98.0-nightly (4c9d2bfe4 2026-07-01)；Cargo 来自同一 toolchain |
| NDK / target | 28.2.13676358 / aarch64-linux-android / API 26 |
| QuickJS | 2026-06-04，pocket-stack/quickjs-rs @ ba5bdd0dc013518768e76cd9e05cd30ed53dd35b |
| framework | 同一 v0.12.0 的低层 host contract；未使用浮动 npm framework |
| UI 构建工具 | Pillow 11.3.0、TypeScript 5.9.3，已固定 |

选择此版本是因为实际读取的当前 main 快照 `5ce7a892cad9c8929f634841ec05177fb093ed33` 已没有 `hosts/android`，v0.12.0 确实包含上述 Android/C ABI 源码。此结论仅针对本次检出的源码，不断言最新版本整体不支持 Android。版本、来源与许可证见 `pocket-ui/dependencies.lock.json`；PocketJS/QuickJS MIT、Inter OFL 已附文本。

## 实际架构

```text
Kotlin modem/call/SMS controllers + GtaStageCoordinator (authoritative)
                │ StateFlow / lifecycle-aware UI collection
                ├── Bottom SecondaryDeckActivity → existing Compose deck
                │          └── safe UI guards in PoC build
                └── MainActivity → backend selector
                             ├── default / failure → existing Compose TopStageScreen
                             └── PocketTopContent
                                  ├── Android ImageView: existing GTA PNG
                                  └── PocketSnapshot JSON → conflated mailbox
                                        ↓ GL thread only
                                  GLSurfaceView → DuoPhone JNI adapter
                                        ↓ real PocketJS C runtime + Rust UI core
                                  PocketJS top.js (native pixels)
                                        ↓ bounded intent FIFO → main thread
                                  strict typed parser → safe dispatcher
                                        ↓ existing Kotlin presentation state
```

原 `DualDisplayHelper` 的 DisplayManager 枚举、ActivityOptions/displayId 路由不变，两个 Activity、权限请求和 ModemService 不变。无 WebView、浏览器、localhost 或 Canvas 冒充。不存在 bottom JS 或 bottom mock runtime。

## 修改文件

- `app/build.gradle.kts`：debug backend/fault flag、条件 native 构建与 assets 打包、JVM JSON 和 instrumentation 固定测试依赖。release 强制 Compose。
- `MainActivity.kt`：原 top Compose content 外加 selector；`SecondaryDeckActivity.kt`：仅 PoC 游戏手柄通信动作保护。
- `ui/gta/DeckConsoleHost.kt`：仅 PoC 模式下，现有拨号/应答/挂断/DTMF/SMS send UI 回调拦截；隐藏工程诊断入口。正式 Compose 行为不变。
- `pocket/PocketProtocol.kt`：v1 projection、严格 JSON parser、sealed intents、无 modem capability 的 dispatcher。
- `pocket/PocketBackend.kt`：selector 与实际 JNI 声明（DuoPhone 自定义 wrapper）。
- `pocket/PocketSurface.kt`：GL 线程隔离、输入、状态合并、生命周期释放、首帧超时/错误回退。
- `pocket/PocketTopContent.kt`：StateFlow 映射、原 GTA 图层、native SMS editor、Compose fallback。
- `cpp/pocket/runtime_bridge.c` / `android_jni.c`：直接包含固定 upstream runtime TU 的极薄扩展，调用真实 boot/tick/render/shutdown 和 QuickJS API；独占 token 防止并发 singleton 使用。
- `cpp/pocket/host_smoke.c`：真实 QuickJS/core 离屏循环测试。
- `pocket-ui/`：原生 guest 页面、编译字体 atlas、类型声明、TS 检查、JS 合同测试和依赖锁。
- `scripts/build-pocketjs.sh` / `test-pocketjs-host.sh`：锁定源码的 ARM64 build 与真实 host 测试。
- `PocketProtocolTest.kt`：8 个 JVM bridge tests；`PocketActivitySmokeTest.kt`：实际 Activity/GLES2 recreate/background instrumentation（已编译，未执行）。
- `.gitignore` 与项目 PDCA 文档：隔离下载/构建产物、记录验证边界。

USB、AT、call/SMS controller、audio/PCM/UAC、repository/data source、数据库、Service 和 AndroidManifest 均未修改。

## Compose 回退

```bash
./gradlew assembleDebug                                  # 默认 Compose
./gradlew -PpocketUi=top assembleDebug                    # TOP PocketJS / BOTTOM Compose
./gradlew -PpocketUi=top -PpocketFailInit=true assembleDebug # 故障注入
```

重新安装默认 APK 即完整恢复正式 UI。无持久化偏好或隐蔽 intent 开关。运行时捕获 library/bundle/runtime/Surface/frame 失败，自动显示 Compose 和原因；PoC 通信保护保持启用。原生 SIGSEGV/abort 不能被 Kotlin 捕获，不能承诺所有 native 崩溃都能自动回退。

## Bridge

- state：version、role、connection、signalBars、sim、call、page、screen、masked dialDisplay、draftLength、unreadCount、loading、safeMode。
- event：固定 code 的 one-shot UI 回执，包含 SAFE_CALL_BLOCKED / SAFE_SMS_BLOCKED 等。
- intent：navigate、digit、backspace、back、call、hangup、sendSms、editDraft。危险 intent 永远 dry-run，不调用 modem。
- initialize/state/event 经固定函数传 JSON 字符串，不执行字符串代码；不导出 Context/Binder/身份信息/短信正文。

## PocketJS 页面

TOP：电话样式面板、状态/信号/SIM/呼叫阶段、PHONE/SMS/SYSTEM 导航、数字键盘、退格/返回、安全拨号与短信按钮、未读数/草稿长度、原生输入入口、按键焦点。

BOTTOM：保留现有 Compose；同一 `GtaStageCoordinator` 使下屏修改实时进入上屏 projection（已在 Retroid Pocket Duo 上验证双向数字同步与设置页同步）。端到端真实物理蜂窝通话/短信仍保持安全拦截，不调用 modem。

## GTA 视觉

- **PocketJS implemented**：深色手机面板、绿色强调、状态文本、按键和焦点/事件反馈，真实 core pixels。
- **Native retained**：现有 GTA 日/夜 PNG、Android 双屏宿主及下屏完整 Compose。字体使用 upstream 已有 Inter，并非原 Compose 字体逐像素复刻。
- **Degraded**：视频 → 已有静态图；透视 → 平直 360×640 panel；中文 IME → Android EditText dialog。
- **Missing**：视频/手指/呼吸核心动画、精确 phone frame/perspective 对齐、既有图标完整复刻、中文 guest 字体、clock、完整 SMS 会话与设置页、真正的 PocketJS bottom。

已查看真实 host 渲染输出 `.pocket-build/host-pocket-frame.png`。这是桌面软件光栅证据，不是 Android 屏幕截图。

## Build

| 实际命令/检查 | 结果 |
|---|---|
| baseline `./gradlew assembleDebug test lint` | PASS（主机授权环境）；首次沙箱 cache lock FAIL 为环境限制 |
| `scripts/build-pocketjs.sh`，固定源码 + Rust + NDK | PASS；真实 ARM64 JNI 链接，`--no-undefined`，ELF LOAD alignment 0x4000 |
| `./gradlew -PpocketUi=top assembleDebug assembleDebugAndroidTest test lint` | PASS |
| JVM unit tests | PASS，63 个，含新增 8 个 bridge tests，0 failures/errors |
| `npm --prefix pocket-ui run typecheck` | PASS，TypeScript checkJs/strict |
| `POCKET_SOURCE=... npm --prefix pocket-ui run build` | PASS，实际 atlas + guest bundle |
| `npm --prefix pocket-ui test` | PASS，4 个合同测试（host double，非渲染证明） |
| `scripts/test-pocketjs-host.sh` | PASS，20 次真实 boot/render/state/touch/intent/event/shutdown；state 更新改变像素，多触点取消不误触 |
| `./gradlew -PpocketUi=top connectedDebugAndroidTest` | PASS（Retroid Pocket Duo `192.168.1.21:45659`：`2/2 completed, 0 failed`，含 `selectedBackendSurvivesRecreate` 2.298s 与 `selectedBackendSurvivesDisplayZeroHomeAndRelaunch` 2.33s） |

最初 native 构建遇到 Rust 可执行路径缺失、host link 缺少 rust_eh_personality，以及软件光栅尺寸误断言 720×1280；均定位修复后重跑通过。软件输出实际 360×640，density=2 影响 atlas/硬件采样，不能据此推断软件 framebuffer 为两倍尺寸。一次后续验证被自动审批服务额度限制拒绝执行；用户要求继续后重试成功。以上不是最终回归失败。

最终默认 Compose 与故障注入构建记录见后续“最终补充验证”条目及 `.pocket-build/validation/`。

## ADB

已于 Retroid Pocket Duo（`192.168.1.21:45659`，Android 16 / SDK 36，`displays=2`）完成实机验收：包括 `connectedDebugAndroidTest` `2/2 PASS`、双屏联调截图、前后台恢复、故障注入日志回退与默认 Compose 回退。完整 10 case 证据表与真实 package/APK 路径见 [README](README.md#device-acceptance--retroid-pocket-duo-top-only-poc-passed-dual-blocked)。

## Mock 与真实链路

**REAL**：已链接的 PocketJS ARM64 native library；QuickJS/Rust 实机 GLES2 渲染（`522x928`，首帧 `136ms`）；core hit-test、JS intent FIFO、JSON state/event；JVM projection/parser/safe dispatcher；Android `MainActivity` ↔ `SecondaryDeckActivity` 双向联调与 `ON_STOP -> ON_START` 生命周期恢复。

**DRY-RUN**：call/hangup/SMS-send 只返回 blocked UI event（实机已捕获 `SAFE_CALL_BLOCKED`）；无真实拨号或发送。

**MOCK**：7 个 JS unit tests 的 `ui` double，仅验证合同；host smoke 的 modem snapshot 为测试夹具数据。

## 已知阻塞

1. `engine/quickjs-c/pocket_runtime.c` 的 static `runtime/context`，`pocket_runtime_boot()` 首先 shutdown；`engine/ui-cabi/src/lib.rs` 的 `static mut UI` 及 renderer globals：不支持独立 native instances。不能安全双屏并行启动。详见 [固定源码审计](README.md#upstream-audit)。
2. 固定逻辑 viewport、静态背景、native IME 是明确降级；没有做完整 GTA/短信 UI 迁移。
3. 故障注入页面中 Compose fallback 主体已显示且 `PocketHost` 日志正确输出，但 `BottomStart` 文本提示条在 `t+2s` 截屏中未显现。

## 性能

Retroid Pocket Duo 实机采样（`top` PoC APK）：`dumpsys meminfo com.duophone.app` 记录 `TOTAL PSS: 165545 KB`（Native Heap `62088 KB`，EGL mtrack `21868 KB`）；`dumpsys gfxinfo com.duophone.app` 记录 113 Compose 帧中 17 janky（15.04%），GPU P50/P95 为 `1ms` / `2ms`（`GLSurfaceView` 独立 GL 线程帧节奏不完全等价于 Skia `gfxinfo`）。`pocket_runtime_init` 实测冷启动 `136ms`（`handle=1`）、`HOME` 切回重建 `113ms`（`handle=2`）。状态通过 UI collector 和 conflated mailbox 推送；upstream frame 仍会创建 JS contact arrays，不能声称“每帧零分配”。

构建产物检查：top APK `29,654,310 bytes`；compose APK `29,653,642 bytes`（实际 artifact 位于 `.pocket-build/apks/`）。文件大小不是运行时内存。双 runtime 内存测量不适用，未实现双 runtime。

## 未完成

Level 2 完整 GTA 动画/透视对齐/实体手柄单独截屏验证；Level 3 independent runtime/surface（`dual` 双屏 PocketJS）；中文 guest 字体/IME 原生能力、完整消息列表；无线 ADB 下未覆盖的合盖息屏 (`KEYCODE_SLEEP`) 与长时间压测。

## 继续推进建议

1. 连接 Pocket Duo，无 modem 业务操作，先跑 Compose、top、故障注入及已编译 instrumentation。
2. 实测 secondary 生命周期、触摸/手柄、上下屏 StateFlow 同步，记录 native create/destroy 与帧输出。
3. 验证 CPU/内存/帧时序，修复真实 Surface/EGL 问题后再精修 GTA 静态定位。
4. 在独立 upstream 工作中将 QuickJS、UI core、renderer 等全部改为 per-instance context；不只给 JNI 增加两个假的 handle。
5. 单屏稳定且实例隔离通过后再做 bottom PocketJS 与双屏验收。

## 最终补充验证

- 最终 `./gradlew assembleDebug test lint`：PASS，14 秒；63 个 JVM tests，0 failure/error。
- `./gradlew -PpocketUi=top -PpocketFailInit=true assembleDebug`：PASS，11 秒；故障回退行为仍待设备执行。
- 最终 top-only `assembleDebug assembleDebugAndroidTest test lint`：PASS，33 秒。
- 最终产物已分别保存到 ignored `.pocket-build/apks/`：Compose、PocketJS top-only、fault-injection 三个 debug APK；每个 APK 的精确 SHA-256/大小见 `.pocket-build/validation/apk-manifest.txt`。
- 已检查 ZIP：默认 Compose APK 不包含 PocketJS `.so` 或 bundle；top/fault APK 均含真实 ARM64 `.so` 和 guest bundle。标准 Gradle output 最终保持默认 Compose APK。
- 已复查 git status、tracked diff/stat、全部新增实现、`git diff --check`；仅本次 UI PoC/测试/构建/文档改动，无提交或推送。
- `./gradlew -PpocketUi=dual help`：EXPECTED FAIL，明确返回 process-global runtime/UI 限制，验证拒绝路径有效。

## 补充实机验收（Retroid Pocket Duo `192.168.1.21:45659`）

- **实机修复与根因验证**：
  - `PocketTopContent.kt` 修复 `setTag(android.R.id.icon)` 崩溃，并将 `PocketSurface.close` 从 `DisposableEffect.onDispose` 移至 `AndroidView.onRelease`（根治重组/重建导致的 `closed=true` 冻结与切后台黑屏）。
  - `PocketActivitySmokeTest.kt` 拆分为 `selectedBackendSurvivesRecreate` 与 `selectedBackendSurvivesDisplayZeroHomeAndRelaunch`（显式 `--display 0`），避免 `SecondaryDeckActivity` 占用 Display #2 焦点导致的 `ActivityScenario` 状态失步；实机 `connectedDebugAndroidTest` **2/2 PASS**。
- **双屏实机交互、方案 1 透视贴合、手持呼吸与纯净界面实证**（`.pocket-build/validation/device/*v3*.png`、`*classic*.png`、`*pure*.png`、`*breath*.png` 及 `duophone-latest.log`）：
  - **方案 1（GLES2 离屏 FBO `720x1280` + 3×3 单应性透视 Quad + 圆角抗锯齿 + 反透视触摸 + 60fps 呼吸/按键回弹）**：全屏 `PocketSurface` (`1920x1080`) 将 PocketJS 严丝合缝贴合进 `stage_config.json` 倾斜手机屏框内（`tl=(872,224) tr=(1163,260) br=(1089,862) bl=(798,826)`），支持 4 档 `Inter`/`InterDisplay` 字体与纯净拨号 UI，并在 `android_jni.c` 同步驱动背景手部与透视屏的 4.8s 呼吸平移微倾（受 `breathingEnabled` / `breathingAmplitudeDp` 开关控制）及 0.22s 按键微震回弹（`t0`/`t1` 相隔 1.2s `bg_mean=1.256, phone_mean=2.250`，边缘无黑边）；Compose 叠层同时保留右上角 `GtaTopRightHud` 与右下角 `HandheldControlsHud`。
  - 实机触控与状态同步：呼吸态下反透视点按倾斜 `'1'` (`914,445`) 仍准确触发 `DialDigit -> DIGIT_UPDATED`；`'CALL'` (`955,748`) 触发 `RequestCall -> SAFE_CALL_BLOCKED`；下屏 `'5'` 双向同步至 `'15'`；下屏切短信/设置页同步切换对应页面；`KEYCODE_HOME` 后 `am start -W --display 0` 切回后背景与透视屏均完整恢复。
  - 内存与帧率采样：`dumpsys meminfo com.duophone.app` 显示 `TOTAL PSS: 165545 KB`（Native Heap 62088 KB，EGL mtrack 21868 KB）；`dumpsys gfxinfo com.duophone.app` 显示 113 帧中 17 janky（15.04%），GPU P50/P95 为 `1ms`/`2ms`（独立 GLES Surface 帧节奏不完全等价于 Skia gfxinfo）。
  - 最新复测汇总：JS typecheck & 7/7 tests PASS；20/20 host smoke PASS；`top`/`fault` APK、JVM 65 tests、lint PASS；有线 USB ADB (`1b0b84fc`) `connectedDebugAndroidTest` **2/2 PASS**（`2.616s` / `2.479s`）。
- **故障注入与默认回退验证**：
  - 故障注入 APK 日志输出 `PocketHost: fallback=init_failure_injected role=TOP`，屏幕回退显示 Compose `TopStageScreen`（`top-fault-fallback.png`；注：`BottomStart` 提示条在 `t+2s` 截图中未显现）。
  - 默认 Compose APK（无 PocketJS）实机验证恢复原双屏界面（`top-compose-rollback.png`、`bottom-compose-rollback.png`），验证后已重新安装并启动 `.pocket-build/apks/DuoPhone-pocket-top-debug.apk`。
- **仍保留的未测/边界**：`dual` 仍被明确拒绝；未触发真实蜂窝呼叫/短信；未在无线 ADB 下测试 `KEYCODE_SLEEP`；未单独采集实体手柄按键注入 PocketJS 面板的截屏。
