# QDC507 硬件通信协议规范 (`QDC507_PROTOCOL.md`)

## 1. USB 身份与组合配置
- 主识别 ID：`VID = 0x2CA3 (11427)`, `PID = 0x4006 (16390)`
- 兼容识别 ID：`VID = 0x2C7C (11388)`, `PID = 0x0125 (293)`
- `AT+QCFG="usbcfg"` 参数位序：
  `<vid>,<pid>,<diag>,<nmea>,<at_port>,<modem>,<rmnet/ecm>,<adb>,<uac>`
  - 出厂常见值：`0x2CA3,0x4006,1,1,1,1,1,0,0`（ADB=0, UAC=0）
  - 语音就绪值：`0x2CA3,0x4006,1,1,1,1,1,1,1`（或保持原位仅启用 ADB，通过 sysfs 临时追加 `audio`）
- **高危红线**：
  1. 任何 `AT+QCFG="usbcfg"` 写操作必须原样保留当前 VID/PID（尤其是 `0x2CA3,0x4006`），严禁套用 Quectel 文档中的 `0x2C7C,0x0125`。
  2. 修改任何 `AT+QCFG` 前必须通过 `AT+CGSN`（IMEI）生成本机配置备份，并经过用户二次确认。
  3. 严禁刷写标准 Quectel EG25-G/EC25 固件（存在 9008 变砖风险）。

## 2. 典型 USB 接口布局（随 `usbnet` 与 `usbcfg` 动态变化）
| 典型接口号 | Class/SubClass/Proto | 功能角色 | Duo Phone 策略 |
| --- | --- | --- | --- |
| IF0 | `FF/FF/FF` | Diag 诊断口 | **避免作为首选探测**（重启后探测可能阻塞 USB） |
| IF1 | `FF/00/00` | NMEA / `/dev/ttyGS0` 备用原始 PCM 通道 | 次选候选；Phase 4 可作原始 PCM 备用路径 |
| IF2 | `FF/00/00` | **标准 AT 口**（常见 Bulk IN `0x84`, Bulk OUT `0x03`） | **第一优先探测** |
| IF3 | `FF/00/00` | Modem 串口（亦支持 AT） | **第二优先探测** |
| IF4 | `FF/FF/FF` 或 `02/06` + `0A/00` | QMI/RMNET 或 CDC-ECM/MBIM 网卡 | 跳过（不作为 AT 口探测） |
| 动态 | `FF/42/01` | **模块侧 ADB (`ffs`)** | **强制跳过 AT 探测**；由 Phase 4 `UsbAdbClient` 专用 |
| 动态 | `01/01/00` & `01/02/00` | **USB Audio Class (UAC1)** (`AC Interface` / `AS Interface`) | **强制跳过 AT 探测**；由 Android Audio / Phase 4 音频桥接使用 |

## 3. 串口控制握手
打开 AT 接口后，通过 USB Control Transfer 发送 CDC `SET_CONTROL_LINE_STATE`：
- `requestType = 0x21` (Host-to-Device, Class, Interface)
- `request = 0x22` (`SET_CONTROL_LINE_STATE`)
- `value = 0x03`（拉高 DTR + RTS；深度探测时可尝试 `0x00`）
- `index = interfaceId`（注意：使用 `UsbInterface.id`，即描述符 `bInterfaceNumber`，而非数组下标）
- 随后发送 `AT\r\n`，预期在 1200ms 内返回包含 `OK` 的响应。

## 4. 字符集注意事项
- 处于 `AT+CSCS="UCS2"` 时，部分 QDC507 固件会把 `AT+QCFG` 的键名回显为 UCS2 十六进制或导致某些文本参数解析异常。
- **Duo Phone 策略**：全程默认使用 **PDU 模式 (`AT+CMGF=0`)** 处理短信，主通道保持 `AT+CSCS="GSM"` 或 `IRA`，彻底规避 UCS2 字符集污染 `AT+QCFG` / `AT+COPS?` 响应的问题。
