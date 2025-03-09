plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ktor) apply false
}

allprojects {
    group = "ru.herobrine1st.matrix.bridge.telegram"
    version = "1.0.0-SNAPSHOT"
}