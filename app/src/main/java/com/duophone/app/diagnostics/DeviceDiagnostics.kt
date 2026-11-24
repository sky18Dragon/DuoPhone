package com.duophone.app.diagnostics

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.input.InputManager
import android.hardware.usb.UsbManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.view.Display
import android.view.InputDevice

data class DisplaySnapshot(
    val displayId: Int,
    val name: String,
    val width: Int,
    val height: Int,
    val refreshRate: Float,
    val isDefault: Boolean,
)

data class InputSnapshot(
    val id: Int,
    val name: String,
    val isGamepad: Boolean,
    val isKeyboard: Boolean,
    val isExternal: Boolean,
)

data class AudioDeviceSnapshot(
    val id: Int,
    val productName: String,
    val type: Int,
    val typeName: String,
    val isSink: Boolean,
    val sampleRates: List<Int>,
)

data class HostHardwareSnapshot(
    val model: String,
    val manufacturer: String,
    val androidRelease: String,
    val sdkInt: Int,
    val abi: String,
    val displays: List<DisplaySnapshot>,
    val inputs: List<InputSnapshot>,
    val audioDevices: List<AudioDeviceSnapshot>,
    val attachedUsbSummary: List<String>,
)

object DeviceDiagnostics {
    fun capture(context: Context): HostHardwareSnapshot {
        val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val displays = displayManager.displays.map { d ->
            val mode = d.mode
            DisplaySnapshot(
                displayId = d.displayId,
                name = d.name.orEmpty(),
                width = mode.physicalWidth,
                height = mode.physicalHeight,
                refreshRate = mode.refreshRate,
                isDefault = d.displayId == Display.DEFAULT_DISPLAY,
            )
        }

        val inputManager = context.getSystemService(Context.INPUT_SERVICE) as InputManager
        val inputs = inputManager.inputDeviceIds.toList().mapNotNull { id ->
            val dev = inputManager.getInputDevice(id) ?: return@mapNotNull null
            val sources = dev.sources
            InputSnapshot(
                id = id,
                name = dev.name.orEmpty(),
                isGamepad = (sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
                    (sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK,
                isKeyboard = (sources and InputDevice.SOURCE_KEYBOARD) == InputDevice.SOURCE_KEYBOARD,
                isExternal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) dev.isExternal else false,
            )
        }

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val audioDevices = audioManager.getDevices(
            AudioManager.GET_DEVICES_INPUTS or AudioManager.GET_DEVICES_OUTPUTS,
        ).map { dev ->
            AudioDeviceSnapshot(
                id = dev.id,
                productName = dev.productName?.toString().orEmpty(),
                type = dev.type,
                typeName = audioTypeName(dev.type),
                isSink = dev.isSink,
                sampleRates = dev.sampleRates.toList(),
            )
        }

        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val usbSummary = usbManager.deviceList.values.map { dev ->
            String.format(
                "%04X:%04X %s (%d ifaces)",
                dev.vendorId,
                dev.productId,
                dev.productName ?: dev.deviceName,
                dev.interfaceCount,
            )
        }

        val snapshot = HostHardwareSnapshot(
            model = Build.MODEL,
            manufacturer = Build.MANUFACTURER,
            androidRelease = Build.VERSION.RELEASE,
            sdkInt = Build.VERSION.SDK_INT,
            abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty(),
            displays = displays,
            inputs = inputs,
            audioDevices = audioDevices,
            attachedUsbSummary = usbSummary,
        )

        DuoLogger.i(
            category = LogCategory.DISPLAY,
            operation = "capture_host_hw",
            message = "model=${snapshot.model} sdk=${snapshot.sdkInt} displays=${displays.size} " +
                "gamepads=${inputs.count { it.isGamepad }} usb=${usbSummary.size}",
        )
        return snapshot
    }

    private fun audioTypeName(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "EARPIECE"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "SPEAKER"
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "BUILTIN_MIC"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "WIRED_HEADSET"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "WIRED_HEADPHONES"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BT_SCO"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "BT_A2DP"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB_DEVICE"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB_HEADSET"
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB_ACCESSORY"
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "BLE_HEADSET"
        AudioDeviceInfo.TYPE_TELEPHONY -> "TELEPHONY"
        else -> "TYPE_$type"
    }
}
