# 测试报告 (`TEST_REPORT.md`)

> 严格区分**模拟/单元测试**与**实机硬件测试**；绝不编造未实测项。

## 1. 自动化单元测试与静态检查（Phase 1 — 2026-11-24）

| 项目 | 命令 | 状态 | 实际结果 |
| --- | --- | --- | --- |
| Debug APK 构建 | `./gradlew assembleDebug` | `PASS` | `BUILD SUCCESSFUL`，产物 `app/build/outputs/apk/debug/app-debug.apk` (8.5 MB) |
| JVM 单元测试 | `./gradlew testDebugUnitTest` | `PASS` | **12 / 12 全部通过**（0 失败，0 跳过） |
| Android Lint 静态检查 | `./gradlew lintDebug` | `PASS` | **0 Error**（修复 `AudioManager.getDevices` `WrongConstant` 后通过） |

### 单元测试覆盖明细（模拟测试）
1. `UsbInterfaceClassifierTest`（2 项）：`2CA3:4006` / `2C7C:0125` 匹配；严格排除 ADB (`FF/42/01`)、UAC (`01`)、CDC (`02`/`0A`)、存储 (`08`)、Diag (`IF0`)，并按 `IF2 → IF3 → IF1` 排序。
2. `AtProtocolParsersTest`（4 项）：非幂等指令禁止重试守卫（`ATD`/`AT+CMGS`/`AT+VTS`/`AT+CFUN=1,1`/`AT+QCFG` 写）；USB 拆包行帧器与短信 `>` 提示符检测；URC 与在途查询响应分流（`+CEREG: 5` vs `+CEREG: 0,1`、`RING`、`+CLIP`、`+CMTI`、`NO CARRIER`）；`+CSQ`/`+COPS?`(含 UCS2 运营商解码)/`+CPIN?`/`+CEREG?`/`+QCFG` 解析。
3. `AtEngineAndControllerTest`（5 项）：命令执行途中分片 URC 实时分流无污染；短信 `>` 提示符与 `Ctrl+Z` 载荷提交；`ATD` 失败零自动重试；模拟 QDC507 完整状态机（`DISCONNECTED → READY` 及拔出释放）；未授权（`PERMISSION_REQUIRED`）与无 SIM 卡（`SIM_NOT_READY`）分支。
4. `PiiRedactorTest`（1 项）：手机号、IMEI、`ATD` 拨号串、短信验证码自动脱敏。

---

## 2. Retroid Pocket Duo 实机测试（无外接 QDC507 基线，2026-11-24）

测试设备：`Retroid Pocket Duo` (`1b0b84fc`) | Android 16 (API 36) | SoC `CQ7790S`

| 用例 / 检查项 | 状态 | 实际结果与实机日志证据 |
| --- | --- | --- |
| APK 安装与启动 | `PASS` | `adb install -r app-debug.apk` → `Success`；`am start -n com.duophone.app/.MainActivity` 无崩溃 |
| Android 16 前台服务 (`ModemService`) | `PASS` | `dumpsys activity services com.duophone.app` 确认 `isForeground=true foregroundId=1001 types=0x00000010 (connectedDevice)`，`targetSdkVersion=36` |
| `UI-001` 实机双屏与手柄识别 | `PASS` | 实机 `duophone-latest.log` 确认：`15:52:14.203 I/DUO_DISPLAY capture_host_hw: model=Retroid Pocket Duo sdk=36 displays=2 gamepads=1 usb=0` |
| USB 广播接收器注册（Android 16） | `PASS` | `15:52:14.275 I/DUO_USB receivers_registered: USB attach/detach and permission receivers active` |
| `USB-001` ~ `USB-007`（QDC507 实机热插拔与 AT 握手） | `BLOCKED` | 外部阻塞：当前掌机唯一 USB-C 接口连接开发机用于有线 ADB（`usb=0`），待切无线 ADB（`192.168.17.64:5555`）并插入 QDC507 模块后实测 |
| `SMS-001` ~ `SMS-010` | `NOT_TESTED` | 待 Phase 2 开发与实机 SIM 卡测试 |
| `CALL-001` ~ `CALL-010` | `NOT_TESTED` | 待 Phase 3 开发与授权测试号码实测 |
| `AUDIO-001` ~ `AUDIO-010` | `NOT_TESTED` | 待 Phase 4 双向语音专项开发与实测 |
| `SYS-001` ~ `SYS-008` | `NOT_TESTED` | 待 Phase 5 后台与合盖功耗实测 |
