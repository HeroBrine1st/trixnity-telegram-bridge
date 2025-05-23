package ru.herobrine1st.matrix.bridge.telegram

import com.github.kotlintelegrambot.Bot
import com.github.kotlintelegrambot.bot
import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.entities.MessageId
import com.github.kotlintelegrambot.entities.TelegramFile
import com.github.kotlintelegrambot.types.TelegramBotResult
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.utils.io.jvm.javaio.toByteReadChannel
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import net.folivo.trixnity.clientserverapi.client.MatrixClientServerApiClient
import net.folivo.trixnity.clientserverapi.model.media.Media
import net.folivo.trixnity.core.model.events.ClientEvent
import net.folivo.trixnity.core.model.events.m.room.ImageInfo
import net.folivo.trixnity.core.model.events.m.room.RoomMessageEventContent
import ru.herobrine1st.matrix.bridge.api.EventHandlerScope
import ru.herobrine1st.matrix.bridge.api.RemoteRoom
import ru.herobrine1st.matrix.bridge.api.RemoteUser
import ru.herobrine1st.matrix.bridge.api.RemoteWorkerAPI
import ru.herobrine1st.matrix.bridge.api.value.RemoteEventId
import ru.herobrine1st.matrix.bridge.api.worker.BasicRemoteWorker
import ru.herobrine1st.matrix.bridge.exception.UnhandledEventException
import ru.herobrine1st.matrix.bridge.repository.generic.doublepuppeted.ActorProvisionRepository
import ru.herobrine1st.matrix.bridge.repository.generic.doublepuppeted.RemoteWorkerRepositorySet
import ru.herobrine1st.matrix.bridge.telegram.util.getOrThrow
import ru.herobrine1st.matrix.bridge.telegram.util.toResult
import ru.herobrine1st.matrix.bridge.telegram.value.TelegramActorData
import ru.herobrine1st.matrix.bridge.telegram.value.TelegramActorId
import ru.herobrine1st.matrix.bridge.telegram.value.UserId
import java.io.IOException
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.io.path.outputStream

