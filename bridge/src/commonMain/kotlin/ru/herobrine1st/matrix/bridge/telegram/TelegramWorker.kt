package ru.herobrine1st.matrix.bridge.telegram

import com.github.kotlintelegrambot.Bot
import com.github.kotlintelegrambot.bot
import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.entities.MessageId
import com.github.kotlintelegrambot.types.TelegramBotResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import net.folivo.trixnity.core.model.events.ClientEvent
import net.folivo.trixnity.core.model.events.m.room.RoomMessageEventContent
import ru.herobrine1st.matrix.bridge.api.*
import ru.herobrine1st.matrix.bridge.api.value.RemoteEventId
import ru.herobrine1st.matrix.bridge.exception.UnhandledEventException
import ru.herobrine1st.matrix.bridge.repository.generic.doublepuppeted.ActorProvisionRepository
import ru.herobrine1st.matrix.bridge.telegram.value.TelegramActorData
import ru.herobrine1st.matrix.bridge.telegram.value.TelegramActorId
import ru.herobrine1st.matrix.bridge.telegram.value.UserId
import java.io.IOException

class TelegramWorker(
    private val actorProvisionRepository: ActorProvisionRepository<TelegramActorId, TelegramActorData>,
    private val api: RemoteWorkerAPI<UserId, ChatId, MessageId>
) : RemoteWorker<TelegramActorId, UserId, ChatId, MessageId> {
    override suspend fun EventHandlerScope<MessageId>.handleEvent(
        actorId: TelegramActorId,
        roomId: ChatId,
        event: ClientEvent.RoomEvent<*>
    ) {
        when (event) {
            is ClientEvent.RoomEvent.MessageEvent<*> -> {
                when (val content = event.content) {
                    is RoomMessageEventContent -> {
                        val textContent = when (content) {
                            is RoomMessageEventContent.TextBased.Text -> content.body
                            else -> throw UnhandledEventException(
                                message = "This event is not delivered due to lack of support"
                            )
                        }

                        val bot = getBot(actorId)
                        val result = withContext(Dispatchers.IO) {
                            bot.sendMessage(
                                chatId = roomId,
                                text = "<${event.sender}>: $textContent"
                            )
                        }

                        when (result) {
                            is TelegramBotResult.Success -> {
                                linkMessageId(MessageId(result.value.messageId))
                            }
                            is TelegramBotResult.Error -> {
                                when (result) {
                                    is TelegramBotResult.Error.HttpError -> throw IOException("HTTP error ${result.httpCode}: ${result.description}")
                                    is TelegramBotResult.Error.TelegramApi -> error("Telegram API error ${result.errorCode}: ${result.description}")
                                    is TelegramBotResult.Error.InvalidResponse -> throw IOException("Invalid response (HTTP ${result.httpCode}): ${result.httpStatusMessage}")
                                    is TelegramBotResult.Error.Unknown -> throw result.exception
                                }
                            }
                        }
                    }
                    else -> return
                }
            }
            is ClientEvent.RoomEvent.StateEvent<*> -> {
                return // TODO: Support for future states
            }
        }
    }

    override fun getEvents(actorId: TelegramActorId): Flow<WorkerEvent<UserId, ChatId, MessageId>> = flow {
        val bot = getBot(actorId)

        var offset: Long? = null

        while (true) {
            val result = withContext(Dispatchers.IO) {
                bot.getUpdates(
                    limit = 100,
                    offset = offset,
                    timeout = 30
                )
            }

            when (result) {
                is TelegramBotResult.Success -> {
                    if (result.value.isEmpty()) continue // Short-circuit empty responses
                    result.value.forEach { update ->
                        update.message?.let { message ->
                            val (chat, sender, content) = run {
                                message.text?.let { text ->
                                    val sender = message.from!! // will likely throw exceptions, but docs say it is null only in channels
                                    return@run Triple(message.chat, sender, RoomMessageEventContent.TextBased.Text(body = text))
                                }
                                return@forEach // TODO respond with unhandled event
                            }

                            val userId = UserId(sender.id)
                            val messageId = MessageId(message.messageId)
                            val chatId = ChatId.fromId(chat.id)

                            emit(
                                WorkerEvent.RemoteEvent(
                                    RoomEvent.MessageEvent(
                                        roomId = chatId,
                                        eventId = RemoteEventId(update.updateId.toString()),
                                        sender = userId,
                                        content = content,
                                        messageId = messageId
                                    )
                                )
                            )
                        }
                    }
                    offset = result.value.last().updateId + 1
                }

                is TelegramBotResult.Error.Unknown -> throw IOException("Failed to get updates from Telegram", result.exception)
                is TelegramBotResult.Error -> throw IOException("Failed to get updates from Telegram: $result")
            }
        }
    }

    override suspend fun getUser(
        actorId: TelegramActorId,
        id: UserId
    ): RemoteUser<UserId> {
        val bot = getBot(actorId)
        val chatId = ChatId.fromId(id.id)
        val chat = withContext(Dispatchers.IO) { bot.getChat(chatId).get() }
        val displayName = chat.firstName ?: chat.username ?: "Unknown"
        return RemoteUser(
            remoteId = id,
            displayName = displayName
        )
    }

    override suspend fun getRoom(
        actorId: TelegramActorId,
        id: ChatId
    ): RemoteRoom<ChatId> {
        val bot = getBot(actorId)
        val chat = withContext(Dispatchers.IO) { bot.getChat(id).get() }

        check(chat.type != "channel") { "Can't create room for a channel" }

        return RemoteRoom(
            id = id,
            isDirect = chat.type == "private",
            displayName = chat.title
        )
    }

    override fun getRoomMembers(
        actorId: TelegramActorId,
        remoteId: ChatId
    ): Flow<Pair<UserId, RemoteUser<UserId>?>> = flow {
        val bot = getBot(actorId)

        // API Limitation: no access to full list
        val members = withContext(Dispatchers.IO) {
            bot.getChatAdministrators(remoteId).get()
        }

        for (member in members) {
            val userId = UserId(member.user.id)
            val remoteUser = RemoteUser(userId, member.user.firstName)
            emit(userId to remoteUser)
        }
    }

    private suspend fun getBot(actorId: TelegramActorId): Bot {
        val actorData = actorProvisionRepository.getActorData(actorId)
        val token = actorData.token
        return bot { this.token = token }
    }

    class Factory(
        private val actorProvisionRepository: ActorProvisionRepository<TelegramActorId, TelegramActorData>
    ) : RemoteWorkerFactory<TelegramActorId, UserId, ChatId, MessageId> {
        override fun getRemoteWorker(
            api: RemoteWorkerAPI<UserId, ChatId, MessageId>
        ): RemoteWorker<TelegramActorId, UserId, ChatId, MessageId> {
            return TelegramWorker(actorProvisionRepository, api)
        }
    }
}