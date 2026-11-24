# Android 双向音频桥接设计 (`AUDIO_BRIDGE.md`)

## 1. 双向音频链路目标
- **上行 (TX)**：Pocket Duo 麦克风（内置 mic / 3.5mm 耳机麦 / 蓝牙 SCO/BLE 麦）→ Android `AudioRecord` → 48k/16k→8k 重采样 & 增益/静音控制 → QDC507 音频输入 → 蜂窝网络。
- **下行 (RX)**：蜂窝网络 → QDC507 音频输出 (8kHz mono S16_LE) → 8k→48k/16k 重采样 & 抖动缓冲 → Android `AudioTrack` → Pocket Duo 扬声器 / 听筒 / 3.5mm 耳机 / 蓝牙耳机。

## 2. Android 16 (Retroid Pocket Duo) 实机音频特征
来自 `dumpsys media.audio_policy` 实机输出：
- `Config source: AIDL HAL`
- 内置输出：`earpiece`（48kHz mono PCM16）、`speaker`（48kHz stereo PCM16）
- 内置输入：`built_in_mic` (`@:bottom`)、`built_in_back_mic` (`@:back`)，均原生支持 `8000, 16000, 48000 Hz` PCM16！
- `android.media.audio.Flags.concurrentAudioRecordBypassPermission: false`（普通应用并发多路 `AudioRecord` 受 Android 策略约束，必须实机验证！）。

## 3. 五级递进实现路线（严格按非 Root、低延迟、稳定性排序）

### 方案 A：Android 原生 Audio API 独立设备路由（UAC1）
- 使用 `AudioManager.getDevices(GET_DEVICES_INPUTS / OUTPUTS)` 查找 QDC507 的 `TYPE_USB_DEVICE` / `TYPE_USB_HEADSET`（`AC Interface` / `AS Interface`）。
- 4 条音频流并行：
  1. `AudioRecord` (preferred = `BUILTIN_MIC` / `BLUETOOTH_SCO` / `WIRED_HEADSET`) → 上行采样
  2. `AudioTrack` (preferred = QDC507 UAC Output) → 写模块上行
  3. `AudioRecord` (preferred = QDC507 UAC Input) → 读模块下行
  4. `AudioTrack` (preferred = `BUILTIN_SPEAKER` / `EARPIECE` / `BLUETOOTH_SCO` / `WIRED_HEADSET`) → 本地播放
- **核心风险（必须实机验证）**：Android AudioPolicy 在接入 USB Audio 时常自动将默认系统路由切到 USB Audio，且非特权应用同时开启两个 `AudioRecord`（内置麦 + USB 麦）可能被静音其中一路（Android Audio Sharing / Concurrency Policy）！

### 方案 B（极高潜力推荐备选）：USB Bulk Raw PCM 桥接协议（`/dev/ttyGS0` ↔ USB IF1）
- 利用模块侧 `mavo_pcm_bridge`（MIT 源码可完全自主定制）通过 USB Interface 1 Bulk Endpoint 直接收发 8kHz mono S16_LE PCM，**不启用 USB Audio Class (`audio` function)**！
- **巨大优势**：
  1. Android 只需要 **1 路 `AudioRecord` + 1 路 `AudioTrack`**（均走本机麦克风/扬声器/蓝牙/3.5mm），**100% 规避 Android 双 `AudioRecord` 并发限制与 USB Audio 抢占系统音量/游戏声音的问题！**
  2. 原生支持 `AcousticEchoCanceler` (AEC)、`NoiseSuppressor` (NS)、`AutomaticGainControl` (AGC) 绑定在本地 `VOICE_COMMUNICATION` AudioSession 上，自带硬件/系统级回声消除！
  3. 仅需 Android USB Host API，零 Root。
- **待实机验证**：在 QDC507 上实测 `mavo-pcm-bridge.armv7` 通过 `/dev/ttyGS0` 与 `hw:0,4` / `hw:0,5/6` 桥接的实机丢包率、延迟与音质（注：MaVo 曾因旧 `hw:0,0` 路径问题转向 UAC，若需通过 ttyGS0 桥接，需评估 `hw:0,5` playback + `hw:0,6` capture 配合 `hw:0,4` hostless route 的组合实测）。

### 方案 C：JNI / NDK USB Audio Isochronous 或 Oboe 低延迟桥接
- 若方案 A 受限且模块只能走 UAC1，使用 NDK (`ndk 28.2.13676358`) + Android `UsbDeviceConnection.fileDescriptor` 配合 libusb / `usbdevfs` 直接驱动 UAC1 Isochronous 端点，同时本地用 Oboe/AAudio 进行 `VOICE_COMMUNICATION` 采集与播放（仍无需 Root！）。

### 方案 D / E：外置桥接硬件 / 系统级 Audio HAL
- 仅在 A/B/C 均被实机证实不可行时再行评估。

## 4. 音频质量保障指标
- 跨帧连续线性/多相重采样（8kHz ↔ 48kHz / 16kHz），消除帧边界爆音。
- 下行有界抖动缓冲区（例如上限 200–400ms），避免时钟漂移导致延迟无限累积。
- 独立 `AUDIO_MODE_IN_COMMUNICATION` + AEC/NS 启用 + 软削波保护（Int16 饱和限幅）。
- 蓝牙 SCO/LE Audio 及 3.5mm 耳机插拔动态监听并平滑重建本地路由，不挂断通话。
