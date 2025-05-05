package ru.herobrine1st.matrix.bridge.telegram.value

import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual

fun getRemoteActorSerializersModule() = SerializersModule {
    contextual(ChatIdSerializer())
    contextual(MessageIdSerializer())
}