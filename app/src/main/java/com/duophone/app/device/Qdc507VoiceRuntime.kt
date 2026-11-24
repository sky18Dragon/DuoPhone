package com.duophone.app.device

import com.duophone.app.diagnostics.DuoLogger
import com.duophone.app.diagnostics.LogCategory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.security.MessageDigest

enum class VoiceRuntimeStage {
    UNKNOWN,
    ADB_UNAVAILABLE,
    PROBE_ONLY_READY,
    ASSETS_MISSING,
    PREPARED,
    ROUTE_ACTIVE,
    FAILED,
}

data class VoiceAssetSpec(
    val fileName: String,
    val expectedSha256: String,
    val remoteModeBits: Int,
    val licenseNote: String,
)

data class LocalAssetVerification(
    val spec: VoiceAssetSpec,
    val exists: Boolean,
    val actualSha256: String?,
    val valid: Boolean,
)

data class VoiceRuntimeSnapshot(
    val stage: VoiceRuntimeStage = VoiceRuntimeStage.UNKNOWN,
    val adbDetected: Boolean = false,
    val uacDetected: Boolean = false,
    val kernelVersion: String = "",
    val soundCardPresent: Boolean = false,
    val pcmDevices: List<String> = emptyList(),
    val aprLoaded: Boolean = false,
    val voiceLoaded: Boolean = false,
    val bridgePid: Int? = null,
    val bridgeStartTicks: Long? = null,
    val localAssets: List<LocalAssetVerification> = emptyList(),
    val lastSummary: String = "尚未探测 QDC507 语音运行时",
    val lastError: String? = null,
) {
    val requiredPcmNodesPresent: Boolean
        get() = REQUIRED_PCM_NODES.all { node -> pcmDevices.contains(node) }

    companion object {
        val REQUIRED_PCM_NODES = listOf("controlC0", "pcmC0D4p", "pcmC0D4c", "pcmC0D5p", "pcmC0D6c")
    }
}

object Qdc507VoiceAssets {
    const val REMOTE_TMP_DIR = "/tmp/duophone-voice"

    val APR_KO = VoiceAssetSpec(
        fileName = "qdc507_aprv3.ko",
        expectedSha256 = "3d82d3dec4f1e323201bba87156df9d41438e08314097353f2607f9117211d4a",
        remoteModeBits = 0b110_100_100, // 0644
        licenseNote = "GPL-2.0 (external user-staged; never bundled in APK)",
    )

    val VOICE_KO = VoiceAssetSpec(
        fileName = "qdc507_voice.ko",
        expectedSha256 = "ed3821682d5309969a01c764192c83feff9669c61ef237c69475cd1619cf296c",
        remoteModeBits = 0b110_100_100, // 0644
        licenseNote = "GPL-2.0 (external user-staged; never bundled in APK)",
    )

    val PCM_BRIDGE = VoiceAssetSpec(
        fileName = "mavo-pcm-bridge.armv7",
        expectedSha256 = "88d47c15e61d1428a59c821fed804c2e6490e82859a085062f21966b58d167fc",
        remoteModeBits = 0b111_101_101, // 0755
        licenseNote = "MIT (external/side-staged binary)",
    )

    val ALL = listOf(APR_KO, VOICE_KO, PCM_BRIDGE)
}

object Sha256Digest {
    fun hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString(separator = "") { b -> "%02x".format(b) }
    }

    fun hex(file: File): String = hex(file.readBytes())
}

data class VoiceProbeParsed(
    val kernelVersion: String,
    val soundCardPresent: Boolean,
    val pcmDevices: List<String>,
    val aprLoaded: Boolean,
    val voiceLoaded: Boolean,
    val activeBridgePid: Int?,
    val activeBridgeStartTicks: Long?,
)

