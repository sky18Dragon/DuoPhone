# 测试报告 (`TEST_REPORT.md`)

> 严格区分**模拟/单元测试**与**实机硬件测试**；绝不编造未实测项。

## 1. 自动化单元测试与静态检查（Phase 1–6 / P1 终端功能 — 2026-11-24）

| 项目 | 命令 | 状态 | 实际结果 |
| --- | --- | --- | --- |
| Debug APK 构建 | `./gradlew assembleDebug` | `PASS` | `BUILD SUCCESSFUL`，产物 `app/build/outputs/apk/debug/app-debug.apk` (`9,419,147` bytes / 8.98 MiB，含 Phase 5–6 P1 通讯录/铃声/快捷通知/耳机热插拔路由/后台保活) |
| JVM 单元测试 | `./gradlew testDebugUnitTest` | `PASS` | **47 / 47 全部通过**（16 个测试套件，0 失败，0 跳过） |
| Android Lint 静态检查 | `./gradlew lintDebug` | `PASS` | **0 Error**（退出码 0；已检查 API 26–36 通知/音频/权限合规） |

### 单元测试覆盖明细（模拟测试，47 / 47 PASS）
1. `UsbInterfaceClassifierTest`（2 项）：`2CA3:4006` / `2C7C:0125` 匹配；严格排除 ADB (`FF/42/01`)、UAC (`01`)、CDC (`02`/`0A`)、存储 (`08`)、Diag (`IF0`)，并按 `IF2 → IF3 → IF1` 排序。
2. `PiiRedactorTest`（1 项）：手机号、IMEI、`ATD` 拨号串、短信验证码自动脱敏。
3. `AtProtocolParsersTest`（4 项）：非幂等指令禁止重试守卫（`ATD`/`AT+CMGS`/`AT+VTS`/`AT+CFUN=1,1`/`AT+QCFG` 写）；USB 拆包行帧器与短信 `>` 提示符检测；URC 与在途查询响应分流；`+CSQ`/`+COPS?`(含 UCS2 运营商解码)/`+CPIN?`/`+CEREG?`/`+QCFG` 解析。
4. `AtEngineAndControllerTest`（5 项）：命令执行途中分片 URC 实时分流无污染；短信 `>` 提示符与 `Ctrl+Z` 载荷提交；`ATD` 失败零自动重试；模拟 QDC507 完整状态机（`DISCONNECTED → READY` 及拔出释放）；未授权（`PERMISSION_REQUIRED`）与无 SIM 卡（`SIM_NOT_READY`）分支。
5. `SmsPduCodecTest`（4 项）：英文 GSM 7-bit 单条与长短信 PDU 编码；中文 UCS2 单条与长短信（含 Emoji UTF-16 代理对防截断）；3GPP GSM 7-bit 与 UCS2 多片乱序 SMS-DELIVER 解码重组；验证码提取与号码规范化。
6. `SmsServiceControllerTest`（2 项）：`AT+CMGL=4` 长短信同步重组及二次同步去重；中文长短信双分片 `AT+CMGS` 发送与失败零自动重试校验。
7. `CallAtParserTest`（3 项）：拨号号码规范化与注入防护、DTMF `AT+VTS` 校验、`+CLCC` 多行语音过滤与 `+CEER` 错误解析。
8. `CallControllerTest`（3 项）：去电 `ATD` → `+CLCC` active → `NO CARRIER` 远端挂断与记录落盘；来电 `RING` + `+CLIP` → `ATA` 接听 / `ATH` 拒接；`ATD` `ERROR` + `+CEER` 明确失败原因且零重试。
9. `GamepadKeyRouterTest`（2 项）：双屏显示选择与单屏降级判断；L1/R1/START/SELECT 与基于通话状态的 A/B/X/Y 手柄键路由。
10. `AdbWireProtocolTest`（3 项）：ADB USB 24B 小端包头/校验和/魔数反码编解码与损坏包拦截、`sh -c` 退出码封装解析、`sync:` `SEND`/`DATA`/`DONE`/`OKAY`/`FAIL` 传输帧。
11. `Qdc507VoiceRuntimeTest`（3 项）：缺失或 SHA-256 不匹配外部资源拦截（`ASSETS_MISSING`，不发送任何推包/加载指令）、只读环境探测（内核/`aprv3`/`voice`/`/dev/snd`/跨括号 `/proc/<pid>/stat` 启动时间戳）、以及安全停机回滚（带进程启动时钟核验的 `SIGTERM` + `/run/voc_svr` `T/T/B`，严禁 `rmmod`）。
12. `PcmResamplerTest`（5 项）：`8kHz → 48kHz` 跨帧状态线性上采样连续性、`48kHz → 8kHz` 下采样频域/幅度保持、同采样率直通与 `S16_LE` 编解码往返、`PcmLevelMeter` dBFS 与全零静音识别、以及 `PcmVoiceConditioner` 直流抑制 / `+9.5 dB` 语音频带软限幅放大 / 回声衰减验证。
13. `Qdc507ConfigManagerTest`（3 项）：`$1$` MD5-crypt 向量（`$1$12345678$0jXKXQwSwMxYoegx0S.I.1`）与 15 字符 `QADBKEY` 派生、`UsbCfgTuple` 原厂 VID/PID 保留与 `AT+QADBKEY` 日志脱敏、无备份或通话中拦截与受控写入/回读/还原流程。
14. `ContactPhoneMatcherTest`（3 项）：国际区号/分隔符号码规范化与 `matchKey` 后 11 位匹配、短号防误匹配、本地联系人优先覆盖与系统重复联系人去重合并。
15. `AudioRoutePolicyTest`（3 项）：蓝牙 / 3.5mm 有线耳机 / 扬声器优先级与用户覆写选择、热插拔实时切换策略、仅免提扬声器启用 NLP 回声衰减开关。
16. `ModemKeepaliveTest`（1 项）：验证 `ModemController` 定时 AT 心跳在活跃通话期间暂停，且连续 2 次心跳失败触发 `RECOVERING` 自动恢复。

