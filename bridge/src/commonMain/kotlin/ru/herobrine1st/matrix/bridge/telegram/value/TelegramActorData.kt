package ru.herobrine1st.matrix.bridge.telegram.value

import kotlinx.serialization.Serializable
import net.folivo.trixnity.core.model.UserId

@Serializable
data class TelegramActorData(
    val token: String,
    val admin: UserId = UserId("@empty:for.migration"),
)