# 重要架构与技术决策记录 (`DECISIONS.md`)

## ADR-001：不直接 Fork CardDock 仓库，改为在 Duo Phone 中模块化新建工程并借鉴其 MIT 经验
- **日期**：2026-11-24
- **背景**：CardDock（MIT）是最贴近的 Android USB Host 参考项目，但源码审计显示其仅有 4 个扁平文件（无 Service、无 PDU 短信、无电话控制、无音频、无 Room、无测试），且绑定 Gradle 8.9（不兼容本机 OpenJDK 25）和 `dev.usbsms` 包名。
- **决策**：在 `/Users/zhanghuilong/Documents/DuoPhone` 以 `com.duophone.app` 构建清晰的 8 层模块化 Android 工程（Gradle 9.x + AGP 9.x + Jetpack Compose），借鉴 CardDock 经实机验证的 USB Host 探测、CDC DTR/RTS 握手与按 IMEI 备份配置思路，并在 `THIRD_PARTY_NOTICES.md` 保留 MIT 致谢。
- **影响**：避免后期因单文件紧耦合导致的重构债务，直接满足 Android 16 (API 36) + JDK 25 构建与单元测试需求。

## ADR-002：采用独立 App 呼叫管理，不依赖 Android Telecom / `ConnectionService`
- **日期**：2026-11-24
- **背景**：在 Retroid Pocket Duo (`1b0b84fc`) 实机执行 `adb shell dumpsys telecom` 返回 `Can't find service: telecom`，确认系统未运行 Telecom 服务。
- **决策**：所有呼叫状态机、来电提醒、响铃、音频路由和通话记录均由 `ModemService` 独立管理；保留未来在标准 Android 手机上可选适配 Telecom 的扩展位。

## ADR-003：短信采用 PDU 模式 (`AT+CMGF=0`) 为主，保持 AT 通道字符集为 `GSM/IRA`
- **日期**：2026-11-24
- **背景**：CardDock 使用文本模式 + `AT+CSCS="UCS2"` 导致 `AT+QCFG` 响应被十六进制编码且无法支持长短信分片重组。
- **决策**：Phase 2 统一采用 3GPP PDU 模式，完整支持中英文、Emoji 代理对安全切分、长短信 UDH 重组与字母发件人，同时保证 AT 控制命令通道清洁。

## ADR-004：QDC507 语音运行时不内置 GPL-2.0 `.ko` 二进制，采用“用户显式确认 + SHA-256 校验导入 + `/tmp` 临时加载”
- **日期**：2026-11-24
- **背景**：MaVo 的 `qdc507_aprv3.ko` / `qdc507_voice.ko` 为 GPL-2.0，且上游公开仓库未包含与 `qdc507_voice.ko` 完全匹配的构建补丁源码。
- **决策**：Duo Phone 仓库仅保留自有清洁代码（及可复现的 MIT 协议逻辑），绝不向 Git 提交 GPL-2.0 内核二进制；Phase 4 运行时通过 SHA-256 校验外部文件后经直接 USB ADB 推送至模块 `/tmp`，重启即清空。

## ADR-005：USB AT 探测显式隔离 ADB / UAC / Storage / Diag 接口
- **日期**：2026-11-24
- **背景**：DJOneHubNative 实测表明重启后盲探 Diag `IF0` 会挂起 USB；而误 claim ADB (`FF/42/01`) 或 UAC (`01/*/*`) 会破坏 Phase 4 语音运行时与音频桥接。
- **决策**：`UsbInterfaceClassifier` 硬过滤非串口类与 ADB 描述符，并按 `IF2 → IF3 → 其他非 IF0 候选` 排序探测。
