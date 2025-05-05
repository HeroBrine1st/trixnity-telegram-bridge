package ru.herobrine1st.matrix.bridge.telegram

import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.entities.MessageId
import io.ktor.server.application.ApplicationEnvironment
import ru.herobrine1st.matrix.bridge.repository.AppServiceWorkerRepositorySet
import ru.herobrine1st.matrix.bridge.repository.generic.doublepuppeted.RemoteWorkerRepositorySet
import ru.herobrine1st.matrix.bridge.telegram.value.TelegramActorData
import ru.herobrine1st.matrix.bridge.telegram.value.TelegramActorId
import ru.herobrine1st.matrix.bridge.telegram.value.UserId

@Suppress("ktlint:standard:max-line-length")
expect suspend fun ApplicationEnvironment.createRepository(): Pair<AppServiceWorkerRepositorySet<TelegramActorId, UserId, ChatId, MessageId>, RemoteWorkerRepositorySet<TelegramActorId, TelegramActorData, UserId>>