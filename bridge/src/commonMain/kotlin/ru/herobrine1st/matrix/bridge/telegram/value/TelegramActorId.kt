package ru.herobrine1st.matrix.bridge.telegram.value

import kotlinx.serialization.Serializable

@Serializable
data class TelegramActorId(
    val id: Long
)