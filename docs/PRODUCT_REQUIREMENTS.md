# 产品需求规格说明书 (`PRODUCT_REQUIREMENTS.md`)

## 1. 产品愿景
将 **Retroid Pocket Duo**（双屏实体按键 Android 16 游戏掌机）通过 USB-C OTG 连接 **DJI Cellular Dongle Gen 1（QDC507）**，改造为一台免 Root、低延迟、支持双屏与实体按键操作的独立蜂窝通信终端。

## 2. 功能优先级矩阵

### P0 — 核心通信能力（必须优先完成并实机验收）
1. 自动识别大疆一代 4G 模块（`2CA3:4006` / 兼容 `2C7C:0125`）与 USB 权限申请。
2. 安全 AT 接口自动探测、AT 串口通信队列、URC 异步事件分流。
3. 实时遥测：SIM 状态（`+CPIN?`）、运营商（`+COPS?`）、信号强度（`+CSQ`）、网络注册状态（`+CREG?`/`+CGREG?`/`+CEREG?`）。
4. 短信完整能力：PDU 模式双向收发、英文 GSM 7-bit 与中文 UCS2、长短信分片与重组、本地持久化历史记录、新短信通知。
5. 电话呼叫控制（**独立验收项 A**）：拨号（`ATD`）、接听（`ATA`）、拒接/挂断（`ATH`/`AT+CHUP`）、来电显示（`+CLIP`）、实时通话状态（`+CLCC`）、通话历史记录。
6. 双向语音通话（**独立验收项 B**）：QDC507 可逆语音运行时初始化 + Android 侧双向全双工 PCM 音频桥接（上行、下行、全双工分别验证）。
7. USB 热插拔检测、自动重连与异常恢复。
8. Android 16 合规后台 Foreground Service（`ModemService`）。
9. 内置 AT 指令调试终端与脱敏诊断日志系统。

### P1 — 完整掌机终端体验
1. 通讯录管理与 Android 系统通讯录只读/双向集成。
2. 音频路由：内置扬声器/听筒、3.5mm 耳机（实机已确认存在 `Headset Jack`）、蓝牙耳机通话、麦克风静音、音量控制。
3. DTMF 拨号（`AT+VTS`）、来电铃声、来电通知快捷操作、短信快捷回复、短信验证码提取与一键复制。
4. 双屏独立 UI（上屏 `Display 0: 1920x1080` 展示信息/通话状态/短信正文；下屏 `Display 2: 1280x960` 展示拨号键盘/输入/操作面板；并完整支持单屏降级）。
5. Retroid Pocket Controller 实体按键映射（A/B/X/Y/方向键/L/R/START/SELECT，支持配置）。
6. 翻盖休眠（`hall_switch`）待机策略、开盖恢复、游戏运行期间来电悬浮/通知提醒。
7. Android Telecom 集成可行性结论：**已实测 `dumpsys telecom` 返回 `Can't find service: telecom`**，本机无 Telecom 系统服务，明确采用独立 App 呼叫管理方案。

### P2 — 扩展能力（不阻塞 P0/P1）
1. 无 Root USB 4G 移动网络（利用实机已验证的 `EthernetNetworkFactory` + CDC-ECM 模式）。
2. APN 管理、流量统计、网络制式与 VoLTE/IMS 状态查询。
3. 模块 GPS/GNSS、多模块按 IMEI 配置备份与恢复、低功耗调优、AI 语音拨号与 AI 短信助手。

## 3. 安全与非功能要求
- **免 Root / 不刷机**：不修改 Android 系统分区，不刷写 QDC507 固件。
- **隐私脱敏**：日志默认脱敏手机号、IMEI、IMSI、ICCID、短信内容与验证码。
- **紧急呼叫免责**：在完成运营商与法规级验证前，不作为可靠紧急呼叫设备。
