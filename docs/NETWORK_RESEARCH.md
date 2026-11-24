# 4G 移动网络专项调研 (`NETWORK_RESEARCH.md`)

> 所属阶段：Phase 7（P2 扩展功能，不阻塞 P0/P1 核心通话与短信开发）。

## 1. QDC507 USB 网络模式支持（参考资料与待验证项）
| `AT+QCFG="usbnet"` | 模式 | 主机驱动特性 | Duo Phone 适配预期 |
| --- | --- | --- | --- |
| `0` | QMI / RMNET | 模块默认；Android 非 Root 无内置 qmi_wwan 用户态拨号管理 | 仅适合控制/短信，不直接形成 Android 系统以太网 |
| `1` | **CDC-ECM** | **首选无 Root 方案**：模块内部自动拨号并运行 `bridge0` (`192.168.225.1/24`) + `dnsmasq` DHCP | 配合 Android 内核 `cdc_ether` 驱动与实机已确认存在的 **`EthernetNetworkFactory`**，有极大概率免 Root 自动获得系统默认/备用网络 |
| `2` | MBIM | 偏 Windows/Linux 桌面 | Android 通用性不如 ECM |
| `3` | RNDIS | 遗留协议，会导致 AT 接口移位 | 仅作备选 |

## 2. Retroid Pocket Duo 实机网络基础设施（已确认）
- `dumpsys connectivity` 已证实系统运行 **`1: EthernetNetworkFactory`**。
- 下一步在 Phase 7 实测：
  1. 检查内核是否编入 `cdc_ether` / `usbnet`（插上 `usbnet=1` 模块后观察 `adb shell ip link` 是否出现 `eth0` / `usb0`）。
  2. 核对 `EthernetTracker`、DHCP 分配、路由表、DNS 与 `ConnectivityManager` 表现。
  3. 任何切换 `usbnet` 前强制按 IMEI 备份并要求用户确认，确保随时可恢复原模式且不丢失 AT/ADB 口。
