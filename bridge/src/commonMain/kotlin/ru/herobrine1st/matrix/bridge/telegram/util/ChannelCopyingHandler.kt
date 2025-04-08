package ru.herobrine1st.matrix.bridge.telegram.util

import com.github.kotlintelegrambot.Bot
import com.github.kotlintelegrambot.dispatcher.handlers.Handler
import com.github.kotlintelegrambot.entities.Update
import kotlinx.coroutines.channels.SendChannel

internal class ChannelCopyingHandler(
    private val channel: SendChannel<CallbackWrapper<Update>>,
) : Handler {
    override fun checkUpdate(update: Update): Boolean = true
    override suspend fun handleUpdate(
        bot: Bot,
        update: Update,
    ) {
        val wrapper = CallbackWrapper(update)
        channel.send(wrapper)
        wrapper.job.join()
    }
}