package ru.herobrine1st.matrix.bridge.telegram

import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.entities.MessageId
import io.ktor.server.application.*
import ru.herobrine1st.matrix.bridge.repository.RepositorySet
import ru.herobrine1st.matrix.bridge.repository.generic.doublepuppeted.ActorProvisionRepository
import ru.herobrine1st.matrix.bridge.telegram.value.TelegramActorData
import ru.herobrine1st.matrix.bridge.telegram.value.TelegramActorId
import ru.herobrine1st.matrix.bridge.telegram.value.UserId

expect suspend fun ApplicationEnvironment.createRepository(): Pair<RepositorySet<TelegramActorId, UserId, ChatId, MessageId>, ActorProvisionRepository<TelegramActorId, TelegramActorData>>
