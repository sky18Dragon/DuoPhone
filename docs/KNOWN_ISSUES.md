# 已知问题与解决方案 (`KNOWN_ISSUES.md`)

| 编号 | 发现日期 | 现象/问题 | 根因分析 | 解决方案 / 规避措施 | 状态 |
| --- | --- | --- | --- | --- | --- |
| KI-001 | 2026-11-24 | Retroid Pocket Duo 上 `dumpsys telecom` 返回 `Can't find service: telecom` | 掌机 ROM 虽声明了 `android.software.telecom` feature，但未启动系统 Telecom 服务 | 放弃依赖系统 `ConnectionService`，由独立 `ModemService` 全权管理呼叫状态、通知与音频路由 | 已解决（架构规避） |
| KI-002 | 2026-11-24 | 开发机仅安装 OpenJDK 25.0.2，无法运行 CardDock 原有的 Gradle 8.9 | Gradle 8.x 不支持 JDK 25；需 Gradle 9.1+ 与 AGP 9.x | Duo Phone 采用 Gradle 9.1+ & AGP 9.x（内置 Kotlin 支持） | 已解决 |
| KI-003 | 2026-11-24 | QDC507 原厂固件所有 AT 音频写入指令（`AT+QPCMV=1,0/1,2`、`AT+QDAI`）返回 ERROR | 大疆定制固件 `QDC507GLEFM21` 裁剪了 AT 音频导出且内核无内置 ALSA 声卡 | Phase 4 采用可逆 USB ADB 运行时加载方案（参考 MaVo/DJ4Hub），不刷机 | 已规划 |
| KI-004 | 2026-11-24 | QDC507 重启后盲目探测 Diag `IF0` 可能导致 USB 同步传输阻塞 | 参考 DJOneHubNative 实测记录，IF0 诊断口非 AT 口 | `UsbInterfaceClassifier` 默认跳过 ADB/UAC/存储并优先探测 IF2/IF3，排除 IF0 常规探测 | 已在设计中规避 |
| KI-005 | 2026-11-24 | MaVo 仓库 `MODULE-REPORT.md` 与实际打包的 `qdc507_voice.ko` 不一致且缺少该 `.ko` 完整补丁源码 | 上游文档滞后且未公开最新 `.ko` 补丁构建脚本 | Duo Phone 不将 GPL-2.0 `.ko` 提交至本仓库，Phase 4 改为用户明确授权 + SHA-256 校验导入 | 已规避合规风险 |
