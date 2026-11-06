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
                "src/sharedTest/kotlin",
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
    dependsOn("noEmDash")
}

// ---------------------------------------------------------------------------------------------
// Em-dash guard. The project writes " - " (or a comma / colon) instead of U+2014 everywhere:
// in strings the TV's fonts render the glyph inconsistently, and in prose the rule keeps the
// text plain. `noEmDash` (part of `check` and `ci`) fails listing file:line for every U+2014
// in a tracked text file; `fixEmDash` rewrites them to "-" with the surrounding spaces normalised
// (both the spaced and the unspaced form become `a - b`).

/**
 * Tracked files that are text: everything `git ls-files` lists except known binary extensions.
 * A provider so the configuration cache re-runs `git ls-files` to validate its entry. The lambdas
 * capture only plain values (no script properties), which the configuration cache can serialize.
 */
fun trackedTextFiles(root: File): Provider<List<File>> {
    val binary = setOf("png", "jpg", "jpeg", "webp", "gif", "ts", "mkv", "mp4", "jar", "jks", "bin", "ico")
    return providers
        .exec {
            commandLine("git", "ls-files", "-z")
            workingDir = root
        }.standardOutput.asText
        .map { listing ->
            listing
                .split('\u0000')
                .filter { it.isNotEmpty() && it.substringAfterLast('.', "").lowercase() !in binary }
                .map { root.resolve(it) }
                .filter { it.isFile }
        }
}

val noEmDash = tasks.register("noEmDash") {
    group = "verification"
    description = "Fails if any tracked text file contains an em dash (U+2014); run fixEmDash to rewrite them."
    val root: File = rootDir
    val files = trackedTextFiles(root)
    val emDash = '\u2014'
    doLast {
        val hits =
            files.get().flatMap { file ->
                file
                    .readLines(Charsets.UTF_8)
                    .withIndex()
                    .filter { emDash in it.value }
                    .map { "${file.relativeTo(root)}:${it.index + 1}: ${it.value.trim()}" }
            }
        if (hits.isNotEmpty()) {
            throw GradleException(
                "Em dash (U+2014) found in ${hits.size} line(s); write ' - ' instead or run ./gradlew fixEmDash:\n" +
                    hits.joinToString("\n"),
            )
        }
        logger.lifecycle("noEmDash: no U+2014 in ${files.get().size} tracked text files")
    }
}

tasks.register("fixEmDash") {
    group = "formatting"
    description = "Rewrites every em dash (U+2014) in tracked text files to '-' with single spaces around it."
    val root: File = rootDir
    val files = trackedTextFiles(root)
    val emDash = '\u2014'
    doLast {
        val spacedDash = Regex("[ \\t]*$emDash[ \\t]*")
        var changed = 0
        var dashes = 0
        files.get().forEach { file ->
            val text = file.readText(Charsets.UTF_8)
            if (emDash !in text) return@forEach
            dashes += text.count { it == emDash }
            changed++
            file.writeText(text.replace(spacedDash, " - "), Charsets.UTF_8)
            logger.lifecycle("fixEmDash: ${file.relativeTo(root)}")
        }
        logger.lifecycle("fixEmDash: replaced $dashes em dash(es) in $changed file(s)")
    }
}

// `check` is a lifecycle task of the base plugin, which the root project does not apply; a root
// `check` that runs the guard lets `./gradlew check` from the repository root cover it.
tasks.register("check") {
    group = "verification"
    description = "Runs every module's check plus the root-level guards."
    dependsOn(subprojects.map { "${it.path}:check" })
    dependsOn(noEmDash)
}
