package ru.herobrine1st.matrix.bridge.telegram.module

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.config.ApplicationConfig
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import net.folivo.trixnity.applicationserviceapi.server.matrixApplicationServiceApiServer
import net.folivo.trixnity.applicationserviceapi.server.matrixApplicationServiceApiServerRoutes
import net.folivo.trixnity.clientserverapi.client.MatrixClientServerApiClientImpl
import net.folivo.trixnity.core.model.UserId
import net.folivo.trixnity.core.serialization.events.DefaultEventContentSerializerMappings
import ru.herobrine1st.matrix.bridge.config.BridgeConfig
import ru.herobrine1st.matrix.bridge.config.BridgeConfig.Presence
import ru.herobrine1st.matrix.bridge.config.BridgeConfig.Provisioning
import ru.herobrine1st.matrix.bridge.telegram.TelegramWorker
import ru.herobrine1st.matrix.bridge.telegram.createRepository
import ru.herobrine1st.matrix.bridge.telegram.RemoteIdToMatrixMapperImpl
import ru.herobrine1st.matrix.bridge.telegram.value.TelegramActorData
import ru.herobrine1st.matrix.bridge.telegram.value.TelegramActorId
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

    monitor.subscribe(ApplicationStarted) {
        runBlocking {
            worker.createAppServiceBot()
        }
        // TODO replace with AS bot commands
        launch {
            val configActors = environment.config.configList("telegram.actors")
                .associate {
                    Pair(
                        TelegramActorId(it.property("id").getString().toLong()),
                        TelegramActorData(token = it.property("token").getString(), admin = UserId(it.property("admin").getString()))
                    )
                }
            if (configActors.isEmpty()) return@launch
            val dbActors = actorProvisionRepository.getAllActors()
                .associate { (id, data) -> id to data }
            // remove excess actors
            (dbActors.keys - configActors.keys).forEach {
                log.info("Removing actor $it as removed from configuration")
                actorProvisionRepository.remoteActor(it)
            }
            // add actors from configuration
            (configActors - dbActors.keys).forEach {(id, data) ->
                log.info("Adding actor $id to database")
                actorProvisionRepository.addActor(id, data)
            }
            // update existing actors
            dbActors.keys.intersect(configActors.keys).forEach { id ->
                // SAFETY: id is contained in both maps due to intersect
                if(dbActors[id]!! != configActors[id]!!) {
                    log.info("Updating actor $it")
                    actorProvisionRepository.updateActor(id, configActors[id]!!)
                }
            }
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