object VoiceProbeParser {
    fun parseProbeOutput(stdout: String): VoiceProbeParsed {
        val sections = mutableMapOf<String, MutableList<String>>()
        var currentKey = ""
        for (rawLine in stdout.replace("\r\n", "\n").lines()) {
            val line = rawLine.trim()
            if (line.startsWith("===DUO:") && line.endsWith("===")) {
                currentKey = line.removePrefix("===DUO:").removeSuffix("===")
                sections.putIfAbsent(currentKey, mutableListOf())
            } else if (currentKey.isNotEmpty() && line.isNotEmpty()) {
                sections[currentKey]?.add(line)
            }
        }

        val kernel = sections["UNAME"]?.firstOrNull().orEmpty()
        val cardsText = sections["CARDS"]?.joinToString("\n").orEmpty()
        val soundCardPresent = cardsText.contains("mdm9607", ignoreCase = true) ||
            cardsText.contains("tomtom", ignoreCase = true) ||
            cardsText.lineSequence().any { it.trim().matches(Regex("^\\d+\\s+\\[.*")) }

        val pcmDevices = sections["SND"].orEmpty()
            .flatMap { it.split(Regex("\\s+")) }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .sorted()

        val modulesText = sections["MODULES"]?.joinToString("\n").orEmpty()
        val aprLoaded = modulesText.contains("qdc507_aprv3")
        val voiceLoaded = modulesText.contains("qdc507_voice")

        val bridgeLine = sections["BRIDGE"]?.firstOrNull().orEmpty()
        val parts = bridgeLine.split(':').map { it.trim() }
        val pid = parts.getOrNull(0)?.toIntOrNull()
        val ticks = parts.getOrNull(1)?.toLongOrNull()

        return VoiceProbeParsed(
            kernelVersion = kernel,
            soundCardPresent = soundCardPresent,
            pcmDevices = pcmDevices,
            aprLoaded = aprLoaded,
            voiceLoaded = voiceLoaded,
            activeBridgePid = pid,
            activeBridgeStartTicks = ticks,
        )
    }

    /**
     * Parses field #22 (starttime in clock ticks) from `/proc/<pid>/stat`, safely skipping `(<comm>)`
     * even if the executable name contains spaces or parentheses.
     */
    fun parseProcStatStartTicks(statLine: String): Long? {
        val closeParen = statLine.lastIndexOf(')')
        if (closeParen < 0 || closeParen + 2 >= statLine.length) return null
        val fieldsAfterComm = statLine.substring(closeParen + 2).trim().split(Regex("\\s+"))
        // Field #3 (state) is index 0 in fieldsAfterComm; field #22 (starttime) is index 19.
        return fieldsAfterComm.getOrNull(19)?.toLongOrNull()
    }
}

