package org.cloudfoundry.promregator.scraping

import io.netty.channel.ChannelOption
import io.netty.handler.timeout.ReadTimeoutHandler
import io.netty.handler.timeout.WriteTimeoutHandler
import mu.KotlinLogging
import org.cloudfoundry.promregator.authentication.AuthenticationService
import org.cloudfoundry.promregator.config.Target
import org.cloudfoundry.promregator.discovery.CFMultiDiscoverer
import org.cloudfoundry.promregator.scanner.Instance
import org.cloudfoundry.promregator.scanner.ResolvedTarget
import org.springframework.http.MediaType
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono
import reactor.core.publisher.Mono
import reactor.netty.Connection
import reactor.netty.http.client.HttpClient
import reactor.netty.tcp.TcpClient
import java.time.Duration


private val logger = KotlinLogging.logger { }

@RestController
class ScrapingController(
        val discoverer: CFMultiDiscoverer,
        val authService: AuthenticationService,
        val webClient: WebClient.Builder
) {

    @GetMapping(value = ["/v2/singleTargetScraping/{appName}/{instanceNumber}/{hash}"], produces = [MediaType.TEXT_PLAIN_VALUE])
    fun scrape(@PathVariable appName: String,
               @PathVariable instanceNumber: String,
               @PathVariable hash: Int): Mono<String> {
        return this.discoverer.discover().flatMap { discoveredInstances: Map<Int, Instance> ->
            val instance = discoveredInstances[hash]
                    ?: return@flatMap Mono.error<String>(RuntimeException("Unable to locate instance appName:$appName instanceNumber:$instanceNumber"))

            val authEnricher = authService.getAuthenticationEnricherById(instance.target.originalTarget.authenticatorId)

            logger.info { "Scraping $instance" }

            val tcpClient = TcpClient.create()
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 3000) // Connection Timeout
                    .doOnConnected { connection: Connection ->
                        connection.addHandlerLast(ReadTimeoutHandler(20)) // Read Timeout
                                .addHandlerLast(WriteTimeoutHandler(20)) // Write Timeout
                    }

            webClient
                    .baseUrl(instance.accessUrl ?: "")
                    .apply(authEnricher.lookupEnrichAuthentication())
                    .defaultHeader("X-CF-APP-INSTANCE", "${instance.applicationId}:${instance.instanceNumber}")
                    .clientConnector(ReactorClientHttpConnector(HttpClient.from(tcpClient)))
                    .build()
                    .get()
                    .retrieve()
                    .onStatus({ it.isError }, { clientError ->
                        Mono.error(RuntimeException(
                                "Received client error with status code ${clientError.statusCode()} " +
                                        "from url ${instance.accessUrl}"))
                    })
                    .bodyToMono<String>()
                    .timeout(Duration.ofSeconds(300), Mono.just("#Timed out waiting for response"))
        }
    }

}