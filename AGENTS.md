# Duo Phone — AI 开发与工程规范 (`AGENTS.md`)

## 1. 项目定位
Duo Phone 是面向 **Retroid Pocket Duo**（Android 16 / SDK 36 / ARM64 / 双屏掌机）+ **大疆第一代 4G 增强图传模块（DJI Cellular Dongle Gen 1 / QDC507 / MDM9207）** 的独立非 Root Android 通信终端应用。

## 2. 开发环境与构建命令
- **JDK**: OpenJDK 25.0.2 (`JAVA_HOME=<JDK_25_HOME>`)
- **Android SDK**: `<ANDROID_SDK_DIR>`（`android-36`, `build-tools/36.0.0`, `ndk/28.2.13676358`, `cmake/3.22.1`）
- **ADB**: `<ANDROID_SDK_DIR>/platform-tools/adb`
- **构建系统**: Gradle 9.x + AGP 9.x（内置 Kotlin 支持）+ Jetpack Compose

常用命令：
```bash
export JAVA_HOME=/path/to/jdk-25
export ANDROID_HOME=/path/to/Android/sdk

./gradlew assembleDebug
./gradlew testDebugUnitTest
./gradlew lintDebug
./gradlew connectedDebugAndroidTest

# 部署至 Retroid Pocket Duo（优先使用无线 ADB 以释放 USB-C 给 QDC507）
adb install -r app/build/outputs/apk/debug/DuoPhone-debug.apk
adb logcat -s DUO_USB DUO_AT DUO_URC DUO_SMS DUO_CALL DUO_AUDIO DUO_MODEM DUO_NETWORK DUO_POWER DUO_DISPLAY DUO_INPUT DUO_SERVICE
```

## 3. 代码分层结构
```text
app/src/main/java/com/duophone/app/
├── ui/           # Jetpack Compose UI、双屏 Presentation/多屏适配、实体按键交互
├── domain/       # 电话状态机、短信会话、联系人与业务用例
├── service/      # ModemService 前台服务与生命周期恢复
├── protocol/     # AT Engine、命令队列、URC 解析器、PDU 短信编解码
├── audio/        # 双向 PCM 音频桥接（Android AudioRecord/AudioTrack + UAC/PCM 路由）
├── device/       # USB Host 枚举、安全接口探测、QDC507 ADB 语音运行时初始化
├── data/         # SQLite/Room 短信、通话记录、配置备份持久化
└── diagnostics/  # 分类脱敏日志（DuoLogger）、设备诊断、AT 调试终端
```

## 4. 工程铁律
1. **非 Root 优先**：不修改 Android 系统、不刷写 QDC507 固件（严禁跨刷 EG25-G 固件）。
2. **可逆操作**：模块语音运行时仅部署到 `/tmp`，不写 boot/MTD/NV/EDL；任何 `AT+QCFG` 写操作前必须按 IMEI 备份并获得用户明确确认。
3. **证据驱动**：不得将 APK 编译成功、模拟测试或呼叫控制成功等同于双向语音/实机功能验收；不得编造硬件能力或测试结果。
4. **验收解耦**：“呼叫控制成功（ATD/ATA/ATH/+CLCC）”与“双向语音通话成功（上行+下行+全双工）”必须独立验收。
5. **安全探测**：探测 USB AT 接口时必须显式跳过 ADB (`FF/42/01`)、UAC (`01/*/*`)、大容量存储 (`08/*/*`)，并优先探测 IF2/IF3，避免重启后盲探 Diag IF0 导致挂起。
6. **禁止自动重试非幂等业务指令**：绝不对 `ATD`、`AT+CMGS`、`AT+CFUN=1,1` 进行无条件自动重试。
7. **隐私与日志脱敏**：默认对手机号、IMEI、IMSI、ICCID、短信正文、验证码进行脱敏，禁止上传第三方服务，禁止提交真实隐私信息到 Git。
8. **许可证隔离**：本项目主代码与 MIT 参考实现（CardDock、MaVo 主机/桥接设计）保持清晰边界；严禁从 PolyForm Noncommercial 仓库复制源码；若未来引入 GPL-2.0 内核模块（`qdc507_aprv3.ko` / `qdc507_voice.ko`），必须独立存放并附带 `COPYING-GPL-2.0` 与声明。

## 5. PDCA 自主开发流程
每轮迭代严格执行：
- **Plan**: 阅读源码/硬件数据 → 明确问题与风险 → 更新 `docs/TASKS.md` 与 `docs/DECISIONS.md`。
- **Do**: 编码实现 → 编写单元测试 → Gradle 编译 → Lint → 真机部署并采集 `adb logcat`。
- **Check**: 核对编译、单测、Lint、实机日志与 `docs/TEST_PLAN.md` 验收项；定位根因。
- **Act**: 修复缺陷 → 回归验证 → 更新 `docs/TEST_REPORT.md`、`docs/KNOWN_ISSUES.md`、`docs/logs/YYYY-MM-DD.md` → 小粒度规范化 Git Commit。

## 6. Git 分支与提交规范
- 主分支：`main`（稳定发布）、`develop`（日常集成分支）、`feature/<module>`（功能分支）。
- 提交格式：`type(scope): concise description`（如 `feat(usb): ...`, `feat(at): ...`, `test(at): ...`, `docs(phase0): ...`）。
- 未经用户授权不得执行 `git push` 或破坏性 Git 操作。
