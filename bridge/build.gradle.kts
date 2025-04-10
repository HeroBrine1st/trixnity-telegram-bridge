plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.shadow)
}



tasks.shadowJar {
    manifest {
        attributes["Main-Class"] = "ru.herobrine1st.matrix.bridge.telegram.MainKt"
    }
    mergeServiceFiles()
}

kotlin {
    jvmToolchain(17)

    jvm()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.ktor.server.core)
            implementation(libs.ktor.server.callLogging)

            implementation(libs.trixnity.bridge.core)
            implementation(libs.trixnity.bridge.compat)
            implementation(libs.kotlinLogging)
            implementation(libs.telegram.bot)
        }
        jvmMain.dependencies {
            implementation(libs.trixnity.bridge.repository.doublepuppeted)
            implementation(libs.slf4j.simple)
            implementation(libs.ktor.server.cio)
            implementation(libs.r2dbc.postgresql)
            implementation(libs.r2dbc.pool)
        }
    }
}