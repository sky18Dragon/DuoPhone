package com.duophone.app.device

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class Qdc507VoiceRuntimeTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `prepare fails cleanly without pushing when local assets are missing or sha mismatches`() = runBlocking {
        val dir = tempFolder.newFolder("voice_runtime")
        // Put one mismatched file; leave others absent
        File(dir, Qdc507VoiceAssets.APR_KO.fileName).writeBytes(byteArrayOf(1, 2, 3))

        val fakeAdb = RecordingAdbRunner(adbPresent = true)
        val runtime = Qdc507VoiceRuntime(fakeAdb, dir)

        val result = runtime.prepareReversibleRuntime(uacDetected = true)
        assertTrue(result.isFailure)
        assertEquals(VoiceRuntimeStage.ASSETS_MISSING, runtime.state.value.stage)
        assertTrue(fakeAdb.commands.isEmpty())
        assertTrue(fakeAdb.pushedPaths.isEmpty())
    }

    @Test
    fun `read-only probe parses kernel modules snd nodes and proc stat start ticks`() = runBlocking {
        val dir = tempFolder.newFolder("voice_runtime_probe")
        val fakeAdb = RecordingAdbRunner(
            adbPresent = true,
            shellHandler = {
                AdbShellResult(
                    exitCode = 0,
                    stdout = """
                        ===DUO:UNAME===
                        3.18.44-perf
                        ===DUO:CARDS===
                         0 [mdm9607-tomtom-]: mdm9607-tomtom-i2s - mdm9607-tomtom-i2s-snd-card
                        ===DUO:SND===
                        controlC0 pcmC0D4c pcmC0D4p pcmC0D5p pcmC0D6c
                        ===DUO:MODULES===
                        qdc507_voice 16384 0 - Live 0xbf010000 (O)
                        qdc507_aprv3 12288 1 qdc507_voice, Live 0xbf000000 (O)
                        ===DUO:BRIDGE===
                    """.trimIndent(),
                    rawOutput = "",
                )
            },
        )
        val runtime = Qdc507VoiceRuntime(fakeAdb, dir)

        val snap = runtime.probeReadOnly(uacDetected = true).getOrThrow()
        assertEquals(VoiceRuntimeStage.PREPARED, snap.stage)
        assertEquals("3.18.44-perf", snap.kernelVersion)
        assertTrue(snap.soundCardPresent)
        assertTrue(snap.aprLoaded)
        assertTrue(snap.voiceLoaded)
        assertTrue(snap.requiredPcmNodesPresent)

        // Check /proc/<pid>/stat parsing even when comm contains spaces & parens
        val statLine = "4321 (mavo bridge (v1)) S 1 4321 4321 0 -1 4194304 100 0 0 0 1 2 0 0 20 0 1 0 987654 1024 50"
        assertEquals(987654L, VoiceProbeParser.parseProcStatStartTicks(statLine))
    }

    @Test
    fun `stopVoiceRoute uses PID start ticks SIGTERM voc_svr rollback and never calls rmmod`() = runBlocking {
        val dir = tempFolder.newFolder("voice_runtime_stop")
        val fakeAdb = RecordingAdbRunner(
            adbPresent = true,
            shellHandler = { cmd ->
                if (cmd.contains("nohup")) {
                    AdbShellResult(
                        exitCode = 0,
                        stdout = "5120 (mavo-pcm-bridge) S 1 5120 5120 0 -1 4194304 1 0 0 0 0 0 0 0 20 0 1 0 777111 100 10",
                        rawOutput = "",
                    )
                } else {
                    AdbShellResult(exitCode = 0, stdout = "", rawOutput = "")
                }
            },
        )

        val runtime = Qdc507VoiceRuntime(fakeAdb, dir)
        val noAdbSnap = Qdc507VoiceRuntime(RecordingAdbRunner(adbPresent = false), dir)
            .probeReadOnly()
            .getOrThrow()
        assertEquals(VoiceRuntimeStage.ADB_UNAVAILABLE, noAdbSnap.stage)

        // Probe first -> simulate active bridge via probe output -> verify safe stop command
        fakeAdb.shellHandler = {
            AdbShellResult(
                exitCode = 0,
                stdout = """
                    ===DUO:UNAME===
                    3.18.44-perf
                    ===DUO:CARDS===
                     0 [mdm9607-tomtom-]: mdm9607-tomtom-i2s
                    ===DUO:SND===
                    controlC0 pcmC0D4c pcmC0D4p pcmC0D5p pcmC0D6c
                    ===DUO:MODULES===
                    qdc507_aprv3 1 0
                    qdc507_voice 1 0
                    ===DUO:BRIDGE===
                    5120:777111
                """.trimIndent(),
                rawOutput = "",
            )
        }
        val probed = runtime.probeReadOnly().getOrThrow()
        assertEquals(VoiceRuntimeStage.ROUTE_ACTIVE, probed.stage)
        assertEquals(5120, probed.bridgePid)

        fakeAdb.commands.clear()
        fakeAdb.shellHandler = { AdbShellResult(0, "", "") }
        val stopped = runtime.stopVoiceRoute().getOrThrow()
        assertEquals(VoiceRuntimeStage.PREPARED, stopped.stage)
        assertEquals(null, stopped.bridgePid)
        val stopCmd = fakeAdb.commands.single()
        assertTrue(stopCmd.contains("777111"))
        assertTrue(stopCmd.contains("kill -TERM 5120"))
        assertTrue(stopCmd.contains("/sys/class/android_usb/f_audio/audio_enable"))
        assertTrue(stopCmd.contains("-p /run/voc_svr"))
        assertFalse("Must never rmmod kernel modules", stopCmd.contains("rmmod"))
    }

    private class RecordingAdbRunner(
        private val adbPresent: Boolean,
        var shellHandler: (String) -> AdbShellResult = { AdbShellResult(0, "", "") },
    ) : AdbShellRunner {
        val commands = mutableListOf<String>()
        val pushedPaths = mutableListOf<String>()

        override suspend fun isAdbInterfacePresent(): Boolean = adbPresent

        override suspend fun runShell(command: String, timeoutMs: Long): Result<AdbShellResult> {
            commands.add(command)
            return Result.success(shellHandler(command))
        }

        override suspend fun pushFile(
            localBytes: ByteArray,
            remotePath: String,
            modeBits: Int,
            timeoutMs: Long,
        ): Result<Unit> {
            pushedPaths.add(remotePath)
            return Result.success(Unit)
        }
    }
}