---

## 2. Retroid Pocket Duo 实机测试

### 2.1 Phase 1 实机基线（2026-11-24 15:52–15:53，`1b0b84fc`）

| 用例 / 检查项 | 状态 | 实际结果与实机日志证据 |
| --- | --- | --- |
| APK 安装与启动 | `PASS` | Phase 1 版本 `adb install -r app-debug.apk` → `Success`；`MainActivity` 启动无崩溃 |
| Android 16 前台服务 (`ModemService`) | `PASS` | `dumpsys activity services com.duophone.app` 确认 `isForeground=true foregroundId=1001 types=0x00000010 (connectedDevice)`，`targetSdkVersion=36` |
| `UI-001` 实机双屏与手柄识别 | `PASS` | `duophone-latest.log` 确认：`model=Retroid Pocket Duo sdk=36 displays=2 gamepads=1 usb=0` |
| 手柄 L1/R1 页签切换实测 | `PASS` | `duophone-latest.log` 记录 `D/DUO_INPUT gamepad_r1 / gamepad_l1` 切换至 `AT_CONSOLE` / `DIAGNOSTICS` |
| USB 广播接收器注册（Android 16） | `PASS` | `I/DUO_USB receivers_registered: USB attach/detach and permission receivers active` |

### 2.2 Phase 2 / Phase 3 / 双屏实机部署与 QDC507 硬件验收（2026-11-24 19:46–20:50，无线 ADB + 实机日志）

