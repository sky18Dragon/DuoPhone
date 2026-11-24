# 测试报告 (`TEST_REPORT.md`)

> 严格区分**模拟/单元测试**与**实机硬件测试**；绝不编造未实测项。

## 1. 自动化单元测试与静态检查（Phase 1–3 + 双屏/手柄 — 2026-11-24）

| 项目 | 命令 | 状态 | 实际结果 |
| --- | --- | --- | --- |
| Debug APK 构建 | `./gradlew assembleDebug` | `PASS` | `BUILD SUCCESSFUL`，产物 `app/build/outputs/apk/debug/app-debug.apk` (8.8 MB) |
| JVM 单元测试 | `./gradlew testDebugUnitTest` | `PASS` | **26 / 26 全部通过**（9 个测试套件，0 失败，0 跳过） |
| Android Lint 静态检查 | `./gradlew lintDebug` | `PASS` | **0 Error**（修复 `SecondaryDeckActivity` API 30 `display` `NewApi` 后通过） |

### 单元测试覆盖明细（模拟测试，26 / 26 PASS）
1. `UsbInterfaceClassifierTest`（2 项）：`2CA3:4006` / `2C7C:0125` 匹配；严格排除 ADB (`FF/42/01`)、UAC (`01`)、CDC (`02`/`0A`)、存储 (`08`)、Diag (`IF0`)，并按 `IF2 → IF3 → IF1` 排序。
2. `PiiRedactorTest`（1 项）：手机号、IMEI、`ATD` 拨号串、短信验证码自动脱敏。
3. `AtProtocolParsersTest`（4 项）：非幂等指令禁止重试守卫（`ATD`/`AT+CMGS`/`AT+VTS`/`AT+CFUN=1,1`/`AT+QCFG` 写）；USB 拆包行帧器与短信 `>` 提示符检测；URC 与在途查询响应分流；`+CSQ`/`+COPS?`(含 UCS2 运营商解码)/`+CPIN?`/`+CEREG?`/`+QCFG` 解析。
4. `AtEngineAndControllerTest`（5 项）：命令执行途中分片 URC 实时分流无污染；短信 `>` 提示符与 `Ctrl+Z` 载荷提交；`ATD` 失败零自动重试；模拟 QDC507 完整状态机（`DISCONNECTED → READY` 及拔出释放）；未授权（`PERMISSION_REQUIRED`）与无 SIM 卡（`SIM_NOT_READY`）分支。
5. `SmsPduCodecTest`（4 项）：英文 GSM 7-bit 单条与长短信 PDU 编码；中文 UCS2 单条与长短信（含 Emoji UTF-16 代理对防截断）；3GPP GSM 7-bit 与 UCS2 多片乱序 SMS-DELIVER 解码重组；验证码提取与号码规范化。
6. `SmsServiceControllerTest`（2 项）：`AT+CMGL=4` 长短信同步重组及二次同步去重；中文长短信双分片 `AT+CMGS` 发送与失败零自动重试校验。
7. `CallAtParserTest`（3 项）：拨号号码规范化与注入防护、DTMF `AT+VTS` 校验、`+CLCC` 多行语音过滤与 `+CEER` 错误解析。
8. `CallControllerTest`（3 项）：去电 `ATD` → `+CLCC` active → `NO CARRIER` 远端挂断与记录落盘；来电 `RING` + `+CLIP` → `ATA` 接听 / `ATH` 拒接；`ATD` `ERROR` + `+CEER` 明确失败原因且零重试。
9. `GamepadKeyRouterTest`（2 项）：双屏显示选择与单屏降级判断；L1/R1/START/SELECT 与基于通话状态的 A/B/X/Y 手柄键路由。

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

### 2.2 Phase 2 / Phase 3 / 双屏新版实机验证与 QDC507 硬件验收

| 用例 / 检查项 | 状态 | 说明与阻塞原因 |
| --- | --- | --- |
| Phase 2/3/双屏 APK (`8.8 MB`) 真机安装与 `UI-002`~`UI-004` 实机交互 | `BLOCKED` | 18:11 检查时 `1b0b84fc` 已断开 ADB 连接，且用户指示“暂停安装操作”；待重新连入设备并恢复安装后实测 |
| `USB-001` ~ `USB-007`（QDC507 实机热插拔与 AT 握手） | `BLOCKED` | 外部阻塞：QDC507 4G 模块尚未接入 |
| `SMS-001` ~ `SMS-010`（真实 SIM 卡收发短信） | `BLOCKED` | 外部阻塞：QDC507 4G 模块与 SIM 卡尚未接入 |
| `CALL-001` ~ `CALL-010`（真实运营商呼叫控制） | `BLOCKED` | 外部阻塞：QDC507 4G 模块、SIM 卡与授权测试号码尚未接入 |
| `AUDIO-001` ~ `AUDIO-010`（双向语音通话） | `NOT_TESTED` | 待 Phase 4 双向语音专项开发与实测 |
| `SYS-001` ~ `SYS-008`（后台稳定性与合盖功耗） | `NOT_TESTED` | 待 Phase 5 实测 |
