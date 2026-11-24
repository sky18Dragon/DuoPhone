# USB 通信与接口探测设计 (`USB_INTERFACE.md`)

## 1. Android USB Host API 实现要点
- 在 `AndroidManifest.xml` 声明 `<uses-feature android:name="android.hardware.usb.host" android:required="true" />`。
- `res/xml/usb_device_filter.xml` 同时注册：
  - `vendor-id="11427" product-id="16390"` (`0x2CA3:0x4006`)
  - `vendor-id="11388" product-id="293"` (`0x2C7C:0x0125`)
- 权限请求 `PendingIntent` 在 Android 12+ (`SDK >= 31`) 使用 `FLAG_UPDATE_CURRENT or FLAG_MUTABLE` 并显式绑定 `setPackage(context.packageName)`（Android 14+ 安全要求：可变 PendingIntent 必须显式指定包名，否则抛 `IllegalArgumentException`！）。
- 动态广播注册在 Android 13+ (`SDK >= 33`) 必须区分：
  - 应用私有权限回调 `com.duophone.app.action.USB_PERMISSION` 使用 `RECEIVER_NOT_EXPORTED`（注意系统发回该广播时需验证在 Android 16 实机上能正常收到——若系统跨进程发送带包名显式 Intent，`RECEIVER_NOT_EXPORTED` 可收到；实机验证确认）。
  - 系统广播 `ACTION_USB_DEVICE_ATTACHED` / `ACTION_USB_DEVICE_DETACHED` 使用 `RECEIVER_EXPORTED`（因为发送方是 `system_server`！CardDock 用 `RECEIVER_NOT_EXPORTED` 监听系统广播在某些 Android 14+/16 ROM 上有漏收风险，这里我们拆分成两个 receiver 或明确区分，保证零漏收）。

## 2. 安全 AT 接口探测算法（三级递进）
1. **枚举所有 `UsbInterface` 并分类**：
   - 显式排除：
     - `class == 0x01` (Audio / UAC)
     - `class == 0x02` (CDC Control: ECM/MBIM/RNDIS)
     - `class == 0x0A` (CDC Data)
     - `class == 0x08` (Mass Storage)
     - `class == 0x09` (Hub)
     - `class == 0xE0` (Wireless / RNDIS)
     - `class == 0xFF && subclass == 0x42 && protocol == 0x01` (**ADB 接口，严禁触碰！**)
   - 仅保留同时具备至少 1 个 `BULK IN` 和 1 个 `BULK OUT` 端点的候选接口。
2. **候选接口优先级排序**：
   - 优先级 1：`bInterfaceNumber == 2`（标准 AT 口）
   - 优先级 2：`bInterfaceNumber == 3`（Modem AT 口）
   - 优先级 3：其余非 Diag 候选接口（`bInterfaceNumber != 0` 且非 QMI 3-endpoint 网卡口）
   - 优先级 4（仅在深度探测阶段兜底）：其他剩余候选接口（默认跳过 `bInterfaceNumber == 0` Diag 口除非全部失败且显式开启兜底，防止 USB 阻塞）。
3. **三级探测流程**：
   - **Pass 1（快速）**：对优先级 1–3 接口，拉高 DTR+RTS，发 `AT\r\n`，等待 `OK`（1000ms）。
   - **Pass 2（深入）**：尝试 DTR=ON / OFF，分别发 `AT\r\n`、`ATI\r\n`（1600ms）。
   - **Pass 3（冷启动延迟恢复）**：等待 3000ms 应对模块重启刚枚举完毕，再执行一次深入探测。
4. **严格释放**：任何未命中的接口在退出前立即停止 reader 并 `releaseInterface(intf)`；设备断开或 `bulkTransfer` 连续硬错误立即触发 `RECOVERING` / `DISCONNECTED` 并完整关闭 `UsbDeviceConnection`。
