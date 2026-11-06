import java.util.Properties

// :app - the Android application. UI (grid, fullscreen, settings), MediaCodec decoder pool,
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

/**
 * App version. The release workflow passes the tag minus its leading `v`
 * (`./gradlew assembleRelease -PversionName=1.2.3`); a build without the property is `0.0.0-dev`
 * with versionCode 1, so a dev build never outranks a published release on a device.
 */
val appVersion = AppVersion.from(providers.gradleProperty("versionName").orNull)

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
        versionCode = appVersion.code
        versionName = appVersion.name
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

    // One universal APK per build type (`app-debug.apk`, `app-release.apk`) and no ABI splits:
    // the app ships no native code, so a per-ABI APK would be the same bytes behind a manifest
    // filter and only confuse sideloading. A release built with `-PversionName=X.Y.Z` (the
    // release workflow) is written as `HikViewer-X.Y.Z.apk`, the file name the GitHub release carries.
    // `applicationVariants` is the old variant API; the new one has no output-file-name hook yet.
    @Suppress("DEPRECATION")
    applicationVariants.configureEach {
        if (buildType.name == "release" && appVersion.fromProperty) {
            outputs.configureEach {
                (this as com.android.build.gradle.api.ApkVariantOutput).outputFileName =
                    "HikViewer-${appVersion.name}.apk"
            }
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
    // The TS fixture is :core's own synthetic clip; referencing its directory avoids a 280 KB duplicate.
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

/**
 * `versionName` / `versionCode` pair. [code] is `major * 10000 + minor * 100 + patch` of the
 * numeric part of [name] (`1.2.3` -> 10203, `0.1` -> 100, `0.1.0` -> 100); a pre-release suffix
 * such as `-rc1` is ignored for the code, so `1.0.0-rc1` and `1.0.0` share 10000 and the final
 * build must be installed over the candidate with `-r`. The code is clamped to at least 1 because
 * Android rejects 0. [fromProperty] says whether the name came from `-PversionName` (a release)
 * rather than the dev default.
 */
data class AppVersion(
    val name: String,
    val code: Int,
    val fromProperty: Boolean,
) {
    companion object {
        private const val DEV_NAME = "0.0.0-dev"
        private val semver = Regex("""^(\d+)\.(\d+)(?:\.(\d+))?(?:-[0-9A-Za-z.]+)?$""")

        fun from(property: String?): AppVersion {
            val name = property?.trim()?.takeIf { it.isNotEmpty() } ?: DEV_NAME
            val match =
                semver.matchEntire(name)
                    ?: error(
                        "versionName '$name' must look like X.Y, X.Y.Z or X.Y.Z-suffix " +
                            "(the release tag without its leading 'v')",
                    )
            val (major, minor, patch) = match.destructured
            require(major.toInt() < 100 && minor.toInt() < 100 && (patch.toIntOrNull() ?: 0) < 100) {
                "versionName '$name': each component must be below 100 so versionCode stays unambiguous"
            }
            val code = major.toInt() * 10000 + minor.toInt() * 100 + (patch.toIntOrNull() ?: 0)
            return AppVersion(name = name, code = maxOf(code, 1), fromProperty = property != null)
        }
    }
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
