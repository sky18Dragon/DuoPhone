package com.duophone.app.device

import com.duophone.app.diagnostics.DuoLogger
import com.duophone.app.diagnostics.LogCategory
import com.duophone.app.protocol.AtEngine
import com.duophone.app.protocol.AtResponseParser
import com.duophone.app.protocol.CallAtParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.security.MessageDigest

/**
 * Clean-room Unix MD5-crypt (`$1$`) key derivation used exclusively in memory for
 * legacy 8-digit Quectel/QDC507 `AT+QADBKEY?` interoperability after user consent.
 */
object Md5Crypt {
    private const val ITOA64 = "./0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
    private const val MAGIC = "\$1\$"

    fun crypt(password: String, saltInput: String): String {
        val cleanSalt = saltInput
            .removePrefix(MAGIC)
            .substringBefore('$')
            .take(8)
        val pwBytes = password.toByteArray(Charsets.UTF_8)
        val saltBytes = cleanSalt.toByteArray(Charsets.US_ASCII)

        val alt = MessageDigest.getInstance("MD5").apply {
            update(pwBytes)
            update(saltBytes)
            update(pwBytes)
        }.digest()

        val ctx = MessageDigest.getInstance("MD5").apply {
            update(pwBytes)
            update(MAGIC.toByteArray(Charsets.US_ASCII))
            update(saltBytes)
            var remaining = pwBytes.size
            while (remaining > 0) {
                update(alt, 0, minOf(16, remaining))
                remaining -= 16
            }
            var i = pwBytes.size
            while (i > 0) {
                if ((i and 1) != 0) {
                    update(0.toByte())
                } else {
                    update(pwBytes[0])
                }
                i = i ushr 1
            }
        }
        var current = ctx.digest()

        for (round in 0 until 1000) {
            val step = MessageDigest.getInstance("MD5")
            if ((round and 1) != 0) step.update(pwBytes) else step.update(current)
            if ((round % 3) != 0) step.update(saltBytes)
            if ((round % 7) != 0) step.update(pwBytes)
            if ((round and 1) != 0) step.update(current) else step.update(pwBytes)
            current = step.digest()
        }

        val out = StringBuilder(MAGIC).append(cleanSalt).append('$')
        append64(out, current[0], current[6], current[12], 4)
        append64(out, current[1], current[7], current[13], 4)
        append64(out, current[2], current[8], current[14], 4)
        append64(out, current[3], current[9], current[15], 4)
        append64(out, current[4], current[10], current[5], 4)
        append64(out, 0, 0, current[11], 2)
        return out.toString()
    }

    private fun append64(out: StringBuilder, b2: Byte, b1: Byte, b0: Byte, count: Int) {
        var value = ((b2.toInt() and 0xFF) shl 16) or
            ((b1.toInt() and 0xFF) shl 8) or
            (b0.toInt() and 0xFF)
        repeat(count) {
            out.append(ITOA64[value and 0x3F])
            value = value ushr 6
        }
    }
}

object QadbKeyDeriver {
    private const val SHARED_PASSWORD = "SH_adb_quectel"
    private val eightDigitChallenge = Regex("""^\d{8}$""")

    fun parseChallenge(rawResponse: String): String? {
        for (line in rawResponse.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.uppercase().startsWith("+QADBKEY:")) {
                val token = trimmed.substringAfter(':').trim().trim('"')
                if (eightDigitChallenge.matches(token)) return token
            }
        }
        return null
    }

    fun deriveLegacyKey(challenge: String): String? {
        val clean = challenge.trim()
        if (!eightDigitChallenge.matches(clean)) return null
        val full = Md5Crypt.crypt(SHARED_PASSWORD, clean)
        val digestPart = full.substringAfterLast('$')
        return if (digestPart.length >= 15) digestPart.substring(0, 15) else null
    }
}

