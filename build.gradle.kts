// Root build script. Declares plugin versions once (via the version catalog) and wires the
// quality gates that every module shares: ktlint (formatting), detekt (static analysis) and
// Kover (coverage). Android Lint is configured in :app because it is an Android-only tool.
//
// `./gradlew check` runs everything; `./gradlew ci` is the alias CI invokes (check + report
// aggregation) so the workflow files never need to know individual task names.
import io.gitlab.arturbosch.detekt.Detekt
import org.jlleitschuh.gradle.ktlint.KtlintExtension

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.kover) apply false
}

val detektVersion = libs.versions.detekt.get()

subprojects {
    apply(plugin = "org.jlleitschuh.gradle.ktlint")
    apply(plugin = "io.gitlab.arturbosch.detekt")
    apply(plugin = "org.jetbrains.kotlinx.kover")

    configure<KtlintExtension> {
        // The ktlint rule set reads .editorconfig for the code style (ktlint_official).
        android.set(false)
        ignoreFailures.set(false)
        reporters {
            reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.PLAIN)
            reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.CHECKSTYLE)
            reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.SARIF)
        }
        filter {
            exclude { it.file.path.contains("/build/") }
        }
    }

    configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
        buildUponDefaultConfig = true
        allRules = false
        config.setFrom(rootProject.files("config/detekt/detekt.yml"))
        // No baseline on purpose: findings must be fixed or the rule tuned in detekt.yml.
        source.setFrom(
            files(
                "src/main/kotlin",
                "src/test/kotlin",
                "src/androidTest/kotlin",
            ),
        )
    }

    tasks.withType<Detekt>().configureEach {
        jvmTarget = "17"
        reports {
            html.required.set(true)
            xml.required.set(true)
            sarif.required.set(true)
            txt.required.set(false)
            md.required.set(false)
        }
    }

    dependencies {
        add("detektPlugins", "io.gitlab.arturbosch.detekt:detekt-formatting:$detektVersion")
    }
}

/**
 * Everything CI runs for a pull request, in one task: unit tests, ktlint, detekt, Android Lint
 * and Kover verification + reports. Each module wires its own gates into `check` (coverage
 * rules are per module so a drop in :core cannot be hidden by UI code), so this is just an
 * alias over them. Keep BUILDING.md in sync when adding a gate.
 */
tasks.register("ci") {
    group = "verification"
    description = "Runs every quality gate CI relies on: tests, ktlint, detekt, Android Lint, Kover verification + reports."
    dependsOn(subprojects.map { "${it.path}:check" })
}
