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
- [x] 用户实机直连 QDC507 验证基础识别与 AT/网络就绪（`USB-001`~`USB-003` PASS）
- [ ] 待补采 QDC507 热插拔重连与详尽描述符/AT 遥测（`USB-004`~`USB-007`）

## Phase 2：短信功能（PDU 模式） — ✅ 代码、单测与真实模块基础收发已验证
- [x] 实现 3GPP TS 23.040 PDU 编码（GSM 7-bit / UCS2 / 长短信 UDH / UTF-16 代理对安全切分）与解码/乱序重组（`SmsPduCodec`）
- [x] 实现 SQLite 持久化（`DuoDatabaseHelper` / `SmsRepository` / `SmsStore`）、新短信通知（`SmsNotificationHelper`）、`+CMTI` 实时同步（`SmsServiceController`）与验证码一键复制
- [x] 实现短信会话/详情/快捷回复 Compose UI（含无硬件本地 `[SIMULATED]` 验证入口）与单元测试（`SmsPduCodecTest`、`SmsServiceControllerTest`）
- [x] 用户直连 QDC507 与真实 SIM 卡实测：收发短信成功（`SMS-001`~`SMS-004` PASS）；`SMS-005`~`SMS-010` 长短信/验证码边界项待补验

## Phase 3：电话呼叫控制 — ✅ 代码、单元测试与 APK 安装完成（真机呼叫待测）
- [x] 实现呼叫 AT/URC 解析（`CallAtParser`：`ATD`/`ATA`/`ATH`/`AT+CLCC`/`AT+CLIP`/`AT+CEER`/`AT+VTS`）
- [x] 实现呼叫状态机与通话记录 SQLite 持久化（`CallController` / `CallLogRepository`），严格禁止 `ATD` 自动重试
- [x] 实现拨号键盘、通话控制面板、来电通知与通话记录 UI（`CallScreen`）及单元测试（`CallAtParserTest`、`CallControllerTest`）
- [ ] 待使用当前已连接 QDC507 与授权测试号码完成 `CALL-001` ~ `CALL-010` 实机呼叫控制验收

## 双屏 UI 与手柄控制（Phase 6 前置基础） — ✅ 代码、单测与双屏拉起完成（手柄通话交互待测）
- [x] 实现 `DualDisplayHelper`、`SecondaryDeckActivity`（`Display Id != 0` 下屏拨号/通话/快捷短信/AT 控制台）与完整单屏降级
- [x] 实现 `GamepadKeyRouter`（L1/R1、START/SELECT、通话情景感知 A/B/X/Y 按键映射）与 `GamepadKeyRouterTest`
- [x] 完成 `19:46` APK 安装，`MainActivity` 与 `Display Id=2` 双屏同时点亮；待补测 `UI-002`~`UI-004` 手柄通话交互

## Phase 4：双向语音通话专项 — ✅ 代码与单测完成（实机部署与硬件语音验收待测）
- [x] 内置直接 USB ADB 客户端（`FF/42/01`：`AdbWireProtocol` / `AndroidUsbAdbClient`）
- [x] 实现 QDC507 可逆语音运行时编排（`Qdc507VoiceRuntime`：仅读取外部 `filesDir/voice_runtime/` 并强校验 SHA-256，严格仅部署 `/tmp/duophone-voice`，停用时用启动时间戳核验 `SIGTERM` + `voc_svr` 回滚且严禁 `rmmod`）
- [x] 实现跨帧线性重采样器（`StatefulLinearResampler`）、dBFS/零帧监视器（`PcmLevelMeter`）与 Android UAC 双向音频桥接（`AndroidAudioBridge`，支持 `EARPIECE`/`SPEAKER`/`WIRED_HEADSET`/`BLUETOOTH` 及 Android 16 并发录音零帧告警）
- [x] 接入 `RECORD_AUDIO`/`MODIFY_AUDIO_SETTINGS` 权限、`CallController` 音频联动回调及【诊断】页 Phase 4 控制面板，并通过 10 项 Phase 4 新增 JVM 单测
- [ ] 待部署新版 APK 至 Retroid Pocket Duo 并开展 QDC507 UAC1 双向语音真机验收（`AUDIO-001`~`AUDIO-010`）

## Phase 5–7：后台稳定性 / 产品化完善 / 4G 移动网络 — ⏳ 待开始
- [ ] 合盖（`hall_switch`）USB 供电实测、8h 待机测试、通讯录、蓝牙耳机切换、ECM 移动网络验证
