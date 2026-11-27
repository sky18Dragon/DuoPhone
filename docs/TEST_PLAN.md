# 测试计划与验收用例清单 (`TEST_PLAN.md`)

测试状态统一标记：`PASS`（实测通过）、`FAIL`（实测失败）、`BLOCKED`（外部阻塞）、`NOT_TESTED`（未测）。模拟测试与实机测试分开记录。

## 1. USB 通信（Phase 1）
- **USB-001**：正确识别 QDC507（`2CA3:4006` / `2C7C:0125`）并展示完整 USB 接口描述符。
- **USB-002**：正确申请 USB 权限，拒绝与允许分支均无崩溃。
- **USB-003**：安全跳过 ADB/UAC/存储接口，自动定位 AT 口并完成 `AT` 握手。
- **USB-004**：模块拔出后正确释放 `UsbDeviceConnection` 与协程资源。
- **USB-005**：重新插入后自动重连并恢复状态机至 `READY`。
- **USB-006**：连续热插拔 20 次无崩溃、无句柄泄漏。
- **USB-007**：供电异常/USB IO 超时后正确转入 `RECOVERING` / `ERROR` 并可恢复。

## 2. 短信（Phase 2）
- **SMS-001 ~ SMS-006**：英文/中文/长短信的接收与发送（PDU 编码、分片、重组）。
- **SMS-007 ~ SMS-008**：后台与锁屏状态接收短信并弹出通知。
- **SMS-009 ~ SMS-010**：本地 Room/SQLite 持久化与模块重连后补拉未处理短信。

## 3. 电话呼叫控制（Phase 3 — 独立验收项）
- **CALL-001 ~ CALL-004**：拨号、接听、拒接、挂断（使用用户授权测试号码）。
- **CALL-005 ~ CALL-008**：来电号码显示 (`+CLIP`)、`+CLCC` 状态同步、对方挂断恢复、呼叫失败 `AT+CEER` 明确原因。
- **CALL-009 ~ CALL-010**：后台与锁屏来电通知。

## 4. 双向语音通话（Phase 4 — 独立验收项）
- **AUDIO-001**：识别模块语音接口（UAC1 或 USB Bulk PCM）。
- **AUDIO-002 / AUDIO-003 / AUDIO-004**：分别验证下行可听、上行可听、全双工同时对话。
- **AUDIO-005**：连续通话 30 分钟无中断与延迟漂移。
- **AUDIO-006 ~ AUDIO-010**：静音、扬声器切换、蓝牙耳机通话、异常中断释放、模块重连恢复音频。

## 5. 双屏、实体按键与系统稳定性（Phase 5–6）
- **UI-001 ~ UI-006**：双屏识别（Display 0 & 2）、双屏显示正确、单屏降级正常、手柄按键映射正确、开/合盖状态处理、休眠唤醒恢复。
- **SYS-001 ~ SYS-008**：8 小时连续待机、待机收短信/接电话、后台稳定、异常断开恢复、游戏期间来电通知、系统重启恢复、长期无资源泄漏。

## 2026-11-26 — PocketJS UI PoC 验收
- JVM：v1 snapshot/serialization、严格 intent parsing、invalid/unknown-version rejection、safe call/SMS、Kotlin state mapping。
- JS：类型检查、guest 合同测试；host double 不作为 native rendering 证据。
- Native host：真实 QuickJS/Rust core 启动、像素输出、state 改变像素、触摸命中、双触点取消、intent/event、20 次释放循环。
- Android：default/top/fault APK；真实 GLES2 首帧、Activity recreate、后台恢复 instrumentation；双屏、拔插/不可用、rotation（若允许）、screen off/on、输入与性能清单见 docs/pocketjs/README.md。
- Level 3 gate：仅在 per-instance core/runtime 完成且 Phase B 实机通过后启用。
