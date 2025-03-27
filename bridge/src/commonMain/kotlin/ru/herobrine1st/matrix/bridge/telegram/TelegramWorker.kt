package ru.herobrine1st.matrix.bridge.telegram

import com.github.kotlintelegrambot.Bot
import com.github.kotlintelegrambot.bot
import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.entities.MessageId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import net.folivo.trixnity.core.model.events.ClientEvent
import ru.herobrine1st.matrix.bridge.api.EventHandlerScope
import ru.herobrine1st.matrix.bridge.api.RemoteRoom
import ru.herobrine1st.matrix.bridge.api.RemoteUser
import ru.herobrine1st.matrix.bridge.api.RemoteWorker
import ru.herobrine1st.matrix.bridge.api.RemoteWorkerAPI
import ru.herobrine1st.matrix.bridge.api.RemoteWorkerFactory
import ru.herobrine1st.matrix.bridge.api.WorkerEvent
import ru.herobrine1st.matrix.bridge.repository.generic.doublepuppeted.ActorProvisionRepository
import ru.herobrine1st.matrix.bridge.telegram.value.TelegramActorData
import ru.herobrine1st.matrix.bridge.telegram.value.TelegramActorId
import ru.herobrine1st.matrix.bridge.telegram.value.UserId

class TelegramWorker(
    private val actorProvisionRepository: ActorProvisionRepository<TelegramActorId, TelegramActorData>,
    private val api: RemoteWorkerAPI<UserId, ChatId, MessageId>
) : RemoteWorker<TelegramActorId, UserId, ChatId, MessageId> {
    override suspend fun EventHandlerScope<MessageId>.handleEvent(
        actorId: TelegramActorId,
        roomId: ChatId,
        event: ClientEvent.RoomEvent<*>
    ) {
        TODO("Not yet implemented")
    }

    override fun getEvents(actorId: TelegramActorId): Flow<WorkerEvent<UserId, ChatId, MessageId>> {
        TODO("Not yet implemented")
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