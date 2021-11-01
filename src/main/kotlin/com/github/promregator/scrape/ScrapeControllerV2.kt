package com.github.promregator.scrape

import com.github.promregator.auth.AuthenticatorService
import io.netty.channel.ChannelOption
import io.netty.handler.timeout.ReadTimeoutHandler
import io.netty.handler.timeout.WriteTimeoutHandler
import io.prometheus.client.exporter.common.TextFormat
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.runBlocking
import com.github.promregator.discovery.DiscoveryService
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono
import reactor.core.publisher.Mono
import reactor.netty.http.client.HttpClient
import reactor.netty.tcp.TcpClient
import java.time.Duration

private val log = mu.KotlinLogging.logger { }

@RestController
class ScrapeControllerV2(
        private val discoverer: DiscoveryService,
        private val webClientBuilder: WebClient.Builder,
        private val authService: AuthenticatorService,
) {
    private val tcpClient = TcpClient.create()
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 3000) // Connection Timeout
            .doOnConnected { connection ->
                connection.addHandlerLast(ReadTimeoutHandler(20)) // Read Timeout
                        .addHandlerLast(WriteTimeoutHandler(20)) // Write Timeout
            }
    private val webClient: WebClient = webClientBuilder
                        .clientConnector(ReactorClientHttpConnector(HttpClient.from(tcpClient)))
                        .build()

    @GetMapping(value = ["/v2/singleTargetMetrics/{appId}/{instanceNumber}"], produces = [TextFormat.CONTENT_TYPE_004])
    fun scrape(@PathVariable appId: String,
                       @PathVariable instanceNumber: String): String = runBlocking {
        val instance = discoverer.discoverCached()
                .firstOrNull { it.applicationId == appId }
                ?: throw RuntimeException("No application with id $appId found")

        val authEnricher = authService.getAuthenticationEnricherById(instance.target.originalTarget?.authenticatorId)
        log.debug { "Scraping $instance" }

        webClient
                .get()
                .uri(instance.accessUrl)
                .header("X-CF-APP-INSTANCE", "${instance.applicationId}:${instance.instanceNumber}")
                .headers { authEnricher.enrichWithAuthentication(it) }
                .retrieve()
                .onStatus({ it.isError }, { clientError ->
                    Mono.error(RuntimeException(
                            "Received client error with status code ${clientError.statusCode()} " +
                                    "from url ${instance.accessUrl}"))
                })
                .bodyToMono<String>()
                .timeout(Duration.ofSeconds(300), Mono.just("Timed out waiting for response"))
                .awaitSingle()
    }
}
