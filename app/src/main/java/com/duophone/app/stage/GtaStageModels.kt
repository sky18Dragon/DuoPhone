package com.duophone.app.stage

import java.util.Locale

enum class DeckMenu(val title: String, val subtitle: String) {
    PHONE("电话", "DIALER"),
    SMS("短信", "MESSAGES"),
    SETTINGS("设置", "SYSTEM"),
}

enum class StageThemeMode(val label: String) {
    SYSTEM("跟随系统"),
    LIGHT("日间浅色"),
    DARK("夜间深色"),
}

enum class AppScreenState(val label: String) {
    Opening("开场动画"),
    PhoneDialing("电话拨号"),
    CallDialing("正在呼叫"),
    CallIncoming("来电振铃"),
    InCall("通话中"),
    SmsChat("短信会话"),
    Settings("系统设置"),
}

enum class OpeningPhase {
    PLAYING_VIDEO,
    FALLBACK_ANIMATION,
    IDLE_READY,
}

enum class VirtualKeyId {
    DIGIT_1,
    DIGIT_2,
    DIGIT_3,
    DIGIT_4,
    DIGIT_5,
    DIGIT_6,
    DIGIT_7,
    DIGIT_8,
    DIGIT_9,
    STAR,
    DIGIT_0,
    HASH,
    BACKSPACE,
    CLEAR,
    CALL,
    HANGUP,
    ANSWER,
    SMS_KEY_LEFT,
    SMS_KEY_CENTER,
    SMS_KEY_RIGHT,
    SMS_SEND,
    MENU_TAB,
    ;

    companion object {
        fun fromDialChar(ch: String): VirtualKeyId? = when (ch) {
            "1" -> DIGIT_1
            "2" -> DIGIT_2
            "3" -> DIGIT_3
            "4" -> DIGIT_4
            "5" -> DIGIT_5
            "6" -> DIGIT_6
            "7" -> DIGIT_7
            "8" -> DIGIT_8
            "9" -> DIGIT_9
            "*" -> STAR
            "0" -> DIGIT_0
            "#" -> HASH
            else -> null
        }
    }
}

data class NormalizedPoint(val x: Float, val y: Float)

object VirtualPhoneKeyMap {
    private val anchors: Map<VirtualKeyId, NormalizedPoint> = mapOf(
        VirtualKeyId.DIGIT_1 to NormalizedPoint(0.24f, 0.34f),
        VirtualKeyId.DIGIT_2 to NormalizedPoint(0.50f, 0.34f),
        VirtualKeyId.DIGIT_3 to NormalizedPoint(0.76f, 0.34f),
        VirtualKeyId.DIGIT_4 to NormalizedPoint(0.24f, 0.46f),
        VirtualKeyId.DIGIT_5 to NormalizedPoint(0.50f, 0.46f),
        VirtualKeyId.DIGIT_6 to NormalizedPoint(0.76f, 0.46f),
        VirtualKeyId.DIGIT_7 to NormalizedPoint(0.24f, 0.58f),
        VirtualKeyId.DIGIT_8 to NormalizedPoint(0.50f, 0.58f),
        VirtualKeyId.DIGIT_9 to NormalizedPoint(0.76f, 0.58f),
        VirtualKeyId.STAR to NormalizedPoint(0.24f, 0.70f),
        VirtualKeyId.DIGIT_0 to NormalizedPoint(0.50f, 0.70f),
        VirtualKeyId.HASH to NormalizedPoint(0.76f, 0.70f),
        VirtualKeyId.CLEAR to NormalizedPoint(0.24f, 0.82f),
        VirtualKeyId.CALL to NormalizedPoint(0.50f, 0.82f),
        VirtualKeyId.BACKSPACE to NormalizedPoint(0.76f, 0.82f),
        VirtualKeyId.ANSWER to NormalizedPoint(0.30f, 0.82f),
        VirtualKeyId.HANGUP to NormalizedPoint(0.50f, 0.82f),
        VirtualKeyId.SMS_KEY_LEFT to NormalizedPoint(0.22f, 0.84f),
        VirtualKeyId.SMS_KEY_CENTER to NormalizedPoint(0.50f, 0.88f),
        VirtualKeyId.SMS_KEY_RIGHT to NormalizedPoint(0.78f, 0.84f),
        VirtualKeyId.SMS_SEND to NormalizedPoint(0.86f, 0.69f),
        VirtualKeyId.MENU_TAB to NormalizedPoint(0.50f, 0.92f),
    )

