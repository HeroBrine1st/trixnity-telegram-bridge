import java.net.URI

dependencyResolutionManagement {
    @Suppress("UnstableApiUsage")
    repositories {
        mavenCentral()
        maven {
            url = URI("https://git.herobrine1st.ru/api/packages/HeroBrine1st/maven")
            name = "forgejo"
        }
        maven {
            url = URI("https://jitpack.io")
        }
    }
}

plugins {
    // Use the Foojay Toolchains plugin to automatically download JDKs required by subprojects.
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

rootProject.name = "trixnity-telegram-bridge"
include("bridge")