data class UsbCfgTuple(
    val vidHex: String,
    val pidHex: String,
    val diag: Int,
    val nmea: Int,
    val at: Int,
    val modem: Int,
    val rmnet: Int,
    val adb: Int,
    val uac: Int,
) {
    fun withAdbAndUac(adbEnabled: Boolean, uacEnabled: Boolean): UsbCfgTuple =
        copy(adb = if (adbEnabled) 1 else 0, uac = if (uacEnabled) 1 else 0)

    fun toWriteCommand(): String =
        "AT+QCFG=\"usbcfg\",$vidHex,$pidHex,$diag,$nmea,$at,$modem,$rmnet,$adb,$uac"

    fun summaryTuple(): String = "$vidHex,$pidHex,$diag,$nmea,$at,$modem,$rmnet,$adb,$uac"

    companion object {
        private val LINE_REGEX = Regex(
            """\+QCFG:\s*"usbcfg"\s*,\s*(0x[0-9A-Fa-f]+)\s*,\s*(0x[0-9A-Fa-f]+)\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)""",
            RegexOption.IGNORE_CASE,
        )

        fun parse(raw: String): UsbCfgTuple? {
            val match = LINE_REGEX.find(raw) ?: return null
            val g = match.groupValues
            return UsbCfgTuple(
                vidHex = g[1],
                pidHex = g[2],
                diag = g[3].toIntOrNull() ?: return null,
                nmea = g[4].toIntOrNull() ?: return null,
                at = g[5].toIntOrNull() ?: return null,
                modem = g[6].toIntOrNull() ?: return null,
                rmnet = g[7].toIntOrNull() ?: return null,
                adb = g[8].toIntOrNull() ?: return null,
                uac = g[9].toIntOrNull() ?: return null,
            )
        }
    }
}

data class ModemBackupRecord(
    val imei: String,
    val firmwareRevision: String,
    val usbnetMode: Int?,
    val imsRaw: String,
    val qpcmvSupportRaw: String,
    val usbCfg: UsbCfgTuple,
    val backedUpAtMs: Long,
    val backupFilePath: String,
)

data class ModemConfigBackupState(
    val isBusy: Boolean = false,
    val currentImei: String = "",
    val currentUsbCfg: UsbCfgTuple? = null,
    val backup: ModemBackupRecord? = null,
    val lastStatus: String = "尚未执行配置备份",
    val lastError: String? = null,
) {
    val hasMatchingBackup: Boolean
        get() = backup != null && (currentImei.isBlank() || backup.imei == currentImei)

    val proposedEnableCommand: String?
        get() = currentUsbCfg?.withAdbAndUac(adbEnabled = true, uacEnabled = true)?.toWriteCommand()
}

