# 开源许可证合规审计 (`LICENSE_AUDIT.md`)

审计日期：2026-11-24

| 项目 | 许可证 | 允许商业/自由再分发 | Duo Phone 使用策略 | 风险等级 |
| --- | --- | --- | --- | --- |
| **CardDock** (`Godles-lab/usb-modem-sms`) | MIT | 是 | 可借鉴架构、USB Host 探测及 AT 模式；保留 MIT 致谢声明 | 低 |
| **MaVo 主机端 & `mavo_pcm_bridge.c`** (`moluncn/mavo`) | MIT | 是 | 可参考/移植协议逻辑与用户态 PCM 桥接设计；保留 MIT 声明 | 低 |
| **MaVo `qdc507_aprv3.ko` / `qdc507_voice.ko`** | **GPL-2.0** | 是（需满足 GPL-2.0 源码公开义务） | **Phase 0–3 不打包进仓库/APK**。Phase 4 优先采用“用户本机/可信来源按 SHA-256 校验导入”机制；若随包分发则必须履行 GPL-2.0 完整义务并核查对应补丁源码可用性 | **高** |
| **DJOneHubNative** (`cr-zhichen/DJOneHubNative`) | PolyForm Noncommercial 1.0.0 | **禁止商业用途** | **严禁复制源码**；仅查阅文档/硬件行为结论 | 中（已规避） |
| **DJ4Hub** (`WongLoki/DJ4Hub`) | PolyForm Noncommercial 1.0.0 | **禁止商业用途** | **严禁复制源码**；仅查阅实测记录与协议结论 | 中（已规避） |
| **DJOneHub-mac-enhanced** | PolyForm Noncommercial 1.0.0 | **禁止商业用途** | **严禁复制源码**；仅查阅产品流程与边界经验 | 中（已规避） |

## 关键合规红线
1. **PolyForm Noncommercial 隔离**：不得将上述三个 PolyForm 仓库的任何 Go/Swift 代码直接拷贝进 Duo Phone。
2. **GPL-2.0 二进制缺口（重要发现）**：DJ4Hub 实测审计发现，MaVo 公开仓库虽包含 `COPYING-GPL-2.0` 与 `qdc507_aprv3.ko` / `qdc507_voice.ko` 二进制，但其公开树**未提供与当前 `qdc507_voice.ko` 对应的完整可复现内核补丁/Makefile**，且 `MODULE-REPORT.md` 仍停留在旧版 `qdc507_afe.ko`。因此 Duo Phone **不会将这两个 `.ko` 二进制直接提交到本仓库**，Phase 4 将采用**用户显式授权 + SHA-256 校验导入外部运行时文件**方案，彻底规避 GPL 源码分发合规风险。