    fun anchorFor(keyId: VirtualKeyId): NormalizedPoint =
        anchors[keyId] ?: NormalizedPoint(0.50f, 0.72f)
}

/**
 * Normalized [0..1] screen-space corners of the phone display in the unscaled 1920x1080 POV backdrop,
 * ordered TL, TR, BR, BL. When present, Layer 2 renders through a projective 4-corner homography
 * instead of an orthogonal 3D rotation approximation.
 */
data class QuadCorners(
    val tlX: Float,
    val tlY: Float,
    val trX: Float,
    val trY: Float,
    val brX: Float,
    val brY: Float,
    val blX: Float,
    val blY: Float,
) {
    fun coerced(): QuadCorners = QuadCorners(
        tlX = tlX.coerceIn(0f, 1f),
        tlY = tlY.coerceIn(0f, 1f),
        trX = trX.coerceIn(0f, 1f),
        trY = trY.coerceIn(0f, 1f),
        brX = brX.coerceIn(0f, 1f),
        brY = brY.coerceIn(0f, 1f),
        blX = blX.coerceIn(0f, 1f),
        blY = blY.coerceIn(0f, 1f),
    )
}

data class PhoneOverlayConfig(
    val leftFraction: Float = 0.56f,
    val topFraction: Float = 0.14f,
    val widthFraction: Float = 0.24f,
    val heightFraction: Float = 0.72f,
    val rotationX: Float = 4.0f,
    val rotationY: Float = -10.0f,
    val rotationZ: Float = -4.0f,
    val cameraDistanceDp: Float = 16.0f,
    val cornerRadiusDp: Float = 24.0f,
    val breathingEnabled: Boolean = true,
    val breathingAmplitudeDp: Float = 5.0f,
    val screenBrightness: Float = 1.0f,
    val quadCorners: QuadCorners? = null,
) {
    fun coerced(): PhoneOverlayConfig = copy(
        leftFraction = leftFraction.coerceIn(0.02f, 0.85f),
        topFraction = topFraction.coerceIn(0.02f, 0.80f),
        widthFraction = widthFraction.coerceIn(0.12f, 0.65f),
        heightFraction = heightFraction.coerceIn(0.25f, 0.92f),
        rotationX = rotationX.coerceIn(-35f, 35f),
        rotationY = rotationY.coerceIn(-35f, 35f),
        rotationZ = rotationZ.coerceIn(-30f, 30f),
        cameraDistanceDp = cameraDistanceDp.coerceIn(6f, 40f),
        cornerRadiusDp = cornerRadiusDp.coerceIn(4f, 48f),
        breathingAmplitudeDp = breathingAmplitudeDp.coerceIn(0f, 16f),
        screenBrightness = screenBrightness.coerceIn(0.35f, 1.0f),
        quadCorners = quadCorners?.coerced(),
    )
}

data class GtaAssetConfig(
    val openingVideoFile: String = "opening_light.mp4",
    val openingLightVideoFile: String = "opening_light.mp4",
    val openingDarkVideoFile: String = "opening_dark.mp4",
    val idleLoopVideoFile: String = "idle_loop.mp4",
    val idleImageFile: String = "idle_hand_phone_light.png",
    val idleLightImageFile: String = "idle_hand_phone_light.png",
    val idleDarkImageFile: String = "idle_hand_phone_dark.png",
    val dialingImageFile: String = "dialing_pose_light.png",
    val dialingLightImageFile: String = "dialing_pose_light.png",
    val dialingDarkImageFile: String = "dialing_pose_dark.png",
    val thumbImageFile: String = "thumb_overlay.png",
    val thumbFramesDir: String = "thumb_frames",
)

