# 参考开源项目源码与能力审计 (`REFERENCE_PROJECTS.md`)

审计日期：2026-11-24（全部 5 个仓库均已实际克隆并完成源码级检查，结论均基于源码而非仅 README）。

---

## 1. CardDock (`Godles-lab/usb-modem-sms`)
- **GitHub URL**: https://github.com/Godles-lab/usb-modem-sms
- **分支 / Commit SHA**: `main` / `8fe736a1d95a7a2daaedbdc6b5d614b4679236d0`
- **最后更新时间**: `2026-08-23 01:13:52 +0800`
- **开源许可证**: MIT License
- **技术栈**: Kotlin + Jetpack Compose + Coroutines + Android USB Host API（Gradle 8.9 / AGP 8.6.0 / compileSdk 35）
- **实际源码结构**: 仅 4 个源码文件（`Modem.kt` 735 行、`MainActivity.kt` 379 行、`Ui.kt`、`Theme.kt`）+ `device_filter.xml`
- **已实现功能（源码证实）**:
  - USB 设备识别（硬编码 `0x2CA3:0x4006`，注释中含 `0x2C7C:0x0125`）。
  - Android `UsbManager` 权限申请（API 31+ `FLAG_MUTABLE`）与热插拔广播。
  - 三级递进 AT 接口自动探测（优先 `IF2`，枚举所有含 bulk IN+OUT 的接口，下发 CDC `SET_CONTROL_LINE_STATE` `0x21/0x22` 拉高/拉低 DTR+RTS，发 `AT\r\n`/`AT\r`/`ATI\r\n`）。
  - 基础遥测（`+CSQ`、`+COPS?`、`+CSCA?`、`+CEREG?`）、IMEI/型号（`AT+CGSN`、`ATI`）。
  - **仅文本模式 (`AT+CMGF=1`) 短信**收发（GSM 7-bit / UCS2 切换）、删除、存储区切换（`AT+CPMS`）、简单验证码提取。
  - `AT+QCFG="usbnet"` 查询/切换与按 IMEI 区分的 SharedPreferences 配置备份。
  - 原始 AT 控制台。
- **未实现或已知缺陷（源码证实）**:
  - **无电话呼叫控制**（无 `ATD`/`ATA`/`ATH`/`+CLCC`/`RING`/`+CLIP`）。
  - **无任何语音/音频实现**。
  - **无 PDU 短信编解码、无长短信分片重组**。
  - **无后台 Foreground Service**，无通知，无 Room/SQLite 持久化；`Modem` 实例直接绑定在 `MainActivity` 生命周期内。
  - **接口探测隐患**：`describe()` 未排除 ADB (`FF/42/01`) 或 Diag (`IF0`)，在重启后探测 IF0 存在挂起风险。
  - **URC 分离简陋**：仅用 `urc.contains("+CMTI")` 子串匹配，未做行帧分流，无法处理 `RING`/`+CLIP`/`NO CARRIER` 等多类异步 URC。
  - **构建兼容**：Gradle 8.9 不支持本机 OpenJDK 25。
- **与当前硬件兼容性**: Android USB Host 方案直接兼容 Retroid Pocket Duo + QDC507。
- **复用评估（决定不直接 Fork，采用模块化重建 + 借鉴设计）**: 见 `DECISIONS.md`（ADR-001）。

---

