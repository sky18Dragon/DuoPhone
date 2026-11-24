# Retroid Pocket Duo 兼容性实机报告 (`RETROID_DUO_COMPATIBILITY.md`)

实机采集日期：2026-11-24 | 设备序列号：`1b0b84fc` | 指纹：`qti/sun/sun:16/BQ2A.260427.001-BP2A.250705.008/eng.RPDuo.20261114.082341:user/dev-keys`

## 1. 已证实能力（实机 ADB 证据）
| 能力域 | 实机结论 | 证据摘要 |
| --- | --- | --- |
| **USB Host / OTG** | ✅ 支持 | `feature:android.hardware.usb.host`；`dumpsys usb` 确认 `port0` `supported_modes=dual, audio_acc`，支持 `{power_role=source, data_role=host}` |
| **双屏幕独立暴露** | ✅ 独立双 Display | `Display Id=0` (`1920x1080` 横屏, 60/120Hz, `fts_ts`) 与 `Display Id=2` (`1280x960` 4:3, `fts_ts_sec`) 均为 `type=INTERNAL`，具备 `android.software.activities_on_secondary_displays` |
| **音频硬件** | ✅ 完整 | `earpiece`、`speaker`、`built_in_mic`、`built_in_back_mic`、`Headset Jack` (3.5mm)、蓝牙 A2DP/SCO/BLE Audio、`low_latency` & `audio.pro` |
| **实体手柄** | ✅ 标准输入设备 | `Device 10: Retroid Pocket Controller` (`KEYBOARD \| GAMEPAD \| JOYSTICK \| EXTERNAL`) |
| **翻盖休眠开关** | ✅ 霍尔开关接入 PowerManager | `Device 5: hall_switch`；`dumpsys power` 证实 `mLastSleepReason=lid_switch`, `mUseAutoSuspend=true` |
| **USB 以太网框架** | ✅ 存在 | `dumpsys connectivity` 存在 `EthernetNetworkFactory` |
| **Android Telecom 服务** | ❌ **系统未运行** | `dumpsys telecom` 返回 `Can't find service: telecom`（尽管 `pm list features` 列出了 `android.software.telecom`） |

## 2. 关键兼容性注意事项与待实机验证项
1. **无线 ADB 调试必要性**：Retroid Pocket Duo 仅有一个底部 USB Type-C 接口（`port0`）。实机连接 QDC507 时需通过无线 ADB（当前 Wi-Fi IP `192.168.17.64`，`adb tcpip 5555`）采集日志。
2. **OTG 供电能力（待实测）**：QDC507 在弱信号发射短信/通话时峰值电流可达 800mA–1.5A。需实测直连是否稳定，若出现突发断连则建议使用带 PD 供电透传的 OTG Hub 并记录电压/电流特征。
3. **合盖休眠与 USB Host 供电（待实测）**：`hall_switch` 触发 `lid_switch` 进入 `Asleep` + `AutoSuspend`。合盖后 `port0` 是否保持 5V VBUS 供电及 USB Host 唤醒能力必须在 Phase 5 实测。
4. **手柄按键 KeyCode 实测（待 Phase 6 实测）**：需通过实机按键事件采集确认 Retroid Pocket Controller 的 A/B/X/Y、DPAD、L1/R1、START、SELECT 具体 `KeyEvent.KEYCODE_*`（注意任天堂布局与 Xbox 布局切换设置）。
5. **Android 16 (API 36) 行为约束**：
   - 前台服务必须声明合法 `foregroundServiceType` 与对应权限。
   - `PendingIntent` 若含 `FLAG_MUTABLE` 必须使用显式 `Intent`（`setPackage`）。
   - 动态注册系统广播（USB ATTACHED/DETACHED）需使用 `RECEIVER_EXPORTED`。
