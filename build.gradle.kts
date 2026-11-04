// Root build script: declares plugin versions once (via the version catalog) so that modules
// can apply them without repeating versions. Module-specific configuration lives in each module.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
