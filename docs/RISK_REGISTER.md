# 技术风险与外部阻塞清单 (`RISK_REGISTER.md`)

| ID | 风险/阻塞项 | 严重度 | 可能性 | 影响阶段 | 应对与缓解策略 |
| --- | --- | --- | --- | --- | --- |
| R-01 | **Android 16 并发双 `AudioRecord` / USB Audio 路由限制**：普通非 Root App 可能无法同时采集本机麦克风与 QDC507 UAC1 输入，或接入 UAC 后系统抢占全局音频 | **极高** | 高 | Phase 4 | 设计多级方案：优先实测方案 A（原生 Audio API 独立设备路由），若受限立即切换方案 B（USB IF1 Raw Bulk PCM 桥接，仅需本机 1 路录音+播放，彻底绕开 UAC 抢占）或方案 C（NDK usbdevfs + Oboe） |
| R-02 | **QDC507 原厂固件版本/内核 ABI 差异**：不同批次模块若非 `3.18.44` 或关闭了 `ADB`，`insmod` 会失败或崩溃 | **高** | 中 | Phase 4 | 严格校验 `uname -r == 3.18.44`、`id -u == 0` 与 SHA-256；绝不盲目加载不匹配模块；严禁刷机 |
| R-03 | **Retroid Pocket Duo 合盖休眠（`hall_switch`）切断 USB Host 供电**：合盖后 `AutoSuspend` 可能导致模块掉电无法后台接听来电/收短信 | **高** | 高 | Phase 5 | Phase 5 实机测量合盖 VBUS 状态；若硬件断电则提供带电 OTG Hub 建议、开盖快速重连与明确文档说明，不误判为 App Bug |
| R-04 | **OTG 瞬时供电不足**：QDC507 弱信号发射短信/通话时峰值电流触发 USB 复位掉线 | 中 | 中 | Phase 1–4 | 实现 `RECOVERING` 指数退避自动重连；日志记录断连上下文并提示排查供电/使用带外接供电 OTG 转接头 |
| R-05 | **PolyForm Noncommercial / GPL-2.0 许可证污染** | 中 | 低 | 全周期 | 严禁复制 DJOneHubNative/DJ4Hub/DJOneHub-mac-enhanced 源码；不向本仓库提交 GPL-2.0 `.ko` 二进制 |
| B-01 | **当前外部阻塞（实机 QDC507 未接入）**：Retroid Pocket Duo 唯一 USB-C 口正在连接宿主机用于有线 ADB | 中 | 当前存在 | Phase 1 实机验收 | 先完成 Phase 1 全量代码、Mock 单元测试、Gradle 构建、Lint 及 Retroid Pocket Duo 真机安装/无硬件诊断验证；待切换无线 ADB（`192.168.17.64:5555`）并插上 QDC507 后执行 `USB-001`~`USB-007` 实机联调 |
