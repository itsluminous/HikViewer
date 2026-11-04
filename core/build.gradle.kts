// :core — pure Kotlin/JVM. Holds the Hik-Connect protocol (REST client, VTDU token, VTM relay
// framing, RTP/PS demux, NAL decryption, access-unit assembly). No Android imports, so every
// test runs on the JVM in seconds and the module can be driven from the command line.
plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

kotlin {
    jvmToolchain(17)
    explicitApi()
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)

    testImplementation(platform(libs.junit5.bom))
    testImplementation(libs.bundles.core.test)
    testRuntimeOnly(libs.junit5.platform.launcher)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// Coverage gate for the protocol module. Start at 70 % line coverage and raise as the module grows;
// protocol code is expected to end near 90 % because it is driven by known-answer tests.
kover {
    reports {
        total {
            xml { onCheck = true }
            html { onCheck = true }
            verify {
                onCheck = true
                rule("core line coverage") {
                    minBound(70)
                }
            }
        }
    }
}
