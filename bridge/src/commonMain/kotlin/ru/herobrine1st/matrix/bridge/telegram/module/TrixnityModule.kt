package ru.herobrine1st.matrix.bridge.telegram.module

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.config.ApplicationConfig
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import net.folivo.trixnity.applicationserviceapi.server.matrixApplicationServiceApiServer
import net.folivo.trixnity.applicationserviceapi.server.matrixApplicationServiceApiServerRoutes
import net.folivo.trixnity.clientserverapi.client.MatrixClientServerApiClientImpl
import net.folivo.trixnity.core.serialization.events.DefaultEventContentSerializerMappings
import ru.herobrine1st.matrix.bridge.config.BridgeConfig
import ru.herobrine1st.matrix.bridge.config.BridgeConfig.Presence
import ru.herobrine1st.matrix.bridge.config.BridgeConfig.Provisioning
import ru.herobrine1st.matrix.bridge.telegram.TelegramWorker
import ru.herobrine1st.matrix.bridge.telegram.createRepository
import ru.herobrine1st.matrix.bridge.telegram.RemoteIdToMatrixMapperImpl
import ru.herobrine1st.matrix.bridge.worker.AppServiceWorker
import ru.herobrine1st.matrix.compat.ServiceMembersContentSerializerMappings

fun Application.trixnityModule() {
    val mappings = DefaultEventContentSerializerMappings + ServiceMembersContentSerializerMappings

    val (repositorySet, actorProvisionRepository) =
        // currently no way to wait on something is available in Ktor, blocking launch instead
        // (though we can TODO postpone migration and at least configure server without runBlocking)
        runBlocking {
            environment.createRepository()
        }
    val mxClient = MatrixClientServerApiClientImpl(
        baseUrl = Url(environment.config.property("ktor.deployment.homeserverUrl").getString()),
        eventContentSerializerMappings = mappings
    ).apply {
        accessToken.value = environment.config.property("ktor.deployment.asToken").getString()
    }

    val worker = AppServiceWorker(
        applicationJob = coroutineContext[Job]!!,
        client = mxClient,
        remoteWorkerFactory = TelegramWorker.Factory(actorProvisionRepository),
        repositorySet = repositorySet,
        idMapperFactory = RemoteIdToMatrixMapperImpl.Factory,
        bridgeConfig = BridgeConfig.fromConfig(environment.config.config("bridge"))
    )

    monitor.subscribe(ApplicationStarting) {
        runBlocking {
            worker.createAppServiceBot()
        }
        worker.startup()
    }

    matrixApplicationServiceApiServer(environment.config.property("ktor.deployment.hsToken").getString()) {
        matrixApplicationServiceApiServerRoutes(
            worker,
            eventContentSerializerMappings = mappings
        )
    }
}

// TODO move to main trixnity-bridge repo
fun BridgeConfig.Companion.fromConfig(config: ApplicationConfig) = BridgeConfig(
    homeserverDomain = config.property("homeserverDomain").getString(),
    botLocalpart = config.property("botLocalpart").getString(),
    puppetPrefix = config.property("puppetPrefix").getString(),
    roomAliasPrefix = config.property("roomAliasPrefix").getString(),
    provisioning = Provisioning(
        whitelist = config.property("provisioning.whitelist").getList().map {
            Regex(it)
        },
        blacklist = config.property("provisioning.blacklist").getList().map {
            Regex(it)
        }
    ),
    presence = Presence(
        remote = config.property("presence.remote").getString().toBoolean(),
        local = config.property("presence.local").getString().toBoolean()
    )
)