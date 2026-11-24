# 测试报告 (`TEST_REPORT.md`)

> 严格区分**模拟/单元测试**与**实机硬件测试**；绝不编造未实测项。

## 1. 环境与设备诊断基线（2026-11-24）
| 检查项 | 环境 | 状态 | 实际结果 |
| --- | --- | --- | --- |
| Retroid Pocket Duo ADB 连通性 | 实机 `1b0b84fc` (USB) | `PASS` | `Retroid_Pocket_Duo` / Android 16 (API 36) / `CQ7790S` / `arm64-v8a` |
| Retroid Pocket Duo USB Host 能力查询 | 实机 `1b0b84fc` | `PASS` | `android.hardware.usb.host` 存在；`port0` 支持 `{source, host}` 与 `{sink, device}` |
| Retroid Pocket Duo 双屏诊断 (`UI-001` 硬件层) | 实机 `1b0b84fc` | `PASS` | 枚举出 `Display Id=0 (1920x1080)` 与 `Display Id=2 (1280x960)` 两个独立内部屏 |
| Retroid Pocket Duo Telecom 服务核查 | 实机 `1b0b84fc` | `PASS`（确认不支持） | `dumpsys telecom` 返回 `Can't find service: telecom` |
| QDC507 实机 USB 枚举与 AT 握手 (`USB-001` ~ `USB-007`) | 实机 + QDC507 | `BLOCKED` | 阻塞原因：当前 Retroid Pocket Duo 唯一 USB-C 接口连接开发机用于有线 ADB，尚未切无线 ADB 并接入 QDC507 硬件模块 |

## 2. Phase 1 自动化单元测试与实机验证
- 待 Phase 1 代码构建完成后补充实际命令输出、单测统计及 Retroid Pocket Duo 真机安装运行日志。
