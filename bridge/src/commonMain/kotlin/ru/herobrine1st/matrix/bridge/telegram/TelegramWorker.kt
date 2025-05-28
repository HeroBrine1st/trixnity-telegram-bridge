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
import net.folivo.trixnity.core.model.events.m.RelatesTo
import net.folivo.trixnity.core.model.events.m.room.FileInfo
import net.folivo.trixnity.core.model.events.m.room.ImageInfo
import net.folivo.trixnity.core.model.events.m.room.RedactionEventContent
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
import net.folivo.trixnity.core.model.UserId as MxUserId

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
                when (val contentRaw = event.content) {
                    is RoomMessageEventContent -> {
                        val replacement = (contentRaw.relatesTo as? RelatesTo.Replace)?.let {
                            val messageId = api.getMessageEventId(it.eventId) ?: return@let null

                            val originalEvent = client.room.getEvent(event.roomId, it.eventId).getOrThrow()
                            val originalContent = originalEvent.content
                            if (originalContent !is RoomMessageEventContent) {
                                throw UnhandledEventException(
                                    "The edited event (${it.eventId}) is likely not known to database",
                                )
                            }

                            val newContent = it.newContent

                            if (newContent !is RoomMessageEventContent) {
                                throw UnhandledEventException(
                                    "The bridge does not support this event because it is not room message event: $newContent",
                                )
                            }

                            @Suppress("ktlint:standard:max-line-length")
                            val isTheSame = when (originalContent) {
                                // avoid reflection at all costs
                                is RoomMessageEventContent.FileBased.Audio -> newContent is RoomMessageEventContent.FileBased.Audio
                                is RoomMessageEventContent.FileBased.File -> newContent is RoomMessageEventContent.FileBased.File
                                is RoomMessageEventContent.FileBased.Image -> newContent is RoomMessageEventContent.FileBased.Image
                                is RoomMessageEventContent.FileBased.Video -> newContent is RoomMessageEventContent.FileBased.Video
                                is RoomMessageEventContent.TextBased.Emote -> newContent is RoomMessageEventContent.TextBased.Emote
                                is RoomMessageEventContent.TextBased.Notice -> newContent is RoomMessageEventContent.TextBased.Notice
                                is RoomMessageEventContent.TextBased.Text -> newContent is RoomMessageEventContent.TextBased.Text
                                is RoomMessageEventContent.Location -> newContent is RoomMessageEventContent.Location
                                // are not supported
                                is RoomMessageEventContent.Unknown -> return
                                is RoomMessageEventContent.VerificationRequest -> return
                            }

                            if (!isTheSame) {
                                throw UnhandledEventException(
                                    "This event replacement is not sent because Telegram does not support changing message types",
                                )
                            }

                            Pair(
                                messageId,
                                newContent,
                                // Previous replacement can be fetched to optimise caption-only replacements performance
                                // but trixnity requires `from` token although it is not needed
                                // spec.matrix.org/v1.14/client-server-api/#get_matrixclientv1roomsroomidrelationseventidreltype
                            )
                        }

                        val replyTo = (contentRaw.relatesTo as? RelatesTo.Reply)?.replyTo?.let {
                            api.getMessageEventId(it.eventId)
                        }

                        check(replacement == null || replyTo == null) {
                            "Replacement event is never canonical: replyTo is not null"
                        }

                        val content = replacement?.second ?: contentRaw

                        val bot = getBot(actorId)

                        when (content) {
                            is RoomMessageEventContent.TextBased.Text if replacement != null -> withContext(
                                Dispatchers.IO,
                            ) {
                                bot.editMessageText(
                                    chatId = roomId,
                                    messageId = replacement.first.messageId,
                                    text = "<${event.sender}>: ${content.body}",
                                ).toResult()
                            }.ignoreTheSameContentOrThrow()

                            is RoomMessageEventContent.TextBased.Text -> {
                                val textContent = content.body

                                withContext(Dispatchers.IO) {
                                    bot.sendMessage(
                                        chatId = roomId,
                                        text = "<${event.sender}>: $textContent",
                                        replyToMessageId = replyTo?.messageId,
                                    )
                                }.getOrThrow().let { (messageId) ->
                                    linkMessageId(MessageId(messageId))
                                }
                            }

                            is RoomMessageEventContent.FileBased.Image -> {
                                val caption = when {
                                    content.fileName == null || content.fileName == content.body ->
                                        if (replacement == null) "${event.sender} sent a picture"
                                        else "${event.sender} replaced a picture or removed its caption"

                                    else ->
                                        if (replacement == null) "${event.sender}: ${content.body}"
                                        else "${event.sender} (edit): ${content.body}"
                                }

                                downloadMatrixMedia(content) { photoFile ->
                                    withContext(Dispatchers.IO) {
                                        bot.sendPhoto(
                                            chatId = roomId,
                                            photo = photoFile,
                                            caption = caption,
                                            // editMessageMedia endpoint fails, so reply is used as a workaround
                                            // exploiting the fact that replacement event is not canonical
                                            replyToMessageId = replacement?.first?.messageId
                                                ?: replyTo?.messageId,
                                        )
                                    }.toResult().getOrThrow().let { (messageId) ->
                                        linkMessageId(MessageId(messageId))
                                    }
                                }
                            }

                            is RoomMessageEventContent.FileBased.File -> {
                                val caption = when {
                                    content.fileName == null || content.fileName == content.body ->
                                        if (replacement == null) "${event.sender} sent a file"
                                        else "${event.sender} replaced a file or removed its caption"

                                    else ->
                                        if (replacement == null) "${event.sender}: ${content.body}"
                                        else "${event.sender} (edit): ${content.body}"
                                }

                                downloadMatrixMedia(content) { file ->
                                    withContext(Dispatchers.IO) {
                                        bot.sendDocument(
                                            chatId = roomId,
                                            document = file,
                                            caption = caption,
                                            replyToMessageId = replacement?.first?.messageId
                                                ?: replyTo?.messageId,
                                        )
                                    }.toResult().getOrThrow().let { (messageId) ->
                                        linkMessageId(MessageId(messageId))
                                    }
                                }
                            }

                            else -> {
                                throw UnhandledEventException("This event is not delivered due to lack of support")
                            }
                        }
                    }

                    is RedactionEventContent -> {
                        val messageId = api.getMessageEventId(contentRaw.redacts) ?: return

                        val bot = getBot(actorId)

                        withContext(Dispatchers.IO) {
                            val res = bot.deleteMessage(roomId, messageId.messageId)
                            when (res) {
                                // idempotency
                                is TelegramBotResult.Error.TelegramApi
                                if res.description == "Bad Request: message to delete not found" -> {}
                                // - too old message
                                // - bot can't delete another's message
                                // (API does not differentiate between those two cases)
                                is TelegramBotResult.Error.TelegramApi
                                if res.description == "Bad Request: message can't be deleted" -> {
                                    throw UnhandledEventException(
                                        "Could not replicate this redaction. Message is likely too old or bot has no permissions to delete messages it did not send",
                                    )
                                }

                                else -> res.getOrThrow()
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

    @OptIn(ExperimentalPathApi::class)
    private suspend inline fun <T> downloadMatrixMedia(
        content: RoomMessageEventContent.FileBased,
        crossinline block: suspend (TelegramFile) -> T,
    ): T {
        val url = content.url ?: throw UnhandledEventException("Event content has no url")
        val fileName = (content.fileName ?: content.body).take(255)
            .replace('/', '_')
            .replace('\\', '_')
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
                        (update.message?.let { it to false } ?: update.editedMessage?.let { it to true })
                            ?.let { (message, isReplacement) ->
                                // will likely throw exceptions, but docs say message.from is null only in channels
                                val userId = UserId(message.from!!.id)
                                val messageId = MessageId(message.messageId)
                                val chatId = ChatId.fromId(message.chat.id)

                                if (!api.isRoomBridged(chatId)) {
                                    // Handling commands here
                                    val text = message.text ?: return@forEach
                                    if (text.startsWith("/start")) {
                                        withContext(Dispatchers.IO) {
                                            bot.sendMessage(
                                                chatId,
                                                "Use /bridge <fully qualified matrix user id> to create new bridged room on matrix side",
                                            ).getOrThrow()
                                        }
                                    } else if (text.startsWith("/bridge ")) {
                                        // TODO validate from matrix side - probably a feature for trixnity-bridge
                                        val userId = MxUserId(text.substringAfter("/bridge "))
                                        if (userId != MxUserId(userId.localpart, userId.domain)) {
                                            withContext(Dispatchers.IO) {
                                                bot.sendMessage(chatId, "User ID is not valid").getOrThrow()
                                            }
                                            return@forEach
                                        }
                                        emit(
                                            BasicRemoteWorker.Event.Remote.Room.Create(
                                                chatId,
                                                roomData = getRoom(actorId, chatId).copy(realMembers = setOf(userId)),
                                            ),
                                        )
                                        withContext(Dispatchers.IO) {
                                            bot.sendMessage(chatId, "Complete! Look for invite on matrix side").getOrThrow()
                                        }
                                    }

                                    return@forEach
                                }

                                val rawContent = run {
                                    message.text?.let { text ->
                                        return@run RoomMessageEventContent.TextBased.Text(body = text)
                                    }

                                    message.document?.let { document ->
                                        val fileInfo = withContext(Dispatchers.IO) {
                                            bot.getFile(document.fileId).toResult().get()
                                        }

                                        val responseBody = fileInfo.filePath?.let { filePath ->
                                            withContext(Dispatchers.IO) {
                                                bot.downloadFile(filePath).toResult().get()
                                            }
                                        } ?: return@run RoomMessageEventContent.TextBased.Notice(
                                            body = "Could not download file. Caption was: ${message.caption}",
                                        )

                                        val mxcUrl = client.media.upload(
                                            Media(
                                                content = responseBody.byteStream().toByteReadChannel(),
                                                contentLength = responseBody.contentLength(),
                                                contentType = responseBody.contentType()?.let {
                                                    ContentType(it.type(), it.subtype())
                                                },
                                                contentDisposition = document.fileName?.let { fileName ->
                                                    ContentDisposition.Attachment.withParameter(
                                                        "filename",
                                                        fileName,
                                                    )
                                                },
                                            ),
                                        ).getOrThrow().contentUri

                                        return@run RoomMessageEventContent.FileBased.File(
                                            fileName = document.fileName,
                                            body = message.caption ?: document.fileName ?: "file",
                                            url = mxcUrl,
                                            info = FileInfo(
                                                mimeType = document.mimeType,
                                                size = document.fileSize?.toLong(),
                                            ),
                                        )
                                    }

                                    message.photo?.maxByOrNull { it.fileSize ?: 0 }?.let { photo ->
                                        val fileInfo = withContext(Dispatchers.IO) {
                                            bot.getFile(photo.fileId).toResult().getOrThrow()
                                        }

                                        val caption = message.caption
                                        val responseBody = fileInfo.filePath?.let { filePath ->
                                            withContext(Dispatchers.IO) {
                                                bot.downloadFile(filePath).toResult().getOrThrow()
                                            }
                                        } ?: return@run RoomMessageEventContent.TextBased.Notice(
                                            body = "Could not download photo. Caption was: $caption",
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

                                        return@run RoomMessageEventContent.FileBased.Image(
                                            fileName = "photo.jpg",
                                            body = caption ?: "photo.jpg",
                                            url = mxcUrl,
                                            info = ImageInfo(
                                                mimeType = "image/jpeg",
                                                size = photo.fileSize?.toLong(),
                                                height = photo.height,
                                                width = photo.width,
                                            ),
                                        )
                                    }
                                    return@forEach // TODO respond with unhandled event
                                }

                                val content = if (isReplacement) RoomMessageEventContent.TextBased.Text(
                                    body = "* ${rawContent.body}", // spec does not mention requirements on outer fields
                                    relatesTo = RelatesTo.Replace(
                                        api.getMessageEventId(MessageId(message.messageId))
                                            ?: return@forEach, // ignore events created before bridging
                                        newContent = rawContent,
                                    ),
                                ) else rawContent

                                emit(
                                    BasicRemoteWorker.Event.Remote.Room.Message(
                                        roomId = chatId,
                                        eventId = RemoteEventId(update.updateId.toString()),
                                        sender = userId,
                                        content = content,
                                        messageId = if (!isReplacement) messageId else null,
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

    private fun TelegramBotResult<*>.ignoreTheSameContentOrThrow() {
        if (this !is TelegramBotResult.Error.TelegramApi ||
            errorCode != 400 ||
            description != "Bad Request: message is not modified: " +
            "specified new message content and reply markup are exactly the same " +
            "as a current content and reply markup of the message"
        ) {
            getOrThrow()
        }
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