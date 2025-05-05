package ru.herobrine1st.matrix.bridge.telegram

import com.github.kotlintelegrambot.entities.ChatId
import net.folivo.trixnity.core.model.RoomAliasId
import ru.herobrine1st.matrix.bridge.api.RemoteIdToMatrixMapper
import ru.herobrine1st.matrix.bridge.telegram.value.UserId

class RemoteIdToMatrixMapperImpl(
    private val roomAliasPrefix: String,
    private val puppetPrefix: String,
    private val homeserverDomain: String,
) : RemoteIdToMatrixMapper<ChatId, UserId> {
    override fun buildRoomAlias(remoteRoomId: ChatId): RoomAliasId {
        val suffix = when (remoteRoomId) {
            is ChatId.ChannelUsername -> "c_${remoteRoomId.username}"
            is ChatId.Id -> "i_${remoteRoomId.id}"
        }
        return RoomAliasId(localpart = roomAliasPrefix + suffix, domain = homeserverDomain)
    }

    override fun buildPuppetUserId(remoteUserId: UserId): net.folivo.trixnity.core.model.UserId =
        net.folivo.trixnity.core.model.UserId(localpart = "$puppetPrefix${remoteUserId.id}", domain = homeserverDomain)

    object Factory : RemoteIdToMatrixMapper.Factory<ChatId, UserId> {
        override fun create(roomAliasPrefix: String, puppetPrefix: String, homeserverDomain: String) =
            RemoteIdToMatrixMapperImpl(roomAliasPrefix, puppetPrefix, homeserverDomain)
    }
}