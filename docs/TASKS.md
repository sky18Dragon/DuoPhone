# 开发任务与验收追踪 (`TASKS.md`)

最后更新：2026-11-24

## Phase 0：技术调研与环境准备 — ✅ 已完成
- [x] 检查工作目录、Git 与本地 Android/JDK/SDK/NDK 工具链
- [x] 通过 ADB 采集 Retroid Pocket Duo (`1b0b84fc`) 完整系统能力与硬件拓扑
- [x] 克隆并逐行审计 5 个参考仓库（CardDock、MaVo、DJOneHubNative、DJ4Hub、DJOneHub-mac-enhanced）
- [x] 完成开源许可证合规审计与风险边界定义
- [x] 制定架构方案（ADR-001 ~ ADR-005）并生成完整项目文档与 `AGENTS.md`

## Phase 1：USB 与 AT 通信核心 — ✅ 代码/单测/掌机基线完成（QDC507 实机联调待接模块）
- [x] 初始化 Gradle 9.1.0 + AGP 9.0.1 + Jetpack Compose 工程结构（`com.duophone.app`，JDK 25 / SDK 36）
- [x] 实现分类脱敏日志系统 `DuoLogger`（12 分类 Tag + PII 脱敏 + 滚动文件日志落盘）
- [x] 实现 USB 设备识别（`2CA3:4006` / `2C7C:0125`）、描述符解析与安全接口分类/排序（显式跳过 ADB/UAC/存储/Diag）
- [x] 实现连接状态机（`DISCONNECTED` → `READY` + 5 种异常状态）
- [x] 实现 `AtEngine`（串行队列、行帧解析、异步 URC 分流、多行响应、SMS `>` 提示符、可控只读重试、非幂等指令防重试）
- [x] 实现基础遥测与只读模块诊断（`AT`/`ATI`/`ATE0`/`CGMI`/`CGMM`/`CGMR`/`CGSN`/`CPIN?`/`CSQ`/`COPS?`/`CREG?`/`CGREG?`/`CEREG?`/`QCFG="usbnet"/"usbcfg"/"ims"`/`QPCMV=?`）
- [x] 实现 `ModemService` 前台服务骨架与热插拔/断线自动重连恢复
- [x] 实现 Phase 1 Compose UI（状态仪表盘、USB 接口诊断、Retroid 双屏/输入硬件诊断卡、AT 调试终端、脱敏日志视图、L1/R1 手柄切换页签）
- [x] 编写 JUnit 4 自动化单元测试并通过 `./gradlew assembleDebug testDebugUnitTest lintDebug`
- [x] 安装启动 Phase 1 基线至 Retroid Pocket Duo (`1b0b84fc`) 实机验证
- [ ] `BLOCKED`：切换无线 ADB 并接入 QDC507 实体模块完成 `USB-001` ~ `USB-007` 实机验收

## Phase 2：短信功能（PDU 模式） — ✅ 代码与单元测试完成（实机待接模块）
- [x] 实现 3GPP TS 23.040 PDU 编码（GSM 7-bit / UCS2 / 长短信 UDH / UTF-16 代理对安全切分）与解码/乱序重组（`SmsPduCodec`）
- [x] 实现 SQLite 持久化（`DuoDatabaseHelper` / `SmsRepository` / `SmsStore`）、新短信通知（`SmsNotificationHelper`）、`+CMTI` 实时同步（`SmsServiceController`）与验证码一键复制
- [x] 实现短信会话/详情/快捷回复 Compose UI（含无硬件本地 `[SIMULATED]` 验证入口）与单元测试（`SmsPduCodecTest`、`SmsServiceControllerTest`）
- [ ] `BLOCKED`：接入 QDC507 与真实 SIM 卡完成 `SMS-001` ~ `SMS-010` 实机双向短信验收

## Phase 3：电话呼叫控制 — ✅ 代码与单元测试完成（实机待接模块）
- [x] 实现呼叫 AT/URC 解析（`CallAtParser`：`ATD`/`ATA`/`ATH`/`AT+CLCC`/`AT+CLIP`/`AT+CEER`/`AT+VTS`）
- [x] 实现呼叫状态机与通话记录 SQLite 持久化（`CallController` / `CallLogRepository`），严格禁止 `ATD` 自动重试
- [x] 实现拨号键盘、通话控制面板、来电通知与通话记录 UI（`CallScreen`）及单元测试（`CallAtParserTest`、`CallControllerTest`）
- [ ] `BLOCKED`：接入 QDC507、SIM 卡与授权测试号码完成 `CALL-001` ~ `CALL-010` 实机呼叫控制验收

## 双屏 UI 与手柄控制（Phase 6 前置基础） — ✅ 代码与单元测试完成（真机部署已暂停）
- [x] 实现 `DualDisplayHelper`、`SecondaryDeckActivity`（`Display Id != 0` 下屏拨号/通话/快捷短信/AT 控制台）与完整单屏降级
- [x] 实现 `GamepadKeyRouter`（L1/R1、START/SELECT、通话情景感知 A/B/X/Y 按键映射）与 `GamepadKeyRouterTest`
- [ ] `BLOCKED`：恢复连接 Retroid Pocket Duo 并完成新版 APK 安装及 `UI-002`~`UI-004` 实机双屏/手柄验证

## Phase 4：双向语音通话专项 — ⏳ 待开始
- [ ] 内置直接 USB ADB 客户端（`FF/42/01`）+ QDC507 可逆语音运行时编排（仅 `/tmp` 临时加载 + SHA-256 校验）
- [ ] Android 音频桥接实机验证（方案 A UAC1 vs 方案 B USB Bulk PCM vs 方案 C NDK）

## Phase 5–7：后台稳定性 / 产品化完善 / 4G 移动网络 — ⏳ 待开始
- [ ] 合盖（`hall_switch`）USB 供电实测、8h 待机测试、通讯录、蓝牙耳机切换、ECM 移动网络验证
