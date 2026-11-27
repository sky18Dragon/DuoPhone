# 已知问题与解决方案 (`KNOWN_ISSUES.md`)

| 编号 | 发现日期 | 现象/问题 | 根因分析 | 解决方案 / 规避措施 | 状态 |
| --- | --- | --- | --- | --- | --- |
| KI-001 | 2026-11-24 | Retroid Pocket Duo 上 `dumpsys telecom` 返回 `Can't find service: telecom` | 掌机 ROM 虽声明了 `android.software.telecom` feature，但未启动系统 Telecom 服务 | 放弃依赖系统 `ConnectionService`，由独立 `ModemService` 全权管理呼叫状态、通知与音频路由 | 已解决（架构规避） |
| KI-002 | 2026-11-24 | 开发机仅安装 OpenJDK 25.0.2，无法运行 CardDock 原有的 Gradle 8.9 | Gradle 8.x 不支持 JDK 25；需 Gradle 9.1+ 与 AGP 9.x | Duo Phone 采用 Gradle 9.1+ & AGP 9.x（内置 Kotlin 支持） | 已解决 |
| KI-003 | 2026-11-24 | QDC507 原厂固件所有 AT 音频写入指令（`AT+QPCMV=1,0/1,2`、`AT+QDAI`）返回 ERROR | 大疆定制固件 `QDC507GLEFM21` 裁剪了 AT 音频导出且内核无内置 ALSA 声卡 | Phase 4 采用可逆 USB ADB 运行时加载方案（参考 MaVo/DJ4Hub），不刷机 | 已规划 |
| KI-004 | 2026-11-24 | QDC507 重启后盲目探测 Diag `IF0` 可能导致 USB 同步传输阻塞 | 参考 DJOneHubNative 实测记录，IF0 诊断口非 AT 口 | `UsbInterfaceClassifier` 默认跳过 ADB/UAC/存储并优先探测 IF2/IF3，排除 IF0 常规探测 | 已在设计中规避 |
| KI-005 | 2026-11-24 | MaVo 仓库 `MODULE-REPORT.md` 与实际打包的 `qdc507_voice.ko` 不一致且缺少该 `.ko` 完整补丁源码 | 上游文档滞后且未公开最新 `.ko` 补丁构建脚本 | Duo Phone 不将 GPL-2.0 `.ko` 提交至本仓库，Phase 4 改为用户明确授权 + SHA-256 校验导入 | 已规避合规风险 |
| KI-006 | 2026-11-24 | Retroid Pocket Duo `user` 构建默认过滤 `adb logcat` 中的三方应用 `D`/`I` 级日志 | 厂商 ROM 日志过滤策略仅保留 `E` 级输出 | 在 `DuoLogger` 新增脱敏滚动文件日志（`files/logs/duophone-latest.log`），支持应用内实时查看与 `adb shell run-as com.duophone.app cat files/logs/duophone-latest.log` 提取 | 已解决 |

## 2026-11-26 / 2026-11-27 — PocketJS UI 与脱敏迭代
- Upstream v0.12.0 `engine/quickjs-c/pocket_runtime.c` 使用 static runtime/context；`engine/ui-cabi/src/lib.rs` 使用 `static mut UI`，第二次 boot 会释放第一实例。当前只允许 `TOP`（`-PpocketUi=top`），`dual` 配置保持拒绝，`BOTTOM` 保持 Compose。
- Scheme 1 GLES2 离屏 FBO（720×1280 + 四点单应性合成 + 60fps 呼吸/按键回弹 + 反透视触控）、PoC 安全模式放开、下屏去拨号输入框以及全应用号码第 4–7 位 `*` 脱敏已在 **Retroid Pocket Duo（Android 16 / SDK 36，`1b0b84fc`）** 完成实机构建与基础 UI 验证；**接入 QDC507 模块后的真机通话/短信全链路待实测**。
- `pocket-ui/build.py` 仅烘焙 ASCII `32..126` 字库，中文草稿预览在 PocketJS 短信页回退为 `Draft: N chars`（完整中文仍在 Compose 下屏编辑/显示）。
- Native `SIGSEGV`/`abort` 无法由 Kotlin Compose fallback 捕获；当前 fallback 涵盖动态库加载失败、初始化失败、渲染/消息异常及首帧超时。
- 固定 360×640 guest viewport；双独立 runtime 性能与屏幕动态拔插适配尚未实现。
