package org.cloudfoundry.promregator.scraping

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.netty.channel.ChannelOption
import io.netty.handler.timeout.ReadTimeoutHandler
import io.netty.handler.timeout.WriteTimeoutHandler
import mu.KotlinLogging
import org.cloudfoundry.promregator.authentication.AuthenticationService
import org.cloudfoundry.promregator.config.ScrapeTarget
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
import java.util.*


private val logger = KotlinLogging.logger {  }

@RestController
class ScrapingController(
        val mapper: ObjectMapper,
        val authService: AuthenticationService,
        val webClient: WebClient.Builder
) {

    @GetMapping(value=["/v2/singleTargetScraping/{target}"], produces = [MediaType.TEXT_PLAIN_VALUE])
    fun scrape(@PathVariable target: String): Mono<String> {
        val scrapeTarget = mapper.readValue<ScrapeTarget>(String(Base64.getDecoder().decode(target)))

        val authEnricher = authService.getAuthenticationEnricherById(scrapeTarget.authId)

        logger.info { "Scraping $scrapeTarget" }

        val tcpClient = TcpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 1000) // Connection Timeout
                .doOnConnected { connection: Connection ->
                    connection.addHandlerLast(ReadTimeoutHandler(10)) // Read Timeout
                            .addHandlerLast(WriteTimeoutHandler(10)) // Write Timeout
                }

        return webClient
                .baseUrl(scrapeTarget.scrapeUrl)
                .apply(authEnricher.lookupEnrichAuthentication())
                .defaultHeader("X-CF-APP-INSTANCE", "${scrapeTarget.applicationId}:${scrapeTarget.instanceNumber}")
                .clientConnector(ReactorClientHttpConnector(HttpClient.from(tcpClient)))
                .build()
                .get()
                .retrieve()
                .onStatus({it.isError}, {clientError -> Mono.error(RuntimeException(
                        "Received client error with status code ${clientError.statusCode()} " +
                        "from url ${scrapeTarget.scrapeUrl}"))})
                .bodyToMono<String>()
                .timeout(Duration.ofSeconds(300), Mono.just("#Timed out waiting for response"))
    }

}