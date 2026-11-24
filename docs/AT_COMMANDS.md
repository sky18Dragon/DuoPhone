# AT 指令与 URC 协议清单 (`AT_COMMANDS.md`)

> 状态标记：`CANDIDATE`（候选待实机验证）、`VERIFIED_REF`（参考仓库在 QDC507GLEFM21 上实测证实）、`UNSUPPORTED_REF`（参考仓库证实原厂固件返回 ERROR）、`VERIFIED_LOCAL`（本机实测通过）。

## 1. 基础识别与状态查询（Phase 1）
| 指令 | 用途 | 预期响应示例 | 可自动重试 | 当前状态 |
| --- | --- | --- | --- | --- |
| `AT` | 链路握手 | `OK` | 是（最多 2 次） | `VERIFIED_REF` |
| `ATE0` | 关闭命令回显 | `OK` | 是 | `VERIFIED_REF` |
| `ATI` | 模块产品与版本信息 | `Quectel / QDC507... / OK` | 是 | `VERIFIED_REF` |
| `AT+CGMI` | 制造商 | `Quectel` | 是 | `CANDIDATE` |
| `AT+CGMM` | 模块型号 | `EG25` / `QDC507` | 是 | `CANDIDATE` |
| `AT+CGMR` | 固件修订号 | `QDC507GLEFM21` | 是 | `VERIFIED_REF` |
| `AT+CGSN` | 模块 IMEI（日志脱敏） | 15 位数字 | 是 | `VERIFIED_REF` |
| `AT+CPIN?` | SIM 卡状态 | `+CPIN: READY` / `SIM PIN` / `CME ERROR: 10` | 是 | `VERIFIED_REF` |
| `AT+CSQ` | 信号强度 RSSI & BER | `+CSQ: 24,99`（`dbm = -113 + 2*rssi`） | 是 | `VERIFIED_REF` |
| `AT+COPS?` | 当前运营商与接入制式 | `+COPS: 0,0,"CHN-CT",7` | 是 | `VERIFIED_REF` |
| `AT+CREG?` | CS 域网络注册状态 | `+CREG: 0,1` / `0,5` | 是 | `CANDIDATE` |
| `AT+CGREG?` | PS 域网络注册状态 | `+CGREG: 0,1` | 是 | `CANDIDATE` |
| `AT+CEREG?` | EPS/LTE 网络注册状态 | `+CEREG: 0,1` / `0,5` | 是 | `VERIFIED_REF` |
| `AT+QCFG="usbnet"` | 查询 USB 网卡模式（只读） | `+QCFG: "usbnet",0` | 是 | `VERIFIED_REF` |
| `AT+QCFG="usbcfg"` | 查询 USB 接口组合（只读） | `+QCFG: "usbcfg",0x2CA3,0x4006,...` | 是 | `VERIFIED_REF` |
| `AT+QCFG="ims"` | 查询 IMS/VoLTE 状态（只读） | `+QCFG: "ims",1,1` | 是 | `VERIFIED_REF` |

## 2. 短信指令（Phase 2，优先 PDU 模式）
| 指令 | 用途 | 可自动重试 | 当前状态 |
| --- | --- | --- | --- |
| `AT+CMGF=0` | 设置 PDU 模式（主模式） | 是 | `VERIFIED_REF` |
| `AT+CMGF=1` | 文本模式（诊断备选） | 是 | `VERIFIED_REF` |
| `AT+CPMS?` / `AT+CPMS=?` | 查询短信存储区（`ME`/`SM`） | 是 | `VERIFIED_REF` |
| `AT+CNMI=2,1,0,0,0` | 新短信上报 `+CMTI` | 是 | `VERIFIED_REF` |
| `AT+CMGL=4` | PDU 模式列举全部短信 | 是 | `VERIFIED_REF` |
| `AT+CMGR=<idx>` | 读取指定索引短信 | 是 | `CANDIDATE` |
| `AT+CMGS=<tpdu_len>` + PDU + `0x1A` | 发送短信 | **严禁自动重试** | `VERIFIED_REF` |
| `AT+CMGD=<idx>` | 删除单条短信 | 否 | `VERIFIED_REF` |

## 3. 电话呼叫控制指令（Phase 3）
| 指令 | 用途 | 可自动重试 | 当前状态 |
| --- | --- | --- | --- |
| `AT+CLIP=1` | 开启来电号码显示 | 是 | `VERIFIED_REF` |
| `ATD<number>;` | 发起语音呼叫（分号结尾） | **严禁自动重试** | `VERIFIED_REF` |
| `ATA` | 接听来电 | 否 | `VERIFIED_REF` |
| `ATH` / `AT+CHUP` | 挂断 / 拒接 | 否 | `VERIFIED_REF` |
| `AT+CLCC` | 查询实时通话列表与状态 | 是 | `VERIFIED_REF` |
| `AT+CEER` | 查询通话失败扩展原因 | 是 | `VERIFIED_REF` |
| `AT+VTS="<digit>"` | 通话中发送 DTMF 音 | **严禁自动重试** | `VERIFIED_REF` |

## 4. 音频探测指令（Phase 4，先只读探测）
| 指令 | 参考仓库 QDC507GLEFM21 结果 | 当前状态 |
| --- | --- | --- |
| `AT+QPCMV=?` | 返回 `(0,1),(0-2)` | `VERIFIED_REF` |
| `AT+QPCMV?` | 返回当前值 | `CANDIDATE` |
| `AT+QPCMV=1,0` / `1,2` | 返回 `ERROR`（原厂固件裁剪） | `UNSUPPORTED_REF` |
| `AT+QDAI=?` | 返回 `ERROR` | `UNSUPPORTED_REF` |
| `AT+QCFG="usbaudio"` | 返回 `ERROR` | `UNSUPPORTED_REF` |

## 5. 异步 URC 分流清单
`AtEngine` 行帧器识别并异步推送以下 URC，绝不混入当前命令响应：
- `RING` / `+CRING: ...` → 来电振铃
- `+CLIP: "<num>",<type>,...` → 来电号码
- `+CMTI: "<mem>",<index>` → 新短信入库通知
- `+CMT: ...`（含下一行 PDU）→ 直推新短信
- `NO CARRIER` / `BUSY` / `NO ANSWER` / `NO DIALTONE` → 呼叫结束原因
- `CONNECT` / `MO CONNECTED` → 呼叫接通事件
- `+CREG:` / `+CGREG:` / `+CEREG:`（单参数 URC 形态）→ 网络注册变更
- `+QIND: "csq",...` / `+CPIN:` → 信号或 SIM 变更
