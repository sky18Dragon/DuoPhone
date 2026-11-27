import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

base {
    archivesName.set("DuoPhone-v1.0.0")
}

val keystoreProperties = Properties().apply {
    val propFile = rootProject.file("keystore.properties")
    if (propFile.isFile) {
        propFile.inputStream().use(::load)
    }
}

fun signingProp(key: String, envKey: String): String? =
    keystoreProperties.getProperty(key)?.takeIf { it.isNotBlank() }
        ?: System.getenv(envKey)?.takeIf { it.isNotBlank() }

val releaseStoreFilePath = signingProp("storeFile", "DUOPHONE_STORE_FILE")
val releaseStorePassword = signingProp("storePassword", "DUOPHONE_STORE_PASSWORD")
val releaseKeyAlias = signingProp("keyAlias", "DUOPHONE_KEY_ALIAS")
val releaseKeyPassword = signingProp("keyPassword", "DUOPHONE_KEY_PASSWORD")
val hasReleaseSigning = !releaseStoreFilePath.isNullOrBlank() &&
    !releaseStorePassword.isNullOrBlank() &&
    !releaseKeyAlias.isNullOrBlank() &&
    !releaseKeyPassword.isNullOrBlank()

val pocketUi = providers.gradleProperty("pocketUi").orElse("compose").get()
require(pocketUi in setOf("compose", "top")) {
    "pocketUi must be compose or top. dual is blocked: PocketJS v0.12.0 has process-global runtime/UI."
}
val pocketNativeDir = rootProject.layout.projectDirectory.dir(".pocket-build/jniLibs")
val pocketAssetsDir = rootProject.layout.projectDirectory.dir(".pocket-build/assets")

android {
    namespace = "com.duophone.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.duophone.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    if (pocketUi == "top") {
        sourceSets.getByName("debug").jniLibs.srcDir(pocketNativeDir)
        sourceSets.getByName("debug").assets.srcDir(pocketAssetsDir)
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFilePath!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            buildConfigField("String", "POCKET_UI", "\"compose\"")
            buildConfigField("boolean", "POCKET_FAIL_INIT", "false")
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            buildConfigField("String", "POCKET_UI", "\"$pocketUi\"")
            buildConfigField("boolean", "POCKET_FAIL_INIT", providers.gradleProperty("pocketFailInit").orElse("false").get().also {
                require(it == "true" || it == "false")
            })
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.kotlinx.coroutines.android)

    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation("org.json:json:20240303")
    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
}

val buildPocketNative by tasks.registering(Exec::class) {
    group = "build"
    description = "Build pinned PocketJS ARM64 core, JNI adapter and UI bundle"
    workingDir(rootProject.projectDir)
    commandLine("bash", "scripts/build-pocketjs.sh")
}
if (pocketUi == "top") {
    tasks.matching { it.name == "preDebugBuild" }.configureEach { dependsOn(buildPocketNative) }
}
