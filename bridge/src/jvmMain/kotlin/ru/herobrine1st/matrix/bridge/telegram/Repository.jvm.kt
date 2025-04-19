package ru.herobrine1st.matrix.bridge.telegram

import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.entities.MessageId
import io.ktor.server.application.ApplicationEnvironment
import io.r2dbc.pool.PoolingConnectionFactoryProvider.MAX_IDLE_TIME
import io.r2dbc.pool.PoolingConnectionFactoryProvider.MAX_SIZE
import io.r2dbc.pool.PoolingConnectionFactoryProvider.POOLING_DRIVER
import io.r2dbc.spi.ConnectionFactories
import io.r2dbc.spi.ConnectionFactoryOptions
import io.r2dbc.spi.ConnectionFactoryOptions.DATABASE
import io.r2dbc.spi.ConnectionFactoryOptions.DRIVER
import io.r2dbc.spi.ConnectionFactoryOptions.HOST
import io.r2dbc.spi.ConnectionFactoryOptions.PASSWORD
import io.r2dbc.spi.ConnectionFactoryOptions.PORT
import io.r2dbc.spi.ConnectionFactoryOptions.PROTOCOL
import io.r2dbc.spi.ConnectionFactoryOptions.USER
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.plus
import ru.herobrine1st.matrix.bridge.repository.AppServiceWorkerRepositorySet
import ru.herobrine1st.matrix.bridge.repository.generic.doublepuppeted.RemoteWorkerRepositorySet
import ru.herobrine1st.matrix.bridge.repository.generic.doublepuppeted.createR2DBCRepositorySet
import ru.herobrine1st.matrix.bridge.telegram.value.TelegramActorData
import ru.herobrine1st.matrix.bridge.telegram.value.TelegramActorId
import ru.herobrine1st.matrix.bridge.telegram.value.UserId
import ru.herobrine1st.matrix.bridge.telegram.value.getRemoteActorSerializersModule
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.toJavaDuration

actual suspend fun ApplicationEnvironment.createRepository(): Pair<AppServiceWorkerRepositorySet<TelegramActorId, UserId, ChatId, MessageId>, RemoteWorkerRepositorySet<TelegramActorId, TelegramActorData, UserId>> {
    return createR2DBCRepositorySet<TelegramActorId, UserId, ChatId, MessageId, TelegramActorData>(
        stringFormat = Json {
            serializersModule = Json.serializersModule + getRemoteActorSerializersModule()
        },
        connectionFactory = ConnectionFactories.get(ConnectionFactoryOptions.builder().apply {
            option(DRIVER, POOLING_DRIVER)
            option(PROTOCOL, "postgresql")
            with(config.config("ktor.deployment.database")) {
                option(HOST, property("host").getString())
                option(PORT, property("port").getString().toIntOrNull() ?: error("Database port invalid"))
                option(USER, property("username").getString())
                option(PASSWORD, property("password").getString())
                option(DATABASE, property("database").getString())
                propertyOrNull("pool.connectionIdleTimeMs")?.getString()?.let {
                    val duration = (it.toIntOrNull()
                        ?: error("Database connectionIdleTimeMs invalid")).milliseconds.toJavaDuration()
                    option(MAX_IDLE_TIME, duration)
                }
                propertyOrNull("pool.maxSize")?.getString()?.let {
                    option(MAX_SIZE, it.toIntOrNull() ?: error("Database maxPoolSize invalid"))
                }
            }

        }.build())
    )
}