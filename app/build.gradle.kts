import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Local HA connection details live in a gitignored secrets.properties (see
// secrets.properties.example) so a real token/host never lands in source
// control. Falls back to placeholders so a fresh clone still compiles.
val secrets = Properties().apply {
    val f = rootProject.file("secrets.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun secret(key: String, default: String) = (secrets.getProperty(key) ?: default)

/**
 * Copies device/config/dashboard.json into the APK's assets, so the layout
 * the remote runs is also the one written out on first start and fallen back
 * to when /sdcard/astrion/dashboard.json can't be read. One copy, not two.
 */
abstract class BundleDashboardConfig : DefaultTask() {
    @get:InputFile
    abstract val source: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        source.get().asFile.copyTo(outputDir.file("dashboard.json").get().asFile, overwrite = true)
    }
}

val bundleDashboardConfig = tasks.register<BundleDashboardConfig>("bundleDashboardConfig") {
    source.set(rootProject.layout.projectDirectory.file("device/config/dashboard.json"))
}

android {
    namespace = "com.custom.astrion"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.custom.astrion"
        // Astrion HA100 runs Android 8.1 (API 27). minSdk 26 keeps a little
        // headroom while covering the device; targetSdk stays modern.
        minSdk = 26
        targetSdk = 34
        versionCode = 12
        versionName = "1.2.0"

        buildConfigField("String", "HA_URL", "\"${secret("haUrl", "http://YOUR_HA_IP:8123")}\"")
        buildConfigField("String", "HA_TOKEN", "\"${secret("haToken", "YOUR_LONG_LIVED_ACCESS_TOKEN")}\"")
    }

    buildTypes {
        // Install the release build on the remote: Compose runs markedly
        // slower in a debuggable build, and R8 strips the unused bulk of
        // material-icons-extended and friends (17 MB -> 1.7 MB). Signed with
        // the debug key, so it installs over the debug APK with no keystore
        // to manage; this app is sideloaded, never published.
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }


    // android.util.Log in HaClient returns defaults instead of throwing in JVM tests.
    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(bundleDashboardConfig, BundleDashboardConfig::outputDir)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.04.00")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    // Extended icon set (FastRewind, PowerSettingsNew, etc.) used by cards.
    implementation("androidx.compose.material:material-icons-extended")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    // OkHttp provides the WebSocket transport.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("junit:junit:4.13.2")
}
