package com.duophone.app.stage

import android.content.Context
import android.net.Uri
import com.duophone.app.diagnostics.DuoLogger
import com.duophone.app.diagnostics.LogCategory
import java.io.File

interface StageConfigStore {
    fun loadConfig(): GtaStageConfig
    fun saveConfig(config: GtaStageConfig)
    fun resetConfig(): GtaStageConfig
    fun rescanAssets(assetConfig: GtaAssetConfig): ResolvedStageAssets
    fun loadSoundEnabled(): Boolean
    fun saveSoundEnabled(enabled: Boolean)
    fun loadHapticEnabled(): Boolean
    fun saveHapticEnabled(enabled: Boolean)
    fun loadThemeMode(): StageThemeMode = StageThemeMode.SYSTEM
    fun saveThemeMode(mode: StageThemeMode) {}
}

class GtaConfigRepository(
    private val context: Context,
) : StageConfigStore {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val assetsDir: File by lazy {
        val dir = context.getExternalFilesDir("gta_assets") ?: File(context.filesDir, "gta_assets")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        dir
    }

    override fun loadConfig(): GtaStageConfig {
        val externalConfigFile = File(assetsDir, CONFIG_FILE_NAME)
        val baseConfig = when {
            externalConfigFile.isFile -> {
                runCatching {
                    GtaStageConfig.fromJson(externalConfigFile.readText())
                }.getOrElse { err ->
                    DuoLogger.w(LogCategory.DISPLAY, "gta_config_parse_err", err.message.orEmpty())
                    loadBundledConfigOrDefault()
                }
            }
            else -> loadBundledConfigOrDefault()
        }

        val hasOverlayOverrides = prefs.contains(KEY_LEFT)
        val overlay = if (hasOverlayOverrides) {
            PhoneOverlayConfig(
                leftFraction = prefs.getFloat(KEY_LEFT, baseConfig.phoneOverlay.leftFraction),
                topFraction = prefs.getFloat(KEY_TOP, baseConfig.phoneOverlay.topFraction),
                widthFraction = prefs.getFloat(KEY_WIDTH, baseConfig.phoneOverlay.widthFraction),
                heightFraction = prefs.getFloat(KEY_HEIGHT, baseConfig.phoneOverlay.heightFraction),
                rotationX = prefs.getFloat(KEY_ROT_X, baseConfig.phoneOverlay.rotationX),
                rotationY = prefs.getFloat(KEY_ROT_Y, baseConfig.phoneOverlay.rotationY),
                rotationZ = prefs.getFloat(KEY_ROT_Z, baseConfig.phoneOverlay.rotationZ),
                cameraDistanceDp = prefs.getFloat(KEY_CAM_DIST, baseConfig.phoneOverlay.cameraDistanceDp),
                cornerRadiusDp = prefs.getFloat(KEY_CORNER, baseConfig.phoneOverlay.cornerRadiusDp),
                breathingEnabled = prefs.getBoolean(KEY_BREATHING, baseConfig.phoneOverlay.breathingEnabled),
                breathingAmplitudeDp = prefs.getFloat(KEY_BREATH_AMP, baseConfig.phoneOverlay.breathingAmplitudeDp),
                screenBrightness = prefs.getFloat(KEY_BRIGHTNESS, baseConfig.phoneOverlay.screenBrightness),
            ).coerced()
        } else {
            baseConfig.phoneOverlay.coerced()
        }

        return baseConfig.copy(phoneOverlay = overlay)
    }

    override fun saveConfig(config: GtaStageConfig) {
        val coercedOverlay = config.phoneOverlay.coerced()
        val finalConfig = config.copy(phoneOverlay = coercedOverlay)
        prefs.edit()
            .putFloat(KEY_LEFT, coercedOverlay.leftFraction)
            .putFloat(KEY_TOP, coercedOverlay.topFraction)
            .putFloat(KEY_WIDTH, coercedOverlay.widthFraction)
            .putFloat(KEY_HEIGHT, coercedOverlay.heightFraction)
            .putFloat(KEY_ROT_X, coercedOverlay.rotationX)
            .putFloat(KEY_ROT_Y, coercedOverlay.rotationY)
            .putFloat(KEY_ROT_Z, coercedOverlay.rotationZ)
            .putFloat(KEY_CAM_DIST, coercedOverlay.cameraDistanceDp)
            .putFloat(KEY_CORNER, coercedOverlay.cornerRadiusDp)
            .putBoolean(KEY_BREATHING, coercedOverlay.breathingEnabled)
            .putFloat(KEY_BREATH_AMP, coercedOverlay.breathingAmplitudeDp)
            .putFloat(KEY_BRIGHTNESS, coercedOverlay.screenBrightness)
            .apply()

        runCatching {
            File(assetsDir, CONFIG_FILE_NAME).writeText(finalConfig.toJson())
        }.onFailure { err ->
            DuoLogger.w(LogCategory.DISPLAY, "gta_config_write_err", err.message.orEmpty())
        }
    }

    override fun resetConfig(): GtaStageConfig {
        prefs.edit()
            .remove(KEY_LEFT)
            .remove(KEY_TOP)
            .remove(KEY_WIDTH)
            .remove(KEY_HEIGHT)
            .remove(KEY_ROT_X)
            .remove(KEY_ROT_Y)
            .remove(KEY_ROT_Z)
            .remove(KEY_CAM_DIST)
            .remove(KEY_CORNER)
            .remove(KEY_BREATHING)
            .remove(KEY_BREATH_AMP)
            .remove(KEY_BRIGHTNESS)
            .apply()

        val defaults = loadBundledConfigOrDefault()
        runCatching {
            File(assetsDir, CONFIG_FILE_NAME).writeText(defaults.toJson())
        }
        return defaults
    }

    override fun rescanAssets(assetConfig: GtaAssetConfig): ResolvedStageAssets {
        val extConfigFile = File(assetsDir, CONFIG_FILE_NAME)
        val bundledFiles = runCatching {
            context.assets.list(BUNDLED_GTA_DIR)?.toSet().orEmpty()
        }.getOrDefault(emptySet())

        fun resolveVideoUri(fileName: String): String? {
            if (fileName.isBlank()) return null
            val external = File(assetsDir, fileName)
            if (external.isFile && external.length() > 0L) {
                return Uri.fromFile(external).toString()
            }
            if (fileName in bundledFiles) {
                return "asset:///$BUNDLED_GTA_DIR/$fileName"
            }
            return null
        }

        fun resolveImageSource(fileName: String): AssetSource? {
            if (fileName.isBlank()) return null
            val external = File(assetsDir, fileName)
            if (external.isFile && external.length() > 0L) {
                return AssetSource.ExternalFile(external.absolutePath)
            }
            if (fileName in bundledFiles) {
                return AssetSource.BundledAsset("$BUNDLED_GTA_DIR/$fileName")
            }
            return null
        }

        val frameSources = resolveFrameSequence(assetConfig.thumbFramesDir)
        val genericOpening = resolveVideoUri(assetConfig.openingVideoFile)
        val lightOpening = resolveVideoUri(assetConfig.openingLightVideoFile) ?: genericOpening
        val darkOpening = resolveVideoUri(assetConfig.openingDarkVideoFile) ?: genericOpening
        val genericIdle = resolveImageSource(assetConfig.idleImageFile)
        val lightIdle = resolveImageSource(assetConfig.idleLightImageFile) ?: genericIdle
        val darkIdle = resolveImageSource(assetConfig.idleDarkImageFile) ?: genericIdle
        val genericDialing = resolveImageSource(assetConfig.dialingImageFile)
        val lightDialing = resolveImageSource(assetConfig.dialingLightImageFile) ?: genericDialing
        val darkDialing = resolveImageSource(assetConfig.dialingDarkImageFile) ?: genericDialing

        return ResolvedStageAssets(
            assetsDirPath = assetsDir.absolutePath,
            externalConfigPresent = extConfigFile.isFile,
            openingVideoUri = genericOpening ?: lightOpening ?: darkOpening,
            openingLightVideoUri = lightOpening,
            openingDarkVideoUri = darkOpening,
            idleLoopVideoUri = resolveVideoUri(assetConfig.idleLoopVideoFile),
            idleImagePath = genericIdle ?: lightIdle ?: darkIdle,
            idleLightImagePath = lightIdle,
            idleDarkImagePath = darkIdle,
            dialingImagePath = genericDialing ?: lightDialing ?: darkDialing,
            dialingLightImagePath = lightDialing,
            dialingDarkImagePath = darkDialing,
            thumbImagePath = resolveImageSource(assetConfig.thumbImageFile),
            thumbFramePaths = frameSources,
        )
    }

    private fun resolveFrameSequence(dirName: String): List<AssetSource> {
        if (dirName.isBlank()) return emptyList()
        val extDir = File(assetsDir, dirName)
        if (extDir.isDirectory) {
            val files = extDir.listFiles { f ->
                f.isFile && isSupportedImageName(f.name)
            }?.sortedBy { it.name }.orEmpty()
            if (files.isNotEmpty()) {
                return files.map { AssetSource.ExternalFile(it.absolutePath) }
            }
        }
        val bundledDir = "$BUNDLED_GTA_DIR/$dirName"
        val bundledEntries = runCatching {
            context.assets.list(bundledDir)
                ?.filter { isSupportedImageName(it) }
                ?.sorted()
                .orEmpty()
        }.getOrDefault(emptyList())
        return bundledEntries.map { AssetSource.BundledAsset("$bundledDir/$it") }
    }

    private fun isSupportedImageName(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".png") || lower.endsWith(".webp") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
    }

    private fun loadBundledConfigOrDefault(): GtaStageConfig {
        return runCatching {
            context.assets.open("$BUNDLED_GTA_DIR/$CONFIG_FILE_NAME").bufferedReader().use { reader ->
                GtaStageConfig.fromJson(reader.readText())
            }
        }.getOrElse { GtaStageConfig.default() }
    }

    override fun loadSoundEnabled(): Boolean = prefs.getBoolean(KEY_SOUND_ENABLED, true)

    override fun saveSoundEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SOUND_ENABLED, enabled).apply()
    }

    override fun loadHapticEnabled(): Boolean = prefs.getBoolean(KEY_HAPTIC_ENABLED, true)

    override fun saveHapticEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_HAPTIC_ENABLED, enabled).apply()
    }

    override fun loadThemeMode(): StageThemeMode {
        val raw = prefs.getString(KEY_THEME_MODE, StageThemeMode.SYSTEM.name)
        return StageThemeMode.entries.firstOrNull { it.name == raw } ?: StageThemeMode.SYSTEM
    }

    override fun saveThemeMode(mode: StageThemeMode) {
        prefs.edit().putString(KEY_THEME_MODE, mode.name).apply()
    }

    companion object {
        private const val PREFS_NAME = "gta_stage_prefs"
        private const val BUNDLED_GTA_DIR = "gta"
        private const val CONFIG_FILE_NAME = "stage_config.json"

        private const val KEY_LEFT = "overlay_left"
        private const val KEY_TOP = "overlay_top"
        private const val KEY_WIDTH = "overlay_width"
        private const val KEY_HEIGHT = "overlay_height"
        private const val KEY_ROT_X = "overlay_rot_x"
        private const val KEY_ROT_Y = "overlay_rot_y"
        private const val KEY_ROT_Z = "overlay_rot_z"
        private const val KEY_CAM_DIST = "overlay_cam_dist"
        private const val KEY_CORNER = "overlay_corner"
        private const val KEY_BREATHING = "overlay_breathing"
        private const val KEY_BREATH_AMP = "overlay_breath_amp"
        private const val KEY_BRIGHTNESS = "overlay_brightness"
        private const val KEY_SOUND_ENABLED = "ui_sound_enabled"
        private const val KEY_HAPTIC_ENABLED = "ui_haptic_enabled"
        private const val KEY_THEME_MODE = "stage_theme_mode"
    }
}