| 用例 / 检查项 | 状态 | 实际结果与实机日志证据 |
| --- | --- | --- |
| 无线 ADB 安装与双屏拉起（含横屏拨号布局修复） | `PASS` | `20:41` `adb -s 192.168.1.21:39513 install -r app-debug.apk` → `Success`；`MainActivity`（主屏 `Display#0`）与 `SecondaryDeckActivity`（下屏 `Display#2`）均可正常点亮与交互 |
| `USB-001` ~ `USB-003`（QDC507 识别、授权与 AT 握手） | `PASS` | `duophone-latest.log` 实机读取：`Baiwang / QDC507`，`0x2CA3:0x4006`（`ifaces=5`），固件 `QDC507GLEFM21`，锁定 AT 口 `IF#2`（`236ms`），China Broadnet 4G LTE (`+CEREG: 0,1`, `-81 dBm`)，`+QCFG: "ims",0,1`，`+QCFG: "usbcfg",0x2CA3,0x4006,1,1,1,1,1,0,0`（当前 `adb=0, uac=0`），`+QPCMV: (0,1),(0-2)` |
| `SMS-001` ~ `SMS-004`（真实 SIM 卡收发短信） | `PASS` | 用户确认真实收发短信正常；日志记录 `AT+CMGF=0` / `AT+CNMI=2,1,0,0,0` / `AT+CMGL=4` 返回 `rawPdus=8 assembled=4` |
| `UI-002`（双屏横屏拨号布局重构实机验证） | `PASS` | 实机测试主屏与下屏（`Display#2`）横屏拨号按键及右侧 `拨出 (ATD)` 完整可见，顺利发起多通真实拨号与通话中 DTMF 操作 |
| `CALL-001` ~ `CALL-007`（真实运营商去电、来电、DTMF 与挂断） | `PASS` | `duophone-latest.log` 实机记录：`ATD` 去电成功并正确过滤 `+CLCC` 两条 `mode=1` 数据通道只保留语音通话；通话中发送 `AT+VTS="2/5/9/7/5/3/0"` 全部 `OK`；`ATH` 主动挂断成功；呼入 `RING` + `+CLIP` 号码显示正常 → `ATA` 接听 → 对端挂断 `NO CARRIER` 正常回退空闲 |
| `CALL-008` ~ `CALL-010`（异常 `+CEER` / 锁屏来电）、`USB-004` ~ `USB-007`、`SMS-005` ~ `SMS-010`、`UI-003` ~ `UI-004` | `NOT_TESTED` | 待补测热插拔 20 次稳定性、锁屏通知细节及失败错误码分支 |
| `AUDIO-001` ~ `AUDIO-004`（UAC 识别与双向 VoLTE 语音互通 — 独立验收项） | `PASS` | `22:08:55` 实机最终复测通过：`AT+QADBKEY` 解锁与 `usbcfg` 切为 `1,1`（保留 `0x2CA3:0x4006`）→ `/tmp/duophone-voice` 加载 `aprv3`/`voice` + ACDB VoLTE 校准 → `Baiwang` UAC 双向声卡（`8000 Hz`）识别；日志实证 `source=VOICE_COMMUNICATION aec=true ns=true agc=true`、`USAGE_ALARM` 上行 + `PcmVoiceConditioner`（`80–3400Hz` 带通 / `+9.5 dB` 软限幅 / 扬声器 NLP 回声衰减），**双路并发采集均 `zero=0` (`not silenced`)**，连续两通真实通话 `PID=1461` 均干净启停（`bridge_stopped` + `voice_route_stopped`）、零 ANR；用户实机确认：“对方听您说话音量明显清晰放大，回音明显减轻” |
| `AUDIO-005` ~ `AUDIO-010`（30min 稳定性 / 蓝牙有线切换 / 重连恢复） | `NOT_TESTED` | 代码/单测已完成；用户确认蓝牙/3.5mm 有线耳机项延后实测，待专项验收 |
| `SYS-003` ~ `SYS-005`（合盖 `hall_switch` / 灭屏 USB 供电与 AT 心跳保活） | `PASS` | `23:23:55`、`23:26:15`、`23:27:29` 三次合盖/灭屏日志实证：`displayId=0/2 state=1`，`I/DUO_POWER screen_off: modem=READY usbAttached=true`，灭屏期间 `23:27:31` 定时 `AT -> OK (5ms)` 持续存活；开盖秒恢复原界面（`state=2`、`modem=READY`）且全程零断连 |
| `SYS-001` ~ `SYS-002`, `SYS-006` ~ `SYS-008`（8h 长时待机 / 连续压测） | `NOT_TESTED` | 待后续长时续航压测 |
| P1 新增交互（来电铃声/振动、通话期 WakeLock、通知栏短信直复、通讯录授权与本地保存） | `PASS` | `23:24`–`23:25` 实机日志实证：`READ_CONTACTS granted=true`、`contacts_reload local=1`；真机呼入触发 `wakelock_acquire`、`ringtone_start: out=2:Retroid Pocket Duo` → `ATA` 接听停铃并启动 `PID=1493` 全双工语音（`zero=0`）→ 对端挂断 `NO CARRIER` 释放 `WakeLock` 与音频路由；收到新短信 `+CMTI: "ME",8` 后通过通知栏直复 `notif_action_reply` → `AT+CMGS=18` → `send_success: Sent 1/1 segments` |
