# 阶段开发计划 (`DEVELOPMENT_PLAN.md`)

| 阶段 | 名称 | 核心交付 | 验收标准 | 当前状态 |
| --- | --- | --- | --- | --- |
| **Phase 0** | 技术调研与环境准备 | 5 个参考仓库源码/许可证审计、Retroid Pocket Duo ADB 实机诊断、架构与文档基线 | 形成有源码和实机证据支撑的技术方案 | **已完成** |
| **Phase 1** | USB 与 AT 通信核心 | Gradle 工程、USB 识别与权限、安全接口探测、AT 队列 + URC 分流、状态机、SIM/运营商/信号/注册遥测、`ModemService` 基础、AT 调试终端、脱敏日志、单测与真机安装 | 单元测试全绿、APK 在 Retroid Pocket Duo 运行稳定；接 QDC507 后稳定握手并查询状态 | **已完成（实机验证 PASS）** |
| **Phase 2** | 短信功能（PDU） | GSM 7-bit / UCS2 PDU 编解码、长短信分片重组、`+CMTI`/`+CMT` 实时接收、Room 持久化、新短信通知、验证码识别 | 单元测试覆盖编解码与重组；真实 SIM 卡完成中英文及长短信双向收发 | **已完成（实机验证 PASS）** |
| **Phase 3** | 电话呼叫控制 | `ATD`/`ATA`/`ATH`/`AT+CLCC`/`AT+CLIP`/`AT+VTS`、通话状态机、来电通知、通话记录持久化 | 独立验收“呼叫控制成功”（不含音频验收） | **已完成（实机验证 PASS）** |
| **Phase 4** | 双向语音通话专项 | 模块只读语音探测 → 直接 USB ADB 客户端 → 可逆语音运行时编排 → Android UAC/PCM 双向桥接（8k↔48k）+ 回声控制 | 独立验收上行、下行、全双工真实双向通话 | **已完成（实机验证 PASS）** |
| **Phase 5** | 后台服务与低功耗稳定性 | Foreground Service 深化、Doze/合盖（`hall_switch`）USB 供电实测、异常恢复、8 小时待机压测 | 后台/待机可持续接收来电短信并明确合盖 USB 供电特性 | **进行中** |
| **Phase 6** | 双屏适配与产品化 | 双屏 UI（Display 0 + Display 2）+ 单屏降级、Retroid 手柄按键映射、通讯录、蓝牙/3.5mm 音频切换、Release 构建方案 | 形成可日常试用的完整通信终端 APK | **进行中** |
| **Phase 7** | 高级扩展 | USB 4G 移动网络（ECM + EthernetNetworkFactory）、APN/流量、GPS、AI 助手 | 按可行性独立推进，不阻塞核心版 | 待开始 |
