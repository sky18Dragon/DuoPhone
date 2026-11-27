package com.duophone.app.pocket

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.duophone.app.MainActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean

/** No dialing/SMS or mock native renderer. Requires an actual Android GLES2 device. */
@RunWith(AndroidJUnit4::class)
class PocketActivitySmokeTest {
    @Test fun selectedBackendSurvivesRecreate() {
        val expectsPocket = PocketBackend.requested && !PocketBackend.faultInjection
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            if (expectsPocket) {
                awaitFrame(scenario)
                scenario.recreate()
                awaitFrame(scenario)
            } else {
                scenario.onActivity { activity -> assertNull(findSurface(activity.window.decorView)) }
                scenario.recreate()
                scenario.onActivity { activity -> assertNull(findSurface(activity.window.decorView)) }
            }
        }
    }

    @Test fun selectedBackendSurvivesDisplayZeroHomeAndRelaunch() {
        val expectsPocket = PocketBackend.requested && !PocketBackend.faultInjection
        if (!expectsPocket) return

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        runShell("am start -W --display 0 -n com.duophone.app/.MainActivity")
        val activity = findLiveMainActivity(timeoutMs = 10_000)

        val stopped = AtomicBoolean(false)
        val resumedAfterStop = AtomicBoolean(false)
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> stopped.set(true)
                Lifecycle.Event.ON_RESUME -> if (stopped.get()) resumedAfterStop.set(true)
                else -> Unit
            }
        }
        instrumentation.runOnMainSync { activity.lifecycle.addObserver(observer) }
        try {
            awaitFrameOnActivity(activity, after = 0)
            runShell("input -d 0 keyevent KEYCODE_HOME")
            val stopDeadline = SystemClock.uptimeMillis() + 5_000
            while (!stopped.get() && SystemClock.uptimeMillis() < stopDeadline) {
                SystemClock.sleep(50)
            }
            assertTrue("MainActivity did not enter ON_STOP on Display #0 HOME", stopped.get())

            var priorFrames = 0L
            instrumentation.runOnMainSync {
                priorFrames = findSurface(activity.window.decorView)!!.presentedFrames
            }
            runShell("am start -W --display 0 -n com.duophone.app/.MainActivity")
            val resumeDeadline = SystemClock.uptimeMillis() + 10_000
            while (!resumedAfterStop.get() && SystemClock.uptimeMillis() < resumeDeadline) {
                SystemClock.sleep(50)
            }
            assertTrue("MainActivity did not resume after Display #0 HOME", resumedAfterStop.get())
            awaitFrameOnActivity(activity, after = priorFrames)
        } finally {
            runCatching {
                instrumentation.runOnMainSync {
                    activity.lifecycle.removeObserver(observer)
                    if (!activity.isFinishing) activity.finish()
                }
            }
        }
    }

    private fun findLiveMainActivity(timeoutMs: Long): MainActivity {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        var found: MainActivity? = null
        while (found == null && SystemClock.uptimeMillis() < deadline) {
            instrumentation.runOnMainSync {
                val monitor = ActivityLifecycleMonitorRegistry.getInstance()
                val candidates = listOf(Stage.RESUMED, Stage.PAUSED, Stage.STARTED).flatMap { stage ->
                    monitor.getActivitiesInStage(stage)
                }
                found = candidates.filterIsInstance<MainActivity>()
                    .firstOrNull { !it.isFinishing && !it.isDestroyed }
            }
            if (found == null) SystemClock.sleep(50)
        }
        assertNotNull("Expected live MainActivity on Display #0 after am start", found)
        return found!!
    }

    private fun awaitFrameOnActivity(activity: MainActivity, after: Long = 0) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val deadline = SystemClock.uptimeMillis() + 10_000
        var frames = 0L
        while (frames <= after && SystemClock.uptimeMillis() < deadline) {
            instrumentation.runOnMainSync {
                frames = findSurface(activity.window.decorView)?.presentedFrames ?: 0
            }
            if (frames <= after) SystemClock.sleep(50)
        }
        val trace = if (frames <= after) {
            com.duophone.app.diagnostics.DuoLogger.entries.value.takeLast(25).joinToString("\n") { it.formatLine() }
        } else ""
        assertTrue("Real PocketJS GLES2 frame required; fallback is not success\n$trace", frames > after)
    }
    private fun runShell(command: String) {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
    }
    private fun awaitFrame(scenario: ActivityScenario<MainActivity>, after: Long = 0) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        var frames = 0L
        while (frames <= after && SystemClock.uptimeMillis() < deadline) {
            scenario.onActivity { frames = findSurface(it.window.decorView)?.presentedFrames ?: 0 }
            if (frames <= after) SystemClock.sleep(50)
        }
        val trace = if (frames <= after) {
            com.duophone.app.diagnostics.DuoLogger.entries.value.takeLast(25).joinToString("\n") { it.formatLine() }
        } else ""
        assertTrue("Real PocketJS GLES2 frame required; fallback is not success\n$trace", frames > after)
    }
    private fun findSurface(view: View): PocketSurface? {
        if (view is PocketSurface) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            findSurface(view.getChildAt(index))?.let { return it }
        }
        return null
    }
}