## 2. MaVo (`moluncn/mavo`)
- **GitHub URL**: https://github.com/moluncn/mavo
- **分支 / Commit SHA**: `main` / `0443dfdaf8aec086fd76ba2ee9152fd908114524`
- **最后更新时间**: `2026-07-14 22:43:09 +0800`
- **开源许可证**: MIT License（主机 App、`module/mavo_pcm_bridge.c`、`CModemBridge`、`CUACProbe`）；`Resources/ModuleVoice/` 下的 `qdc507_aprv3.ko` 与 `qdc507_voice.ko` 为 **GPL-2.0**（源自 `the-modem-distro/quectel_eg25_kernel` commit `82ed00908b3e8efc3ff0de27d2b5a7c0524ecd7f`）。
- **技术栈**: Swift（macOS AppKit/SwiftUI）+ C（IOKit USB `CModemBridge`、CoreAudio `CUACProbe`、模块侧 ARMv7 C `mavo_pcm_bridge.c`）。
- **重点核查目录与资源（实测全部真实存在）**:
  - `Resources/ModuleVoice/qdc507_aprv3.ko`（36 KB, SHA-256 `3d82d3dec4f1e323201bba87156df9d41438e08314097353f2607f9117211d4a`）
  - `Resources/ModuleVoice/qdc507_voice.ko`（976 KB, SHA-256 `ed3821682d5309969a01c764192c83feff9669c61ef237c69475cd1619cf296c`）
  - `Resources/ModuleVoice/mavo-pcm-bridge.armv7`（17 KB, SHA-256 `88d47c15e61d1428a59c821fed804c2e6490e82859a085062f21966b58d167fc`）
  - `Resources/ModuleVoice/manifest.json`（`runtimeVersion: qdc507-3.18.44-voice-20260712.5`, `kernelRelease: 3.18.44`, `cardName: mdm9607-tomtom-i2s-snd-card`, required devices: `controlC0`, `pcmC0D4p/4c`, `pcmC0D5p`, `pcmC0D6c`）
  - `Resources/ModuleVoice/MODULE-REPORT.md`（注：该报告记录的是旧版 `qdc507_afe.ko`，与实际打包的 `qdc507_voice.ko` 存在文档滞后！）
  - `Sources/CModemBridge/ModemBridge.c`（1514 行，macOS IOKit USB 驱动层，目标 `0x2CA3:0x4006` / `0x2C7C:0x0125`，AT IF2 bulk IN `0x84`/OUT `0x03`，Voice IF1）
  - `Sources/CUACProbe/CUACProbe.c`（2396 行，macOS CoreAudio UAC1 8kHz 单声道环形缓冲桥接，**不可直接用于 Android**）
  - `module/mavo_pcm_bridge.c`（1118 行，MIT，动态加载模块内 `libql_lib_audio.so.1`，支持 `--voice-route-session` 路由 VoLTE 到 AFE + 开启 `/sys/class/android_usb/f_audio/audio_enable` + 打开 hostless `hw:0,4`，同时保留原始 `/dev/ttyGS0` 8kHz S16_LE PCM 桥接代码）
  - `Sources/MaVo/ModuleVoiceRuntime.swift`（完整模块语音生命周期：ADB 校验 `id -u == 0` & `uname -r == 3.18.44` → 推送至 `/tmp/mavo-call` → `insmod` → `alsaucm_test` ACDB VoLTE 校准 → `--voice-route-session` → 挂断后 `SIGTERM` + `audio_enable=0` + `/run/voc_svr` 发送 `T/T/B` 回滚，不热卸载内核模块防 DSP 竞态）
  - `Sources/MaVo/ADBProtocol.swift`（146 行极简直接 USB ADB 协议实现：`CNXN`/`OPEN`/`WRTE`/`OKAY`/`CLSE`/`SYNC`）
  - `Sources/MaVo/SMSPDUEncoder.swift` & `SMSPDUDecoder.swift`（完整 UCS2/GSM7 PDU 编解码与多片重组）
  - `Sources/MaVo/CallATParser.swift`（`ATD`/`+CLCC`/`RING`/`+CLIP`/`NO CARRIER`/`BUSY`/`NO ANSWER`/`AT+VTS` 行帧解析）
- **可复用 vs 需重写**:
  - 可借鉴（MIT 协议逻辑）：直连 USB ADB 协议、QDC507 语音运行时编排流程、PDU 编解码算法、Call AT/URC 状态机、模块侧 `mavo_pcm_bridge.c`。
  - 必须为 Android 重写：USB 传输（改 Android `UsbManager`）、音频桥接（改 Android `AudioRecord`/`AudioTrack` + USB Audio / Oboe / NDK，**绝不复用 macOS CoreAudio/IOKit**）。

---

