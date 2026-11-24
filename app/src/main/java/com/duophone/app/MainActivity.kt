package com.duophone.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.duophone.app.diagnostics.DeviceDiagnostics
import com.duophone.app.diagnostics.DuoLogger
import com.duophone.app.diagnostics.HostHardwareSnapshot
import com.duophone.app.diagnostics.LogCategory
import com.duophone.app.service.ModemService
import com.duophone.app.ui.DuoPhoneScreen
import com.duophone.app.ui.DuoPhoneTheme
import com.duophone.app.ui.DuoTab
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private var selectedTab by mutableStateOf(DuoTab.STATUS)
    private lateinit var hostHardware: HostHardwareSnapshot
    private var hardwareState by mutableStateOf<HostHardwareSnapshot?>(null)

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        DuoLogger.i(LogCategory.SERVICE, "notif_permission", "POST_NOTIFICATIONS granted=$granted")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )

        val app = application as DuoPhoneApplication
        val controller = app.modemController

        requestNotificationPermissionIfNeeded()
        ModemService.start(this, triggerConnect = true)

        hostHardware = DeviceDiagnostics.capture(this)
        hardwareState = hostHardware

        setContent {
            DuoPhoneTheme {
                val modemState by controller.state.collectAsStateWithLifecycle()
                val logs by DuoLogger.entries.collectAsStateWithLifecycle()
                DuoPhoneScreen(
                    selectedTab = selectedTab,
                    onSelectTab = { selectedTab = it },
                    modemState = modemState,
                    hostHardware = hardwareState ?: hostHardware,
                    logs = logs,
                    onConnect = { lifecycleScope.launch { controller.connect() } },
                    onRefreshTelemetry = {
                        lifecycleScope.launch { controller.refreshTelemetry(includeIdentityAndConfig = true) }
                    },
                    onRefreshHostHardware = {
                        hardwareState = DeviceDiagnostics.capture(this)
                    },
                    onRunAtCommand = { cmd ->
                        lifecycleScope.launch { controller.runRawAtCommand(cmd) }
                    },
                    onClearConsole = { controller.clearConsole() },
                    onClearLogs = { DuoLogger.clear() },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            (application as DuoPhoneApplication).modemController.onUsbDeviceAttached()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val tabs = DuoTab.entries
        return when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_L1 -> {
                val next = (selectedTab.ordinal - 1 + tabs.size) % tabs.size
                selectedTab = tabs[next]
                DuoLogger.d(LogCategory.INPUT, "gamepad_l1", "Switched tab -> ${selectedTab.name}")
                true
            }
            KeyEvent.KEYCODE_BUTTON_R1 -> {
                val next = (selectedTab.ordinal + 1) % tabs.size
                selectedTab = tabs[next]
                DuoLogger.d(LogCategory.INPUT, "gamepad_r1", "Switched tab -> ${selectedTab.name}")
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
