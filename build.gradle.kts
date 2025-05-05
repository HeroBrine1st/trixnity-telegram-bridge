plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.shadow) apply false
    alias(libs.plugins.spotless)
}

allprojects {
    group = "ru.herobrine1st.matrix.bridge.telegram"
    version = "1.0.0-SNAPSHOT"

    apply(plugin = "com.diffplug.spotless")
    // Use predeclareDeps once this is huge
    // https://github.com/diffplug/spotless/tree/main/plugin-gradle#dependency-resolution-modes
    if (project != rootProject) spotless {
        isEnforceCheck = false
        kotlin {
            target("src/*/kotlin/**/*.kt")
            ktlint()
                .setEditorConfigPath("$rootDir/.editorconfig")
                .editorConfigOverride(mapOf(
                    "ktlint_code_style" to "intellij_idea",
                    "ktlint_standard_function-expression-body" to "disabled",
                ))
        }
    }
}