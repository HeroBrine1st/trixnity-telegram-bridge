package ru.herobrine1st.matrix.bridge.telegram.value

import com.github.kotlintelegrambot.entities.ChatId
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.decodeStructure
import kotlinx.serialization.encoding.encodeStructure

class ChatIdSerializer : KSerializer<ChatId> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("com.github.kotlintelegrambot.entities.ChatId") {
            element<Long>("channelId")
            element<String>("channelUsername")
        }

    override fun serialize(
        encoder: Encoder,
        value: ChatId
    ) {
        encoder.encodeStructure(descriptor) {
            when (value) {
                is ChatId.Id -> encodeLongElement(descriptor, 0, value.id)
                is ChatId.ChannelUsername -> encodeStringElement(descriptor, 1, value.username)
            }
        }
    }

    override fun deserialize(decoder: Decoder) = decoder.decodeStructure(descriptor) {
        when(val index = decodeElementIndex(descriptor)) {
            0 -> ChatId.Id(decodeLongElement(descriptor, 0))
            1 -> ChatId.ChannelUsername(decodeStringElement(descriptor, 1))
            else -> error("Unexpected index $index")
        }
    }

}