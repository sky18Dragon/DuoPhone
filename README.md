# Duo Phone

面向 **Retroid Pocket Duo** 双屏游戏掌机与 **大疆第一代 4G 增强图传模块（DJI Cellular Dongle Gen 1 / QDC507）** 的独立 Android 蜂窝通信终端应用（无需 Root，不刷写模块固件）。

## 项目目标
通过 USB Type-C OTG 连接大疆第一代 4G 模块（`2CA3:4006` / 兼容 `2C7C:0125`），在 Retroid Pocket Duo（Qualcomm CQ7790S / Android 16 / 双内屏 `1920x1080` + `1280x960`）上实现：
- **P0 核心能力**：USB 自动识别与热插拔重连、AT 协议队列与 URC 事件分离、SIM/运营商/信号/注册遥测、PDU 双向中文与长短信、电话呼叫控制（拨号/接听/拒接/挂断/来电显示/通话状态）、双向语音通话、前台通信服务、AT 调试终端。
- **P1 终端体验**：双屏独立 UI、Retroid 手柄实体按键映射、合盖待机与开盖恢复、通讯录集成、蓝牙/有线耳机/扬声器语音路由、验证码识别与快捷复制。
- **P2 扩展能力**：无 Root USB 4G 移动网络（CDC-ECM / EthernetNetworkFactory）、APN 与流量诊断、模块配置备份恢复。

## 当前进度
- **Phase 0（技术调研与环境准备）**：已完成全部 5 个参考仓库源码/许可证审计与 Retroid Pocket Duo 实机 ADB 诊断。
- **Phase 1（USB 与 AT 通信核心）**：进行中。

## 文档索引
详见 [`docs/`](docs/) 目录：
- [产品需求](docs/PRODUCT_REQUIREMENTS.md) · [系统架构](docs/ARCHITECTURE.md) · [开发计划](docs/DEVELOPMENT_PLAN.md)
- [参考项目审计](docs/REFERENCE_PROJECTS.md) · [许可证审计](docs/LICENSE_AUDIT.md)
- [硬件与协议研究](docs/HARDWARE_RESEARCH.md) · [QDC507 协议](docs/QDC507_PROTOCOL.md) · [USB 接口规范](docs/USB_INTERFACE.md) · [AT 指令清单](docs/AT_COMMANDS.md)
- [语音运行时方案](docs/VOICE_RUNTIME.md) · [Android 音频桥接](docs/AUDIO_BRIDGE.md)
- [Retroid Pocket Duo 实机兼容性](docs/RETROID_DUO_COMPATIBILITY.md) · [双屏设计](docs/DUAL_SCREEN_DESIGN.md) · [电源与合盖管理](docs/POWER_MANAGEMENT.md)
- [测试计划](docs/TEST_PLAN.md) · [测试报告](docs/TEST_REPORT.md) · [任务跟踪](docs/TASKS.md) · [架构决策](docs/DECISIONS.md) · [风险清单](docs/RISK_REGISTER.md) · [已知问题](docs/KNOWN_ISSUES.md)

## 免责声明
本项目为独立非官方开源工具，与 DJI、Quectel、Retroid/Moorechip 无隶属关系。在完成正式运营商与紧急呼叫验收前，**不得将本项目作为替代标准手机的可靠紧急呼叫（110/119/120/911/112）设备**。
