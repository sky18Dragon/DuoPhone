# 双屏 UI 与实体按键设计 (`DUAL_SCREEN_DESIGN.md`)

## 1. 实机双屏拓扑（已实测确认）
- **上屏（Primary, `Display Id=0`）**：逻辑 `1920 x 1080` (16:9 横屏)，60/120Hz，密度 369 dpi。
- **下屏（Secondary, `Display Id=2`）**：逻辑 `1280 x 960` (4:3)，支持独立触控 (`fts_ts_sec`) 与 `activities_on_secondary_displays`。

## 2. 双屏分工方案
- **实现机制**：优先使用 `DisplayManager` 枚举 `displayId != Display.DEFAULT_DISPLAY` 的副屏，提供两种可切换机制：
  1. 主机制：带 `ActivityOptions.setLaunchDisplayId(secondaryDisplayId)` 启动交互式 `SecondaryDeckActivity`（天然支持副屏触控输入与软键盘，优于传统只读 `Presentation`）。
  2. 降级机制：当仅检测到单屏、副屏关闭或在非双屏设备运行时，自动无缝折叠为单屏响应式布局，所有功能 100% 可用。
- **信息分配**：
  - **上屏（信息与状态视窗）**：顶部通信状态栏（运营商、信号格/dBm、制式、SIM/USB 状态、电量）、通话大卡片（来电者/通话计时/音频波形）、短信正文阅读区、诊断实时日志/AT 输出终端。
  - **下屏（高频交互操控台）**：T9/数字拨号键盘、通话大按键（接听/挂断/静音/免提/蓝牙/DTMF）、短信输入与快捷回复/验证码复制、联系人快速筛选列表、AT 快捷命令面板。

## 3. 实体按键映射设计（可配置）
基于 `Retroid Pocket Controller` 标准 `KeyEvent`：
| 按键 | 默认映射 KeyCode | 默认功能 |
| --- | --- | --- |
| A | `KEYCODE_BUTTON_A` | 确认 / 接听来电 |
| B | `KEYCODE_BUTTON_B` | 返回 / 拒接 / 挂断 |
| X | `KEYCODE_BUTTON_X` | 通话麦克风静音切换 |
| Y | `KEYCODE_BUTTON_Y` | 扬声器 / 听筒 / 耳机切换 |
| 方向键 | `KEYCODE_DPAD_*` | Compose 焦点移动 |
| L1 / R1 | `KEYCODE_BUTTON_L1` / `R1` | 切换顶部主功能页签 |
| START | `KEYCODE_BUTTON_START` | 快捷跳转拨号页 |
| SELECT | `KEYCODE_BUTTON_SELECT` | 快捷跳转短信页 |

注意：非 Root 应用在游戏前台运行时无法全局拦截手柄按键；游戏期间来电采用**高优先级通知 + Heads-up 弹窗（非抢占全屏游戏输入）**提示。
