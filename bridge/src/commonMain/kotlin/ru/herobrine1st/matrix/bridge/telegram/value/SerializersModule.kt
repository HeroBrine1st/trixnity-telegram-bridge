package ru.herobrine1st.matrix.bridge.telegram.value

import kotlinx.serialization.modules.contextual
import kotlinx.serialization.modules.SerializersModule

fun getRemoteActorSerializersModule() = SerializersModule {
    contextual(ChatIdSerializer())
    contextual(MessageIdSerializer())
}