import java.util.Properties

// :app — the Android application. UI (grid, fullscreen, settings), MediaCodec decoder pool,
// encrypted preferences. Protocol code lives in :core so it stays testable on the JVM.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

/**
 * Release signing. Values come from environment variables (CI) or `keystore.properties` at the
 * repo root (local, gitignored). When nothing is configured we fall back to the debug keystore so
 * `assembleRelease` always produces an installable APK, e.g. for pull-request builds.
 */
val releaseSigning = ReleaseSigning.load(rootProject.file("keystore.properties"), providers)

android {
    namespace = "com.itsluminous.hikviewer"
    compileSdk =
        libs.versions.compileSdk
            .get()
            .toInt()

    defaultConfig {
        applicationId = "com.itsluminous.hikviewer"
        minSdk =
            libs.versions.minSdk
                .get()
                .toInt()
        targetSdk =
            libs.versions.targetSdk
                .get()
                .toInt()
        versionCode = 1
        versionName = "0.1.0"
        // Installs the fake-cloud Application so Espresso never touches Hik-Connect.
        testInstrumentationRunner = "com.itsluminous.hikviewer.TestRunner"
    }

    signingConfigs {
        if (releaseSigning != null) {
            create("release") {
                storeFile = releaseSigning.storeFile
                storePassword = releaseSigning.storePassword
                keyAlias = releaseSigning.keyAlias
                keyPassword = releaseSigning.keyPassword
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig =
                if (releaseSigning != null) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }

    // We have no native code, so the universal APK is small. Per-ABI splits are kept because
    // they are free and let the release job attach them too.
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            isUniversalApk = true
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = false
        }
    }

    // Fakes shared by the Robolectric and the Espresso tests (FakeCloudServices, SyntheticStream).
    // The TS fixture is :core's own capture; referencing its directory avoids a 190 KB duplicate.
    sourceSets {
        getByName("test") {
            kotlin.srcDir("src/sharedTest/kotlin")
            resources.srcDir("../core/src/test/resources")
        }
        getByName("androidTest") {
            kotlin.srcDir("src/sharedTest/kotlin")
            resources.srcDir("../core/src/test/resources")
        }
    }

    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module", "META-INF/LICENSE*", "DebugProbesKt.bin")
    }

    // Android Lint is a hard gate: every warning fails the build and dependencies (:core) are
    // checked too. There is deliberately no baseline file; fix or suppress with a justification.
    lint {
        abortOnError = true
        warningsAsErrors = true
        checkDependencies = true
        checkAllWarnings = true
        checkReleaseBuilds = false // lintDebug (run by `check`) covers the same sources
        lintConfig = rootProject.file("config/lint/lint.xml")
        xmlReport = true
        htmlReport = true
        sarifReport = true
        textReport = false
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.activity)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.security.crypto)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.material)
    // HikConnectClient's constructor has an OkHttpClient parameter; Kotlin needs the type to
    // compile a call that uses the default.
    implementation(libs.okhttp)

    testImplementation(libs.bundles.app.unit.test)
    androidTestImplementation(libs.bundles.app.android.test)
}

/** Resolved release-signing material, or `null` when neither env vars nor the properties file are set. */
data class ReleaseSigning(
    val storeFile: File,
    val storePassword: String,
    val keyAlias: String,
    val keyPassword: String,
) {
    companion object {
        fun load(
            propertiesFile: File,
            providers: ProviderFactory,
        ): ReleaseSigning? {
            val props = Properties()
            if (propertiesFile.isFile) propertiesFile.inputStream().use(props::load)

            fun value(
                envName: String,
                propName: String,
            ): String? = providers.environmentVariable(envName).orNull?.takeIf { it.isNotBlank() }
                ?: props.getProperty(propName)?.takeIf { it.isNotBlank() }

            val storePath = value("KEYSTORE_FILE", "storeFile") ?: return null
            val storeFile = propertiesFile.parentFile.resolve(storePath)
            if (!storeFile.isFile) return null
            return ReleaseSigning(
                storeFile = storeFile,
                storePassword = value("KEYSTORE_PASSWORD", "storePassword") ?: return null,
                keyAlias = value("KEY_ALIAS", "keyAlias") ?: return null,
                keyPassword = value("KEY_PASSWORD", "keyPassword") ?: return null,
            )
        }
    }
}

// Coverage gate for the UI module. Only the debug variant is measured (release has no extra code
// and running its unit tests twice buys nothing). Robolectric drives every screen and ViewModel
// with the fake cloud and recording decoders, so the module sits above 80 % lines; the floor is
// 75 %. The uncovered remainder is the MediaCodec plumbing (TileDecoder, KeyframeDecoderPool),
// which only a device can exercise and which the emulator run in BUILDING.md checks. Generated
// classes (view binding, R) are excluded explicitly: Kover 0.9.1's androidGeneratedClasses()
// helper excluded every class in this project.
kover {
    reports {
        filters {
            excludes {
                classes(
                    "com.itsluminous.hikviewer.databinding.*",
                    "com.itsluminous.hikviewer.BuildConfig",
                    "com.itsluminous.hikviewer.R",
                    "com.itsluminous.hikviewer.R$*",
                )
            }
        }
        total {
            xml { onCheck = false }
            html { onCheck = false }
            verify { onCheck = false }
        }
        variant("debug") {
            xml { onCheck = true }
            html { onCheck = true }
            verify {
                onCheck = true
                rule("app line coverage") {
                    minBound(75)
                }
            }
        }
    }
}
