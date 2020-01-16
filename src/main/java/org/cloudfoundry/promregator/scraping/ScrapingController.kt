package org.cloudfoundry.promregator.scraping

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import mu.KotlinLogging
import org.cloudfoundry.promregator.authentication.AuthenticationService
import org.cloudfoundry.promregator.config.ScrapeTarget
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono
import reactor.core.publisher.Mono
import java.lang.RuntimeException
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
        return webClient
                .baseUrl(scrapeTarget.scrapeUrl)
                .apply(authEnricher.lookupEnrichAuthentication())
                .build()
                .get()
                .retrieve()
                .onStatus({it.isError}, {clientError -> Mono.error(RuntimeException(
                        "Received client error with status code ${clientError.statusCode()} " +
                        "from url ${scrapeTarget.scrapeUrl}"))})
                .bodyToMono<String>()

    }

}