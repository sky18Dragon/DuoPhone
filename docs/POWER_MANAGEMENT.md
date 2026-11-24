# 后台服务、合盖待机与低功耗设计 (`POWER_MANAGEMENT.md`)

## 1. 后台通信服务 (`ModemService`)
- 作为 `ForegroundService`（`foregroundServiceType="connectedDevice"`，通话期按需扩展）常驻维护 USB 连接、`AtEngine` 读取线程与 URC 监听，脱离 `Activity` 生命周期，确保切换游戏或退后台后持续收短信与来电。
- 避免高频轮询：正常连接就绪后优先依赖 URC 事件（`+CMTI`、`RING`、`+CLIP`、`+CEREG`），仅在屏幕亮起或低频心跳（如 30–60 秒）执行轻量化 `+CSQ`/`+CEREG?` 刷新。
- WakeLock 原则：待机期间**不持续持有 `PARTIAL_WAKE_LOCK`**；仅在来电振铃、通话进行中或执行 USB/AT 事务短窗口内按需持有并严格超时释放。
- 引导用户将 Duo Phone 加入电池优化白名单（`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`），应对 Android 16 Doze 模式。

## 2. 合盖休眠（`hall_switch`）实机分析
- 已通过 `dumpsys input` 与 `dumpsys power` 证实：合盖由 `Device 5: hall_switch` 触发，系统直接执行 `mLastSleepReason=lid_switch` 并启用 `mHalAutoSuspendModeEnabled=true`。
- **待硬件实测项**：合盖 1 分钟 / 10 分钟 / 1 小时后：
  1. USB-C `port0` VBUS 供电是否关闭或掉电？
  2. 若 VBUS 维持，USB Host 控制器是否进入挂起、模块来电/短信的 USB Bulk IN 中断能否唤醒 SoC？
  3. 若硬件层直接断电，需记录为硬件/系统电源策略约束，并评估外部带电 Hub 方案与开盖自动秒级重连恢复。

## 3. 功耗测量计划（待硬件验证，禁止编造数据）
| 场景 | 状态 | 备注 |
| --- | --- | --- |
| 掌机空闲功耗（无模块） | `NOT_TESTED`（待实测） | 通过 `dumpsys battery` 电流/电量曲线与 USB 功率计测量 |
| 模块连接后待机功耗 | `NOT_TESTED` | 待接入 QDC507 后测量 |
| 短信收发功耗 | `NOT_TESTED` | 待接入 QDC507 后测量 |
| 双向语音通话功耗 | `NOT_TESTED` | 待 Phase 4 完成后测量 |
| 弱信号功耗 | `NOT_TESTED` | 待接入 QDC507 后测量 |
| 合盖待机功耗与 VBUS 状态 | `NOT_TESTED` | 待 Phase 5 实测 |
