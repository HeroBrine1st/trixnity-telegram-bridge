package ru.herobrine1st.matrix.bridge.telegram

import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.entities.MessageId
import kotlinx.coroutines.flow.Flow
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
        TODO("Not yet implemented")
    }

    override suspend fun getRoom(
        actorId: TelegramActorId,
        id: ChatId
    ): RemoteRoom<ChatId> {
        TODO("Not yet implemented")
    }

    override fun getRoomMembers(
        actorId: TelegramActorId,
        remoteId: ChatId
    ): Flow<Pair<UserId, RemoteUser<UserId>?>> {
        TODO("Not yet implemented")
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