class Qdc507VoiceRuntime(
    private val adbRunner: AdbShellRunner,
    private val voiceRuntimeDir: File,
) {
    private val _state = MutableStateFlow(VoiceRuntimeSnapshot(localAssets = inspectLocalAssets()))
    val state: StateFlow<VoiceRuntimeSnapshot> = _state.asStateFlow()

    fun inspectLocalAssets(): List<LocalAssetVerification> {
        return Qdc507VoiceAssets.ALL.map { spec ->
            val file = File(voiceRuntimeDir, spec.fileName)
            if (!file.isFile) {
                LocalAssetVerification(spec = spec, exists = false, actualSha256 = null, valid = false)
            } else {
                val actual = runCatching { Sha256Digest.hex(file) }.getOrNull()
                val valid = actual != null && actual.equals(spec.expectedSha256, ignoreCase = true)
                LocalAssetVerification(spec = spec, exists = true, actualSha256 = actual, valid = valid)
            }
        }
    }

    suspend fun probeReadOnly(uacDetected: Boolean = _state.value.uacDetected): Result<VoiceRuntimeSnapshot> {
        val localAssets = inspectLocalAssets()
        val adbPresent = adbRunner.isAdbInterfacePresent()
        if (!adbPresent) {
            val snap = _state.value.copy(
                stage = VoiceRuntimeStage.ADB_UNAVAILABLE,
                adbDetected = false,
                uacDetected = uacDetected,
                localAssets = localAssets,
                lastSummary = "未检测到 QDC507 USB ADB 接口 (FF/42/01)",
                lastError = null,
            )
            _state.value = snap
            return Result.success(snap)
        }

        val probeCmd = buildString {
            append("printf '===DUO:UNAME===\\n'; uname -r; ")
            append("printf '===DUO:CARDS===\\n'; cat /proc/asound/cards 2>/dev/null || true; ")
            append("printf '===DUO:SND===\\n'; ls /dev/snd 2>/dev/null || true; ")
            append("printf '===DUO:MODULES===\\n'; cat /proc/modules 2>/dev/null || true; ")
            append("printf '===DUO:BRIDGE===\\n'; ")
            append("if [ -f ${Qdc507VoiceAssets.REMOTE_TMP_DIR}/bridge.pid ]; then ")
            append("p=$(cat ${Qdc507VoiceAssets.REMOTE_TMP_DIR}/bridge.pid 2>/dev/null); ")
            append("if [ -n \"\$p\" ] && [ -r \"/proc/\$p/stat\" ]; then ")
            append("st=$(awk '{print \$NF}' /dev/null 2>/dev/null); ")
            append("t=$(cut -d')' -f2 \"/proc/\$p/stat\" | awk '{print \$20}'); ")
            append("echo \"\$p:\$t\"; fi; fi")
        }

        return adbRunner.runShell(probeCmd).map { shell ->
            if (shell.exitCode != 0) {
                error("Read-only probe exited with ${shell.exitCode}: ${shell.stdout}")
            }
            val parsed = VoiceProbeParser.parseProbeOutput(shell.stdout)
            val allAssetsValid = localAssets.all { it.valid }
            val nextStage = when {
                parsed.activeBridgePid != null -> VoiceRuntimeStage.ROUTE_ACTIVE
                parsed.aprLoaded && parsed.voiceLoaded &&
                    VoiceRuntimeSnapshot.REQUIRED_PCM_NODES.all { it in parsed.pcmDevices } -> VoiceRuntimeStage.PREPARED
                !allAssetsValid -> VoiceRuntimeStage.PROBE_ONLY_READY
                else -> VoiceRuntimeStage.PROBE_ONLY_READY
            }
            val summary = buildString {
                append("kernel=").append(parsed.kernelVersion.ifBlank { "unknown" })
                append(" | apr=").append(parsed.aprLoaded)
                append(" voice=").append(parsed.voiceLoaded)
                append(" | pcm=").append(parsed.pcmDevices.size)
                if (parsed.activeBridgePid != null) {
                    append(" | bridgePid=").append(parsed.activeBridgePid)
                }
            }
            val snap = VoiceRuntimeSnapshot(
                stage = nextStage,
                adbDetected = true,
                uacDetected = uacDetected,
                kernelVersion = parsed.kernelVersion,
                soundCardPresent = parsed.soundCardPresent,
                pcmDevices = parsed.pcmDevices,
                aprLoaded = parsed.aprLoaded,
                voiceLoaded = parsed.voiceLoaded,
                bridgePid = parsed.activeBridgePid,
                bridgeStartTicks = parsed.activeBridgeStartTicks,
                localAssets = localAssets,
                lastSummary = summary,
                lastError = null,
            )
            _state.value = snap
            DuoLogger.i(LogCategory.AUDIO, "voice_probe", summary)
            snap
        }.onFailure { err ->
            val snap = _state.value.copy(
                stage = VoiceRuntimeStage.FAILED,
                adbDetected = true,
                uacDetected = uacDetected,
                localAssets = localAssets,
                lastError = err.message,
                lastSummary = "只读语音环境探测失败",
            )
            _state.value = snap
        }
    }

    suspend fun prepareReversibleRuntime(
        uacDetected: Boolean = _state.value.uacDetected,
    ): Result<VoiceRuntimeSnapshot> {
        val localAssets = inspectLocalAssets()
        val missingOrInvalid = localAssets.filter { !it.valid }
        if (missingOrInvalid.isNotEmpty()) {
            val detail = missingOrInvalid.joinToString("; ") { item ->
                val reason = if (!item.exists) "缺失" else "SHA-256不匹配(${item.actualSha256?.take(12)})"
                "${item.spec.fileName}:$reason"
            }
            val msg = "本地 voice_runtime 资源未就绪 ($detail)。根据 GPL-2.0 隔离规则，APK 不内置二进制，请放置到 ${voiceRuntimeDir.absolutePath}"
            _state.value = _state.value.copy(
                stage = VoiceRuntimeStage.ASSETS_MISSING,
                localAssets = localAssets,
                uacDetected = uacDetected,
                lastSummary = "等待外部语音运行时资源",
                lastError = msg,
            )
            return Result.failure(IllegalStateException(msg))
        }

        if (!adbRunner.isAdbInterfacePresent()) {
            val msg = "未检测到 QDC507 USB ADB 接口 (FF/42/01)，无法部署 /tmp 运行时"
            _state.value = _state.value.copy(
                stage = VoiceRuntimeStage.ADB_UNAVAILABLE,
                adbDetected = false,
                uacDetected = uacDetected,
                localAssets = localAssets,
                lastSummary = msg,
                lastError = msg,
            )
            return Result.failure(IllegalStateException(msg))
        }

        return runCatching {
            val mkdirRes = adbRunner.runShell("mkdir -p ${Qdc507VoiceAssets.REMOTE_TMP_DIR}").getOrThrow()
            require(mkdirRes.exitCode == 0) { "Failed to create ${Qdc507VoiceAssets.REMOTE_TMP_DIR}: ${mkdirRes.stdout}" }

            for (asset in Qdc507VoiceAssets.ALL) {
                val localFile = File(voiceRuntimeDir, asset.fileName)
                val remotePath = "${Qdc507VoiceAssets.REMOTE_TMP_DIR}/${asset.fileName}"
                val remoteCheck = adbRunner.runShell("sha256sum '$remotePath' 2>/dev/null | awk '{print \$1}'").getOrThrow()
                val alreadyMatches = remoteCheck.stdout.trim().equals(asset.expectedSha256, ignoreCase = true)
                if (!alreadyMatches) {
                    adbRunner.pushFile(
                        localBytes = localFile.readBytes(),
                        remotePath = remotePath,
                        modeBits = asset.remoteModeBits,
                    ).getOrThrow()
                }
            }

            // Reversible /tmp-only load: never writes boot/MTD/NV and never runs rmmod on cleanup.
            val dir = Qdc507VoiceAssets.REMOTE_TMP_DIR
            val initScript = buildString {
                append("set -e; ")
                append("if ! grep -q '^qdc507_aprv3 ' /proc/modules; then ")
                append("insmod $dir/${Qdc507VoiceAssets.APR_KO.fileName}; ")
                append("fi; ")
                append("if ! grep -q '^qdc507_voice ' /proc/modules; then ")
                append("insmod $dir/${Qdc507VoiceAssets.VOICE_KO.fileName}; ")
                append("fi; ")
                append("chmod 0755 $dir/${Qdc507VoiceAssets.PCM_BRIDGE.fileName}; ")
                append("n=0; ready=0; while [ \$n -lt 60 ]; do ")
                append("if grep -Fq 'mdm9607-tomtom-i2s-snd-card' /proc/asound/cards 2>/dev/null && ")
                append("[ -c /dev/snd/controlC0 ] && [ -c /dev/snd/pcmC0D4p ] && [ -c /dev/snd/pcmC0D4c ] && ")
                append("[ -c /dev/snd/pcmC0D5p ] && [ -c /dev/snd/pcmC0D6c ]; then ready=1; break; fi; ")
                append("sleep 0.2; n=$((n+1)); done; test \"\$ready\" -eq 1; ")
                append("if ! grep -q 'ACDB -> Sent VocProc Cal!' '$dir/alsaucm.log' 2>/dev/null; then ")
                append("for p in /proc/[0-9]*; do [ -r \"\$p/cmdline\" ] || continue; ")
                append("if [ \"\$(tr '\\000' '\\n' < \"\$p/cmdline\" 2>/dev/null | sed -n '1p')\" = '/usr/bin/alsaucm_test' ]; then ")
                append("kill -TERM \"\${p##*/}\" 2>/dev/null || true; sleep 0.2; fi; done; ")
                append("rm -f /run/alsaucm_test '$dir/alsaucm.log'; ")
                append("nohup /usr/bin/alsaucm_test </dev/null >>'$dir/alsaucm.log' 2>&1 & ")
                append("n=0; while [ \$n -lt 40 ] && [ ! -p /run/alsaucm_test ]; do sleep 0.1; n=$((n+1)); done; ")
                append("test -p /run/alsaucm_test; ")
                append("printf 'open snd_soc_msm_9x07_Tomtom_I2S\\n' > /run/alsaucm_test; ")
                append("printf 'set _verb VoLTE\\n' > /run/alsaucm_test; ")
                append("printf 'set _enadev Auxpcm Rx\\n' > /run/alsaucm_test; ")
                append("printf 'set _enadev Auxpcm Tx\\n' > /run/alsaucm_test; ")
                append("n=0; while [ \$n -lt 80 ]; do ")
                append("grep -q 'ACDB -> Sent VocProc Cal!' '$dir/alsaucm.log' 2>/dev/null && break; ")
                append("sleep 0.1; n=$((n+1)); done; ")
                append("grep -q 'ACDB -> Sent VocProc Cal!' '$dir/alsaucm.log'; fi; ")
                append("test -p /run/voc_svr; ")
                append("$dir/${Qdc507VoiceAssets.PCM_BRIDGE.fileName} --check")
            }
            val initRes = adbRunner.runShell(initScript, timeoutMs = 25_000L).getOrThrow()
            require(initRes.exitCode == 0) {
                "QDC507 runtime init/--check failed (rc=${initRes.exitCode}): ${initRes.stdout}"
            }

            val probed = probeReadOnly(uacDetected = uacDetected).getOrThrow()
            val prepared = probed.copy(
                stage = VoiceRuntimeStage.PREPARED,
                lastSummary = "/tmp 可逆语音运行时就绪 (${probed.kernelVersion})",
                lastError = null,
            )
            _state.value = prepared
            DuoLogger.i(LogCategory.AUDIO, "voice_runtime_prepared", prepared.lastSummary)
            prepared
        }.onFailure { err ->
            DuoLogger.e(LogCategory.AUDIO, "voice_runtime_prepare_fail", "Failed to prepare runtime: ${err.message}")
            _state.value = _state.value.copy(
                stage = VoiceRuntimeStage.FAILED,
                localAssets = localAssets,
                uacDetected = uacDetected,
                lastSummary = "/tmp 语音运行时准备失败",
                lastError = err.message,
            )
        }
    }

    suspend fun startVoiceRoute(): Result<VoiceRuntimeSnapshot> {
        return runCatching {
            val current = _state.value
            if (current.stage != VoiceRuntimeStage.PREPARED && current.stage != VoiceRuntimeStage.ROUTE_ACTIVE) {
                prepareReversibleRuntime(uacDetected = current.uacDetected).getOrThrow()
            }
            if (_state.value.bridgePid != null) {
                stopVoiceRoute()
            }

            val dir = Qdc507VoiceAssets.REMOTE_TMP_DIR
            val bin = "$dir/${Qdc507VoiceAssets.PCM_BRIDGE.fileName}"
            val launchCmd = buildString {
                append("set -e; ")
                append("nohup '$bin' --voice-route-session --verbose >'$dir/bridge.log' 2>&1 </dev/null & ")
                append("p=$!; ")
                append("echo \"\$p\" >'$dir/bridge.pid'; ")
                append("sleep 1; ")
                append("if [ ! -r \"/proc/\$p/stat\" ]; then cat '$dir/bridge.log' 2>/dev/null; exit 1; fi; ")
                append("printf '===BLOG===\\n'; tail -n 15 '$dir/bridge.log' 2>/dev/null || true; ")
                append("printf '===STAT===\\n'; cat \"/proc/\$p/stat\"")
            }
            val launchRes = adbRunner.runShell(launchCmd, timeoutMs = 12_000L).getOrThrow()
            require(launchRes.exitCode == 0) {
                "Failed to start mavo-pcm-bridge: ${launchRes.stdout}"
            }
            val blogSection = launchRes.stdout.substringAfter("===BLOG===", "").substringBefore("===STAT===").trim()
            if (blogSection.isNotBlank()) {
                DuoLogger.i(LogCategory.AUDIO, "voice_route_log", blogSection.replace('\n', ' '))
            }
            val statSection = launchRes.stdout.substringAfter("===STAT===", launchRes.stdout).trim()
            val statLine = statSection.lineSequence().lastOrNull().orEmpty()
            val pid = statLine.substringBefore(' ').trim().toIntOrNull()
                ?: error("Could not parse started bridge PID from stat line")
            val ticks = VoiceProbeParser.parseProcStatStartTicks(statLine)

            val updated = _state.value.copy(
                stage = VoiceRuntimeStage.ROUTE_ACTIVE,
                bridgePid = pid,
                bridgeStartTicks = ticks,
                lastSummary = "QDC507 双向音频路由已启动 (PID=$pid)",
                lastError = null,
            )
            _state.value = updated
            DuoLogger.i(LogCategory.AUDIO, "voice_route_started", updated.lastSummary)
            updated
        }.onFailure { err ->
            DuoLogger.e(LogCategory.AUDIO, "voice_route_start_fail", "Start voice route failed: ${err.message}")
            _state.value = _state.value.copy(
                stage = VoiceRuntimeStage.FAILED,
                lastSummary = "QDC507 音频路由启动失败",
                lastError = err.message,
            )
        }
    }

    suspend fun stopVoiceRoute(): Result<VoiceRuntimeSnapshot> {
        val current = _state.value
        if (!adbRunner.isAdbInterfacePresent()) {
            val cleared = current.copy(
                stage = VoiceRuntimeStage.ADB_UNAVAILABLE,
                adbDetected = false,
                bridgePid = null,
                bridgeStartTicks = null,
                lastSummary = "USB ADB 已断开，清理本地路由状态",
                lastError = null,
            )
            _state.value = cleared
            return Result.success(cleared)
        }

        val dir = Qdc507VoiceAssets.REMOTE_TMP_DIR
        val trackedPid = current.bridgePid
        val trackedTicks = current.bridgeStartTicks

        // Safe reversible stop: verify PID start ticks before SIGTERM, reset voc_svr/audio_enable=0,
        // and NEVER rmmod qdc507_aprv3.ko / qdc507_voice.ko.
        val stopCmd = buildString {
            if (trackedPid != null) {
                append("if [ -r '/proc/$trackedPid/stat' ]; then ")
                if (trackedTicks != null) {
                    append("cur=$(cut -d')' -f2 '/proc/$trackedPid/stat' | awk '{print \$20}'); ")
                    append("if [ \"\$cur\" = '$trackedTicks' ]; then kill -TERM $trackedPid 2>/dev/null || true; fi; ")
                } else {
                    append("kill -TERM $trackedPid 2>/dev/null || true; ")
                }
                append("fi; ")
            }
            append("rm -f '$dir/bridge.pid'; ")
            append("if [ -w /sys/class/android_usb/f_audio/audio_enable ]; then ")
            append("echo 0 > /sys/class/android_usb/f_audio/audio_enable 2>/dev/null || true; fi; ")
            append("if [ -p /run/voc_svr ]; then printf 'T\\nT\\nB\\n' >/run/voc_svr 2>/dev/null || true; fi")
        }

        return runCatching {
            adbRunner.runShell(stopCmd, timeoutMs = 8_000L).getOrThrow()
            val nextStage = if (current.aprLoaded && current.voiceLoaded) {
                VoiceRuntimeStage.PREPARED
            } else {
                VoiceRuntimeStage.PROBE_ONLY_READY
            }
            val updated = current.copy(
                stage = nextStage,
                bridgePid = null,
                bridgeStartTicks = null,
                lastSummary = "QDC507 音频路由已安全回滚关闭（保留内核模块防崩溃）",
                lastError = null,
            )
            _state.value = updated
            DuoLogger.i(LogCategory.AUDIO, "voice_route_stopped", updated.lastSummary)
            updated
        }.onFailure { err ->
            val updated = current.copy(
                lastError = err.message,
                lastSummary = "停止 QDC507 音频路由时出现告警",
            )
            _state.value = updated
        }
    }
}
