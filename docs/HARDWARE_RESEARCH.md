# 硬件调研档案 (`HARDWARE_RESEARCH.md`)

## 1. Retroid Pocket Duo（实机 ADB 确认，2026-11-24）
所有以下参数均通过 `adb` 在真实设备上直接采集，非推测数据：

| 项目 | 实机读取值 | 工程意义 |
| --- | --- | --- |
| 型号 / 制造商 | `Retroid Pocket Duo` / `Moorechip` | 目标设备确认 |
| 代号 / 硬件平台 | `sun` / `qcom` / SoC `CQ7790S` | Qualcomm Dragonwing Q-7790 |
| Android 版本 / SDK | Android `16` / API `36` | 需严格遵循 Android 16 前台服务、广播、PendingIntent 与权限规范 |
| CPU 架构 / 内核 | `arm64-v8a` / Linux `6.6.127-android15-8-maybe-dirty-4k` | 64-bit ARM64，NDK 目标优先 `arm64-v8a` |
| 主屏 (`Display Id=0`) | `local:4630947039571902866`, 物理 `1080x1920`（横屏逻辑 `1920x1080`）, 60/120Hz, 369 dpi | 上屏，适合展示状态、来电卡片、通话界面与短信正文 |
| 副屏 (`Display Id=2`) | `local:4630946671160541331`, 逻辑 `1280x960`（4:3）, 独立内部屏 | 下屏，适合拨号盘、快捷操作、键盘输入与联系人列表 |
| 多屏能力 | `android.software.activities_on_secondary_displays` | 系统原生支持副屏 Activity 与 `Presentation` |
| 触摸输入 | `fts_ts`（主屏）+ `fts_ts_sec`（副屏） | 双屏均具备独立触控 |
| 实体手柄 | `Device 10: Retroid Pocket Controller` (`KEYBOARD \| GAMEPAD \| JOYSTICK \| EXTERNAL`) | 可通过标准 Android `KeyEvent` 接收按键 |
| 翻盖霍尔开关 | `Device 5: hall_switch` (`SWITCH`), `dumpsys power` 证实 `mLastSleepReason=lid_switch` | 合盖直接触发系统进入 `Asleep` + `AutoSuspend` |
| USB Type-C 端口 | `port0`, `supported_modes=dual, audio_acc`, `role_combinations=[source/host, sink/device]`, `android.hardware.usb.host` | 确认支持 USB Host OTG；同时具备 OTG 供电角色，但实际带载 QDC507 峰值发射电流仍需实机验证 |
| 音频子系统 | AIDL Audio HAL (`kera-mtp-snd-card`), `low_latency`, `audio.pro`, 3.5mm `Headset Jack`, 蓝牙/BLE Audio；输出含 `earpiece(48k)` & `speaker(48k stereo)`；输入含 `built_in_mic(bottom)` & `built_in_back_mic(back)`（均支持 8k/16k/48k PCM16） | 本地音频能力完备；但接入 QDC507 UAC 后 Android 是否允许并发独立路由必须在 Phase 4 实机验证 |
| 蜂窝与 Telecom | 无 `android.hardware.telephony`；`dumpsys telecom` 返回 `Can't find service: telecom` | **不能依赖系统 Telecom/ConnectionService** |
| 网络能力 | `EthernetNetworkFactory` 存在于 `dumpsys connectivity` | Phase 7 无 Root USB CDC-ECM 4G 上网具备高度可行性 |
| 电池 | 实机 `Charge counter: 6951000` uAh（100% 满电，约 6000–7000mAh 级别 Li-ion） | 续航基础良好，后续实测待机与通话功耗 |

## 2. 大疆第一代 4G 增强图传模块（DJI Cellular Dongle Gen 1 / QDC507）
当前环境尚未通过 USB 接入 QDC507（Retroid Pocket Duo 的唯一 USB-C 口正连接 Mac 用于有线 ADB）。以下为来自参考仓库实机记录的已知事实，**接入实机后将逐项复核**：

| 项目 | 参考仓库实机确认值 | 待本机实测核验项 |
| --- | --- | --- |
| 芯片平台 | Qualcomm MDM9207（FCC ID `2A2TS2021IG830`） | `AT+CGMM` / `ATI` |
| 默认 USB VID:PID | `0x2CA3:0x4006`（产品名 `Baiwang`） | 实际 USB 描述符 |
| 兼容 USB VID:PID | `0x2C7C:0x0125`（误改 `usbcfg` 后可能出现） | 兼容匹配 |
| 参考固件版本 | `QDC507GLEFM21`（Linux 内核 `3.18.44` ARMv7） | `AT+CGMR` + 模块 `uname -r` |
| 呼叫控制 AT | `ATD<num>;`、`ATA`、`ATH`、`AT+CLCC`、`AT+CLIP`、`AT+CEER`、`AT+VTS` 均可用；部分新卡/新模块需确认 `AT+QCFG="ims",1` | 实机 AT 响应 |
| 标准 AT 音频指令 | `AT+QPCMV=?` 有响应，但 `AT+QPCMV=1,0/1,2`、`AT+QDAI`、`AT+QCFG="usbaudio"` 在 QDC507 原厂固件均返回 `ERROR` | 实机只读探测验证 |
| 语音运行环境 | 原厂无 ALSA 声卡；需开启 ADB 后临时向 `/tmp` 推送并 `insmod` 匹配 `3.18.44` 的 `qdc507_aprv3.ko` + `qdc507_voice.ko`，运行 `alsaucm_test` 加载 VoLTE ACDB 校准，启动 `mavo-pcm-bridge.armv7 --voice-route-session` 路由至 UAC1 (`AC Interface` / `AS Interface`, 8kHz mono) | Phase 4 受控实机验证 |
| USB 网络模式 | `AT+QCFG="usbnet"`：`0`=QMI/RMNET（默认）、`1`=CDC-ECM、`2`=MBIM、`3`=RNDIS | Phase 7 验证 |
