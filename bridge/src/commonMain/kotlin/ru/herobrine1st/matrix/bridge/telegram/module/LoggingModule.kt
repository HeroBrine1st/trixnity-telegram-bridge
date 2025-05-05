package ru.herobrine1st.matrix.bridge.telegram.module

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.calllogging.CallLogging

fun Application.loggingModule() {
    install(CallLogging)
}