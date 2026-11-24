# 短信 PDU 协议设计 (`SMS_PROTOCOL.md`)

## 1. 模式选择
Duo Phone 在 Phase 2 采用 **3GPP TS 23.040 PDU 模式 (`AT+CMGF=0`)** 作为主协议：
- 彻底避免 `AT+CSCS="UCS2"` 对 `AT+QCFG`/`AT+COPS?` 的副作用。
- 原生支持英文（GSM 7-bit）、中文/Emoji（UCS2 / UTF-16BE）、字母发件人（Alphanumeric Sender）、以及 8-bit/16-bit UDH 长短信分片与重组。

## 2. 编码规格（SMS-SUBMIT）
- SMSC 头：`0x00`（使用 SIM/网络默认 SMSC）。
- 首字节：单条 `0x01`，长短信带 UDH `0x41`。
- 号码格式：国际号码（`+` 开头）使用 TON/NPI `0x91`，本地号码使用 `0x81`，BCD 半字节交换编码（奇数位补 `F`）。
- 字符集选择：
  - 纯 GSM 7-bit 可表示字符且长度 ≤ 160（或长短信每片 153 septets）：可编码为 GSM 7-bit (`DCS = 0x00`)，或统一支持 UCS2 (`DCS = 0x08`)。
  - 含中文/非 GSM 字符：UCS2 (`DCS = 0x08`)，单条上限 70 UTF-16 码元；长短信带 6 字节 UDH (`05 00 03 <ref> <total> <seq>`) 每片上限 67 UTF-16 码元，且**严禁在 UTF-16 代理对（Emoji Surrogate Pair）中间切断**。
- 发送流程：`AT+CMGS=<tpduLength>\r` → 等待 `>` 提示符 → 发送大写十六进制 PDU + `0x1A`（Ctrl+Z）→ 等待 `+CMGS:` 或 `ERROR`（超时 60s，失败时发送 `0x1B` ESC 清理输入态，**绝不自动重发**）。

## 3. 解码与重组规格（SMS-DELIVER）
- 解析 SMSC 长度并跳过 → 检查 MTI (`firstOctet & 0x03 == 0`) 与 UDHI (`0x40`) → 解析发件人地址（支持国际/本地号码及 GSM 7-bit 字母发件人）→ 解析 PID/DCS/SCTS（含时区偏移与正负号位）→ 按 DCS 分发 GSM 7-bit（含 UDH 7-bit 填充位对齐）、8-bit 二进制或 UCS2 解码。
- 长短信重组：按 `(sender, concatRef, total, dcs)` 聚合，在合理时间窗（如 12 小时）内按 `seq = 1..total` 排序拼接，保留各分片原始 modem 存储索引用于后续清理；同时本地 SQLite/Room 持久化避免模块存储满后丢失历史记录。
- 验证码提取：基于中英文关键词（验证码/校验码/动态码/OTP/code）与 4–8 位数字边界正则，优先 6 位其次 4 位。
