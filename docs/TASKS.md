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
- [x] 编写 JUnit 4 自动化单元测试（12/12 PASS）
- [x] 执行 `./gradlew assembleDebug testDebugUnitTest lintDebug` 全绿并安装启动至 Retroid Pocket Duo (`1b0b84fc`) 实机验证
- [ ] `BLOCKED`：切换无线 ADB 并接入 QDC507 实体模块完成 `USB-001` ~ `USB-007` 实机验收

## Phase 2：短信功能（PDU 模式） — ⏳ 待开始
- [ ] 3GPP TS 23.040 PDU 编码（GSM 7-bit / UCS2 / 长短信 UDH）与解码/分片重组 + 单元测试
- [ ] Room 数据库持久化、新短信通知、`+CMTI`/`+CMT` 实时拉取与验证码一键复制

## Phase 3：电话呼叫控制 — ⏳ 待开始
- [ ] `ATD`/`ATA`/`ATH`/`AT+CLCC`/`AT+CLIP`/`AT+CEER`/`AT+VTS` 呼叫状态机与通话记录持久化

## Phase 4：双向语音通话专项 — ⏳ 待开始
- [ ] 内置直接 USB ADB 客户端（`FF/42/01`）+ QDC507 可逆语音运行时编排
- [ ] Android 音频桥接验证（方案 A UAC1 vs 方案 B USB Bulk PCM vs 方案 C NDK）

## Phase 5–7：后台稳定性 / 双屏与手柄产品化 / 4G 移动网络 — ⏳ 待开始
- [ ] 合盖（`hall_switch`）供电实测、8h 待机测试、双屏 UI（Display 0 + Display 2）、Retroid 手柄按键映射、ECM 移动网络验证