class TelegramWorker(
    private val actorProvisionRepository: ActorProvisionRepository<TelegramActorId, TelegramActorData>,
    private val api: RemoteWorkerAPI<UserId, ChatId, MessageId>,
    private val client: MatrixClientServerApiClient,
) : BasicRemoteWorker<TelegramActorId, UserId, ChatId, MessageId> {
    override suspend fun EventHandlerScope<MessageId>.handleEvent(
        actorId: TelegramActorId,
        roomId: ChatId,
        event: ClientEvent.RoomEvent<*>,
    ) {
        when (event) {
            is ClientEvent.RoomEvent.MessageEvent<*> -> {
                when (val content = event.content) {
                    is RoomMessageEventContent -> {
                        val bot = getBot(actorId)
                        val result = when (content) {
                            is RoomMessageEventContent.TextBased.Text -> {
                                val textContent = content.body

                                withContext(Dispatchers.IO) {
                                    bot.sendMessage(
                                        chatId = roomId,
                                        text = "<${event.sender}>: $textContent",
                                    )
                                }
                            }

                            is RoomMessageEventContent.FileBased.Image -> {
                                val caption = when {
                                    content.fileName == null || content.fileName == content.body ->
                                        "${event.sender} sent a picture"

                                    else -> "${event.sender}: ${content.body}"
                                }

                                // encrypted or malformed if null
                                val url = content.url ?: return

                                downloadMatrixMedia(url) { photoFile ->
                                    withContext(Dispatchers.IO) {
                                        bot.sendPhoto(
                                            chatId = roomId,
                                            photo = photoFile,
                                            caption = caption,
                                        )
                                    }.toResult()
                                }
                            }

                            is RoomMessageEventContent.FileBased.File -> {
                                val caption = when {
                                    content.fileName == null || content.fileName == content.body ->
                                        "${event.sender} sent a file"

                                    else -> "${event.sender}: ${content.body}"
                                }

                                val url = content.url ?: return
                                val fileName = (content.fileName ?: content.body).take(255)
                                    .replace('/', '_')
                                    .replace('\\', '_')

                                downloadMatrixMedia(url, fileName) { file ->
                                    withContext(Dispatchers.IO) {
                                        bot.sendDocument(
                                            chatId = roomId,
                                            document = file,
                                            caption = caption,
                                        )
                                    }.toResult()
                                }
                            }

                            else -> {
                                throw UnhandledEventException("This event is not delivered due to lack of support")
                            }
                        }

                        linkMessageId(MessageId(result.getOrThrow().messageId))
                    }

                    else -> return
                }
            }

            is ClientEvent.RoomEvent.StateEvent<*> -> {
                return // TODO: Support for future states
            }
        }
    }

    @OptIn(ExperimentalPathApi::class)
    private suspend inline fun <T> downloadMatrixMedia(
        url: String,
        fileName: String = "file.tmp",
        crossinline block: suspend (TelegramFile) -> T,
    ): T {
        return client.media.download(url) { media: Media ->
            val tempFile = createTempDirectory().resolve(fileName)
            try {
                withContext(Dispatchers.IO) {
                    tempFile.outputStream().use { outputStream ->
                        media.content.toInputStream().transferTo(outputStream)
                    }
                }
                block(TelegramFile.ByFile(tempFile.toFile()))
            } finally {
                tempFile.parent.deleteRecursively()
            }
        }.getOrThrow()
    }

    override fun getEvents(actorId: TelegramActorId): Flow<BasicRemoteWorker.Event<UserId, ChatId, MessageId>> = flow {
        val bot = getBot(actorId)

        var offset: Long? = null

        while (true) {
            val result = withContext(Dispatchers.IO) {
                bot.getUpdates(
                    limit = 100,
                    offset = offset,
                    timeout = 30,
                )
            }

            when (result) {
                is TelegramBotResult.Success -> {
                    if (result.value.isEmpty()) continue // Short-circuit empty responses
                    result.value.forEach { update ->
                        update.message?.let { message ->
                            val (chat, sender, content) = run {
                                message.text?.let { text ->
                                    // will likely throw exceptions, but docs say it is null only in channels
                                    val sender = message.from!!
                                    return@run Triple(
                                        message.chat,
                                        sender,
                                        RoomMessageEventContent.TextBased.Text(body = text),
                                    )
                                }

                                message.photo?.maxByOrNull { it.fileSize ?: 0 }?.let { photo ->
                                    val sender = message.from!!

                                    val fileInfo = withContext(Dispatchers.IO) {
                                        bot.getFile(photo.fileId).toResult().getOrThrow()
                                    }

                                    val caption = message.caption
                                    val responseBody = fileInfo.filePath?.let { filePath ->
                                        withContext(Dispatchers.IO) {
                                            bot.downloadFile(filePath).toResult().getOrThrow()
                                        }
                                    } ?: return@run Triple(
                                        message.chat,
                                        sender,
                                        RoomMessageEventContent.TextBased.Notice(
                                            body = "Could not download photo. Caption was: $caption",
                                        ),
                                    )

                                    val mxcUrl = client.media.upload(
                                        Media(
                                            content = responseBody.byteStream().toByteReadChannel(),
                                            contentLength = responseBody.contentLength(),
                                            contentType = responseBody.contentType()?.let {
                                                ContentType(it.type(), it.subtype())
                                            },
                                            contentDisposition = ContentDisposition.Attachment.withParameter(
                                                "filename",
                                                "image.jpg",
                                            ),
                                        ),
                                    ).getOrThrow().contentUri

                                    return@run Triple(
                                        message.chat,
                                        sender,
                                        RoomMessageEventContent.FileBased.Image(
                                            fileName = "photo.jpg",
                                            body = caption ?: "photo.jpg",
                                            url = mxcUrl,
                                            info = ImageInfo(
                                                mimeType = "image/jpeg",
                                                size = photo.fileSize?.toLong(),
                                                height = photo.height,
                                                width = photo.width,
                                            ),
                                        ),
                                    )
                                }
                                return@forEach // TODO respond with unhandled event
                            }

                            val userId = UserId(sender.id)
                            val messageId = MessageId(message.messageId)
                            val chatId = ChatId.fromId(chat.id)

                            emit(
                                BasicRemoteWorker.Event.Remote.Room.Message(
                                    roomId = chatId,
                                    eventId = RemoteEventId(update.updateId.toString()),
                                    sender = userId,
                                    content = content,
                                    messageId = messageId,
                                ),
                            )
                        }
                    }
                    offset = result.value.last().updateId + 1
                }

                is TelegramBotResult.Error.Unknown -> throw IOException(
                    "Failed to get updates from Telegram",
                    result.exception,
                )

                is TelegramBotResult.Error -> throw IOException("Failed to get updates from Telegram: $result")
            }
        }
    }

    override suspend fun getUser(actorId: TelegramActorId, id: UserId): RemoteUser<UserId> {
        val bot = getBot(actorId)
        val chatId = ChatId.fromId(id.id)
        val chat = withContext(Dispatchers.IO) { bot.getChat(chatId).getOrThrow() }
        val displayName = chat.firstName ?: chat.username ?: "Unknown"
        return RemoteUser(
            id = id,
            displayName = displayName,
        )
    }

    override suspend fun getRoom(actorId: TelegramActorId, id: ChatId): RemoteRoom<UserId, ChatId> {
        val bot = getBot(actorId)
        val chat = withContext(Dispatchers.IO) { bot.getChat(id).getOrThrow() }

        check(chat.type != "channel") { "Can't create room for a channel" }
        check(chat.type != "private") { "Double puppeted bridge does not work with direct messages" }

        return RemoteRoom(
            id = id,
            directData = null,
            displayName = chat.title,
            realMembers = setOf(actorProvisionRepository.getActorData(actorId).admin),
        )
    }

    override fun getRoomMembers(actorId: TelegramActorId, remoteId: ChatId) = flow {
        val bot = getBot(actorId)

        // API Limitation: no access to full list
        val members = withContext(Dispatchers.IO) {
            bot.getChatAdministrators(remoteId).getOrThrow()
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
        private val repositorySet: RemoteWorkerRepositorySet<TelegramActorId, TelegramActorData, UserId>,
        private val client: MatrixClientServerApiClient,
    ) : BasicRemoteWorker.Factory<TelegramActorId, UserId, ChatId, MessageId> {
        override fun getRemoteWorker(
            api: RemoteWorkerAPI<UserId, ChatId, MessageId>,
        ): BasicRemoteWorker<TelegramActorId, UserId, ChatId, MessageId> =
            TelegramWorker(repositorySet.actorProvisionRepository, api, client)
    }
}