data class GtaStageConfig(
    val assets: GtaAssetConfig = GtaAssetConfig(),
    val phoneOverlay: PhoneOverlayConfig = PhoneOverlayConfig(),
) {
    fun toJson(): String {
        fun esc(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")
        fun fmt(f: Float): String = String.format(Locale.US, "%.4f", f)
            .trimEnd('0')
            .let { if (it.endsWith('.')) "${it}0" else it }

        fun fmt6(f: Float): String = String.format(Locale.US, "%.6f", f)

        return buildString {
            appendLine("{")
            appendLine("  \"assets\": {")
            appendLine("    \"openingVideoFile\": \"${esc(assets.openingVideoFile)}\",")
            appendLine("    \"openingLightVideoFile\": \"${esc(assets.openingLightVideoFile)}\",")
            appendLine("    \"openingDarkVideoFile\": \"${esc(assets.openingDarkVideoFile)}\",")
            appendLine("    \"idleLoopVideoFile\": \"${esc(assets.idleLoopVideoFile)}\",")
            appendLine("    \"idleImageFile\": \"${esc(assets.idleImageFile)}\",")
            appendLine("    \"idleLightImageFile\": \"${esc(assets.idleLightImageFile)}\",")
            appendLine("    \"idleDarkImageFile\": \"${esc(assets.idleDarkImageFile)}\",")
            appendLine("    \"dialingImageFile\": \"${esc(assets.dialingImageFile)}\",")
            appendLine("    \"dialingLightImageFile\": \"${esc(assets.dialingLightImageFile)}\",")
            appendLine("    \"dialingDarkImageFile\": \"${esc(assets.dialingDarkImageFile)}\",")
            appendLine("    \"thumbImageFile\": \"${esc(assets.thumbImageFile)}\",")
            appendLine("    \"thumbFramesDir\": \"${esc(assets.thumbFramesDir)}\"")
            appendLine("  },")
            appendLine("  \"phoneOverlay\": {")
            appendLine("    \"leftFraction\": ${fmt(phoneOverlay.leftFraction)},")
            appendLine("    \"topFraction\": ${fmt(phoneOverlay.topFraction)},")
            appendLine("    \"widthFraction\": ${fmt(phoneOverlay.widthFraction)},")
            appendLine("    \"heightFraction\": ${fmt(phoneOverlay.heightFraction)},")
            appendLine("    \"rotationX\": ${fmt(phoneOverlay.rotationX)},")
            appendLine("    \"rotationY\": ${fmt(phoneOverlay.rotationY)},")
            appendLine("    \"rotationZ\": ${fmt(phoneOverlay.rotationZ)},")
            appendLine("    \"cameraDistanceDp\": ${fmt(phoneOverlay.cameraDistanceDp)},")
            appendLine("    \"cornerRadiusDp\": ${fmt(phoneOverlay.cornerRadiusDp)},")
            appendLine("    \"breathingEnabled\": ${phoneOverlay.breathingEnabled},")
            appendLine("    \"breathingAmplitudeDp\": ${fmt(phoneOverlay.breathingAmplitudeDp)},")
            val qc = phoneOverlay.quadCorners
            if (qc != null) {
                appendLine("    \"screenBrightness\": ${fmt(phoneOverlay.screenBrightness)},")
                appendLine("    \"quadCorners\": {")
                appendLine("      \"tlX\": ${fmt6(qc.tlX)}, \"tlY\": ${fmt6(qc.tlY)},")
                appendLine("      \"trX\": ${fmt6(qc.trX)}, \"trY\": ${fmt6(qc.trY)},")
                appendLine("      \"brX\": ${fmt6(qc.brX)}, \"brY\": ${fmt6(qc.brY)},")
                appendLine("      \"blX\": ${fmt6(qc.blX)}, \"blY\": ${fmt6(qc.blY)}")
                appendLine("    }")
            } else {
                appendLine("    \"screenBrightness\": ${fmt(phoneOverlay.screenBrightness)}")
            }
            appendLine("  }")
            append("}")
        }
    }

    companion object {
        fun default(): GtaStageConfig = GtaStageConfig()

        fun fromJson(raw: String): GtaStageConfig {
            val defaults = default()

            fun strField(key: String, fallback: String): String {
                val regex = Regex("\"${Regex.escape(key)}\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
                val rawVal = regex.find(raw)?.groupValues?.getOrNull(1)
                    ?.replace("\\\"", "\"")
                    ?.replace("\\\\", "\\")
                    ?.trim()
                return if (rawVal.isNullOrBlank()) fallback else rawVal
            }

            fun floatField(key: String, fallback: Float): Float {
                val regex = Regex("\"${Regex.escape(key)}\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)")
                return regex.find(raw)?.groupValues?.getOrNull(1)?.toFloatOrNull() ?: fallback
            }

            fun boolField(key: String, fallback: Boolean): Boolean {
                val regex = Regex("\"${Regex.escape(key)}\"\\s*:\\s*(true|false)", RegexOption.IGNORE_CASE)
                return regex.find(raw)?.groupValues?.getOrNull(1)?.lowercase()?.toBooleanStrictOrNull() ?: fallback
            }

            val baseOpening = strField("openingVideoFile", defaults.assets.openingVideoFile)
            val baseIdle = strField("idleImageFile", defaults.assets.idleImageFile)
            val baseDialing = strField("dialingImageFile", defaults.assets.dialingImageFile)

            val assets = GtaAssetConfig(
                openingVideoFile = baseOpening,
                openingLightVideoFile = strField("openingLightVideoFile", baseOpening),
                openingDarkVideoFile = strField("openingDarkVideoFile", defaults.assets.openingDarkVideoFile),
                idleLoopVideoFile = strField("idleLoopVideoFile", defaults.assets.idleLoopVideoFile),
                idleImageFile = baseIdle,
                idleLightImageFile = strField("idleLightImageFile", baseIdle),
                idleDarkImageFile = strField("idleDarkImageFile", defaults.assets.idleDarkImageFile),
                dialingImageFile = baseDialing,
                dialingLightImageFile = strField("dialingLightImageFile", baseDialing),
                dialingDarkImageFile = strField("dialingDarkImageFile", defaults.assets.dialingDarkImageFile),
                thumbImageFile = strField("thumbImageFile", defaults.assets.thumbImageFile),
                thumbFramesDir = strField("thumbFramesDir", defaults.assets.thumbFramesDir),
            )

            val quadBlock = Regex("\"quadCorners\"\\s*:\\s*\\{([^}]*)\\}").find(raw)?.groupValues?.getOrNull(1)
            val parsedQuad = if (quadBlock != null) {
                fun qFloat(key: String): Float? =
                    Regex("\"${Regex.escape(key)}\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)")
                        .find(quadBlock)?.groupValues?.getOrNull(1)?.toFloatOrNull()
                val tlX = qFloat("tlX")
                val tlY = qFloat("tlY")
                val trX = qFloat("trX")
                val trY = qFloat("trY")
                val brX = qFloat("brX")
                val brY = qFloat("brY")
                val blX = qFloat("blX")
                val blY = qFloat("blY")
                if (tlX != null && tlY != null && trX != null && trY != null &&
                    brX != null && brY != null && blX != null && blY != null
                ) {
                    QuadCorners(tlX, tlY, trX, trY, brX, brY, blX, blY)
                } else {
                    null
                }
            } else {
                null
            }

            val overlay = PhoneOverlayConfig(
                leftFraction = floatField("leftFraction", defaults.phoneOverlay.leftFraction),
                topFraction = floatField("topFraction", defaults.phoneOverlay.topFraction),
                widthFraction = floatField("widthFraction", defaults.phoneOverlay.widthFraction),
                heightFraction = floatField("heightFraction", defaults.phoneOverlay.heightFraction),
                rotationX = floatField("rotationX", defaults.phoneOverlay.rotationX),
                rotationY = floatField("rotationY", defaults.phoneOverlay.rotationY),
                rotationZ = floatField("rotationZ", defaults.phoneOverlay.rotationZ),
                cameraDistanceDp = floatField("cameraDistanceDp", defaults.phoneOverlay.cameraDistanceDp),
                cornerRadiusDp = floatField("cornerRadiusDp", defaults.phoneOverlay.cornerRadiusDp),
                breathingEnabled = boolField("breathingEnabled", defaults.phoneOverlay.breathingEnabled),
                breathingAmplitudeDp = floatField("breathingAmplitudeDp", defaults.phoneOverlay.breathingAmplitudeDp),
                screenBrightness = floatField("screenBrightness", defaults.phoneOverlay.screenBrightness),
                quadCorners = parsedQuad,
            ).coerced()

            return GtaStageConfig(assets = assets, phoneOverlay = overlay)
        }
    }
}

sealed interface AssetSource {
    val label: String

    data class ExternalFile(val path: String) : AssetSource {
        override val label: String = "外部素材"
    }

    data class BundledAsset(val assetPath: String) : AssetSource {
        override val label: String = "内置素材"
    }
}

data class ResolvedStageAssets(
    val assetsDirPath: String = "",
    val externalConfigPresent: Boolean = false,
    val openingVideoUri: String? = null,
    val openingLightVideoUri: String? = null,
    val openingDarkVideoUri: String? = null,
    val idleLoopVideoUri: String? = null,
    val idleImagePath: AssetSource? = null,
    val idleLightImagePath: AssetSource? = null,
    val idleDarkImagePath: AssetSource? = null,
    val dialingImagePath: AssetSource? = null,
    val dialingLightImagePath: AssetSource? = null,
    val dialingDarkImagePath: AssetSource? = null,
    val thumbImagePath: AssetSource? = null,
    val thumbFramePaths: List<AssetSource> = emptyList(),
) {
    fun openingVideoFor(isDark: Boolean): String? =
        if (isDark) (openingDarkVideoUri ?: openingVideoUri) else (openingLightVideoUri ?: openingVideoUri)

    fun idleImageFor(isDark: Boolean): AssetSource? =
        if (isDark) (idleDarkImagePath ?: idleImagePath) else (idleLightImagePath ?: idleImagePath)

    fun dialingImageFor(isDark: Boolean): AssetSource? =
        if (isDark) (dialingDarkImagePath ?: dialingImagePath ?: idleImageFor(true))
        else (dialingLightImagePath ?: dialingImagePath ?: idleImageFor(false))

    fun hasAnyOpeningVideo(): Boolean =
        openingVideoUri != null || openingLightVideoUri != null || openingDarkVideoUri != null
}

data class SmsBannerNotice(
    val address: String,
    val displayName: String?,
    val preview: String,
    val otpCode: String?,
    val timestampMs: Long,
)

data class GtaStageState(
    val openingPhase: OpeningPhase = OpeningPhase.FALLBACK_ANIMATION,
    val activeMenu: DeckMenu = DeckMenu.PHONE,
    val screenState: AppScreenState = AppScreenState.Opening,
    val themeMode: StageThemeMode = StageThemeMode.SYSTEM,
    val dialBuffer: String = "",
    val selectedSmsAddress: String? = null,
    val isComposingNewSms: Boolean = false,
    val smsDraftNumber: String = "",
    val smsDraftBody: String = "",
    val incomingSmsBanner: SmsBannerNotice? = null,
    val isCalibrationGridVisible: Boolean = false,
    val stageConfig: GtaStageConfig = GtaStageConfig.default(),
    val resolvedAssets: ResolvedStageAssets = ResolvedStageAssets(),
    val soundEnabled: Boolean = true,
    val hapticEnabled: Boolean = true,
    val showLegacyDiagnostics: Boolean = false,
)

sealed interface InteractionEvent {
    data class MenuSwitched(val from: DeckMenu, val to: DeckMenu) : InteractionEvent
    data class KeyPressed(
        val keyId: VirtualKeyId,
        val label: String,
        val timestampMs: Long = System.currentTimeMillis(),
    ) : InteractionEvent

    data class TextChanged(
        val text: String,
        val addedChar: Char?,
        val timestampMs: Long = System.currentTimeMillis(),
    ) : InteractionEvent

    data class CallStarted(val number: String) : InteractionEvent
    data object CallConnected : InteractionEvent
    data object CallEnded : InteractionEvent
    data class SmsSent(val address: String, val body: String) : InteractionEvent
    data class SmsReceived(val notice: SmsBannerNotice) : InteractionEvent
    data object ReplayOpening : InteractionEvent
}
