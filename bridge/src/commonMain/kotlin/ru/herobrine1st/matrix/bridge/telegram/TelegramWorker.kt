package ru.herobrine1st.matrix.bridge.telegram

import com.github.kotlintelegrambot.Bot
import com.github.kotlintelegrambot.bot
import com.github.kotlintelegrambot.dispatch
import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.entities.MessageId
import com.github.kotlintelegrambot.entities.Update
import com.github.kotlintelegrambot.types.TelegramBotResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.folivo.trixnity.core.model.events.ClientEvent
import net.folivo.trixnity.core.model.events.m.room.RoomMessageEventContent
import ru.herobrine1st.matrix.bridge.api.*
import ru.herobrine1st.matrix.bridge.exception.UnhandledEventException
import ru.herobrine1st.matrix.bridge.repository.generic.doublepuppeted.ActorProvisionRepository
import ru.herobrine1st.matrix.bridge.telegram.util.CallbackWrapper
import ru.herobrine1st.matrix.bridge.telegram.util.ChannelCopyingHandler
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

                        val (bot, _) = getBot(actorId)
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

    override fun getEvents(actorId: TelegramActorId): Flow<WorkerEvent<UserId, ChatId, MessageId>> {
        TODO("Not yet implemented")
    }

    override suspend fun getUser(
        actorId: TelegramActorId,
        id: UserId
    ): RemoteUser<UserId> {
        val (bot, _) = getBot(actorId)
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
        val (bot, _) = getBot(actorId)
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
        val (bot, _) = getBot(actorId)

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

    private val botCacheMutex = Mutex()
    private val botCache = mutableMapOf<TelegramActorId, Pair<Bot, ReceiveChannel<CallbackWrapper<Update>>>>()


    private suspend fun getBot(actorId: TelegramActorId): Pair<Bot, ReceiveChannel<CallbackWrapper<Update>>> {
        botCache[actorId]?.let {
            return it
        }
        return botCacheMutex.withLock {
            val actorData = actorProvisionRepository.getActorData(actorId)
            val eventChannel = Channel<CallbackWrapper<Update>>()
            val bot = bot {
                this.token = actorData.token
                dispatch {
                    addHandler(ChannelCopyingHandler(eventChannel))
                }
            }
            // called when `getEvents` is complete, both due to error and actor removal
            eventChannel.invokeOnClose {
                // kotlin-telegram-bot uses OkHttp internally. Shutdown isn't necessary.
                // https://square.github.io/okhttp/5.x/okhttp/okhttp3/-ok-http-client/index.html

                // Remove bot as its actor is most probably deleted
                // SAFETY: if `getEvents` is called concurrently to channel closing (which is not possible but not guaranteed),
                //     it will crash and then restore, so race condition is automatically recovered from.
                //     Bot is not shut down, so it can be used in fetcher methods
                botCache.remove(actorId)
            }
            val res = bot to eventChannel
            botCache.put(actorId, res)

            return res
        }
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