class Qdc507ConfigManager(
    private val atEngine: AtEngine,
    private val backupDir: File,
) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow(loadLatestSavedState())
    val state: StateFlow<ModemConfigBackupState> = _state.asStateFlow()

    suspend fun backupCurrentConfig(): Result<ModemBackupRecord> = mutex.withLock {
        if (!atEngine.isRunning) {
            return@withLock failWith("AT 端口未就绪，无法读取模块配置")
        }
        _state.value = _state.value.copy(isBusy = true, lastError = null)
        return@withLock try {
            val imeiRes = atEngine.execute("AT+CGSN", timeoutMs = 3_000L, maxRetries = 1)
            val imei = AtResponseParser.parseImei(imeiRes)
            require(imei.length >= 14) { "无法读取有效模块 IMEI，拒绝执行写入前备份" }

            val cgmrRes = atEngine.execute("AT+CGMR", timeoutMs = 3_000L, maxRetries = 1)
            val rev = AtResponseParser.parseSingleValueLine(cgmrRes, "+CGMR:")
            val usbcfgRes = atEngine.execute("AT+QCFG=\"usbcfg\"", timeoutMs = 3_000L, maxRetries = 1)
            val tuple = UsbCfgTuple.parse(usbcfgRes.raw)
                ?: error("无法解析 AT+QCFG=\"usbcfg\" 返回: ${usbcfgRes.raw}")
            val usbnetRes = atEngine.execute("AT+QCFG=\"usbnet\"", timeoutMs = 3_000L, maxRetries = 1)
            val usbnetMode = AtResponseParser.parseQcfgTailInt(usbnetRes)
            val imsRes = atEngine.execute("AT+QCFG=\"ims\"", timeoutMs = 3_000L, maxRetries = 1)
            val imsRaw = AtResponseParser.parseQcfgLine(imsRes)
            val qpcmvRes = atEngine.execute("AT+QPCMV=?", timeoutMs = 3_000L, maxRetries = 1)
            val qpcmvRaw = qpcmvRes.payloadLines.firstOrNull().orEmpty()

            backupDir.mkdirs()
            val outFile = File(backupDir, "qdc507-${imei}-backup.json")
            val record = ModemBackupRecord(
                imei = imei,
                firmwareRevision = rev,
                usbnetMode = usbnetMode,
                imsRaw = imsRaw,
                qpcmvSupportRaw = qpcmvRaw,
                usbCfg = tuple,
                backedUpAtMs = System.currentTimeMillis(),
                backupFilePath = outFile.absolutePath,
            )
            outFile.writeText(serializeBackup(record), Charsets.UTF_8)

            _state.value = ModemConfigBackupState(
                isBusy = false,
                currentImei = imei,
                currentUsbCfg = tuple,
                backup = record,
                lastStatus = "已按 IMEI 完成原始配置备份 (${tuple.summaryTuple()})",
                lastError = null,
            )
            DuoLogger.i(LogCategory.MODEM, "config_backup", "Saved QDC507 backup usbcfg=${tuple.summaryTuple()}")
            Result.success(record)
        } catch (t: Throwable) {
            failWith("配置备份失败: ${t.message}")
        }
    }

    suspend fun enableAdbAndUacWithReboot(): Result<String> = mutex.withLock {
        if (!atEngine.isRunning) {
            return@withLock failWith("AT 端口未就绪")
        }
        _state.value = _state.value.copy(isBusy = true, lastError = null)
        return@withLock try {
            val clcc = atEngine.execute("AT+CLCC", timeoutMs = 3_000L, maxRetries = 0)
            require(clcc.isOk && CallAtParser.parseClccResponse(clcc.raw).isEmpty()) {
                "检测到正在进行中的语音通话，禁止切换 USB 组合"
            }

            val imei = AtResponseParser.parseImei(atEngine.execute("AT+CGSN", timeoutMs = 3_000L, maxRetries = 1))
            val savedBackup = findBackupForImei(imei)
                ?: error("未找到匹配当前模块 IMEI 的本地配置备份，请先点击「备份配置」")

            val usbcfgRes = atEngine.execute("AT+QCFG=\"usbcfg\"", timeoutMs = 3_000L, maxRetries = 0)
            val current = UsbCfgTuple.parse(usbcfgRes.raw)
                ?: error("无法读取当前 usbcfg: ${usbcfgRes.raw}")
            val target = current.withAdbAndUac(adbEnabled = true, uacEnabled = true)

            if (current.adb == 1 && current.uac == 1) {
                _state.value = _state.value.copy(
                    isBusy = false,
                    currentImei = imei,
                    currentUsbCfg = current,
                    backup = savedBackup,
                    lastStatus = "当前 usbcfg 已开启 ADB=1 与 UAC=1，无需改写",
                )
                return@withLock Result.success("当前配置已启用 ADB+UAC")
            }

            if (current.adb == 0) {
                val keyQuery = atEngine.execute("AT+QADBKEY?", timeoutMs = 3_000L, maxRetries = 0)
                val challenge = QadbKeyDeriver.parseChallenge(keyQuery.raw)
                if (challenge != null) {
                    val derivedKey = QadbKeyDeriver.deriveLegacyKey(challenge)
                        ?: error("无法基于 QADBKEY 挑战计算授权口令")
                    val unlockRes = atEngine.execute("AT+QADBKEY=\"$derivedKey\"", timeoutMs = 3_000L, maxRetries = 0)
                    require(unlockRes.isOk) {
                        "AT+QADBKEY 授权被拒绝 (${unlockRes.errorDetail ?: unlockRes.status})"
                    }
                    DuoLogger.i(LogCategory.MODEM, "qadbkey_unlock", "Accepted 8-digit QADBKEY challenge")
                } else {
                    DuoLogger.w(
                        LogCategory.MODEM,
                        "qadbkey_skip",
                        "No 8-digit QADBKEY challenge returned (${keyQuery.raw.trim()}); trying USBCFG directly",
                    )
                }
            }

            val writeCmd = target.toWriteCommand()
            val writeRes = atEngine.execute(writeCmd, timeoutMs = 5_000L, maxRetries = 0)
            require(writeRes.isOk) {
                "写入 $writeCmd 被模块拒绝 (${writeRes.errorDetail ?: writeRes.status})"
            }

            val readbackRes = atEngine.execute("AT+QCFG=\"usbcfg\"", timeoutMs = 3_000L, maxRetries = 0)
            val readback = UsbCfgTuple.parse(readbackRes.raw)
            require(readback == target) {
                "写入回读不一致: expected=${target.summaryTuple()}, actual=${readback?.summaryTuple()}"
            }

            // Trigger controlled modem reboot so the new ADB + UAC USB gadget interfaces enumerate.
            val rebootRes = atEngine.execute("AT+CFUN=1,1", timeoutMs = 5_000L, maxRetries = 0)
            require(rebootRes.isOk) {
                "usbcfg 已写入并校验通过，但 AT+CFUN=1,1 重启失败 (${rebootRes.status})，请手动重插模块"
            }

            val msg = "已备份原配置，写入 ${target.summaryTuple()} 并下发 AT+CFUN=1,1；请等待模块约 10~15 秒重连并弹出 USB 授权"
            _state.value = ModemConfigBackupState(
                isBusy = false,
                currentImei = imei,
                currentUsbCfg = readback,
                backup = savedBackup,
                lastStatus = msg,
                lastError = null,
            )
            DuoLogger.i(LogCategory.MODEM, "usbcfg_enable_adb_uac", msg)
            Result.success(msg)
        } catch (t: Throwable) {
            failWith("开启 ADB+UAC 失败: ${t.message}")
        }
    }

    suspend fun restoreOriginalUsbCfgWithReboot(): Result<String> = mutex.withLock {
        if (!atEngine.isRunning) {
            return@withLock failWith("AT 端口未就绪")
        }
        _state.value = _state.value.copy(isBusy = true, lastError = null)
        return@withLock try {
            val clcc = atEngine.execute("AT+CLCC", timeoutMs = 3_000L, maxRetries = 0)
            require(clcc.isOk && CallAtParser.parseClccResponse(clcc.raw).isEmpty()) {
                "检测到正在进行中的语音通话，禁止还原 USB 组合"
            }

            val imei = AtResponseParser.parseImei(atEngine.execute("AT+CGSN", timeoutMs = 3_000L, maxRetries = 1))
            val savedBackup = findBackupForImei(imei)
                ?: error("未找到匹配当前 IMEI 的配置备份")

            val restoreCmd = savedBackup.usbCfg.toWriteCommand()
            val writeRes = atEngine.execute(restoreCmd, timeoutMs = 5_000L, maxRetries = 0)
            require(writeRes.isOk) {
                "还原配置失败 (${writeRes.errorDetail ?: writeRes.status})"
            }

            val readbackRes = atEngine.execute("AT+QCFG=\"usbcfg\"", timeoutMs = 3_000L, maxRetries = 0)
            val readback = UsbCfgTuple.parse(readbackRes.raw)
            require(readback == savedBackup.usbCfg) {
                "还原回读不匹配: expected=${savedBackup.usbCfg.summaryTuple()}, actual=${readback?.summaryTuple()}"
            }

            val rebootRes = atEngine.execute("AT+CFUN=1,1", timeoutMs = 5_000L, maxRetries = 0)
            require(rebootRes.isOk) {
                "已还原配置但 AT+CFUN=1,1 重启失败 (${rebootRes.status})"
            }

            val msg = "已还原原始 usbcfg=${savedBackup.usbCfg.summaryTuple()} 并下发 AT+CFUN=1,1 重启恢复（注：QADBKEY 持久解锁位不受接口开关影响）"
            _state.value = ModemConfigBackupState(
                isBusy = false,
                currentImei = imei,
                currentUsbCfg = readback,
                backup = savedBackup,
                lastStatus = msg,
                lastError = null,
            )
            DuoLogger.i(LogCategory.MODEM, "usbcfg_restore", msg)
            Result.success(msg)
        } catch (t: Throwable) {
            failWith("还原原厂 USB 失败: ${t.message}")
        }
    }

    private fun <T> failWith(message: String): Result<T> {
        DuoLogger.w(LogCategory.MODEM, "config_mgr_fail", message)
        _state.value = _state.value.copy(isBusy = false, lastError = message)
        return Result.failure(IllegalStateException(message))
    }

    private fun findBackupForImei(imei: String): ModemBackupRecord? {
        if (imei.isBlank()) return null
        val file = File(backupDir, "qdc507-${imei}-backup.json")
        if (file.isFile) {
            return parseBackup(file)
        }
        return _state.value.backup?.takeIf { it.imei == imei }
    }

    private fun loadLatestSavedState(): ModemConfigBackupState {
        if (!backupDir.isDirectory) return ModemConfigBackupState()
        val files = backupDir.listFiles { f -> f.isFile && f.name.endsWith("-backup.json") }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()
        val latest = files.firstNotNullOfOrNull { parseBackup(it) } ?: return ModemConfigBackupState()
        return ModemConfigBackupState(
            currentImei = latest.imei,
            currentUsbCfg = latest.usbCfg,
            backup = latest,
            lastStatus = "已加载本地 IMEI 备份 (${latest.usbCfg.summaryTuple()})",
        )
    }

    companion object {
        internal fun serializeBackup(record: ModemBackupRecord): String {
            fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
            return buildString {
                appendLine("{")
                appendLine("  \"imei\": \"${esc(record.imei)}\",")
                appendLine("  \"firmwareRevision\": \"${esc(record.firmwareRevision)}\",")
                appendLine("  \"usbnetMode\": ${record.usbnetMode?.toString() ?: "null"},")
                appendLine("  \"imsRaw\": \"${esc(record.imsRaw)}\",")
                appendLine("  \"qpcmvSupportRaw\": \"${esc(record.qpcmvSupportRaw)}\",")
                appendLine("  \"usbcfgWriteCommand\": \"${esc(record.usbCfg.toWriteCommand())}\",")
                appendLine("  \"backedUpAtMs\": ${record.backedUpAtMs}")
                append("}")
            }
        }

        internal fun parseBackup(file: File): ModemBackupRecord? = runCatching {
            val text = file.readText(Charsets.UTF_8)
            fun strField(name: String): String {
                val m = Regex(""""$name"\s*:\s*"((?:\\"|[^"])*)"""").find(text) ?: return ""
                return m.groupValues[1].replace("\\\"", "\"").replace("\\\\", "\\")
            }
            fun longField(name: String): Long? =
                Regex(""""$name"\s*:\s*(-?\d+)""").find(text)?.groupValues?.get(1)?.toLongOrNull()

            val imei = strField("imei")
            val writeCmd = strField("usbcfgWriteCommand")
            val fakeLine = "+QCFG: \"usbcfg\"," + writeCmd.substringAfter("\"usbcfg\",", "")
            val tuple = UsbCfgTuple.parse(fakeLine) ?: return null
            if (imei.isBlank()) return null

            ModemBackupRecord(
                imei = imei,
                firmwareRevision = strField("firmwareRevision"),
                usbnetMode = longField("usbnetMode")?.toInt(),
                imsRaw = strField("imsRaw"),
                qpcmvSupportRaw = strField("qpcmvSupportRaw"),
                usbCfg = tuple,
                backedUpAtMs = longField("backedUpAtMs") ?: file.lastModified(),
                backupFilePath = file.absolutePath,
            )
        }.getOrNull()
    }
}
