package ru.herobrine1st.matrix.bridge.telegram.value

import com.github.kotlintelegrambot.entities.MessageId
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

class MessageIdSerializer : KSerializer<MessageId> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.github.kotlintelegrambot.entities.MessageId", PrimitiveKind.LONG)
    override fun serialize(encoder: Encoder, value: MessageId) {
        encoder.encodeLong(value.messageId)
    }

    override fun deserialize(decoder: Decoder): MessageId = MessageId(decoder.decodeLong())
}