# QDC507 模块侧语音运行时专项研究 (`VOICE_RUNTIME.md`)

## 1. 背景与原厂限制
基于 MaVo、DJOneHubNative 与 DJ4Hub 源码及实机记录：
- QDC507 原厂固件（参考版本 `QDC507GLEFM21`，Linux `3.18.44 preempt mod_unload ARMv7 p2v8`）保留了 `AT+QPCMV=?` 查询响应 `(0,1),(0-2)`，但**裁剪了所有 AT 音频路由写入**（`AT+QPCMV=1,0`、`1,2`、`AT+QDAI`、`AT+QCFG="usbaudio"` 均返回 `ERROR`）。
- 原厂 `/proc/asound/cards` 为空（无 ALSA 声卡），但模块内核留有 `CONFIG_MODULES=y`、`CONFIG_MODVERSIONS=n`、`CONFIG_MODULE_SIG=n`，且文件系统内建 `libql_lib_audio.so.1`、`/usr/bin/alsaucm_test`、`/run/voc_svr`、`/dev/ttyGS0`、`/sys/class/android_usb/f_audio/audio_enable`。

## 2. 可逆临时加载架构（不刷机、不改启动区）
Phase 4 在实机先执行只读 AT 探测（确认 `AT+QPCMV` 确实受限）并获得用户授权后，通过 Android App 内置的**直接 USB ADB 客户端**（匹配接口 `FF/42/01`，不依赖 `adb` 二进制，无需 Android Root）完成以下步骤：

1. **只读前置校验（任一项不匹配即中止）**：
   - `id -u` 必须返回 `0`（root ADB）。
   - `uname -r` 必须严格等于 `3.18.44`。
   - 检查无冲突通话（`AT+CLCC` 为空）且未加载旧版 `qdc507_afe`。
2. **推送 SHA-256 校验通过的运行时文件至 `/tmp/duophone-voice`（RAM tmpfs，重启即清空）**：
   - `qdc507_aprv3.ko` — SHA-256: `3d82d3dec4f1e323201bba87156df9d41438e08314097353f2607f9117211d4a`（GPL-2.0，外部导入不进仓库）
   - `qdc507_voice.ko` — SHA-256: `ed3821682d5309969a01c764192c83feff9669c61ef237c69475cd1619cf296c`（GPL-2.0，外部导入不进仓库）
   - `mavo-pcm-bridge.armv7` — SHA-256: `88d47c15e61d1428a59c821fed804c2e6490e82859a085062f21966b58d167fc`（MIT，可由 `module/mavo_pcm_bridge.c` 交叉编译复现）
3. **加载驱动并等待声卡节点**：
   - `insmod qdc507_aprv3.ko` → `insmod qdc507_voice.ko`
   - 轮询确认 `/proc/asound/cards` 出现 `mdm9607-tomtom-i2s-snd-card` 以及 `/dev/snd/controlC0`, `pcmC0D4p`, `pcmC0D4c`, `pcmC0D5p`, `pcmC0D6c`。
4. **加载 ACDB VoLTE 校准**：
   - 启动 `/usr/bin/alsaucm_test`，依次向 `/run/alsaucm_test` 写入：
     - `open snd_soc_msm_9x07_Tomtom_I2S`
     - `set _verb VoLTE`
     - `set _enadev Auxpcm Rx`
     - `set _enadev Auxpcm Tx`
   - 等待校准日志出现 `ACDB -> Sent VocProc Cal!`（缺失校准会导致内核报 `ADSP_EFAILED` 与无声）。
5. **启动通话路由会话（两种路径评估）**：
   - **路径 1（UAC1 优先）**：运行 `mavo-pcm-bridge.armv7 --voice-route-session --verbose`：关闭旧 `SEC_AUX_PCM` mixer，开启 `AFE_PCM_RX_Voice Mixer VoLTE` 与 `VoLTE_Tx Mixer AFE_PCM_TX_VoLTE`，置 `/sys/class/android_usb/f_audio/audio_enable = 1`，打开 hostless `hw:0,4`（8kHz / 1ch / S16_LE）。注意若 USB functions 缺少 `audio`，需评估临时启用 UAC 或使用路径 2。
   - **路径 2（原始 USB Bulk PCM 备选 — Android 极佳兜底！）**：`mavo_pcm_bridge.c` 内置了 `/dev/ttyGS0`（对应 USB IF1 Bulk IN/OUT）的 8kHz mono S16_LE 原始 PCM 双向桥接模式！若 Android 16 Audio HAL 在 UAC 并发路由上存在独占限制（见 `AUDIO_BRIDGE.md` 方案 B），**路径 2 可以完全绕开 Android USB Audio 抢占系统默认声卡的问题，直接通过 Android `UsbDeviceConnection.bulkTransfer` 在 IF1 收发 8kHz PCM！**
6. **安全回滚与重连恢复**：
   - 挂断时校验 PID + `/proc/$pid/stat` 启动时间 + cmdline，发 `SIGTERM` 让 helper 优雅关闭 PCM 与回滚 mixer，随后写 `audio_enable=0` 并向 `/run/voc_svr` 写 `T\n`、`T\n`、`B\n`。
   - **绝不热卸载 `qdc507_aprv3` / `qdc507_voice`**（避免延迟 DSP 回调引发内核崩溃）；模块断电重启后自动恢复至干净原厂态，App 重连检测到 `/proc/asound/cards` 为空后按需重新准备。
7. **新模块 ADB 未开启与 IMS 核验**：
   - 若实机 `AT+QCFG="usbcfg"` 显示 `adb=0`，需先按 IMEI 备份配置、由用户显式确认后，才进行旧式 8 位 `AT+QADBKEY?` 授权与仅开启 ADB 位。
   - 同时检查 `AT+QCFG="ims"`（确认 `1,1`）和 `volte_disable=0`。