## 3. DJOneHubNative (`cr-zhichen/DJOneHubNative`)
- **GitHub URL**: https://github.com/cr-zhichen/DJOneHubNative
- **分支 / Commit SHA**: `main` / `7194ae8cd71da34e0ab3727874dafb82ffd2537b`
- **最后更新时间**: `2026-09-16 22:49:20 +0800`
- **开源许可证**: **PolyForm Noncommercial License 1.0.0**（源自 `iniwex5/vohive`）
- **技术栈**: SwiftUI (macOS) + Go 后端（libusb）
- **核心研究价值（仅协议参考，不复制源码）**:
  - `docs/MODULE_RESEARCH.md` 完整实测验证了 QDC507GLEFM21 AT 音频指令现状：`AT+QPCMV=?` 返回 `(0,1),(0-2)`，但 `AT+QPCMV=1,0` / `1,2` / `AT+QDAI` / `AT+QCFG="usbaudio"` **全部返回 ERROR**（原厂裁剪写入）。
  - 证实 `AT+QCFG="usbcfg",<vid>,<pid>,<diag>,<nmea>,<at>,<modem>,<rmnet>,<adb>,<uac>` 中若误传 `0x2C7C,0x0125` 会改写枚举 ID，必须保留原 `0x2CA3,0x4006`。
  - 证实重启后探测 Diag IF0 可能导致 USB 同步探测挂起，AT 应优先聚焦 IF2/IF3，ADB 按 `FF/42/01` 匹配。
  - 证实新出厂模块可能 `ADB=0`，需通过旧式 8 位 `AT+QADBKEY?` 挑战派生密码授权并开启 ADB 位；以及检查 `AT+QCFG="ims",1` 与 `volte_disable=0`。
  - 证实模块 UAC 暴露独立输入设备 `AC Interface`（8kHz 单声道）与输出设备 `AS Interface`（8kHz 单声道）。

---

## 4. DJ4Hub (`WongLoki/DJ4Hub`)
- **GitHub URL**: https://github.com/WongLoki/DJ4Hub
- **分支 / Commit SHA**: `main` / `3914cd6e0ebe0866e9f962b7c975ba8fd3a36196`
- **最后更新时间**: `2026-09-13 19:30:42 +1200`
- **开源许可证**: **PolyForm Noncommercial License 1.0.0**（源自 `iniwex5/vohive`）
- **技术栈**: Go 后端（libusb + SQLite）+ SwiftUI 原生客户端 + Web 控制台
- **核心研究价值（仅协议与实测证据参考，不复制源码）**:
  - `docs/QDC507_AUDIO_RESEARCH.md` 提供了 **2026-09-12 真实双向语音通话实测记录**：在 `QDC507GLEFM21` + Linux `3.18.44` 上加载 MaVo 的 `qdc507_aprv3.ko`、`qdc507_voice.ko` 与 `mavo-pcm-bridge.armv7`，执行 `alsaucm_test` VoLTE 校准并临时在 sysfs 中向 USB functions 追加 `,audio` 后，通过 `AC Interface` 与 `AS Interface` 实现了短时清晰双向通话（两端均确认清晰）。
  - 揭示了一个极关键坑点：**仅写 `audio_enable=1` 不会让无 `audio` function 的模块枚举 UAC 声卡**，若当前 USB functions 为 `diag,serial,rmnet,ffs`（无 `audio`），需临时通过 sysfs 或合法 `usbcfg` 启用 `audio` function，并注意脱离 ADB 会话（`setsid`）防 USB 重枚举导致脚本终止。
  - `docs/QDC507_INITIALIZATION.md` 揭示另一关键坑点：若新模块 `AT+QCFG="ims"` 为 `0,0`，在仅支持 VoLTE 的运营商网络上拨号会直接返回 `ERROR`，需先检查/开启 `AT+QCFG="ims",1`。

---

## 5. DJOneHub-mac-enhanced (`rogerbush007-a11y/DJOneHub-mac-enhanced`)
- **GitHub URL**: https://github.com/rogerbush007-a11y/DJOneHub-mac-enhanced
- **分支 / Commit SHA**: `main` / `5b15eaec0e97dd9fa7b2abe54d0d1289c1e93054`
- **最后更新时间**: `2026-08-30 21:45:31 +0800`
- **开源许可证**: **PolyForm Noncommercial License 1.0.0**（源自 `iniwex5/vohive`；内部包含少量 MIT MaVo 适配）
- **技术栈**: Go 后端 + SwiftUI macOS App + Windows 实验端
- **核心研究价值（仅产品与业务参考，不复制源码）**:
  - 完整的电话/短信/通讯录/录音/配置备份交互流与安全边界（修改前自动备份、导入校验、失败回滚）。
  - 指出在移动设备上同时开启 UAC 时，移动操作系统可能错误把模块 UAC 当作默认系统音频输出，因此其提供“关闭 UAC 保留 ECM+AT”的移动模式开关（对 Android AudioManager 路由策略极具参考价值！）。
  - 提及模块侧保活服务（wake lock）以减少手机锁屏后的 ECM 断网。
